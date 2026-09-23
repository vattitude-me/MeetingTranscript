#!/bin/bash
# Fetches the prebuilt sherpa-onnx static libraries and the C header that
# describes them into mac/vendor/. Run once before `swift build`.
#
# Both come from the same tagged release and are checked against pinned
# hashes, so the header can never describe a different library than the one
# that gets linked. Nothing is downloaded if vendor/ is already complete.
set -euo pipefail

VERSION=1.13.8
LIB_SHA=3d7f9b8a496694af13d9802c33b8133231e397bdef302f543d19468765e83136
HEADER_SHA=2a1b95084be8fd1deb3228fcad2fd3f7f0258b64582f7402281ec174c7b7f4ce

here="$(cd "$(dirname "$0")/.." && pwd)"
vendor="${VENDOR:-$here/vendor}"
name="sherpa-onnx-v$VERSION-osx-arm64-static-no-tts-lib"
tarball="$vendor/$name.tar.bz2"
header="$vendor/include/sherpa-onnx/c-api/c-api.h"

check() { [ "$(shasum -a 256 "$1" | cut -d' ' -f1)" = "$2" ]; }

mkdir -p "$vendor/include/sherpa-onnx/c-api"

if [ -f "$vendor/$name/lib/libsherpa-onnx-c-api.a" ] && [ -f "$header" ] && check "$header" "$HEADER_SHA"; then
    echo "vendor/ is ready (sherpa-onnx $VERSION)."
    exit 0
fi

if [ ! -f "$tarball" ] || ! check "$tarball" "$LIB_SHA"; then
    echo "Downloading sherpa-onnx $VERSION static libraries (~19 MB)…"
    curl -fL --progress-bar -o "$tarball.part" \
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$name.tar.bz2"
    check "$tarball.part" "$LIB_SHA" || { echo "Checksum mismatch for $name.tar.bz2" >&2; exit 1; }
    mv "$tarball.part" "$tarball"
fi
tar xjf "$tarball" -C "$vendor"

echo "Downloading the matching C header…"
curl -fsSL -o "$header.part" \
    "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$VERSION/sherpa-onnx/c-api/c-api.h"
check "$header.part" "$HEADER_SHA" || { echo "Checksum mismatch for c-api.h" >&2; exit 1; }
mv "$header.part" "$header"

echo "vendor/ is ready (sherpa-onnx $VERSION)."
