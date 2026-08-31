#!/usr/bin/env bash
# Selftest for the §5.G evidence carrier inside apps/qianwangyou/scripts/test-hook.sh
# (PR #62 R3 P1-4).
#
# The finding: every raw acceptance report lived only in TEMP_ROOT and
# cleanup_transaction deleted it, so a --cellular-matrix run could not bind
# session id + result file + installed APK SHA (acceptance package §5.G) and a
# failed run destroyed its own only raw carrier.
#
# This exercises the REAL shipped preserve_report() (extracted by sed, same
# pattern as selftest-test-hook-install-guard.sh) and pins:
#   c1  a preserved copy exists + one EVIDENCE line binds session/path/sha/apk
#   c2  a missing evidence directory is a LOUD rc=2, never a silent skip
#   c3  an unwritable evidence directory is a LOUD rc=2
#   c4  simulated cleanup (rm -rf TEMP_ROOT) does NOT touch the preserved copy
# Exit 0 = all cases pass; anything else = failure.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_HOOK="$HERE/../apps/qianwangyou/scripts/test-hook.sh"

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

[ -f "$TEST_HOOK" ] || { echo "selftest target missing: $TEST_HOOK" >&2; exit 1; }

FN="$(sed -n '/^preserve_report()/,/^}/p' "$TEST_HOOK")"
[ -n "$FN" ] || { echo "could not extract preserve_report from $TEST_HOOK" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

run_preserve() { # evidence_dir session report_file -> OUT / RC
    OUT="$(EVIDENCE_DIR="$1" INSTALLED_APK_SHA="feedfacefeedface" bash -c '
        '"$FN"'
        preserve_report "$1" "$2"
    ' _ "$2" "$3" 2>&1)"
    RC=$?
}

# ---- c1: happy path — copy + full binding line -----------------------------
EV="$WORK/evidence"; mkdir -p "$EV"
TMP="$WORK/temp"; mkdir -p "$TMP"
printf '{"probe":"raw-report-bytes"}' >"$TMP/acceptance-1.json"
run_preserve "$EV" "acceptance-1" "$TMP/acceptance-1.json"
[ "$RC" -eq 0 ] && report ok "c1 preserve succeeds" || report fail "c1 preserve succeeds" "rc=$RC out=$OUT"
[ -f "$EV/acceptance-1.json" ] && report ok "c1 preserved copy exists" ||
    report fail "c1 preserved copy exists" "missing $EV/acceptance-1.json"
EXPECT_SHA="$(shasum -a 256 "$TMP/acceptance-1.json" | awk '{print $1}')"
grep -q "EVIDENCE session=acceptance-1 report=$EV/acceptance-1.json report_sha256=$EXPECT_SHA apk_sha256=feedfacefeedface" <<<"$OUT" &&
    report ok "c1 one EVIDENCE line binds session/path/sha/apk" ||
    report fail "c1 one EVIDENCE line binds session/path/sha/apk" "$OUT"

# ---- c2: missing evidence dir — loud failure, no silent skip ---------------
run_preserve "$WORK/does-not-exist" "acceptance-2" "$TMP/acceptance-1.json"
[ "$RC" -eq 2 ] && report ok "c2 missing evidence dir -> rc=2" ||
    report fail "c2 missing evidence dir -> rc=2" "rc=$RC out=$OUT"
grep -q "HARNESS_ERROR" <<<"$OUT" && report ok "c2 names HARNESS_ERROR" ||
    report fail "c2 names HARNESS_ERROR" "$OUT"

# ---- c3: unwritable evidence dir — loud failure ----------------------------
RO="$WORK/readonly"; mkdir -p "$RO"; chmod a-w "$RO"
run_preserve "$RO" "acceptance-3" "$TMP/acceptance-1.json"
chmod u+w "$RO"
[ "$RC" -eq 2 ] && report ok "c3 unwritable evidence dir -> rc=2" ||
    report fail "c3 unwritable evidence dir -> rc=2" "rc=$RC out=$OUT"
grep -q "HARNESS_ERROR" <<<"$OUT" && report ok "c3 names HARNESS_ERROR" ||
    report fail "c3 names HARNESS_ERROR" "$OUT"

# ---- c4: cleanup deletes only TEMP_ROOT, never the preserved evidence ------
rm -rf -- "$TMP"
[ ! -e "$TMP/acceptance-1.json" ] || report fail "c4 temp actually removed" "temp survived"
[ -f "$EV/acceptance-1.json" ] &&
    report ok "c4 preserved evidence survives cleanup of TEMP_ROOT" ||
    report fail "c4 preserved evidence survives cleanup of TEMP_ROOT" "evidence destroyed with temp"
# Structural half: the shipped cleanup must not reference EVIDENCE_DIR at all.
CLEANUP="$(sed -n '/^cleanup_transaction()/,/^}/p' "$TEST_HOOK")"
grep -q 'EVIDENCE_DIR' <<<"$CLEANUP" &&
    report fail "c4 cleanup_transaction must not touch EVIDENCE_DIR" "cleanup references the evidence dir" ||
    report ok "c4 cleanup_transaction never references EVIDENCE_DIR"

printf 'test-hook evidence-carrier selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
