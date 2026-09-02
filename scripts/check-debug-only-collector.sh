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

# Debug-only surface inventory, per app (G2-Row2 hard boundary 2: production
# 零携带 for everything the Row 2 execution plane drives).
#
# 2026-08-28 extension (exec-plane lane): the original list covered only the
# #52 fault/revoke collector classes. Since then the Row 2 evidence contract
# (PR #55) binds THREE more debug-only classes as canonical `am start`
# components — MockProviderAcceptanceActivity (SET-01), HandshakeProbeActivity
# (SET-02), FullLoopProbeActivity (INJ-02/RST-01) — and PairingApprovalActivity
# plus the HookAcceptance* family are equally debug-only. A leak of any of
# them into src/main or the release dex pool was invisible to the guard: the
# recorded red was the two comment-only references in SerialProbeRunner.kt.
#
# Deliberately NOT on any list:
#   - DebugHookProbeController: variant-abstracted on purpose — the real
#     controller lives in src/debug and a no-op stub with the SAME class name
#     ships in src/release (ComposeActivity references it from src/main), so
#     the name legitimately appears in release bytes.
#   - SerialProbeRunner: intentionally src/main production code.
#
# NOTE: symbols are CONTENT-declared types, not file names — the gate files
# (RevokeCollectorGate.kt / CollectorGate.kt) declare objects under their own
# names (RevokeReadback, AutoArmRecordCodec, ArmRecordCodec, QwyRevokeProof),
# and the per-symbol non-vacuous arm below greps content, so filename-derived
# entries are wrong in both directions (dead names pass vacuously, real
# classes go unguarded).
#
# Structure: per-app lists, because the two apps carry DIFFERENT debug-only
# surfaces (the qwy collector vs the auto probes). A symbol is banned from
# THIS app's src/main and scanned for in THIS app's release APK only when the
# class actually lives in this app's src/debug — enforced per-symbol by the
# non-vacuous arm below (renames now fail loudly instead of scanning for
# names that can never appear).
APP_SYMBOLS_CELLREBEL_AUTO=(
  "FullLoopProbeActivity"
  "HandshakeProbeActivity"
  "ProviderRevokeCollectorActivity"
  "RevokeReadback"
  "AutoArmRecordCodec"
  "ExtraCoerce"
  # G2 §5A seed/run surface (backfill): the seed Activity + its pure plan logic.
  "APlusSeedActivity"
  "APlus10APlanSeed"
)
APP_SYMBOLS_QIANWANGYOU=(
  "FaultCollectorActivity"
  "MockProviderAcceptanceActivity"
  "PairingApprovalActivity"
  "HookAcceptanceActivity"
  "HookAcceptanceApplication"
  "HookAcceptancePayload"
  "HookAcceptanceRecovery"
  "HookAcceptanceRecoveryCoordinator"
  "HookAcceptanceStateMachine"
  "HookProbeRunner"
  "QwyDurableSnapshot"
  "QwyRevokeProof"
  "ArmRecordCodec"
  "ExtraCoerce"
  # G2 §5A 10-address explicit-id fixture seeder (backfill).
  "APlus10AFixtureSeed"
  "APlus10AScheduleReset"
  # R6 P1-1: reflected owner-lock seam for the fenced seed critical section.
  "APlus10AOwnerFence"
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

# Resolve the per-app symbol list from the module directory path.
app_symbols() { # app-module-dir -> prints this app's banned symbols, one/line
  case "$1" in
    *cellrebel-auto*) printf '%s\n' "${APP_SYMBOLS_CELLREBEL_AUTO[@]}" ;;
    *qianwangyou*)    printf '%s\n' "${APP_SYMBOLS_QIANWANGYOU[@]}" ;;
    *)
      # Unknown app: fall back to the union so a NEW app module is not silently
      # unguarded (it fails the non-vacuous arm below unless it actually
      # carries the classes, which is the honest outcome for a new app).
      printf '%s\n' "${APP_SYMBOLS_CELLREBEL_AUTO[@]}" "${APP_SYMBOLS_QIANWANGYOU[@]}"
      ;;
  esac
}

# ---- 1. source purity -----------------------------------------------------
MAIN_SRC="$APP_DIR/src/main"
if [ ! -d "$MAIN_SRC" ]; then
  echo "FAIL: no src/main under $APP_DIR — wrong dir?" >&2
  exit 2
fi
while IFS= read -r symbol; do
  [ -n "$symbol" ] || continue
  hits=$(grep -rF -- "$symbol" "$MAIN_SRC" --include='*.kt' --include='*.java' --include='*.xml' 2>/dev/null | wc -l | tr -d ' ')
  if [ "$hits" -ne 0 ]; then
    echo "FAIL: collector symbol '$symbol' found in PRODUCTION sources under $MAIN_SRC ($hits hits)"
    grep -rF -- "$symbol" "$MAIN_SRC" --include='*.kt' --include='*.java' --include='*.xml' | head -5
    fail=1
  fi
done < <(app_symbols "$APP_DIR")

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
  # Per-symbol non-vacuous: every banned symbol must actually live in THIS
  # app's src/debug. Without this arm a renamed class would leave a dead name
  # on the list — the guard would stay green while scanning for bytes that can
  # never appear (the vacuous-pass shape case 3 kills at marker level).
  while IFS= read -r symbol; do
    [ -n "$symbol" ] || continue
    sym_debug=$(grep -rF -- "$symbol" "$DEBUG_SRC" --include='*.kt' 2>/dev/null | wc -l | tr -d ' ')
    if [ "$sym_debug" -eq 0 ]; then
      echo "FAIL: banned symbol '$symbol' not found in src/debug either — it was renamed/moved; update the guard's symbol list (guarding a dead name = vacuous)"
      fail=1
    fi
  done < <(app_symbols "$APP_DIR")
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
  # Also scan this app's full debug-only class-name list in the dex string
  # pool (2026-08-28: was hardcoded to two names; the contract-bound probe
  # components were invisible to the scan half of the guard).
  while IFS= read -r symbol; do
    [ -n "$symbol" ] || continue
    sym_hits=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -a -c "$symbol" || true)
    if [ "${sym_hits:-0}" -ne 0 ]; then
      echo "FAIL: collector class '$symbol' found in release APK $APK"
      fail=1
    fi
  done < <(app_symbols "$APP_DIR")
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
apk_note=""
[ -n "$APK" ] && apk_note=" (+ release APK scanned)"
echo "ok: debug-only collector boundary holds for $APP_DIR$apk_note"
