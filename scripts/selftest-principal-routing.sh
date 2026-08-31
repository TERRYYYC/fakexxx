#!/usr/bin/env bash
# selftest-principal-routing.sh — negative matrix for
# scripts/check-principal-routing.sh (Terra PR-#65 review P2 closure).
#
# A routing proof that cannot fail is decoration. Every check the guard makes
# is exercised against a hand-built violation:
#
#   case 1  clean fixture                              → guard PASSES
#   case 2  debug variant constant flipped to false    → guard FAILS
#   case 3  release variant constant flipped to true   → guard FAILS
#   case 4  hardcoded principal literal in src/main    → guard FAILS (the
#           APlusComposition split-principal mutation this guard exists to kill)
#   case 5  ProviderPrincipalBuild used in src/main    → guard FAILS (build flag
#           consulted outside the single selection point)
#   case 6  selector deleted from src/main             → guard FAILS (exit 2)
#   case 7  release "APK" missing the selector bytes   → guard FAILS (artifact half)
#   case 8  clean release "APK" with selector bytes    → guard PASSES
#   case 9  selector mutated to `selected = resolve(true)` → guard FAILS
#           (Terra R3 exact mutation: the build flag is bypassed at the
#           binding site while every marker the old guard checked survives)
#   case 10 resolve body hardcodes `if (true)` (parameter ignored) → guard FAILS
#           (same failure mode one level deeper: binding passes, body lies)
#   case 11 branches swapped (debug→PRODUCTION)        → guard FAILS
#
# The behavioral half (hardcoded production ⇒ default composition fail-closes
# in a debug build) is killed in the Kotlin lane by
# DefaultPrincipalCompositionGreenTest; this matrix keeps the shell guard
# load-bearing. Device-free by construction (fixture trees + fixture zips).
#
# Exit 0 = all cases behave as specified; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/check-principal-routing.sh"

pass=0
fail=0

report() {
  if [ "$1" = "ok" ]; then
    printf 'ok   %s\n' "$2"
    pass=$((pass + 1))
  else
    printf 'FAIL %s :: %s\n' "$2" "$3"
    fail=$((fail + 1))
  fi
}

if [ ! -f "$GUARD" ]; then
  echo "selftest target missing: $GUARD (write the guard, not just the test)" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# make_app_fixture <dir> <debug-const> <release-const> [extra-main-file-body]
#                   [selector-mutation]
# The optional mutation body goes into a SEPARATE src/main file — the real
# Terra-P2 shape (a hardcoded gate inside a composition file), not appended to
# the selector itself. The optional selector-mutation rewrites the selector
# itself (Terra R3 family: presence survives, binding does not).
make_app_fixture() {
  local dir="$1" dbg="$2" rel="$3" extra_main="${4:-}" sel_mut="${5:-}"
  mkdir -p \
    "$dir/src/main/java/com/example/app" \
    "$dir/src/debug/java/com/example/app" \
    "$dir/src/release/java/com/example/app"
  local selector_body
  case "$sel_mut" in
    ""|none)
      selector_body='internal object ProviderPrincipal {
    fun resolve(isDebugBuild: Boolean): String =
        if (isDebugBuild) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }
    val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)
    val knownApplicationIds: List<String> =
        listOf(selected, resolve(!ProviderPrincipalBuild.isDebugBuild))
}' ;;
    # Terra R3 EXACT mutation: the binding bypasses the build flag; every marker
    # the pre-strengthening guard checked (literals, ProviderPrincipalBuild
    # reference, variant constants, dex bytes) survives this diff.
    resolve_true)
      selector_body='internal object ProviderPrincipal {
    fun resolve(isDebugBuild: Boolean): String =
        if (isDebugBuild) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }
    val selected: String = resolve(true)
    val knownApplicationIds: List<String> =
        listOf(selected, resolve(!ProviderPrincipalBuild.isDebugBuild))
}' ;;
    # Same failure mode one level deeper: binding consumes the flag, but the
    # body ignores its parameter — release still hard-routes bench.
    body_hardcode)
      selector_body='internal object ProviderPrincipal {
    fun resolve(isDebugBuild: Boolean): String =
        if (true) {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        }
    val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)
    val knownApplicationIds: List<String> =
        listOf(selected, resolve(!ProviderPrincipalBuild.isDebugBuild))
}' ;;
    # Branch swap: debug selects production, release selects bench.
    branch_swap)
      selector_body='internal object ProviderPrincipal {
    fun resolve(isDebugBuild: Boolean): String =
        if (isDebugBuild) {
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        } else {
            ContractV1.PROVIDER_APPLICATION_ID_BENCH
        }
    val selected: String = resolve(ProviderPrincipalBuild.isDebugBuild)
    val knownApplicationIds: List<String> =
        listOf(selected, resolve(!ProviderPrincipalBuild.isDebugBuild))
}' ;;
    *)
      echo "make_app_fixture: unknown selector-mutation: $sel_mut" >&2; exit 2 ;;
  esac
  {
    printf 'package com.example.app\nimport io.github.terryyyc.fakexxx.contract.v1.ContractV1\n'
    printf '%s\n' "$selector_body"
  } >"$dir/src/main/java/com/example/app/ProviderPrincipal.kt"
  if [ -n "$extra_main" ]; then
    printf 'package com.example.app\n\n%s\n' "$extra_main" \
      >"$dir/src/main/java/com/example/app/Composition.kt"
  fi
  printf 'package com.example.app\ninternal object ProviderPrincipalBuild {\n    const val isDebugBuild: Boolean = %s\n}\n' "$dbg" \
    >"$dir/src/debug/java/com/example/app/ProviderPrincipalBuild.kt"
  printf 'package com.example.app\ninternal object ProviderPrincipalBuild {\n    const val isDebugBuild: Boolean = %s\n}\n' "$rel" \
    >"$dir/src/release/java/com/example/app/ProviderPrincipalBuild.kt"
}

make_apk_fixture() { # path needle
  local apk="$1" needle="$2"
  mkdir -p "$(dirname "$apk")"
  if [ -n "$needle" ]; then
    printf 'strings\n%s\nconst "%s"\n' "$needle" "$needle" >"$WORK/classes.dex"
  else
    printf 'strings\nnothing to see\n' >"$WORK/classes.dex"
  fi
  ( cd "$WORK" && zip -q -r tmp-fixture.apk classes.dex >/dev/null 2>&1 )
  mv "$WORK/tmp-fixture.apk" "$apk"
  rm -f "$WORK/classes.dex"
}

# ---- case 1: clean fixture passes -----------------------------------------
F1="$WORK/app-clean/app"
make_app_fixture "$F1" "true" "false"
if "$GUARD" "$F1" >/dev/null 2>&1; then
  report ok "case1 clean fixture passes"
else
  report fail "case1 clean fixture passes" "guard rejected a clean tree"
fi

# ---- case 2: debug variant constant flipped --------------------------------
F2="$WORK/app-debug-flip/app"
make_app_fixture "$F2" "false" "false"
if "$GUARD" "$F2" >/dev/null 2>&1; then
  report fail "case2 debug constant flip is rejected" "guard passed a debug build routed to production"
else
  report ok "case2 debug constant flip is rejected"
fi

# ---- case 3: release variant constant flipped ------------------------------
F3="$WORK/app-release-flip/app"
make_app_fixture "$F3" "true" "true"
if "$GUARD" "$F3" >/dev/null 2>&1; then
  report fail "case3 release constant flip is rejected" "guard passed a release build routed to bench"
else
  report ok "case3 release constant flip is rejected"
fi

# ---- case 4: hardcoded principal literal in src/main ----------------------
# The Terra-P2 mutation: a trust/observe gate consulting a literal principal
# instead of the single selection. Green here would let the split ship.
F4="$WORK/app-hardcoded/app"
make_app_fixture "$F4" "true" "false" \
  'object Composition { fun gate(): Boolean = trustGate.isCurrentSignerTrusted(io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION) }'
if "$GUARD" "$F4" >/dev/null 2>&1; then
  report fail "case4 hardcoded principal in src/main is rejected" "guard passed the split-principal mutation"
else
  report ok "case4 hardcoded principal in src/main is rejected"
fi

# ---- case 5: ProviderPrincipalBuild used outside the selector -------------
F5="$WORK/app-buildflag-leak/app"
make_app_fixture "$F5" "true" "false" \
  'object Diagnostics { val bypass = ProviderPrincipalBuild.isDebugBuild }'
if "$GUARD" "$F5" >/dev/null 2>&1; then
  report fail "case5 build-flag oracle outside selector is rejected" "guard passed an independent build-flag read"
else
  report ok "case5 build-flag oracle outside selector is rejected"
fi

# ---- case 6: selector deleted from src/main -------------------------------
F6="$WORK/app-no-selector/app"
make_app_fixture "$F6" "true" "false"
rm "$F6/src/main/java/com/example/app/ProviderPrincipal.kt"
if "$GUARD" "$F6" >/dev/null 2>&1; then
  report fail "case6 missing selector is rejected" "guard passed with no single selection point"
else
  report ok "case6 missing selector is rejected"
fi

# ---- case 7: release APK missing the selector bytes ------------------------
A7="$WORK/app-release-noselector.apk"
make_apk_fixture "$A7" ""
if "$GUARD" "$F1" --apk "$A7" >/dev/null 2>&1; then
  report fail "case7 selector absent from release APK is rejected" "guard passed an APK that lost the selector class"
else
  report ok "case7 selector absent from release APK is rejected"
fi

# ---- case 8: clean release APK with selector bytes -------------------------
A8="$WORK/app-release-withselector.apk"
make_apk_fixture "$A8" "ProviderPrincipalBuild"
if "$GUARD" "$F1" --apk "$A8" >/dev/null 2>&1; then
  report ok "case8 clean release APK passes"
else
  report fail "case8 clean release APK passes" "guard rejected a clean APK"
fi

# ---- case 9: selector mutated to selected = resolve(true) (Terra R3 exact) --
# The recorded false-green: every marker the pre-strengthening guard checked
# survives (variant constants, literals-in-selector, ProviderPrincipalBuild
# reference via knownApplicationIds, dex bytes) while release hard-routes bench.
F9="$WORK/app-resolve-true/app"
make_app_fixture "$F9" "true" "false" "" "resolve_true"
if "$GUARD" "$F9" >/dev/null 2>&1; then
  report fail "case9 selected=resolve(true) is rejected" "guard passed a build-flag bypass at the binding site"
else
  report ok "case9 selected=resolve(true) is rejected"
fi

# ---- case 10: resolve body ignores its parameter ----------------------------
F10="$WORK/app-body-hardcode/app"
make_app_fixture "$F10" "true" "false" "" "body_hardcode"
if "$GUARD" "$F10" >/dev/null 2>&1; then
  report fail "case10 resolve body if(true) is rejected" "guard passed an ignored-parameter body"
else
  report ok "case10 resolve body if(true) is rejected"
fi

# ---- case 11: branches swapped ----------------------------------------------
F11="$WORK/app-branch-swap/app"
make_app_fixture "$F11" "true" "false" "" "branch_swap"
if "$GUARD" "$F11" >/dev/null 2>&1; then
  report fail "case11 swapped branches are rejected" "guard passed debug→production / release→bench routing"
else
  report ok "case11 swapped branches are rejected"
fi

# ---- verdict ---------------------------------------------------------------
printf '\n%d passed, %d failed\n' "$pass" "$fail"
if [ "$fail" -ne 0 ]; then
  exit 1
fi
exit 0
