#!/usr/bin/env bash
# Device-free selftest for the §5A executable seed gate
# (apps/qianwangyou/scripts/seed-10a-gate.sh, PR #62 R8 P1-1 → R9 P1).
#
# It SOURCES the real shipped gate functions (SEED_GATE_SOURCE_ONLY=1) and
# fakes only the device seam (`dev`) + `sleep`, then drives each fail-closed
# branch by observed OUTCOME (exit code + whether the seed was launched + the
# emitted marker).
#
# R9 (Sol) additions — the gate must:
#   * bind each launch to a unique token echoed in every terminal marker and
#     accept exactly ONE internally consistent terminal result for THAT token
#     (stale success or stale failure from an earlier launch is ignored);
#   * treat the PID probe as tri-state and abort on probe failure (never read
#     "adb/pidof error" as "process gone");
#   * reclaim a lock only when its recorded owner is provably dead AND the
#     device shows no live bench process; refuse otherwise;
#   * hand the seeded state off quiescent (force-stop after the verdict).
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

DIGEST=cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852
OLD_TOKEN=20260901T000000Z-1-stale

# Drive one gate invocation in a subshell (so its EXIT trap/lock is scoped),
# with the device seam + sleep faked. Scenario state comes from env/files:
#   FAKE_PID_MODE      alive|absent|error|noremote   (tri-state probe; default absent)
#   FAKE_LOGS_BEFORE   logcat content present BEFORE the launch (stale markers)
#   FAKE_LOGS_AFTER    logcat content that appears only AFTER the launch; the
#                      literal @TOKEN@ is replaced by the token the gate launched with
#   FAKE_FORCESTOP_RC  force-stop exit code
run_gate() { # -> OUT / RC / EVENTS ; first arg = lock dir
    local lock="$1"; shift
    EVENTS="$WORK/events.$RANDOM$RANDOM"; : >"$EVENTS"
    local outf="$WORK/out.$RANDOM$RANDOM"
    # A REAL ( ) subshell writing to a file — NOT $(...), because macOS bash 3.2
    # mis-parses a `case` pattern's `)` inside command substitution.
    (
        export SEED_GATE_SOURCE_ONLY=1
        export PATH="$SHIM:$PATH"
        export FAKE_EVENTS="$EVENTS"
        export SEED_GATE_LOCK_DIR="$lock"
        export SEED_GATE_AWAIT_TRIES="${AWAIT_TRIES:-3}"
        # shellcheck disable=SC1090
        . "$GATE"
        sleep() { :; }
        dev() {
            case "$*" in
                "shell pidof "*)
                    mode="${FAKE_PID_MODE:-absent}"
                    if grep -q seed-launched "$FAKE_EVENTS" 2>/dev/null && [ -n "${FAKE_PID_MODE_AFTER_LAUNCH:-}" ]; then
                        mode="$FAKE_PID_MODE_AFTER_LAUNCH"   # the process came back after the seed launched
                    fi
                    if [[ "$*" == *"__RC"* ]]; then
                        # new protocol: the remote echoes pidof's own status
                        case "$mode" in
                            alive)    printf '%s\n__RC=0\n' "${FAKE_PID:-4321}" ;;
                            absent)   printf '__RC=1\n' ;;
                            error)    printf '__RC=42\n' ;;
                            noremote) return 1 ;;   # adb transport failure: no output at all
                        esac
                    else
                        # legacy protocol (pipe-through pidof): output only
                        case "$mode" in
                            alive)    printf '%s\n' "${FAKE_PID:-4321}" ;;
                            absent)   : ;;
                            error)    return 42 ;;
                            noremote) return 1 ;;
                        esac
                    fi ;;
                "shell am force-stop "*) echo force-stop >>"$FAKE_EVENTS"; return "${FAKE_FORCESTOP_RC:-0}" ;;
                "shell am start "*)
                    echo seed-launched >>"$FAKE_EVENTS"
                    printf '%s\n' "$*" | sed -n 's/.*--es seed_token \([^ ]*\).*/\1/p' | tr -d '\n' >"$FAKE_EVENTS.token"
                    return 0 ;;
                "logcat "*)
                    cat "${FAKE_LOGS_BEFORE:-/dev/null}" 2>/dev/null
                    if grep -q seed-launched "$FAKE_EVENTS" 2>/dev/null && [ -n "${FAKE_LOGS_AFTER:-}" ]; then
                        tok=$(cat "$FAKE_EVENTS.token" 2>/dev/null)
                        sed "s/@TOKEN@/${tok:-NOTOKEN}/g" "$FAKE_LOGS_AFTER" 2>/dev/null
                    fi ;;
                "shell run-as "*) printf '%s' "${FAKE_PREFS_XML:-}" ;;
                *) return 0 ;;
            esac
        }
        seed_gate_main --fixture QkFTRTY0 --digest "$DIGEST" ${EXTRA_ARGS:-}
    ) >"$outf" 2>&1
    RC=$?
    OUT="$(cat "$outf")"
}
seed_launched() { grep -q seed-launched "$EVENTS"; }
force_stops() { grep -c force-stop "$EVENTS" | tr -d ' '; }

# canonical marker files ------------------------------------------------------
STALE_OK="$WORK/stale_ok";     printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a token=%s digest=%s\nI FakeGPSAcceptance: SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7 token=%s reason=x\n' "$OLD_TOKEN" "$DIGEST" "$OLD_TOKEN" >"$STALE_OK"
STALE_FAIL="$WORK/stale_fail"; printf 'I FakeGPSAcceptance: SEED_FAILED command=prepare_10a token=%s IllegalStateException: old drift\n' "$OLD_TOKEN" >"$STALE_FAIL"
NEW_OK="$WORK/new_ok";         printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a token=@TOKEN@ digest=%s\nI FakeGPSAcceptance: SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7 token=@TOKEN@ reason=x\n' "$DIGEST" >"$NEW_OK"
NEW_FAIL="$WORK/new_fail";     printf 'I FakeGPSAcceptance: SEED_FAILED command=prepare_10a token=@TOKEN@ IllegalStateException: drift\n' >"$NEW_FAIL"
NEW_BOTH="$WORK/new_both";     cat "$NEW_OK" "$NEW_FAIL" >"$NEW_BOTH"
NEW_BARE="$WORK/new_bare";     printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a token=@TOKEN@ digest=%s\n' "$DIGEST" >"$NEW_BARE"
NEW_WRONGDIGEST="$WORK/new_wd";printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a token=@TOKEN@ digest=0000000000000000000000000000000000000000000000000000000000000000\nI FakeGPSAcceptance: SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7 token=@TOKEN@ reason=x\n' >"$NEW_WRONGDIGEST"
NEAR_TOKEN="$WORK/near";       printf 'I FakeGPSAcceptance: SEED_LOCAL_VERIFIED command=prepare_10a token=@TOKEN@x digest=%s\nI FakeGPSAcceptance: SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7 token=@TOKEN@x reason=x\n' "$DIGEST" >"$NEAR_TOKEN"
NOISE="$WORK/noise";           printf 'I FakeGPSAcceptance: some unrelated line\n' >"$NOISE"

# ---- g1: happy path — stale success from an OLD launch present, new token-bound pair arrives -> PASS
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_OK" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock1"
{ [ "$RC" -eq 0 ] && grep -q "SEED_GATE_PASS command=prepare_10a" <<<"$OUT" && grep -q "token=" <<<"$OUT" && seed_launched; } &&
    report ok "g1 quiescent + token-bound honest-split verdict -> SEED_GATE_PASS" ||
    report fail "g1 happy path" "rc=$RC out=$OUT"
[ ! -d "$WORK/lock1" ] && report ok "g1 lock released on success" || report fail "g1 lock released" "lock survives"
[ "$(force_stops)" -ge 2 ] && report ok "g1 handoff: bench force-stopped again AFTER the verdict (no live writer at handoff)" ||
    report fail "g1 handoff force-stop" "force-stops=$(force_stops) (need pre-seed + post-verdict)"

# ---- g2: PID survives force-stop -> abort, seed NEVER launched
FAKE_PID_MODE=alive FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock2"
{ [ "$RC" -ne 0 ] && grep -q "still alive" <<<"$OUT" && ! seed_launched; } &&
    report ok "g2 surviving PID -> SEED_GATE_FAIL and NO seed launched" ||
    report fail "g2 surviving PID must abort before seeding" "rc=$RC launched=$(seed_launched && echo yes || echo no) out=$OUT"

# ---- g3: lock held by a LIVE owner -> refuse, no force-stop/seed
mkdir -p "$WORK/lock3"; printf 'pid=%s\nstarted=now\nhost=selftest\ntoken=live\n' "$$" >"$WORK/lock3/owner"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock3"
{ [ "$RC" -ne 0 ] && grep -q "refusing concurrent seed" <<<"$OUT" && ! seed_launched && [ -d "$WORK/lock3" ]; } &&
    report ok "g3 live-owner lock -> single-flight refusal, NO seed launched, lock untouched" ||
    report fail "g3 concurrent gate must refuse" "rc=$RC out=$OUT"
rm -rf "$WORK/lock3"

# ---- g4: device reports SEED_FAILED for THIS token -> gate fails
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_FAIL" run_gate "$WORK/lock4"
{ [ "$RC" -ne 0 ] && grep -q "reported SEED_FAILED" <<<"$OUT"; } &&
    report ok "g4 SEED_FAILED (this token) -> SEED_GATE_FAIL" ||
    report fail "g4 SEED_FAILED must fail the gate" "rc=$RC out=$OUT"

# ---- g5: no verdict within the window -> fail
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="" run_gate "$WORK/lock5"
{ [ "$RC" -ne 0 ] && grep -q "no token-bound" <<<"$OUT"; } &&
    report ok "g5 no verdict in window -> SEED_GATE_FAIL" ||
    report fail "g5 missing verdict must fail" "rc=$RC out=$OUT"

# ---- g6: SEED_LOCAL_VERIFIED WITHOUT the gap⑦ split -> NOT a pass
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_BARE" run_gate "$WORK/lock6"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g6 local-verified WITHOUT gap7 split -> not a pass" ||
    report fail "g6 bare local-verified must not pass" "rc=$RC out=$OUT"

# ---- g7 (R9): STALE success from an earlier launch, NEW launch emits nothing -> must FAIL
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_OK" FAKE_LOGS_AFTER="" run_gate "$WORK/lock7"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g7 stale success + new timeout -> SEED_GATE_FAIL (prior invocation's verdict not borrowed)" ||
    report fail "g7 stale success must not pass a new launch" "rc=$RC out=$OUT"

# ---- g8 (R9): STALE success + NEW failure -> FAIL for the new failure
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_OK" FAKE_LOGS_AFTER="$NEW_FAIL" run_gate "$WORK/lock8"
{ [ "$RC" -ne 0 ] && grep -q "reported SEED_FAILED" <<<"$OUT"; } &&
    report ok "g8 stale success + new SEED_FAILED -> SEED_GATE_FAIL" ||
    report fail "g8 new failure must win over stale success" "rc=$RC out=$OUT"

# ---- g8b (R9): STALE failure + NEW valid pair -> PASS (stale failure must not poison)
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_FAIL" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock8b"
{ [ "$RC" -eq 0 ] && grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g8b stale SEED_FAILED + new valid pair -> SEED_GATE_PASS (stale failure ignored)" ||
    report fail "g8b stale failure must not poison a valid run" "rc=$RC out=$OUT"

# ---- g9 (R9): pidof probe ERROR (rc=42) -> abort, seed NEVER launched
FAKE_PID_MODE=error FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock9"
{ [ "$RC" -ne 0 ] && grep -qi "probe" <<<"$OUT" && ! seed_launched; } &&
    report ok "g9 pidof probe error -> SEED_GATE_FAIL, NO seed launched (error != absence)" ||
    report fail "g9 probe error must abort" "rc=$RC launched=$(seed_launched && echo yes || echo no) out=$OUT"

# ---- g9b (R9): adb transport failure (no remote status) -> abort, NO seed
FAKE_PID_MODE=noremote FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock9b"
{ [ "$RC" -ne 0 ] && ! seed_launched; } &&
    report ok "g9b adb transport failure during PID probe -> SEED_GATE_FAIL, NO seed launched" ||
    report fail "g9b transport failure must abort" "rc=$RC launched=$(seed_launched && echo yes || echo no) out=$OUT"

# ---- g10 (R9): inconsistent terminal markers for the SAME token -> FAIL
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_BOTH" run_gate "$WORK/lock10"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g10 VERIFIED pair + SEED_FAILED for one token -> inconsistent -> SEED_GATE_FAIL" ||
    report fail "g10 inconsistent terminals must fail" "rc=$RC out=$OUT"

# ---- g11 (R9): verified marker echoes a DIFFERENT digest -> FAIL
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_WRONGDIGEST" run_gate "$WORK/lock11"
{ [ "$RC" -ne 0 ] && grep -qi "digest" <<<"$OUT" && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g11 marker digest != launched digest -> SEED_GATE_FAIL" ||
    report fail "g11 digest echo must be bound" "rc=$RC out=$OUT"

# ---- g12 (R9): near-miss token (prefix match) must be ignored -> FAIL (no verdict)
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEAR_TOKEN" run_gate "$WORK/lock12"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g12 token=<ours>x is NOT our token -> no verdict -> SEED_GATE_FAIL" ||
    report fail "g12 token match must be exact" "rc=$RC out=$OUT"

# ---- g13 (R9 P2): stale lock, owner pid DEAD, device absent -> reclaimed -> PASS
sleep 0 & DEAD=$!; wait "$DEAD" 2>/dev/null
mkdir -p "$WORK/lock13"; printf 'pid=%s\nstarted=2026-09-01T00:00:00Z\nhost=selftest\ntoken=old\n' "$DEAD" >"$WORK/lock13/owner"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock13"
{ [ "$RC" -eq 0 ] && grep -q "RECLAIMED_STALE_LOCK" <<<"$OUT" && grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g13 dead-owner lock + quiescent device -> reclaimed, then PASS" ||
    report fail "g13 stale lock must be reclaimable" "rc=$RC out=$OUT"

# ---- g14 (R9 P2): stale lock, owner DEAD, but device shows a LIVE bench process -> refuse
sleep 0 & DEAD2=$!; wait "$DEAD2" 2>/dev/null
mkdir -p "$WORK/lock14"; printf 'pid=%s\nstarted=2026-09-01T00:00:00Z\nhost=selftest\ntoken=old\n' "$DEAD2" >"$WORK/lock14/owner"
FAKE_PID_MODE=alive FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock14"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ -d "$WORK/lock14" ]; } &&
    report ok "g14 dead-owner lock but LIVE bench process -> refuse to reclaim, NO seed" ||
    report fail "g14 must not reclaim over a live seed" "rc=$RC launched=$(seed_launched && echo yes || echo no) out=$OUT"
rm -rf "$WORK/lock14"

# ---- g15 (R9 P2): lock without owner record -> refuse (cannot prove the owner is gone)
mkdir -p "$WORK/lock15"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock15"
{ [ "$RC" -ne 0 ] && ! seed_launched; } &&
    report ok "g15 ownerless lock -> refuse (no silent reclaim)" ||
    report fail "g15 ownerless lock must refuse" "rc=$RC out=$OUT"
rm -rf "$WORK/lock15"

# ---- g16 (R9): the verdict is in, but the bench process is ALIVE again at handoff -> FAIL, no PASS
FAKE_PID_MODE=absent FAKE_PID_MODE_AFTER_LAUNCH=alive FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock16"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "handoff" <<<"$OUT"; } &&
    report ok "g16 live writer at handoff -> SEED_GATE_FAIL (no PASS released over a live package)" ||
    report fail "g16 handoff must be quiescent" "rc=$RC out=$OUT"

printf 'seed-10a-gate selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
