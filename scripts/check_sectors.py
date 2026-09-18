#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BREAKFRONT 扇区配置校验器（breakfront/sectors.json）

为什么要有它：sectors.json 是 /bfs 编辑器产出、服务端启动装载的**唯一事实来源**。
换地图后若沿用上一张图的坐标，据点会落在未生成/无地形处（表现为「据点不渲染、出生走兜底」）。
本脚本在启动前/提交前把这类问题挡住。

用法：
    python3 scripts/check_sectors.py [sectors.json] [--spawn-x X --spawn-z Z] [--max-dist 400]

检查项：
  1. JSON 可解析、顶层为对象、version 存在
  2. sectors 为数组且非空；每个扇区有 name 与非空 zones
  3. zone：id 非空且**全图唯一**、x/z 有限、radius > 0
  4. spawns（可选）：attacker/defender/lobby 的 x/z 有限
  5. （给了 --spawn-x/--spawn-z 时）据点与世界出生点的距离，超过阈值告警 —— 用来抓「坐标属于上一张地图」
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys


def fail(msg: str) -> None:
    print("[ERROR] " + msg)


def warn(msg: str) -> None:
    print("[WARN ] " + msg)


def info(msg: str) -> None:
    print("[INFO ] " + msg)


def num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(float(v))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path", nargs="?", default="breakfront/sectors.json")
    ap.add_argument("--spawn-x", type=float, default=None, help="世界出生点 X（用于距离告警）")
    ap.add_argument("--spawn-z", type=float, default=None, help="世界出生点 Z（用于距离告警）")
    ap.add_argument("--max-dist", type=float, default=400.0, help="据点离出生点的告警距离（方块）")
    args = ap.parse_args()

    path = args.path
    if not os.path.isfile(path):
        fail("找不到文件: %s（服务器运行目录下的 breakfront/sectors.json）" % path)
        return 1

    try:
        with open(path, encoding="utf-8") as f:
            root = json.load(f)
    except Exception as e:
        fail("JSON 解析失败: %s" % e)
        return 1

    if not isinstance(root, dict):
        fail("顶层必须是 JSON 对象")
        return 1

    errors = 0
    warns = 0

    if "version" not in root:
        warn("缺少 version 字段（当前 SectorLayout.VERSION = 1）")
        warns += 1

    sectors = root.get("sectors")
    if not isinstance(sectors, list) or not sectors:
        fail("sectors 必须是非空数组")
        return 1

    seen_ids: set[str] = set()
    zone_total = 0

    for si, sec in enumerate(sectors, 1):
        if not isinstance(sec, dict):
            fail("扇区 #%d 不是对象" % si)
            errors += 1
            continue
        name = sec.get("name")
        if not isinstance(name, str) or not name.strip():
            warn("扇区 #%d 缺少 name，将回退为「扇区」" % si)
            warns += 1
        zones = sec.get("zones")
        if not isinstance(zones, list) or not zones:
            fail("扇区 #%d（%s）的 zones 必须是非空数组" % (si, name))
            errors += 1
            continue

        for zi, z in enumerate(zones, 1):
            zone_total += 1
            label = "扇区#%d 据点#%d" % (si, zi)
            if not isinstance(z, dict):
                fail("%s 不是对象" % label)
                errors += 1
                continue
            zid = z.get("id")
            if not isinstance(zid, str) or not zid.strip():
                fail("%s 缺少 id" % label)
                errors += 1
                continue
            if zid in seen_ids:
                fail("%s 的 id 重复: %s（据点 id 必须全图唯一，否则占点会串）" % (label, zid))
                errors += 1
            seen_ids.add(zid)

            for key in ("x", "z"):
                if not num(z.get(key)):
                    fail("%s (%s) 的 %s 不是有限数字: %r" % (label, zid, key, z.get(key)))
                    errors += 1

            r = z.get("radius", 6.0)
            if not num(r) or float(r) <= 0:
                fail("%s (%s) 的 radius 必须 > 0: %r" % (label, zid, r))
                errors += 1
                continue

            if args.spawn_x is not None and args.spawn_z is not None and num(z.get("x")) and num(z.get("z")):
                d = math.hypot(float(z["x"]) - args.spawn_x, float(z["z"]) - args.spawn_z)
                if d > args.max_dist:
                    warn("%s (%s) 距世界出生点 %.0f 方块（> %.0f）——若为换图后遗留下来的旧坐标，请用 /bfs 重划"
                         % (label, zid, d, args.max_dist))
                    warns += 1

    spawns = root.get("spawns")
    if isinstance(spawns, dict):
        for side in ("attacker", "defender", "lobby"):
            s = spawns.get(side)
            if s is None:
                continue
            if not isinstance(s, dict):
                fail("spawns.%s 必须是对象" % side)
                errors += 1
                continue
            for key in ("x", "z"):
                if not num(s.get(key)):
                    fail("spawns.%s 的 %s 不是有限数字: %r" % (side, key, s.get(key)))
                    errors += 1

    info("路径: %s" % path)
    info("扇区 %d 个 / 据点 %d 个" % (len(sectors), zone_total))
    if spawns:
        info("出生点: %s" % ", ".join(k for k in ("attacker", "defender", "lobby") if spawns.get(k)))
    else:
        info("出生点: 未配置（回退世界出生点 / spawn.lobby）")

    if errors:
        fail("校验未通过：%d 个错误、%d 个告警" % (errors, warns))
        return 1
    if warns:
        warn("校验通过（有 %d 个告警）" % warns)
    else:
        info("校验通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
