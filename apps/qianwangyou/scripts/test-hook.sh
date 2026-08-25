#!/usr/bin/env bash
#
# FakeGps public-API hook verification.
#
#   --current-profile   Read-only diagnostics for the user's effective profile.
#   --cellular-matrix   Strict, isolated acceptance transaction. Publishes
#                       debug-only exact and behavioral schema-v3 payloads,
#                       verifies every supported cellular field, and restores the
#                       database-backed payload.
#   --runtime-verify    Read-only validation of release probe/scheduler evidence already present
#                       in logcat. Never installs, clears logs, or changes the profile.
set -u

PKG="name.caiyao.fakegps"
ACT="$PKG/.ui.ComposeActivity"
ACCEPTANCE_ACT="$PKG/.probe.HookAcceptanceActivity"
PROVIDER="content://$PKG.data.AppInfoProvider/app"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
MATRIX_TOOL="$SCRIPT_DIR/cellular_acceptance_matrix.py"
VERDICT_TOOL="$SCRIPT_DIR/hook_verdict.py"
RUNTIME_VERIFY_TOOL="$SCRIPT_DIR/test_runtime_verify_flow.py"

MODE=${1:---current-profile}
case "$MODE" in
    --current-profile|--cellular-matrix|--runtime-verify) ;;
    *)
        echo "usage: $0 [--current-profile|--cellular-matrix|--runtime-verify]" >&2
        exit 2
        ;;
esac

command -v adb >/dev/null || { echo "HARNESS_ERROR adb not found" >&2; exit 2; }
PY=$(command -v python3 || command -v python) ||
    { echo "HARNESS_ERROR python3 not found" >&2; exit 2; }

TEMP_ROOT=""
TRANSACTION_ACTIVE=0
DB_BEFORE=""
PREFS_BEFORE=""
PREFS_BEFORE_FINGERPRINT=""
RESTORE_FAILED=0
DEVICE_API=""
FULL_RSCP_CONTROL_REPORT=""

device_count() {
    adb devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
}

root_shell() {
    adb shell "su -c '$1'"
}

snapshot_db() {
    root_shell "content query --uri $PROVIDER" 2>/dev/null |
        sed -n '/^Row:/p'
}

snapshot_prefs() {
    root_shell \
        "find /data/misc -type f -name spoof_config.xml -exec cat {} \\;" \
        2>/dev/null |
        sed -n '/<string name="json">/p' |
        LC_ALL=C sort
}

prefs_payload_fingerprint() {
    PREFS_XML=$1 "$PY" -c '
import hashlib, html, os, re, sys
values = {
    html.unescape(match.group(1))
    for line in os.environ["PREFS_XML"].splitlines()
    for match in [re.search(r"<string name=\"json\">(.*)</string>", line)]
    if match
}
if len(values) != 1:
    sys.exit(1)
payload = next(iter(values)).encode()
print("sha256:" + hashlib.sha256(payload).hexdigest()[:16])
'
}

prefs_payload_refresh_interval_ms() {
    PREFS_XML=$1 "$PY" -c '
import html, json, os, re, sys
values = {
    html.unescape(match.group(1))
    for line in os.environ["PREFS_XML"].splitlines()
    for match in [re.search(r"<string name=\"json\">(.*)</string>", line)]
    if match
}
if len(values) != 1:
    sys.exit(1)
payload = json.loads(next(iter(values)))
raw = payload.get("refreshIntervalSec")
if isinstance(raw, (int, float)) and not isinstance(raw, bool):
    try:
        seconds = int(raw)
    except (OverflowError, ValueError):
        seconds = 30
else:
    seconds = 30
print(max(5, min(60, seconds)) * 1000)
'
}

wait_for_profile_schema() {
    attempt=0
    while [ "$attempt" -lt 12 ]; do
        if snapshot_db | grep -F 'unavailable_fields=' >/dev/null; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    echo "HARNESS_ERROR Room migration did not expose unavailable_fields" >&2
    return 2
}

read_acceptance_logs() {
    adb logcat -d -v brief -s FakeGPSAcceptance:W '*:S' 2>/dev/null
}

has_state() {
    session=$1
    state=$2
    read_acceptance_logs |
        grep -F "\"sessionId\":\"$session\",\"state\":\"$state\"" >/dev/null
}

has_pending_recovery() {
    root_shell \
        "find /data/misc -type f -name hook_acceptance_recovery.xml -exec cat {} \\;" \
        2>/dev/null |
        grep -F 'name="pending" value="true"' >/dev/null
}

restore_database_payload() {
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb shell am start -W -n "$ACT" >/dev/null 2>&1 || {
        echo "HARNESS_ERROR failed to relaunch normal activity for restore" >&2
        return 1
    }

    attempt=0
    while [ "$attempt" -lt 12 ]; do
        current=$(snapshot_prefs)
        if [ -n "$PREFS_BEFORE" ] && [ "$current" = "$PREFS_BEFORE" ]; then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    echo "HARNESS_ERROR database-backed transport fingerprint was not restored" >&2
    return 1
}

cleanup_transaction() {
    rc=$?
    trap - EXIT INT TERM

    if [ "$TRANSACTION_ACTIVE" -eq 1 ]; then
        if ! restore_database_payload; then
            RESTORE_FAILED=1
        fi

        db_after=$(snapshot_db)
        if [ "$db_after" != "$DB_BEFORE" ]; then
            echo "HARNESS_ERROR profile database changed during acceptance" >&2
            RESTORE_FAILED=1
        else
            echo "VERIFIED restore.database unchanged"
        fi

        prefs_after=$(snapshot_prefs)
        if [ "$prefs_after" != "$PREFS_BEFORE" ]; then
            echo "HARNESS_ERROR transport does not match pre-test fingerprint" >&2
            RESTORE_FAILED=1
        else
            echo "VERIFIED restore.transport database-backed fingerprint restored"
        fi
    fi

    if [ -n "$TEMP_ROOT" ] &&
        case "$TEMP_ROOT" in
            "${TMPDIR:-/tmp}"/fakegps-acceptance.*) true ;;
            *) false ;;
        esac
    then
        rm -rf -- "$TEMP_ROOT"
    fi

    if [ "$RESTORE_FAILED" -ne 0 ] && [ "$rc" -eq 0 ]; then
        rc=1
    fi
    if [ "$rc" -eq 0 ] && [ "$RESTORE_FAILED" -eq 0 ] && [ "$TRANSACTION_ACTIVE" -eq 1 ]; then
        echo "ACCEPTANCE_PASS exact, fluctuation, and unavailable cellular scenarios verified; database and transport restored"
    fi
    exit "$rc"
}

signal_exit() {
    signal=$1
    echo "HARNESS_ERROR interrupted by $signal; restoring database-backed payload" >&2
    case "$signal" in
        INT) exit 130 ;;
        TERM) exit 143 ;;
        *) exit 1 ;;
    esac
}

preflight_device() {
    count=$(device_count)
    [ "$count" -eq 1 ] ||
        { echo "HARNESS_ERROR expected exactly one adb device, found $count" >&2; return 2; }
    adb get-state >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR adb device unavailable" >&2; return 2; }
    root_shell id 2>/dev/null | grep -q 'uid=0' ||
        { echo "HARNESS_ERROR rooted development device required" >&2; return 2; }

    DEVICE_API=$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
    case "$DEVICE_API" in
        ''|*[!0-9]*)
            echo "HARNESS_ERROR invalid Android API level: $DEVICE_API" >&2
            return 2
            ;;
    esac

    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
    adb shell wm dismiss-keyguard >/dev/null 2>&1
    adb shell input keyevent 82 >/dev/null 2>&1
    sleep 1
    wakefulness=$(adb shell dumpsys power 2>/dev/null |
        grep -Eo 'mWakefulness=[A-Za-z]+' | head -1 | cut -d= -f2)
    keyguard=$(adb shell dumpsys window policy 2>/dev/null |
        sed -n '/KeyguardStateMonitor/,+8p' |
        grep -Eo 'mIsShowing=(true|false)' | head -1 | cut -d= -f2)
    [ "$wakefulness" = "Awake" ] && [ "$keyguard" = "false" ] || {
        echo "HARNESS_ERROR device must be awake and unlocked: wake=$wakefulness keyguard=$keyguard" >&2
        return 2
    }
    echo "VERIFIED preflight.device api=$DEVICE_API awake unlocked rooted"
}

install_debug_apk_if_changed() {
    local_sha=$(shasum -a 256 "$APK" 2>/dev/null | awk '{print $1}')
    [ -n "$local_sha" ] ||
        { echo "HARNESS_ERROR could not fingerprint debug APK" >&2; return 2; }
    installed_path=$(adb shell pm path "$PKG" 2>/dev/null |
        sed -n 's/^package://p' | tr -d '\r' | head -1)
    installed_sha=""
    if [ -n "$installed_path" ]; then
        installed_sha=$(root_shell "sha256sum $installed_path" 2>/dev/null |
            awk '{print $1}' | tr -d '\r')
    fi
    if [ "$installed_sha" = "$local_sha" ]; then
        echo "[install] identical debug APK already installed"
        return 0
    fi

    echo "[install] $APK"
    # F-18: never swallow adb install output. A failed install (e.g. signature
    # mismatch against a foreign-signed package) must carry its full reason to
    # the caller, or the failure is silent and acceptance runs false-green on a
    # stale APK whose versionCode/versionName are unchanged.
    local install_out
    if ! install_out="$(adb install -r -t "$APK" 2>&1)"; then
        echo "HARNESS_ERROR debug APK install failed; full adb output follows:" >&2
        printf '%s\n' "$install_out" >&2
        case "$install_out" in
            *UPDATE_INCOMPATIBLE*)
                echo "HARNESS_HINT signatures do not match (F-18): installed package carries a foreign signer — see c5-evidence/f18-signer-divergence/" >&2
                ;;
        esac
        return 2
    fi

    # LSPosed may disable a module or clear its scope when PackageManager changes the APK path.
    # Rebooting cannot restore that user-owned policy, and writing LSPosed's private DB would fake
    # the test precondition. Stop here: the next run is byte-idempotent after the operator restores
    # the intended scope in LSPosed.
    echo "HARNESS_ACTION debug module updated; enable FakeGPS and restore its intended LSPosed scope, then rerun" >&2
    return 3
}

preflight_matrix() {
    [ "$DEVICE_API" -ge 33 ] ||
        {
            echo "HARNESS_ERROR Android API 33+ required for --cellular-matrix, found $DEVICE_API" >&2
            return 2
        }
    [ -f "$APK" ] ||
        { echo "HARNESS_ERROR debug APK missing; run ./gradlew assembleDebug" >&2; return 2; }
    [ -f "$MATRIX_TOOL" ] && [ -f "$VERDICT_TOOL" ] ||
        { echo "HARNESS_ERROR acceptance tools missing" >&2; return 2; }

    install_debug_apk_if_changed || return $?

    root_shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant ACCESS_FINE_LOCATION" >&2; return 2; }
    root_shell "pm grant $PKG android.permission.ACCESS_COARSE_LOCATION" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant ACCESS_COARSE_LOCATION" >&2; return 2; }
    root_shell "pm grant $PKG android.permission.READ_PHONE_STATE" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant READ_PHONE_STATE" >&2; return 2; }

    package_dump=$(adb shell dumpsys package "$PKG" 2>/dev/null)
    echo "$package_dump" | grep -F 'flags=[ DEBUGGABLE' >/dev/null &&
        echo "$package_dump" |
        grep -F 'name.caiyao.fakegps.permission.RUN_HOOK_ACCEPTANCE: prot=signature' \
            >/dev/null ||
        { echo "HARNESS_ERROR installed APK is not the debug acceptance build" >&2; return 2; }

    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    adb shell am start -W -n "$ACT" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR normal activity failed to start" >&2; return 2; }
    attempt=0
    while [ "$attempt" -lt 12 ]; do
        if adb logcat -d 2>/dev/null |
            grep -F 'FakeGPS: [DIAG] prefs loaded fields=' >/dev/null
        then
            wait_for_profile_schema || return $?
            echo "VERIFIED preflight.xposed self-hook loaded schema-v3 prefs"
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    echo "HARNESS_ERROR Xposed self-hook did not load schema-v3 prefs" >&2
    return 2
}

run_current_profile() {
    preflight_device || return $?
    db=$(snapshot_db)
    [ -n "$db" ] ||
        { echo "HARNESS_ERROR no saved profile (or provider unavailable)" >&2; return 2; }

    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    adb shell am start -W -n "$ACT" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR normal activity failed to start" >&2; return 2; }

    attempt=0
    probe=""
    while [ "$attempt" -lt 25 ]; do
        probe=$(adb logcat -d -v brief -s FakeGPSProbe:W '*:S' \
            2>/dev/null | tail -1)
        if [ -n "$probe" ]; then
            break
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    diag=$(adb logcat -d 2>/dev/null |
        grep -F 'FakeGPS: [DIAG] prefs loaded fields=' | tail -1)
    [ -n "$diag" ] ||
        { echo "HARNESS_ERROR no Xposed prefs-loaded diagnostic" >&2; return 1; }
    [ -n "$probe" ] ||
        { echo "HARNESS_ERROR no public-API probe diagnostic" >&2; return 1; }

    echo "[DB] $db"
    echo "[transport] $(snapshot_prefs)"
    echo "[hook] $diag"
    echo "[probe] $probe"
    echo "DIAGNOSTIC_ONLY current profile was not substituted with a distinct matrix"
}

run_scenario() {
    scenario=$1
    session="acceptance-$(date +%s)-$$-$scenario"
    payload=$(
        "$PY" "$MATRIX_TOOL" "$scenario" --output payload --session-id "$session"
    ) || return 2
    expected=$(
        "$PY" "$MATRIX_TOOL" "$scenario" --output expected --session-id "$session"
    ) || return 2
    encoded=$(PAYLOAD="$payload" "$PY" -c \
        'import base64,os; print(base64.urlsafe_b64encode(os.environ["PAYLOAD"].encode()).decode().rstrip("="))')
    remote_report="/data/user/0/$PKG/cache/hook-acceptance-$session.json"
    local_report="$TEMP_ROOT/$session.json"

    echo "[scenario] $scenario session=$session"
    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    root_shell \
        "am start -W -n $ACCEPTANCE_ACT --es acceptance_session_id $session --es acceptance_payload_base64 $encoded" \
        >/dev/null || {
        echo "HARNESS_ERROR acceptance activity failed to start for $session" >&2
        return 2
    }

    attempt=0
    while [ "$attempt" -lt 35 ]; do
        if has_state "$session" "probe_failed" ||
            has_state "$session" "restore_failed" ||
            has_state "$session" "aborted"
        then
            echo "HARNESS_ERROR acceptance activity failed for $session" >&2
            read_acceptance_logs >&2
            return 1
        fi
        if has_state "$session" "report_ready" &&
            has_state "$session" "restored"
        then
            break
        fi
        sleep 1
        attempt=$((attempt + 1))
    done

    if ! has_state "$session" "report_ready" ||
        ! has_state "$session" "restored"
    then
        echo "HARNESS_ERROR timed out waiting for report_ready + restored: $session" >&2
        read_acceptance_logs >&2
        return 1
    fi

    root_shell "cat $remote_report" >"$local_report" 2>/dev/null || {
        echo "HARNESS_ERROR could not read acceptance report for $session" >&2
        return 2
    }

    control_args=()
    if [ "$scenario" = "unavailable" ]; then
        [ -n "$FULL_RSCP_CONTROL_REPORT" ] && [ -f "$FULL_RSCP_CONTROL_REPORT" ] || {
            echo "HARNESS_ERROR unavailable scenario has no full-rscp negative control" >&2
            return 2
        }
        control_args+=(--control-report-file "$FULL_RSCP_CONTROL_REPORT")
        while IFS= read -r path; do
            control_args+=(--control-path "$path")
        done < <(PYTHONPATH="$REPO_ROOT" "$PY" -c \
            'from scripts.cellular_acceptance_matrix import unavailable_negative_control_paths; print("\n".join(unavailable_negative_control_paths()))' \
            2>/dev/null)
    fi

    restored_args=()
    if has_state "$session" "restored"; then
        restored_args+=(--restored)
    fi

    "$PY" "$VERDICT_TOOL" \
        --expected-json "$expected" \
        --report-file "$local_report" \
        --session-id "$session" \
        "${restored_args[@]}" \
        "${control_args[@]}" || return $?

    if [ "$scenario" = "full-rscp" ]; then
        FULL_RSCP_CONTROL_REPORT="$local_report"
    fi
}

verify_durable_recovery() {
    session="acceptance-recovery-$(date +%s)-$$"
    payload=$(
        "$PY" "$MATRIX_TOOL" full-rscp --output payload --session-id "$session"
    ) || return 2
    encoded=$(PAYLOAD="$payload" "$PY" -c \
        'import base64,os; print(base64.urlsafe_b64encode(os.environ["PAYLOAD"].encode()).decode().rstrip("="))')

    echo "[recovery] session=$session"
    adb shell am force-stop "$PKG" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    root_shell \
        "am start -W -n $ACCEPTANCE_ACT --es acceptance_session_id $session --es acceptance_payload_base64 $encoded --ez acceptance_hold_after_publish true" \
        >/dev/null || {
        echo "HARNESS_ERROR recovery probe activity failed to start" >&2
        return 2
    }

    attempt=0
    while [ "$attempt" -lt 15 ]; do
        if has_state "$session" "recovery_test_armed"; then
            break
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    has_state "$session" "recovery_test_armed" || {
        echo "HARNESS_ERROR recovery transaction was not armed" >&2
        read_acceptance_logs >&2
        return 1
    }
    has_pending_recovery || {
        echo "HARNESS_ERROR durable recovery record missing before overwrite" >&2
        return 1
    }
    during=$(snapshot_prefs)
    [ "$during" != "$PREFS_BEFORE" ] || {
        echo "HARNESS_ERROR recovery probe did not publish a distinct payload" >&2
        return 1
    }

    pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
    [ -n "$pid" ] || {
        echo "HARNESS_ERROR recovery probe process not found" >&2
        return 2
    }
    root_shell "kill -9 $pid" >/dev/null 2>&1 || {
        echo "HARNESS_ERROR could not SIGKILL recovery probe process" >&2
        return 2
    }
    sleep 1

    adb logcat -c >/dev/null 2>&1
    adb shell am start -W -n "$ACT" >/dev/null 2>&1 || {
        echo "HARNESS_ERROR normal activity failed to start recovery" >&2
        return 2
    }
    attempt=0
    while [ "$attempt" -lt 12 ]; do
        current=$(snapshot_prefs)
        if [ "$current" = "$PREFS_BEFORE" ] &&
            ! has_pending_recovery &&
            adb logcat -d -v brief -s FakeGPSAcceptanceRecovery:W '*:S' \
                2>/dev/null |
                grep -F "recovered_pending fp=$PREFS_BEFORE_FINGERPRINT" >/dev/null
        then
            echo "VERIFIED recovery.sigkill durable record restored pre-test payload"
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done

    echo "HARNESS_ERROR durable recovery did not complete after SIGKILL" >&2
    adb logcat -d -v brief -s FakeGPSAcceptanceRecovery:V '*:S' >&2
    return 1
}

run_cellular_matrix() {
    preflight_device || return $?
    preflight_matrix || return $?

    DB_BEFORE=$(snapshot_db)
    [ -n "$DB_BEFORE" ] ||
        { echo "HARNESS_ERROR no saved profile to protect" >&2; return 2; }
    PREFS_BEFORE=$(snapshot_prefs)
    [ -n "$PREFS_BEFORE" ] ||
        { echo "HARNESS_ERROR schema-v3 safe-zone prefs not found" >&2; return 2; }
    PREFS_BEFORE_FINGERPRINT=$(prefs_payload_fingerprint "$PREFS_BEFORE") ||
        { echo "HARNESS_ERROR could not fingerprint protected transport payload" >&2; return 2; }

    TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/fakegps-acceptance.XXXXXX") ||
        { echo "HARNESS_ERROR could not create temporary directory" >&2; return 2; }
    TRANSACTION_ACTIVE=1
    trap cleanup_transaction EXIT
    trap 'signal_exit INT' INT
    trap 'signal_exit TERM' TERM

    verify_durable_recovery || return $?
    run_scenario full-rscp || return $?
    run_scenario full-rssi || return $?
    run_scenario fluctuation-enabled || return $?
    run_scenario unavailable || return $?
}

run_runtime_verify() {
    count=$(device_count)
    [ "$count" -eq 1 ] || {
        echo "HARNESS_ERROR expected exactly one adb device, found $count" >&2
        return 2
    }
    [ -f "$RUNTIME_VERIFY_TOOL" ] || {
        echo "HARNESS_ERROR runtime verification tool missing" >&2
        return 2
    }
    prefs=$(snapshot_prefs)
    [ -n "$prefs" ] || {
        echo "HARNESS_ERROR schema-v3 safe-zone prefs not found" >&2
        return 2
    }
    fingerprint=$(prefs_payload_fingerprint "$prefs") || {
        echo "HARNESS_ERROR could not fingerprint current transport payload" >&2
        return 2
    }
    interval_ms=$(prefs_payload_refresh_interval_ms "$prefs") || {
        echo "HARNESS_ERROR could not read current transport refresh interval" >&2
        return 2
    }
    "$PY" "$RUNTIME_VERIFY_TOOL" \
        --from-adb \
        --require-probe \
        --require-scheduler \
        --expected-interval-ms "$interval_ms" \
        --expected-fingerprint "$fingerprint"
}

echo "════════════════════════════════════════════════════════════════"
echo " FakeGps hook verification: $MODE"
echo "════════════════════════════════════════════════════════════════"

case "$MODE" in
    --current-profile) run_current_profile ;;
    --cellular-matrix) run_cellular_matrix ;;
    --runtime-verify) run_runtime_verify ;;
esac
