#!/usr/bin/env bash
#
# G2 §5A executable, fail-closed single-flight seed gate (PR #62 R8 P1-1 → R9 P1).
#
# WHY THIS EXISTS
# --------------
# The in-app owner fence (APlus10AOwnerFence) serializes only
# EnvironmentControlHandler's OWN fenced ops. prepareKyiv, ProfileRepository,
# and the settings UI mutate the SAME profile table + transport WITHOUT that
# lock, so a concurrent writer during the seed produces durable bytes the
# seed's own schedule/lease checks cannot see. The runbook's prose "force-stop
# then seed" was operator discipline, not an enforced gate.
#
# This script is the executable gate. It is the SOLE sanctioned seed launcher.
# It fails closed — a nonzero exit means NO trustworthy seed was produced:
#
#   1. EXCLUSIVE host lock (atomic mkdir) carrying owner metadata (pid, start,
#      host, token). A held lock is reclaimed ONLY when its recorded owner is
#      provably dead AND the device shows no live bench process; a live owner,
#      a missing owner record, or a live device process all refuse (R9 P2).
#   2. force-stop the bench package, then ASSERT its process is gone through a
#      TRI-STATE probe: the remote shell echoes pidof's own exit status, so an
#      adb transport failure / odd status can never read as "no process"
#      (R9 P1: `adb shell pidof | tr` lost that status). probe_failed aborts.
#   3. Launch the SOLE seed command bound to a UNIQUE launch token
#      (--es seed_token). The Activity echoes token= and digest= in every
#      terminal marker.
#   4. Await the seed's own verdict for THIS token only: exactly one
#      SEED_LOCAL_VERIFIED (echoing the launched digest) + exactly one
#      SEED_CONTRACT_INCOMPLETE gap=7 (the honest split — see gap⑦) is
#      SEED_GATE_PASS; SEED_FAILED for this token, a FAILED+VERIFIED mix,
#      duplicate markers, a foreign digest, or a timeout is SEED_GATE_FAIL.
#      Stale markers from earlier launches (other tokens) are ignored, so a
#      prior invocation's success can never be borrowed and a prior failure
#      can never poison a valid run (R9 P1).
#   5. Hand off QUIESCENT: force-stop again after the verdict and re-assert
#      absence, so the seeded durable state is released with no live writer
#      (R9 P1 "gate releases while the fresh package remains alive").
#   6. The success marker (SEED_GATE_PASS token=… digest=…) is emitted only
#      inside this gate, so a green verdict cannot exist without the
#      lock + quiescence + token-bound-verdict + handoff proof.
#
# Optional: --evidence-dir <dir> dumps the device's REAL published transport
# (shared_prefs/spoof_config.xml via run-as, plus the extracted canonical JSON)
# after PASS — the device-side canonical fixture for the record. Non-fatal.
#
# Device-free-guarded by scripts/selftest-seed-10a-gate.sh (fakes dev/sleep
# and pins each fail-closed branch).
set -u

BENCH_PACKAGE="name.caiyao.fakegps.bench"
SEED_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
LOCK_DIR="${SEED_GATE_LOCK_DIR:-${TMPDIR:-/tmp}/fakegps-seed-10a-gate.lock}"
SEED_AWAIT_TRIES="${SEED_GATE_AWAIT_TRIES:-40}"
EVIDENCE_DIR=""
FIXTURE_B64=""
FIXTURE_DIGEST=""
SEED_TOKEN=""

usage() {
    echo "usage: $0 --fixture <base64> --digest <sha256> [--package <pkg>] [--evidence-dir <dir>]" >&2
    echo "  fail-closed: nonzero exit = no trustworthy seed produced" >&2
}

# Device seam — the ONLY edge the selftest fakes.
dev() { adb "$@"; }

# #90: Vector-aware evidence resolver (exact-package, live-zone, fail-closed).
# When this gate is executed, $0 is the gate itself; when the selftest sources
# it, $0 is the selftest — so an explicit VE_LIB_PATH override wins.
VE_LIB="${VE_LIB_PATH:-$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)/vector-evidence.sh}"
[ -r "$VE_LIB" ] || { echo "SEED_GATE_FAIL vector-evidence.sh not found at $VE_LIB" >&2; exit 2; }
# shellcheck source=vector-evidence.sh
. "$VE_LIB"

# [A-Za-z0-9-] only: echoed verbatim by the Activity and matched EXACTLY in logcat.
new_seed_token() {
    printf '%s-%s-%s%s' "$(date -u +%Y%m%dT%H%M%SZ)" "${BASHPID:-$$}" "$RANDOM" "$RANDOM"
}

# Tri-state PID probe. Prints exactly one line:
#   alive <pid>       pidof exited 0 with a pid
#   absent            pidof exited 1 with no output
#   probe_failed …    anything else (adb failure, no remote status, odd rc/output)
probe_bench_pid() {
    local raw adb_rc rc_line remote_rc pid
    raw=$(dev shell "pidof $BENCH_PACKAGE; echo __RC=\$?" 2>/dev/null)
    adb_rc=$?
    raw=$(printf '%s\n' "$raw" | tr -d '\r')
    rc_line=$(printf '%s\n' "$raw" | grep -E '^__RC=[0-9]+$' | tail -1)
    if [ -z "$rc_line" ]; then
        printf 'probe_failed adb_rc=%s no-remote-status\n' "$adb_rc"
        return 0
    fi
    remote_rc=${rc_line#__RC=}
    pid=$(printf '%s\n' "$raw" | grep -v '^__RC=' | tr -d '[:space:]')
    case "$remote_rc" in
        0) if [ -n "$pid" ]; then printf 'alive %s\n' "$pid"; else printf 'probe_failed rc=0-without-pid\n'; fi ;;
        1) if [ -z "$pid" ]; then printf 'absent\n'; else printf 'probe_failed rc=1-with-output\n'; fi ;;
        *) printf 'probe_failed remote_rc=%s\n' "$remote_rc" ;;
    esac
}

lock_owner_field() { sed -n "s/^$1=//p" "$LOCK_DIR/owner" 2>/dev/null | head -1; }

write_lock_owner() {
    printf 'pid=%s\nstarted=%s\nhost=%s\ntoken=%s\npackage=%s\n' \
        "${BASHPID:-$$}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname 2>/dev/null || echo unknown)" \
        "$SEED_TOKEN" "$BENCH_PACKAGE" >"$LOCK_DIR/owner"
}

# Exclusive lock with safe stale-owner reclamation (R9 P2).
acquire_lock_or_abort() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_owner
        return 0
    fi
    local owner_pid state
    owner_pid=$(lock_owner_field pid)
    if [ -z "$owner_pid" ]; then
        echo "SEED_GATE_FAIL $LOCK_DIR is held with no owner record — cannot prove the owner is gone; refusing concurrent seed (single-flight). Remove it by hand only after proving no seed is running." >&2
        return 3
    fi
    if kill -0 "$owner_pid" 2>/dev/null; then
        echo "SEED_GATE_FAIL another seed gate holds $LOCK_DIR (pid=$owner_pid started=$(lock_owner_field started)) — refusing concurrent seed (single-flight)" >&2
        return 3
    fi
    state=$(probe_bench_pid)
    if [ "$state" != "absent" ]; then
        echo "SEED_GATE_FAIL $LOCK_DIR owner pid=$owner_pid is dead but the device shows '$state' — a seed may still be running; refusing to reclaim (single-flight)" >&2
        return 3
    fi
    echo "SEED_GATE_RECLAIMED_STALE_LOCK $LOCK_DIR owner pid=$owner_pid dead, device quiescent — reclaiming" >&2
    rm -rf "$LOCK_DIR"
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        write_lock_owner
        return 0
    fi
    echo "SEED_GATE_FAIL lost the race re-acquiring $LOCK_DIR after reclaim" >&2
    return 3
}

release_lock() { { [ -n "$LOCK_DIR" ] && [ -d "$LOCK_DIR" ] && rm -rf "$LOCK_DIR"; } 2>/dev/null || true; }

# force-stop, then ASSERT the process is actually gone (tri-state). A surviving
# PID is a live unfenced writer domain and a failed probe is NOT absence —
# both abort. $1 = phase label (pre-seed | handoff).
force_stop_and_assert_quiescent() {
    local phase="${1:-pre-seed}" tries=0 state
    dev shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1 || {
        echo "SEED_GATE_FAIL could not force-stop $BENCH_PACKAGE ($phase)" >&2
        return 2
    }
    while [ "$tries" -lt 10 ]; do
        state=$(probe_bench_pid)
        case "$state" in
            absent) return 0 ;;
            alive\ *) sleep 1; tries=$((tries + 1)) ;;
            *)
                echo "SEED_GATE_FAIL PID probe failed ($state) during $phase — cannot prove quiescence; a probe failure is not absence" >&2
                return 2 ;;
        esac
    done
    echo "SEED_GATE_FAIL $BENCH_PACKAGE still alive ($state) after force-stop ($phase) — a live writer domain; refusing" >&2
    return 2
}

launch_seed() {
    dev shell am start -n "$SEED_ACT" \
        --es command prepare_10a \
        --es fixture_payload_base64 "$FIXTURE_B64" \
        --es fixture_digest "$FIXTURE_DIGEST" \
        --es seed_token "$SEED_TOKEN" >/dev/null 2>&1 || {
        echo "SEED_GATE_FAIL could not launch the seed activity" >&2
        return 2
    }
}

read_seed_logs() {
    dev logcat -d -v brief -s FakeGPSAcceptance:I MockProviderAcceptance:I '*:S' 2>/dev/null
}

# Only lines carrying EXACTLY our token (token=<t> followed by space or EOL).
token_lines() { printf '%s\n' "$1" | grep -E -- " token=${SEED_TOKEN}( |\$)"; }
count_marker() { printf '%s\n' "$1" | grep -c -F -- "$2"; }

# Exactly one internally consistent terminal result for THIS token.
await_seed_verdict() {
    local tries=0 logs mine failed verified incomplete
    while [ "$tries" -lt "$SEED_AWAIT_TRIES" ]; do
        logs=$(read_seed_logs)
        mine=$(token_lines "$logs")
        failed=$(count_marker "$mine" "SEED_FAILED command=prepare_10a")
        verified=$(count_marker "$mine" "SEED_LOCAL_VERIFIED command=prepare_10a")
        incomplete=$(count_marker "$mine" "SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7")
        if [ "$failed" -gt 0 ] && { [ "$verified" -gt 0 ] || [ "$incomplete" -gt 0 ]; }; then
            echo "SEED_GATE_FAIL inconsistent terminal markers for token $SEED_TOKEN (failed=$failed verified=$verified incomplete=$incomplete)" >&2
            return 2
        fi
        if [ "$failed" -gt 0 ]; then
            echo "SEED_GATE_FAIL the seed reported SEED_FAILED (token $SEED_TOKEN)" >&2
            return 2
        fi
        if [ "$verified" -gt 1 ] || [ "$incomplete" -gt 1 ]; then
            echo "SEED_GATE_FAIL duplicate terminal markers for token $SEED_TOKEN (verified=$verified incomplete=$incomplete)" >&2
            return 2
        fi
        if [ "$verified" -eq 1 ] && [ "$incomplete" -eq 1 ]; then
            if printf '%s\n' "$mine" | grep -F "SEED_LOCAL_VERIFIED command=prepare_10a" | grep -q -E -- " digest=${FIXTURE_DIGEST}( |\$)"; then
                return 0
            fi
            echo "SEED_GATE_FAIL verified marker for token $SEED_TOKEN does not echo the launched digest $FIXTURE_DIGEST" >&2
            return 2
        fi
        sleep 1
        tries=$((tries + 1))
    done
    echo "SEED_GATE_FAIL no token-bound SEED_LOCAL_VERIFIED+SEED_CONTRACT_INCOMPLETE for token $SEED_TOKEN within ${SEED_AWAIT_TRIES}s (markers from other launches are ignored)" >&2
    return 2
}

# Evidence for the record: the device's REAL published transport. #90: the
# canonical source is the LIVE Vector zone (/data/misc/*/prefs/<exact-package>/)
# read via root; the app-private shared_prefs copy is at best a stale
# pre-Vector mirror and is NEVER emitted as canonical. Zero/multiple live
# sources, read failure, or missing root FAIL CLOSED — an explicitly requested
# evidence capture that cannot prove its source zone is a gate failure, not a
# note, because a self-consistent stale mirror is exactly the lap-3 false-P1
# shape this closes.
dump_evidence() {
    [ -n "$EVIDENCE_DIR" ] || return 0
    mkdir -p "$EVIDENCE_DIR" 2>/dev/null || { echo "SEED_GATE_NOTE evidence dir $EVIDENCE_DIR not writable — skipping dump" >&2; return 0; }
    if ! ve_capture_evidence "$BENCH_PACKAGE" spoof_config.xml "$EVIDENCE_DIR"; then
        echo "SEED_GATE_FAIL --evidence-dir requested but canonical Vector-live capture failed (fail-closed; never falls back to the app-private mirror)" >&2
        return 2
    fi
    # Back-compat names for existing evidence consumers: the canonical capture
    # is vector-prefs/spoof_config.xml (+ .provenance); these are byte copies.
    cp "$EVIDENCE_DIR/vector-prefs/spoof_config.xml" "$EVIDENCE_DIR/seed-published-transport.xml"
    tr -d '\r' <"$EVIDENCE_DIR/seed-published-transport.xml" |
        sed -n 's/.*name="json">\(.*\)<\/string>.*/\1/p' |
        sed 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g' >"$EVIDENCE_DIR/seed-published-payload.json"
    echo "SEED_GATE_EVIDENCE token=$SEED_TOKEN transport=$EVIDENCE_DIR/vector-prefs/spoof_config.xml payload=$EVIDENCE_DIR/seed-published-payload.json (zone=vector-live; provenance alongside)"
}

seed_gate_main() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --fixture) FIXTURE_B64="${2:?--fixture needs a value}"; shift 2 ;;
            --digest) FIXTURE_DIGEST="${2:?--digest needs a value}"; shift 2 ;;
            --package) BENCH_PACKAGE="${2:?--package needs a value}"
                SEED_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"; shift 2 ;;
            --evidence-dir) EVIDENCE_DIR="${2:?--evidence-dir needs a value}"; shift 2 ;;
            -h|--help) usage; return 0 ;;
            *) echo "unknown arg: $1" >&2; usage; return 2 ;;
        esac
    done
    [ -n "$FIXTURE_B64" ] && [ -n "$FIXTURE_DIGEST" ] || { usage; return 2; }
    command -v adb >/dev/null || { echo "SEED_GATE_FAIL adb not found" >&2; return 2; }
    SEED_TOKEN="$(new_seed_token)"
    case "$SEED_TOKEN" in *[!A-Za-z0-9-]*|"") echo "SEED_GATE_FAIL malformed launch token" >&2; return 2 ;; esac

    acquire_lock_or_abort || return $?
    # From here the lock is held; every exit path releases it.
    trap 'release_lock' EXIT

    force_stop_and_assert_quiescent pre-seed || return $?
    launch_seed || return $?
    await_seed_verdict || return $?
    dump_evidence || return $?
    force_stop_and_assert_quiescent handoff || {
        echo "SEED_GATE_FAIL seeded state could not be handed off quiescent (token $SEED_TOKEN)" >&2
        return 2
    }
    echo "SEED_GATE_PASS command=prepare_10a package=$BENCH_PACKAGE token=$SEED_TOKEN digest=$FIXTURE_DIGEST (exclusive lock + tri-state quiescent PID + token-bound single terminal verdict + quiescent handoff, owned by this transaction)"
    return 0
}

# Only run main when executed, not when sourced (the selftest sources the
# functions with fakes in place).
if [ "${SEED_GATE_SOURCE_ONLY:-0}" != "1" ]; then
    seed_gate_main "$@"
    exit $?
fi
