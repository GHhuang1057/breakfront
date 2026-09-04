#!/usr/bin/env bash
# 回合自动循环冒烟（对本地 dev 服，RCON 驱动）
# 前置：服务端已运行且开启 RCON (127.0.0.1:25575 / breakfront_dev)
set -e
cd "$(dirname "$0")"
RCON="python3 rcon.py"
echo "== 1. 当前状态 =="
$RCON "/bf status"
echo "== 2. 开局 =="
$RCON "/bf start"
sleep 4
$RCON "/bf status"
echo "== 3. 强制结算（应 8s 后自动重开） =="
$RCON "/bf end"
sleep 10
echo "== 4. 自动重开后的状态（期望 COUNTDOWN/战斗中） =="
$RCON "/bf status"
echo "== 5. 停止回大厅 =="
$RCON "/bf stop"
sleep 1
$RCON "/bf status"
echo "SMOKE OK"
