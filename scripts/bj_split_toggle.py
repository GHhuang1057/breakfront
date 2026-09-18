#!/usr/bin/env python3
"""切换 v2rayN 的「GitHub 走北京」分流配置（自定义 FullConfig 模板）。

背景：v2rayN 默认只有单个 proxy 出站，无法「按域名分流到不同服务器」。
本脚本通过启用 xray 的“自定义配置模板”实现：
  - github.com / *.githubusercontent.com / codeload / ghcr.io 等 -> 北京节点（=当前选中节点）
  - 其它境外流量 -> 内置的 HK 103.24.217.132 节点
  - 国内/局域网 -> 直连

用法：
  python bj_split_toggle.py status    # 查看当前状态
  python bj_split_toggle.py on        # 启用分流
  python bj_split_toggle.py off       # 停用分流（回到普通单节点模式）
  python bj_split_toggle.py restore   # 用最近一次备份还原 guiNDB/guiNConfig

注意：on/off/restore 前请先在托盘退出 v2rayN；改完重新打开 v2rayN 生效。
"""
import os
import sys
import glob
import shutil
import sqlite3

V2RAYN = os.environ.get("V2RAYN_DIR", r"G:\Download\v2rayN-windows-64-desktop\v2rayN-windows-64")
DB = os.path.join(V2RAYN, "guiConfigs", "guiNDB.db")
CFG = os.path.join(V2RAYN, "guiConfigs", "guiNConfig.json")
BACKUP_DIR = os.path.join(V2RAYN, "guiConfigs", "_bf_backup")
TEMPLATE_ID = "5191217181743909431"  # 内置 "V2ray" 模板行


def status():
    con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    for r in con.execute("SELECT * FROM FullConfigTemplateItem"):
        d = dict(r)
        print(f"template={d['Remarks']:<8} Enabled={d['Enabled']} AddProxyOnly={d['AddProxyOnly']} hasConfig={d['Config'] is not None}")
    print("profiles:", [dict(x)["Remarks"] for x in con.execute("SELECT Remarks FROM ProfileItem")])
    con.close()


def set_enabled(flag: int):
    con = sqlite3.connect(DB)
    con.execute("UPDATE FullConfigTemplateItem SET Enabled=? WHERE Id=?", (flag, TEMPLATE_ID))
    con.commit()
    con.close()
    print(f"V2ray 模板 Enabled -> {flag}  (请重新打开 v2rayN 生效)")


def restore():
    dbs = sorted(glob.glob(os.path.join(BACKUP_DIR, "guiNDB_*.db")))
    cfgs = sorted(glob.glob(os.path.join(BACKUP_DIR, "guiNConfig_*.json")))
    if not dbs or not cfgs:
        print("没有备份可还原"); return
    shutil.copy2(dbs[-1], DB)
    shutil.copy2(cfgs[-1], CFG)
    print("已还原:", os.path.basename(dbs[-1]), "/", os.path.basename(cfgs[-1]))


if __name__ == "__main__":
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "status").lower()
    if cmd == "on":
        set_enabled(1)
    elif cmd == "off":
        set_enabled(0)
    elif cmd == "restore":
        restore()
    else:
        status()
