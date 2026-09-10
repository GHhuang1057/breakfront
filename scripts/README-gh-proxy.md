# 本地 GitHub 全局代理（Breakfront 开发机）

本机位于国内，`github.com` / `raw.githubusercontent.com` 直连被拦截
（`api.github.com` 尚可），导致 `git push`、拉依赖、看 GitHub 网页都不稳。
本目录提供一套**零服务端改造**的本地代理：把流量经 SSH 动态转发（SOCKS5）
送到可直连 GitHub 的香港/北京 VPS。

## 链路

```
浏览器 / git / curl
      │  系统代理 socks=127.0.0.1:1080
      ▼
本机 OpenSSH  ssh -N -D 1080
      │  SSH（密钥 ~/.ssh/bf_ghproxy）
      ▼
北京 VPS 8.141.114.60 ──► github.com / codeload / raw ...   ✅ 实测 200
```

- 选北京 VPS（`8.141.114.60`）而非内网主机：它是本项目自己的机器、系统级常驻、
  实测可直连 `github.com` / `api.github.com` / `codeload.github.com`。
- **注意**：该 VPS 在国内，`google.com` 等仍不可达；本代理解决的是 **GitHub 可达性**。

## 文件

| 文件 | 作用 |
|---|---|
| `gh_proxy_tunnel.bat` | SOCKS5 隧道守护进程（断线自动重连） |
| `gh_proxy_on.bat` | 启动隧道 + 设置 Windows 系统代理 + git 代理 |
| `gh_proxy_off.bat` | 关闭隧道 + 还原系统代理 + 清 git 代理 |
| `gh_proxy_autostart.vbs` | 隐藏窗口拉起隧道（供启动文件夹调用） |
| `gh_proxy_install_startup.bat` | 安装「登录自动启动」到用户启动文件夹（免提权） |

## 用法

```bat
:: 开
scripts\gh_proxy_on.bat

:: 关
scripts\gh_proxy_off.bat

:: 安装开机自启（一次即可）
scripts\gh_proxy_install_startup.bat
```

验证：

```bash
curl -x socks5h://127.0.0.1:1080 -I https://github.com/      # 期望 200
git ls-remote https://github.com/GHhuang1057/breakfront.git HEAD
```

## 生效范围

- **系统代理**：写入 `HKCU\...\Internet Settings`，Chrome / Edge / IE / 部分基于
  WinINET 的应用自动生效（改完需**重启浏览器**）。
- **git / curl / aria2**：已在 `git config --global` 写入 `socks5h://127.0.0.1:1080`。
- **不支持**系统代理的应用（部分 Electron/Firefox 默认设置）需自行指定
  `socks5://127.0.0.1:1080`。

## 排障

| 现象 | 处理 |
|---|---|
| 浏览器打不开任何网站 | 隧道没起来 → 跑 `gh_proxy_on.bat`；或先 `gh_proxy_off.bat` 恢复 |
| `git` 报 proxy 连接失败 | 同上；`gh_proxy_off.bat` 会清掉 git 代理 |
| 想换链路 | 改 `gh_proxy_tunnel.bat` 里的 `HOST`（如内网 MC 主机经 frps 10022） |

## 其它通道（备选）

- **CI / 拉 release 资产**：`bfupdate.geekhonize.top` 的 CF Worker 中转，无需代理。
- **git 推送**：`scripts/push_via_relay.sh`（经 CF Worker），代理不可用时的兜底。
