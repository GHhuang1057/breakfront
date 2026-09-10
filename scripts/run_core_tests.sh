#!/usr/bin/env bash
# BREAKFRONT 纯 Java 逻辑测试（本地秒级验证，不依赖 Gradle/Minecraft）
# ---------------------------------------------------------------------------
# 背景：本机无 Gradle、磁盘紧张，完整构建走 GitHub Actions（一轮数分钟）。
# core 里有相当一部分逻辑是纯 Java（无 Minecraft 依赖），可在本地秒级验证，
# 避免把明显错误推到 CI 才发现。配套 scripts/junit-mini 提供最小 JUnit 替身，
# 使标注 @Test 的既有 JUnit 测试也能在本地跑。
#
# 覆盖：
#   - weapon.*  武器数值 / TTK / BTK（WeaponBalanceTest、WeaponCatalogTest、WeaponTtkTest）
#   - server.*  管理台请求解析与据点语义（AdminEditTest）
#
# 用法：
#   bash scripts/run_core_tests.sh          # 跑全部
#   bash scripts/run_core_tests.sh --ttk    # 打印 TTK 对照报告
# ---------------------------------------------------------------------------
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK" 2>/dev/null || true' EXIT

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

if ! command -v "$JAVAC" >/dev/null 2>&1; then
  echo "[!] 未找到 javac，跳过本地测试（CI 会跑完整构建）"
  exit 0
fi

SRC="$WORK/src"
mkdir -p "$SRC/com/breakfront/weapon" "$SRC/com/breakfront/server"

# --- core 纯 Java 源（无 Minecraft 依赖）---
for f in AmmoType FireMode TtkMath WeaponClass WeaponSpec WeaponCatalog; do
  cp "$ROOT/core/src/main/java/com/breakfront/weapon/$f.java" "$SRC/com/breakfront/weapon/"
done
for f in Json ZoneAnchor; do
  cp "$ROOT/core/src/main/java/com/breakfront/server/$f.java" "$SRC/com/breakfront/server/"
done

# --- 测试源 ---
for f in WeaponBalanceTest WeaponCatalogTest WeaponTtkTest TtkReport; do
  cp "$ROOT/core/src/test/java/com/breakfront/weapon/$f.java" "$SRC/com/breakfront/weapon/"
done
cp "$ROOT/core/src/test/java/com/breakfront/server/AdminEditTest.java" "$SRC/com/breakfront/server/"

# --- JUnit 最小替身 + 运行器 ---
cp -r "$ROOT/scripts/junit-mini/org" "$SRC/"
cp "$ROOT/scripts/junit-mini/JUnitMiniRunner.java" "$SRC/"

# --- 编译 ---
echo "[*] 编译纯 Java 子集…"
if ! "$JAVAC" -encoding UTF-8 -nowarn -d "$WORK/out" $(find "$SRC" -name '*.java') 2>&1 | head -30; then
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
run_main() {
  echo ""
  echo "[*] $1"
  if ! "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out" "$1"; then
    FAILED=1
  fi
}

run_junit() {
  echo ""
  echo "[*] JUnit: $*"
  if ! "$JAVA" -Dfile.encoding=UTF-8 -cp "$WORK/out" JUnitMiniRunner "$@"; then
    FAILED=1
  fi
}

run_main com.breakfront.weapon.WeaponBalanceTest
run_main com.breakfront.server.AdminEditTest
run_junit com.breakfront.weapon.WeaponCatalogTest com.breakfront.weapon.WeaponTtkTest

echo ""
if [ "$FAILED" -eq 0 ]; then
  echo "[✓] 全部本地测试通过"
else
  echo "[FAIL] 存在失败用例"
fi
exit "$FAILED"
