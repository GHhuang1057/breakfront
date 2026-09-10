@echo off
rem ============================================================
rem  Breakfront 本地 GitHub 全局代理 —— SOCKS5 隧道守护
rem  链路：本机 127.0.0.1:1080 --(SSH 动态转发)--> 北京 VPS(8.141.114.60) --> GitHub
rem  断线自动重连；用 scripts\gh_proxy_off.bat 关闭。
rem ============================================================
setlocal
set "KEY=%USERPROFILE%\.ssh\bf_ghproxy"
set "HOST=root@8.141.114.60"
set "PORT=1080"
set "SSH=%SystemRoot%\System32\OpenSSH\ssh.exe"
if not exist "%SSH%" set "SSH=ssh"

:loop
"%SSH%" -N -D 127.0.0.1:%PORT% -i "%KEY%" ^
  -o StrictHostKeyChecking=accept-new ^
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 ^
  -o ExitOnForwardFailure=yes -o ConnectTimeout=12 ^
  %HOST%
rem ssh 退出（断网/服务器重启）→ 5 秒后重连
timeout /t 5 /nobreak >nul
goto loop
