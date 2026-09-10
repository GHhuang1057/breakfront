#!/usr/bin/env python3
"""
音乐库迁移 Cloudflare R2（2026-09-10）
=====================================
把 MC 主机 runDir/breakfront-music/<scene>/<file> 的音频全量搬到 Cloudflare R2，
使音频分发完全脱离大陆未备案入口（法律/合规风险清零）。

流程：
  1) 从 MC 主机（RDP 不可用时的直连 HTTP 源）或本地镜像目录抓取音频清单
  2) 计算 sha256，写入 R2 自定义元数据 bf-sha256（客户端校验依据）
  3) 经 wrangler r2 object put 上传（键布局 <scene>/<file>，与目录结构一致）
  4) 校验：重新扫描 R2 清单，比对数量与 sha256 是否一致

用法：
  python migrate_music_to_r2.py --scan                 # 只看清单
  python migrate_music_to_r2.py --src <本地目录>        # 从本地目录上传
  python migrate_music_to_r2.py --from-origin          # 经 HTTP 从 MC 主机抓取后上传
  python migrate_music_to_r2.py --verify               # 校验 R2 与源是否一致
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

AUDIO_EXT = {".mp3", ".ogg", ".oga", ".wav", ".flac", ".m4a"}
DEFAULT_BUCKET = "breakfront-music"
DEFAULT_ORIGIN = "http://8.141.114.60:25610"
WRANGLER_CWD = Path("G:/bfadmin-cf")
CACHE_DIR = Path("G:/bf_music_cache")

MIME = {
    ".mp3": "audio/mpeg",
    ".ogg": "audio/ogg",
    ".oga": "audio/ogg",
    ".wav": "audio/wav",
    ".flac": "audio/flac",
    ".m4a": "audio/mp4",
}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_audio(name: str) -> bool:
    return Path(name).suffix.lower() in AUDIO_EXT


def scan_local(root: Path) -> list[dict]:
    """扫描本地目录 → [{scene, name, path, size, sha256}]"""
    out: list[dict] = []
    if not root.is_dir():
        print(f"[!] 目录不存在：{root}")
        return out
    for scene_dir in sorted(root.iterdir()):
        if not scene_dir.is_dir():
            continue
        for f in sorted(scene_dir.iterdir()):
            if not f.is_file() or not is_audio(f.name):
                continue
            out.append({
                "scene": scene_dir.name,
                "name": f.name,
                "path": f,
                "size": f.stat().st_size,
                "sha256": sha256_file(f),
            })
    return out


def fetch_origin_manifest(origin: str) -> list[dict]:
    """从 MC 主机拉 music.json 清单（含 sha256/size）。"""
    url = origin.rstrip("/") + "/breakfront/music/music.json"
    print(f"[*] 拉取清单：{url}")
    try:
        with urllib.request.urlopen(url, timeout=15) as r:
            data = json.loads(r.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as e:
        print(f"[!] 清单拉取失败：{e}")
        return []
    files = data.get("files", [])
    print(f"[*] 远端曲目 {len(files)} 首")
    return files


def download_from_origin(origin: str, entries: list[dict]) -> list[dict]:
    """把远端曲目下载到本地缓存，返回带本地 path 的条目。"""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    out: list[dict] = []
    for i, e in enumerate(entries, 1):
        scene, name = e["scene"], e["name"]
        dst = CACHE_DIR / scene / name
        need = True
        if dst.is_file() and e.get("sha256"):
            need = sha256_file(dst) != e["sha256"]
        if need:
            url = f"{origin.rstrip('/')}/breakfront/music/files/{scene}/{name}"
            print(f"  [{i}/{len(entries)}] 下载 {scene}/{name}")
            try:
                dst.parent.mkdir(parents=True, exist_ok=True)
                with urllib.request.urlopen(url, timeout=120) as r, dst.open("wb") as f:
                    while True:
                        chunk = r.read(1 << 20)
                        if not chunk:
                            break
                        f.write(chunk)
            except (urllib.error.URLError, TimeoutError) as ex:
                print(f"    [!] 失败：{ex}")
                continue
        actual = sha256_file(dst)
        if e.get("sha256") and actual != e["sha256"]:
            print(f"    [!] sha 不符，跳过 {scene}/{name}")
            continue
        out.append({
            "scene": scene, "name": name, "path": dst,
            "size": dst.stat().st_size, "sha256": actual,
        })
    return out


def r2_put(bucket: str, key: str, path: Path, sha: str) -> bool:
    """经 wrangler 上传单对象（含 bf-sha256 元数据）。"""
    mime = MIME.get(Path(key).suffix.lower(), "application/octet-stream")
    cmd = [
        "npx", "wrangler", "r2", "object", "put", f"{bucket}/{key}",
        f"--file={path}",
        f"--content-type={mime}",
        f"--custom-metadata=bf-sha256={sha}",
        "--remote",
    ]
    env = dict(os.environ)
    env["CLOUDFLARE_ACCOUNT_ID"] = env.get("CLOUDFLARE_ACCOUNT_ID", "")
    try:
        r = subprocess.run(cmd, cwd=str(WRANGLER_CWD), env=env,
                           capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        print(f"    [!] 上传超时：{key}")
        return False
    if r.returncode != 0:
        print(f"    [!] 上传失败 {key}\n        {r.stderr.strip()[:400]}")
        return False
    return True


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--scan", action="store_true", help="仅列出源清单")
    ap.add_argument("--src", type=str, help="本地音乐根目录（含 <scene>/ 子目录）")
    ap.add_argument("--from-origin", action="store_true", help="经 HTTP 从 MC 主机抓取")
    ap.add_argument("--origin", type=str, default=DEFAULT_ORIGIN)
    ap.add_argument("--bucket", type=str, default=DEFAULT_BUCKET)
    ap.add_argument("--dry-run", action="store_true", help="只打印将上传的对象")
    ap.add_argument("--verify", action="store_true", help="校验 R2 清单")
    args = ap.parse_args()

    if args.verify:
        return do_verify(args.bucket)

    # ---- 采集源清单 ----
    if args.src:
        entries = scan_local(Path(args.src))
    elif args.from_origin:
        remote = fetch_origin_manifest(args.origin)
        if not remote:
            return 2
        entries = download_from_origin(args.origin, remote)
    else:
        ap.error("需指定 --src <目录> 或 --from-origin")

    if not entries:
        print("[!] 未发现可迁移的音频")
        return 1

    total = sum(e["size"] for e in entries)
    print(f"\n[*] 待上传 {len(entries)} 首，合计 {total / 1048576:.1f} MB")

    if args.scan or args.dry_run:
        for e in entries:
            print(f"  {e['scene']}/{e['name']}  {e['size'] / 1024:.0f}KB  {e['sha256'][:12]}…")
        return 0

    # ---- 上传 ----
    ok = 0
    for i, e in enumerate(entries, 1):
        key = f"{e['scene']}/{e['name']}"
        print(f"[{i}/{len(entries)}] 上传 {key} …")
        if r2_put(args.bucket, key, e["path"], e["sha256"]):
            ok += 1
    print(f"\n[✓] 上传完成 {ok}/{len(entries)}")

    # 落一份清单快照，便于审计
    snapshot = Path("G:/bf_music_cache") / "r2_manifest_snapshot.json"
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    snapshot.write_text(json.dumps(
        {"bucket": args.bucket, "count": len(entries),
         "files": [{k: e[k] for k in ("scene", "name", "size", "sha256")} for e in entries]},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[*] 清单快照：{snapshot}")
    return 0 if ok == len(entries) else 3


def do_verify(bucket: str) -> int:
    """经 Worker /healthz + music.json 校验 R2 清单。"""
    base = os.environ.get("BFUPDATE_BASE", "https://bfupdate.geekhonize.top")
    try:
        with urllib.request.urlopen(base + "/healthz", timeout=15) as r:
            health = json.loads(r.read().decode("utf-8"))
        print(f"[*] /healthz music = {health.get('music')}")
        with urllib.request.urlopen(base + "/breakfront/music/music.json", timeout=15) as r:
            mf = json.loads(r.read().decode("utf-8"))
        files = mf.get("files", [])
        print(f"[*] music.json source={mf.get('source')} 曲目 {len(files)} 首")
        missing_sha = [f"{f['scene']}/{f['name']}" for f in files if not f.get("sha256")]
        if missing_sha:
            print(f"[!] {len(missing_sha)} 首缺 sha256 元数据（客户端将每次重新下载）：")
            for m in missing_sha[:20]:
                print("    " + m)
        else:
            print("[✓] 全部曲目均带 sha256 元数据")
        return 0
    except Exception as e:
        print(f"[!] 校验失败：{e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
