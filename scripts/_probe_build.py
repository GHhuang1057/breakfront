#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""探测 MC 主机（经北京 frps 10022 隧道）能否作为本地编译机。

用途：本地无 Gradle/磁盘受限时，优先在能直连 maven 的机器上跑 :core:build，
把「盲写→推 CI→等 5 分钟→红」的一轮空转换成分钟级反馈。

用法：python scripts/_probe_build.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mc_remote as m  # noqa: E402

PROBES = [
    ("java", "java -version"),
    ("javahome", "$env:JAVA_HOME"),
    ("gradle", "(Get-Command gradle -ErrorAction SilentlyContinue).Source"),
    ("git", "git --version"),
    ("maven443", "(Test-NetConnection maven.fabricmc.net -Port 443 -InformationLevel Quiet)"),
    ("gh443", "(Test-NetConnection github.com -Port 443 -InformationLevel Quiet)"),
    ("disk", "Get-PSDrive -PSProvider FileSystem | ForEach-Object { \"$($_.Name): $([math]::Round($_.Free/1GB,1))GB free\" }"),
    ("jdks", "Get-ChildItem 'C:\\Program Files\\Java','C:\\Program Files\\Eclipse Adoptium','D:\\' -ErrorAction SilentlyContinue -Directory | Select-Object -Expand FullName"),
]


def main() -> int:
    cli = m.connect()
    try:
        for tag, cmd in PROBES:
            print(f"##### {tag}")
            try:
                print(m.ps(cli, cmd).strip() or "(空)")
            except Exception as e:  # noqa: BLE001
                print(f"[!] {type(e).__name__}: {e}")
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
