#!/usr/bin/env bash
# 一键回归：从干净状态跑全部测试，任一失败即退出非 0
#
# 注意两件事（都是踩过的坑）：
#  1. 所有测试都在**项目根目录**跑 —— StaticAudit 读的是 "app/src/com/binf/ime/..." 相对路径，
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
echo " BinIME 完整回归测试"
echo "════════════════════════════════════════"

# 依赖桩：让 PinyinEngine / CloudSuggest 能在没有 Android 的 JVM 上编译
mkdir -p build/stub/android/content/res
cat > build/stub/android/content/Context.java <<'EOF'
package android.content;
public class Context { public android.content.res.AssetManager getAssets(){return null;} }
EOF
cat > build/stub/android/content/res/AssetManager.java <<'EOF'
package android.content.res;
public class AssetManager { public java.io.InputStream open(String n) throws java.io.IOException {return null;} }
EOF

DICT="app/assets/pinyin.dict.gz"
if [ ! -f "$DICT" ]; then
  echo "找不到词典 $DICT"
  exit 1
fi
echo "  词典: $DICT ($(du -h "$DICT" | cut -f1))"

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
     -d build/allsrc app/src/com/binf/ime/*.java 2>build/javac.log; then
  echo "  ✓ 通过"
else
  echo "  ✗ 编译失败"
  head -20 build/javac.log
  FAIL=$((FAIL+1))
fi

# 1. 编解码
rm -rf build/r_core && mkdir -p build/r_core
"$JAVAC" -encoding UTF-8 -d build/r_core \
  app/src/com/binf/ime/BinCore.java tools/CoreTest.java 2>&1 | head -3
run "编解码核心" 37 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_core CoreTest

# 2. 拼音引擎 v2（真实词库）
rm -rf build/r_py && mkdir -p build/r_py
"$JAVAC" -encoding UTF-8 -d build/r_py \
  build/stub/android/content/Context.java \
  build/stub/android/content/res/AssetManager.java \
  app/src/com/binf/ime/PinyinEngine.java tools/PinyinTest.java 2>&1 | head -3
run "拼音引擎 v2" 46 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_py PinyinTest "$DICT"

# 3. 音节切分
rm -rf build/r_seg && mkdir -p build/r_seg
"$JAVAC" -encoding UTF-8 -d build/r_seg \
  build/stub/android/content/Context.java \
  build/stub/android/content/res/AssetManager.java \
  app/src/com/binf/ime/PinyinEngine.java \
  app/src/com/binf/ime/PinyinComposer.java \
  tools/SegmentTest.java 2>&1 | head -3
run "音节切分" 30 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_seg com.binf.ime.SegmentTest

# 4. 九键 T9（真实词库端到端）
rm -rf build/r_t9 && mkdir -p build/r_t9
"$JAVAC" -encoding UTF-8 -d build/r_t9 \
  build/stub/android/content/Context.java \
  build/stub/android/content/res/AssetManager.java \
  app/src/com/binf/ime/PinyinEngine.java \
  app/src/com/binf/ime/T9Engine.java \
  tools/T9Test.java 2>&1 | head -3
run "九键 T9" 23 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_t9 com.binf.ime.T9Test "$DICT"

# 5. 整句解码（假引擎，纯算法）
rm -rf build/r_dec && mkdir -p build/r_dec
"$JAVAC" -encoding UTF-8 -d build/r_dec \
  build/stub/android/content/Context.java \
  build/stub/android/content/res/AssetManager.java \
  app/src/com/binf/ime/PinyinEngine.java \
  app/src/com/binf/ime/T9Engine.java \
  app/src/com/binf/ime/SentenceDecoder.java \
  tools/DecodeTest.java 2>&1 | head -5
run "整句解码" 100 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_dec com.binf.ime.DecodeTest

# 6. 云端补词（离线：注入假 Transport，不联网）
rm -rf build/r_cloud && mkdir -p build/r_cloud
"$JAVAC" -encoding UTF-8 -d build/r_cloud \
  app/src/com/binf/ime/CloudSuggest.java tools/CloudTest.java 2>&1 | head -5
run "云端补词" 20 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_cloud com.binf.ime.CloudTest

# 6.5 端到端冒烟（真词库）：DecodeTest 用假引擎验算法，这里验「算法+真词库+真权重」
#     中文输出必须写 UTF-8 文件，Windows 控制台是 GBK，直接 println 会乱码
rm -rf build/r_smoke && mkdir -p build/r_smoke
"$JAVAC" -encoding UTF-8 -d build/r_smoke \
  build/stub/android/content/Context.java \
  build/stub/android/content/res/AssetManager.java \
  app/src/com/binf/ime/PinyinEngine.java \
  app/src/com/binf/ime/T9Engine.java \
  app/src/com/binf/ime/SentenceDecoder.java \
  tools/SmokeTest.java 2>&1 | head -5
run "端到端冒烟" 25 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_smoke com.binf.ime.SmokeTest "$DICT" build/smoke.log

# 7. 静态审查（读源码做约束检查，必须在项目根目录跑）
rm -rf build/r_audit && mkdir -p build/r_audit
"$JAVAC" -encoding UTF-8 -d build/r_audit \
  app/src/com/binf/ime/BinCore.java tools/StaticAudit.java 2>&1 | head -3
run "静态审查" 91 "$JAVA" -Dfile.encoding=UTF-8 -cp build/r_audit com.binf.ime.StaticAudit

echo ""
echo "════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  echo " ✅ 全部通过（累计 $TOTAL 项断言）"
  exit 0
else
  echo " ❌ $FAIL 组测试失败"
  exit 1
fi
