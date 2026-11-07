#!/usr/bin/env bash
# Release build of Theta Memo Hatch, signed with the Theta Hatch series key (secrets/README.md in
# Theta.Hatch.Series). Nothing secret is in the repository: the key and its passwords come from the
# environment, as release.yml passes them from the repository Secrets.
#   SIGNING_FILE       the keystore (.jks)
#   ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
set -euo pipefail
cd "$(dirname "$0")"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" ]]; then echo 'Set ANDROID_SDK_ROOT to your Android SDK directory.' >&2; exit 1; fi
: "${SIGNING_FILE:?SIGNING_FILE (the keystore) is required}"
: "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD is required}"
: "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS is required}"
KEY_PASSWORD="${ANDROID_KEY_PASSWORD:-$ANDROID_KEYSTORE_PASSWORD}"
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
VERSION="$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" app/build.gradle)"
OUT="build/ThetaMemoHatch-$VERSION.apk"
export ANDROID_SDK_ROOT="$SDK_DIR"
./gradlew :app:assembleRelease --no-daemon
mkdir -p build
"$BUILD_TOOLS/zipalign" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_FILE" --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass "pass:$KEY_PASSWORD" --out "$OUT" build/aligned.apk
rm -f build/aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$OUT"
# The series key (Theta.Hatch.Series secrets/README.md). Anything else must not be published.
"$BUILD_TOOLS/apksigner" verify --print-certs "$OUT" | grep -qi "SHA-256 digest: f47740d8549bc1c37feb00b18780d80b52758b60252ba0d2217d83ab3f012a23" \
  || { echo "The APK is not signed with the Theta Hatch series key" >&2; exit 1; }
echo "Built $OUT"
