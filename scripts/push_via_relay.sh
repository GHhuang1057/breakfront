#!/usr/bin/env bash
# 经 Cloudflare Worker 中转推送（本机无法直连 github.com 时使用）
# ---------------------------------------------------------------------------
# 背景：开发机网络有时无法直连 github.com:443（代理停用/被墙），而 api.github.com
# 与自有 CF Worker 仍可达。bfadmin-cf 的 Worker 提供了受密钥保护的 GitHub 转发端点
# （/gh/<key>/...），本脚本借它完成 git push。
#
# 前提：
#   1) bfadmin-cf 已部署且 wrangler.toml 里配置了 GH_PROXY_KEY
#   2) 本机 git 已登录 GitHub（凭据可从 git credential 取出；token 不会落盘）
#
# 用法：
#   bash scripts/push_via_relay.sh            # 推送 main
#   bash scripts/push_via_relay.sh <branch>   # 推送指定分支
#   RELAY_KEY=xxx bash scripts/push_via_relay.sh
# ---------------------------------------------------------------------------
set -uo pipefail

RELAY_HOST="${RELAY_HOST:-bfupdate.geekhonize.top}"
RELAY_KEY="${RELAY_KEY:-bf-gh-relay-2026}"
REPO_PATH="${REPO_PATH:-GHhuang1057/breakfront.git}"
BRANCH="${1:-main}"

# 从 git 凭据中取 token（脱敏输出，不落盘）
TOKEN=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill 2>/dev/null \
  | sed -n 's/^password=//p')
if [ -z "$TOKEN" ]; then
  echo "[!] 未能取得 GitHub token —— 请先确认本机已登录 GitHub（git push 能正常认证）"
  exit 1
fi

URL="https://x-access-token:${TOKEN}@${RELAY_HOST}/gh/${RELAY_KEY}/${REPO_PATH}"
echo "[*] 经中转推送 → ${RELAY_HOST}（分支 ${BRANCH}）"
echo "[*] 中转端点：/gh/<key>/${REPO_PATH%.git}"

# 必须清掉本机 http(s)_proxy：中转通道走直连 CF，代理反而会拦住
git -c http.proxy= -c https.proxy= push "$URL" "$BRANCH" 2>&1 \
  | sed -E "s/${TOKEN}/<TOKEN>/g"

echo "[*] 完成。若直连已恢复，可改回：git push origin ${BRANCH}"
