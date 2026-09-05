#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BREAKFRONT · TaCZ 默认枪包 100HP 数值适配（M2 / 批 C）

背景：TaCZ:Refabricated 默认枪包 (tacz_default_gun) 的伤害按原版 20HP 体系设计
（ak47=9/600rpm → 3 发击杀）。本项目战斗模型为 100HP（BreakthroughTuning.
PLAYER_MAX_HEALTH=100），若不调参全枪偏弱、TTK 不成立。

本脚本把伤害重定为 100HP 口径的「战地 TTK 手感」：
  - 步枪 ~5-6 发击杀 / SMG 更高射速稍弱 / DMR 3 发 / 栓狙胸 2 发 头 1 发 /
    反器材全身 1 发 / 霰弹近距全中 1 发 / 手枪 & 大威力手枪分档。
改写字段（每把 *_data.json）：
  - bullet.damage                → 近距基础伤害
  - bullet.extra_damage.damage_adjust  → 距离衰减三档（30/60/inf 或族专属档位）
  - bullet.extra_damage.head_shot_multiplier → 族爆头倍率
不动：rpm / 弹容 / 弹药类型 / 弹道 / 换弹 / 后座 / 附件。

用法：
  python scripts/tune_tacz_pack.py <gunpack_dir> [<gunpack_dir> ...] [--dry-run]
服务端与客户端两个运行目录各跑一次，保证两端一致（伤害以服务端为准，
客户端数据主要供本地表现/校准）。

设计目标速查（100HP）：
  族        近/中/远    爆头   参照
  AR        20/17/13    ×1.6   600-950rpm，5-6 发击杀
  SMG       14/12/10    ×1.6   820-900rpm，7-8 发
  DMR       34/29/23    ×1.8   350-450rpm 半自动，3 发
  LMG       15/13/11    ×1.5   750rpm，7 发（弹链持续）
  Shotgun   10/8/6(丸)  ×1.3   近距全中 1-2 发（多弹丸按 nB 折）
  SR        76/72/60    ×2.2   栓动：躯干 2 发残、爆头 1 发
  AMR(50bmg)105/105/105 ×2.0   反器材：躯干 1 发
  Pistol    20/17/13    ×1.7   400-500rpm
  Magnum    34/28/20    ×2.0   300rpm，躯干 2-3 发
爆炸物（rpg7/m320）与特殊件不动（数值另定）。
"""

import json
import os
import shutil
import sys
import time

# ---------------------------------------------------------------------------
# 枪族规则
# ---------------------------------------------------------------------------
FAM = {
    "ar":      {"dmg": (20, 17, 13), "head": 1.6, "d1": 30,  "d2": 60},
    "smg":     {"dmg": (14, 12, 10), "head": 1.6, "d1": 30,  "d2": 60},
    "dmr":     {"dmg": (34, 29, 23), "head": 1.8, "d1": 45,  "d2": 90},
    "lmg":     {"dmg": (15, 13, 11), "head": 1.5, "d1": 35,  "d2": 80},
    "minigun": {"dmg": (13, 11,  9), "head": 1.5, "d1": 35,  "d2": 80},
    # 霰弹 damage = 整发总伤（TaCZ 按弹丸数 1/nB 分摊到每丸）。pump/semi 近距全中 1 发
    "shotgun": {"dmg": (110, 80, 50), "head": 1.3, "d1": 25,  "d2": 50},
    "aa12":    {"dmg": (70, 50, 35),  "head": 1.3, "d1": 20,  "d2": 40},  # 全自动喷：2 发连喷
    "db":      {"dmg": (110, 85, 55), "head": 1.3, "d1": 30,  "d2": 60},  # 双管
    "sr":      {"dmg": (76, 72, 60), "head": 2.2, "d1": 50,  "d2": 100},
    "amr":     {"dmg": (105, 105, 105), "head": 2.0, "d1": 50, "d2": 100},
    "pistol":  {"dmg": (20, 17, 13), "head": 1.7, "d1": 18,  "d2": 45},
    "magnum":  {"dmg": (50, 42, 30), "head": 2.0, "d1": 15,  "d2": 35},   # 躯干 2 发
}

# default 枪包 54 把枪 → 族。SKIP = 爆炸/特殊件，不改数值。
GUN_TO_FAM = {
    # 突击步枪
    "ak47": "ar", "aug": "ar", "g36k": "ar", "hk416d": "ar", "m4a1": "ar",
    "m16a1": "ar", "m16a4": "ar", "qbz_191": "ar", "qbz_95": "ar",
    "type_81": "ar", "scar_l": "ar", "rpk": "ar",
    # SMG（cz75 900rpm 全自动手感同 PDW）
    "b93r": "smg", "hk_mp5a5": "smg", "p90": "smg", "ump45": "smg",
    "uzi": "smg", "vector45": "smg", "cz75": "smg",
    # DMR / 战斗步枪
    "fn_fal": "dmr", "hk_g3": "dmr", "scar_h": "dmr", "mk14": "dmr",
    "sks_tactical": "dmr", "spr15hb": "dmr",
    # 机枪
    "fn_evolys": "lmg", "m249": "lmg", "minigun": "minigun",
    # 霰弹（pump/semi / 全自动 / 双管）
    "m1014": "shotgun", "m870": "shotgun", "spas_12": "shotgun",
    "aa12": "aa12", "db_long": "db", "db_short": "db",
    # 栓动狙击
    "ai_awp": "sr", "kar98": "sr", "lonetrail": "sr", "m700": "sr",
    "springfield1873": "sr",
    # 反器材
    "m95": "amr", "m107": "amr", "timeless50": "amr",
    # 手枪
    "glock_17": "pistol", "hk_mk23": "pistol",
    "m1911": "pistol", "m9a4": "pistol", "p320": "pistol", "taurus943": "pistol",
    # 大威力手枪
    "deagle": "magnum", "deagle_golden": "magnum",
    "rhino357": "magnum", "taurus500": "magnum",
    # 爆炸 / 特殊（不改）
    "rpg7": None, "m320": None,
}

# ---------------------------------------------------------------------------
# JSONC 处理（TaCZ 数据带 // 行注释与中文）
# ---------------------------------------------------------------------------
def strip_jsonc(s: str) -> str:
    out, i, n, instr = [], 0, len(s), False
    while i < n:
        c = s[i]
        if instr:
            out.append(c)
            if c == "\\":
                i += 1
            elif c == '"':
                instr = False
        else:
            if c == '"':
                instr = True
                out.append(c)
            elif c == "/" and i + 1 < n and s[i + 1] == "/":
                while i < n and s[i] != "\n":
                    i += 1
                continue
            elif c == "/" and i + 1 < n and s[i + 1] == "*":
                i += 2
                while i + 1 < n and not (s[i] == "*" and s[i + 1] == "/"):
                    i += 1
                i += 1
            else:
                out.append(c)
        i += 1
    return "".join(out)


def load_jsonc(path):
    with open(path, encoding="utf-8-sig") as f:
        return json.loads(strip_jsonc(f.read()))


def dump_jsonc(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def tune_pack(pack_dir, dry_run=False):
    guns_dir = os.path.join(pack_dir, "data", "tacz", "data", "guns")
    if not os.path.isdir(guns_dir):
        print(f"[skip] {pack_dir}: 找不到 data/tacz/data/guns")
        return 0, []

    rows = []
    changed = 0
    backup_dir = os.path.join(pack_dir, "_bf_tune_backup_" + time.strftime("%Y%m%d_%H%M%S"))
    if not dry_run:
        os.makedirs(backup_dir, exist_ok=True)

    for fname in sorted(os.listdir(guns_dir)):
        if not fname.endswith("_data.json"):
            continue
        gid = fname[: -len("_data.json")]
        fam = GUN_TO_FAM.get(gid)
        if fam is None:
            rows.append((gid, "skip" if gid in GUN_TO_FAM else "UNKNOWN", "-", "-", "-"))
            continue
        path = os.path.join(guns_dir, fname)
        try:
            data = load_jsonc(path)
        except Exception as e:  # noqa: BLE001
            rows.append((gid, fam, "PARSE_FAIL", str(e)[:40], "-"))
            continue

        bullet = data.get("bullet")
        if not isinstance(bullet, dict):
            rows.append((gid, fam, "NO_BULLET", "-", "-"))
            continue
        ex = bullet.setdefault("extra_damage", {})
        rule = FAM[fam]
        c1, c2, c3 = rule["dmg"]
        d1, d2 = rule["d1"], rule["d2"]

        old_dmg = bullet.get("damage")
        old_head = ex.get("head_shot_multiplier")
        bullet["damage"] = c1
        ex["head_shot_multiplier"] = rule["head"]
        ex["damage_adjust"] = [
            {"distance": d1, "damage": c1},
            {"distance": d2, "damage": c2},
            {"distance": "infinite", "damage": c3},
        ]
        if not dry_run:
            # 备份原始文件一次
            bak = os.path.join(backup_dir, fname)
            if not os.path.exists(bak):
                shutil.copy2(path, bak)
            dump_jsonc(path, data)
        changed += 1
        rows.append((gid, fam, f"{old_dmg}->{c1}", f"head {old_head}->{rule['head']}",
                     f"adj {d1}/{d2}/inf"))
    return changed, rows


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("-")]
    dry_run = "--dry-run" in sys.argv
    if not args:
        print(__doc__)
        sys.exit(1)
    for pack in args:
        n, rows = tune_pack(pack, dry_run)
        print(f"\n===== {pack}  (改写 {n} 把)" + ("  [DRY-RUN]" if dry_run else "") + " =====")
        hdr = f"{'gun':<18}{'fam':<10}{'damage':<12}{'head':<12}{'adjust'}"
        print(hdr)
        print("-" * len(hdr.encode('gbk', errors='ignore').decode('gbk', errors='ignore') if False else hdr))
        for r in rows:
            print(f"{r[0]:<18}{str(r[1]):<10}{str(r[2]):<12}{str(r[3]):<12}{str(r[4])}")


if __name__ == "__main__":
    main()
