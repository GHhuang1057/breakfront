#!/usr/bin/env bash
# gh CLI 走本机 SOCKS5 隧道（Breakfront 开发机专用）。
#
# 为什么需要它：
#   1) 本机 github.com 直连不通，靠 scripts/gh_proxy_on.bat 起的
#      `ssh -N -D 1080` SOCKS5 隧道出网；
#   2) **沙箱/Agent 环境会注入 http_proxy / https_proxy**（例如
#      http://127.0.0.1:51667），gh(Go) 默认走 HTTP(S)_PROXY 且**优先级高于**任何本地配置
#      → github.com 经这条链路会直接卡死/超时；
#   3) 本地代理端口会变（用户/工具重开隧道），所以不要写死到 gh 的全局配置里，
#      统一由本脚本在调用时注入。
#
# 用法（把 gh 换成 scripts/gh.sh 即可，参数原样透传）：
#   scripts/gh.sh auth status
#   scripts/gh.sh release list --repo GHhuang1057/breakfront --limit 5
#   scripts/gh.sh api repos/GHhuang1057/breakfront
#
# 环境变量：
#   GH_SOCKS   覆盖 SOCKS 地址（默认 socks5://127.0.0.1:1080）
set -euo pipefail

SOCKS="${GH_SOCKS:-socks5://127.0.0.1:1080}"
PORT="${SOCKS##*:}"

if ! (exec 3<>/dev/tcp/127.0.0.1/"${PORT}") 2>/dev/null; then
  echo "[✗] 127.0.0.1:${PORT} 未监听 —— 先跑 scripts\\gh_proxy_on.bat 起隧道" >&2
  exit 1
fi

# 先清掉沙箱注入的 HTTP 代理，再显式指向本地 SOCKS5
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
export HTTPS_PROXY="$SOCKS"
export HTTP_PROXY="$SOCKS"
export ALL_PROXY="$SOCKS"
# gh 的 OAuth 也走同一出口
export GH_HOST="${GH_HOST:-github.com}"

exec gh "$@"
