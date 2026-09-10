#!/usr/bin/env bash
# BREAKFRONT 纯 Java 逻辑测试（本地快速验证，不依赖 Gradle/Minecraft）
# ---------------------------------------------------------------------------
# 背景：本机无 Gradle、磁盘紧张，完整构建走 GitHub Actions。但 core 里有相当一部分
# 逻辑是纯 Java（无 Minecraft 依赖），可以在本地用 javac 秒级验证，避免把明显错误
# 推到 CI 才发现（CI 一轮要跑几分钟）。
#
# 覆盖：
#   - com.breakfront.weapon.*  武器数值 / TTK / BTK（WeaponBalanceTest、TtkReport）
#   - com.breakfront.server.Json / ZoneAnchor  管理台请求解析与据点语义（AdminEditTest）
#
# 用法：
#   bash scripts/run_core_tests.sh          # 跑全部
#   bash scripts/run_core_tests.sh --ttk    # 只打印 TTK 对照报告
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

if ! command -v "$JAVAC" >/dev/null 2>&1; then
  echo "[!] 未找到 javac，跳过本地测试（CI 会跑完整构建）"
  exit 0
fi

mkdir -p "$WORK/src/com/breakfront/weapon" "$WORK/src/com/breakfront/server"

# --- 纯 Java 源（无 Minecraft 依赖）---
WEAPON_SRC=(AmmoType FireMode TtkMath WeaponClass WeaponSpec WeaponCatalog)
for f in "${WEAPON_SRC[@]}"; do
  cp "$ROOT/core/src/main/java/com/breakfront/weapon/$f.java" "$WORK/src/com/breakfront/weapon/"
done
cp "$ROOT/core/src/test/java/com/breakfront/weapon/WeaponBalanceTest.java" "$WORK/src/com/breakfront/weapon/"
cp "$ROOT/core/src/test/java/com/breakfront/weapon/TtkReport.java"        "$WORK/src/com/breakfront/weapon/"

cp "$ROOT/core/src/main/java/com/breakfront/server/Json.java"       "$WORK/src/com/breakfront/server/"
cp "$ROOT/core/src/main/java/com/breakfront/server/ZoneAnchor.java" "$WORK/src/com/breakfront/server/"
cp "$ROOT/core/src/test/java/com/breakfront/server/AdminEditTest.java" "$WORK/src/com/breakfront/server/"

# --- 编译 ---
echo "[*] 编译纯 Java 子集…"
if ! "$JAVAC" -encoding UTF-8 -d "$WORK/out" $(find "$WORK/src" -name '*.java') 2>&1 | head -30; then
  echo "[FAIL] 编译失败"
  exit 1
fi
echo "[✓] 编译通过"

if [ "${1:-}" = "--ttk" ]; then
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out" com.breakfront.weapon.TtkReport
  exit $?
fi

# --- 运行 ---
FAILED=0
run() {
  echo ""
  echo "[*] $1"
  if ! "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out" "$1"; then
    FAILED=1
  fi
}

run com.breakfront.weapon.WeaponBalanceTest
run com.breakfront.server.AdminEditTest

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "[✓] 全部本地测试通过"
else
  echo "[FAIL] 存在失败用例"
fi
exit "$FAILED"
