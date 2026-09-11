# -*- coding: utf-8 -*-
"""诊断 + 修复主机 C:\\bfdeploy\\bf_ci_deploy.ps1：对比本地源、重写、报 parse 错。"""
import base64
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402
import bf_ci_deploy_setup as S  # noqa: E402

P = "C:\\bfdeploy\\bf_ci_deploy.ps1"

cli = connect()
try:
    # 1) 强制重写
    b64 = base64.b64encode(S.PS1.encode("utf-8-sig")).decode()
    ps(cli, "[IO.File]::WriteAllText('" + P + "', "
            "[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64 + "')))")
    # 2) 报 parse 错 + 抽查 defer 段落
    out = ps(cli,
             "$errs = $null\n"
             "[System.Management.Automation.PSParser]::Tokenize("
             "(Get-Content '" + P + "' -Raw), [ref]$errs) | Out-Null\n"
             "Write-Output ('parse errors: ' + $errs.Count)\n"
             "$errs | ForEach-Object { Write-Output ('ERR L' + $_.Token.StartLine + ': ' + $_.Message) }\n"
             "Write-Output '--- defer block ---'\n"
             "(Get-Content '" + P + "') | Select-String 'RemoteAddress','defer' | "
             "ForEach-Object { Write-Output $_.Line }")
    print(out.strip())
finally:
    cli.close()
