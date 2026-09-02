#!/usr/bin/env bash
# Device-free selftest for the §5A executable seed gate
# (apps/qianwangyou/scripts/seed-10a-gate.sh, PR #62 R8 P1-1).
#
# It SOURCES the real shipped gate functions (SEED_GATE_SOURCE_ONLY=1) and
# fakes only the device seam (`dev`) + `sleep`, then drives each fail-closed
# branch by observed OUTCOME (exit code + whether the seed was launched + the
# emitted marker). Deleting the PID assertion, the lock, or the honest-split
# verdict check turns a case red.
#
# Exit 0 = all cases pass.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GATE="$HERE/../apps/qianwangyou/scripts/seed-10a-gate.sh"

pass=0; fail=0
report() {
    if [ "$1" = ok ]; then printf 'ok   %s\n' "$2"; pass=$((pass+1))
    else printf 'FAIL %s :: %s\n' "$2" "$3"; fail=$((fail+1)); fi
}
[ -f "$GATE" ] || { echo "gate missing: $GATE" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
SHIM="$WORK/shim"; mkdir -p "$SHIM"
printf '#!/bin/sh\nexit 0\n' >"$SHIM/adb"; chmod +x "$SHIM/adb"  # command -v adb must resolve

# Drive one gate invocation in a subshell (so its EXIT trap/lock is scoped),
# with the device seam + sleep faked. Scenario state comes from files/env.
run_gate() { # -> OUT / RC ; uses FAKE_PID_FILE, FAKE_LOGS, FAKE_FORCESTOP_RC, LOCK
    local lock="$1"; shift
    local events="$WORK/events.$RANDOM$RANDOM"; : >"$events"
    local outf="$WORK/out.$RANDOM$RANDOM"
    # A REAL ( ) subshell writing to a file — NOT $(...), because macOS bash 3.2
    # mis-parses a `case` pattern's `)` inside command substitution.
    (
        export SEED_GATE_SOURCE_ONLY=1
        export PATH="$SHIM:$PATH"
        export FAKE_EVENTS="$events"
        # The gate reads LOCK_DIR + SEED_AWAIT_TRIES at SOURCE time, so these
        # must be exported BEFORE sourcing, not passed as a call-time prefix.
        export SEED_GATE_LOCK_DIR="$lock"
        export SEED_GATE_AWAIT_TRIES="${AWAIT_TRIES:-3}"
        # shellcheck disable=SC1090
        . "$GATE"
        sleep() { :; }
        dev() {
            case "$*" in
                "shell pidof "*) printf '%s' "$(cat "${FAKE_PID_FILE:-/dev/null}" 2>/dev/null)" ;;
                "shell am force-stop "*) echo force-stop >>"$FAKE_EVENTS"; return "${FAKE_FORCESTOP_RC:-0}" ;;
                "shell am start "*) echo seed-launched >>"$FAKE_EVENTS"; return 0 ;;
                "logcat "*) cat "${FAKE_LOGS:-/dev/null}" 2>/dev/null ;;
                *) return 0 ;;
            esac
        }
        seed_gate_main --fixture QkFTRTY0 --digest deadbeef
    ) >"$outf" 2>&1
    RC=$?
    OUT="$(cat "$outf")"
    # surface whether the seed was launched, for the caller to assert
    grep -q seed-launched "$events" && OUT="$OUT
__SEED_LAUNCHED__"
}

seed_launched() { grep -q "__SEED_LAUNCHED__" <<<"$OUT"; }

# ---- g1: happy path — quiescent PID + honest-split verdict -> PASS ----------
PIDF="$WORK/pid1"; : >"$PIDF"                  # empty = process gone
LOGS1="$WORK/logs1"
printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a\nI FakeGPSAcceptance: SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7 reason=x\n' >"$LOGS1"
FAKE_PID_FILE="$PIDF" FAKE_LOGS="$LOGS1" run_gate "$WORK/lock1"
{ [ "$RC" -eq 0 ] && grep -q "SEED_GATE_PASS command=prepare_10a" <<<"$OUT" && seed_launched; } &&
    report ok "g1 quiescent + honest-split verdict -> SEED_GATE_PASS" ||
    report fail "g1 happy path" "rc=$RC out=$OUT"
[ ! -d "$WORK/lock1" ] && report ok "g1 lock released on success" || report fail "g1 lock released" "lock survives"

# ---- g2: PID survives force-stop -> abort, seed NEVER launched -------------
PIDF2="$WORK/pid2"; printf '4321' >"$PIDF2"     # non-empty = still alive
FAKE_PID_FILE="$PIDF2" FAKE_LOGS="$LOGS1" run_gate "$WORK/lock2"
{ [ "$RC" -ne 0 ] && grep -q "still alive" <<<"$OUT" && ! seed_launched; } &&
    report ok "g2 surviving PID -> SEED_GATE_FAIL and NO seed launched" ||
    report fail "g2 surviving PID must abort before seeding" "rc=$RC launched=$(seed_launched && echo yes || echo no) out=$OUT"

# ---- g3: lock already held -> concurrent abort, no force-stop/seed ---------
mkdir -p "$WORK/lock3"                           # pre-hold the lock
FAKE_PID_FILE="$PIDF" FAKE_LOGS="$LOGS1" run_gate "$WORK/lock3"
{ [ "$RC" -ne 0 ] && grep -q "refusing concurrent seed" <<<"$OUT" && ! seed_launched; } &&
    report ok "g3 held lock -> single-flight refusal, NO seed launched" ||
    report fail "g3 concurrent gate must refuse" "rc=$RC out=$OUT"
rmdir "$WORK/lock3" 2>/dev/null || true

# ---- g4: device reports SEED_FAILED -> gate fails --------------------------
LOGS4="$WORK/logs4"; printf 'I FakeGPSAcceptance: SEED_FAILED command=prepare_10a IllegalStateException: drift\n' >"$LOGS4"
FAKE_PID_FILE="$PIDF" FAKE_LOGS="$LOGS4" run_gate "$WORK/lock4"
{ [ "$RC" -ne 0 ] && grep -q "reported SEED_FAILED" <<<"$OUT"; } &&
    report ok "g4 SEED_FAILED -> SEED_GATE_FAIL" ||
    report fail "g4 SEED_FAILED must fail the gate" "rc=$RC out=$OUT"

# ---- g5: no verdict within the window -> fail ------------------------------
LOGS5="$WORK/logs5"; printf 'I FakeGPSAcceptance: some unrelated line\n' >"$LOGS5"
AWAIT_TRIES=2 FAKE_PID_FILE="$PIDF" FAKE_LOGS="$LOGS5" run_gate "$WORK/lock5"
{ [ "$RC" -ne 0 ] && grep -q "no SEED_LOCAL_VERIFIED" <<<"$OUT"; } &&
    report ok "g5 no verdict in window -> SEED_GATE_FAIL" ||
    report fail "g5 missing verdict must fail" "rc=$RC out=$OUT"

# ---- g6: SEED_LOCAL_VERIFIED WITHOUT the gap⑦ split -> NOT a pass ----------
# The honest-split contract: a bare local-verified without SEED_CONTRACT_INCOMPLETE
# must not be accepted (that would be the false READY opus5 ruled out).
LOGS6="$WORK/logs6"; printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a\n' >"$LOGS6"
AWAIT_TRIES=2 FAKE_PID_FILE="$PIDF" FAKE_LOGS="$LOGS6" run_gate "$WORK/lock6"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g6 local-verified WITHOUT gap7 split -> not a pass" ||
    report fail "g6 bare local-verified must not pass" "rc=$RC out=$OUT"

printf 'seed-10a-gate selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
