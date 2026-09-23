#!/bin/bash
# Builds "Meeting Transcript.app" from source. No Apple Developer account:
# the bundle is signed with the local "Meeting Transcript Local" identity if
# scripts/make-signing-identity.sh has created one, and ad-hoc otherwise.
#
#   scripts/build-app.sh             → build/Meeting Transcript.app
#   scripts/build-app.sh --install   → also copies it to ~/Applications
#
# Ad-hoc is enough to run the app. The local identity only matters because
# macOS ties the Microphone and Screen Recording grants to the signature: an
# ad-hoc signature changes with every build, so each rebuild asks again. A
# stable identity keeps the grants across rebuilds.
set -euo pipefail

IDENTITY="Meeting Transcript Local"
here="$(cd "$(dirname "$0")/.." && pwd)"
out="${OUT:-$here/build}"
app="$out/Meeting Transcript.app"
install=false
[ "${1:-}" = "--install" ] && install=true

"$here/scripts/setup.sh"

echo "Building (release)…"
swift build -c release --package-path "$here" --product MeetingTranscript
bin="$(swift build -c release --package-path "$here" --show-bin-path)/MeetingTranscript"

echo "Assembling $app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$here/Resources/Info.plist" "$app/Contents/Info.plist"
cp "$bin" "$app/Contents/MacOS/MeetingTranscript"
printf 'APPL????' > "$app/Contents/PkgInfo"
sips -s format icns "$here/../docs/img/logo.png" --out "$app/Contents/Resources/AppIcon.icns" >/dev/null

# Self-signed, so find-identity lists it as not trusted. codesign does not
# care; it only needs the key, and nothing verifies against a CA locally.
if security find-identity -p codesigning | grep -q "\"$IDENTITY\""; then
    echo "Signing with \"$IDENTITY\""
    codesign --force --sign "$IDENTITY" "$app"
else
    echo "Signing ad-hoc (run scripts/make-signing-identity.sh once to keep permissions across rebuilds)"
    codesign --force --sign - "$app"
fi
codesign --verify --strict "$app"

if $install; then
    mkdir -p "$HOME/Applications"
    rm -rf "$HOME/Applications/Meeting Transcript.app"
    ditto "$app" "$HOME/Applications/Meeting Transcript.app"
    app="$HOME/Applications/Meeting Transcript.app"
    echo "Installed to $app"
fi

echo
echo "Done. Open it with:  open \"$app\""
