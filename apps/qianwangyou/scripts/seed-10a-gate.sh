#!/usr/bin/env bash
#
# G2 §5A executable, fail-closed single-flight seed gate (PR #62 R8 P1-1).
#
# WHY THIS EXISTS
# --------------
# The in-app owner fence (APlus10AOwnerFence) serializes only
# EnvironmentControlHandler's OWN fenced ops. prepareKyiv, ProfileRepository,
# and the settings UI mutate the SAME profile table + transport WITHOUT that
# lock, so a concurrent writer during the seed produces durable bytes the
# seed's own schedule/lease checks cannot see. The runbook's prose "force-stop
# then seed" was operator discipline, not an enforced gate: commented commands
# execute nothing, `pidof … || true` never aborts, and nothing prevents two
# overlapping seeds.
#
# This script is the executable gate. It is the SOLE sanctioned seed launcher.
# It fails closed — a nonzero exit means NO trustworthy seed was produced:
#
#   1. EXCLUSIVE host lock (atomic mkdir) — two gates cannot run at once; the
#      seed is the single writer for its window.
#   2. force-stop the bench package, then ASSERT its process is gone (pidof
#      empty). A surviving PID is a live writer domain → abort, never seed.
#   3. Launch the SOLE seed command.
#   4. Await the seed's own verdict in logcat: SEED_LOCAL_VERIFIED +
#      SEED_CONTRACT_INCOMPLETE (the honest split — see gap⑦), owned by THIS
#      protected transaction, is SEED_GATE_PASS. SEED_FAILED or a timeout is
#      SEED_GATE_FAIL.
#   5. The success marker (SEED_GATE_PASS) is emitted only inside this gate,
#      so a green verdict cannot exist without the lock+quiescence proof.
#
# Device-free-guarded by scripts/selftest-seed-10a-gate.sh (fakes dev/pidof/
# logcat and pins each fail-closed branch).
set -u

BENCH_PACKAGE="name.caiyao.fakegps.bench"
SEED_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
LOCK_DIR="${SEED_GATE_LOCK_DIR:-${TMPDIR:-/tmp}/fakegps-seed-10a-gate.lock}"
SEED_AWAIT_TRIES="${SEED_GATE_AWAIT_TRIES:-40}"
FIXTURE_B64=""
FIXTURE_DIGEST=""

usage() {
    echo "usage: $0 --fixture <base64> --digest <sha256> [--package <pkg>]" >&2
    echo "  fail-closed: nonzero exit = no trustworthy seed produced" >&2
}

# Device seam — the ONLY edge the selftest fakes.
dev() { adb "$@"; }

# One PID line, trimmed; empty when the process is gone.
bench_pid() { dev shell pidof "$BENCH_PACKAGE" 2>/dev/null | tr -d '\r\n[:space:]'; }

acquire_lock_or_abort() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        return 0
    fi
    echo "SEED_GATE_FAIL another seed gate holds $LOCK_DIR — refusing concurrent seed (single-flight)" >&2
    return 3
}

release_lock() { [ -n "$LOCK_DIR" ] && rmdir "$LOCK_DIR" 2>/dev/null || true; }

# force-stop, then ASSERT the process is actually gone. A surviving PID is a
# live unfenced writer domain — abort, do NOT seed.
force_stop_and_assert_quiescent() {
    dev shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1 || {
        echo "SEED_GATE_FAIL could not force-stop $BENCH_PACKAGE" >&2
        return 2
    }
    tries=0
    while [ "$tries" -lt 10 ]; do
        pid=$(bench_pid)
        [ -z "$pid" ] && return 0
        sleep 1
        tries=$((tries + 1))
    done
    echo "SEED_GATE_FAIL $BENCH_PACKAGE still alive (pid=$(bench_pid)) after force-stop — a live writer domain; refusing to seed" >&2
    return 2
}

launch_seed() {
    dev shell am start -n "$SEED_ACT" \
        --es command prepare_10a \
        --es fixture_payload_base64 "$FIXTURE_B64" \
        --es fixture_digest "$FIXTURE_DIGEST" >/dev/null 2>&1 || {
        echo "SEED_GATE_FAIL could not launch the seed activity" >&2
        return 2
    }
}

read_seed_logs() {
    dev logcat -d -v brief -s FakeGPSAcceptance:I MockProviderAcceptance:I '*:S' 2>/dev/null
}

# The seed's HONEST verdict (gap⑦): success is SEED_LOCAL_VERIFIED AND
# SEED_CONTRACT_INCOMPLETE gap=7 together — never a bare READY. SEED_FAILED is
# an explicit failure. Absence within the window is also a failure.
await_seed_verdict() {
    tries=0
    while [ "$tries" -lt "$SEED_AWAIT_TRIES" ]; do
        logs=$(read_seed_logs)
        if printf '%s\n' "$logs" | grep -Fq "SEED_FAILED command=prepare_10a"; then
            echo "SEED_GATE_FAIL the seed reported SEED_FAILED" >&2
            return 2
        fi
        if printf '%s\n' "$logs" | grep -Fq "SEED_LOCAL_VERIFIED command=prepare_10a" &&
            printf '%s\n' "$logs" | grep -Fq "SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7"; then
            return 0
        fi
        sleep 1
        tries=$((tries + 1))
    done
    echo "SEED_GATE_FAIL no SEED_LOCAL_VERIFIED+SEED_CONTRACT_INCOMPLETE within ${SEED_AWAIT_TRIES}s" >&2
    return 2
}

seed_gate_main() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --fixture) FIXTURE_B64="${2:?--fixture needs a value}"; shift 2 ;;
            --digest) FIXTURE_DIGEST="${2:?--digest needs a value}"; shift 2 ;;
            --package) BENCH_PACKAGE="${2:?--package needs a value}"
                SEED_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"; shift 2 ;;
            -h|--help) usage; return 0 ;;
            *) echo "unknown arg: $1" >&2; usage; return 2 ;;
        esac
    done
    [ -n "$FIXTURE_B64" ] && [ -n "$FIXTURE_DIGEST" ] || { usage; return 2; }
    command -v adb >/dev/null || { echo "SEED_GATE_FAIL adb not found" >&2; return 2; }

    acquire_lock_or_abort || return $?
    # From here the lock is held; every exit path releases it.
    trap 'release_lock' EXIT

    force_stop_and_assert_quiescent || return $?
    launch_seed || return $?
    await_seed_verdict || return $?
    echo "SEED_GATE_PASS command=prepare_10a package=$BENCH_PACKAGE (exclusive lock + quiescent PID + seed verdict, owned by this transaction)"
    return 0
}

# Only run main when executed, not when sourced (the selftest sources the
# functions with fakes in place).
if [ "${SEED_GATE_SOURCE_ONLY:-0}" != "1" ]; then
    seed_gate_main "$@"
    exit $?
fi
