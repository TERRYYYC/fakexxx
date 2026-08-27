#!/usr/bin/env bash
# selftest-debug-only-collector.sh — negative matrix for
# scripts/check-debug-only-collector.sh (G2 §3 P10).
#
# The guard is the release-purity half of hard boundary 1 ("production 不得
# 携带"). A purity guard that cannot fail is decoration, so every check it
# makes is exercised against a hand-built violation:
#
#   case 1  clean fixture                        → guard PASSES
#   case 2  collector symbol in src/main         → guard FAILS  (mutation 3:
#          "production 变体意外带上" — the false green this boundary exists to kill)
#   case 3  marker missing from src/debug        → guard FAILS  (vacuous pass)
#   case 4  marker bytes inside a release "APK"  → guard FAILS  (built-artifact half)
#   case 5  collector class name inside the APK  → guard FAILS  (belt-and-braces)
#   case 6  clean APK                            → guard PASSES
#
# The OTHER two dispatch mutations are killed in the Kotlin lanes, not here:
#   "入口存在但没真触发"  → P10CollectorSurfaceGuardTest (live call-site regex +
#                          Process.killProcess primitive) + CollectorGateTest /
#                          RevokeCollectorGateTest (fire logic)
#   "触发了但状态没落盘"  → QwyDurableSnapshotTest.captureIgnoresStateThatNeverReachedTheDisk
#
# Exit 0 = all cases behave as specified; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/check-debug-only-collector.sh"

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

make_app_fixture() { # dir with_main_marker with_debug_marker
  local dir="$1" with_main="$2" with_debug="$3"
  mkdir -p "$dir/src/main/java/com/example/app" "$dir/src/debug/java/com/example/app"
  cat >"$dir/src/main/java/com/example/app/Main.kt" <<'EOF'
package com.example.app
class Main
EOF
  cat >"$dir/src/debug/java/com/example/app/Debug.kt" <<EOF
package com.example.app
// marker: $with_debug
class Debug
EOF
  if [ "$with_main" != "none" ]; then
    printf '// leaked reference: %s\n' "$with_main" >>"$dir/src/main/java/com/example/app/Main.kt"
  fi
}

# ---- case 1: clean fixture passes -----------------------------------------
F1="$WORK/app-clean"
make_app_fixture "$F1" "none" "P10DBG-COLLECTOR-V1"
if "$GUARD" "$F1" >/dev/null 2>&1; then
  report ok "case1 clean fixture passes"
else
  report fail "case1 clean fixture passes" "guard rejected a clean tree"
fi

# ---- case 2: collector symbol in src/main fails ---------------------------
F2="$WORK/app-leak"
make_app_fixture "$F2" "FaultCollectorActivity" "P10DBG-COLLECTOR-V1"
if "$GUARD" "$F2" >/dev/null 2>&1; then
  report fail "case2 collector in src/main is rejected" "guard passed a production leak"
else
  report ok "case2 collector in src/main is rejected"
fi

# ---- case 3: marker absent from debug fails (non-vacuous) -----------------
F3="$WORK/app-vacuous"
make_app_fixture "$F3" "none" "SOME-OTHER-MARKER"
if "$GUARD" "$F3" >/dev/null 2>&1;
then report fail "case3 missing debug marker is rejected" "guard passed vacuously"
else report ok "case3 missing debug marker is rejected"
fi

# ---- APK fixtures ----------------------------------------------------------
# A real zip with one classes.dex containing (or not containing) the marker.
make_apk_fixture() { # path needle
  local apk="$1" needle="$2"
  mkdir -p "$(dirname "$apk")"
  # A dex is not required to be valid for a byte scan; a text file named
  # classes.dex exercises exactly the grep path the guard uses.
  if [ -n "$needle" ]; then
    printf 'strings\n%s\nconst "%s"\n' "$needle" "$needle" >"$WORK/classes.dex"
  else
    printf 'strings\nnothing to see\n' >"$WORK/classes.dex"
  fi
  ( cd "$WORK" && zip -q -r tmp-fixture.apk classes.dex >/dev/null 2>&1 )
  mv "$WORK/tmp-fixture.apk" "$apk"
  rm -f "$WORK/classes.dex"
}

CLEAN_APP="$F1"

# ---- case 4: marker inside release APK fails ------------------------------
A4="$WORK/app-release-marker.apk"
make_apk_fixture "$A4" "P10DBG-COLLECTOR-V1"
if "$GUARD" "$CLEAN_APP" --apk "$A4" >/dev/null 2>&1; then
  report fail "case4 marker in release APK is rejected" "guard passed a dirty APK"
else
  report ok "case4 marker in release APK is rejected"
fi

# ---- case 5: collector class name inside APK fails ------------------------
A5="$WORK/app-release-class.apk"
make_apk_fixture "$A5" "FaultCollectorActivity"
if "$GUARD" "$CLEAN_APP" --apk "$A5" >/dev/null 2>&1; then
  report fail "case5 collector class in release APK is rejected" "guard passed a dirty APK"
else
  report ok "case5 collector class in release APK is rejected"
fi

# ---- case 6: clean APK passes ---------------------------------------------
A6="$WORK/app-release-clean.apk"
make_apk_fixture "$A6" ""
if "$GUARD" "$CLEAN_APP" --apk "$A6" >/dev/null 2>&1; then
  report ok "case6 clean APK passes"
else
  report fail "case6 clean APK passes" "guard rejected a clean APK"
fi

# ---- summary ----------------------------------------------------------------
echo
echo "selftest-debug-only-collector: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
