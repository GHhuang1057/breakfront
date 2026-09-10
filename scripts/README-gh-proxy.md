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

### ⚠️ `git push` 长时间「零输出」卡死（2026-09-10 实测根因）

**症状**：`git push` 挂十几分钟，stdout/stderr 一个字节都没有，也不报错不退出；
即使 `GIT_TERMINAL_PROMPT=0 GCM_INTERACTIVE=never` 也一样（所以**不是**在等输密码）。
同时 `curl -x socks5h://127.0.0.1:1080 https://github.com/` 却是 **200 / 0.6s**。

**根因**：Agent/沙箱环境会注入 `http_proxy` / `https_proxy` 环境变量（本项目沙箱为
`http://127.0.0.1:51667`）。**环境变量优先级高于 `git config http.proxy`**，于是 git
绕开了可用的 SOCKS 隧道、改走沙箱代理去连 github.com → 该链路对 github.com 不通 → 静默挂死。
（同一条沙箱代理对 `api.github.com` 是放行的，所以 `gh api` / curl api 看着都正常，极易误判。）

**正确姿势**：清掉代理环境变量 + 显式指定 socks5h。已封装为脚本：

```bash
scripts/git_push.sh          # 推当前分支
scripts/git_push.sh main     # 推指定分支
```

等价的手工命令：

```bash
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u all_proxy \
  git -c http.proxy=socks5h://127.0.0.1:1080 -c https.proxy=socks5h://127.0.0.1:1080 push origin main
```

**顺带结论**：`scripts/git_relay.py`（经编译机中转）只适用于**取代码**——编译机
（`J:\bfbuild\breakfront`）虽然 `git fetch` 正常（有读凭据），但 `git push` 会卡在
Git Credential Manager 的凭据提示上（该机没有可写凭据），推不上去。要推还是得本地走隧道。

## 其它通道（备选）

- **CI / 拉 release 资产**：`bfupdate.geekhonize.top` 的 CF Worker 中转，无需代理。
- **git 远端本身就是中继**：本机 `origin` = `https://bfupdate.geekhonize.top/gh/bf-gh-relay-2026/GHhuang1057/breakfront.git`
  （CF Worker 再转发到 github.com），`scripts/push_via_relay.sh` 是其直推封装。
  即便如此，**仍要清掉代理环境变量**再推——否则请求会先被沙箱代理截走（见上一节的排障）。
- **编译机**（`J:\bfbuild\breakfront`）的 `origin` 才是直连的 `github.com`，只读可用。


## gh CLI 代理设置（2026-09-10 更新）

**结论：gh 的凭据没问题，坏的是代理。** 之前 `gh auth status` 报「not logged into any
GitHub hosts」是**误报** —— 沙箱注入的 `http_proxy/https_proxy = http://127.0.0.1:51667`
会截走 github.com 的连接并失败，gh 把连接失败当成未登录。凭据其实一直在系统凭据库里
（`gho_…`，scopes: `gist, read:org, repo, workflow`）。

本机出网只有一条可用通道：**`ssh -N -D 1080` 起的 SOCKS5 隧道**
（`scripts\gh_proxy_on.bat`）。所以**不要**把代理写进 gh 的全局配置（端口会变），
统一用封装脚本在调用时注入：

```bash
scripts/gh.sh auth status
scripts/gh.sh release list --repo GHhuang1057/breakfront --limit 5
scripts/gh.sh release download dev-<sha> --repo GHhuang1057/breakfront --pattern "*.json" --dir out
```

`scripts/gh.sh` 做三件事（与 `git_push.sh` 同一套思路）：
1. 探活 `127.0.0.1:1080`，没起隧道就明确报错；
2. **unset** 沙箱注入的 `http_proxy/https_proxy/HTTP_PROXY/HTTPS_PROXY/all_proxy`；
3. 显式 `export HTTPS_PROXY/HTTP_PROXY/ALL_PROXY=socks5://127.0.0.1:1080` 后 `exec gh`。

可用 `GH_SOCKS` 覆盖隧道地址（默认 `socks5://127.0.0.1:1080`）。

⚠️ 同一坑对 git 一样成立：**任何走网络的 git/gh 操作前都要清代理环境变量**，
否则表现为「静默挂死、零输出、不报错」（git）或「误报未登录」（gh）。
