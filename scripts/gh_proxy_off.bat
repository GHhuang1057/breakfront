@echo off
rem ============================================================
rem  关闭本地 GitHub 全局代理：清系统代理 + git 代理 + 结束隧道
rem ============================================================
setlocal

rem --- 关闭 Windows 系统代理 ---
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyEnable /t REG_DWORD /d 0 /f >nul
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyServer /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings" /v ProxyOverride /f >nul 2>&1

rem --- 清 git 代理 ---
git config --global --unset http.proxy 2>nul
git config --global --unset https.proxy 2>nul

rem --- 结束占用 1080 端口的 ssh 隧道（按端口精确匹配，不误杀其它 ssh）---
set "KILLED=0"
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":1080" ^| findstr "LISTENING"') do (
  taskkill /f /pid %%p >nul 2>&1 && set "KILLED=1"
)
if "%KILLED%"=="1" (echo [gh-proxy] 隧道已结束) else (echo [gh-proxy] 未发现运行中的隧道)

echo [gh-proxy] 已关闭（系统代理 / git 代理已还原）
