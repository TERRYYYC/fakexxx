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
#   1. VARIANT PINNING — src/debug pins `isDebugBuild = true` and forwards only
#      BuildConfig.CODEX_BENCH; src/release pins both flags false. The pure
#      resolver tests prove the three build identities; these source anchors
#      carry that truth into the variant ARTIFACT inputs.
#   2. SINGLE SELECTION — inside src/main, the principal literals
#      (PROVIDER_APPLICATION_ID_PRODUCTION / _BENCH / codex ID) may appear ONLY in
#      ProviderPrincipal.kt, and `ProviderPrincipalBuild` / CODEX_BENCH may be referenced
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

# Ignore comments and formatting when pinning executable source. Preserve
# quoted content (including spaces) so the package ID remains an exact anchor.
# This is a structural convention guard, not a substitute for Kotlin behavior
# tests; legitimate selector refactors update these anchors and mutations.
normalized_source() {
  awk '
    {
      for (i = 1; i <= length($0); i++) {
        c = substr($0, i, 1); pair = substr($0, i, 2)
        if (block) {
          if (pair == "*/") { block = 0; i++ }
          continue
        }
        if (quoted) {
          printf "%s", c
          if (escaped) escaped = 0
          else if (c == "\\") escaped = 1
          else if (c == "\"") quoted = 0
          continue
        }
        if (pair == "//") break
        if (pair == "/*") { block = 1; i++; continue }
        if (c == "\"") quoted = 1
        if (c !~ /[[:space:]]/) printf "%s", c
      }
    }
    END { print "" }
  ' "$1"
}

# ---- 1. variant constant pinning ------------------------------------------
pin_variant() { # <src-variant-dir> <expected-debug> <expected-codex-expression>
  local variant_dir="$1" want="$2" codex_want="$3" file hits code expected flag
  file=$(find "$variant_dir" -name "${VARIANT_OBJECT}.kt" 2>/dev/null)
  hits=$(printf '%s\n' "$file" | grep -c .)
  if [ "$hits" -ne 1 ]; then
    echo "FAIL: expected exactly one ${VARIANT_OBJECT}.kt under $variant_dir (found $hits)" >&2
    fail=1
    return
  fi
  code=$(normalized_source "$file")
  expected="internalobject${VARIANT_OBJECT}{constvalisDebugBuild:Boolean=${want}constvalisCodexBenchBuild:Boolean=${codex_want}}"
  if [[ "$code" != *"$expected" ]]; then
    echo "FAIL: $file must pin isDebugBuild=${want} and isCodexBenchBuild=${codex_want} in the variant adapter — a variant flag drifted" >&2
    fail=1
  fi
  # Non-vacuous: the file must not carry a SECOND boolean leg that could fork.
  for flag in isDebugBuild isCodexBenchBuild; do
    if [ "$(printf '%s\n' "$code" | grep -o "$flag" | wc -l | tr -d ' ')" -ne 1 ]; then
      echo "FAIL: $file must declare $flag exactly once — a second leg can fork the routing" >&2
      fail=1
    fi
  done
}

pin_variant "$APP_DIR/src/debug" "true" "BuildConfig.CODEX_BENCH"
pin_variant "$APP_DIR/src/release" "false" "false"

# ---- 2. single-selection source scan ---------------------------------------
MAIN_SRC="$APP_DIR/src/main"
if [ ! -d "$MAIN_SRC" ]; then
  echo "FAIL: no src/main under $APP_DIR — wrong dir?" >&2
  exit 2
fi
SELECTOR_FILE=$(find "$MAIN_SRC" -name "$SELECTOR" 2>/dev/null)
if [ "$(printf '%s\n' "$SELECTOR_FILE" | grep -c .)" -ne 1 ]; then
  echo "FAIL: expected exactly one $SELECTOR under $MAIN_SRC — the single selection point is missing or duplicated" >&2
  exit 2
fi

# 2a. principal literals only inside the selector.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  echo "FAIL: principal literal outside $SELECTOR in src/main (single-truth-source violation):" >&2
  echo "  $hit" >&2
  fail=1
done < <(grep -rn --include='*.kt' -E 'PROVIDER_APPLICATION_ID_(PRODUCTION|BENCH)|CODEX_BENCH|name\.caiyao\.fakegps\.codexbench' "$MAIN_SRC" 2>/dev/null | grep -vF "$SELECTOR_FILE:" || true)

# 2b. the variant object is referenced only by the selector.
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  echo "FAIL: $VARIANT_OBJECT referenced outside $SELECTOR in src/main (the build flag is not a runtime oracle):" >&2
  echo "  $hit" >&2
  fail=1
done < <(grep -rn --include='*.kt' -F "$VARIANT_OBJECT" "$MAIN_SRC" 2>/dev/null | grep -vF "$SELECTOR_FILE:" || true)

# Non-vacuous: the selector itself must actually carry all identity markers, else
# the allowlist above scans for names that never appear.
for marker in "PROVIDER_APPLICATION_ID_PRODUCTION" "PROVIDER_APPLICATION_ID_BENCH" "CODEX_BENCH_APPLICATION_ID" "$VARIANT_OBJECT"; do
  if ! grep -qF "$marker" "$SELECTOR_FILE"; then
    echo "FAIL: $SELECTOR_FILE no longer references '$marker' — this guard's allowlist drifted vacuous; update it" >&2
    fail=1
  fi
done

# 2c. exact SELECTION BINDING (Terra R3): presence of the markers proves nothing
# about routing — `selected = resolve(true)` keeps every marker green while
# release hard-routes bench. Both build flags, the fail-closed requirement,
# all three branches, and the complete allowlist expression are pinned.
# These are frozen anchors like the variant constants in check 1: a legitimate
# refactor updates the pin in the same commit, by design.
selector_code=$(normalized_source "$SELECTOR_FILE")
if ! printf '%s\n' "$selector_code" | grep -Eq 'valselected:String=resolve\(ProviderPrincipalBuild\.isDebugBuild,ProviderPrincipalBuild\.isCodexBenchBuild\)(val|fun|})'; then
  echo "FAIL: $SELECTOR_FILE selected must bind both ProviderPrincipalBuild flags exactly — a literal/omitted codex flag bypasses build isolation" >&2
  fail=1
fi
if ! printf '%s\n' "$selector_code" | grep -Eq 'constvalCODEX_BENCH_APPLICATION_ID:String="name\.caiyao\.fakegps\.codexbench"funresolve\('; then
  echo "FAIL: $SELECTOR_FILE codex provider ID must remain pinned in the single selector" >&2
  fail=1
fi
resolver_prefix='funresolve(isDebugBuild:Boolean,isCodexBenchBuild:Boolean=false):String{require(isDebugBuild||!isCodexBenchBuild){"codex-bench requires a debug build"}'
resolver_body='returnif(isCodexBenchBuild){CODEX_BENCH_APPLICATION_ID}elseif(isDebugBuild){ContractV1.PROVIDER_APPLICATION_ID_BENCH}else{ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION}}'
if [[ "$selector_code" != *"$resolver_prefix$resolver_body"* ]]; then
  echo "FAIL: $SELECTOR_FILE resolve must require a debug codex build and map codex/debug/release to CODEX_BENCH/BENCH/PRODUCTION (branch swap / ignored-parameter body)" >&2
  fail=1
fi
known_body='funknownForBuild(isDebugBuild:Boolean,isCodexBenchBuild:Boolean):List<String>{valtarget=resolve(isDebugBuild,isCodexBenchBuild)returnif(isCodexBenchBuild)listOf(target)elselistOf(target,resolve(!isDebugBuild))}'
if [[ "$selector_code" != *"$known_body"* ]]; then
  echo "FAIL: $SELECTOR_FILE knownForBuild must consume both flags and keep the codex allowlist singleton" >&2
  fail=1
fi
if ! printf '%s\n' "$selector_code" | grep -Eq 'valknownApplicationIds:List<String>=knownForBuild\(ProviderPrincipalBuild\.isDebugBuild,ProviderPrincipalBuild\.isCodexBenchBuild\)(val|fun|})'; then
  echo "FAIL: $SELECTOR_FILE knownApplicationIds must bind both build flags without widening the allowlist" >&2
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
