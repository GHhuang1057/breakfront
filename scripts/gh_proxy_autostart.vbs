' Breakfront 本地 GitHub 全局代理 —— 登录时静默拉起 SOCKS5 隧道（无窗口）
' 由 scripts\gh_proxy_install_startup.bat 复制到「启动」文件夹后自动随登录运行。
CreateObject("WScript.Shell").Run """" & "G:\BF_MC\scripts\gh_proxy_tunnel.bat" & """", 0, False
