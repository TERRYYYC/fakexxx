#!/usr/bin/env bash
# provision-device.sh — ONE idempotent command: take an Android device from
# "APKs installed" to "fakexxx lane ready" (QWY provider + cellrebel-auto engine).
#
# Encodes the ten-step install chain of the device-ops skill §2 (新手机全套配置)
# as a re-runnable command. ORDER IS LOAD-BEARING: accessibility (step 9) must be
# the LAST device-touching step — installs/grants can silently clear it (OEM
# behavior), and the engine dies if its a11y service recycles.
#
# Per-step discipline: every step first SELF-CHECKS completion on the device and
# reports already-done work as SKIP with on-device evidence, so any re-run
# (after a failure, or after the mid-chain reboot Vector demands) executes only
# the missing remainder. Never uninstall; install -r only.
#
# Deliberately OUT of scope (stays manual, listed in the report leftovers):
#   - APK installation of CellRebel / QWY / Auto (use scripts/install_apk_verified.sh;
#     preflight hard-fails if any lane package is missing);
#   - QWY profile import + FAB anchor (skill §2.6) and Auto provider approval (§2.8);
#   - the publish-probe System Mock toggle (input tap is unreliable — a human or
#     the upper layer toggles it once; this script only prompts and then VERIFIES
#     via logcat ConfigPrefsSync published=true).
#
# Usage:
#   scripts/provision-device.sh --serial SERIAL --lane LANE
#       [--plan-csv FILE] [--profile-csv FILE]
#       [--vector-zip ZIP] [--vector-zip-sha256 HEX]
#       [--dry-run] [--step NAME] [--help]
#
# Required:
#   --serial SERIAL        adb serial (adb devices -l); all adb calls use -s SERIAL
#   --lane LANE            glmbench | bench | release | codexbench
# Optional:
#   --plan-csv FILE        test-plan CSV (longitude,latitude,priority,required_successes);
#                          pushed to /sdcard/Download/location_test_plan.csv (renamed)
#   --profile-csv FILE     QWY profile CSV (addname,latitude,longitude — QWY-only
#                          coordinates); pushed under its own basename
#   --vector-zip ZIP       Vector (Xposed) module zip
#                          (default /tmp/Vector-v2.2-3110-Release.zip; recover with:
#                           gh release download canary-3110 --repo JingMatrix/Vector
#                           --pattern "Vector-v2.2-3110-Release.zip")
#   --vector-zip-sha256 H  expected sha256 of the zip (fails the step on mismatch;
#                          the computed hash is always recorded as evidence)
#   --dry-run              print the full cold-path plan (routing + commands);
#                          executes ZERO adb commands
#   --step NAME            run a single step instead of the whole chain:
#                          mock_location permissions push-csv power-whitelist
#                          vector-module manager-apk vector-scope publish-probe
#                          accessibility report
#
# Steps (1..10, each self-checks and SKIPs when already done):
#   1 mock_location    appops set <QWY lane pkg> android:mock_location allow
#   2 permissions      pm grant FINE/COARSE location + POST_NOTIFICATIONS for both
#                      apps; grant+verify retried <=3x with 2s gaps (HyperOS
#                      probabilistic revoke); undeclared perms are noted and skipped
#   3 push-csv         push plan/profile CSVs to /sdcard/Download/ (sha256-verified
#                      when the device supports sha256sum, size fallback)
#   4 power-whitelist  svc power stayon usb + dumpsys deviceidle whitelist +QWY +Auto
#   5 vector-module    sha256-verify zip -> push -> su magisk --install-module
#                      (REBOOT required to activate; re-run this script afterwards)
#   6 manager-apk      chown 2000:2000 + chmod 644 the module's manager.apk, pull,
#                      install -r (org.matrix.vector.manager)
#   7 vector-scope     su /data/adb/lspd/cli modules enable <QWY lane pkg>; scope set
#                      <QWY lane pkg> com.cellrebel.mobile/0 <Auto pkg>/0 <module>/0
#                      (Auto must be in scope to read the signed config back)
#   8 publish-probe    prompt a manual System-Mock toggle, then poll logcat <=60s
#                      asserting ConfigPrefsSync published=true (timeout = FAIL)
#   9 accessibility    settings put enabled_accessibility_services
#                      <Auto pkg>/...AutomationService + accessibility_enabled 1,
#                      assert Bound via dumpsys (retry x3); LAST device step
#  10 report           structured readiness report to stdout + file (step x status
#                      x evidence + leftover manual items)
#
# Exit codes:
#   0  provisioned (root-less devices: READY with root leftovers documented)
#   1  a step FAILED — the step name is printed; report file is written
#   2  usage error (bad/missing flag)
#   3  preflight failure (adb missing / device not ready / lane package missing)
#
# B-layer (on-device) acceptance checklist:
#   - New device, full chain: exactly ONE provision run + ONE manual System-Mock
#     toggle finishes in <= 10 minutes (preflight + 10 steps, no APK builds).
#   - Repair / re-provision after a partial run or reboot: re-run the same
#     command — every completed step reports SKIP with evidence, missing steps
#     execute; whole repair pass <= 2 minutes.
#   - moto (and other OEM accessibility-rollback models): if step 9 keeps failing
#     after retries, the OEM reverts the `settings put` — enable the service
#     manually on the phone (设置 -> 无障碍 -> 已下载的应用 -> Auto engine service),
#     then verify with `--step accessibility`. Do NOT loop-retry the settings put.
#
# Spec source: ~/.agents/skills/fakexxx-device-ops/SKILL.md §2.

set -uo pipefail

# ---------------------------------------------------------------------------
# lane -> package routing table (SINGLE SOURCE; release has no suffix)
# ---------------------------------------------------------------------------
QWY_BASE="name.caiyao.fakegps"
AUTO_BASE="com.example.cellrebelauto"
CELLREBEL_PKG="com.cellrebel.mobile"
AUTO_SERVICE_CLS="com.example.cellrebelauto.automation.AutomationService"
VECTOR_ZIP_DEFAULT="/tmp/Vector-v2.2-3110-Release.zip"
VECTOR_MODULE_ID="org.matrix.vector"
VECTOR_MANAGER_PKG="org.matrix.vector.manager"
LSPD_CLI="/data/adb/lspd/cli"
CSV_DIR="/sdcard/Download"
PLAN_CSV_REMOTE_NAME="location_test_plan.csv"
PERMS="android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.POST_NOTIFICATIONS"
STEPS_LIST="mock_location permissions push-csv power-whitelist vector-module manager-apk vector-scope publish-probe accessibility report"
STEP_NAMES=(mock_location permissions push-csv power-whitelist vector-module manager-apk vector-scope publish-probe accessibility report)

# ---------------------------------------------------------------------------
# args
# ---------------------------------------------------------------------------
usage_err() {
    printf 'provision-device: %s\n' "$1" >&2
    printf 'Try `scripts/provision-device.sh --help`.\n' >&2
    exit 2
}

print_help() {
    sed -n '2,/^set -uo pipefail$/p' "$0" | sed 's/^# \{0,1\}//' | sed '$d'
}

step_index() { # step name -> 0-based index (defined BEFORE arg parsing: validation uses it)
    case "$1" in
        mock_location) printf 0 ;;
        permissions) printf 1 ;;
        push-csv) printf 2 ;;
        power-whitelist) printf 3 ;;
        vector-module) printf 4 ;;
        manager-apk) printf 5 ;;
        vector-scope) printf 6 ;;
        publish-probe) printf 7 ;;
        accessibility) printf 8 ;;
        report) printf 9 ;;
        *) return 1 ;;
    esac
}

step_fn() { printf 'step_%s' "$(printf '%s' "$1" | tr '-' '_')"; }

SERIAL=""
LANE=""
PLAN_CSV=""
PROFILE_CSV=""
VECTOR_ZIP="$VECTOR_ZIP_DEFAULT"
VECTOR_ZIP_SHA="${VECTOR_ZIP_SHA256_EXPECTED:-}"
DRY_RUN=0
ONLY_STEP=""

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) [ -n "${2:-}" ] || usage_err "--serial needs a value"; SERIAL="$2"; shift 2 ;;
        --lane) [ -n "${2:-}" ] || usage_err "--lane needs a value"; LANE="$2"; shift 2 ;;
        --plan-csv) [ -n "${2:-}" ] || usage_err "--plan-csv needs a value"; PLAN_CSV="$2"; shift 2 ;;
        --profile-csv) [ -n "${2:-}" ] || usage_err "--profile-csv needs a value"; PROFILE_CSV="$2"; shift 2 ;;
        --vector-zip) [ -n "${2:-}" ] || usage_err "--vector-zip needs a value"; VECTOR_ZIP="$2"; shift 2 ;;
        --vector-zip-sha256) [ -n "${2:-}" ] || usage_err "--vector-zip-sha256 needs a value"; VECTOR_ZIP_SHA="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --step) [ -n "${2:-}" ] || usage_err "--step needs a value"; ONLY_STEP="$2"; shift 2 ;;
        --help|-h) print_help; exit 0 ;;
        -*) usage_err "unknown argument: $1" ;;
        *) usage_err "unexpected positional argument: $1" ;;
    esac
done

[ -n "$SERIAL" ] || usage_err "--serial is required (adb devices -l)"
case "$LANE" in
    "") usage_err "--lane is required (glmbench|bench|release|codexbench)" ;;
    glmbench|bench|release|codexbench) ;;
    *) usage_err "invalid --lane '$LANE' (valid: glmbench bench release codexbench)" ;;
esac
if [ -n "$ONLY_STEP" ]; then
    step_index "$ONLY_STEP" >/dev/null 2>&1 ||
        usage_err "invalid --step '$ONLY_STEP' (valid: $STEPS_LIST)"
fi

lane_suffix() { # lane -> ".<lane>" or "" for release
    case "$1" in
        release) return ;;
        *) printf '.%s' "$1" ;;
    esac
}
SUF="$(lane_suffix "$LANE")"
QWY_PKG="${QWY_BASE}${SUF}"
AUTO_PKG="${AUTO_BASE}${SUF}"
CSV_REMOTE_PLAN="$CSV_DIR/$PLAN_CSV_REMOTE_NAME"
CSV_REMOTE_PROFILE="$CSV_DIR/$(basename "$PROFILE_CSV" 2>/dev/null || true)"
MGR_LOCAL="${TMPDIR:-/tmp}/provision-$SERIAL/vector-manager.apk"
REPORT_PATH="${PROVISION_REPORT_PATH:-$PWD/provision-report-$SERIAL.txt}"

# ---------------------------------------------------------------------------
# step status bookkeeping
# ---------------------------------------------------------------------------
ST_STATUS=("" "" "" "" "" "" "" "" "" "")
ST_EVIDENCE=("" "" "" "" "" "" "" "" "" "")
LEFTOVERS=()
LEFTOVER_SEEN=""
ROOTED=0
ROOT_MSG=""
WM_SIZE=""
VECTOR_SHA=""
VECTOR_REBOOT_PENDING=0
REPORTED_READY="yes"

oneline() { # collapse whitespace + cap length for evidence strings
    printf '%s' "$1" | tr '\n\t' '  ' | tr -s ' ' | cut -c1-160
}

record() { # idx status evidence
    ST_STATUS[$1]="$2"
    ST_EVIDENCE[$1]="$(oneline "$3")"
}
done_step() { record "$1" DONE "$2"; printf '      DONE (%s)\n' "$(oneline "$2")"; }
skip_step() { record "$1" SKIP "$2"; printf '      SKIP (%s)\n' "$(oneline "$2")"; }

die_step() { # idx msg -> FAILED + report + exit 1
    record "$1" FAILED "$2"
    REPORTED_READY="no"
    printf '      FAILED (%s)\n' "$(oneline "$2")" >&2
    printf '[provision] STEP FAILED: %s — %s\n' "${STEP_NAMES[$1]}" "$(oneline "$2")" >&2
    write_report
    printf '[provision] report written: %s\n' "$REPORT_PATH" >&2
    exit 1
}

add_leftover() { LEFTOVERS+=("$1"); }
add_leftover_once() {
    case "|$LEFTOVER_SEEN|" in
        *"|${1:0:48}|"*) return ;;
    esac
    LEFTOVER_SEEN="$LEFTOVER_SEEN|${1:0:48}"
    add_leftover "$1"
}

begin_step() { printf '  [%d/10] %s\n' $(( $1 + 1 )) "${STEP_NAMES[$1]}"; }

# ---------------------------------------------------------------------------
# adb primitives. run_* are DRY-RUN AWARE: in dry-run they only PRINT the exact
# command (single source for plan and execution). q_* are query-only helpers for
# self-checks and are never called in dry-run.
# ---------------------------------------------------------------------------
run_sh() { # adb -s SERIAL shell <cmd>
    if [ "$DRY_RUN" -eq 1 ]; then printf '      $ adb -s %s shell %s\n' "$SERIAL" "$1"; return 0; fi
    adb -s "$SERIAL" shell "$1"
}
run_adb() { # adb -s SERIAL <args...>
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '      $ adb -s %s' "$SERIAL"
        printf ' %s' "$@"
        printf '\n'
        return 0
    fi
    adb -s "$SERIAL" "$@"
}
run_su() { # adb -s SERIAL shell su -c "<cmd>"
    if [ "$DRY_RUN" -eq 1 ]; then printf '      $ adb -s %s shell su -c "%s"\n' "$SERIAL" "$1"; return 0; fi
    adb -s "$SERIAL" shell su -c "$1"
}
plan_cmd() { # host-side command, display-only (dry-run plans; run mode computes separately)
    [ "$DRY_RUN" -eq 1 ] && printf '      $ %s\n' "$1"
    return 0
}
note_line() { printf '      note: %s\n' "$1"; }

q_sh() { adb -s "$SERIAL" shell "$1" 2>/dev/null | tr -d '\r'; }
q_su() { adb -s "$SERIAL" shell su -c "$1" 2>/dev/null | tr -d '\r'; }

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
    else sha256sum "$1" | awk '{print $1}'
    fi
}

# ---------------------------------------------------------------------------
# preflight (run mode only): adb present, device ready, lane packages installed,
# wm size recorded, root state probed. Missing packages exit 3 — APK installation
# is NOT this script's job (scripts/install_apk_verified.sh).
# ---------------------------------------------------------------------------
preflight() {
    command -v adb >/dev/null 2>&1 || { printf '[provision] ERROR: adb not found in PATH\n' >&2; exit 3; }
    local try=1 state="" gs_out=""
    while [ "$try" -le 6 ]; do
        # capture FIRST, then match: `adb | grep -q` under pipefail races — grep
        # exits early on match, adb eats SIGPIPE (rc 141) and the match is lost.
        gs_out="$(adb -s "$SERIAL" get-state 2>&1 || true)"
        state="$(printf '%s' "$gs_out" | tr -d '\r' | head -1)"
        [ "$state" = "device" ] && break
        case "$gs_out" in
            *unauthorized*)
                printf '[provision] ERROR: device %s is unauthorized — accept the USB debugging prompt on the phone, then re-run\n' "$SERIAL" >&2
                exit 3
                ;;
        esac
        printf '[provision] WARN: device %s not ready (state=%s), retry %d/6 in 5s\n' "$SERIAL" "${state:-<absent>}" "$try" >&2
        sleep 5
        try=$((try + 1))
    done
    [ "$state" = "device" ] || { printf '[provision] ERROR: device %s never reached state=device\n' "$SERIAL" >&2; exit 3; }

    local p path
    for p in "$QWY_PKG" "$AUTO_PKG" "$CELLREBEL_PKG"; do
        path="$(q_sh "pm path $p" | head -1)"
        [ -n "$path" ] || {
            printf '[provision] ERROR: %s is not installed — install the three APKs first (scripts/install_apk_verified.sh)\n' "$p" >&2
            exit 3
        }
    done

    WM_SIZE="$(oneline "$(q_sh 'wm size')")"
    ROOT_MSG="$(oneline "$(q_su 'id' | head -1)")"
    case "$ROOT_MSG" in uid=0*) ROOTED=1 ;; esac
    printf '[provision] preflight ok: serial=%s lane=%s wm_size="%s" rooted=%s\n' \
        "$SERIAL" "$LANE" "$WM_SIZE" "$([ "$ROOTED" -eq 1 ] && echo yes || echo no)"
}

root_skip() { # idx
    add_leftover_once "device not rooted: Vector/LSPosed chain unavailable — A+ trusted lane unusable (published=false -> tests UNVERIFIED). Root via Magisk+Zygisk+Vector, then re-run this script."
    skip_step "$1" "device not rooted (su -c id -> ${ROOT_MSG:-no output}) — root-gated step skipped"
}

# ---------------------------------------------------------------------------
# step 1: mock_location + location appops hardening
#   FINE/COARSE_LOCATION appops must be "allow", not "foreground": on HyperOS
#   the foreground mode lets PowerKeeper drop the test provider while the app
#   has no visible activity, which is the freeze chain root cause of #106
#   (incident remediation: appops foreground->allow on both apps).
# ---------------------------------------------------------------------------
step_mock_location() {
    local idx="$1" pkg op cur all_allow=1
    if [ "$DRY_RUN" -eq 0 ]; then
        all_allow=1
        for pkg in "$QWY_PKG" "$AUTO_PKG"; do
            for op in FINE_LOCATION COARSE_LOCATION; do
                cur="$(q_sh "appops get $pkg $op")"
                case "$cur" in
                    *": allow"*) ;;
                    *) all_allow=0 ;;
                esac
            done
        done
        cur="$(q_sh "appops get $QWY_PKG android:mock_location")"
        case "$cur" in
            *": allow"*) if [ "$all_allow" -eq 1 ]; then
                    skip_step "$idx" "already allow: mock_location + FINE/COARSE on both apps"
                    return
                fi ;;
        esac
    fi
    run_sh "appops set $QWY_PKG android:mock_location allow"
    for pkg in "$QWY_PKG" "$AUTO_PKG"; do
        for op in FINE_LOCATION COARSE_LOCATION; do
            run_sh "appops set $pkg $op allow"
        done
    done
    if [ "$DRY_RUN" -eq 0 ]; then
        local now bad=""
        now="$(q_sh "appops get $QWY_PKG android:mock_location")"
        case "$now" in *": allow"*) ;; *) bad="mock_location: $now" ;; esac
        for pkg in "$QWY_PKG" "$AUTO_PKG"; do
            for op in FINE_LOCATION COARSE_LOCATION; do
                cur="$(q_sh "appops get $pkg $op")"
                case "$cur" in
                    *": allow"*) ;;
                    *) bad="$bad${bad:+; }$pkg/$op: ${cur:-<empty>}" ;;
                esac
            done
        done
        if [ -z "$bad" ]; then
            done_step "$idx" "appops ok: mock_location + FINE/COARSE=allow on both apps (#106 hardening)"
        else
            die_step "$idx" "appops did not stick: $bad"
        fi
    fi
}

# ---------------------------------------------------------------------------
# step 2: permissions (both apps x FINE/COARSE location + POST_NOTIFICATIONS)
# ---------------------------------------------------------------------------
perm_state() { # pkg perm -> granted | todo | absent
    local dump
    dump="$(q_sh "dumpsys package $1")"
    case "$dump" in
        *"$2: granted=true"*) printf 'granted' ;;
        *"$2"*) printf 'todo' ;;
        *) printf 'absent' ;;
    esac
}

step_permissions() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 1 ]; then
        local pkg p
        for pkg in "$QWY_PKG" "$AUTO_PKG"; do
            for p in $PERMS; do run_sh "pm grant $pkg $p"; done
        done
        note_line "runtime: already-granted perms are skipped; grant+verify retried <=3x with 2s gaps (HyperOS probabilistic revoke)"
        return
    fi
    local attempt missing desc pkg p state absent_notes=""
    attempt=1
    while [ "$attempt" -le 3 ]; do
        missing=0
        desc=""
        for pkg in "$QWY_PKG" "$AUTO_PKG"; do
            for p in $PERMS; do
                state="$(perm_state "$pkg" "$p")"
                case "$state" in
                    granted) ;;
                    absent) absent_notes="$absent_notes ${pkg##*.}:$p not declared" ;;
                    *) run_sh "pm grant $pkg $p"; missing=$((missing + 1)); desc="$desc ${pkg}/${p}" ;;
                esac
            done
        done
        [ "$missing" -eq 0 ] && break
        printf '      retry: %d perm(s) still ungranted, attempt %d/3, sleeping 2s\n' "$missing" "$attempt"
        sleep 2
        attempt=$((attempt + 1))
    done
    if [ "$missing" -ne 0 ]; then
        die_step "$idx" "permissions still ungranted after 3 attempts:${desc} — check 设置->应用->权限 (HyperOS may re-strip)"
    fi
    done_step "$idx" "location x2 + notification granted for both apps (attempt $attempt)${absent_notes:+; undeclared:${absent_notes}}"
}

# ---------------------------------------------------------------------------
# step 3: push-csv
# ---------------------------------------------------------------------------
remote_cmp() { # local remote -> MATCH | MATCH(size) | MISMATCH* | ABSENT
    local lsha rsha rsize lsize
    lsha="$(sha256_of "$1")"
    rsha="$(q_sh "sha256sum $2 2>/dev/null" | awk '{print $1}')"
    if printf '%s' "$rsha" | grep -q '^[0-9a-f]\{64\}$'; then
        [ "$rsha" = "$lsha" ] && { printf 'MATCH'; return; }
        printf 'MISMATCH(sha device=%s local=%s)' "${rsha:0:12}" "${lsha:0:12}"
        return
    fi
    rsize="$(q_sh "ls -l $2 2>/dev/null" | awk '{print $5}' | head -1)"
    if [ -n "$rsize" ]; then
        lsize="$(stat -f %z "$1" 2>/dev/null || stat -c %s "$1" 2>/dev/null || printf '?')"
        [ "$rsize" = "$lsize" ] && { printf 'MATCH(size)'; return; }
        printf 'MISMATCH(size device=%s local=%s)' "$rsize" "$lsize"
        return
    fi
    printf 'ABSENT'
}

CSV_EV=""
csv_sync() { # idx local remote label — appends evidence to CSV_EV, prints own lines
    local idx="$1" lp="$2" remote="$3" label="$4" res
    [ -f "$lp" ] || die_step "$idx" "$label csv not found: $lp"
    res="$(remote_cmp "$lp" "$remote")"
    case "$res" in
        MATCH)
            printf '      - SKIP: %s csv already on device with identical bytes (%s)\n' "$label" "$remote"
            CSV_EV="$CSV_EV ${label}:already-on-device(${remote})"
            ;;
        *)
            run_adb push "$lp" "$remote"
            res="$(remote_cmp "$lp" "$remote")"
            case "$res" in
                MATCH*)
                    printf '      - PUSHED: %s csv -> %s (%s)\n' "$label" "$remote" "$res"
                    CSV_EV="$CSV_EV ${label}:pushed(${remote})"
                    ;;
                *) die_step "$idx" "$label csv push did not land ($res): $remote" ;;
            esac
            ;;
    esac
}

step_push_csv() {
    local idx="$1"
    if [ -z "$PLAN_CSV" ] && [ -z "$PROFILE_CSV" ]; then
        skip_step "$idx" "no --plan-csv/--profile-csv given — assuming CSVs already on device"
        return
    fi
    if [ "$DRY_RUN" -eq 1 ]; then
        [ -n "$PLAN_CSV" ] && run_adb push "$PLAN_CSV" "$CSV_REMOTE_PLAN"
        [ -n "$PROFILE_CSV" ] && run_adb push "$PROFILE_CSV" "$CSV_REMOTE_PROFILE"
        note_line "plan csv is renamed to $PLAN_CSV_REMOTE_NAME; profile csv keeps its name; runtime skips push when device bytes match"
        return
    fi
    CSV_EV=""
    [ -n "$PLAN_CSV" ] && csv_sync "$idx" "$PLAN_CSV" "$CSV_REMOTE_PLAN" "plan"
    [ -n "$PROFILE_CSV" ] && csv_sync "$idx" "$PROFILE_CSV" "$CSV_REMOTE_PROFILE" "profile"
    case "$CSV_EV" in
        *pushed*) done_step "$idx" "$CSV_EV" ;;
        *) skip_step "$idx" "$CSV_EV" ;;
    esac
}

# ---------------------------------------------------------------------------
# step 4: power-whitelist
# ---------------------------------------------------------------------------
stay_on_ok() {
    local v
    v="$(q_sh 'settings get global stay_on_while_plugged_in' | tr -dc '0-9')"
    [ -n "$v" ] || v=0
    [ $((v & 2)) -ne 0 ]
}
whitelist_ok() {
    local wl
    wl="$(q_sh 'dumpsys deviceidle whitelist')"
    case "$wl" in *" $QWY_PKG"*|*"$QWY_PKG $AUTO_PKG"*|*"+$QWY_PKG"*) ;; *) return 1 ;; esac
    case "$wl" in *"+$AUTO_PKG"*|*" $AUTO_PKG"*) ;; *) return 1 ;; esac
    return 0
}

step_power_whitelist() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ] && stay_on_ok && whitelist_ok; then
        skip_step "$idx" "stay_on_while_plugged_in has USB bit (stay=$(q_sh 'settings get global stay_on_while_plugged_in')); deviceidle whitelist contains both pkgs"
        return
    fi
    # HyperOS/16: `svc power stayon` from shell uid silently fails (setting stays 0);
    # only the su path lands. Verified on mi14 e53cfd3d: shell -> 0, su -> 2.
    if [ "$ROOTED" -eq 1 ]; then
        run_su "svc power stayon usb"
    else
        run_sh "svc power stayon usb"
    fi
    run_sh "dumpsys deviceidle whitelist +$QWY_PKG"
    run_sh "dumpsys deviceidle whitelist +$AUTO_PKG"
    if [ "$DRY_RUN" -eq 0 ]; then
        stay_on_ok || die_step "$idx" "svc power stayon usb did not set the USB bit"
        whitelist_ok || die_step "$idx" "deviceidle whitelist still missing a lane package after adding"
        done_step "$idx" "stayon usb set; whitelist += $QWY_PKG $AUTO_PKG (screen-off freezes CellRebel UI — CELLREBEL_TIMEOUT)"
    fi
}

# ---------------------------------------------------------------------------
# step 5: vector-module (root)
# ---------------------------------------------------------------------------
vector_module_dir() { # prints the installed module dir, or empty
    local d
    d="$(q_su "test -d /data/adb/modules/$VECTOR_MODULE_ID && echo /data/adb/modules/$VECTOR_MODULE_ID")"
    [ -n "$d" ] && { printf '%s' "$d"; return; }
    q_su "grep -il vector /data/adb/modules/*/module.prop 2>/dev/null" | head -1 | sed 's|/module.prop$||'
}

step_vector_module() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ]; then
        [ "$ROOTED" -eq 1 ] || { root_skip "$idx"; return; }
        local d
        d="$(vector_module_dir)"
        if [ -n "$d" ]; then
            skip_step "$idx" "module already installed at $d"
            return
        fi
        [ -f "$VECTOR_ZIP" ] || die_step "$idx" "Vector zip not found: $VECTOR_ZIP (recover: gh release download canary-3110 --repo JingMatrix/Vector --pattern 'Vector-v2.2-3110-Release.zip')"
        VECTOR_SHA="$(sha256_of "$VECTOR_ZIP")"
        if [ -n "$VECTOR_ZIP_SHA" ] && [ "$VECTOR_SHA" != "$VECTOR_ZIP_SHA" ]; then
            die_step "$idx" "Vector zip sha256 mismatch: got $VECTOR_SHA expected $VECTOR_ZIP_SHA — refusing to install wrong bytes"
        fi
    fi
    plan_cmd "shasum -a 256 $VECTOR_ZIP"
    run_adb push "$VECTOR_ZIP" "/data/local/tmp/$(basename "$VECTOR_ZIP")"
    run_su "magisk --install-module /data/local/tmp/$(basename "$VECTOR_ZIP")"
    note_line "REBOOT required to activate the module; re-run this script after reboot (steps 1-4 will SKIP)"
    if [ "$DRY_RUN" -eq 0 ]; then
        local d
        d="$(vector_module_dir)"
        [ -n "$d" ] || die_step "$idx" "module dir missing after magisk --install-module — inspect magisk output above"
        VECTOR_REBOOT_PENDING=1
        add_leftover "REBOOT required to activate the Vector module; re-run provision-device.sh after reboot (remaining steps will complete or SKIP)"
        done_step "$idx" "installed module at $d (zip sha256=${VECTOR_SHA:-?}); reboot pending"
    fi
}

# ---------------------------------------------------------------------------
# step 6: manager-apk (root)
# ---------------------------------------------------------------------------
step_manager_apk() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ]; then
        [ "$ROOTED" -eq 1 ] || { root_skip "$idx"; return; }
        local pmpath
        pmpath="$(q_sh "pm path $VECTOR_MANAGER_PKG" | head -1)"
        if [ -n "$pmpath" ]; then
            skip_step "$idx" "manager already installed: $pmpath"
            return
        fi
        q_su "test -f /data/adb/modules/$VECTOR_MODULE_ID/manager.apk" >/dev/null ||
            die_step "$idx" "no manager.apk in /data/adb/modules/$VECTOR_MODULE_ID — did step vector-module install the right zip? (reboot first if just installed)"
    fi
    run_su "chown 2000:2000 /data/adb/modules/$VECTOR_MODULE_ID/manager.apk && chmod 644 /data/adb/modules/$VECTOR_MODULE_ID/manager.apk"
    run_adb pull "/data/adb/modules/$VECTOR_MODULE_ID/manager.apk" "$MGR_LOCAL"
    run_adb install -r "$MGR_LOCAL"
    if [ "$DRY_RUN" -eq 0 ]; then
        local pmpath
        pmpath="$(q_sh "pm path $VECTOR_MANAGER_PKG" | head -1)"
        [ -n "$pmpath" ] || die_step "$idx" "manager install claimed success but pm path resolves nothing"
        done_step "$idx" "installed $VECTOR_MANAGER_PKG via install -r (pulled from module dir, chown 2000:2000 + chmod 644)"
    fi
}

# ---------------------------------------------------------------------------
# step 7: vector-scope (root)
# ---------------------------------------------------------------------------
step_vector_scope() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ]; then
        [ "$ROOTED" -eq 1 ] || { root_skip "$idx"; return; }
        local cli_ok list_out scope_out
        cli_ok="$(q_su "test -x $LSPD_CLI && echo ok")"
        [ "$cli_ok" = "ok" ] || die_step "$idx" "$LSPD_CLI not present — reboot the device so the Vector/LSPosed daemon starts, then re-run this script (steps 1-6 will SKIP)"
        list_out="$(q_su "$LSPD_CLI modules ls")"
        scope_out="$(q_su "$LSPD_CLI scope ls $QWY_PKG")"
        if printf '%s' "$list_out" | grep -q "$QWY_PKG" &&
           printf '%s' "$scope_out" | grep -q "$CELLREBEL_PKG" &&
           printf '%s' "$scope_out" | grep -q "$AUTO_PKG" &&
           printf '%s' "$scope_out" | grep -q "$QWY_PKG"; then
            skip_step "$idx" "module enabled and scope already contains $CELLREBEL_PKG, $AUTO_PKG, $QWY_PKG"
            return
        fi
    fi
    run_su "$LSPD_CLI modules enable $QWY_PKG"
    run_su "$LSPD_CLI scope set $QWY_PKG $CELLREBEL_PKG/0 $AUTO_PKG/0 $QWY_PKG/0"
    if [ "$DRY_RUN" -eq 0 ]; then
        local scope_out
        scope_out="$(q_su "$LSPD_CLI scope ls $QWY_PKG")"
        if printf '%s' "$scope_out" | grep -q "$CELLREBEL_PKG" &&
           printf '%s' "$scope_out" | grep -q "$AUTO_PKG" &&
           printf '%s' "$scope_out" | grep -q "$QWY_PKG"; then
            done_step "$idx" "module enabled; scope = $CELLREBEL_PKG/0 + $AUTO_PKG/0 + $QWY_PKG/0 (Auto in scope so the signed config read-back works)"
        else
            die_step "$idx" "scope verify failed — scope ls $QWY_PKG -> $(oneline "$scope_out")"
        fi
    fi
}

# ---------------------------------------------------------------------------
# step 8: publish-probe (root) — manual toggle + logcat assertion
# ---------------------------------------------------------------------------
step_publish_probe() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ]; then
        [ "$ROOTED" -eq 1 ] || { root_skip "$idx"; return; }
        local line
        line="$(q_sh 'logcat -d -s ConfigPrefsSync' | grep -m1 'published=true')"
        if [ -n "$line" ]; then
            skip_step "$idx" "already published in logcat buffer: $line"
            return
        fi
    fi
    run_sh "logcat -c"
    printf '      MANUAL ACTION: open %s settings on the device and toggle "System Mock" OFF->ON once\n' "$QWY_PKG"
    printf '                    (deliberately NOT automated — su input tap on that switch is unreliable; a human or the upper layer does this)\n'
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '      $ adb -s %s shell logcat -d -s ConfigPrefsSync   # polled every 3s, <=60s, must contain published=true\n' "$SERIAL"
        return
    fi
    printf '      polling logcat for ConfigPrefsSync published=true (every 3s, <=60s)...\n'
    local deadline line
    deadline=$(( SECONDS + 60 ))
    while [ "$SECONDS" -lt "$deadline" ]; do
        line="$(q_sh 'logcat -d -s ConfigPrefsSync' | grep -m1 'published=true')"
        if [ -n "$line" ]; then
            done_step "$idx" "ConfigPrefsSync published=true seen: $line"
            return
        fi
        sleep 3
    done
    die_step "$idx" "no ConfigPrefsSync published=true within 60s — check: module enabled+scoped (step 7), daemon alive (reboot done?), System Mock actually toggled"
}

# ---------------------------------------------------------------------------
# step 9: accessibility (root NOT required; MUST be the last device step)
# ---------------------------------------------------------------------------
a11y_svc_name() { printf '%s/%s' "$AUTO_PKG" "$AUTO_SERVICE_CLS"; }

a11y_bound_ok() {
    local out svc_line bound_line inst_line svc_setting en
    svc_setting="$(q_sh 'settings get secure enabled_accessibility_services')"
    en="$(q_sh 'settings get secure accessibility_enabled')"
    case "$svc_setting" in *"$(a11y_svc_name)"*) ;; *) return 1 ;; esac
    [ "$en" = "1" ] || return 1
    out="$(q_sh 'dumpsys accessibility')"
    bound_line="$(printf '%s\n' "$out" | grep -in 'bound' | head -1 | cut -d: -f1)"
    svc_line="$(printf '%s\n' "$out" | grep -n "$AUTO_SERVICE_CLS" | head -1 | cut -d: -f1)"
    [ -n "$bound_line" ] && [ -n "$svc_line" ] || return 1
    [ "$svc_line" -ge "$bound_line" ] || return 1
    inst_line="$(printf '%s\n' "$out" | grep -in 'installed service' | head -1 | cut -d: -f1)"
    if [ -n "$inst_line" ] && [ "$svc_line" -ge "$inst_line" ]; then return 1; fi
    return 0
}

step_accessibility() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 0 ] && a11y_bound_ok; then
        skip_step "$idx" "service already Bound: $(q_sh 'dumpsys accessibility' | grep "$AUTO_SERVICE_CLS" | head -1)"
        return
    fi
    run_sh "settings put secure enabled_accessibility_services $(a11y_svc_name)"
    run_sh "settings put secure accessibility_enabled 1"
    if [ "$DRY_RUN" -eq 1 ]; then
        note_line "runtime asserts Bound via dumpsys accessibility (retry x3 @2s); OEM rollback models (moto) -> manual enable + --step accessibility"
        return
    fi
    local try=1
    while [ "$try" -le 3 ]; do
        if a11y_bound_ok; then
            done_step "$idx" "accessibility Bound: $(q_sh 'dumpsys accessibility' | grep "$AUTO_SERVICE_CLS" | head -1)"
            return
        fi
        sleep 2
        try=$((try + 1))
    done
    add_leftover "accessibility NOT Bound: OEM (moto等) reverts settings put — enable manually on the phone (设置 -> 无障碍 -> 已下载的应用 -> Auto engine service), then verify with --step accessibility"
    die_step "$idx" "accessibility service not Bound after settings put x3 — OEM rollback suspected (moto等): enable manually, then re-run with --step accessibility"
}

# ---------------------------------------------------------------------------
# step 10: report (local only — never touches adb)
# ---------------------------------------------------------------------------
count_status() { # status -> count
    local i n=0
    for i in 0 1 2 3 4 5 6 7 8 9; do
        [ "${ST_STATUS[$i]}" = "$1" ] && n=$((n + 1))
    done
    printf '%d' "$n"
}

write_report() {
    local i nd ns nf
    nd="$(count_status DONE)"; ns="$(count_status SKIP)"; nf="$(count_status FAILED)"
    {
        printf '# fakexxx provision report\n'
        printf 'generated:   %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
        printf 'serial:      %s\n' "$SERIAL"
        printf 'lane:        %s\n' "$LANE"
        printf 'qwy_pkg:     %s\n' "$QWY_PKG"
        printf 'auto_pkg:    %s\n' "$AUTO_PKG"
        printf 'a11y_svc:    %s/%s\n' "$AUTO_PKG" "$AUTO_SERVICE_CLS"
        printf 'cellrebel:   %s\n' "$CELLREBEL_PKG"
        printf 'rooted:      %s\n' "$([ "$ROOTED" -eq 1 ] && echo yes || echo no)"
        [ -n "$WM_SIZE" ] && printf 'wm_size:     %s\n' "$WM_SIZE"
        [ -n "$VECTOR_SHA" ] && printf 'vector_zip_sha256: %s\n' "$VECTOR_SHA"
        printf 'result:      %s (done=%d skip=%d failed=%d)\n' "$([ "$REPORTED_READY" = "yes" ] && printf READY || printf NOT-READY)" "$nd" "$ns" "$nf"
        printf '\n%-4s %-16s %-8s %s\n' '#' 'step' 'status' 'evidence'
        for i in 0 1 2 3 4 5 6 7 8 9; do
            printf '%-4d %-16s %-8s %s\n' $((i + 1)) "${STEP_NAMES[$i]}" "${ST_STATUS[$i]:-PENDING}" "${ST_EVIDENCE[$i]}"
        done
        printf '\nleftover manual items:\n'
        add_leftover_once "manual UI (not verified by this script): QWY profile import + anchor one profile (edit -> save FAB) — skill §2.6; without the anchor the publisher exits early"
        add_leftover_once "manual UI (not verified by this script): Auto provider approval of QWY (首次配对) — skill §2.8"
        if [ "${#LEFTOVERS[@]}" -gt 0 ]; then
            local e
            for e in ${LEFTOVERS[@]+"${LEFTOVERS[@]}"}; do printf '  - %s\n' "$e"; done
        fi
    } >"$REPORT_PATH" 2>/dev/null || { printf '[provision] ERROR: cannot write report to %s\n' "$REPORT_PATH" >&2; return 1; }
    printf '[provision] report written: %s\n' "$REPORT_PATH"
}

step_report() {
    local idx="$1"
    if [ "$DRY_RUN" -eq 1 ]; then
        note_line "local-only step (no adb) — writes provision-report-$SERIAL.txt in the invocation cwd (step x status x evidence + leftovers)"
        return
    fi
    write_report || die_step "$idx" "report could not be written to $REPORT_PATH"
    cat "$REPORT_PATH"
}

# ---------------------------------------------------------------------------
# plan header (dry-run) + dispatch
# ---------------------------------------------------------------------------
print_routing() {
    printf 'routing (single source: <pkg-base>[.<lane>]; release has no suffix):\n'
    printf '  %-20s %s\n' qwy_pkg "$QWY_PKG"
    printf '  %-20s %s\n' auto_pkg "$AUTO_PKG"
    printf '  %-20s %s/%s\n' a11y_service "$AUTO_PKG" "$AUTO_SERVICE_CLS"
    printf '  %-20s %s\n' cellrebel_pkg "$CELLREBEL_PKG"
    printf '  %-20s %s\n' vector_zip "$VECTOR_ZIP"
    printf '  %-20s /data/adb/modules/%s\n' vector_module_dir "$VECTOR_MODULE_ID"
    printf '  %-20s %s\n' vector_manager_pkg "$VECTOR_MANAGER_PKG"
    printf '  %-20s %s\n' lspd_cli "$LSPD_CLI"
    if [ -n "$PLAN_CSV" ]; then
        printf '  %-20s %s -> %s\n' plan_csv "$PLAN_CSV" "$CSV_REMOTE_PLAN"
    else
        printf '  %-20s %s\n' plan_csv "(none given — step 3 will SKIP)"
    fi
    if [ -n "$PROFILE_CSV" ]; then
        printf '  %-20s %s -> %s\n' profile_csv "$PROFILE_CSV" "$CSV_REMOTE_PROFILE"
    else
        printf '  %-20s %s\n' profile_csv "(none given — step 3 will SKIP)"
    fi
    printf '  %-20s %s (cwd)\n' report_file "provision-report-$SERIAL.txt"
}

run_steps() {
    if [ -n "$ONLY_STEP" ]; then
        local i
        i="$(step_index "$ONLY_STEP")"
        begin_step "$i"
        "$(step_fn "$ONLY_STEP")" "$i"
        return
    fi
    local i
    for i in 0 1 2 3 4 5 6 7 8 9; do
        begin_step "$i"
        "$(step_fn "${STEP_NAMES[$i]}")" "$i"
    done
}

# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
    printf 'provision-device DRY RUN — plan only, ZERO adb commands will be executed\n'
    print_routing
    printf 'steps (cold path; at run time every step first self-checks and SKIPs finished work):\n'
    run_steps
    exit 0
fi

preflight
run_steps
if [ -n "$ONLY_STEP" ] && [ "$ONLY_STEP" != "report" ]; then
    write_report
fi
exit 0
