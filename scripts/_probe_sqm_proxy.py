"""端到端验证管理台的 squaremap 代理（25610 → squaremap 8080）。

流程：读服务端 breakfront-server.properties 里的 admin.password → POST /bfadmin/api/login
拿 token → 经 /bfadmin/api/sqm/ 取 settings.json 与一张真实瓦片，校验 HTTP 与体积。
"""
import sys

sys.path.insert(0, "scripts")
from mc_remote import connect, ps  # noqa: E402

PS = r"""
$ErrorActionPreference = 'Continue'
$props = Get-Content 'J:\bfserver\server\breakfront-server.properties' -Encoding UTF8
$line  = $props | Where-Object { $_ -match '^\s*admin\.password\s*=' } | Select-Object -First 1
$pw    = ($line -replace '^\s*admin\.password\s*=\s*', '').Trim()
if (-not $pw) { $pw = 'breakfront' }   # AdminService.password 的默认值
Write-Output ('admin.password found = ' + [bool]$pw)
$body  = '{"pw":"' + $pw + '"}'
try {
  $r = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:25610/bfadmin/api/login' `
        -Body $body -ContentType 'application/json' -TimeoutSec 10
  Write-Output ('login ok=' + $r.ok)
  $tk = $r.token
} catch { Write-Output ('login ERR ' + $_.Exception.Message); exit 1 }
try {
  $s = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 `
        -Uri ('http://127.0.0.1:25610/bfadmin/api/sqm/tiles/settings.json?token=' + $tk)
  Write-Output ('proxy settings.json HTTP ' + $s.StatusCode + ' len=' + $s.RawContentLength)
} catch { Write-Output ('proxy settings ERR ' + $_.Exception.Message) }
try {
  $p = Invoke-WebRequest -UseBasicParsing -TimeoutSec 15 `
        -Uri ('http://127.0.0.1:25610/bfadmin/api/sqm/tiles/minecraft_overworld/3/-5_2.png?token=' + $tk)
  Write-Output ('proxy tile HTTP ' + $p.StatusCode + ' len=' + $p.RawContentLength)
} catch { Write-Output ('proxy tile ERR ' + $_.Exception.Message) }
try {
  $n = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 `
        -Uri 'http://127.0.0.1:25610/bfadmin/api/sqm/tiles/settings.json'
  Write-Output ('proxy no-token HTTP ' + $n.StatusCode)
} catch { Write-Output ('proxy no-token rejected: ' + $_.Exception.Message) }
"""

cli = connect()
try:
    print(ps(cli, PS))
finally:
    cli.close()
