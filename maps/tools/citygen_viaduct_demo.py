#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BREAKFRONT maps/tools — 「高架走廊 Viaduct」微街区原型生成器 v0.1
====================================================================
用途：以纯 Python 离线生成一张 96×96 的城市街区原型，输出：
  1) layout.json          —— 机器可读布局（路网/楼宇足印/广场/高架），未来喂给对局扇区配置
  2) preview_topdown.png  —— 顶视图（区域色 + 楼高色调）
  3) preview_iso.png      —— 等距俯视（柱状挤出，示意立体感）

原则（charter §7 地图工作流）：一切从可复现的「参数 + 随机种子」出发，
同一 seed 永远生成同一座城；后续加 NBT 导出即可直通 WorldEdit/竞技场装载。

运行：
  python maps/tools/citygen_viaduct_demo.py [--seed 42] [--out maps/viaduct/proto]

占位风格说明：混凝土+玻璃的城市攻防，两条主干道十字贯穿、
中部下沉广场、一条横贯东西的高架路（含立柱与两侧匝道），
攻方推进方向沿 x 轴由西向东（后续接扇区线）。
"""

import argparse
import json
import math
import random
from pathlib import Path

# ---------------- 配置（参数化入口，未来读 config.py / YAML） ----------------
W, H = 96, 96                     # 微街区范围（方块）
GROUND_Y = 1                      # 地面层

# 街道
AVENUE_V_X = (40, 47)             # 南北向主干道（x 区间, 宽 8）
AVENUE_H_Z = (48, 55)             # 东西向主干道（z 区间, 宽 8）
ALLEY_W = 3                       # 次街宽度
BLOCK_SIDE = 10                   # 街块边长（足印格）

# 高架（东西走向，横贯 x）
VIADUCT_Z = 24                    # 高架中心 z
VIADUCT_W = 5                     # 路面宽
VIADUCT_Y = 8                     # 路面层
COLUMN_GAP = 8

# 楼宇
MIN_H, MAX_H = 4, 13
TALL_H = 10                       # > = 高层（塔楼）

# ---------------- 区块类型与配色（顶视图） ----------------
COL = {
    "ground":    (154, 162, 100),  # 街区底色（更亮让楼更突出）
    "road":      (40, 42, 48),
    "lane":      (228, 214, 150),  # 车道虚线
    "plaza":     (200, 176, 128),  # 下沉广场铺地
    "cover":     (120, 110, 84),   # 低掩体
    "viaduct":   (228, 228, 232),  # 高架路面
    "column":    (96, 98, 106),
    "b_low":     (62, 78, 96),
    "b_mid":     (44, 60, 82),
    "b_tall":    (28, 40, 60),
    "edge":      (20, 24, 32),
}
ISO_SHADE = {                     # 等距：亮(top) / 侧A / 侧B
    "b_low":  ((150, 160, 175), (82, 96, 112), (66, 78, 94)),
    "b_mid":  ((180, 190, 205), (110, 122, 138), (88, 100, 116)),
    "b_tall": ((120, 135, 165), (62, 76, 100), (46, 58, 82)),
    "viaduct":((230, 230, 235), (190, 190, 196), (170, 170, 178)),
    "column": ((140, 142, 150), (96, 98, 106), (84, 86, 94)),
    "plaza":  ((190, 174, 138), (148, 132, 100), (128, 112, 86)),
}


# ---------------- 地形/街区生成 ----------------
class City:
    def __init__(self, seed: int):
        self.seed = seed
        self.rng = random.Random(seed)
        self.cell = {}            # (x, z) -> 地面 kind
        self.blocks = []          # 楼宇列: dict(x,z,h,color_key,foot_edge)
        self.viaduct_cols = {}    # (x, z) -> True 高架占位（z 带）
        self.viaduct_pillars = [] # [(x, z)] 立柱
        self._lay()

    def _lay(self):
        # 1) 地面与路网
        for z in range(H):
            for x in range(W):
                kind = "ground"
                if AVENUE_V_X[0] <= x <= AVENUE_V_X[1] or AVENUE_H_Z[0] <= z <= AVENUE_H_Z[1]:
                    kind = "road"
                # 高架投影下仍是道路
                if VIADUCT_Z - VIADUCT_W // 2 <= z <= VIADUCT_Z + VIADUCT_W // 2:
                    kind = "road"
                    self.viaduct_cols[(x, z)] = True
                self.cell[(x, z)] = kind

        # 广场（东西主干道与南北主干道交汇核心）
        for z in range(AVENUE_H_Z[0] + 2, AVENUE_H_Z[1] - 2):
            for x in range(AVENUE_V_X[0] + 2, AVENUE_V_X[1] - 2):
                if self.cell[(x, z)] == "road":
                    self.cell[(x, z)] = "plaza"

        # 2) 街块划分（跳过主干道），每个街块塞 1~3 栋楼 + 矮掩体
        block_rng = random.Random(self.seed * 31 + 7)
        for bz in range(4, H - 4, BLOCK_SIDE + ALLEY_W):
            for bx in range(4, W - 4, BLOCK_SIDE + ALLEY_W):
                zone = [(x, z) for x in range(bx, min(bx + BLOCK_SIDE, W - 1))
                        for z in range(bz, min(bz + BLOCK_SIDE, H - 1))]
                zone = [p for p in zone if self.cell[p] in ("ground", "road")]
                if not zone:
                    continue
                self._build_block(zone, block_rng)

        # 3) 高架立柱与两侧落地（z 边缘垂下来的“墙裙”已在渲染处理，立柱真实）
        for x in range(4, W - 4):
            if x % COLUMN_GAP in (0, COLUMN_GAP // 2):
                for z in (VIADUCT_Z - VIADUCT_W // 2 - 1, VIADUCT_Z + VIADUCT_W // 2 + 1):
                    self.viaduct_pillars.append((x, z))

    def _build_block(self, zone, rng):
        towers = rng.randint(1, 3)
        # 足印：从街区 zone 中随机挑塔楼占地（简化：分割为子块）
        for _ in range(towers):
            if not zone:
                break
            bx = rng.choice(zone)
            # 简单矩形占地 6×5，中心 bx
            h = rng.randint(MIN_H, MAX_H)
            key = "b_tall" if h >= TALL_H else ("b_mid" if h >= 7 else "b_low")
            footprint = []
            for dz in range(-2, 3):
                for dx in range(-3, 4):
                    p = (bx[0] + dx, bx[1] + dz)
                    if p in self.cell and self.cell[p] in ("ground", "road") and p not in [q[0] for q in footprint]:
                        footprint.append((p, h, key))
            for item in footprint:
                self.cell[item[0]] = "ground"  # 楼底
            self.blocks.extend(footprint)
            # 占掉已用格避免叠楼
            zone = [p for p in zone if p not in [f[0] for f in footprint]]

        # 街区剩余角落撒矮掩体
        for _ in range(rng.randint(0, 3)):
            if not zone:
                break
            p = rng.choice(zone)
            self.cell[p] = "cover"
            zone = [q for q in zone if q != p]

    def top_color(self, x, z):
        base = self.cell.get((x, z), "ground")
        # 高架优先（高于路面）
        if (x, z) in self.viaduct_cols:
            return COL["viaduct"]
        if base == "road":
            if x == AVENUE_V_X[0] + 1 or x == AVENUE_V_X[1] - 1 \
                    or z == AVENUE_H_Z[0] + 1 or z == AVENUE_H_Z[1] - 1:
                if (x + z) % 8 < 2:
                    return COL["lane"]
            return COL["road"]
        return COL.get(base, COL["ground"])

    def building_at(self, x, z):
        for bx, bh, key in self.blocks:
            if (x, z) == (bx[0], bx[1]):
                return bh, key
        return None, None


# ---------------- 渲染 ----------------
def render_topdown(city: City, out: Path):
    from PIL import Image
    cell_px = 5
    img = Image.new("RGB", (W * cell_px, H * cell_px))
    px = img.load()
    for z in range(H):
        for x in range(W):
            c = city.top_color(x, z)
            h, key = city.building_at(x, z)
            if h:
                f = 1.0 + (h - MIN_H) * 0.04  # 楼越高越深，色阶更明显
                c = tuple(min(255, int(v * f)) for v in c)
            for dy in range(cell_px):
                for dx in range(cell_px):
                    px[x * cell_px + dx, z * cell_px + dy] = c
    # 给楼体加一圈深色描边，提升辨识
    edge = COL["edge"]
    for z in range(H):
        for x in range(W):
            h, _ = city.building_at(x, z)
            if not h:
                continue
            for (dx, dy) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                nx, nz = x + dx, z + dy
                nh, _ = city.building_at(nx, nz)
                if not nh:
                    for ey in range(cell_px):
                        for ex in range(cell_px):
                            exx = nx * cell_px + ex
                            eyy = nz * cell_px + ey
                            if 0 <= exx < img.width and 0 <= eyy < img.height:
                                px[exx, eyy] = edge
    img.save(out)


def render_iso(city: City, out: Path, unit=6, cell_scale=3):
    """柱状挤出等距视图：每根“楼/设施列”画成顶+双侧的柱。"""
    from PIL import Image, ImageDraw

    # iso 投影
    def proj(x, z, y):
        sx = (x - z) * unit
        sy = (x + z) * unit // 2 - y * cell_scale
        return sx, sy

    # 坐标范围
    xs = [proj(x, z, 0)[0] for x in (0, W - 1) for z in (0, H - 1)]
    ys = [proj(x, z, 0)[1] for x in (0, W - 1) for z in (0, H - 1)]
    max_y = max([h for _, h, _ in city.blocks], default=0) + VIADUCT_Y + 2
    lo_x, hi_x = min(xs), max(xs)
    lo_y, hi_y = min(ys), max(ys) - (max_y + 2) * cell_scale
    pad = 8
    off_x, off_y = -lo_x + pad, -lo_y + pad
    Wd, Hd = hi_x - lo_x + unit * 2 + pad * 2, hi_y - lo_y + pad * 2 + (max_y + 2) * cell_scale

    img = Image.new("RGB", (Wd, Hd), (16, 16, 20))
    dr = ImageDraw.Draw(img)
    u = unit

    def quad(pts, fill):
        dr.polygon([(off_x + p[0], off_y + p[1]) for p in pts], fill=fill)

    # 先地面薄板
    for z in range(H):
        for x in range(W):
            top = proj(x, z, 0)
            corners = [
                proj(x, z, 0), proj(x + 1, z, 0), proj(x + 1, z + 1, 0), proj(x, z + 1, 0)]
            base_c = COL.get(city.cell.get((x, z), "ground"), COL["ground"])
            dark = tuple(int(v * 0.75) for v in base_c)
            quad(corners, dark)
    # 柱子+楼体：远→近 (x+z 升序)
    order = sorted(range(H), key=lambda z: z)
    items = []
    for z in order:
        for x in range(W):
            base_c = None
            top_y = 1
            kind = None
            if (x, z) in city.viaduct_cols:
                base_c, top_y, kind = COL["road"], VIADUCT_Y + 1, "viaduct"
            h, key = city.building_at(x, z)
            if h:
                base_c, top_y, kind = COL.get(key, COL["ground"]), h + 1, key
            for (px_, pz_) in city.viaduct_pillars:
                if (px_, pz_) == (x, z):
                    base_c, top_y, kind = COL["column"], VIADUCT_Y + 1, "column"
            if kind is None:
                continue
            items.append((x, z, top_y, kind))
    for (x, z, top_y, kind) in sorted(items, key=lambda t: t[0] + t[1]):
        if kind in ("column",):
            # 立柱按柱段绘制太密，投影一条亮线代替
            c_top, c_a, c_b = ISO_SHADE["column"]
            p0 = proj(x, z, 1)
            quad([proj(x, z, 0), proj(x + 1, z, 0), proj(x + 1, z + 1, 0), proj(x, z + 1, 0)], c_a)
            continue
        c_top, c_a, c_b = ISO_SHADE.get(kind, ISO_SHADE["b_mid"])
        # 顶面（y=top_y）
        tp = [proj(x, z, top_y), proj(x + 1, z, top_y),
              proj(x + 1, z + 1, top_y), proj(x, z + 1, top_y)]
        # 两侧面（向右 +x 面 / 向后 +z 面），用两个顶点柱体近似
        quad([proj(x, z + 1, top_y), proj(x + 1, z + 1, top_y),
              proj(x + 1, z + 1, 0), proj(x, z + 1, 0)], c_b)
        quad([proj(x + 1, z + 1, top_y), proj(x + 1, z, top_y),
              proj(x + 1, z, 0), proj(x + 1, z + 1, 0)], c_a)
        quad(tp, c_top)
    img.save(out)


# ---------------- 布局导出 ----------------
def export_layout(city: City) -> dict:
    buildings = {}
    for bx, bh, key in city.blocks:
        buildings[f"{bx[0]},{bx[1]}"] = {"h": bh, "key": key}
    return {
        "meta": {"name": "viaduct_proto", "extent": [W, H], "ground_y": GROUND_Y, "seed": city.seed},
        "roads": {
            "avenue_ns": {"x": list(AVENUE_V_X)},
            "avenue_ew": {"z": list(AVENUE_H_Z)},
        },
        "viaduct": {"z": VIADUCT_Z, "width": VIADUCT_W, "road_y": VIADUCT_Y,
                    "columns_x_step": COLUMN_GAP},
        "plaza_center": [sum(AVENUE_V_X) // 2, sum(AVENUE_H_Z) // 2],
        "buildings": buildings,
        "note": "顶视图/等距图为示意；NBT/WorldEdit 导入与扇区标注为下一步。",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default=str(Path("maps/viaduct/proto")))
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    city = City(args.seed)

    top = out_dir / "preview_topdown.png"
    iso = out_dir / "preview_iso.png"
    render_topdown(city, top)
    render_iso(city, iso)

    layout = export_layout(city)
    (out_dir / "layout.json").write_text(json.dumps(layout, ensure_ascii=False, indent=2), encoding="utf-8")

    n_block = len(city.blocks)
    print(f"[citygen] seed={city.seed} extent={W}x{H}")
    print(f"[citygen] buildings={n_block} viaduct_pillars={len(city.viaduct_pillars)}")
    print(f"[citygen] wrote {top.name}, {iso.name}, layout.json -> {out_dir}")


if __name__ == "__main__":
    main()
