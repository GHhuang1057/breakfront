#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 squaremap 在 MC 主机上的落地情况（jar / 配置 / 监听端口 / 瓦片目录）。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mc_remote import connect, ps  # noqa: E402

R = r"J:\bfserver\server"

PROBES = [
    ("mods-squaremap", f"Get-ChildItem '{R}\\mods' -Filter '*squaremap*' | Select-Object Name,Length | Format-Table -AutoSize | Out-String"),
    ("mods-list", f"Get-ChildItem '{R}\\mods' -Filter '*.jar' | Select-Object -Expand Name"),
    ("config", f"Get-ChildItem '{R}\\config' -Filter '*squaremap*' -Recurse -ErrorAction SilentlyContinue | Select-Object -Expand FullName"),
    ("webserver-dir", f"Test-Path '{R}\\webserver'"),
    ("port8080", "(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).Count"),
    ("listen25565", "(Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue).Count"),
    ("jvm-opts", f"Get-ChildItem '{R}' -Filter '*.bat','*.txt' | Select-Object -Expand Name"),
    ("start-bat", f"if (Test-Path '{R}\\start.bat') {{ Get-Content '{R}\\start.bat' -Encoding UTF8 }}"),
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
