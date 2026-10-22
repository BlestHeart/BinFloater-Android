#!/usr/bin/env bash
# 一键回归：从干净状态跑全部测试，任一失败即退出非 0
#
# 注意两件事（都是踩过的坑）：
#  1. 所有测试都在**项目根目录**跑 —— StaticAudit 读的是 "app/src/com/binf/floater/..." 相对路径，
#     如果 cd 到 build/r_xxx 里跑会 NoSuchFileException。
#  2. 判定「通过」必须同时看**退出码**和**断言数**。只数 PASS 行会假绿：
#     编译失败时一行没跑，pass=0 fail=0，脚本照样报"全通过"。
set -uo pipefail
export JAVA_HOME="/c/Program Files/Zulu/zulu-21"

cd "$(dirname "$0")"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
PLATFORM="/f/AndroidSdk/platforms/android-36/android.jar"
FAIL=0
TOTAL=0

echo "════════════════════════════════════════"
echo " BinFloater 完整回归测试"
echo "════════════════════════════════════════"

# run <名字> <最少断言数> <命令...>
run() {
  local name="$1"; shift
  local expect_min="$1"; shift
  echo ""
  echo "───── $name ─────"
  local out rc
  out=$("$@" 2>&1)
  rc=$?
  local pass fail
  # 各测试类的断言行有 "  PASS" / "  OK  " 两种写法，统一认
  pass=$(printf '%s\n' "$out" | grep -cE '(^|[[:space:]])(PASS|OK)[[:space:]]' || true)
  fail=$(printf '%s\n' "$out" | grep -c 'FAIL' || true)
  echo "  通过 $pass 项，失败 $fail 项"
  if [ "$rc" -ne 0 ] || [ "$fail" -ne 0 ]; then
    echo "  ✗ 失败（退出码 $rc）"
    printf '%s\n' "$out" | grep -E 'FAIL|Exception|错误|原因' | head -8
    FAIL=$((FAIL+1))
  elif [ "$pass" -lt "$expect_min" ]; then
    echo "  ✗ 只跑了 $pass 项，少于预期的 $expect_min 项"
    printf '%s\n' "$out" | tail -5
    FAIL=$((FAIL+1))
  else
    echo "  ✓ 全通过"
  fi
  TOTAL=$((TOTAL+pass))
}

# 0. 先编一遍全部 Java（含 android.jar），语法错误立刻暴露
echo ""
echo "───── 编译全部源码 ─────"
rm -rf build/allsrc && mkdir -p build/allsrc
if "$JAVAC" -encoding UTF-8 --release 17 -classpath "$PLATFORM" \
     -d build/allsrc app/src/com/binf/floater/*.java 2>build/javac.log; then
  echo "  ✓ 通过"
else
  echo "  ✗ 编译失败"
  head -20 build/javac.log
  FAIL=$((FAIL+1))
fi

# 1. 编解码
rm -rf build/r_core && mkdir -p build/r_core
"$JAVAC" -encoding UTF-8 -d build/r_core \
  app/src/com/binf/floater/BinCore.java tools/CoreTest.java 2>&1 | head -3
run "编解码核心" 37 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_core CoreTest

# 2. 静态审查（读源码做约束检查，必须在项目根目录跑）
rm -rf build/r_audit && mkdir -p build/r_audit
"$JAVAC" -encoding UTF-8 -d build/r_audit \
  app/src/com/binf/floater/BinCore.java tools/StaticAudit.java 2>&1 | head -3
run "静态审查" 43 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_audit com.binf.floater.StaticAudit

echo ""
echo "════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  echo " ✅ 全部通过（累计 $TOTAL 项断言）"
  exit 0
else
  echo " ❌ $FAIL 组测试失败"
  exit 1
fi
