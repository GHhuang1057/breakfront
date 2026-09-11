# -*- coding: utf-8 -*-
"""一次性装配脚本：在 MC 主机安装「CI 发版自动部署」链路（BFDeploy）。

链路：每 3 分钟轮询 bfupdate 清单 tag -> 变化则 面板停服 -> 经 Worker
下载 server zip -> 解压 robocopy 部署(保留 world/properties/squaremap) -> 面板启服。
"""
import base64
import json
import sys
import time

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

PS1 = r"""
$ErrorActionPreference = 'Stop'
$Base = 'https://bfupdate.geekhonize.top'
$Server = 'J:\bfserver\server'
$Work = 'C:\bfdeploy\ci'
$Marker = 'C:\bfdeploy\last_deployed_tag.txt'
$LogFile = 'C:\bfdeploy\ci_deploy.log'
$Lock = 'C:\bfdeploy\ci_deploy.lock'
$Panel = 'http://127.0.0.1:23333'
$DaemonId = 'f9d6aae8af834182b85d279f9ac2cbd7'
$InstanceUuid = '0dabbb870e0f4f398b7d575146c44b14'
$CredFile = 'C:\bfdeploy\mcsm_cred.txt'

function Log($m) { ("{0} {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $m) | Add-Content -Path $LogFile -Encoding UTF8 }
function PortUp([int]$port) {
  $c = New-Object Net.Sockets.TcpClient
  try { $t = $c.BeginConnect('127.0.0.1', $port, $null, $null)
        if ($t.AsyncWaitHandle.WaitOne(2000) -and $c.Connected) { $true } else { $false } }
  finally { $c.Close() }
}
try {
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  if (Test-Path $Lock) {
    $age = ((Get-Date) - (Get-Item $Lock).LastWriteTime).TotalMinutes
    if ($age -lt 20) { exit 0 }
    Remove-Item $Lock -Force
  }
  New-Item -ItemType File -Path $Lock -Force | Out-Null

  if (-not (Test-Path $Work)) { New-Item -ItemType Directory -Path $Work -Force | Out-Null }
  $hdr = @{ 'User-Agent' = 'Mozilla/5.0 Chrome/126' }
  $m = Invoke-RestMethod -Uri "$Base/breakfront/manifest.json" -Headers $hdr -TimeoutSec 30
  $tag = $m.tag
  if (-not $tag) { Log 'manifest has no tag'; Remove-Item $Lock -Force; exit 0 }
  $old = if (Test-Path $Marker) { (Get-Content $Marker -Raw).Trim() } else { '' }
  if ($tag -eq $old) { Remove-Item $Lock -Force; exit 0 }
  Log "deploy begin tag=$tag old=$old"

  $zipName = 'breakfront-dev-server-' + $tag.Substring(4) + '.zip'
  $zip = Join-Path $Work $zipName
  Invoke-WebRequest -Uri "$Base/breakfront/files/$zipName" -OutFile $zip -Headers $hdr -TimeoutSec 900
  Log ('zip ok size=' + (Get-Item $zip).Length)

  $stage = Join-Path $Work ('stage-' + $tag)
  if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
  Expand-Archive -Path $zip -DestinationPath $stage -Force
  $kids = @(Get-ChildItem $stage)
  if ($kids.Count -eq 1 -and $kids[0].PSIsContainer) { $stage = $kids[0].FullName }
  Log "stage=$stage"

  $cred = @(Get-Content $CredFile)
  $body = @{ username = $cred[0].Trim(); password = $cred[1].Trim() } | ConvertTo-Json
  $login = Invoke-RestMethod -Method Post -Uri "$Panel/api/auth/login" -ContentType 'application/json' -Body $body -SessionVariable sess -TimeoutSec 30
  $token = $login.data
  if (-not $token) { throw ('panel login failed: ' + ($login | ConvertTo-Json -Compress)) }
  $ph = @{ 'X-Requested-With' = 'XMLHttpRequest' }
  Log 'panel login ok'

  function InstCall($action) {
    Invoke-RestMethod -Uri ("$Panel/api/protected_instance/$action" + "?daemonId=$DaemonId&uuid=$InstanceUuid&token=$token") -WebSession $sess -Headers $ph -TimeoutSec 30 | Out-Null
  }
  function InstStatus {
    $l = Invoke-RestMethod -Uri ("$Panel/api/service/remote_service_instances" + "?daemonId=$DaemonId&page=1&page_size=50&token=$token") -WebSession $sess -Headers $ph -TimeoutSec 30
    return ($l.data.data | Where-Object { $_.instanceUuid -eq $InstanceUuid }).status
  }

  InstCall 'stop'
  Log 'stop sent'
  $deadline = (Get-Date).AddSeconds(120)
  $st = 3
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 3
    try { $st = InstStatus } catch { $st = -1 }
    if ($st -ne 3) { break }
  }
  Log ("status after stop=$st")

  robocopy (Join-Path $stage 'mods') (Join-Path $Server 'mods') /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
  robocopy $stage $Server /E /XF server.properties usercache.json ops.json banned-players.json banned-ips.json whitelist.json /XD world world_nether world_the_end playerdata logs crash-reports squaremap /NFL /NDL /NJH /NJS /NP | Out-Null
  Log 'files deployed'

  InstCall 'open'
  Log 'open sent'
  $deadline = (Get-Date).AddSeconds(240)
  $up = $false
  while ((Get-Date) -lt $deadline) {
    Start-Sleep 5
    if (PortUp 25565) { $up = $true; break }
  }
  Log ("port25565=$up")
  if ($up) { Set-Content -Path $Marker -Value $tag -Encoding ASCII; Log "deploy done tag=$tag" }
  else { Log "deploy FAILED tag=$tag (server not listening)" }
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
} catch {
  Log ('ERROR: ' + $_.Exception.Message)
  Remove-Item $Lock -Force -ErrorAction SilentlyContinue
  exit 1
}
"""

SETUP_PS = r"""
$ps1 = 'C:\bfdeploy\bf_ci_deploy.ps1'
New-Item -ItemType Directory -Path 'C:\bfdeploy' -Force | Out-Null
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ('-NoProfile -ExecutionPolicy Bypass -File ' + $ps1)
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 3) -RepetitionDuration (New-TimeSpan -Days 3650)
Register-ScheduledTask -TaskName 'BFDeploy' -Action $action -Trigger $trigger -RunLevel Highest -Force | Out-Null
Write-Output 'BFDeploy registered'
"""

def write_remote(cli, path, content):
    b64 = base64.b64encode(content.encode("utf-8")).decode()
    out = ps(cli, f"[IO.File]::WriteAllText('{path}', [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('{b64}'))); "
                  f"Write-Output ('wrote ' + (Get-Item '{path}').Length)")
    print("write", path, "->", out.strip()[:60])

def main():
    cli = connect()
    try:
        # 0) 前置确认：面板进程在跑、服务器目录在
        print(ps(cli, "'panel=' + ((Get-NetTCPConnection -LocalPort 23333 -State Listen -ErrorAction SilentlyContinue).Count) + "
                   "' serverdir=' + (Test-Path 'J:\\bfserver\\server')"))

        # 1) 写部署脚本
        write_remote(cli, r"C:\bfdeploy\bf_ci_deploy.ps1", PS1)

        # 2) 写面板凭据（本地 ~/.mcsm_secret 两行 -> 主机 C:\bfdeploy\mcsm_cred.txt）
        import os
        sec = os.path.expanduser("~/.mcsm_secret")
        cred = open(sec, encoding="utf-8").read()
        write_remote(cli, r"C:\bfdeploy\mcsm_cred.txt", cred)

        # 3) 注册计划任务
        write_remote(cli, r"C:\bfdeploy\bf_ci_deploy_setup.ps1", SETUP_PS)
        print(ps(cli, "powershell -NoProfile -ExecutionPolicy Bypass -File C:\\bfdeploy\\bf_ci_deploy_setup.ps1"))

        # 4) 立即触发一次
        print(ps(cli, "Start-ScheduledTask -TaskName 'BFDeploy'; Write-Output 'triggered'"))
    finally:
        cli.close()

    # 5) 轮询部署日志（部署含 81MB 下载 + 停启，约 3-6 分钟）
    print("waiting for deploy...")
    for i in range(40):
        time.sleep(20)
        cli = connect()
        try:
            log = ps(cli, "if (Test-Path 'C:\\bfdeploy\\ci_deploy.log') { Get-Content 'C:\\bfdeploy\\ci_deploy.log' -Encoding UTF8 -Tail 5 } else { 'no log yet' }")
        finally:
            cli.close()
        print(f"--- {(i + 1) * 20}s ---\n{log.strip()}")
        if "deploy done" in log or "deploy FAILED" in log or "ERROR:" in log:
            break

if __name__ == "__main__":
    main()
