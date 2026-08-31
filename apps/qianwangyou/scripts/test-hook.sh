#!/usr/bin/env bash
#
# FakeGps public-API hook verification.
#
#   --current-profile   Read-only diagnostics for the user's effective profile.
#   --acceptance-readiness
#                       Read-only proof that the runner addresses the REAL
#                       .bench acceptance component WITHOUT entering the §G
#                       transaction: unprivileged start must be denied by the
#                       bench signature permission (gate proof), then a
#                       payload-less privileged start must fail fast with the
#                       probe's own missing-extra abort (identity proof).
#                       Never installs, publishes, or writes business state.
#   --cellular-matrix   Strict, isolated acceptance transaction. Publishes
#                       debug-only exact and behavioral current-schema (v4) payloads,
#                       verifies every supported cellular field, and restores the
#                       database-backed payload.
#   --runtime-verify    Read-only validation of release probe/scheduler evidence already present
#                       in logcat. Never installs, clears logs, or changes the profile.
set -u

# Package identity (G2 §3.3-3): the debug APK built here installs as the
# .bench variant (build.gradle debug applicationIdSuffix ".bench"), and the
# acceptance Activity, its signature permission, and the AppInfoProvider
# authority exist ONLY inside that bench debug install (src/debug manifest
# uses ${applicationId} placeholders). Every device-side coordinate below
# therefore addresses the bench package, with component names spelled as
# EXPLICIT namespace FQCNs: a `pkg/.ShortName` shorthand resolves relative
# to the applicationId (…fakegps.bench.probe.*) and points at classes that
# do not exist — the class namespace stays name.caiyao.fakegps.* (same
# convention as mock_provider_acceptance.sh).
# Guarded device-free by scripts/selftest-test-hook-package-identity.sh.
#
# Known residual (needs on-device path verification before pinning):
# snapshot_prefs()/has_pending_recovery() scan /data/misc for
# spoof_config.xml without a package filter; with BOTH production and bench
# installed the fingerprint helpers fail loud (values != 1), never silent.
BENCH_PACKAGE="name.caiyao.fakegps.bench"
ACT="$BENCH_PACKAGE/name.caiyao.fakegps.ui.ComposeActivity"
ACCEPTANCE_ACT="$BENCH_PACKAGE/name.caiyao.fakegps.probe.HookAcceptanceActivity"
PROVIDER="content://$BENCH_PACKAGE.data.AppInfoProvider/app"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
MATRIX_TOOL="$SCRIPT_DIR/cellular_acceptance_matrix.py"
VERDICT_TOOL="$SCRIPT_DIR/hook_verdict.py"
RUNTIME_VERIFY_TOOL="$SCRIPT_DIR/test_runtime_verify_flow.py"

MODE=${1:---current-profile}
case "$MODE" in
    --current-profile|--acceptance-readiness|--cellular-matrix|--runtime-verify) ;;
    *)
        echo "usage: $0 [--current-profile|--acceptance-readiness|--cellular-matrix|--runtime-verify]" >&2
        exit 2
        ;;
esac

command -v adb >/dev/null || { echo "HARNESS_ERROR adb not found" >&2; exit 2; }
PY=$(command -v python3 || command -v python) ||
    { echo "HARNESS_ERROR python3 not found" >&2; exit 2; }

TEMP_ROOT=""
# G2 §5.G evidence carrier (PR #62 R3 P1-4): raw acceptance reports are
# preserved OUTSIDE the transaction's temp area, in a caller-owned directory
# this script never deletes — success or failure. Override with
# HOOK_EVIDENCE_DIR; default is a timestamped directory under the repo's
# c5-evidence/ tree.
EVIDENCE_DIR=""
INSTALLED_APK_SHA=""
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
        "find /data/misc -type f -name hook_acceptance_recovery.xml -exec cat {} \;" \
        2>/dev/null |
        grep -F 'name="pending" value="true"' >/dev/null
}

# Poll for the payload-less fail-fast abort signature: the real probe's
# onCreate requireNotNull(EXTRA_SESSION_ID) throws before any transaction
# step, logging sessionId "unparsed" state "aborted" with the missing-extra
# error under the FakeGPSAcceptance tag.
wait_for_readiness_abort() {
    attempt=0
    while [ "$attempt" -lt 10 ]; do
        logs=$(read_acceptance_logs)
        if printf '%s\n' "$logs" |
            grep -F '"sessionId":"unparsed","state":"aborted"' >/dev/null &&
            printf '%s\n' "$logs" |
                grep -F 'missing acceptance_session_id' >/dev/null
        then
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    return 1
}

restore_database_payload() {
    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1 || true
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
    # §4.2-1 requires `pm path` to return EXACTLY one base.apk. The previous
    # `head -1` silently discarded extra lines, so a split install
    # (base.apk + split_config.*.apk) could pass the byte check on the base
    # while the device actually runs bytes the single-APK build never produced
    # (PR #62 review P1-5). Capture ALL lines and assert cardinality; only the
    # empty case (not installed) may fall through to the install branch.
    installed_paths=$(adb shell pm path "$BENCH_PACKAGE" 2>/dev/null |
        sed -n 's/^package://p' | tr -d '\r')
    path_count=$(printf '%s' "$installed_paths" | grep -c .)
    if [ "$path_count" -gt 1 ]; then
        echo "HARNESS_ERROR pm path returned $path_count APK entries for $BENCH_PACKAGE — §4.2 requires exactly one base.apk (split install is not the built artifact). Paths:" >&2
        printf '%s\n' "$installed_paths" >&2
        return 2
    fi
    installed_sha=""
    if [ "$path_count" -eq 1 ]; then
        installed_path=$installed_paths
        case "$installed_path" in
            */base.apk) ;;
            *)
                echo "HARNESS_ERROR sole installed path is not a base.apk: $installed_path" >&2
                return 2
                ;;
        esac
        installed_sha=$(root_shell "sha256sum $installed_path" 2>/dev/null |
            awk '{print $1}' | tr -d '\r')
    fi
    if [ "$installed_sha" = "$local_sha" ]; then
        echo "[install] identical debug APK already installed"
        # §4.2 installed identity, claimed narrowly: this line asserts BYTE
        # IDENTITY of the sole on-device base.apk against the built debug APK
        # (path cardinality proven above). Signer/applicationId legs stay with
        # their own §4.2 steps — this echo does not claim them.
        echo "VERIFIED install.apk sha256=$installed_sha (sole base.apk bytes == built debug APK)"
        # §5.G (P1-4): exported for the per-report EVIDENCE binding lines.
        INSTALLED_APK_SHA="$installed_sha"
        return 0
    fi

    echo "[install] $APK"
    # F-18: never swallow adb install output. A failed install (e.g. signature
    # mismatch against a foreign-signed package) must carry its full reason to
    # the caller, or the failure is silent and acceptance runs false-green on a
    # stale APK whose versionCode/versionName are unchanged.
    # R1: process status alone is not install proof either — the real adb exit
    # status is captured before branching (an `if !` inversion would report 0),
    # and exit 0 WITHOUT a Success marker is a refusal, not an update.
    local install_out install_rc
    install_out="$(adb install -r -t "$APK" 2>&1)"
    install_rc=$?
    if [ "$install_rc" -ne 0 ]; then
        echo "HARNESS_ERROR debug APK install failed (adb exit $install_rc); full adb output follows:" >&2
        printf '%s\n' "$install_out" >&2
        case "$install_out" in
            *UPDATE_INCOMPATIBLE*)
                echo "HARNESS_HINT signatures do not match (F-18): installed package carries a foreign signer — see c5-evidence/f18-signer-divergence/" >&2
                ;;
        esac
        return 2
    fi
    if ! grep -q "Success" <<<"$install_out"; then
        echo "HARNESS_ERROR debug APK install reported no Success (adb exit 0); full adb output follows:" >&2
        printf '%s\n' "$install_out" >&2
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

    root_shell "pm grant $BENCH_PACKAGE android.permission.ACCESS_FINE_LOCATION" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant ACCESS_FINE_LOCATION" >&2; return 2; }
    root_shell "pm grant $BENCH_PACKAGE android.permission.ACCESS_COARSE_LOCATION" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant ACCESS_COARSE_LOCATION" >&2; return 2; }
    root_shell "pm grant $BENCH_PACKAGE android.permission.READ_PHONE_STATE" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR could not grant READ_PHONE_STATE" >&2; return 2; }

    package_dump=$(adb shell dumpsys package "$BENCH_PACKAGE" 2>/dev/null)
    # The acceptance permission is declared as ${applicationId}.permission.
    # RUN_HOOK_ACCEPTANCE in the src/debug manifest, so inside the bench
    # install it expands to the BENCH package's namespace. Spelling it via
    # $BENCH_PACKAGE keeps this grep tied to the identity being proven.
    echo "$package_dump" | grep -F 'flags=[ DEBUGGABLE' >/dev/null &&
        echo "$package_dump" |
        grep -F "$BENCH_PACKAGE.permission.RUN_HOOK_ACCEPTANCE: prot=signature" \
            >/dev/null ||
        { echo "HARNESS_ERROR installed APK is not the debug acceptance build" >&2; return 2; }

    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    adb shell am start -W -n "$ACT" >/dev/null 2>&1 ||
        { echo "HARNESS_ERROR normal activity failed to start" >&2; return 2; }
    attempt=0
    while [ "$attempt" -lt 12 ]; do
        if adb logcat -d 2>/dev/null |
            grep -F 'FakeGPS: [DIAG] prefs loaded fields=' >/dev/null
        then
            wait_for_profile_schema || return $?
            echo "VERIFIED preflight.xposed self-hook loaded schema-v4 prefs"
            return 0
        fi
        sleep 1
        attempt=$((attempt + 1))
    done
    echo "HARNESS_ERROR Xposed self-hook did not load schema-v4 prefs" >&2
    return 2
}

run_current_profile() {
    preflight_device || return $?
    db=$(snapshot_db)
    [ -n "$db" ] ||
        { echo "HARNESS_ERROR no saved profile (or provider unavailable)" >&2; return 2; }

    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
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

# G2 §3.3-3 readiness entry: prove, OUTSIDE any §G transaction, that the
# runner starts the real .bench acceptance component. Two-sided, read-only:
#   Stage 1 (gate, unprivileged): the resolved component must be DENIED by
#     the bench signature permission. A start that SUCCEEDS means the
#     component is not the signature-gated probe (imposter / wrong package /
#     gate dropped) — component resolution alone proves nothing.
#   Stage 2 (identity, privileged, NO payload extras): the real probe's
#     onCreate fails fast on the missing session extra and aborts BEFORE any
#     transaction step (no recovery prepare, no publish). A "published" state
#     log means a transaction was entered and this mode FAILS.
# Deliberately does NOT reuse snapshot_prefs()/has_pending_recovery():
# their unfiltered /data/misc scans loud-fail on two-package devices
# (production + .bench coinstalled).
# Guarded device-free by scripts/selftest-test-hook-acceptance-readiness.sh.
run_acceptance_readiness() {
    # Bounded-excerpt pattern sets, declared ONCE and emitted verbatim in the
    # counts lines: the printed patterns= string is byte-for-byte the grep
    # regex, so an auditor can re-run it against the raw text (gpt55 review
    # finding: an underscore-mangled paraphrase is not re-runnable).
    local gate_excerpt_re='SecurityException|Permission Denial|RUN_HOOK_ACCEPTANCE'
    local start_excerpt_re='^(Starting: Intent|Status:|LaunchState:|ThisTime:|TotalTime:|WaitTime:|Complete|Error:)'
    preflight_device || return $?
    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1

    # Evidence capture (Terra validity verdict: "no executed command lines
    # are frozen") — the executed command lines themselves enter the evidence
    # stream verbatim; they are constructed from shipped constants, so they
    # carry no device-private content.
    echo "READINESS_CMD stage1 adb shell am start -W -n $ACCEPTANCE_ACT"
    deny_out=$(adb shell am start -W -n "$ACCEPTANCE_ACT" 2>&1)
    if printf '%s\n' "$deny_out" | grep -Eq 'Status:[[:space:]]*ok'; then
        echo "HARNESS_ERROR unprivileged start succeeded; component is not signature-gated: $ACCEPTANCE_ACT" >&2
        return 2
    fi
    printf '%s\n' "$deny_out" | grep -Eq 'SecurityException|Permission Denial' || {
        echo "HARNESS_ERROR unprivileged start failed for a non-permission reason" >&2
        printf '%s\n' "$deny_out" >&2
        return 2
    }
    printf '%s\n' "$deny_out" |
        grep -F "$BENCH_PACKAGE.permission.RUN_HOOK_ACCEPTANCE" >/dev/null || {
        echo "HARNESS_ERROR denial does not name the bench acceptance permission" >&2
        printf '%s\n' "$deny_out" >&2
        return 2
    }
    # Evidence parity with Stage 2 (S2-B finding): the gate assertions above
    # run on the FULL deny_out, but the frozen evidence directory must also
    # carry the raw denial bytes — a reviewer must confirm from the evidence
    # itself that the denial named the bench permission, not from the exit
    # code. Bounded excerpt: ONLY lines matching gate_excerpt_re (declared
    # above, emitted verbatim in the counts line) enter the evidence stream,
    # so device-private noise on unmatched lines never does; the counts line
    # (matched/total) lets an auditor re-run the same patterns on the raw
    # text and verify no matched line was cropped.
    deny_total=$(printf '%s\n' "$deny_out" | wc -l | tr -d ' ')
    deny_excerpt=$(printf '%s\n' "$deny_out" |
        grep -E "$gate_excerpt_re")
    deny_matched=$(printf '%s\n' "$deny_excerpt" | grep -c .)
    echo "READINESS_GATE_EXCERPT lines=$deny_matched/$deny_total patterns=$gate_excerpt_re"
    printf '%s\n' "$deny_excerpt" | sed 's/^/  gate| /'
    echo "VERIFIED acceptance.gate signature permission denies unprivileged start"

    # Frozen as the REAL host-side command shape — the root_shell wrapper
    # executes `adb shell "su -c '$1'"`, so the evidence line carries the adb
    # wrapper and the su -c quoting verbatim (gpt55 R2: a paraphrase without
    # the wrapper/quoting is not the executed command).
    echo "READINESS_CMD stage2 adb shell \"su -c 'am start -W -n $ACCEPTANCE_ACT'\""
    start_out=$(root_shell "am start -W -n $ACCEPTANCE_ACT")
    printf '%s\n' "$start_out" | grep -Eq 'Status:[[:space:]]*ok' || {
        echo "HARNESS_ERROR payload-less acceptance start failed" >&2
        printf '%s\n' "$start_out" >&2
        return 2
    }
    # Stage 2 evidence parity (Terra verdict scope): same bounded-excerpt
    # contract as Stage 1 — only lines matching start_excerpt_re (declared
    # above, emitted verbatim in the counts line) enter the evidence stream.
    # Bare prefixes like "Warning:" are deliberately NOT in the set: a prefix
    # alone cannot distinguish am-generated markers from device noise that
    # happens to carry the same word (E4).
    start_total=$(printf '%s\n' "$start_out" | wc -l | tr -d ' ')
    start_excerpt=$(printf '%s\n' "$start_out" |
        grep -E "$start_excerpt_re")
    start_matched=$(printf '%s\n' "$start_excerpt" | grep -c .)
    echo "READINESS_START_EXCERPT lines=$start_matched/$start_total patterns=$start_excerpt_re"
    printf '%s\n' "$start_excerpt" | sed 's/^/  start2| /'
    wait_for_readiness_abort || {
        echo "HARNESS_ERROR no fail-fast abort signature from $ACCEPTANCE_ACT (not the acceptance probe?)" >&2
        read_acceptance_logs >&2
        return 1
    }
    if read_acceptance_logs | grep -F '"state":"published"' >/dev/null; then
        echo "HARNESS_ERROR acceptance transaction was entered from a readiness start" >&2
        return 2
    fi
    echo "VERIFIED acceptance.component resolved and fail-fast aborted without payload"
    echo "READINESS_PASS $ACCEPTANCE_ACT is the signature-gated bench acceptance component; no transaction entered"
}

# G2 §5.G evidence carrier (PR #62 R3 P1-4). Previously every raw report
# lived only in TEMP_ROOT and cleanup_transaction deleted it — the run could
# not bind session id + result file + installed APK SHA (acceptance package
# §5.G), and a failure destroyed its own only raw carrier. Every report is
# now copied into EVIDENCE_DIR (never deleted by this script) and bound on
# stdout as one EVIDENCE line. Guarded device-free by
# scripts/selftest-test-hook-evidence-carrier.sh.
preserve_report() { # session local_report
    session=$1
    local_report=$2
    # §5.G requires the report BOUND to the installed APK SHA. An empty or
    # non-64-hex apk sha means the install-identity step never ran (or its
    # export was dropped) — the binding would be a lie ("apk_sha256=unknown").
    # Fail closed BEFORE writing any copy we could not bind (R4 P2).
    if ! printf '%s' "$INSTALLED_APK_SHA" | grep -Eq '^[0-9a-fA-F]{64}$'; then
        echo "HARNESS_ERROR installed APK SHA not bound (got '${INSTALLED_APK_SHA:-<empty>}'); §5.G requires a 64-hex installed sha before preserving evidence" >&2
        return 2
    fi
    [ -n "$EVIDENCE_DIR" ] && [ -d "$EVIDENCE_DIR" ] || {
        echo "HARNESS_ERROR evidence directory missing; cannot preserve raw report for $session" >&2
        return 2
    }
    preserved="$EVIDENCE_DIR/$session.json"
    cp -- "$local_report" "$preserved" 2>/dev/null || {
        echo "HARNESS_ERROR could not preserve raw report for $session into $EVIDENCE_DIR" >&2
        return 2
    }
    report_sha=$(shasum -a 256 "$preserved" 2>/dev/null | awk '{print $1}')
    [ -n "$report_sha" ] || {
        echo "HARNESS_ERROR could not fingerprint preserved report for $session" >&2
        return 2
    }
    echo "EVIDENCE session=$session report=$preserved report_sha256=$report_sha apk_sha256=$INSTALLED_APK_SHA"
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
    remote_report="/data/user/0/$BENCH_PACKAGE/cache/hook-acceptance-$session.json"
    local_report="$TEMP_ROOT/$session.json"

    echo "[scenario] $scenario session=$session"
    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
    adb logcat -c >/dev/null 2>&1
    root_shell \
        "am start -W -n $ACCEPTANCE_ACT --es acceptance_session_id $session --es acceptance_payload_base64 $encoded" \
        >/dev/null || {
        echo "HARNESS_ERROR acceptance activity failed to start for $session" >&2
        return 2
    }

    # §5.G evidence carrier (PR #62 R4 P1-4): the acceptance Activity logs
    # report_ready BEFORE restoreAndFinish can log restore_failed
    # (HookAcceptanceActivity onCreate writes+logs the report, then restore
    # runs). So a restore_failed / restore-timeout run can have a raw report
    # ON DEVICE while the previous ordering returned the primary failure
    # FIRST and lost the only host carrier. Fix: whenever report_ready is
    # observed, fetch + preserve the bytes BEFORE returning ANY failure.
    #
    # try_capture_report: idempotent — pulls the on-device report and
    # preserves it once. Safe to call on both the failure and success paths.
    report_captured=0
    try_capture_report() {
        [ "$report_captured" -eq 1 ] && return 0
        has_state "$session" "report_ready" || return 0
        root_shell "cat $remote_report" >"$local_report" 2>/dev/null || {
            echo "HARNESS_ERROR report_ready seen but could not read on-device report for $session" >&2
            return 2
        }
        preserve_report "$session" "$local_report" || return $?
        report_captured=1
        return 0
    }

    attempt=0
    while [ "$attempt" -lt 35 ]; do
        if has_state "$session" "probe_failed" ||
            has_state "$session" "restore_failed" ||
            has_state "$session" "aborted"
        then
            # Preserve any report_ready bytes BEFORE surfacing the failure.
            try_capture_report || return $?
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
        # A report-ready/restore-timeout run may still have a device report.
        try_capture_report || return $?
        echo "HARNESS_ERROR timed out waiting for report_ready + restored: $session" >&2
        read_acceptance_logs >&2
        return 1
    fi

    # Success path: capture is idempotent (no-op if the failure path already ran).
    try_capture_report || return $?

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
    adb shell am force-stop "$BENCH_PACKAGE" >/dev/null 2>&1
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

    pid=$(adb shell pidof "$BENCH_PACKAGE" 2>/dev/null | tr -d '\r' | awk '{print $1}')
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
        { echo "HARNESS_ERROR schema-v4 safe-zone prefs not found" >&2; return 2; }
    PREFS_BEFORE_FINGERPRINT=$(prefs_payload_fingerprint "$PREFS_BEFORE") ||
        { echo "HARNESS_ERROR could not fingerprint protected transport payload" >&2; return 2; }

    TEMP_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/fakegps-acceptance.XXXXXX") ||
        { echo "HARNESS_ERROR could not create temporary directory" >&2; return 2; }
    # §5.G (P1-4): the preserved evidence directory outlives this run — only
    # TEMP_ROOT (the working area) is cleaned up. Fail loud if it cannot exist:
    # a run without a durable carrier must not proceed to scenarios.
    EVIDENCE_DIR="${HOOK_EVIDENCE_DIR:-$REPO_ROOT/c5-evidence/hook-acceptance-$(date -u +%Y%m%dT%H%M%SZ)}"
    mkdir -p -- "$EVIDENCE_DIR" 2>/dev/null && [ -w "$EVIDENCE_DIR" ] || {
        echo "HARNESS_ERROR could not create writable evidence directory: $EVIDENCE_DIR" >&2
        return 2
    }
    echo "[evidence] preserved directory: $EVIDENCE_DIR (never deleted by this script)"
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
        echo "HARNESS_ERROR schema-v4 safe-zone prefs not found" >&2
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
    --acceptance-readiness) run_acceptance_readiness ;;
    --cellular-matrix) run_cellular_matrix ;;
    --runtime-verify) run_runtime_verify ;;
esac
