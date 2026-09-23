#!/bin/bash
# Creates a self-signed code-signing certificate named "Meeting Transcript
# Local" in your login keychain. Optional, free, and needed only once.
#
# Why: macOS remembers Microphone and Screen Recording permission per code
# signature. An ad-hoc signature is different on every build, so after each
# rebuild the app has to be granted again. Signing every build with the same
# local certificate keeps those grants. The certificate never leaves this Mac
# and is not trusted for anything but signing what you build yourself.
#
# Remove it any time in Keychain Access → login → My Certificates.
set -euo pipefail

NAME="Meeting Transcript Local"
# codesign only searches keychains in the search list, so leave this as the
# login keychain; the override exists to test the script in a scratch one.
keychain="${SIGN_KEYCHAIN:-$HOME/Library/Keychains/login.keychain-db}"

if security find-identity -p codesigning "$keychain" | grep -q "\"$NAME\""; then
    echo "\"$NAME\" already exists. Nothing to do."
    exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pass="$(openssl rand -hex 16)"

openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -subj "/CN=$NAME" \
    -addext "keyUsage=critical,digitalSignature" \
    -addext "extendedKeyUsage=critical,codeSigning" \
    -addext "basicConstraints=critical,CA:false" \
    -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null
# -legacy: the Keychain cannot read OpenSSL 3's default PKCS#12 encryption.
legacy=()
openssl pkcs12 -help 2>&1 | grep -q -- '-legacy' && legacy=(-legacy)
openssl pkcs12 -export ${legacy[@]+"${legacy[@]}"} -name "$NAME" \
    -inkey "$work/key.pem" -in "$work/cert.pem" \
    -out "$work/id.p12" -passout "pass:$pass"

# -T: codesign may use the key without asking for the keychain password.
security import "$work/id.p12" -k "$keychain" -P "$pass" -T /usr/bin/codesign >/dev/null

echo "Created \"$NAME\". Rebuild with scripts/build-app.sh — it will sign with it."
echo "The first launch after switching asks for Microphone and Screen Recording once more."
