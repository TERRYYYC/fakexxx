#!/usr/bin/env bash
# check-debug-signer.sh — assert an APK is signed by the committed repo keystore.
#
# F-18 (2026-08-25): CI runners generate a RANDOM ~/.android/debug.keystore per
# run, so every downloaded CI debug APK carried a different random signer
# (observed: 5f7b44cb, 53fd8e58, 6fef77c0, 9137a0ec, … within one evening) and
# could never `adb install -r` over a local build. The random-signer artifact
# that reached device ZY22JHW9M4 was run 32763136108 / commit d3008ea —
# byte-identical to the sealed c5-evidence/f18-signer-divergence package.
#
# The keystore is now committed (build identity is repo state, not machine
# state). This guard proves a produced APK actually carries that signer, so a
# keystore-path regression or an unsigned/random artifact fails the build.
#
# Usage: check-debug-signer.sh KEYSTORE APK
# Exit 0 = signer matches; 2 = mismatch / missing tool / bad input.

set -uo pipefail

KS="${1:-}"
APK="${2:-}"
[ -n "$KS" ] && [ -f "$KS" ] || { echo "check-debug-signer: keystore missing: $KS" >&2; exit 2; }
[ -n "$APK" ] && [ -f "$APK" ] || { echo "check-debug-signer: apk missing: $APK" >&2; exit 2; }

find_keytool() {
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/keytool" ]; then
        echo "${JAVA_HOME}/bin/keytool"; return 0
    fi
    if command -v keytool >/dev/null 2>&1; then
        command -v keytool; return 0
    fi
    local mac_jbr="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
    if [ -x "$mac_jbr" ]; then
        echo "$mac_jbr"; return 0
    fi
    return 1
}

find_apksigner() {
    if command -v apksigner >/dev/null 2>&1; then
        command -v apksigner; return 0
    fi
    local root candidates best
    for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [ -n "$root" ] || continue
        best="$(ls -1 "$root"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
        if [ -n "$best" ] && [ -x "$best" ]; then
            echo "$best"; return 0
        fi
    done
    return 1
}

KEYTOOL="$(find_keytool)" || {
    echo "check-debug-signer: keytool not found (set JAVA_HOME, or put it on PATH)" >&2
    exit 2
}
APKSIGNER="$(find_apksigner)" || {
    echo "check-debug-signer: apksigner not found (set ANDROID_HOME, or put it on PATH)" >&2
    exit 2
}

normalize() { tr -d ':' | tr 'A-F' 'a-f'; }

KS_SHA="$("$KEYTOOL" -list -v -keystore "$KS" -storepass android -alias androiddebugkey 2>/dev/null |
    awk '/SHA256:/ {print $2}' | head -1 | normalize)"
if [ -z "$KS_SHA" ]; then
    echo "check-debug-signer: could not read SHA-256 from keystore: $KS (alias androiddebugkey, storepass android)" >&2
    exit 2
fi

APK_SHA="$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null |
    awk '/SHA-256 digest/ {print $NF}' | head -1 | normalize)"
if [ -z "$APK_SHA" ]; then
    echo "check-debug-signer: could not read signer SHA-256 from APK: $APK" >&2
    exit 2
fi

if [ "$KS_SHA" != "$APK_SHA" ]; then
    echo "SIGNER MISMATCH (F-18): APK is not signed by the committed keystore" >&2
    echo "  keystore cert sha256: $KS_SHA" >&2
    echo "  apk signer   sha256: $APK_SHA" >&2
    echo "  A random/mismatched debug signer makes every adb install -r fail with" >&2
    echo "  INSTALL_FAILED_UPDATE_INCOMPATIBLE against builds from this repo." >&2
    exit 2
fi

echo "signer ok: $APK"
echo "  cert sha256: $APK_SHA"
exit 0
