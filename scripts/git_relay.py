"""把本地提交经编译机中转到 GitHub（本地出网不稳，编译机出网快）。

用法：
    python scripts/git_relay.py <bundle文件> <远端分支>

流程：SFTP 上传 bundle → 编译机 `git fetch <bundle> main:refs/remotes/relay/main`
→ `git merge --ff-only` → `git push origin main` → 回读远端 sha 确认。
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

BUILD_DIR = r"J:\bfbuild\breakfront"
REMOTE_BUNDLE = r"J:\bfbuild\_relay.bundle"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    local = sys.argv[1]
    branch = sys.argv[2] if len(sys.argv) > 2 else "main"
    if not os.path.isfile(local):
        print("[✗] 找不到 bundle:", local)
        return 1

    print(f"[*] 上传 {local} → {REMOTE_BUNDLE}")
    cli = connect()
    try:
        sftp = cli.open_sftp()
        sftp.put(local, REMOTE_BUNDLE)
        sftp.close()
        print("[✓] 已上传",
              ps(cli, f"(Get-Item '{REMOTE_BUNDLE}').Length").strip(), "字节")

        print("[*] 校验 bundle")
        print(ps(cli, f"cd '{BUILD_DIR}'; git bundle verify '{REMOTE_BUNDLE}' 2>&1 | "
                      f"Select-Object -Last 3"))

        print(f"[*] 取入并快进 {branch}")
        out = ps(cli, f"cd '{BUILD_DIR}'; "
                      f"git fetch '{REMOTE_BUNDLE}' {branch}:refs/remotes/relay/{branch} 2>&1; "
                      f"git merge --ff-only refs/remotes/relay/{branch} 2>&1; "
                      f"Write-Output '--- HEAD ---'; git log --oneline -1")
        print(out.strip())

        print("[*] 推送到 GitHub")
        out = ps(cli, f"cd '{BUILD_DIR}'; git push origin {branch} 2>&1 | Select-Object -Last 4")
        print(out.strip())

        print("[*] 远端实际指向")
        print(ps(cli, f"cd '{BUILD_DIR}'; git ls-remote origin {branch}").strip())
    finally:
        cli.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
