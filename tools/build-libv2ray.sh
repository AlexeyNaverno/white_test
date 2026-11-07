#!/usr/bin/env bash
# Собирает libv2ray.aar ИЗ ИСХОДНИКОВ (native/libv2ray = 2dust/AndroidLibXrayLite) в libs/.
#
# Это F-Droid-совместимый путь: в репозитории нет предсобранных бинарников, Xray-core
# собирается тем же Go-кодом, что и в апстриме.
#
# Требования: Go (версия из native/libv2ray/go.mod), gomobile, Android NDK.
#   export ANDROID_NDK_HOME=$ANDROID_SDK/ndk/26.3.11579264
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="native/libv2ray"
OUT="libs/libv2ray.aar"

if [ ! -f "$SRC/go.mod" ]; then
  echo "[libv2ray] нет исходников: git submodule update --init --recursive native/libv2ray" >&2
  exit 1
fi
command -v go >/dev/null || { echo "[libv2ray] нужен Go" >&2; exit 1; }
: "${ANDROID_NDK_HOME:?нужен ANDROID_NDK_HOME (путь к NDK)}"

export PATH="$PATH:$(go env GOPATH)/bin"
if ! command -v gomobile >/dev/null; then
  echo "[libv2ray] ставлю gomobile"
  go install golang.org/x/mobile/cmd/gomobile@latest
fi

mkdir -p libs "$SRC/data" "$SRC/assets"
(
  cd "$SRC"
  # geoip/geosite .dat — данные Xray (без них ядро не резолвит geo-правила)
  bash gen_assets.sh download
  cp -v data/*.dat assets/ 2>/dev/null || true
  gomobile init
  go mod tidy
  gomobile bind -v -androidapi 24 -trimpath \
    -ldflags='-s -w -buildid= -checklinkname=0' \
    -o "../../$OUT" ./
)
ls -la "$OUT"
