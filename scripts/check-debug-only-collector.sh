#!/usr/bin/env bash
# check-debug-only-collector.sh — G2 §3 P10 hard boundary 1: the fault/revoke
# collector must exist ONLY in debug builds.
#
# Usage:
#   scripts/check-debug-only-collector.sh <app-module-dir> [--apk <path>]
#
#     <app-module-dir>   e.g. apps/qianwangyou/app or apps/cellrebel-auto/app
#     --apk <path>       optional release APK to byte-scan for collector markers
#
# Three checks:
#   1. SOURCE PURITY  — collector symbols must be absent from src/main (the
#                       release variant compiles main only, so absence there
#                       + no debug source in release = release cannot carry it)
#   2. NON-VACUOUS    — the marker must EXIST in src/debug, so a wholesale
#                       deletion of the collector cannot pass silently
#   3. APK SCAN       — when --apk is given, every classes*.dex must be free of
#                       the collector marker string. This is the built-artifact
#                       half of the proof: source placement is necessary but
#                       the release APK is what actually ships.
#
# Exit 0 = all pass; 1 = violation found; 2 = usage/setup error.

set -uo pipefail

MARKER="P10DBG-COLLECTOR-V1"
# Symbols that must never appear in a production source set. Class names are
# the load-bearing set; the marker is the APK-scan key.
COLLECTOR_SYMBOLS=(
  "P10DBG-COLLECTOR-V1"
  "FaultCollectorActivity"
  "ProviderRevokeCollectorActivity"
  "QwyDurableSnapshot"
  "CollectorGate"
  "RevokeCollectorGate"
  "AutoArmRecordCodec"
  "ArmRecordCodec"
)

APP_DIR=""
APK=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:?--apk needs a path}"; shift 2 ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) if [ -n "$APP_DIR" ]; then echo "unexpected extra arg: $1" >&2; exit 2; fi
       APP_DIR="$1"; shift ;;
  esac
done

if [ -z "$APP_DIR" ] || [ ! -d "$APP_DIR" ]; then
  echo "app module dir not found: ${APP_DIR:-<missing>}" >&2
  echo "usage: $0 <app-module-dir> [--apk <path>]" >&2
  exit 2
fi

fail=0

# ---- 1. source purity -----------------------------------------------------
MAIN_SRC="$APP_DIR/src/main"
if [ ! -d "$MAIN_SRC" ]; then
  echo "FAIL: no src/main under $APP_DIR — wrong dir?" >&2
  exit 2
fi
for symbol in "${COLLECTOR_SYMBOLS[@]}"; do
  hits=$(grep -rF -- "$symbol" "$MAIN_SRC" --include='*.kt' --include='*.java' --include='*.xml' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$hits" -ne 0 ]; then
    echo "FAIL: collector symbol '$symbol' found in PRODUCTION sources under $MAIN_SRC ($hits hits)"
    grep -rF -- "$symbol" "$MAIN_SRC" --include='*.kt' --include='*.java' --include='*.xml' | head -5
    fail=1
  fi
done

# ---- 2. non-vacuous -------------------------------------------------------
DEBUG_SRC="$APP_DIR/src/debug"
if [ ! -d "$DEBUG_SRC" ]; then
  echo "FAIL: no src/debug under $APP_DIR — the collector is REQUIRED to live there (P10)"
  fail=1
else
  debug_hits=$(grep -rF -- "$MARKER" "$DEBUG_SRC" --include='*.kt' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$debug_hits" -eq 0 ]; then
    echo "FAIL: marker '$MARKER' absent from src/debug — either the collector was deleted or the marker drifted; this guard must not pass vacuously"
    fail=1
  fi
fi

# ---- 3. release APK byte scan ---------------------------------------------
if [ -n "$APK" ]; then
  if [ ! -f "$APK" ]; then
    echo "FAIL: --apk path not found: $APK" >&2
    exit 2
  fi
  # Read every dex entry and search for the marker as raw bytes. The marker is
  # a compile-time string constant: if ANY collector code reached the release
  # dex pool, its bytes appear. (grep -a: dex bytes are binary.)
  marker_hits=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -a -c "$MARKER" || true)
  if [ "${marker_hits:-0}" -ne 0 ]; then
    echo "FAIL: collector marker '$MARKER' found in release APK $APK — production must not carry the collector"
    fail=1
  fi
  # Also scan for collector class names in the dex string pool.
  for symbol in FaultCollectorActivity ProviderRevokeCollectorActivity; do
    sym_hits=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -a -c "$symbol" || true)
    if [ "${sym_hits:-0}" -ne 0 ]; then
      echo "FAIL: collector class '$symbol' found in release APK $APK"
      fail=1
    fi
  done
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
apk_note=""
[ -n "$APK" ] && apk_note=" (+ release APK scanned)"
echo "ok: debug-only collector boundary holds for $APP_DIR$apk_note"
