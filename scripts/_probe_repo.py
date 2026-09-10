#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查看 MC 主机编译机（J:\\bfbuild\\breakfront）的仓库状态与工具链就绪度。"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mc_remote import connect, ps  # noqa: E402

B = r"J:\bfbuild\breakfront"

PROBES = [
    ("repo-exists", f"Test-Path '{B}\\.git'"),
    ("head", f"cd '{B}'; git log --oneline -3"),
    ("status", f"cd '{B}'; git status --porcelain | Measure-Object | Select-Object -Expand Count"),
    ("remote", f"cd '{B}'; git remote -v"),
    ("gradle", "Test-Path 'J:\\bfbuild\\gradle-9.5.0\\bin\\gradle.bat'"),
    ("jdk", "Test-Path 'J:\\bfserver\\jdk21\\bin\\java.exe'"),
    ("jdks", "& 'J:\\bfserver\\jdk21\\bin\\java.exe' -version"),
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
