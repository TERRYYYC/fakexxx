#!/usr/bin/env bash
# Selftest for the --acceptance-readiness mode of
# apps/qianwangyou/scripts/test-hook.sh.
#
# Gap (G2 §3.3-3 last sentence; S1 on-device block, docs/acceptance/
# issue7-g2-acceptance-package.md): ACCEPTANCE_ACT was reachable ONLY inside
# run_cellular_matrix (the §G transaction) with payload extras. No canonical
# path existed to prove, OUTSIDE a transaction, that the runner starts the
# real .bench acceptance component — so an on-device run had to weaken the
# criterion (or fake it) to close S1-B.
#
# The mode under test is read-only two-sided proof, no payload extras:
#   Stage 1 (gate, unprivileged am start): the resolved component must be
#     DENIED by the bench signature permission. A start that SUCCEEDS means
#     the component is not the signature-gated probe (imposter / wrong
#     package / gate dropped) — resolution proved nothing.
#   Stage 2 (identity, root am start, NO extras): the real probe's onCreate
#     fails fast on the missing session extra and aborts BEFORE any
#     transaction step (HookAcceptanceActivity.requireNotNull ->
#     "aborted" with sessionId "unparsed"). A "published" state log means a
#     transaction was entered — the mode must FAIL (read-only pinned).
#
# Red-first: this selftest FAILS on a script without the mode (function
# extraction fails — the recorded red: no canonical readiness path exists).
# N1/N1b/N2/N3 are the negative matrix; G1 the positive path; S static
# wiring; M1/M2 mutation-check that the N-case guards are load-bearing
# (neutering one guard must bring the matching false green back).
#
# The mode deliberately does NOT reuse snapshot_prefs()/has_pending_recovery()
# (unfiltered /data/misc scans loud-fail on two-package devices); S pins that.
#
# Device-free: extracts the REAL shipped functions (sed) and drives them
# against a fake adb (F-18 selftest lineage). Identity under test is always
# the script's own constants. Exit 0 = all cases pass.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_HOOK="$HERE/../apps/qianwangyou/scripts/test-hook.sh"

BENCH_ID="name.caiyao.fakegps.bench"
FQCN_ACT="$BENCH_ID/name.caiyao.fakegps.probe.HookAcceptanceActivity"

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

if [ ! -f "$TEST_HOOK" ]; then
    echo "selftest target missing: $TEST_HOOK" >&2
    exit 1
fi

CONSTS="$(sed -n '/^set -u$/,/^MODE=/p' "$TEST_HOOK" | sed '$d')"
FN_READY="$(sed -n '/^run_acceptance_readiness()/,/^}/p' "$TEST_HOOK")"
FN_WAIT="$(sed -n '/^wait_for_readiness_abort()/,/^}/p' "$TEST_HOOK")"
FN_LOGS="$(sed -n '/^read_acceptance_logs()/,/^}/p' "$TEST_HOOK")"

# RED (recorded): no canonical readiness path — extraction of the mode
# function itself fails on a script without --acceptance-readiness.
if [ -z "$FN_READY" ]; then
    echo "FAIL red: run_acceptance_readiness not present — no canonical readiness path exists" >&2
    echo "test-hook acceptance-readiness selftest: 0 passed, 1 failed"
    exit 1
fi
for fn in "$FN_WAIT" "$FN_LOGS" "$CONSTS"; do
    [ -n "$fn" ] || { echo "could not extract helpers from $TEST_HOOK" >&2; exit 1; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# fake adb: per-case outputs routed by command shape. Unprivileged vs root
# starts are distinguished by the `su -c` wrapper.
# ---------------------------------------------------------------------------
make_fake_adb() { # dir
    local dir="$1"
    mkdir -p "$dir"
    cat >"$dir/adb" <<EOF
#!/usr/bin/env bash
record() { printf '%s\n' "\$1" >>"\$CALLS"; }
case "\$*" in
  *"su -c 'am start"*)
    # root start (stage 2)
    record "root-start \$*"
    cat "$dir/priv.out" 2>/dev/null
    exit "\$(cat "$dir/priv.rc" 2>/dev/null || echo 0)"
    ;;
  *"am start"*)
    # unprivileged start (stage 1)
    record "unpriv-start \$*"
    cat "$dir/unpriv.out" 2>/dev/null
    exit "\$(cat "$dir/unpriv.rc" 2>/dev/null || echo 0)"
    ;;
  *"logcat -d"*)
    cat "$dir/logcat.out" 2>/dev/null
    exit 0
    ;;
  *"am force-stop "*|*"logcat -c"*)
    exit 0
    ;;
esac
echo "fake adb: unhandled: \$*" >&2
exit 99
EOF
    chmod +x "$dir/adb"
}

# Assemble a runner from real shipped pieces (+ optionally mutated function
# text). $1 dir, $2 mutated_ready("" = shipped), $3 mutated_wait("" = shipped)
build_and_run() {
    local dir="$1" mutated_ready="${2:-}" mutated_wait="${3:-}"
    CALLS="$dir/calls.log"; export CALLS
    : >"$CALLS"
    local ready="$FN_READY" wait_fn="$FN_WAIT"
    [ -n "$mutated_ready" ] && ready="$mutated_ready"
    [ -n "$mutated_wait" ] && wait_fn="$mutated_wait"
    {
        printf '%s\n' "$CONSTS"
        printf 'MODE=--acceptance-readiness\n'
        printf 'DEVICE_API=34\n'
        printf 'sleep() { :; }\n'
        printf 'preflight_device() { DEVICE_API=34; return 0; }\n'
        printf 'root_shell() { adb shell "su -c '"'"'$1'"'"'"; }\n'
        printf '%s\n' "$FN_LOGS"
        printf '%s\n' "$wait_fn"
        printf '%s\n' "$ready"
        printf 'run_acceptance_readiness\n'
    } >"$dir/runner.sh"
    OUT="$(
        cd "$WORK" &&
        env -i PATH="$dir:$PATH" CALLS="$CALLS" HOME="$WORK" \
            bash "$dir/runner.sh" 2>&1
    )"
    RC=$?
}

DENY_BENCH="Error: java.lang.SecurityException: Permission Denial: starting Intent requires $BENCH_ID.permission.RUN_HOOK_ACCEPTANCE"
ABORT_LOG="W/FakeGPSAcceptance: {\"sessionId\":\"unparsed\",\"state\":\"aborted\",\"error\":\"java.lang.IllegalArgumentException: missing acceptance_session_id\"}"

# ---------------------------------------------------------------------------
# N1: unprivileged start SUCCEEDS (component not signature-gated) -> the
# mode must FAIL loudly; nothing may be claimed ready.
# ---------------------------------------------------------------------------
D="$WORK/n1"; make_fake_adb "$D"
printf 'Starting: Intent { cmp=%s }\nStatus: ok\n' "$FQCN_ACT" >"$D/unpriv.out"
printf '0' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
printf '%s\n' "$ABORT_LOG" >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -ne 0 ] &&
    report ok "N1 unguarded component -> FAIL (rc=$RC)" ||
    report fail "N1 unguarded component -> FAIL" "rc=0 out=$OUT"
grep -q "HARNESS_ERROR" <<<"$OUT" &&
    report ok "N1 names HARNESS_ERROR" ||
    report fail "N1 names HARNESS_ERROR" "$OUT"
grep -q "READINESS_PASS" <<<"$OUT" &&
    report fail "N1 must not claim READINESS_PASS" "out=$OUT" ||
    report ok "N1 does not claim READINESS_PASS"

# ---------------------------------------------------------------------------
# N1b: denial that names a DIFFERENT permission (foreign gate) -> FAIL.
# ---------------------------------------------------------------------------
D="$WORK/n1b"; make_fake_adb "$D"
printf 'Error: java.lang.SecurityException: Permission Denial: requires some.other.permission.X\n' >"$D/unpriv.out"
printf '1' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
printf '%s\n' "$ABORT_LOG" >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -ne 0 ] &&
    report ok "N1b foreign permission denial -> FAIL (rc=$RC)" ||
    report fail "N1b foreign permission denial -> FAIL" "rc=0 out=$OUT"

# ---------------------------------------------------------------------------
# N2: privileged start ok but NO fail-fast abort signature (component
# resolved but is not the acceptance probe) -> FAIL.
# ---------------------------------------------------------------------------
D="$WORK/n2"; make_fake_adb "$D"
printf '%s\n' "$DENY_BENCH" >"$D/unpriv.out"
printf '1' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
: >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -ne 0 ] &&
    report ok "N2 silent component -> FAIL (rc=$RC)" ||
    report fail "N2 silent component -> FAIL" "rc=0 out=$OUT"
grep -q "READINESS_PASS" <<<"$OUT" &&
    report fail "N2 must not claim READINESS_PASS" "out=$OUT" ||
    report ok "N2 does not claim READINESS_PASS"

# ---------------------------------------------------------------------------
# N3: abort signature present BUT a published state is also present (a
# transaction was entered from the readiness start) -> FAIL (read-only).
# ---------------------------------------------------------------------------
D="$WORK/n3"; make_fake_adb "$D"
printf '%s\n' "$DENY_BENCH" >"$D/unpriv.out"
printf '1' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
printf '%s\n%s\n' "$ABORT_LOG" \
    'W/FakeGPSAcceptance: {"sessionId":"s","state":"published"}' >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -ne 0 ] &&
    report ok "N3 transaction entered -> FAIL (rc=$RC)" ||
    report fail "N3 transaction entered -> FAIL" "rc=0 out=$OUT"

# ---------------------------------------------------------------------------
# G1: full positive path -> rc=0, both VERIFIED lines, READINESS_PASS, and
# both stages launched the explicit bench FQCN component.
# ---------------------------------------------------------------------------
D="$WORK/g1"; make_fake_adb "$D"
printf '%s\n' "$DENY_BENCH" >"$D/unpriv.out"
printf '1' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
printf '%s\n' "$ABORT_LOG" >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -eq 0 ] &&
    report ok "G1 gated+aborting component -> rc=0" ||
    report fail "G1 gated+aborting component -> rc=0" "rc=$RC out=$OUT"
grep -q "VERIFIED acceptance.gate" <<<"$OUT" &&
    report ok "G1 reports gate VERIFIED" ||
    report fail "G1 reports gate VERIFIED" "$OUT"
grep -q "VERIFIED acceptance.component" <<<"$OUT" &&
    report ok "G1 reports component VERIFIED" ||
    report fail "G1 reports component VERIFIED" "$OUT"
grep -q "READINESS_PASS" <<<"$OUT" &&
    report ok "G1 claims READINESS_PASS" ||
    report fail "G1 claims READINESS_PASS" "$OUT"
grep -qF "unpriv-start shell am start -W -n $FQCN_ACT" "$D/calls.log" &&
    report ok "G1 stage 1 launches explicit bench FQCN" ||
    report fail "G1 stage 1 launches explicit bench FQCN" "calls: $(cat "$D/calls.log")"
grep -qF "root-start shell su -c 'am start -W -n $FQCN_ACT" "$D/calls.log" &&
    report ok "G1 stage 2 launches explicit bench FQCN" ||
    report fail "G1 stage 2 launches explicit bench FQCN" "calls: $(cat "$D/calls.log")"

# ---------------------------------------------------------------------------
# R3 (evidence parity, red-first): the SUCCESS path must emit the raw denial
# excerpt. S2-B finding: the gate assertions are real, but the frozen
# evidence directory only carried the VERIFIED verdict + exit code — an
# independent reviewer could not confirm from raw bytes that the denial
# named the bench permission (Stage 2 already logs its raw fail-fast JSON;
# the two stages' evidence grades must be equal).
# ---------------------------------------------------------------------------
grep -qF "$BENCH_ID.permission.RUN_HOOK_ACCEPTANCE" <<<"$OUT" &&
    report ok "R3 success output carries the raw permission-naming line" ||
    report fail "R3 success output carries the raw permission-naming line" "verdict only, denial bytes dropped: $OUT"
grep -q "Permission Denial" <<<"$OUT" &&
    report ok "R3 success output carries the denial marker" ||
    report fail "R3 success output carries the denial marker" "$OUT"

# E2: the excerpt is self-auditable — a counts line (matched/total + the
# documented patterns) lets an auditor re-run the patterns on the raw text
# and confirm no matched line was cropped.
grep -Eq 'READINESS_GATE_EXCERPT lines=[1-9][0-9]*/[1-9][0-9]*' <<<"$OUT" &&
    report ok "E2 excerpt counts line present" ||
    report fail "E2 excerpt counts line present" "$OUT"

# E3 (bounded excerpt / privacy): raw denial output may interleave device
# noise; lines NOT matching the documented denial patterns must never enter
# the evidence stream.
D="$WORK/e3"; make_fake_adb "$D"
{
    printf 'Warning: device serialno=ZY22PRIVATE build-fingerprint=vendor/private/2026\n'
    printf '%s\n' "$DENY_BENCH"
} >"$D/unpriv.out"
printf '1' >"$D/unpriv.rc"
printf 'Status: ok\n' >"$D/priv.out"
printf '%s\n' "$ABORT_LOG" >"$D/logcat.out"
build_and_run "$D" ""
[ "$RC" -eq 0 ] &&
    report ok "E3 noisy denial still passes" ||
    report fail "E3 noisy denial still passes" "rc=$RC out=$OUT"
grep -q "ZY22PRIVATE" <<<"$OUT" &&
    report fail "E3 device noise must not enter evidence" "leaked: $OUT" ||
    report ok "E3 device noise must not enter evidence"
grep -qF "$BENCH_ID.permission.RUN_HOOK_ACCEPTANCE" <<<"$OUT" &&
    report ok "E3 permission line still excerpted" ||
    report fail "E3 permission line still excerpted" "$OUT"

# ---------------------------------------------------------------------------
# S (static): mode wired into usage/case/dispatch; body does not reuse the
# /data/misc-scanning helpers.
# ---------------------------------------------------------------------------
grep -qF '[--current-profile|--acceptance-readiness|--cellular-matrix|--runtime-verify]' "$TEST_HOOK" &&
    report ok "S usage lists the mode" ||
    report fail "S usage lists the mode" "usage line missing --acceptance-readiness"
grep -q -- '--acceptance-readiness) run_acceptance_readiness ;;' "$TEST_HOOK" &&
    report ok "S dispatch calls the mode" ||
    report fail "S dispatch calls the mode" "dispatch case missing"
if grep -qE 'snapshot_prefs|has_pending_recovery' <<<"$FN_READY"; then
    report fail "S mode avoids /data/misc helpers" "body references them"
else
    report ok "S mode avoids /data/misc helpers"
fi

# ---------------------------------------------------------------------------
# M1 (mutation): delete the ENTIRE stage-1 gate block (the shape of the S1
# incident: criterion weakened to "it started, didn't it?"). The N1 imposter
# shape MUST then false-green — proving stage 1 as a whole is load-bearing.
# (Stage 1's three guards are layered; neutering any single one is still
# caught by the others — the deletion is the minimal honest mutation.)
# ---------------------------------------------------------------------------
MUT1="$(printf '%s\n' "$FN_READY" | sed '/deny_out=/,/^    echo "VERIFIED acceptance.gate/d')"
if [ "$MUT1" = "$FN_READY" ]; then
    report fail "M1 mutation applied" "sed range did not match the shipped stage-1 block"
else
    D="$WORK/m1"; make_fake_adb "$D"
    printf 'Starting: Intent { cmp=%s }\nStatus: ok\n' "$FQCN_ACT" >"$D/unpriv.out"
    printf '0' >"$D/unpriv.rc"
    printf 'Status: ok\n' >"$D/priv.out"
    printf '%s\n' "$ABORT_LOG" >"$D/logcat.out"
    build_and_run "$D" "$MUT1" ""
    [ "$RC" -eq 0 ] && grep -q "READINESS_PASS" <<<"$OUT" &&
        report ok "M1 stage-1 deletion reproduces the false green" ||
        report fail "M1 stage-1 deletion reproduces the false green" "rc=$RC out=$OUT"
fi

# ---------------------------------------------------------------------------
# M2 (mutation): the wait helper gives up and calls the component ready
# anyway (timeout path returns 0 — the S1 shape of judging readiness without
# the component's own signature). The N2 silent-component shape MUST then
# false-green — proving the abort-signature wait is load-bearing.
# ---------------------------------------------------------------------------
MUT2="$(printf '%s\n' "$FN_WAIT" | sed 's/^    return 1$/    return 0/')"
if [ "$MUT2" = "$FN_WAIT" ]; then
    report fail "M2 mutation applied" "sed pattern did not match the shipped helper"
else
    D="$WORK/m2"; make_fake_adb "$D"
    printf '%s\n' "$DENY_BENCH" >"$D/unpriv.out"
    printf '1' >"$D/unpriv.rc"
    printf 'Status: ok\n' >"$D/priv.out"
    : >"$D/logcat.out"
    build_and_run "$D" "" "$MUT2"
    [ "$RC" -eq 0 ] && grep -q "READINESS_PASS" <<<"$OUT" &&
        report ok "M2 neutered wait reproduces the false green" ||
        report fail "M2 neutered wait reproduces the false green" "rc=$RC out=$OUT"
fi

printf 'test-hook acceptance-readiness selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
