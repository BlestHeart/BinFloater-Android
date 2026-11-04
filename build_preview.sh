#!/usr/bin/env bash
set -euo pipefail
# 预览版独立于正式 APK：保留旧 dist/BinFloater.apk 和旧源码。
PROJ="$(cd "$(dirname "$0")" && pwd)"
bash "$PROJ/build.sh"
mkdir -p "$PROJ/dist/previews"
cp "$PROJ/build/BinFloater.apk" "$PROJ/dist/previews/BinFloater-ui-preview.apk"
printf '预览 APK：dist/previews/BinFloater-ui-preview.apk\n'
