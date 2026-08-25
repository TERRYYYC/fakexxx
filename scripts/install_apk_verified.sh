#!/usr/bin/env bash
# install_apk_verified.sh — install an APK and PROVE which bytes are on the device.
#
# F-18 (2026-08-25): a CI-built debug APK (random per-run runner keystore) was
# installed on ZY22JHW9M4. Every later `adb install -r` failed with
# INSTALL_FAILED_UPDATE_INCOMPATIBLE — silently, because callers swallowed the
# output or skipped the exit code — while versionCode/versionName (8 / 3.0.0)
# stayed IDENTICAL, so version-based "is the new build installed?" checks read
# the stale package as current and acceptance ran false-green.
#
# This helper closes both failure classes:
#   1. install failures are LOUD: adb's exit code AND stdout are checked; the
#      full adb output (including the INSTALL_FAILED_* reason) is echoed, never
#      discarded;
#   2. identity is byte-based, never version-based: after install, the SHA-256
#      of the device's base.apk (streamed via `pm path` + `sha256sum`) must
#      equal the local artifact's SHA-256. Comparing the same build's bytes is
#      valid even across JDK/dex drift (see docs/bug-report jdk-drift history).
#
# Exit codes:
#   0  installed and byte-verified
#   2  install failed (adb rc != 0, or output has no Success)
#   3  install claimed Success but installed bytes != local bytes
#   4  package resolves to multiple APK files (splits unsupported — extend me)
#   5  package not resolvable after install
#
# Usage: install_apk_verified.sh -s SERIAL -p PACKAGE [-t] -- APK_PATH
#   -t  pass -t to adb install (test-only APKs)

set -uo pipefail

SERIAL=""
PACKAGE=""
TFLAG=0
APK=""

while [ $# -gt 0 ]; do
    case "$1" in
        -s) SERIAL="$2"; shift 2 ;;
        -p) PACKAGE="$2"; shift 2 ;;
        -t) TFLAG=1; shift ;;
        --) shift; APK="${1:-}"; shift ;;
        *) echo "install_apk_verified: unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$SERIAL" ] || [ -z "$PACKAGE" ] || [ -z "$APK" ]; then
    echo "usage: $0 -s SERIAL -p PACKAGE [-t] -- APK_PATH" >&2
    exit 2
fi
if [ ! -f "$APK" ]; then
    echo "install_apk_verified: APK not found: $APK" >&2
    exit 2
fi

local_sha() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$APK" | awk '{print $1}'
    else
        sha256sum "$APK" | awk '{print $1}'
    fi
}

LOCAL_SHA="$(local_sha)"

# ---- 1. install, loudly -------------------------------------------------------
TARGS=()
[ "$TFLAG" -eq 1 ] && TARGS+=(-t)
if ! OUT="$(adb -s "$SERIAL" install -r ${TARGS[@]+"${TARGS[@]}"} "$APK" 2>&1)"; then
    echo "INSTALL FAILED (adb exit $?): $PACKAGE" >&2
    printf '%s\n' "$OUT" >&2
    case "$OUT" in
        *UPDATE_INCOMPATIBLE*)
            echo "HINT: signatures do not match — the installed package was signed by a different key." >&2
            echo "      See F-18 (c5-evidence/f18-signer-divergence/): random CI runner keystores diverge" >&2
            echo "      from local builds. Signer is now pinned to the repo keystore; rebuild, or resolve" >&2
            echo "      the device package with the dispatch line before touching the device." >&2
            ;;
    esac
    exit 2
fi
if ! grep -q "Success" <<<"$OUT"; then
    echo "INSTALL FAILED (no Success in adb output): $PACKAGE" >&2
    printf '%s\n' "$OUT" >&2
    exit 2
fi

# ---- 2. byte identity, never version -----------------------------------------
PATHS="$(adb -s "$SERIAL" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p')"
if [ -z "$PATHS" ]; then
    echo "IDENTITY CHECK FAILED: pm path resolved nothing for $PACKAGE after install" >&2
    exit 5
fi
N_LINES="$(grep -c . <<<"$PATHS")"
if [ "$N_LINES" -ne 1 ]; then
    echo "IDENTITY CHECK FAILED: $PACKAGE resolves to $N_LINES APK files (splits unsupported by this helper):" >&2
    printf '%s\n' "$PATHS" >&2
    exit 4
fi
BASE_APK_PATH="$(grep -m1 . <<<"$PATHS")"

INSTALLED_SHA="$(adb -s "$SERIAL" shell sha256sum "$BASE_APK_PATH" 2>/dev/null | tr -d '\r' | awk '{print $1}')"
if [ -z "$INSTALLED_SHA" ]; then
    echo "IDENTITY CHECK FAILED: could not stream sha256sum from device for $BASE_APK_PATH" >&2
    exit 5
fi

if [ "$INSTALLED_SHA" != "$LOCAL_SHA" ]; then
    echo "IDENTITY MISMATCH: device bytes are NOT this build (versionCode/versionName prove nothing — F-18)" >&2
    echo "  local    apk sha256: $LOCAL_SHA" >&2
    echo "  installed apk sha256: $INSTALLED_SHA" >&2
    exit 3
fi

echo "[install-verify] $PACKAGE installed+verified sha256=$LOCAL_SHA MATCH"
exit 0
