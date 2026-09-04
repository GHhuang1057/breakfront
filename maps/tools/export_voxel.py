#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
maps/tools/export_voxel.py — 把「高架走廊」生成结果导出为体素地图 JSON（供服务端实建）。
输出：k[]/h[] 两个整数数组（长度 W*H，索引 = z*W + x），代码表：
  0=地面(草地)  1=道路  2=广场  3=掩体  4=高架路面带  5=高架立柱  6=楼宇(h 生效)

用法：python export_voxel.py --seed 42 --out core/src/main/resources/breakfront/maps/viaduct.json
"""
import argparse
import json
from pathlib import Path

from citygen_viaduct_demo import City, W, H, VIADUCT_Z, VIADUCT_W


def code_for(city: City, x: int, z: int) -> tuple[int, int]:
    is_viaduct = (x, z) in city.viaduct_cols
    h, _ = city.building_at(x, z)
    base = city.cell.get((x, z), "ground")
    if h and not is_viaduct:
        return 6, h
    if is_viaduct:
        # 立柱在道路带两侧边缘（与 python 生成时一致：间距列 + 边沿）
        for (px, pz) in city.viaduct_pillars:
            if (px, pz) == (x, z):
                return 5, 0
        return 4, 0
    return {
        "ground": 0, "road": 1, "plaza": 2, "cover": 3,
    }.get(base, 0), 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", required=True, type=Path)
    args = ap.parse_args()

    city = City(args.seed)
    k, h = [], []
    for z in range(H):
        for x in range(W):
            ck, ch = code_for(city, x, z)
            k.append(ck)
            h.append(ch)
    data = {
        "meta": {"name": "viaduct", "width": W, "height": H,
                 "viaduct_z": VIADUCT_Z, "viaduct_width": VIADUCT_W,
                 "viaduct_road_y": 8, "seed": args.seed, "origin_x": 0, "origin_z": 0},
        "k": k,
        "h": h,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(data, separators=(",", ":")), encoding="utf-8")
    print(f"[export_voxel] wrote {args.out} ({len(k)} cells, "
          f"buildings={sum(1 for v in k if v == 6)}b / {len(k) - sum(1 for v in k if v == 6)}e)")


if __name__ == "__main__":
    main()
