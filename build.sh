#!/bin/bash
# Refresh Rate Switch — 无 Gradle 手工构建脚本
# 用法: bash build.sh
# 依赖: JDK 11+, curl, unzip
# 说明: 首次运行会自动下载 Android build-tools / platform 与 Shizuku API（约 120MB）
set -e
cd "$(dirname "$0")"

SDK_DIR="${ANDROID_SDK_ROOT:-./sdk}"
BT="$SDK_DIR/android-15"
PLATFORM="$SDK_DIR/android-35"
SHZ=./shizuku-lib
APP=./app
OUT=./out
MIRROR="https://mirrors.cloud.tencent.com/AndroidSDK"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST=windows; EXE=.exe ;;
  *) HOST=linux; EXE= ;;
esac

# ---------- 0. 准备工具链 ----------
if [ ! -f "$BT/aapt2$EXE" ]; then
  echo "[0/7] 下载 build-tools 35 与 platform 35"
  mkdir -p "$SDK_DIR"
  if [ "$HOST" = windows ]; then
    curl -L -s -o "$SDK_DIR/bt.zip" "$MIRROR/build-tools_r35_windows.zip"
  else
    curl -L -s -o "$SDK_DIR/bt.zip" "$MIRROR/build-tools_r35_linux.zip"
  fi
  curl -L -s -o "$SDK_DIR/pf.zip" "$MIRROR/platform-35_r02.zip"
  unzip -q -o "$SDK_DIR/bt.zip" -d "$SDK_DIR"
  unzip -q -o "$SDK_DIR/pf.zip" -d "$SDK_DIR"
fi

if [ ! -f "$SHZ/api/classes.jar" ]; then
  echo "[0/7] 下载 Shizuku API 与 AIDL"
  mkdir -p "$SHZ"
  for a in api provider aidl shared; do
    curl -L -s -o "$SHZ/$a.aar" \
      "https://repo1.maven.org/maven2/dev/rikka/shizuku/$a/13.1.5/$a-13.1.5.aar"
    mkdir -p "$SHZ/$a"
    unzip -o "$SHZ/$a.aar" classes.jar -d "$SHZ/$a" >/dev/null
  done
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ---------- 1. 资源 ----------
echo "[1/7] aapt2 compile"
"$BT/aapt2$EXE" compile --dir "$APP/res" -o "$OUT/res.zip"

# ---------- 2. 链接 ----------
echo "[2/7] aapt2 link"
"$BT/aapt2$EXE" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM/android.jar" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --auto-add-overlay

# ---------- 3. Java ----------
echo "[3/7] javac"
find "$APP/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac -nowarn -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$PLATFORM/android.jar" \
  -classpath "$PLATFORM/android.jar;$SHZ/api/classes.jar;$SHZ/provider/classes.jar" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

# ---------- 4. dex ----------
echo "[4/7] d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 26 \
  --lib "$PLATFORM/android.jar" \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name "*.class" | tr '\n' ' ') \
  "$SHZ/api/classes.jar" \
  "$SHZ/provider/classes.jar" \
  "$SHZ/aidl/classes.jar" \
  "$SHZ/shared/classes.jar"

# ---------- 5. 合并 ----------
echo "[5/7] 合并 dex"
if command -v zip >/dev/null 2>&1; then
  ( cd "$OUT/dex" && zip -q "../base.apk" classes.dex )
else
  jar uf "$OUT/base.apk" -C "$OUT/dex" classes.dex
fi

# ---------- 6. 对齐 ----------
echo "[6/7] zipalign"
"$BT/zipalign$EXE" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

# ---------- 7. 签名 ----------
echo "[7/7] 签名"
KS=./keystore.jks
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Debug, O=Debug, C=CN" >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/rrswitch.apk" "$OUT/aligned.apk"

echo ""
echo "构建完成: $OUT/rrswitch.apk"
