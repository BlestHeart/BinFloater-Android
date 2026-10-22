#!/usr/bin/env bash
# 手工构建 APK：aapt2 + javac + d8 + zipalign + apksigner
# 不用 Gradle，避免拉取 maven.google.com（本机直连超时）
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="/f/AndroidSdk"
BT="$SDK/build-tools/36.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="/c/Program Files/Zulu/zulu-21"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

OUT="$PROJ/build"
APK_NAME="BinFloater.apk"
PKG="com.binf.floater"
MIN_SDK=34
TARGET_SDK=36
VERSION_CODE=2
VERSION_NAME="2.0"

echo "==> 清理"
rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/flat" "$OUT/base.apk" "$OUT/$APK_NAME"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/flat"

echo "==> 1/7 aapt2 compile 资源"
"$BT/aapt2.exe" compile --dir "$PROJ/app/res" -o "$OUT/flat/res.zip"

echo "==> 2/7 aapt2 link 生成 R.java + 基础 APK"
# assets 目录（拼音词典）通过 -A 打进 APK
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/app/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/flat/res.zip"

echo "==> 3/7 编译 Java 源码"
mapfile -t SRC < <(find "$PROJ/app/src" "$OUT/gen" -name '*.java')
echo "    共 ${#SRC[@]} 个源文件"
"$JAVAC" -encoding UTF-8 --release 17 \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  "${SRC[@]}"

echo "==> 4/7 d8 转 dex"
mapfile -t CLASSFILES < <(find "$OUT/classes" -name '*.class')
"$BT/d8.bat" --min-api $MIN_SDK --output "$OUT/dex" "${CLASSFILES[@]}"

echo "==> 5/7 组装 APK（塞入 classes.dex）"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python - "$OUT/unsigned.apk" "$OUT/dex" <<'PYEOF'
import sys, zipfile, os, glob
apk, dexdir = sys.argv[1], sys.argv[2]
dexes = sorted(glob.glob(os.path.join(dexdir, "classes*.dex")))
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    for d in dexes:
        z.write(d, os.path.basename(d))
        print("    +", os.path.basename(d), os.path.getsize(d), "bytes")
PYEOF
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 6/7 生成签名密钥（首次）"
# 密钥放 signing/ 而不是 build/：build/ 是中间产物目录，随时可清。
# 密钥一旦丢了，下次构建出来的签名就变了，手机上没法覆盖安装旧版。
#
# 注意：signing/*.keystore 在 .gitignore 里，不进版本库。
# 密钥 + 口令进公开仓库 = 任何人都能签出同包名同签名的 APK，
# 手机会把它当成正常更新装进去。所以密钥只留在本地，自己备份好。
KEYSTORE="$PROJ/signing/binf.keystore"
# 口令可以用环境变量覆盖；不设就用这个默认值。
# 密钥文件本身不公开，所以默认口令不算秘密。
KS_PASS="${BINF_KS_PASS:-binf123456}"
mkdir -p "$PROJ/signing"
if [ ! -f "$KEYSTORE" ]; then
  echo "    没找到密钥，生成一个新的（首次构建）"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias binf \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=BinFloater, OU=Binf, O=Binf, L=CN, ST=CN, C=CN" 2>&1 | tail -3
else
  echo "    复用已有密钥 signing/binf.keystore"
fi

echo "==> 7/7 apksigner 签名"
"$BT/apksigner.bat" sign \
  --ks "$KEYSTORE" --ks-key-alias binf \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/$APK_NAME" \
  "$OUT/aligned.apk"

echo "==> 验证签名"
"$BT/apksigner.bat" verify --print-certs "$OUT/$APK_NAME" | head -6

echo
echo "✅ 构建完成: $OUT/$APK_NAME"
ls -lh "$OUT/$APK_NAME"
