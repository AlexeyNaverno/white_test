#!/usr/bin/env bash
# Скачивает ГОТОВЫЙ libv2ray.aar (Xray-core) в libs/ — для локальной сборки и для CI.
#
# ВАЖНО про F-Droid: готовый AAR в дереве репозитория не хранится (это запрещено Inclusion
# Policy), а скачивание бинарника во время сборки F-Droid тоже не приветствуются. Для
# F-Droid правильный путь — tools/build-libv2ray.sh: он собирает тот же AAR из исходников
# (сабмодуль native/libv2ray) через Go + gomobile. Этот скрипт — для быстрой проверки.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${LIBV2RAY_VERSION:-v26.10.31}"
OUT="libs/libv2ray.aar"
mkdir -p libs
if [ -s "$OUT" ]; then
  echo "[libv2ray] уже есть: $OUT"
  exit 0
fi
URL="https://github.com/2dust/AndroidLibXrayLite/releases/download/${VERSION}/libv2ray.aar"
echo "[libv2ray] скачиваю $URL"
curl -fL --retry 3 --max-time 1800 -o "$OUT" "$URL"
ls -la "$OUT"

# Отпечаток версии — чтобы в сборке было видно, что именно подложено
echo "$VERSION" > libs/libv2ray.version
