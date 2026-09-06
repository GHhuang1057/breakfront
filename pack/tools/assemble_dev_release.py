#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BREAKFRONT pack/tools — 开发版整合包组装器
================================================
在 CI（release-dev workflow）中执行：
  1) 解析第三方模组固定版本（Modrinth API，含必需依赖递归）
  2) 下载 jar 到暂存目录，连同 CI 构建出的 breakfront core/client jar
  3) 产出开发版「客户端 mods 包」zip + manifest.json（版本/来源/哈希清单）

产物：dist/breakfront-dev-client-mods-<sha>.zip
用法：
  python assemble_dev_release.py --sha <short> --core-jar <path> --client-jar <path> [--out dist]

说明：本包面向「开发版开箱」——直接把 mods 包丢进装好 Fabric Loader 0.19.5
的 .minecraft/mods 即可联调；正式对外发布（资产许可/渠道）由用户侧负责。
"""

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

MODRINTH = "https://api.modrinth.com/v2"
MC_VERSION = "1.21.1"
LOADER_VERSION = "0.19.5"

# 直接依赖（slug -> 期望版本号子串；None = 该版本下最新）
DIRECT = [
    ("fabric-api", None),
    ("tacz-refabricated", "1.21.1-0.7.0-forge1.1.8-hotfix"),
]

UA = {"User-Agent": "breakfront-dev-release-builder/0.1 (self-hosted)"}

# 性能优化模组版本钉（#44 批次），见 mod_pins.json（与本脚本同目录）
_PERF_PINS_PATH = Path(__file__).resolve().parent / "mod_pins.json"


def load_perf_pins() -> list[tuple[str, str | None]]:
    """读取 mod_pins.json，返回 [(slug, expect), ...]。文件缺失/解析失败则空列表。"""
    if not _PERF_PINS_PATH.exists():
        print(f"[warn] {_PERF_PINS_PATH.name} 缺失，跳过性能模组钉")
        return []
    try:
        data = json.loads(_PERF_PINS_PATH.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"[warn] 解析 {_PERF_PINS_PATH.name} 失败：{e}")
        return []
    pins = data.get("pins", [])
    out = []
    for p in pins:
        slug = p.get("slug")
        if not slug:
            continue
        out.append((slug, p.get("expect")))
    print(f"[pins] 载入性能模组钉 {len(out)} 项：{[s for s, _ in out]}]")
    return out


def http_get(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read()


def api(path: str):
    return json.loads(http_get(MODRINTH + path))


def pick_version(slug: str, expect: str | None) -> dict:
    """取该项目支持 MC 1.21.1 + Fabric 的版本；可指定期望版本子串。"""
    qs = urllib.parse.quote(json.dumps([MC_VERSION]))
    ql = urllib.parse.quote(json.dumps(["fabric"]))
    versions = api(f"/project/{slug}/version?game_versions={qs}&loaders={ql}")
    if not versions:
        raise SystemExit(f"[err] {slug}: 无 MC {MC_VERSION}+Fabric 版本")
    if expect:
        for v in versions:
            if v["version_number"].startswith(expect) or expect in v["version_number"]:
                return v
        raise SystemExit(f"[err] {slug}: 找不到期望版本 {expect}（现有：{[v['version_number'] for v in versions[:5]]}）")
    return versions[0]


def sha1_file(path: Path) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        return
    with open(dest, "wb") as f:
        f.write(http_get(url))


def sanitize(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9._+ -]", "_", name)


def resolve_mod(slug: str, seen: set, out: list[dict], stage: Path, expect: str | None = None) -> None:
    if slug in seen:
        return
    seen.add(slug)
    version = pick_version(slug, expect)
    project = api(f"/project/{slug}")
    # env 以 Modrinth 项目 client_side/server_side 为准（而非硬编码 both），
    # 服务端包据此过滤客户端专属模组（entityculling/immediatelyfast/motionblur 等）
    cs = project.get("client_side")
    ss = project.get("server_side")
    if cs in ("required", "optional") and ss in ("required", "optional"):
        env = "both"
    elif ss in ("required", "optional"):
        env = "server"
    elif cs in ("required", "optional"):
        env = "client"
    else:
        env = "unsupported"
    f0 = version["files"][0]
    fname = sanitize(f0["filename"])
    dest = stage / "mods" / fname
    if not dest.exists():
        print(f"[fetch] {slug} {version['version_number']} ({f0['url']})")
        download(f0["url"], dest)
    out.append({
        "slug": slug,
        "name": project["title"],
        "version": version["version_number"],
        "file": f"mods/{fname}",
        "sha1": sha1_file(dest),
        "url": f0["url"],
        "env": env,
    })
    # 必需依赖（mod 类型）递归
    for dep in version.get("dependencies", []):
        if dep.get("dependency_type") != "required":
            continue
        dep_id = dep.get("project_id")
        if not dep_id:
            continue
        try:
            p = api(f"/project/{dep_id}")
        except Exception:
            continue
        if p.get("project_type") != "mod":
            continue
        resolve_mod(p["slug"], seen, out, stage)


def build_zip(out_path: Path, stage: Path, manifest: dict) -> None:
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in (stage / "mods").glob("*.jar"):
            zf.write(f, f"mods/{f.name}")
        zf.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"[zip] {out_path} ({out_path.stat().st_size / 1024:.0f} KB)")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sha", required=True)
    ap.add_argument("--core-jar", required=True, type=Path)
    ap.add_argument("--client-jar", required=True, type=Path)
    ap.add_argument("--out", default="pack/dist")
    args = ap.parse_args()

    if not args.core_jar.exists() or not args.client_jar.exists():
        raise SystemExit(f"[err] 缺少构建产物：{args.core_jar} / {args.client_jar}")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = {
        "format": "breakfront-dev-pack",
        "build": f"dev-{args.sha}",
        "minecraft": MC_VERSION,
        "fabric_loader": LOADER_VERSION,
        "files": [],
    }

    with tempfile.TemporaryDirectory(prefix="bf-pack-") as td:
        stage = Path(td)
        files: list[dict] = []
        seen: set = set()

        # 自研模组（本地构建）
        for jar, env in ((args.core_jar, "both"), (args.client_jar, "client")):
            dest = stage / "mods" / sanitize(jar.name)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(jar.read_bytes())
            files.append({
                "slug": "breakfront" if env == "both" else "breakfront-client",
                "name": "Breakfront Core" if env == "both" else "Breakfront Client",
                "version": f"dev-{args.sha}",
                "file": f"mods/{dest.name}",
                "sha1": sha1_file(dest),
                "url": "local",
                "env": env,
            })

        # 第三方固定版本
        for slug, expect in DIRECT:
            resolve_mod(slug, seen, files, stage, expect)

        # 性能优化模组（#44 批次，版本钉见 mod_pins.json；复用同一下载/依赖解析 helper）
        # 注意：sodium/iris/tacz 既在 DIRECT 外，也不在此列，保持原样不被改动。
        for slug, expect in load_perf_pins():
            resolve_mod(slug, seen, files, stage, expect)

        manifest["files"] = files
        zname = f"breakfront-dev-client-mods-{args.sha}.zip"
        build_zip(out_dir / zname, stage, manifest)
        (out_dir / f"breakfront-dev-manifest-{args.sha}.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[done] 共 {len(manifest['files'])} 个模组")
    for f in manifest["files"]:
        print(f"  - {f['file']}  ({f['version']})")


if __name__ == "__main__":
    sys.exit(main())
