#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""squaremap 未生成配置 / 未监听 8080 的定位探针。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mc_remote import connect, ps  # noqa: E402

R = r"J:\bfserver\server"
LOG = R + r"\logs\latest.log"

PROBES = [
    ("sqm-all", f"Select-String -Path '{LOG}' -Pattern 'squaremap' | ForEach-Object {{ $_.Line }}"),
    ("config-dir", f"if (Test-Path '{R}\\config') {{ Get-ChildItem '{R}\\config' | Select-Object -Expand Name }} else {{ 'NO config dir' }}"),
    ("sqm-dir", f"if (Test-Path '{R}\\config\\squaremap') {{ Get-ChildItem '{R}\\config\\squaremap' -Recurse | Select-Object -Expand FullName }} else {{ 'NO squaremap cfg dir' }}"),
    ("port-8080", "(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count"),
    ("mods-bot-check", f"Select-String -Path '{LOG}' -Pattern 'BF-Bot|BotSquad|假玩家|编制' | Select-Object -Last 10 | ForEach-Object {{ $_.Line }}"),
    ("last-40", f"(Get-Content '{LOG}' -Tail 40) -join [char]10"),
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
