#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""部署后验收：squaremap 加载情况 / 瓦片 / web 端口 / BOT 编制日志。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mc_remote import connect, ps  # noqa: E402

R = r"J:\bfserver\server"
LOG = R + r"\logs\latest.log"

PROBES = [
    ("ports", "25565,8080,25610 | ForEach-Object { \"$_ : \" + (Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue).Count }"),
    ("squaremap-log", f"Select-String -Path '{LOG}' -Pattern 'squaremap|Squaremap' | Select-Object -Last 12 | ForEach-Object {{ $_.Line }}"),
    ("bot-log", f"Select-String -Path '{LOG}' -Pattern 'BF-Bot|BotSquad|reconcile|热顶替' | Select-Object -Last 14 | ForEach-Object {{ $_.Line }}"),
    ("errors", f"Select-String -Path '{LOG}' -Pattern 'ERROR|Exception|Failed to load|Incompatible' | Select-Object -Last 12 | ForEach-Object {{ $_.Line }}"),
    ("sqm-cfg", f"if (Test-Path '{R}\\config\\squaremap\\config.yml') {{ Select-String -Path '{R}\\config\\squaremap\\config.yml' -Pattern 'bind|port|enabled|world' | Select-Object -First 18 | ForEach-Object {{ $_.Line }} }} else {{ 'NO CONFIG' }}"),
    ("webserver", f"if (Test-Path '{R}\\webserver') {{ Get-ChildItem '{R}\\webserver' -Recurse -File | Select-Object -First 12 -Expand FullName }} else {{ 'NO webserver dir' }}"),
]


def main() -> int:
    cli = connect()
    try:
        for tag, cmd in PROBES:
            print(f"##### {tag}")
            try:
                print(ps(cli, cmd).strip() or "(空)")
            except Exception as e:  # noqa: BLE001
                print(f"[!] {type(e).__name__}: {e}")
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
