#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查看 MC 服务端运行状态与计划任务（部署前必查）。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mc_remote import connect, ps  # noqa: E402

PROBES = [
    ("tasks", "Get-ScheduledTask | Where-Object { $_.TaskName -like '*Breakfront*' -or $_.TaskName -like '*BF*' } | Select-Object TaskName,State | Format-Table -AutoSize | Out-String"),
    ("java-proc", "Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^java' } | Select-Object ProcessId,@{n='cmd';e={$_.CommandLine.Substring(0,[Math]::Min(120,$_.CommandLine.Length))}} | Format-List | Out-String"),
    ("listen", "25565,25610,8080,25575 | ForEach-Object { \"$_ : \" + (Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue).Count }"),
    ("log-tail", "if (Test-Path 'J:\\bfserver\\server\\logs\\latest.log') { (Get-Content 'J:\\bfserver\\server\\logs\\latest.log' -Tail 12) -join [char]10 }"),
    ("mods-breakfront", "Get-ChildItem 'J:\\bfserver\\server\\mods' -Filter 'breakfront*' | Select-Object Name,@{n='MB';e={[math]::Round($_.Length/1MB,2)}},LastWriteTime | Format-Table -AutoSize | Out-String"),
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
