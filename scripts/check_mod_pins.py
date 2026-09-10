#!/usr/bin/env python3
"""模组版本钉体检：核对 mod_pins.json 中每个模组的 Sodium 版本约束是否自洽。

为什么需要它：Modrinth API 的 dependencies 只暴露 project_id，**不含版本范围**；
真正的约束（depends / breaks）写在 jar 内的 fabric.mod.json 里。因此必须下载 jar 才能
判断某模组是否与项目锁定的 Sodium 版本兼容 —— 这正是"装了却启动即崩"的根因所在
（实测：sodium-extra 0.9.3 与 RSO 2.2.3 要求 sodium>=0.8.12，moreculling 1.0.10
声明 breaks sodium<=0.6.13，而本项目因 Iris 1.21.1 仅 1.8.8 必须锁 Sodium 0.6.13）。

用法：
    python scripts/check_mod_pins.py                 # 全量体检
    python scripts/check_mod_pins.py --sodium 0.6.13 # 指定基准 Sodium 版本

jar 缓存于 pack/tools/.modjar_cache/，重复运行不会重复下载。
"""
from __future__ import annotations

import argparse
import io
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request
import zipfile

API = "https://api.modrinth.com/v2"
UA = {"User-Agent": "breakfront-modpin-check/1.0"}
ROOT = pathlib.Path(__file__).resolve().parent.parent
PINS = ROOT / "pack" / "tools" / "mod_pins.json"
CACHE = ROOT / "pack" / "tools" / ".modjar_cache"


def api_get(path: str, **params):
    q = urllib.parse.urlencode({k: json.dumps(v) for k, v in params.items()})
    url = f"{API}{path}?{q}" if q else f"{API}{path}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=45) as r:
        return json.load(r)


def parse_ver(s: str) -> tuple[int, ...]:
    """把版本串归一成数字元组：'>=0.8.12+mc1.21.1' -> (0, 8, 12)。"""
    s = str(s).split("+")[0]
    nums = re.findall(r"\d+", s)
    return tuple(int(x) for x in nums[:4]) if nums else (0,)


def ver_cmp(a: tuple[int, ...], b: tuple[int, ...]) -> int:
    n = max(len(a), len(b))
    aa = a + (0,) * (n - len(a))
    bb = b + (0,) * (n - len(b))
    return (aa > bb) - (aa < bb)


def satisfies(expr: str, target: str) -> bool | None:
    """判断 target 是否落在约束表达式描述的范围内；无法解析返回 None。

    支持：
      - '*' / ''      → 任意版本
      - '0.6.x' 等通配 → 视为区间 [0.6.0, 0.7.0)
      - 空格分隔的多个比较（如 '>=0.6.0 <0.9.0'）
      - 数组形式（Modrinth 偶见 depends 写成 ["0.6.x"]）
    """
    if isinstance(expr, (list, tuple)):
        return any(satisfies(str(x), target) is True for x in expr)
    expr = str(expr or "").strip().strip("[]").replace("'", "").replace('"', "")
    if expr in ("*", ""):
        return True
    t = parse_ver(target)

    # 通配形式 0.6.x → >=0.6.0 且 <0.7.0
    wild = re.fullmatch(r"(\d+(?:\.\d+)*)\.(?:x|X|\*)", expr)
    if wild:
        base = tuple(int(x) for x in wild.group(1).split("."))
        lo = base + (0,) * max(0, 4 - len(base))
        hi = base[:-1] + (base[-1] + 1,) if base else (1,)
        hi = hi + (0,) * max(0, 4 - len(hi))
        return ver_cmp(t, lo) >= 0 and ver_cmp(t, hi) < 0

    for part in expr.split():
        m = re.match(r"^(>=|<=|>|<|=)?(.+)$", part)
        if not m:
            return None
        op, val = m.group(1) or "=", m.group(2)
        v = parse_ver(val)
        c = ver_cmp(t, v)
        ok = {
            ">=": c >= 0,
            "<=": c <= 0,
            ">": c > 0,
            "<": c < 0,
            "=": c == 0,
        }.get(op)
        if ok is None:
            return None
        if not ok:
            return False
    return True


def download(url: str, cache_key: str) -> bytes:
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / cache_key
    if f.is_file():
        return f.read_bytes()
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        blob = r.read()
    f.write_bytes(blob)
    return blob


def find_version(slug: str, expect: str):
    """在 MC 1.21.1/Fabric 的版本列表中，按 version_number 子串匹配。"""
    versions = api_get(f"/project/{slug}/version",
                       game_versions=["1.21.1"], loaders=["fabric"])
    hit = [v for v in versions if expect in v["version_number"]]
    if not hit:
        return None
    return hit[0]


def cross_check(infos: dict[str, tuple[str, dict]]) -> list[str]:
    """两两交叉检查 depends / breaks —— 这是单看「各模组 vs sodium」时的盲区。

    实测教训：Sodium 0.6.13 声明 `breaks reeses-sodium-options <1.8.0`，
    而 RSO 1.8.0-beta.4 按语义化版本仍 <1.8.0（预发布小于正式版）→ 二者冲突。
    只看「RSO 对 sodium 的 depends」是发现不了的，必须双向检查。

    infos: modid -> (filename, fabric.mod.json)
    """
    problems = []
    for mid, (fn, mj) in infos.items():
        mine = str(mj.get("version", "?"))
        for key in ("depends", "breaks"):
            for other, expr in (mj.get(key) or {}).items():
                if other not in infos or other == mid:
                    continue
                ofn, omj = infos[other]
                over = str(omj.get("version", "?"))
                ok = satisfies(expr, over)
                if ok is None:
                    continue
                # depends 不满足 → 冲突；breaks 命中（对方落在排除区间）→ 冲突
                bad = (ok is False) if key == "depends" else (ok is True)
                if bad:
                    problems.append(
                        f"{fn} ({mine}) {key} {other}={expr} —— 但已装 {other} {over}")
    return problems


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sodium", default="0.6.13", help="基准 Sodium 版本（默认 0.6.13）")
    args = ap.parse_args()
    target = args.sodium

    pins = json.loads(PINS.read_text(encoding="utf-8"))["pins"]
    print(f"基准 Sodium = {target}（jar 内 fabric.mod.json 的 depends/breaks 实测）")
    print("=" * 96)

    problems = []
    infos: dict[str, tuple[str, dict]] = {}
    for p in pins:
        slug, expect = p["slug"], p["expect"]
        try:
            v = find_version(slug, expect)
        except Exception as e:  # noqa: BLE001
            print(f"  {slug:<26} {expect:<30} ✗ 查询失败: {e}")
            continue
        if not v:
            print(f"  {slug:<26} {expect:<30} ✗ 未找到该版本")
            problems.append(f"{slug}: 版本 {expect} 在 Modrinth 上未找到")
            continue
        files = [f for f in v.get("files", []) if f.get("primary")] or v.get("files", [])
        if not files:
            print(f"  {slug:<26} {expect:<30} ✗ 无下载文件")
            continue
        fname = files[0]["filename"]
        try:
            blob = download(files[0]["url"], fname)
            with zipfile.ZipFile(io.BytesIO(blob)) as z:
                with z.open("fabric.mod.json") as fh:
                    mj = json.load(fh)
        except Exception as e:  # noqa: BLE001
            print(f"  {slug:<26} {expect:<30} ✗ jar 读取失败: {e}")
            continue
        infos[mj.get("id") or slug] = (fname, mj)

        if slug == "sodium":
            print(f"  {slug:<26} {expect:<30} 基准本体（跳过单向检查）")
            continue

        notes, bad = [], False
        for key in ("depends", "breaks"):
            for name, ver in (mj.get(key) or {}).items():
                # 只关心对 **sodium 本体** 的约束；sodium-extra 等是别的模组，
                # 不能拿 sodium 的版本来套它们的约束（那部分交给 cross_check）。
                if name.lower() != "sodium":
                    continue
                ok = satisfies(ver, target)
                tag = "depends" if key == "depends" else "breaks"
                if ok is None:
                    notes.append(f"? {tag} sodium={ver}")
                    continue
                conflict = (ok is False) if key == "depends" else (ok is True)
                if conflict:
                    bad = True
                    notes.append(f"✗ {tag} sodium={ver}")
                else:
                    notes.append(f"{tag} sodium={ver}")
        verdict = "✗ 冲突" if bad else "✓ 兼容"
        detail = " | ".join(notes) if notes else "(无 sodium 约束)"
        print(f"  {slug:<26} {expect:<30} {verdict}  {detail}")
        if bad:
            problems.append(f"{slug} {expect}: " + "; ".join(n for n in notes if n.startswith("✗")))

    # ---- 交叉检查：所有已装模组两两之间的 depends / breaks ----
    print("=" * 96)
    print(f"交叉约束检查（{len(infos)} 个模组两两之间）")
    cross = cross_check(infos)
    if cross:
        for x in cross:
            print("  ✗ " + x)
        problems.extend(cross)
    else:
        print("  ✓ 无跨模组冲突")

    print("=" * 96)
    if problems:
        print(f"发现 {len(problems)} 处问题：")
        for x in problems:
            print("  - " + x)
        return 1
    print(f"全部模组自洽 ✓（基准 Sodium {target}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
