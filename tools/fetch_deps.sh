#!/usr/bin/env bash
# Скачивает зависимости (не хранятся в репозитории):
#   libv2ray.aar  — Xray-core (VLESS/Trojan/SS/VMess), GitHub Releases 2dust/AndroidLibXrayLite
#   tor-android.aar + jtorctl + kotlin-stdlib + localbroadcastmanager — Tor (Maven Central / Google)
# Использование: ./tools/fetch_deps.sh [DEPS_DIR]
set -euo pipefail
DEPS="${1:-./deps}"
mkdir -p "$DEPS"
cd "$DEPS"

echo "[1/5] libv2ray.aar (Xray-core, ~60 МБ)…"
[ -s libv2ray.aar ] || curl -fL --retry 3 --max-time 1800 -o libv2ray.aar \
  "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.31/libv2ray.aar"

echo "[2/5] tor-android.aar (Tor 0.4.9.13.2, ~13 МБ)…"
[ -s tor-android.aar ] || curl -fL --retry 3 --max-time 600 -o tor-android.aar \
  "https://repo1.maven.org/maven2/info/guardianproject/tor-android/0.4.9.13.2/tor-android-0.4.9.13.2.aar"

echo "[3/5] jtorctl-0.4.5.7.jar…"
[ -s jtorctl-0.4.5.7.jar ] || curl -fL --retry 3 --max-time 300 -o jtorctl-0.4.5.7.jar \
  "https://repo1.maven.org/maven2/info/guardianproject/jtorctl/0.4.5.7/jtorctl-0.4.5.7.jar"

echo "[4/5] kotlin-stdlib-2.2.10.jar…"
[ -s kotlin-stdlib-2.2.10.jar ] || curl -fL --retry 3 --max-time 600 -o kotlin-stdlib-2.2.10.jar \
  "https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.10/kotlin-stdlib-2.2.10.jar"

echo "[5/5] localbroadcastmanager-1.1.0.aar…"
[ -s localbroadcastmanager-1.1.0.aar ] || curl -fL --retry 3 --max-time 300 -o localbroadcastmanager-1.1.0.aar \
  "https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.1.0/localbroadcastmanager-1.1.0.aar"

echo "[deps] готово: $DEPS"
ls -la "$DEPS"
