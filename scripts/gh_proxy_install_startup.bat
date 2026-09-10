@echo off
rem ============================================================
rem  把 GitHub 代理隧道设为「登录时自动启动（无窗口）」
rem  免提权：用启动文件夹 + VBS 隐藏窗口，不写计划任务。
rem ============================================================
setlocal
set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
copy /y "%~dp0gh_proxy_autostart.vbs" "%STARTUP%\BreakfrontGhProxy.vbs" >nul
if errorlevel 1 (
  echo [gh-proxy] 安装失败：无法写入启动文件夹
  exit /b 1
)
echo [gh-proxy] 已安装开机自启：%STARTUP%\BreakfrontGhProxy.vbs
echo [gh-proxy] 卸载：删除上述 vbs 文件即可。
