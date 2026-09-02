#!/usr/bin/env bash
# Selftest for the §5.G evidence carrier inside apps/qianwangyou/scripts/test-hook.sh
# (PR #62 R3 P1-4 → R6 P2).
#
# The finding: every raw acceptance report lived only in TEMP_ROOT and
# cleanup_transaction deleted it, so a --cellular-matrix run could not bind
# session id + result file + installed APK SHA (acceptance package §5.G) and a
# failed run destroyed its own only raw carrier.
#
# This exercises the REAL shipped functions (extracted by sed, same pattern as
# selftest-test-hook-install-guard.sh). Two layers:
#
#   c1–c6  preserve_report unit behavior (binding line, loud failures, apk sha)
#   b1–b6  BEHAVIORAL drivers (R6 P2): the shipped try_capture_report /
#          cleanup_transaction / run_scenario logic runs in a disposable child
#          process with fake device edges, so real INT/TERM delivery, duplicate
#          capture windows, CURRENT_* rolling across scenarios, failure/timeout
#          capture ordering, and malformed shasum output are all verified by
#          OBSERVED OUTCOME (files + EVIDENCE lines + exit codes), not by
#          grepping for tokens — an inverted guard or deleted call goes red.
#
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

# A valid 64-hex installed APK sha for the happy paths (R4 P2 requires it).
APK_SHA="$(printf 'abcd%.0s' {1..16})" # 64 hex chars

run_preserve() { # evidence_dir session report_file [apk_sha] -> OUT / RC
    # ${4-default}: default ONLY when unset — an explicit empty 4th arg (c6)
    # must stay empty to exercise the unbound-apk failure.
    local apk="${4-$APK_SHA}"
    OUT="$(EVIDENCE_DIR="$1" INSTALLED_APK_SHA="$apk" bash -c '
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
grep -q "EVIDENCE session=acceptance-1 report=$EV/acceptance-1.json report_sha256=$EXPECT_SHA apk_sha256=$APK_SHA" <<<"$OUT" &&
    report ok "c1 one EVIDENCE line binds session/path/sha/apk" ||
    report fail "c1 one EVIDENCE line binds session/path/sha/apk" "$OUT"
[ -f "$EV/acceptance-1.json.evidence" ] &&
    [ "$(cat "$EV/acceptance-1.json.evidence")" = "$(grep '^EVIDENCE session=acceptance-1 ' <<<"$OUT" | head -1)" ] &&
    report ok "c1 durable commit record equals the emitted line" ||
    report fail "c1 durable commit record equals the emitted line" "$(cat "$EV/acceptance-1.json.evidence" 2>/dev/null)"

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

# ---- c5: apk sha not 64-hex -> loud failure (R4 P2) ------------------------
EV5="$WORK/ev5"; mkdir -p "$EV5"
SRC5="$WORK/src5.json"; printf '{"probe":"r5"}' >"$SRC5"
run_preserve "$EV5" "acceptance-5" "$SRC5" "unknown"
[ "$RC" -eq 2 ] && report ok "c5 non-64hex apk sha -> rc=2" ||
    report fail "c5 non-64hex apk sha -> rc=2" "rc=$RC out=$OUT"
grep -q "installed APK SHA not bound" <<<"$OUT" &&
    report ok "c5 names the unbound-apk failure" || report fail "c5 names the unbound-apk failure" "$OUT"
[ -f "$EV5/acceptance-5.json" ] &&
    report fail "c5 must not emit EVIDENCE for an unbindable report" "wrote a copy anyway" ||
    report ok "c5 emits no unbindable EVIDENCE line"

# ---- c6: empty apk sha -> loud failure -------------------------------------
run_preserve "$EV5" "acceptance-6" "$SRC5" ""
[ "$RC" -eq 2 ] && report ok "c6 empty apk sha -> rc=2" ||
    report fail "c6 empty apk sha -> rc=2" "rc=$RC out=$OUT"

# ============================================================================
# b-suite (R6 P2): behavioral drivers. A child bash process extracts the REAL
# shipped functions itself, wires the REAL trap lines run_cellular_matrix
# installs, and fakes only the device/database edges (adb, root_shell, logcat
# reads, restore, snapshots). Every verdict below is an observed outcome —
# exit code, preserved file bytes, EVIDENCE line count — so deleting a capture
# call, inverting a guard, or dropping the duplicate-window durable key makes
# a case go red instead of staying green.
# ============================================================================

DRIVER="$WORK/driver.sh"
cat >"$DRIVER" <<'DRIVER_EOF'
#!/usr/bin/env bash
# Behavioral driver for selftest-test-hook-evidence-carrier.sh (R6 P2).
# Env: TEST_HOOK_PATH (shipped script), DRIVER_APK_SHA (64-hex).
set -u
CASE=$1
WORKD=$2

# ---- shipped logic under test (extracted verbatim; fakes never shadow it) --
extract() { sed -n "/^$1()/,/^}/p" "$TEST_HOOK_PATH"; }
for fn in preserve_report try_capture_report cleanup_transaction signal_exit has_state run_scenario; do
    body="$(extract "$fn")"
    [ -n "$body" ] || { echo "DRIVER_ERROR cannot extract $fn" >&2; exit 99; }
    eval "$body"
done

# ---- fake device/database edges (the ONLY fakes) ---------------------------
FAKE_LOGS="$WORKD/fake-logs.txt"
FAKE_DEVICE_REPORT="$WORKD/fake-device-report.json"
read_acceptance_logs() { cat "$FAKE_LOGS" 2>/dev/null; }
root_shell() {
    case "$1" in
        cat\ *) cat "$FAKE_DEVICE_REPORT" ;;
        *) : ;;
    esac
}
adb() { :; }
restore_database_payload() { return 0; }
snapshot_db() { printf 'DBSNAP'; }
snapshot_prefs() { printf 'PREFSNAP'; }

# ---- environment the shipped functions expect ------------------------------
SCRIPT_DIR="$(cd "$(dirname "$TEST_HOOK_PATH")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PY="$(command -v python3)"
MATRIX_TOOL="$SCRIPT_DIR/cellular_acceptance_matrix.py"
BENCH_PACKAGE="name.caiyao.fakegps.bench"
ACCEPTANCE_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.probe.HookAcceptanceActivity"
TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/fakegps-acceptance.XXXXXX") || exit 99
EVIDENCE_DIR="$WORKD/evidence"; mkdir -p "$EVIDENCE_DIR"
INSTALLED_APK_SHA="$DRIVER_APK_SHA"
DB_BEFORE='DBSNAP'
PREFS_BEFORE='PREFSNAP'
RESTORE_FAILED=0
TRANSACTION_ACTIVE=0
CURRENT_SESSION=""; CURRENT_REMOTE_REPORT=""; CURRENT_LOCAL_REPORT=""
REPORT_CAPTURED=0
FULL_RSCP_CONTROL_REPORT=""

echo "DRIVER_TEMP_ROOT=$TEMP_ROOT"

log_state() { # session state — the exact substring has_state greps for
    printf '{"sessionId":"%s","state":"%s"}\n' "$1" "$2" >>"$FAKE_LOGS"
}

install_shipped_traps() {
    # The EXACT trap wiring run_cellular_matrix installs — extracted, not
    # re-typed, so deleting a trap in the shipped script kills these cases.
    wiring="$(sed -n '/^run_cellular_matrix()/,/^}/p' "$TEST_HOOK_PATH" | grep -E '^[[:space:]]*trap ')"
    [ -n "$wiring" ] || { echo "DRIVER_ERROR no trap wiring found in run_cellular_matrix" >&2; exit 99; }
    eval "$wiring"
}

case "$CASE" in
    signal-term|signal-int)
        # report_ready observed, poll has NOT captured yet, signal lands.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="sig-$CASE"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/sig.json"
        REPORT_CAPTURED=0
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"signal-carrier"}' >"$FAKE_DEVICE_REPORT"
        install_shipped_traps
        sig=TERM; [ "$CASE" = signal-int ] && sig=INT
        kill -"$sig" $$
        echo "DRIVER_UNREACHABLE signal did not interrupt the run"
        exit 97
        ;;
    dup-window)
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="dup-session"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/dup.json"
        REPORT_CAPTURED=0
        # Guard direction: WITHOUT report_ready the capture must stay quiet —
        # rc 0, no copy. An inverted has_state guard writes a copy here.
        : >"$FAKE_LOGS"
        try_capture_report || exit 96
        [ ! -f "$EVIDENCE_DIR/$CURRENT_SESSION.json" ] ||
            { echo "DRIVER_ERROR captured without report_ready" >&2; exit 95; }
        log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"dup-carrier"}' >"$FAKE_DEVICE_REPORT"
        try_capture_report || exit 94
        # R6 duplicate window: a signal lands AFTER preserve_report emitted the
        # EVIDENCE line but BEFORE REPORT_CAPTURED=1 ran; the EXIT salvage then
        # re-enters with the flag still 0. The durable key (preserved file)
        # must absorb it: no second copy, no second line.
        REPORT_CAPTURED=0
        try_capture_report || exit 93
        [ "$REPORT_CAPTURED" -eq 1 ] || exit 92
        exit 0
        ;;
    cleanup-rerun)
        # A SECOND process (fresh flags, same evidence dir) running the full
        # EXIT cleanup must not duplicate or overwrite the binding.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="dup-session"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/dup2.json"
        REPORT_CAPTURED=0
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"dup-carrier-SECOND"}' >"$FAKE_DEVICE_REPORT"
        install_shipped_traps
        exit 0  # EXIT trap -> cleanup_transaction -> salvage sees durable key
        ;;
    roll)
        # CURRENT_* must ROLL across scenarios: run_scenario's registration
        # block re-binds session/report coordinates and resets REPORT_CAPTURED
        # for each scenario. Two failure-branch runs, different bytes each.
        TRANSACTION_ACTIVE=1
        date() { if [ "${1-}" = "+%s" ]; then echo 1700000000; else command date "$@"; fi; }
        s1="acceptance-1700000000-$$-full-rscp"
        s2="acceptance-1700000000-$$-full-rssi"
        : >"$FAKE_LOGS"; log_state "$s1" probe_failed; log_state "$s1" report_ready
        printf '{"probe":"roll-ONE"}' >"$FAKE_DEVICE_REPORT"
        run_scenario full-rscp 2>/dev/null; rc1=$?
        [ "$rc1" -eq 1 ] || { echo "DRIVER_ERROR scenario1 rc=$rc1 (want 1)" >&2; exit 91; }
        : >"$FAKE_LOGS"; log_state "$s2" probe_failed; log_state "$s2" report_ready
        printf '{"probe":"roll-TWO-different-bytes"}' >"$FAKE_DEVICE_REPORT"
        run_scenario full-rssi 2>/dev/null; rc2=$?
        [ "$rc2" -eq 1 ] || { echo "DRIVER_ERROR scenario2 rc=$rc2 (want 1)" >&2; exit 90; }
        echo "DRIVER_SESSIONS $s1 $s2"
        exit 0
        ;;
    timeout)
        # report_ready never joined by restored: the timeout branch must still
        # capture the device bytes BEFORE returning its failure.
        TRANSACTION_ACTIVE=1
        date() { if [ "${1-}" = "+%s" ]; then echo 1700000000; else command date "$@"; fi; }
        sleep() { :; }
        s="acceptance-1700000000-$$-full-rscp"
        : >"$FAKE_LOGS"; log_state "$s" report_ready
        printf '{"probe":"timeout-carrier"}' >"$FAKE_DEVICE_REPORT"
        run_scenario full-rscp; rc=$?
        [ "$rc" -eq 1 ] || { echo "DRIVER_ERROR timeout rc=$rc (want 1)" >&2; exit 89; }
        echo "DRIVER_SESSION $s"
        exit 0
        ;;
    residue-final)
        # R7 P1-3 (Sol repro a): a FINAL report file with NO commit record —
        # the cp-then-TERM residue of the old design — must NOT read as
        # captured. Salvage must redo the full capture, replacing the residue.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="res-final"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/res.json"
        REPORT_CAPTURED=0
        printf 'PRE-COMMIT-RESIDUE-GARBAGE' >"$EVIDENCE_DIR/res-final.json"
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"residue-replaced"}' >"$FAKE_DEVICE_REPORT"
        try_capture_report || exit 88
        [ "$REPORT_CAPTURED" -eq 1 ] || exit 87
        exit 0
        ;;
    residue-partial)
        # The commit-protocol crash shape: only a .partial staged when the
        # signal landed. Retry must complete cleanly over it.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="res-partial"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/resp.json"
        REPORT_CAPTURED=0
        printf 'HALF' >"$EVIDENCE_DIR/res-partial.json.partial"
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"partial-completed"}' >"$FAKE_DEVICE_REPORT"
        try_capture_report || exit 86
        exit 0
        ;;
    record-mismatch)
        # A commit record whose sha no longer matches the preserved bytes is
        # a LOUD rc=2 — validate-and-re-emit, never trust-and-green.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="rec-bad"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/rb.json"
        REPORT_CAPTURED=0
        printf '{"probe":"tampered-bytes"}' >"$EVIDENCE_DIR/rec-bad.json"
        wrong_sha=$(printf 'other-bytes' | shasum -a 256 | awk '{print $1}')
        printf 'EVIDENCE session=rec-bad report=%s report_sha256=%s apk_sha256=%s\n' \
            "$EVIDENCE_DIR/rec-bad.json" "$wrong_sha" "$DRIVER_APK_SHA" \
            >"$EVIDENCE_DIR/rec-bad.json.evidence"
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        try_capture_report
        rc=$?
        [ "$rc" -eq 2 ] || { echo "DRIVER_ERROR mismatch rc=$rc (want 2)" >&2; exit 85; }
        [ "$REPORT_CAPTURED" -eq 0 ] || exit 84
        exit 0
        ;;
    shasum-retry)
        # R7 P1-3 (Sol repro b): a malformed-shasum failure must leave NO
        # final file and NO record, and a clean retry must then succeed.
        TRANSACTION_ACTIVE=1
        CURRENT_SESSION="sha-retry"
        CURRENT_REMOTE_REPORT="/fake/device/report.json"
        CURRENT_LOCAL_REPORT="$TEMP_ROOT/sr.json"
        REPORT_CAPTURED=0
        : >"$FAKE_LOGS"; log_state "$CURRENT_SESSION" report_ready
        printf '{"probe":"sha-retry-bytes"}' >"$FAKE_DEVICE_REPORT"
        mkdir -p "$WORKD/shim"
        printf '#!/bin/sh\necho "zzz-not-hex  x"\n' >"$WORKD/shim/shasum"
        chmod +x "$WORKD/shim/shasum"
        OLD_PATH=$PATH
        PATH="$WORKD/shim:$PATH"
        try_capture_report
        rc=$?
        PATH=$OLD_PATH
        [ "$rc" -eq 2 ] || { echo "DRIVER_ERROR shim rc=$rc (want 2)" >&2; exit 83; }
        [ ! -f "$EVIDENCE_DIR/sha-retry.json" ] ||
            { echo "DRIVER_ERROR bad-shasum left a FINAL file" >&2; exit 82; }
        [ ! -f "$EVIDENCE_DIR/sha-retry.json.evidence" ] ||
            { echo "DRIVER_ERROR bad-shasum left a commit record" >&2; exit 81; }
        try_capture_report || exit 80
        [ "$REPORT_CAPTURED" -eq 1 ] || exit 79
        exit 0
        ;;
    *)
        echo "DRIVER_ERROR unknown case $CASE" >&2
        exit 99
        ;;
esac
DRIVER_EOF

run_driver() { # case workdir -> OUT / RC
    mkdir -p "$2"
    OUT="$(TEST_HOOK_PATH="$TEST_HOOK" DRIVER_APK_SHA="$APK_SHA" bash "$DRIVER" "$1" "$2" 2>&1)"
    RC=$?
}

evidence_count() { printf '%s\n' "$OUT" | grep -c '^EVIDENCE session='; }

# ---- b1: real INT/TERM delivery after report_ready salvages the report -----
for sigspec in signal-term:143 signal-int:130; do
    scase="${sigspec%%:*}"; want_rc="${sigspec##*:}"
    D="$WORK/$scase"
    run_driver "$scase" "$D"
    [ "$RC" -eq "$want_rc" ] && ! grep -q DRIVER_UNREACHABLE <<<"$OUT" &&
        report ok "b1 $scase interrupts with rc=$want_rc" ||
        report fail "b1 $scase interrupts with rc=$want_rc" "rc=$RC out=$OUT"
    # The shipped signal trap (not the ambient shell's EXIT behavior) must own
    # the interruption: signal_exit names the signal. Some bashes run EXIT
    # traps even on an untrapped TERM, so the salvage alone cannot prove the
    # trap wiring survived — this line can.
    SIGNAME="${scase#signal-}"; SIGNAME="$(printf '%s' "$SIGNAME" | tr '[:lower:]' '[:upper:]')"
    grep -q "interrupted by $SIGNAME" <<<"$OUT" &&
        report ok "b1 $scase shipped trap handled the signal (interrupted-by line)" ||
        report fail "b1 $scase shipped trap handled the signal" "no 'interrupted by $SIGNAME' in: $OUT"
    [ "$(evidence_count)" -eq 1 ] &&
        report ok "b1 $scase salvage emits exactly one EVIDENCE line" ||
        report fail "b1 $scase salvage emits exactly one EVIDENCE line" "count=$(evidence_count) out=$OUT"
    PRESERVED="$D/evidence/sig-$scase.json"
    [ -f "$PRESERVED" ] && [ "$(cat "$PRESERVED")" = '{"probe":"signal-carrier"}' ] &&
        report ok "b1 $scase preserved file carries the device bytes" ||
        report fail "b1 $scase preserved file carries the device bytes" "missing/mangled $PRESERVED"
    TR="$(printf '%s\n' "$OUT" | sed -n 's/^DRIVER_TEMP_ROOT=//p')"
    [ -n "$TR" ] && [ ! -d "$TR" ] &&
        report ok "b1 $scase TEMP_ROOT still removed after salvage" ||
        report fail "b1 $scase TEMP_ROOT still removed after salvage" "TEMP_ROOT=$TR survived"
done

# ---- b2: duplicate-window + second-process cleanup idempotence -------------
D2="$WORK/dup"
run_driver "dup-window" "$D2"
[ "$RC" -eq 0 ] && report ok "b2 dup-window driver completes (quiet no-op + capture + re-entry)" ||
    report fail "b2 dup-window driver completes" "rc=$RC out=$OUT"
# R7 P1-3: re-entry VALIDATES the commit record and RE-EMITS the recorded
# line — so the window re-entry yields two IDENTICAL lines (one binding,
# presented twice), never a second differing binding or a second copy.
UNIQ_LINES="$(printf '%s\n' "$OUT" | grep '^EVIDENCE session=' | sort -u | wc -l | tr -d ' ')"
[ "$(evidence_count)" -eq 2 ] && [ "$UNIQ_LINES" -eq 1 ] &&
    report ok "b2 flag-reset re-entry re-emits the SAME validated record (2 identical lines, 1 binding)" ||
    report fail "b2 flag-reset re-entry re-emits the SAME validated record" "count=$(evidence_count) uniq=$UNIQ_LINES out=$OUT"
FIRST_LINE="$(printf '%s\n' "$OUT" | grep '^EVIDENCE session=' | head -1)"
[ "$(cat "$D2/evidence/dup-session.json" 2>/dev/null)" = '{"probe":"dup-carrier"}' ] &&
    [ -f "$D2/evidence/dup-session.json.evidence" ] &&
    report ok "b2 preserved bytes + commit record intact after re-entry" ||
    report fail "b2 preserved bytes + commit record intact after re-entry" "$(ls "$D2/evidence" 2>/dev/null)"
run_driver "cleanup-rerun" "$D2"
SECOND_LINE="$(printf '%s\n' "$OUT" | grep '^EVIDENCE session=' | head -1)"
[ "$RC" -eq 0 ] && [ "$(evidence_count)" -eq 1 ] && [ "$SECOND_LINE" = "$FIRST_LINE" ] &&
    report ok "b2 second-process cleanup re-emits the identical record, no new binding" ||
    report fail "b2 second-process cleanup re-emits the identical record" "rc=$RC count=$(evidence_count) out=$OUT"
[ "$(cat "$D2/evidence/dup-session.json" 2>/dev/null)" = '{"probe":"dup-carrier"}' ] &&
    report ok "b2 second-process cleanup never overwrites the preserved bytes" ||
    report fail "b2 second-process cleanup never overwrites the preserved bytes" "$(cat "$D2/evidence/dup-session.json" 2>/dev/null)"

# ---- b3: CURRENT_* rolls across scenarios (and failure branch captures) ----
D3="$WORK/roll"
run_driver "roll" "$D3"
[ "$RC" -eq 0 ] && report ok "b3 two failure-branch scenarios both return 1 with capture" ||
    report fail "b3 two failure-branch scenarios both return 1 with capture" "rc=$RC out=$OUT"
S1="$(printf '%s\n' "$OUT" | sed -n 's/^DRIVER_SESSIONS \([^ ]*\) .*/\1/p')"
S2="$(printf '%s\n' "$OUT" | sed -n 's/^DRIVER_SESSIONS [^ ]* //p')"
[ "$(evidence_count)" -eq 2 ] &&
    report ok "b3 exactly two EVIDENCE lines (one per scenario)" ||
    report fail "b3 exactly two EVIDENCE lines" "count=$(evidence_count) out=$OUT"
if [ -n "$S1" ] && [ -n "$S2" ]; then
    [ "$(cat "$D3/evidence/$S1.json" 2>/dev/null)" = '{"probe":"roll-ONE"}' ] &&
        report ok "b3 first scenario bound its OWN bytes" ||
        report fail "b3 first scenario bound its OWN bytes" "$(cat "$D3/evidence/$S1.json" 2>/dev/null)"
    [ "$(cat "$D3/evidence/$S2.json" 2>/dev/null)" = '{"probe":"roll-TWO-different-bytes"}' ] &&
        report ok "b3 rolled scenario bound its OWN bytes (not the stale carrier)" ||
        report fail "b3 rolled scenario bound its OWN bytes" "$(cat "$D3/evidence/$S2.json" 2>/dev/null)"
    SHA2="$(shasum -a 256 "$D3/evidence/$S2.json" 2>/dev/null | awk '{print $1}')"
    grep -q "EVIDENCE session=$S2 report=$D3/evidence/$S2.json report_sha256=$SHA2" <<<"$OUT" &&
        report ok "b3 rolled EVIDENCE line binds the rolled session+sha" ||
        report fail "b3 rolled EVIDENCE line binds the rolled session+sha" "$OUT"
else
    report fail "b3 driver reported its sessions" "no DRIVER_SESSIONS line: $OUT"
fi

# ---- b6: timeout branch still captures before returning --------------------
D6="$WORK/timeout"
run_driver "timeout" "$D6"
[ "$RC" -eq 0 ] && report ok "b6 timeout scenario returns 1 after capturing" ||
    report fail "b6 timeout scenario returns 1 after capturing" "rc=$RC out=$OUT"
[ "$(evidence_count)" -eq 1 ] && grep -q "timed out waiting for report_ready" <<<"$OUT" &&
    report ok "b6 timeout branch preserved the report_ready bytes" ||
    report fail "b6 timeout branch preserved the report_ready bytes" "count=$(evidence_count) out=$OUT"

# ---- b4: malformed shasum output -> loud rc=2, no EVIDENCE (was token c9) --
SHIM="$WORK/shim"; mkdir -p "$SHIM"
printf '#!/bin/sh\necho "zzz-not-hex-garbage  fake"\n' >"$SHIM/shasum"
chmod +x "$SHIM/shasum"
EVB4="$WORK/ev-b4"; mkdir -p "$EVB4"
SRCB4="$WORK/src-b4.json"; printf '{"probe":"b4"}' >"$SRCB4"
OUT="$(EVIDENCE_DIR="$EVB4" INSTALLED_APK_SHA="$APK_SHA" PATH="$SHIM:$PATH" bash -c '
    '"$FN"'
    preserve_report "$1" "$2"
' _ "sess-b4" "$SRCB4" 2>&1)"
RC=$?
[ "$RC" -eq 2 ] && grep -q "could not fingerprint preserved report" <<<"$OUT" &&
    report ok "b4 malformed shasum output -> rc=2 with named failure" ||
    report fail "b4 malformed shasum output -> rc=2 with named failure" "rc=$RC out=$OUT"
grep -q '^EVIDENCE session=' <<<"$OUT" &&
    report fail "b4 no EVIDENCE line may bind a garbage sha" "$OUT" ||
    report ok "b4 no EVIDENCE line binds a garbage sha"
# R7 P1-3: the failure must leave NO final file and NO commit record — a
# pre-commit residue at the final path was Sol's reproduced false green.
[ ! -f "$EVB4/sess-b4.json" ] && [ ! -f "$EVB4/sess-b4.json.evidence" ] &&
    report ok "b4 bad shasum leaves neither final file nor commit record" ||
    report fail "b4 bad shasum leaves neither final file nor commit record" "$(ls "$EVB4" 2>/dev/null)"

# ---- b7: pre-commit residue is NOT a completed binding (R7 P1-3) -----------
D7="$WORK/residue-final"
run_driver "residue-final" "$D7"
[ "$RC" -eq 0 ] && [ "$(evidence_count)" -eq 1 ] &&
    report ok "b7 final-file residue without record forces a REAL capture" ||
    report fail "b7 final-file residue without record forces a REAL capture" "rc=$RC count=$(evidence_count) out=$OUT"
[ "$(cat "$D7/evidence/res-final.json" 2>/dev/null)" = '{"probe":"residue-replaced"}' ] &&
    [ -f "$D7/evidence/res-final.json.evidence" ] &&
    report ok "b7 residue replaced by bound bytes + commit record" ||
    report fail "b7 residue replaced by bound bytes + commit record" "$(cat "$D7/evidence/res-final.json" 2>/dev/null)"
D7B="$WORK/residue-partial"
run_driver "residue-partial" "$D7B"
[ "$RC" -eq 0 ] && [ "$(evidence_count)" -eq 1 ] &&
    [ "$(cat "$D7B/evidence/res-partial.json" 2>/dev/null)" = '{"probe":"partial-completed"}' ] &&
    report ok "b7 .partial crash residue completes cleanly on retry" ||
    report fail "b7 .partial crash residue completes cleanly on retry" "rc=$RC out=$OUT"

# ---- b8: record validation — mismatched record is loud, never green --------
D8="$WORK/record-mismatch"
run_driver "record-mismatch" "$D8"
[ "$RC" -eq 0 ] && [ "$(evidence_count)" -eq 0 ] &&
    grep -q "does not match preserved bytes" <<<"$OUT" &&
    report ok "b8 record/bytes mismatch -> loud rc=2 from capture, zero EVIDENCE" ||
    report fail "b8 record/bytes mismatch -> loud rc=2, zero EVIDENCE" "rc=$RC count=$(evidence_count) out=$OUT"

# ---- b9: bad shasum leaves nothing; clean retry then succeeds --------------
D9="$WORK/shasum-retry"
run_driver "shasum-retry" "$D9"
[ "$RC" -eq 0 ] && [ "$(evidence_count)" -eq 1 ] &&
    [ -f "$D9/evidence/sha-retry.json" ] && [ -f "$D9/evidence/sha-retry.json.evidence" ] &&
    report ok "b9 bad-shasum failure is residue-free and a clean retry completes the binding" ||
    report fail "b9 bad-shasum failure residue-free + retry completes" "rc=$RC count=$(evidence_count) out=$OUT"

printf 'test-hook evidence-carrier selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
