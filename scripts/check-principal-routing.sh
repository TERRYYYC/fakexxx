#!/usr/bin/env bash
# check-principal-routing.sh — release-variant provider-principal routing proof
# (Terra PR-#65 review P2 closure; #63 single-pr truth source).
#
# CI runs DEBUG unit tests and only ASSEMBLES release, so no JVM test can
# observe which principal a RELEASE build routes to. The debug leg is proven by
# DefaultPrincipalCompositionGreenTest (successful no-override composition on
# the selected bench principal); this guard is the release half of the proof,
# in the same shape as check-debug-only-collector.sh (P10):
#
#   1. VARIANT PINNING — src/debug pins `isDebugBuild = true` and src/release
#      pins `isDebugBuild = false`, exactly one declaration each.
#      ProviderPrincipalRoutingRedTest already proves resolve(true)=bench /
#      resolve(false)=production; pinning the variant constants carries that
#      truth into the release ARTIFACT inputs.
#   2. SINGLE SELECTION — inside src/main, the principal literals
#      (PROVIDER_APPLICATION_ID_PRODUCTION / _BENCH) may appear ONLY in
#      ProviderPrincipal.kt, and `ProviderPrincipalBuild` may be referenced
#      ONLY there. A hardcoded principal at any trust/Binder/observe gate (the
#      APlusComposition split-principal shape) is structurally rejected before
#      it can ship.
#   3. RELEASE APK — when --apk is given, the release dex pool must CONTAIN the
#      `ProviderPrincipalBuild` class bytes: the variant source set actually
#      compiled in. (Both principal STRINGS legitimately appear in every dex via
#      knownApplicationIds, so presence of the selector class is the artifact
#      anchor; the constant VALUE is pinned by check 1 + the pure-function test.)
#
# Usage: scripts/check-principal-routing.sh <app-module-dir> [--apk <release.apk>]
#   e.g. scripts/check-principal-routing.sh apps/cellrebel-auto/app \
#          --apk apps/cellrebel-auto/app/build/outputs/apk/release/app-release-unsigned.apk
# Exit 0 = proof holds; 1 = violation found; 2 = usage/setup error.

set -uo pipefail

SELECTOR="ProviderPrincipal.kt"
VARIANT_OBJECT="ProviderPrincipalBuild"

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
  echo "usage: $0 <app-module-dir> [--apk <release.apk>]" >&2
  exit 2
fi

fail=0

# ---- 1. variant constant pinning ------------------------------------------
pin_variant() { # <src-variant-dir> <expected-boolean>
  local variant_dir="$1" want="$2" file hits
  file=$(find "$variant_dir" -name "${VARIANT_OBJECT}.kt" 2>/dev/null)
  hits=$(printf '%s\n' "$file" | grep -c .)
  if [ "$hits" -ne 1 ]; then
    echo "FAIL: expected exactly one ${VARIANT_OBJECT}.kt under $variant_dir (found $hits)" >&2
    fail=1
    return
  fi
  if ! grep -Eq "const val isDebugBuild: Boolean = ${want}" "$file"; then
    echo "FAIL: $file must pin \`isDebugBuild: Boolean = ${want}\` — the variant constant drifted" >&2
    fail=1
  fi
  # Non-vacuous: the file must not carry a SECOND boolean leg that could fork.
  if [ "$(grep -c 'isDebugBuild' "$file")" -ne 1 ]; then
    echo "FAIL: $file declares isDebugBuild more than once — a second leg can fork the routing" >&2
    fail=1
  fi
}

pin_variant "$APP_DIR/src/debug" "true"
pin_variant "$APP_DIR/src/release" "false"

# ---- 2. single-selection source scan ---------------------------------------
MAIN_SRC="$APP_DIR/src/main"
if [ ! -d "$MAIN_SRC" ]; then
  echo "FAIL: no src/main under $APP_DIR — wrong dir?" >&2
  exit 2
fi
SELECTOR_FILE=$(find "$MAIN_SRC" -name "$SELECTOR" 2>/dev/null)
if [ -z "$SELECTOR_FILE" ]; then
  echo "FAIL: $SELECTOR not found under $MAIN_SRC — the single selection point is gone; every consumer is now an independent oracle" >&2
  exit 2
fi

# 2a. principal literals only inside the selector.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  echo "FAIL: principal literal outside $SELECTOR in src/main (single-truth-source violation):" >&2
  echo "  $hit" >&2
  fail=1
done < <(grep -rn --include='*.kt' -E "PROVIDER_APPLICATION_ID_(PRODUCTION|BENCH)" "$MAIN_SRC" 2>/dev/null | grep -v "$SELECTOR_FILE" || true)

# 2b. the variant object is referenced only by the selector.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  echo "FAIL: $VARIANT_OBJECT referenced outside $SELECTOR in src/main (the build flag is not a runtime oracle):" >&2
  echo "  $hit" >&2
  fail=1
done < <(grep -rn --include='*.kt' -F "$VARIANT_OBJECT" "$MAIN_SRC" 2>/dev/null | grep -v "$SELECTOR_FILE" || true)

# Non-vacuous: the selector itself must actually carry all three markers, else
# the allowlist above scans for names that never appear.
for marker in "PROVIDER_APPLICATION_ID_PRODUCTION" "PROVIDER_APPLICATION_ID_BENCH" "$VARIANT_OBJECT"; do
  if ! grep -qF "$marker" "$SELECTOR_FILE"; then
    echo "FAIL: $SELECTOR_FILE no longer references '$marker' — this guard's allowlist drifted vacuous; update it" >&2
    fail=1
  fi
done

# 2c. exact SELECTION BINDING (Terra R3): presence of the markers proves nothing
# about routing — `selected = resolve(true)` keeps every marker green while
# release hard-routes bench. The binding is pinned exactly, in three parts:
#   (i)   `selected` consumes the build flag at the call site (kills the
#         resolve(true) bypass);
#   (ii)  the resolve body branches on its OWN parameter with BENCH in the
#         debug arm (kills an ignored-parameter body and a branch swap);
#   (iii) the else arm maps to PRODUCTION (kills the swapped release leg).
# These are frozen anchors like the variant constants in check 1: a legitimate
# refactor updates the pin in the same commit, by design.
if ! grep -qF 'val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)' "$SELECTOR_FILE"; then
  echo "FAIL: $SELECTOR_FILE must bind \`val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)\` exactly — a resolve(<literal>) bypass hard-routes release (Terra R3 false-green)" >&2
  fail=1
fi
if ! { grep -A2 -F 'if (isDebugBuild) {' "$SELECTOR_FILE" | grep -qF 'PROVIDER_APPLICATION_ID_BENCH' && \
      grep -A1 -F '} else {' "$SELECTOR_FILE" | grep -qF 'PROVIDER_APPLICATION_ID_PRODUCTION'; }; then
  echo "FAIL: $SELECTOR_FILE must map the debug arm of resolve to BENCH and the else arm to PRODUCTION (branch swap / ignored-parameter body)" >&2
  fail=1
fi

# ---- 3. release APK artifact scan ------------------------------------------
if [ -n "$APK" ]; then
  if [ ! -f "$APK" ]; then
    echo "FAIL: --apk path not found: $APK" >&2
    exit 2
  fi
  class_hits=$(unzip -p "$APK" 'classes*.dex' 2>/dev/null | grep -a -c "$VARIANT_OBJECT" || true)
  if [ "${class_hits:-0}" -eq 0 ]; then
    echo "FAIL: $VARIANT_OBJECT absent from the release dex pool of $APK — the release artifact no longer routes through the selector" >&2
    fail=1
  fi
fi

if [ "$fail" -ne 0 ]; then
  exit 1
fi
apk_note=""
[ -n "$APK" ] && apk_note=" (+ release APK scanned)"
echo "ok: provider principal routing proof holds for $APP_DIR$apk_note"
