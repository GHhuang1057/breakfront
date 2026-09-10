@echo off
rem ============================================================
rem  开启本地 GitHub 全局代理
rem   1) 后台拉起 SOCKS5 隧道（127.0.0.1:1080）
rem   2) 设置 Windows 系统代理（WinINET/Chrome/Edge 生效）
rem   3) 设置 git 全局代理
rem ============================================================
setlocal
start "BF-GH-PROXY" /min cmd /c "%~dp0gh_proxy_tunnel.bat"

rem --- 等待隧道就绪（最多 10 秒）---
set /a n=0
:wait
set /a n+=1
timeout /t 1 /nobreak >nul
netstat -ano | findstr "127.0.0.1:1080" | findstr "LISTENING" >nul 2>&1
if %errorlevel%==0 goto ready
if %n% lss 10 goto wait
echo [gh-proxy] 警告：隧道 10 秒内未就绪，请检查网络或 8.141.114.60 是否可达。

:ready
rem --- Windows 系统代理（socks；Chrome/Edge/IE 走 WinINET）---
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 1 /f >nul
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer /t REG_SZ /d "socks=127.0.0.1:1080" /f >nul
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyOverride /t REG_SZ /d "<local>" /f >nul

rem --- git 全局代理 ---
git config --global http.proxy "socks5h://127.0.0.1:1080" 2>nul
git config --global https.proxy "socks5h://127.0.0.1:1080" 2>nul
git config --global http.sslBackend openssl 2>nul

echo.
echo [gh-proxy] 已开启
echo   系统代理 : socks=127.0.0.1:1080
echo   git 代理 : socks5h://127.0.0.1:1080
echo   验证     : curl -x socks5h://127.0.0.1:1080 -I https://github.com/
echo   关闭     : scripts\gh_proxy_off.bat
echo.
echo 注意：本机 DNS 可正常解析 GitHub 域名；如浏览器仍不生效，重启浏览器即可。
