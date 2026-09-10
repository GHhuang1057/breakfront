# Runs ON the MC host (friend's server). Switches frpc from HK 132 to Beijing.
# Uploaded by agent; executed via switch_frpc_beijing.bat
$ErrorActionPreference = "Stop"
$frpcToml = "C:\bfdeploy\frp\frpc.toml"
$bj = "8.141.114.60"

# 0. precheck: Beijing frp control port must be reachable
$tcp = Test-NetConnection -ComputerName $bj -Port 7000 -WarningAction SilentlyContinue
if (-not $tcp.TcpTestSucceeded) { Write-Output "ABORT: $bj`:7000 unreachable"; exit 1 }
Write-Output "OK: $bj`:7000 reachable"

# 1. backup
Copy-Item $frpcToml "$frpcToml.bak_hk132" -Force
Write-Output "backup -> $frpcToml.bak_hk132"

# 2. switch server address（注意：PS5.1 的 -Encoding UTF8 会写 BOM，frp 的 TOML 解析器会报
#    "invalid character at start of key: ï" —— 必须用 .NET WriteAllText + UTF8Encoding($false) 无 BOM 写回）
$c = Get-Content $frpcToml -Raw
$c2 = $c -replace "103\.24\.217\.132", $bj
[System.IO.File]::WriteAllText($frpcToml, $c2, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "serverAddr -> $bj (BOM-less UTF8)"

# 3. restart frpc task
schtasks /End /TN BreakfrontFrpc 2>$null | Out-Null
Start-Sleep -Seconds 2
schtasks /Run /TN BreakfrontFrpc | Out-Null
Start-Sleep -Seconds 5
Write-Output "BreakfrontFrpc restarted"
Get-Content $frpcToml | Select-String "serverAddr|serverPort|connectServer" | ForEach-Object { $_.Line }

# 4. verify: MC port now served via Beijing (frps only opens remote port after frpc registers)
Start-Sleep -Seconds 5
$v = Test-NetConnection -ComputerName $bj -Port 25565 -WarningAction SilentlyContinue
Write-Output ("Beijing 25565 " + $(if ($v.TcpTestSucceeded) { "OPEN - switch OK" } else { "not open yet (re-check shortly)" }))
Write-Output "DONE"
