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
#   1. EXCLUSIVE host lock (atomic mkdir) carrying a magic-bound exact owner
#      schema (pid, start, host, token, package). A held lock is reclaimed ONLY
#      when that exact record names a positive dead PID, the device shows no
#      live bench process, and the directory contains only its regular owner
#      file. Cleanup unlinks that file then rmdir; it is never recursive.
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
#   6. If evidence was requested, finish and whitelist a hidden sibling stage
#      while the lock is held. Then release the exact owned lock and prove its
#      path absent. Only after that postcondition may the stage be atomically
#      published create-only.
#   7. VE_OK / SEED_GATE_EVIDENCE / SEED_GATE_PASS are emitted only after that
#      state transition, so a green verdict cannot exist without lock cleanup,
#      quiescence, token-bound verdict, handoff, and complete evidence (if any).
#
# Optional: --evidence-dir <fresh-dir> captures the exact-package live Vector
# transport plus labeled historical mirror and derived payload only AFTER the
# post-verdict handoff has proved the package quiescent. The directory must not
# already exist; the complete artifact set is staged beside it and published by
# an atomic no-replace rename. Any capture/hash/write/publish failure is fatal,
# so stale or partial evidence can never accompany SEED_GATE_PASS.
#
# Device-free-guarded by scripts/selftest-seed-10a-gate.sh (fakes dev/sleep
# and pins each fail-closed branch).
set -u

BENCH_PACKAGE="name.caiyao.fakegps.bench"
SEED_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity"
if [ -n "${SEED_GATE_LOCK_DIR:-}" ]; then
    if [ "${SEED_GATE_SOURCE_ONLY:-0}" != 1 ]; then
        echo "SEED_GATE_FAIL SEED_GATE_LOCK_DIR override is allowed only by the sourced host selftest" >&2
        exit 2
    fi
    LOCK_DIR="$SEED_GATE_LOCK_DIR"
else
    LOCK_DIR="/tmp/fakegps-seed-10a-gate.lock"
fi
SEED_AWAIT_TRIES="${SEED_GATE_AWAIT_TRIES:-40}"
EVIDENCE_DIR=""
FIXTURE_B64=""
FIXTURE_DIGEST=""
SEED_TOKEN=""
SEED_LAUNCH_ATTEMPTED=0
SEED_POST_LAUNCH_QUIESCENT=0
SEED_EVIDENCE_STAGE=""
SEED_EVIDENCE_PREPARED=0
SEED_LOCK_OWNED=0
LOCK_OWNER_MAGIC="fakegps-seed-lock-v1"
LOCK_OWNER_PID=""
LOCK_OWNER_STARTED=""
LOCK_OWNER_HOST=""
LOCK_OWNER_TOKEN=""
LOCK_OWNER_PACKAGE=""

usage() {
    echo "usage: $0 --fixture <base64> --digest <sha256> [--package <pkg>] [--evidence-dir <fresh-dir>]" >&2
    echo "  fail-closed: nonzero exit = no trustworthy seed produced" >&2
}

# Device seam — the ONLY edge the selftest fakes.
dev() { adb "$@"; }
ve_live_root_shell() {
    # adb shell joins argv without adding quotes. Keep the complete remote
    # program in ONE argv and preserve the inner command as su -c's one operand.
    # Every command supplied by vector-evidence is built from strict coordinates
    # and must never contain a single quote.
    case "$1" in
        *"'"*)
            echo "SEED_GATE_FAIL unsafe quote in privileged read command" >&2
            return 2 ;;
    esac
    dev shell "su -c '$1'"
}

# #90: Vector-aware evidence resolver (exact-package, live-zone, fail-closed).
# When this gate is executed, $0 is the gate itself; when the selftest sources
# it, $0 is the selftest — so an explicit VE_LIB_PATH override wins.
if [ -n "${VE_LIB_PATH:-}" ]; then
    VE_LIB="$VE_LIB_PATH"
else
    seed_script_dir=$(dirname -- "$0" 2>/dev/null)
    seed_script_dir_rc=$?
    if [ "$seed_script_dir_rc" -ne 0 ] || [ -z "$seed_script_dir" ]; then
        echo "SEED_GATE_FAIL cannot resolve seed gate script directory" >&2
        exit 2
    fi
    seed_script_dir=$(CDPATH= cd -- "$seed_script_dir" 2>/dev/null && pwd)
    seed_script_dir_rc=$?
    if [ "$seed_script_dir_rc" -ne 0 ] || [ -z "$seed_script_dir" ]; then
        echo "SEED_GATE_FAIL cannot canonicalize seed gate script directory" >&2
        exit 2
    fi
    VE_LIB="$seed_script_dir/vector-evidence.sh"
fi
[ -r "$VE_LIB" ] || { echo "SEED_GATE_FAIL vector-evidence.sh not found at $VE_LIB" >&2; exit 2; }
# shellcheck source=vector-evidence.sh
. "$VE_LIB"

# [A-Za-z0-9-] only: echoed verbatim by the Activity and matched EXACTLY in logcat.
new_seed_token() {
    local timestamp timestamp_rc
    timestamp=$(date -u +%Y%m%dT%H%M%SZ)
    timestamp_rc=$?
    [ "$timestamp_rc" -eq 0 ] && [ -n "$timestamp" ] || return 1
    printf '%s-%s-%s%s' "$timestamp" "${BASHPID:-$$}" "$RANDOM" "$RANDOM"
}

validate_fixture_base64() {
    # adb shell joins argv and asks the remote shell to parse the result. Limit
    # this value to canonical RFC 4648 standard base64 so it is both a valid
    # transport and one shell-safe token (no whitespace/metacharacters).
    local value="$1" body last length
    [ -n "$value" ] || return 1
    case "$value" in *[!A-Za-z0-9+/=]*) return 1 ;; esac
    length=${#value}
    [ $((length % 4)) -eq 0 ] || return 1
    case "$value" in
        *==)
            body=${value%==}
            case "$body" in ""|*=*) return 1 ;; esac
            last=${body:${#body}-1:1}
            # With two padding bytes, the low four pad bits must be zero.
            case "$last" in A|Q|g|w) ;; *) return 1 ;; esac
            ;;
        *=)
            body=${value%=}
            case "$body" in ""|*=*) return 1 ;; esac
            last=${body:${#body}-1:1}
            # With one padding byte, the low two pad bits must be zero.
            case "$last" in A|E|I|M|Q|U|Y|c|g|k|o|s|w|0|4|8) ;; *) return 1 ;; esac
            ;;
        *)
            case "$value" in *=*) return 1 ;; esac
            ;;
    esac
    return 0
}

# Tri-state PID probe. Prints exactly one line:
#   alive <pid>       pidof exited 0 with a pid
#   absent            pidof exited 1 with no output
#   probe_failed …    anything else (adb failure, no remote status, odd rc/output)
probe_bench_pid() {
    local raw adb_rc line remote_rc="" remote_status_count=0 pid="" pid_part
    raw=$(dev shell "pidof $BENCH_PACKAGE; echo __RC=\$?" 2>/dev/null)
    adb_rc=$?
    if [ "$adb_rc" -ne 0 ]; then
        printf 'probe_failed adb_rc=%s transport-failed\n' "$adb_rc"
        return 0
    fi
    raw=${raw//$'\r'/}
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            __RC=*)
                remote_rc=${line#__RC=}
                case "$remote_rc" in
                    ""|*[!0-9]*)
                        printf 'probe_failed malformed-remote-status\n'
                        return 0 ;;
                esac
                remote_status_count=$((remote_status_count + 1))
                ;;
            "") ;;
            *)
                # pidof may return multiple numeric PIDs separated by spaces.
                # Parse them with shell builtins so no downstream filter can
                # hide an upstream status.
                for pid_part in $line; do
                    case "$pid_part" in
                        ""|*[!0-9]*)
                            printf 'probe_failed malformed-pid-output\n'
                            return 0 ;;
                    esac
                    if [ -n "$pid" ]; then pid="$pid $pid_part"; else pid="$pid_part"; fi
                done
                ;;
        esac
    done <<<"$raw"
    if [ "$remote_status_count" -ne 1 ]; then
        printf 'probe_failed remote-status-count=%s\n' "$remote_status_count"
        return 0
    fi
    case "$remote_rc" in
        0) if [ -n "$pid" ]; then printf 'alive %s\n' "$pid"; else printf 'probe_failed rc=0-without-pid\n'; fi ;;
        1) if [ -z "$pid" ]; then printf 'absent\n'; else printf 'probe_failed rc=1-with-output\n'; fi ;;
        *) printf 'probe_failed remote_rc=%s\n' "$remote_rc" ;;
    esac
}

write_lock_owner() {
    local started started_rc owner_host owner_host_rc write_rc
    started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    started_rc=$?
    [ "$started_rc" -eq 0 ] && [ -n "$started" ] || return 1
    owner_host=$(hostname 2>/dev/null)
    owner_host_rc=$?
    [ "$owner_host_rc" -eq 0 ] && [ -n "$owner_host" ] || return 1
    printf 'format=%s\npid=%s\nstarted=%s\nhost=%s\ntoken=%s\npackage=%s\n' \
        "$LOCK_OWNER_MAGIC" "${BASHPID:-$$}" "$started" "$owner_host" \
        "$SEED_TOKEN" "$BENCH_PACKAGE" >"$LOCK_DIR/owner"
    write_rc=$?
    [ "$write_rc" -eq 0 ] || return "$write_rc"
    [ -f "$LOCK_DIR/owner" ] && [ ! -L "$LOCK_DIR/owner" ] || return 1
}

load_lock_owner_record() {
    local record record_rc line line_number=0
    LOCK_OWNER_PID=""
    LOCK_OWNER_STARTED=""
    LOCK_OWNER_HOST=""
    LOCK_OWNER_TOKEN=""
    LOCK_OWNER_PACKAGE=""
    [ -d "$LOCK_DIR" ] && [ ! -L "$LOCK_DIR" ] \
        && [ -f "$LOCK_DIR/owner" ] && [ ! -L "$LOCK_DIR/owner" ] || return 1
    record=$(cat -- "$LOCK_DIR/owner" 2>/dev/null)
    record_rc=$?
    [ "$record_rc" -eq 0 ] || return "$record_rc"
    while IFS= read -r line || [ -n "$line" ]; do
        line_number=$((line_number + 1))
        case "$line_number:$line" in
            "1:format=$LOCK_OWNER_MAGIC") ;;
            2:pid=*) LOCK_OWNER_PID=${line#pid=} ;;
            3:started=*) LOCK_OWNER_STARTED=${line#started=} ;;
            4:host=*) LOCK_OWNER_HOST=${line#host=} ;;
            5:token=*) LOCK_OWNER_TOKEN=${line#token=} ;;
            6:package=*) LOCK_OWNER_PACKAGE=${line#package=} ;;
            *) return 1 ;;
        esac
    done <<<"$record"
    [ "$line_number" -eq 6 ] || return 1
    case "$LOCK_OWNER_PID" in ""|0|*[!0-9]*) return 1 ;; esac
    case "$LOCK_OWNER_TOKEN" in ""|*[!A-Za-z0-9-]*) return 1 ;; esac
    [ -n "$LOCK_OWNER_STARTED" ] && [ -n "$LOCK_OWNER_HOST" ] \
        && [ "$LOCK_OWNER_PACKAGE" = "$BENCH_PACKAGE" ] || return 1
    return 0
}

lock_directory_has_only_owner() {
    [ -d "$LOCK_DIR" ] && [ ! -L "$LOCK_DIR" ] || return 1
    (
        local entries
        shopt -s nullglob dotglob
        entries=("$LOCK_DIR"/*)
        [ "${#entries[@]}" -eq 1 ] \
            && [ "${entries[0]}" = "$LOCK_DIR/owner" ] \
            && [ -f "$LOCK_DIR/owner" ] \
            && [ ! -L "$LOCK_DIR/owner" ]
    )
}

cleanup_just_created_lock_after_owner_failure() {
    local remove_rc rmdir_rc
    [ -d "$LOCK_DIR" ] && [ ! -L "$LOCK_DIR" ] || return 2
    (
        local entries
        shopt -s nullglob dotglob
        entries=("$LOCK_DIR"/*)
        if [ "${#entries[@]}" -eq 0 ]; then
            return 0
        fi
        [ "${#entries[@]}" -eq 1 ] \
            && [ "${entries[0]}" = "$LOCK_DIR/owner" ] \
            && [ -f "$LOCK_DIR/owner" ] \
            && [ ! -L "$LOCK_DIR/owner" ]
    ) || return 2
    if [ -e "$LOCK_DIR/owner" ] || [ -L "$LOCK_DIR/owner" ]; then
        rm -f -- "$LOCK_DIR/owner" 2>/dev/null
        remove_rc=$?
        if [ "$remove_rc" -ne 0 ] || [ -e "$LOCK_DIR/owner" ] || [ -L "$LOCK_DIR/owner" ]; then
            return 2
        fi
    fi
    rmdir -- "$LOCK_DIR" 2>/dev/null
    rmdir_rc=$?
    [ "$rmdir_rc" -eq 0 ] && [ ! -e "$LOCK_DIR" ] && [ ! -L "$LOCK_DIR" ]
}

release_lock() {
    local owner_rc remove_rc rmdir_rc
    [ "$SEED_LOCK_OWNED" -eq 1 ] || return 0
    load_lock_owner_record
    owner_rc=$?
    if [ "$owner_rc" -ne 0 ] \
        || [ "$LOCK_OWNER_PID" != "${BASHPID:-$$}" ] \
        || [ "$LOCK_OWNER_TOKEN" != "$SEED_TOKEN" ] \
        || ! lock_directory_has_only_owner; then
        echo "SEED_GATE_FAIL refusing to release a lock no longer matching this transaction" >&2
        return 2
    fi
    rm -f -- "$LOCK_DIR/owner" 2>/dev/null
    remove_rc=$?
    if [ "$remove_rc" -ne 0 ] || [ -e "$LOCK_DIR/owner" ] || [ -L "$LOCK_DIR/owner" ]; then
        return 2
    fi
    # Our identity record is gone. Never treat a concurrently-added entry as
    # ours and never recursively delete it.
    SEED_LOCK_OWNED=0
    rmdir -- "$LOCK_DIR" 2>/dev/null
    rmdir_rc=$?
    [ "$rmdir_rc" -eq 0 ] && [ ! -e "$LOCK_DIR" ] && [ ! -L "$LOCK_DIR" ]
}

initialize_new_lock_or_abort() {
    local owner_rc cleanup_rc
    write_lock_owner
    owner_rc=$?
    if [ "$owner_rc" -eq 0 ]; then
        load_lock_owner_record
        owner_rc=$?
        if [ "$owner_rc" -eq 0 ] \
            && [ "$LOCK_OWNER_PID" = "${BASHPID:-$$}" ] \
            && [ "$LOCK_OWNER_TOKEN" = "$SEED_TOKEN" ] \
            && lock_directory_has_only_owner; then
            SEED_LOCK_OWNED=1
            return 0
        fi
    fi
    echo "SEED_GATE_FAIL cannot write a trustworthy owner record in $LOCK_DIR" >&2
    cleanup_just_created_lock_after_owner_failure
    cleanup_rc=$?
    if [ "$cleanup_rc" -ne 0 ]; then
        echo "SEED_GATE_FAIL could not remove ownerless lock after setup failure: $LOCK_DIR" >&2
    fi
    return 3
}

remove_verified_stale_lock() {
    local expected_pid="$1" expected_token="$2" owner_rc remove_rc rmdir_rc
    load_lock_owner_record
    owner_rc=$?
    if [ "$owner_rc" -ne 0 ] \
        || [ "$LOCK_OWNER_PID" != "$expected_pid" ] \
        || [ "$LOCK_OWNER_TOKEN" != "$expected_token" ] \
        || ! lock_directory_has_only_owner; then
        echo "SEED_GATE_FAIL stale lock changed or contains unknown entries; refusing removal" >&2
        return 3
    fi
    rm -f -- "$LOCK_DIR/owner" 2>/dev/null
    remove_rc=$?
    if [ "$remove_rc" -ne 0 ] || [ -e "$LOCK_DIR/owner" ] || [ -L "$LOCK_DIR/owner" ]; then
        echo "SEED_GATE_FAIL could not remove the verified stale owner record" >&2
        return 3
    fi
    rmdir -- "$LOCK_DIR" 2>/dev/null
    rmdir_rc=$?
    if [ "$rmdir_rc" -ne 0 ] || [ -e "$LOCK_DIR" ] || [ -L "$LOCK_DIR" ]; then
        echo "SEED_GATE_FAIL stale lock directory is not empty; refusing recursive cleanup" >&2
        return 3
    fi
    return 0
}

# Exclusive lock with safe stale-owner reclamation (R9 P2).
acquire_lock_or_abort() {
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        SEED_LOCK_OWNED=0
        initialize_new_lock_or_abort
        return $?
    fi
    local owner_pid owner_token owner_rc owner_started state state_rc cleanup_rc
    load_lock_owner_record
    owner_rc=$?
    if [ "$owner_rc" -ne 0 ]; then
        echo "SEED_GATE_FAIL $LOCK_DIR has no exact $LOCK_OWNER_MAGIC owner record — refusing concurrent seed and leaving it untouched" >&2
        return 3
    fi
    owner_pid="$LOCK_OWNER_PID"
    owner_token="$LOCK_OWNER_TOKEN"
    owner_started="$LOCK_OWNER_STARTED"
    if kill -0 "$owner_pid" 2>/dev/null; then
        echo "SEED_GATE_FAIL another seed gate holds $LOCK_DIR (pid=$owner_pid started=$owner_started) — refusing concurrent seed (single-flight)" >&2
        return 3
    fi
    state=$(probe_bench_pid)
    state_rc=$?
    if [ "$state_rc" -ne 0 ]; then
        state="probe_failed probe_rc=$state_rc"
    fi
    if [ "$state" != "absent" ]; then
        echo "SEED_GATE_FAIL $LOCK_DIR owner pid=$owner_pid is dead but the device shows '$state' — a seed may still be running; refusing to reclaim (single-flight)" >&2
        return 3
    fi
    echo "SEED_GATE_RECLAIMED_STALE_LOCK $LOCK_DIR owner pid=$owner_pid dead, device quiescent — reclaiming" >&2
    remove_verified_stale_lock "$owner_pid" "$owner_token"
    cleanup_rc=$?
    if [ "$cleanup_rc" -ne 0 ] || [ -e "$LOCK_DIR" ] || [ -L "$LOCK_DIR" ]; then
        echo "SEED_GATE_FAIL could not remove stale lock $LOCK_DIR; refusing to continue" >&2
        return 3
    fi
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        SEED_LOCK_OWNED=0
        initialize_new_lock_or_abort
        return $?
    fi
    echo "SEED_GATE_FAIL lost the race re-acquiring $LOCK_DIR after reclaim" >&2
    return 3
}

release_lock_and_assert_absent() {
    local release_rc
    release_lock
    release_rc=$?
    if [ "$release_rc" -ne 0 ]; then
        echo "SEED_GATE_FAIL primary lock cleanup failed (rc=$release_rc): $LOCK_DIR" >&2
        return 2
    fi
    if [ -e "$LOCK_DIR" ] || [ -L "$LOCK_DIR" ]; then
        echo "SEED_GATE_FAIL primary lock cleanup reported success but the lock still exists: $LOCK_DIR" >&2
        return 2
    fi
    return 0
}

seed_gate_exit_cleanup() {
    local original_status="${1:-2}" cleanup_failed=0 release_rc
    if [ "$SEED_LAUNCH_ATTEMPTED" -eq 1 ] && [ "$SEED_POST_LAUNCH_QUIESCENT" -ne 1 ]; then
        if force_stop_and_assert_quiescent emergency-cleanup; then
            SEED_POST_LAUNCH_QUIESCENT=1
        else
            echo "SEED_GATE_FAIL emergency cleanup could not prove $BENCH_PACKAGE quiescent" >&2
            cleanup_failed=1
        fi
    fi
    release_lock
    release_rc=$?
    if [ "$release_rc" -ne 0 ] || [ -e "$LOCK_DIR" ] || [ -L "$LOCK_DIR" ]; then
        echo "SEED_GATE_FAIL failure-path lock cleanup did not complete: $LOCK_DIR" >&2
        cleanup_failed=1
    fi
    trap - EXIT
    if [ "$original_status" -eq 0 ] && [ "$cleanup_failed" -ne 0 ]; then
        exit 2
    fi
    exit "$original_status"
}

cleanup_failed_launch() {
    local phase="${1:-failure-cleanup}"
    if force_stop_and_assert_quiescent "$phase"; then
        SEED_POST_LAUNCH_QUIESCENT=1
        return 0
    fi
    echo "SEED_GATE_FAIL post-launch cleanup could not prove $BENCH_PACKAGE quiescent ($phase)" >&2
    return 2
}

# force-stop, then ASSERT the process is actually gone (tri-state). A surviving
# PID is a live unfenced writer domain and a failed probe is NOT absence —
# both abort. $1 = phase label (pre-seed | handoff).
force_stop_and_assert_quiescent() {
    local phase="${1:-pre-seed}" tries=0 state probe_rc
    dev shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1 || {
        echo "SEED_GATE_FAIL could not force-stop $BENCH_PACKAGE ($phase)" >&2
        return 2
    }
    while [ "$tries" -lt 10 ]; do
        state=$(probe_bench_pid)
        probe_rc=$?
        if [ "$probe_rc" -ne 0 ]; then
            state="probe_failed probe_rc=$probe_rc"
        fi
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
token_lines() {
    local parsed parser_rc
    parsed=$(grep -E -- " token=${SEED_TOKEN}( |\$)" <<<"$1")
    parser_rc=$?
    case "$parser_rc" in
        0) printf '%s\n' "$parsed"; return 0 ;;
        1) [ -z "$parsed" ] || return 2; return 0 ;;
        *) return "$parser_rc" ;;
    esac
}

count_marker() {
    local parsed parser_rc
    parsed=$(grep -c -F -- "$2" <<<"$1")
    parser_rc=$?
    case "$parser_rc" in
        0) ;;
        1) [ "$parsed" = 0 ] || return 2 ;;
        *) return "$parser_rc" ;;
    esac
    case "$parsed" in ""|*[!0-9]*|*$'\n'*) return 2 ;; esac
    printf '%s\n' "$parsed"
}

count_verified_digest_marker() {
    local parsed parser_rc
    parsed=$(grep -c -E -- "SEED_LOCAL_VERIFIED command=prepare_10a.* digest=${FIXTURE_DIGEST}( |\$)" <<<"$1")
    parser_rc=$?
    case "$parser_rc" in
        0) ;;
        1) [ "$parsed" = 0 ] || return 2 ;;
        *) return "$parser_rc" ;;
    esac
    case "$parsed" in ""|*[!0-9]*|*$'\n'*) return 2 ;; esac
    printf '%s\n' "$parsed"
}

# Exactly one internally consistent terminal result for THIS token.
await_seed_verdict() {
    local tries=0 logs logs_rc mine token_parser_rc failed failed_rc
    local verified verified_rc incomplete incomplete_rc digest_matches digest_rc
    while [ "$tries" -lt "$SEED_AWAIT_TRIES" ]; do
        logs=$(read_seed_logs)
        logs_rc=$?
        if [ "$logs_rc" -ne 0 ]; then
            echo "SEED_GATE_FAIL log producer failed (rc=$logs_rc); plausible stdout is not a verdict" >&2
            return 2
        fi
        mine=$(token_lines "$logs")
        token_parser_rc=$?
        if [ "$token_parser_rc" -ne 0 ]; then
            echo "SEED_GATE_FAIL token-line parser failed (rc=$token_parser_rc); plausible stdout is not a verdict" >&2
            return 2
        fi
        failed=$(count_marker "$mine" "SEED_FAILED command=prepare_10a")
        failed_rc=$?
        verified=$(count_marker "$mine" "SEED_LOCAL_VERIFIED command=prepare_10a")
        verified_rc=$?
        incomplete=$(count_marker "$mine" "SEED_CONTRACT_INCOMPLETE command=prepare_10a gap=7")
        incomplete_rc=$?
        if [ "$failed_rc" -ne 0 ] || [ "$verified_rc" -ne 0 ] || [ "$incomplete_rc" -ne 0 ]; then
            echo "SEED_GATE_FAIL terminal-marker parser failed (failed_rc=$failed_rc verified_rc=$verified_rc incomplete_rc=$incomplete_rc)" >&2
            return 2
        fi
        case "$failed:$verified:$incomplete" in
            *[!0-9:]*)
                echo "SEED_GATE_FAIL terminal-marker parser emitted malformed counts" >&2
                return 2 ;;
        esac
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
            digest_matches=$(count_verified_digest_marker "$mine")
            digest_rc=$?
            if [ "$digest_rc" -ne 0 ]; then
                echo "SEED_GATE_FAIL digest-binding parser failed (rc=$digest_rc)" >&2
                return 2
            fi
            if [ "$digest_matches" -eq 1 ]; then
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
remove_evidence_stage() {
    local stage="$1" remove_rc
    case "$stage" in ""|/|.) return 2 ;; esac
    rm -rf -- "$stage" 2>/dev/null
    remove_rc=$?
    if [ "$remove_rc" -ne 0 ] || [ -e "$stage" ] || [ -L "$stage" ]; then
        echo "SEED_GATE_FAIL could not remove incomplete evidence staging tree: $stage" >&2
        return 2
    fi
    if [ "$SEED_EVIDENCE_STAGE" = "$stage" ]; then
        SEED_EVIDENCE_STAGE=""
        SEED_EVIDENCE_PREPARED=0
    fi
    return 0
}

verify_staged_evidence_inventory() {
    local stage="$1" mirror_state="$2" inventory_python inventory_rc
    inventory_python="${SEED_INVENTORY_PYTHON:-}"
    if [ -z "$inventory_python" ]; then
        inventory_python=$(command -v python3 2>/dev/null) || inventory_python=''
    fi
    if [ -z "$inventory_python" ]; then
        echo "SEED_GATE_FAIL python3 is required to verify the staged evidence inventory" >&2
        return 1
    fi
    "$inventory_python" - "$stage" "$mirror_state" <<'PY'
import os
import stat
import sys

stage, mirror_state = sys.argv[1:]
mirror_present_states = {"identical", "divergent"}
mirror_empty_states = {"absent", "unavailable", "read-failed", "empty"}
if mirror_state not in mirror_present_states | mirror_empty_states:
    print(f"unknown mirror state: {mirror_state!r}", file=sys.stderr)
    raise SystemExit(1)

expected = {
    "vector-prefs": "dir",
    "app-private-mirror": "dir",
    "vector-prefs/spoof_config.xml": "file",
    "vector-prefs/spoof_config.xml.provenance": "file",
    "seed-published-transport.xml": "file",
    "seed-published-transport.xml.provenance": "file",
    "seed-published-payload.json": "file",
    "seed-published-payload.json.provenance": "file",
}
if mirror_state in mirror_present_states:
    expected.update({
        "app-private-mirror/spoof_config.xml": "file",
        "app-private-mirror/spoof_config.xml.provenance": "file",
    })

actual = {}
pending = [stage]
try:
    while pending:
        parent = pending.pop()
        with os.scandir(parent) as entries:
            for entry in entries:
                relative = os.path.relpath(entry.path, stage)
                metadata = entry.stat(follow_symlinks=False)
                if stat.S_ISLNK(metadata.st_mode):
                    raise ValueError(f"symlink not allowed: {relative}")
                if stat.S_ISDIR(metadata.st_mode):
                    actual[relative] = "dir"
                    pending.append(entry.path)
                elif stat.S_ISREG(metadata.st_mode):
                    if metadata.st_size <= 0:
                        raise ValueError(f"empty artifact not allowed: {relative}")
                    actual[relative] = "file"
                else:
                    raise ValueError(f"special filesystem entry not allowed: {relative}")
except (OSError, ValueError) as error:
    print(f"staged inventory scan failed: {error}", file=sys.stderr)
    raise SystemExit(1)

if actual != expected:
    missing = sorted(set(expected) - set(actual))
    extra = sorted(set(actual) - set(expected))
    mismatched = sorted(
        name for name in set(actual) & set(expected)
        if actual[name] != expected[name]
    )
    print(
        f"staged inventory mismatch: missing={missing} extra={extra} "
        f"type_mismatch={mismatched} mirror={mirror_state}",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
    inventory_rc=$?
    if [ "$inventory_rc" -ne 0 ]; then
        echo "SEED_GATE_FAIL staged evidence inventory is not exact for mirror=$mirror_state" >&2
        return 1
    fi
    return 0
}

# Phase 1: build and verify a hidden, non-consumable sibling stage while the
# primary seed lock is still held. No final directory or success marker exists.
prepare_evidence() {
    SEED_EVIDENCE_STAGE=""
    SEED_EVIDENCE_PREPARED=0
    [ -n "$EVIDENCE_DIR" ] || { SEED_EVIDENCE_PREPARED=1; return 0; }
    local evidence_parent evidence_parent_rc evidence_base evidence_base_rc
    local evidence_stage capture_dir transport payload transport_hash payload_hash capture_rc
    if ve_path_exists "$EVIDENCE_DIR"; then
        echo "SEED_GATE_FAIL evidence destination already exists and is not fresh: $EVIDENCE_DIR" >&2
        return 2
    fi
    evidence_parent=$(dirname -- "$EVIDENCE_DIR")
    evidence_parent_rc=$?
    evidence_base=$(basename -- "$EVIDENCE_DIR")
    evidence_base_rc=$?
    if [ "$evidence_parent_rc" -ne 0 ] || [ "$evidence_base_rc" -ne 0 ] \
        || [ -z "$evidence_parent" ] || [ -z "$evidence_base" ]; then
        echo "SEED_GATE_FAIL could not parse evidence destination coordinates" >&2
        return 2
    fi
    mkdir -p "$evidence_parent" 2>/dev/null || {
        echo "SEED_GATE_FAIL cannot create evidence parent: $evidence_parent" >&2
        return 2
    }
    evidence_stage=$(mktemp -d "$evidence_parent/.${evidence_base}.seed-evidence.XXXXXX" 2>/dev/null) || {
        echo "SEED_GATE_FAIL cannot create staging directory beside $EVIDENCE_DIR" >&2
        return 2
    }
    SEED_EVIDENCE_STAGE="$evidence_stage"
    capture_dir="$evidence_stage/vector-capture"
    ve_capture_evidence "$BENCH_PACKAGE" spoof_config.xml "$capture_dir" --defer-ok
    capture_rc=$?
    if [ "$capture_rc" -ne 0 ]; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL --evidence-dir requested but canonical Vector-live capture failed (fail-closed; never falls back to the app-private mirror)" >&2
        return 2
    fi
    if ! mv "$capture_dir/vector-prefs" "$evidence_stage/vector-prefs" \
        || ! mv "$capture_dir/app-private-mirror" "$evidence_stage/app-private-mirror" \
        || ! rmdir "$capture_dir"; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL could not assemble staged Vector evidence" >&2
        return 2
    fi
    # Back-compat names for existing evidence consumers: the canonical capture
    # is vector-prefs/spoof_config.xml (+ .provenance); these are byte copies.
    transport="$evidence_stage/seed-published-transport.xml"
    payload="$evidence_stage/seed-published-payload.json"
    if ! cp "$evidence_stage/vector-prefs/spoof_config.xml" "$transport" \
        || [ ! -s "$transport" ]; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL could not create verified compatibility transport copy" >&2
        return 2
    fi
    if ! extract_single_json_payload "$transport" "$payload" || [ ! -s "$payload" ]; then
        rm -f "$payload"
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL canonical transport did not yield exactly one valid JSON payload" >&2
        return 2
    fi
    if ! transport_hash=$(ve_hash_file "$transport") \
        || ! payload_hash=$(ve_hash_file "$payload") \
        || [ "$transport_hash" != "$VE_CAPTURE_LIVE_HASH" ]; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL derived evidence hash validation failed" >&2
        return 2
    fi
    if ! ve_write_provenance "$transport.provenance" \
        "$BENCH_PACKAGE" seed-published-transport.xml vector-live-copy \
        "$VE_CAPTURE_LIVE_PATH" 1/1 "$transport_hash" true "$VE_CAPTURE_LIVE_HASH" \
        || ! ve_write_provenance "$payload.provenance" \
        "$BENCH_PACKAGE" seed-published-payload.json derived-vector-live \
        "$VE_CAPTURE_LIVE_PATH" 1/1 "$payload_hash" true "$VE_CAPTURE_LIVE_HASH"; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL could not write complete derived evidence provenance" >&2
        return 2
    fi
    if ! verify_staged_evidence_inventory "$evidence_stage" "${VE_CAPTURE_MIRROR_STATE:-}"; then
        remove_evidence_stage "$evidence_stage" || true
        return 2
    fi
    SEED_EVIDENCE_PREPARED=1
    return 0
}

# Phase 2: called only after the primary lock is confirmed absent. Re-check the
# frozen whitelist, atomically publish it create-only, then and only then emit
# evidence success markers.
finalize_evidence() {
    [ "$SEED_EVIDENCE_PREPARED" -eq 1 ] || {
        echo "SEED_GATE_FAIL evidence finalize called without a verified stage" >&2
        return 2
    }
    [ -n "$EVIDENCE_DIR" ] || return 0
    local evidence_stage="$SEED_EVIDENCE_STAGE"
    if [ -z "$evidence_stage" ] || [ ! -d "$evidence_stage" ] || [ -L "$evidence_stage" ]; then
        echo "SEED_GATE_FAIL prepared evidence stage is unavailable" >&2
        return 2
    fi
    if [ -e "$LOCK_DIR" ] || [ -L "$LOCK_DIR" ]; then
        echo "SEED_GATE_FAIL refusing final evidence publication while primary lock exists" >&2
        return 2
    fi
    if ! verify_staged_evidence_inventory "$evidence_stage" "${VE_CAPTURE_MIRROR_STATE:-}"; then
        remove_evidence_stage "$evidence_stage" || true
        return 2
    fi
    if ve_path_exists "$EVIDENCE_DIR" \
        || ! ve_publish_directory_create_only "$evidence_stage" "$EVIDENCE_DIR"; then
        remove_evidence_stage "$evidence_stage" || true
        echo "SEED_GATE_FAIL evidence destination appeared or atomic publish failed: $EVIDENCE_DIR" >&2
        return 2
    fi
    SEED_EVIDENCE_STAGE=""
    SEED_EVIDENCE_PREPARED=0
    ve_emit_capture_success
    echo "SEED_GATE_EVIDENCE token=$SEED_TOKEN transport=$EVIDENCE_DIR/vector-prefs/spoof_config.xml payload=$EVIDENCE_DIR/seed-published-payload.json (zone=vector-live; provenance alongside)"
    return 0
}

extract_single_json_payload() {
    # Parse SharedPreferences XML, select one unambiguous direct json string,
    # let the XML parser decode entities exactly once, then validate that same
    # text as strict JSON before writing it create-only to staging.
    if [ "$#" -ne 2 ] || [ ! -s "$1" ] || ve_path_exists "$2"; then
        echo "SEED_GATE_FAIL strict JSON extractor needs one nonempty XML input and one fresh output" >&2
        return 1
    fi
    local json_python
    json_python="${SEED_JSON_PYTHON:-}"
    if [ -z "$json_python" ]; then
        json_python=$(command -v python3 2>/dev/null) || json_python=''
    fi
    if [ -z "$json_python" ]; then
        echo "SEED_GATE_FAIL python3 is required to parse canonical transport evidence" >&2
        return 1
    fi
    "$json_python" - "$1" "$2" <<'PY'
import json
import re
import sys
import xml.etree.ElementTree as ElementTree

xml_path, output_path = sys.argv[1:]


def reject_duplicate_object_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def reject_nonfinite(value):
    raise ValueError(f"non-finite JSON number: {value}")


try:
    xml_bytes = open(xml_path, "rb").read()
    if xml_bytes.startswith((
        b"\xef\xbb\xbf",       # UTF-8 BOM
        b"\xff\xfe", b"\xfe\xff",  # UTF-16/32 BOM prefixes
        b"\x00\x00\xfe\xff", b"\xff\xfe\x00\x00",
    )):
        raise ValueError("XML byte-order marks are not accepted; transport must be plain UTF-8")
    if b"\x00" in xml_bytes:
        raise ValueError("NUL bytes are not accepted; UTF-16/32 transports are forbidden")
    xml_text = xml_bytes.decode("utf-8", errors="strict")
    declaration = re.match(r"\A\s*<\?xml\s+([^?]*)\?>", xml_text, flags=re.IGNORECASE)
    if declaration is not None:
        encoding = re.search(
            r"\bencoding\s*=\s*(['\"])([^'\"]+)\1",
            declaration.group(1),
            flags=re.IGNORECASE,
        )
        if encoding is not None and encoding.group(2).casefold() != "utf-8":
            raise ValueError(f"XML declaration must specify UTF-8, got {encoding.group(2)!r}")
    upper_xml = xml_text.upper()
    if "<!DOCTYPE" in upper_xml or "<!ENTITY" in upper_xml:
        raise ValueError("DTD/entity declarations are not accepted")
    root = ElementTree.fromstring(xml_text)
    if root.tag != "map":
        raise ValueError(f"SharedPreferences root must be map, got {root.tag!r}")

    seen_names = set()
    json_nodes = []
    for child in list(root):
        name = child.attrib.get("name")
        if name is not None:
            if name in seen_names:
                raise ValueError(f"duplicate SharedPreferences key: {name}")
            seen_names.add(name)
        if child.tag == "string" and name == "json":
            json_nodes.append(child)
    if len(json_nodes) != 1:
        raise ValueError(f"expected exactly one direct string[name=json], got {len(json_nodes)}")
    node = json_nodes[0]
    if list(node):
        raise ValueError("string[name=json] must contain text only")
    payload = node.text
    if payload is None or payload == "":
        raise ValueError("string[name=json] is empty")
    decoded = json.loads(
        payload,
        object_pairs_hook=reject_duplicate_object_keys,
        parse_constant=reject_nonfinite,
    )
    if not isinstance(decoded, dict):
        raise ValueError("canonical payload must be a JSON object")
    with open(output_path, "x", encoding="utf-8", newline="\n") as output:
        output.write(payload)
        output.write("\n")
except (OSError, UnicodeError, ValueError, ElementTree.ParseError, json.JSONDecodeError) as error:
    print(f"strict canonical JSON extraction failed: {error}", file=sys.stderr)
    raise SystemExit(1)
PY
}

seed_gate_main() {
    local launch_rc verdict_rc token_rc release_rc
    SEED_LAUNCH_ATTEMPTED=0
    SEED_POST_LAUNCH_QUIESCENT=0
    SEED_EVIDENCE_STAGE=""
    SEED_EVIDENCE_PREPARED=0
    SEED_LOCK_OWNED=0
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
    if ! validate_fixture_base64 "$FIXTURE_B64"; then
        echo "SEED_GATE_FAIL fixture must be canonical standard base64 (one adb-shell-safe token)" >&2
        return 2
    fi
    if ! [[ "$FIXTURE_DIGEST" =~ ^[0-9a-f]{64}$ ]]; then
        echo "SEED_GATE_FAIL fixture digest must be exactly 64 lowercase hex characters" >&2
        return 2
    fi
    ve_validate_coordinate "$BENCH_PACKAGE" package || {
        echo "SEED_GATE_FAIL invalid package identity: $BENCH_PACKAGE" >&2
        return 2
    }
    command -v adb >/dev/null || { echo "SEED_GATE_FAIL adb not found" >&2; return 2; }
    SEED_TOKEN=$(new_seed_token)
    token_rc=$?
    if [ "$token_rc" -ne 0 ]; then
        echo "SEED_GATE_FAIL launch token producer failed (rc=$token_rc)" >&2
        return 2
    fi
    case "$SEED_TOKEN" in *[!A-Za-z0-9-]*|"") echo "SEED_GATE_FAIL malformed launch token" >&2; return 2 ;; esac

    acquire_lock_or_abort || return $?
    # From here the lock is held; every exit path releases it.
    trap 'seed_gate_exit_cleanup "$?"' EXIT

    force_stop_and_assert_quiescent pre-seed || return $?
    SEED_LAUNCH_ATTEMPTED=1
    launch_seed || {
        launch_rc=$?
        cleanup_failed_launch launch-failure-cleanup || true
        return "$launch_rc"
    }
    await_seed_verdict || {
        verdict_rc=$?
        cleanup_failed_launch verdict-failure-cleanup || true
        return "$verdict_rc"
    }
    force_stop_and_assert_quiescent handoff || {
        echo "SEED_GATE_FAIL seeded state could not be handed off quiescent (token $SEED_TOKEN)" >&2
        return 2
    }
    SEED_POST_LAUNCH_QUIESCENT=1
    prepare_evidence || return $?
    release_lock_and_assert_absent
    release_rc=$?
    if [ "$release_rc" -ne 0 ]; then
        if [ -n "$SEED_EVIDENCE_STAGE" ]; then
            echo "SEED_GATE_UNPUBLISHED_STAGE path=$SEED_EVIDENCE_STAGE reason=primary-lock-release-failed (hidden staging only; not consumable evidence)" >&2
        fi
        return "$release_rc"
    fi
    finalize_evidence || return $?
    trap - EXIT
    echo "SEED_GATE_PASS command=prepare_10a package=$BENCH_PACKAGE token=$SEED_TOKEN digest=$FIXTURE_DIGEST (exclusive lock + tri-state quiescent PID + token-bound single terminal verdict + quiescent handoff, owned by this transaction)"
    return 0
}

# Only run main when executed, not when sourced (the selftest sources the
# functions with fakes in place).
if [ "${SEED_GATE_SOURCE_ONLY:-0}" != "1" ]; then
    seed_gate_main "$@"
    exit $?
fi
