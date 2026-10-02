#!/usr/bin/env bash
# Verify a Morsel release APK before trusting it:
#   - the signing certificate matches the expected SHA-256 fingerprint;
#   - the package identity is io.evren.morsel.
#
# Usage: scripts/verify-apk.sh PATH_TO_APK EXPECTED_CERT_SHA256
# The expected fingerprint is uppercase hex; colons are accepted and stripped.
# apksigner/aapt are taken from $ANDROID_HOME build-tools when not on PATH.
set -euo pipefail

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 PATH_TO_APK EXPECTED_CERT_SHA256" >&2
    exit 2
fi

APK=$1
EXPECTED=$(printf '%s' "$2" | tr -d ':' | tr '[:lower:]' '[:upper:]')

if [ ! -f "$APK" ]; then
    echo "FAIL: APK not found: $APK" >&2
    exit 2
fi

BUILD_TOOLS_DIR=""
if [ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME/build-tools" ]; then
    BUILD_TOOLS_DIR=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
fi

if command -v apksigner > /dev/null 2>&1; then
    APKSIGNER=apksigner
else
    APKSIGNER="$BUILD_TOOLS_DIR/apksigner"
fi
if command -v aapt > /dev/null 2>&1; then
    AAPT=aapt
else
    AAPT="$BUILD_TOOLS_DIR/aapt"
fi

if [ -z "${BUILD_TOOLS_DIR:-}" ] && [ ! -x "$APKSIGNER" ]; then
    echo "FAIL: apksigner not found; set ANDROID_HOME or put apksigner on PATH" >&2
    exit 2
fi

ACTUAL="$("$APKSIGNER" verify --print-certs "$APK" | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr -d ':' | tr '[:lower:]' '[:upper:]')"
if [ -z "$ACTUAL" ]; then
    echo "FAIL: could not read a signing certificate from $APK" >&2
    exit 1
fi
if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "FAIL: certificate mismatch" >&2
    echo "  expected: $EXPECTED" >&2
    echo "  actual:   $ACTUAL" >&2
    exit 1
fi

if [ -x "$AAPT" ] || command -v aapt > /dev/null 2>&1; then
    PACKAGE="$("$AAPT" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
    VERSION="$("$AAPT" dump badging "$APK" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    if [ "$PACKAGE" != "io.evren.morsel" ]; then
        echo "FAIL: unexpected package name: $PACKAGE" >&2
        exit 1
    fi
else
    PACKAGE="(aapt unavailable)"
    VERSION=""
fi

"$APKSIGNER" verify "$APK"

echo "OK: $APK"
echo "  package:     $PACKAGE"
[ -n "$VERSION" ] && echo "  versionName: $VERSION"
echo "  cert sha256: $ACTUAL"
