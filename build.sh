#!/usr/bin/env bash
# ============================================
#  Обход Белых Списков — build APK without Gradle
#  Аналог SlideDungeon build.bat для Linux/macOS
#  Использование:
#     ./build.sh [SDK_DIR] [DEPS_DIR]
#  Переменные окружения:
#     ABIS="arm64-v8a armeabi-v7a"   (или "arm64-v8a armeabi-v7a x86_64 x86")
#     ANDROID_HOME — путь к SDK, если не передан аргументом
# ============================================
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="WhitelistBypass"
MIN_SDK=24          # tor-android AAR имеет minSdk 24
ABIS="${ABIS:-armeabi-v7a arm64-v8a}"
BUILD_DIR=build
OUT_APK="${APP_NAME}.apk"

SDK="${1:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk /home/user/deps/android-sdk; do
    [ -d "$d/platforms" ] && SDK="$d" && break
  done
fi
[ -n "$SDK" ] && [ -d "$SDK/platforms" ] || { echo "[ERROR] Android SDK не найден. Передайте путь: ./build.sh /path/to/sdk"; exit 1; }

DEPS="${2:-${DEPS_DIR:-./deps}}"

PLATFORM_DIR=$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -1)
ANDROID_JAR="$PLATFORM_DIR/android.jar"
PLATFORM_NAME=$(basename "$PLATFORM_DIR")
TARGET_SDK=$(echo "$PLATFORM_NAME" | sed 's/android-//')
BUILD_TOOLS_VER=$(ls "$SDK/build-tools" | sort -V | tail -1)
BUILD_TOOLS="$SDK/build-tools/$BUILD_TOOLS_VER"
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

command -v javac >/dev/null || { echo "[ERROR] javac не в PATH"; exit 1; }
command -v jar  >/dev/null || { echo "[ERROR] jar не в PATH"; exit 1; }
command -v keytool >/dev/null || { echo "[ERROR] keytool не в PATH"; exit 1; }
command -v python3 >/dev/null || { echo "[ERROR] нужен python3 для распаковки AAR"; exit 1; }
[ -x "$AAPT2" ] || { echo "[ERROR] aapt2 не найден: $AAPT2"; exit 1; }
[ -x "$ZIPALIGN" ] || { echo "[ERROR] zipalign не найден: $ZIPALIGN"; exit 1; }
[ -x "$APKSIGNER" ] || { echo "[ERROR] apksigner не найден: $APKSIGNER"; exit 1; }

echo "Platform: $PLATFORM_DIR  target=$TARGET_SDK  min=$MIN_SDK"
echo "Build tools: $BUILD_TOOLS   ABIS: $ABIS"

# --- Зависимости (AAR/JAR) ---
if [ ! -f "$DEPS/libv2ray.aar" ] || [ ! -f "$DEPS/tor-android.aar" ]; then
  echo "[deps] скачиваю зависимости…"
  bash tools/fetch_deps.sh "$DEPS"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/classes" "$BUILD_DIR/dex" \
         "$BUILD_DIR/deps-classes/xray" "$BUILD_DIR/deps-classes/tor" "$BUILD_DIR/deps-classes/lbc"

# --- Распаковать AAR (classes.jar + jni/*.so) ---
python3 - "$DEPS" "$BUILD_DIR" <<'PY'
import sys, zipfile, os
deps, build = sys.argv[1], sys.argv[2]
jobs = [
    ("libv2ray.aar", "xray"),
    ("tor-android.aar", "tor"),
    ("localbroadcastmanager-1.1.0.aar", "lbc"),
]
for aar, name in jobs:
    src = os.path.join(deps, aar)
    dst = os.path.join(build, "deps-aar", name)
    os.makedirs(dst, exist_ok=True)
    with zipfile.ZipFile(src) as z:
        z.extractall(dst)
    # classes.jar → deps-classes/<name>
    cjar = os.path.join(dst, "classes.jar")
    cdst = os.path.join(build, "deps-classes", name)
    with zipfile.ZipFile(cjar) as z:
        z.extractall(cdst)
print("AAR unpack done")
PY

# --- [1/7] aapt2 compile ---
echo "[1/7] aapt2 compile"
"$AAPT2" compile --dir res -o "$BUILD_DIR/compiled_res"

echo "  packing .flat -> resources.zip"
(cd "$BUILD_DIR/compiled_res" && rm -f resources.zip && zip -q resources.zip -- *.flat) || \
(cd "$BUILD_DIR/compiled_res" && rm -f resources.zip && jar cfM resources.zip *.flat) || \
(cd "$BUILD_DIR/compiled_res" && rm -f resources.zip && tar -a -c -f resources.zip *.flat)

# --- [2/7] aapt2 link ---
echo "[2/7] aapt2 link"
"$AAPT2" link -o "$BUILD_DIR/base.apk" -I "$ANDROID_JAR" --manifest "AndroidManifest.xml" \
  --java "$BUILD_DIR" -A assets --min-sdk-version $MIN_SDK --target-sdk-version "$TARGET_SDK" \
  "$BUILD_DIR/compiled_res/resources.zip"

R_JAVA=$(find "$BUILD_DIR" -name R.java | head -1)
echo "  R.java: $R_JAVA"

# --- [3/7] javac ---
echo "[3/7] javac"
JAVA_FILES=$(find src -name '*.java')
CLASSPATH="$BUILD_DIR/deps-classes/xray:$BUILD_DIR/deps-classes/tor:$BUILD_DIR/deps-classes/lbc:$DEPS/jtorctl-0.4.5.7.jar:$DEPS/kotlin-stdlib-2.2.10.jar"
javac -encoding UTF-8 --release 8 -nowarn -classpath "$ANDROID_JAR:$CLASSPATH" \
  -d "$BUILD_DIR/classes" $JAVA_FILES "$R_JAVA"

# --- [4/7] d8 ---
echo "[4/7] d8"
for name in xray tor lbc; do
  (cd "$BUILD_DIR/deps-classes/$name" && jar cfM "../deps-$name-classes.jar" .)
done
CLASS_FILES=$(find "$BUILD_DIR/classes" -name '*.class')
"$D8" --lib "$ANDROID_JAR" --min-api $MIN_SDK --output "$BUILD_DIR/dex" $CLASS_FILES \
  "$BUILD_DIR/deps-classes/deps-xray-classes.jar" "$BUILD_DIR/deps-classes/deps-tor-classes.jar" "$BUILD_DIR/deps-classes/deps-lbc-classes.jar" \
  "$DEPS/jtorctl-0.4.5.7.jar" "$DEPS/kotlin-stdlib-2.2.10.jar"

# --- [5/7] jar uf: classes.dex + jni libs ---
echo "[5/7] jar uf"
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unaligned.apk"
jar uf "$BUILD_DIR/unaligned.apk" -C "$BUILD_DIR/dex" classes.dex

STAGING="$BUILD_DIR/apk-libs"
mkdir -p "$STAGING"
for ABI in $ABIS; do
  mkdir -p "$STAGING/lib/$ABI"
  cp "jniLibs/$ABI/"*.so "$STAGING/lib/$ABI/"
  cp "$BUILD_DIR/deps-aar/xray/jni/$ABI/libgojni.so" "$STAGING/lib/$ABI/" 2>/dev/null || echo "  [warn] net libgojni.so dlya $ABI"
  cp "$BUILD_DIR/deps-aar/tor/jni/$ABI/libtor.so"    "$STAGING/lib/$ABI/" 2>/dev/null || echo "  [warn] net libtor.so dlya $ABI"
done
jar uf "$BUILD_DIR/unaligned.apk" -C "$STAGING" lib

# --- [6/7] zipalign ---
echo "[6/7] zipalign"
"$ZIPALIGN" -f 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

# --- [7/7] apksigner ---
echo "[7/7] apksigner"
if [ ! -f debug.keystore ]; then
  echo "  generating debug.keystore"
  keytool -genkeypair -v -keystore debug.keystore -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Android Debug,O=WhitelistBypass,C=RU"
fi
if [ -n "${APKSIGNER_KEY:-}" ] && [ -n "${APKSIGNER_CERT:-}" ]; then
  # Подпись сырыми PEM (для старых JDK, не читающих PKCS12/HmacPBESHA256)
  "$APKSIGNER" sign --key "$APKSIGNER_KEY" --cert "$APKSIGNER_CERT" \
    --out "$OUT_APK" "$BUILD_DIR/aligned.apk"
else
  "$APKSIGNER" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_APK" "$BUILD_DIR/aligned.apk"
fi

echo
echo "============================================"
echo " DONE: $OUT_APK"
ls -la "$OUT_APK"
sha256sum "$OUT_APK" 2>/dev/null || true
echo " Install: adb install -r $OUT_APK"
echo "============================================"
