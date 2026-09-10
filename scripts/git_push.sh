#!/usr/bin/env bash
# 经本机 SOCKS5 隧道推送（Breakfront 开发机专用）。
#
# 为什么需要它：本机 github.com 直连被拦，靠 scripts/gh_proxy_on.bat 起的
# ssh -N -D 1080 SOCKS5 隧道出网。但**沙箱/Agent 环境会注入 http_proxy /
# https_proxy 环境变量**（如 http://127.0.0.1:51667），curl/git 会优先用它们，
# 结果 github.com 走这条链路直接卡死 —— 表现为 `git push` 长时间无任何输出、
# 不报错也不结束（实测挂 13 分钟仍是 0 字节输出）。
#
# 正确做法：把代理类环境变量清掉，并显式指定 socks5h。用法：
#     scripts/git_push.sh            # 推当前分支
#     scripts/git_push.sh main       # 推指定分支
set -euo pipefail

SOCKS="socks5h://127.0.0.1:1080"
BRANCH="${1:-$(git rev-parse --abbrev-ref HEAD)}"

if ! (exec 3<>/dev/tcp/127.0.0.1/1080) 2>/dev/null; then
  echo "[✗] 127.0.0.1:1080 未监听 —— 先跑 scripts\\gh_proxy_on.bat 起隧道" >&2
  exit 1
fi

echo "[*] 经 $SOCKS 推送 $BRANCH …"
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u all_proxy \
  git -c http.proxy="$SOCKS" -c https.proxy="$SOCKS" push origin "$BRANCH"

echo "[✓] 完成，远端指向："
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u all_proxy \
  git -c http.proxy="$SOCKS" -c https.proxy="$SOCKS" ls-remote origin "$BRANCH"
