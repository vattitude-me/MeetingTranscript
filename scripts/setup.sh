#!/usr/bin/env bash
# Fetches the prebuilt sherpa-onnx Android AAR that Scribe links against.
# It is ~48 MB, so it is not committed.
set -euo pipefail

VERSION="${SHERPA_VERSION:-1.13.8}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/libs"
FILE="$DEST/sherpa-onnx-$VERSION.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"

mkdir -p "$DEST"
if [ -f "$FILE" ]; then
  echo "Already present: $FILE"
  exit 0
fi

echo "Downloading sherpa-onnx $VERSION…"
curl -fL --progress-bar -o "$FILE.part" "$URL"
mv "$FILE.part" "$FILE"
echo "Saved $FILE"

if [ ! -f "$ROOT/local.properties" ]; then
  echo
  echo "Note: local.properties is missing. Create it with your SDK path, e.g."
  echo "  echo \"sdk.dir=\$HOME/Library/Android/sdk\" > $ROOT/local.properties"
fi
