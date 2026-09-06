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

write_test_lock_owner() { # directory pid token
    printf 'format=fakegps-seed-lock-v1\npid=%s\nstarted=2026-09-06T12:00:00Z\nhost=selftest\ntoken=%s\npackage=name.caiyao.fakegps.bench\n' \
        "$2" "$3" >"$1/owner"
}

provenance_matches() { # artifact sidecar package file zone remote cardinality canonical [derived-from]
    local artifact="$1" sidecar="$2" package="$3" file="$4" zone="$5"
    local remote="$6" cardinality="$7" canonical="$8" derived="${9:-}"
    local actual_hash
    [ -s "$artifact" ] && [ -s "$sidecar" ] || return 1
    actual_hash=$(shasum -a 256 "$artifact" 2>/dev/null | awk 'NR == 1 { print $1 }') || return 1
    [[ "$actual_hash" =~ ^[0-9a-f]{64}$ ]] || return 1
    grep -Fqx "package=$package" "$sidecar" &&
        grep -Fqx "file=$file" "$sidecar" &&
        grep -Fqx "sourceZone=$zone" "$sidecar" &&
        grep -Fqx "remotePath=$remote" "$sidecar" &&
        grep -Fqx "cardinality=$cardinality" "$sidecar" &&
        grep -Fqx "sha256=$actual_hash" "$sidecar" &&
        grep -Fqx "canonical=$canonical" "$sidecar" || return 1
    if [ -n "$derived" ]; then
        grep -Fqx "derivedFromSha256=$derived" "$sidecar"
    else
        ! grep -q '^derivedFromSha256=' "$sidecar"
    fi
}

no_evidence_residue() { # failed fresh destination, staging and lock are all absent
    local destination="$1" parent base
    parent=$(dirname -- "$destination")
    base=$(basename -- "$destination")
    [ ! -e "$destination" ] && [ ! -L "$destination" ] || return 1
    (
        local residue
        shopt -s nullglob
        residue=(
            "$parent/.${base}.seed-evidence."*
            "$parent/.${base}.vector-evidence."*
        )
        [ "${#residue[@]}" -eq 0 ]
    )
}
[ -f "$GATE" ] || { echo "gate missing: $GATE" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
SHIM="$WORK/shim"; mkdir -p "$SHIM"
ADB_SENTINEL="$WORK/unexpected-adb-edge"
printf '#!/bin/sh\nprintf "called\\n" >>"$FAKE_ADB_SENTINEL"\nprintf "UNEXPECTED_REAL_ADB_EDGE\\n" >&2\nexit 97\n' >"$SHIM/adb"
chmod +x "$SHIM/adb"  # command -v adb must resolve; any execution is fatal

DIGEST=cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852
OLD_TOKEN=20260901T000000Z-1-stale

# Drive one gate invocation in a subshell (so its EXIT trap/lock is scoped),
# with the device seam + sleep faked. Scenario state comes from env/files:
#   FAKE_PID_MODE      alive|absent|error|noremote   (tri-state probe; default absent)
#   FAKE_LOGS_BEFORE   logcat content present BEFORE the launch (stale markers)
#   FAKE_LOGS_AFTER    logcat content that appears only AFTER the launch; the
#                      literal @TOKEN@ is replaced by the token the gate launched with
#   FAKE_FORCESTOP_RC  force-stop exit code
#   FAKE_LOGCAT_RC     logcat producer exit code (stdout may still look valid)
#   FAKE_*_PARSER_RC   parser exit code after emitting plausible parsed stdout
run_gate() { # -> OUT / RC / EVENTS ; first arg = lock dir
    local lock="$1"; shift
    EVENTS="$WORK/events.$RANDOM$RANDOM"; : >"$EVENTS"
    local outf="$WORK/out.$RANDOM$RANDOM"
    # A REAL ( ) subshell writing to a file — NOT $(...), because macOS bash 3.2
    # mis-parses a `case` pattern's `)` inside command substitution.
    (
        export SEED_GATE_SOURCE_ONLY=1
        export VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        export PATH="$SHIM:$PATH"
        export FAKE_ADB_SENTINEL="$ADB_SENTINEL"
        export FAKE_EVENTS="$EVENTS"
        export SEED_GATE_LOCK_DIR="$lock"
        export SEED_GATE_AWAIT_TRIES="${AWAIT_TRIES:-3}"
        # shellcheck disable=SC1090
        . "$GATE"
        if [ -n "${FAKE_HASH_FAILURE:-}" ]; then
            ve_hash_file() { return 1; }
        fi
        if [ -n "${FAKE_PROVENANCE_WRITE_FAILURE:-}" ]; then
            ve_write_provenance() { return 1; }
        fi
        if [ -n "${FAKE_COPY_FAILURE:-}" ]; then
            cp() { return 1; }
        fi
        eval "$(declare -f release_lock | sed '1s/^release_lock /seed_test_original_release_lock /')"
        release_lock() {
            printf 'lock-release-attempt\n' >>"$FAKE_EVENTS"
            if [ -n "${FAKE_RELEASE_LOCK_RC:-}" ]; then
                return "$FAKE_RELEASE_LOCK_RC"
            fi
            if [ -n "${FAKE_RELEASE_LOCK_LEAVES_PATH:-}" ]; then
                return 0
            fi
            seed_test_original_release_lock "$@"
        }
        eval "$(declare -f ve_publish_directory_create_only | sed '1s/^ve_publish_directory_create_only /seed_test_original_ve_publish_directory_create_only /')"
        ve_publish_directory_create_only() {
            if [ "${2:-}" = "$EVIDENCE_DIR" ]; then
                printf 'outer-publish-attempt\n' >>"$FAKE_EVENTS"
            fi
            seed_test_original_ve_publish_directory_create_only "$@"
        }
        eval "$(declare -f ve_emit_capture_success | sed '1s/^ve_emit_capture_success /seed_test_original_ve_emit_capture_success /')"
        ve_emit_capture_success() {
            printf 'capture-success-marker\n' >>"$FAKE_EVENTS"
            seed_test_original_ve_emit_capture_success "$@"
        }
        if [ -n "${FAKE_NEW_TOKEN_RC:-}" ]; then
            new_seed_token() {
                printf '20260906T120000Z-123-safe-token'
                return "$FAKE_NEW_TOKEN_RC"
            }
        fi
        if [ -n "${FAKE_LOCK_OWNER_PARSER_RC:-}" ]; then
            load_lock_owner_record() {
                LOCK_OWNER_PID="${FAKE_LOCK_OWNER_VALUE:-999999}"
                LOCK_OWNER_STARTED=old
                LOCK_OWNER_HOST=selftest
                LOCK_OWNER_TOKEN=old-safe-token
                LOCK_OWNER_PACKAGE=name.caiyao.fakegps.bench
                return "$FAKE_LOCK_OWNER_PARSER_RC"
            }
        fi
        if [ -n "${FAKE_LOCK_OWNER_WRITE_RC:-}" ]; then
            write_lock_owner() {
                printf 'pid=%s\n' "${BASHPID:-$$}" >"$LOCK_DIR/owner"
                if [ -n "${FAKE_LOCK_OWNER_WRITE_FOREIGN:-}" ]; then
                    printf 'foreign-do-not-delete\n' >"$LOCK_DIR/foreign-sentinel"
                fi
                return "$FAKE_LOCK_OWNER_WRITE_RC"
            }
        fi
        if [ -n "${FAKE_TOKEN_PARSER_RC:-}" ]; then
            token_lines() {
                local parsed
                parsed=$(printf '%s\n' "$1" | grep -E -- " token=${SEED_TOKEN}( |\$)")
                printf '%s\n' "$parsed"
                return "$FAKE_TOKEN_PARSER_RC"
            }
        fi
        if [ -n "${FAKE_COUNT_PARSER_RC:-}" ]; then
            count_marker() {
                local parsed
                parsed=$(printf '%s\n' "$1" | grep -c -F -- "$2")
                printf '%s\n' "$parsed"
                return "$FAKE_COUNT_PARSER_RC"
            }
        fi
        if [ -n "${FAKE_DIGEST_PARSER_RC:-}" ]; then
            count_verified_digest_marker() {
                printf '1\n'
                return "$FAKE_DIGEST_PARSER_RC"
            }
        fi
        if [ -n "${FAKE_STAGE_CLEANUP_RC:-}" ]; then
            remove_evidence_stage() {
                printf 'stage-cleanup-attempt:%s\n' "$1" >>"$FAKE_EVENTS"
                return "$FAKE_STAGE_CLEANUP_RC"
            }
        fi
        if [ -n "${FAKE_CAPTURE_RESIDUE:-}" ]; then
            eval "$(declare -f ve_capture_evidence | sed '1s/^ve_capture_evidence /seed_test_original_ve_capture_evidence /')"
            ve_capture_evidence() {
                local capture_rc
                seed_test_original_ve_capture_evidence "$@"
                capture_rc=$?
                [ "$capture_rc" -eq 0 ] || return "$capture_rc"
                case "$FAKE_CAPTURE_RESIDUE" in
                    unknown)
                        printf 'foreign\n' >"$3/vector-prefs/unknown.bin" ;;
                    inner-lock)
                        mkdir -p "$3/app-private-mirror/.inner-capture.lock"
                        printf 'stale-lock\n' >"$3/app-private-mirror/.inner-capture.lock/owner" ;;
                    missing-mirror)
                        rm -f "$3/app-private-mirror/spoof_config.xml" \
                            "$3/app-private-mirror/spoof_config.xml.provenance" ;;
                    *) return 96 ;;
                esac
                return 0
            }
        fi
        if [ -n "${FAKE_OUTER_PUBLISH_RACE:-}" ]; then
            # Model a destination created immediately after the publisher's
            # last absence observation. Returning the observation (absent)
            # while creating the directory makes a following plain `mv`
            # exercise its dangerous "move inside destination" behavior.
            VE_OUTER_EXISTS_CALLS=0
            ve_path_exists() {
                local candidate="$1"
                if [ "$candidate" = "$EVIDENCE_DIR" ]; then
                    VE_OUTER_EXISTS_CALLS=$((VE_OUTER_EXISTS_CALLS + 1))
                    if [ "$VE_OUTER_EXISTS_CALLS" -eq 2 ]; then
                        mkdir -p "$candidate"
                        printf 'foreign-owner\n' >"$candidate/foreign-owner.txt"
                        return 1
                    fi
                fi
                [ -e "$candidate" ] || [ -L "$candidate" ]
            }
        fi
        sleep() { :; }
        dev() {
            local dev_argc=$# joined="$*" root_command
            case "$joined" in
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
                            transport-absent) printf '__RC=1\n'; return 42 ;;
                            transport-alive) printf '%s\n__RC=0\n' "${FAKE_PID:-4321}"; return 42 ;;
                            duplicate-status) printf '__RC=1\n__RC=1\n' ;;
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
                    if [ -n "${FAKE_LOCK_FOREIGN_AFTER_LAUNCH:-}" ]; then
                        printf 'foreign-do-not-delete\n' >"$SEED_GATE_LOCK_DIR/foreign-sentinel"
                    fi
                    return 0 ;;
                "logcat "*)
                    cat "${FAKE_LOGS_BEFORE:-/dev/null}" 2>/dev/null
                    if grep -q seed-launched "$FAKE_EVENTS" 2>/dev/null && [ -n "${FAKE_LOGS_AFTER:-}" ]; then
                        tok=$(cat "$FAKE_EVENTS.token" 2>/dev/null)
                        sed "s/@TOKEN@/${tok:-NOTOKEN}/g" "$FAKE_LOGS_AFTER" 2>/dev/null
                    fi
                    return "${FAKE_LOGCAT_RC:-0}" ;;
                "shell run-as "*)
                    printf 'mirror:%s\n' "$*" >>"$FAKE_EVENTS"
                    mirror_mode="${FAKE_MIRROR_STATE:-}"
                    if [ -z "$mirror_mode" ]; then
                        if [ -n "${FAKE_PREFS_XML:-}" ]; then mirror_mode=present; else mirror_mode=absent; fi
                    fi
                    case "$*" in
                        *"__VE_MIRROR_EXISTS="*)
                            case "$mirror_mode" in
                                absent) printf '__VE_MIRROR_EXISTS=0\n'; return 0 ;;
                                unavailable) return 42 ;;
                                *) printf '__VE_MIRROR_EXISTS=1\n'; return 0 ;;
                            esac ;;
                        *)
                            case "$mirror_mode" in
                                absent) return 1 ;;
                                unavailable) return 42 ;;
                                read-failed) printf '%s' "${FAKE_PREFS_XML:-partial}"; return 41 ;;
                                empty) return 0 ;;
                                present) printf '%s' "${FAKE_PREFS_XML:-}"; return 0 ;;
                                *) return 93 ;;
                            esac ;;
                    esac ;;
                "shell su "*)
                    # #90 Vector-aware evidence: the resolver reads the LIVE zone via root.
                    printf 'root:%s\nroot-argc:%s\n' "$joined" "$dev_argc" >>"$FAKE_EVENTS"
                    root_command="$joined"
                    if [ -n "${FAKE_REQUIRE_SINGLE_ROOT_COMMAND:-}" ]; then
                        [ "$dev_argc" -eq 2 ] || return 88
                        case "$2" in
                            "su -c '"*"'") ;;
                            *) return 89 ;;
                        esac
                        sh -n -c "$2" || return 90
                        root_command=${2#"su -c '"}
                        root_command=${root_command%"'"}
                        printf 'root-remote-command:%s\n' "$root_command" >>"$FAKE_EVENTS"
                    fi
                    case "$root_command" in
                        *"find /data/misc"*)
                            printf '%s\n%s\n' "${FAKE_VECTOR_PATHS:-}" "${FAKE_PROD_VECTOR_PATH:-}"
                            return 0 ;;
                        *"ls -d /data/misc"*|*"ls -d \"/data/misc"*)
                            if [ -n "${FAKE_SU_UNAVAILABLE:-}" ]; then return 1; fi
                            if [ -n "${FAKE_PROD_VECTOR_PATH:-}" ] && [[ "$root_command" == *'/prefs/*/spoof_config.xml'* ]]; then
                                printf '%s\n%s\n' "${FAKE_VECTOR_PATHS:-}" "$FAKE_PROD_VECTOR_PATH"
                                return "${FAKE_VECTOR_RESOLVE_RC:-0}"
                            fi
                            if [ -n "${FAKE_PROD_VECTOR_PATH:-}" ] && [[ "$root_command" == *'/prefs/name.caiyao.fakegps/spoof_config.xml'* ]]; then
                                printf '%s\n' "$FAKE_PROD_VECTOR_PATH"
                                return "${FAKE_VECTOR_RESOLVE_RC:-0}"
                            fi
                            printf '%s\n' "${FAKE_VECTOR_PATHS:-}" | grep -c . >/dev/null 2>&1
                            printf '%s\n' "${FAKE_VECTOR_PATHS:-}"
                            return "${FAKE_VECTOR_RESOLVE_RC:-0}" ;;
                        *"cat /data/misc"*|*"cat \"/data/misc"*)
                            if [ -n "${FAKE_SU_UNAVAILABLE:-}" ]; then return 1; fi
                            if [ -n "${FAKE_VECTOR_READ_FAILURE:-}" ]; then return 41; fi
                            if [ -n "${FAKE_PROD_VECTOR_PATH:-}" ] && [[ "$root_command" == *"$FAKE_PROD_VECTOR_PATH"* ]]; then
                                printf '%s' "${FAKE_PROD_VECTOR_CONTENT:-}"
                                return 0
                            fi
                            printf '%s' "${FAKE_VECTOR_CONTENT:-}" ;;
                        *) return 0 ;;
                    esac ;;
                *) return 0 ;;
            esac
        }
        seed_gate_main --fixture "${FAKE_FIXTURE_ARG:-QkFTRTY0}" --digest "$DIGEST" ${EXTRA_ARGS:-}
    ) >"$outf" 2>&1
    RC=$?
    OUT="$(cat "$outf")"
}
seed_launched() { grep -q seed-launched "$EVENTS"; }
force_stops() { grep -c force-stop "$EVENTS" | tr -d ' '; }
quiesced_after_launch() { seed_launched && [ "$(force_stops)" -ge 2 ]; }

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
mkdir -p "$WORK/lock3"; write_test_lock_owner "$WORK/lock3" "$$" live-safe-token
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock3"
{ [ "$RC" -ne 0 ] && grep -q "refusing concurrent seed" <<<"$OUT" && ! seed_launched && [ -d "$WORK/lock3" ]; } &&
    report ok "g3 live-owner lock -> single-flight refusal, NO seed launched, lock untouched" ||
    report fail "g3 concurrent gate must refuse" "rc=$RC out=$OUT"
rm -rf "$WORK/lock3"

# ---- g4: device reports SEED_FAILED for THIS token -> gate fails
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_FAIL" run_gate "$WORK/lock4"
{ [ "$RC" -ne 0 ] && grep -q "reported SEED_FAILED" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g4 SEED_FAILED (this token) -> SEED_GATE_FAIL" ||
    report fail "g4 SEED_FAILED must fail the gate" "rc=$RC out=$OUT"

# ---- g5: no verdict within the window -> fail
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="" run_gate "$WORK/lock5"
{ [ "$RC" -ne 0 ] && grep -q "no token-bound" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g5 no verdict in window -> SEED_GATE_FAIL" ||
    report fail "g5 missing verdict must fail" "rc=$RC out=$OUT"

# ---- g6: SEED_LOCAL_VERIFIED WITHOUT the gap⑦ split -> NOT a pass
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_BARE" run_gate "$WORK/lock6"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g6 local-verified WITHOUT gap7 split -> not a pass" ||
    report fail "g6 bare local-verified must not pass" "rc=$RC out=$OUT"

# ---- g7 (R9): STALE success from an earlier launch, NEW launch emits nothing -> must FAIL
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_OK" FAKE_LOGS_AFTER="" run_gate "$WORK/lock7"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g7 stale success + new timeout -> SEED_GATE_FAIL (prior invocation's verdict not borrowed)" ||
    report fail "g7 stale success must not pass a new launch" "rc=$RC out=$OUT"

# ---- g8 (R9): STALE success + NEW failure -> FAIL for the new failure
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$STALE_OK" FAKE_LOGS_AFTER="$NEW_FAIL" run_gate "$WORK/lock8"
{ [ "$RC" -ne 0 ] && grep -q "reported SEED_FAILED" <<<"$OUT" && quiesced_after_launch; } &&
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
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g10 VERIFIED pair + SEED_FAILED for one token -> inconsistent -> SEED_GATE_FAIL" ||
    report fail "g10 inconsistent terminals must fail" "rc=$RC out=$OUT"

# ---- g11 (R9): verified marker echoes a DIFFERENT digest -> FAIL
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_WRONGDIGEST" run_gate "$WORK/lock11"
{ [ "$RC" -ne 0 ] && grep -qi "digest" <<<"$OUT" && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g11 marker digest != launched digest -> SEED_GATE_FAIL" ||
    report fail "g11 digest echo must be bound" "rc=$RC out=$OUT"

# ---- g12 (R9): near-miss token (prefix match) must be ignored -> FAIL (no verdict)
AWAIT_TRIES=2 FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEAR_TOKEN" run_gate "$WORK/lock12"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g12 token=<ours>x is NOT our token -> no verdict -> SEED_GATE_FAIL" ||
    report fail "g12 token match must be exact" "rc=$RC out=$OUT"

# ---- g13 (R9 P2): stale lock, owner pid DEAD, device absent -> reclaimed -> PASS
sleep 0 & DEAD=$!; wait "$DEAD" 2>/dev/null
mkdir -p "$WORK/lock13"; write_test_lock_owner "$WORK/lock13" "$DEAD" old-safe-token
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock13"
{ [ "$RC" -eq 0 ] && grep -q "RECLAIMED_STALE_LOCK" <<<"$OUT" && grep -q "SEED_GATE_PASS" <<<"$OUT"; } &&
    report ok "g13 dead-owner lock + quiescent device -> reclaimed, then PASS" ||
    report fail "g13 stale lock must be reclaimable" "rc=$RC out=$OUT"

# ---- g14 (R9 P2): stale lock, owner DEAD, but device shows a LIVE bench process -> refuse
sleep 0 & DEAD2=$!; wait "$DEAD2" 2>/dev/null
mkdir -p "$WORK/lock14"; write_test_lock_owner "$WORK/lock14" "$DEAD2" old-safe-token
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
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "handoff" <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g16 live writer at handoff -> SEED_GATE_FAIL (no PASS released over a live package)" ||
    report fail "g16 handoff must be quiescent" "rc=$RC out=$OUT"

# ---- #90 Vector-aware evidence capture (--evidence-dir). RED-first family. ----
VE_LIVE_PATH="/data/misc/vd/prefs/name.caiyao.fakegps.bench/spoof_config.xml"
VE_LIVE_XML='<?xml version="1.0"?><map><string name="json">{"marker":"live"}</string></map>'
VE_MIRROR_OLD='<?xml version="1.0"?><map><string name="json">{"marker":"stale-pre-vector"}</string></map>'
VE_MIRROR_SAME="$VE_LIVE_XML"
EVDIR=""

run_resolver_case() { # package, file, returned path, privileged resolver rc
    local package="$1" file="$2" returned_path="$3" root_rc="${4:-0}"
    local resolver_calls="$WORK/resolver-calls.$RANDOM$RANDOM"
    : >"$resolver_calls"
    RESOLVER_OUT="$(
        FAKE_PACKAGE="$package" FAKE_FILE="$file" FAKE_RETURNED_PATH="$returned_path" \
        FAKE_ROOT_RC="$root_rc" FAKE_RESOLVER_CALLS="$resolver_calls" \
        VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh" \
        sh -c '
            . "$VE_LIB_PATH"
            ve_live_root_shell() {
                printf "called\n" >>"$FAKE_RESOLVER_CALLS"
                printf "%s\n" "$FAKE_RETURNED_PATH"
                return "$FAKE_ROOT_RC"
            }
            ve_resolve_single_live_path "$FAKE_PACKAGE" "$FAKE_FILE"
        ' 2>&1
    )"
    RESOLVER_RC=$?
    RESOLVER_CALLS="$(cat "$resolver_calls")"
    rm -f "$resolver_calls"
}

run_vector_publish_race() { # public destination
    local destination="$1"
    VECTOR_RACE_OUT="$(
        FAKE_DEST="$destination" FAKE_VECTOR_PATH="$VE_LIVE_PATH" \
        FAKE_VECTOR_XML="$VE_LIVE_XML" VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh" \
        bash -c '
            set -u
            . "$VE_LIB_PATH"
            dev() { return 1; }
            ve_live_root_shell() {
                case "$1" in
                    "ls -d "*) printf "%s\n" "$FAKE_VECTOR_PATH" ;;
                    "cat "*) printf "%s" "$FAKE_VECTOR_XML" ;;
                    *) return 91 ;;
                esac
            }
            VE_DEST_CHECKS=0
            ve_path_exists() {
                local candidate="$1"
                if [ "$candidate" = "$FAKE_DEST" ]; then
                    VE_DEST_CHECKS=$((VE_DEST_CHECKS + 1))
                    if [ "$VE_DEST_CHECKS" -eq 2 ]; then
                        mkdir -p "$candidate"
                        printf "foreign-owner\n" >"$candidate/foreign-owner.txt"
                        return 1
                    fi
                fi
                [ -e "$candidate" ] || [ -L "$candidate" ]
            }
            ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$FAKE_DEST"
        ' 2>&1
    )"
    VECTOR_RACE_RC=$?
}

no_nested_stage() { # destination, basename fragment
    local destination="$1" fragment="$2"
    (
        shopt -s nullglob
        local nested=("$destination"/."$fragment".*)
        [ "${#nested[@]}" -eq 0 ]
    )
}

handoff_precedes_evidence() { # ordered EVENTS from the last run_gate
    local second_stop first_evidence
    second_stop=$(grep -n '^force-stop$' "$EVENTS" | sed -n '2s/:.*//p')
    first_evidence=$(grep -nE '^(root|mirror):' "$EVENTS" | sed -n '1s/:.*//p')
    [ -n "$second_stop" ] && [ -n "$first_evidence" ] && [ "$second_stop" -lt "$first_evidence" ]
}

# Static belt: the shipped gate must delegate the complete capture to the
# shared helper. Re-introducing a bespoke run-as or /data/misc reader here
# would split source identity and recreate #90 even if the fixtures stayed
# green.
FN_PREPARE="$(sed -n '/^prepare_evidence()/,/^}/p' "$GATE")"
{ grep -q 've_capture_evidence' <<<"$FN_PREPARE" \
  && ! grep -Eq 'run-as|shared_prefs|/data/misc' <<<"$FN_PREPARE" ; } &&
    report ok "gS evidence gate delegates capture and owns no private/live path reader" ||
    report fail "gS canonical capture must remain centralized" "prepare_evidence drifted from shared Vector helper"

# ---- g17 (#90): exactly 1 live Vector source + divergent app-private mirror -> PASS, canonical=vector-live, divergence reported
EVDIR="$WORK/ev17"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock17"
LIVE_HASH=$(shasum -a 256 "$EVDIR/vector-prefs/spoof_config.xml" 2>/dev/null | awk 'NR == 1 { print $1 }')
{ [ "$RC" -eq 0 ] && grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "VE_OK" <<<"$OUT" && grep -q "VE_DIVERGENCE" <<<"$OUT" \
  && provenance_matches "$EVDIR/vector-prefs/spoof_config.xml" \
       "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
       name.caiyao.fakegps.bench spoof_config.xml vector-live "$VE_LIVE_PATH" 1/1 true \
  && provenance_matches "$EVDIR/app-private-mirror/spoof_config.xml" \
       "$EVDIR/app-private-mirror/spoof_config.xml.provenance" \
       name.caiyao.fakegps.bench spoof_config.xml app-private-mirror \
       run-as:name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml 1/1 false \
  && provenance_matches "$EVDIR/seed-published-transport.xml" \
       "$EVDIR/seed-published-transport.xml.provenance" \
       name.caiyao.fakegps.bench seed-published-transport.xml vector-live-copy \
       "$VE_LIVE_PATH" 1/1 true "$LIVE_HASH" \
  && provenance_matches "$EVDIR/seed-published-payload.json" \
       "$EVDIR/seed-published-payload.json.provenance" \
       name.caiyao.fakegps.bench seed-published-payload.json derived-vector-live \
       "$VE_LIVE_PATH" 1/1 true "$LIVE_HASH" \
  && grep -q '"marker":"live"' "$EVDIR/vector-prefs/spoof_config.xml" \
  && ! grep -q '"marker":"stale-pre-vector"' "$EVDIR/vector-prefs/spoof_config.xml" \
  && handoff_precedes_evidence ; } &&
    report ok "g17 1 live source + divergent mirror -> PASS, canonical=vector-live, divergence reported" ||
    report fail "g17 evidence capture must use the live zone" "rc=$RC out=$OUT dir=$(ls "$EVDIR" 2>/dev/null | tr '\n' ' ')"

# ---- g18 (#90): ZERO live Vector sources -> --evidence-dir FAILS CLOSED (never falls back to the mirror)
EVDIR="$WORK/ev18"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="" FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock18"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "VE_FAIL" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g18 zero live sources -> SEED_GATE_FAIL fail-closed, no canonical emitted" ||
    report fail "g18 zero-source must fail closed" "rc=$RC out=$OUT dir=$(ls "$EVDIR/vector-prefs" 2>/dev/null | tr '\n' ' ')"

# ---- g19 (#90): MULTIPLE live Vector sources (ambiguous package zone) -> fail closed
EVDIR="$WORK/ev19"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="/data/misc/a/prefs/name.caiyao.fakegps.bench/spoof_config.xml
/data/misc/b/prefs/name.caiyao.fakegps.bench/spoof_config.xml" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock19"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "VE_FAIL" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g19 multiple live sources -> SEED_GATE_FAIL fail-closed (no silent pick)" ||
    report fail "g19 ambiguity must fail closed" "rc=$RC out=$OUT"

# ---- g20 (#90): mirror identical to live -> PASS, mirror=identical, NO divergence noise
EVDIR="$WORK/ev20"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock20"
{ [ "$RC" -eq 0 ] && grep -q "VE_OK" <<<"$OUT" && grep -q "mirror=identical" <<<"$OUT" && ! grep -q "VE_DIVERGENCE" <<<"$OUT" ; } &&
    report ok "g20 identical mirror -> PASS, mirror=identical, no divergence noise" ||
    report fail "g20 identical mirror must not warn" "rc=$RC out=$OUT"

# ---- g21 (#90): root (su) unavailable -> fail closed, never fall back to app-private
EVDIR="$WORK/ev21"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_SU_UNAVAILABLE=1 FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock21"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "VE_FAIL" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g21 su unavailable -> SEED_GATE_FAIL fail-closed (no root, no canonical)" ||
    report fail "g21 root loss must fail closed" "rc=$RC out=$OUT"

# ---- g22 (#90): mirror unreadable/absent -> live capture still canonical, mirror noted absent
EVDIR="$WORK/ev22"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock22"
{ [ "$RC" -eq 0 ] && grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "mirror=absent" <<<"$OUT" \
  && [ -s "$EVDIR/vector-prefs/spoof_config.xml" ] \
  && grep -Fqx 'mirrorAttemptedPath=run-as:name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorProbeRc=0' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorReadRc=not-attempted' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -q '__VE_MIRROR_EXISTS=' "$EVENTS" \
  && ! grep -q 'mirror:.* cat shared_prefs/' "$EVENTS" ; } &&
    report ok "g22 mirror absent -> PASS, canonical live intact, mirror=absent note" ||
    report fail "g22 missing mirror must not block canonical" "rc=$RC out=$OUT"

# ---- g23 (#90): exact source resolves but privileged cat fails -> fail closed
EVDIR="$WORK/ev23"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_READ_FAILURE=1 FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock23"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "VE_FAIL" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g23 live read failure -> SEED_GATE_FAIL with no canonical" ||
    report fail "g23 live read failure must propagate" "rc=$RC out=$OUT"

# ---- g24 (#90): explicitly requested evidence dir cannot be created -> fail closed
BLOCKER="$WORK/not-a-directory"; printf x >"$BLOCKER"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" \
EXTRA_ARGS="--evidence-dir $BLOCKER/child" run_gate "$WORK/lock24"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" && grep -q "SEED_GATE_FAIL" <<<"$OUT" \
  && [ "$(<"$BLOCKER")" = x ] && quiesced_after_launch ; } &&
    report ok "g24 unwritable evidence destination -> SEED_GATE_FAIL" ||
    report fail "g24 explicit evidence destination failure must be fatal" "rc=$RC out=$OUT"

# ---- g25 (#90): reused directory with stale canonical artifacts is refused
EVDIR="$WORK/ev25"; mkdir -p "$EVDIR/vector-prefs" "$EVDIR/app-private-mirror"
printf stale >"$EVDIR/vector-prefs/spoof_config.xml"
printf stale >"$EVDIR/vector-prefs/spoof_config.xml.provenance"
printf stale >"$EVDIR/app-private-mirror/spoof_config.xml"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="" FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock25"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && grep -qE "not fresh|already exists|refus" <<<"$OUT" \
  && [ "$(<"$EVDIR/vector-prefs/spoof_config.xml")" = stale ] \
  && [ "$(<"$EVDIR/vector-prefs/spoof_config.xml.provenance")" = stale ] \
  && [ "$(<"$EVDIR/app-private-mirror/spoof_config.xml")" = stale ] && quiesced_after_launch ; } &&
    report ok "g25 reused destination is refused before stale evidence can be mistaken for this run" ||
    report fail "g25 stale destination must fail loudly" "rc=$RC out=$OUT"

# ---- g26 (#90): mirror from a prior run cannot survive a new mirror-absent run
EVDIR="$WORK/ev26"; mkdir -p "$EVDIR/app-private-mirror"
printf stale >"$EVDIR/app-private-mirror/spoof_config.xml"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock26"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && grep -qE "not fresh|already exists|refus" <<<"$OUT" \
  && [ "$(<"$EVDIR/app-private-mirror/spoof_config.xml")" = stale ] && quiesced_after_launch ; } &&
    report ok "g26 prior mirror makes destination non-fresh and is refused" ||
    report fail "g26 stale mirror must never coexist with mirror=absent" "rc=$RC out=$OUT"

# ---- g27 (#90): empty live bytes are a failed read, not canonical evidence
EVDIR="$WORK/ev27"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="" FAKE_PREFS_XML="$VE_MIRROR_OLD" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock27"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g27 empty live bytes -> SEED_GATE_FAIL" ||
    report fail "g27 empty live capture must fail" "rc=$RC out=$OUT"

# ---- g28 (#90): hash failure prevents VE_OK/canonical publication
EVDIR="$WORK/ev28"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
FAKE_HASH_FAILURE=1 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock28"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|VE_OK" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g28 hash failure -> SEED_GATE_FAIL" ||
    report fail "g28 missing artifact hash must be fatal" "rc=$RC out=$OUT"

# ---- g29 (#90): provenance write failure prevents publication
EVDIR="$WORK/ev29"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
FAKE_PROVENANCE_WRITE_FAILURE=1 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock29"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|VE_OK" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g29 provenance write failure -> SEED_GATE_FAIL" ||
    report fail "g29 provenance write failure must be fatal" "rc=$RC out=$OUT"

# ---- g30 (#90): compatibility-copy failure is fatal
EVDIR="$WORK/ev30"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
FAKE_COPY_FAILURE=1 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock30"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g30 compatibility-copy failure -> SEED_GATE_FAIL" ||
    report fail "g30 compatibility copy must be checked" "rc=$RC out=$OUT"

# ---- g31 (#90): nonempty XML without canonical JSON is not valid derived evidence
EVDIR="$WORK/ev31"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT='<map><string name="other">x</string></map>' FAKE_PREFS_XML="" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock31"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch ; } &&
    report ok "g31 missing canonical JSON payload -> SEED_GATE_FAIL" ||
    report fail "g31 empty derived payload must be fatal" "rc=$RC out=$OUT"

# ---- g32 (#90): a dangling symlink is still a pre-existing destination
EVDIR="$WORK/ev32"; ln -s "$WORK/missing-historical-target" "$EVDIR"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="" \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock32"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS" <<<"$OUT" \
  && [ -L "$EVDIR" ] && [ "$(readlink "$EVDIR")" = "$WORK/missing-historical-target" ] && quiesced_after_launch ; } &&
    report ok "g32 dangling historical destination is preserved and refused" ||
    report fail "g32 broken symlink must not be treated as fresh" "rc=$RC out=$OUT"

# ---- g33 (#90 review): resolver output is not evidence when root transport fails after output
run_resolver_case name.caiyao.fakegps.bench spoof_config.xml "$VE_LIVE_PATH" 42
{ [ "$RESOLVER_RC" -ne 0 ] && ! grep -Fqx "$VE_LIVE_PATH" <<<"$RESOLVER_OUT"; } &&
    report ok "g33 resolver preserves privileged producer rc after mixed output" ||
    report fail "g33 resolver must not let trailing normalization swallow rc=42" "rc=$RESOLVER_RC out=$RESOLVER_OUT"

# ---- g34-g38 (#90 review): exact coordinates reject dot segments and non-package ids
run_resolver_case . spoof_config.xml '/data/misc/vd/prefs/./spoof_config.xml'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g34 package '.' is rejected" ||
    report fail "g34 dot package must fail before resolver trust" "rc=$RESOLVER_RC out=$RESOLVER_OUT"
run_resolver_case .. spoof_config.xml '/data/misc/vd/prefs/../spoof_config.xml'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g35 package '..' is rejected" ||
    report fail "g35 parent package must fail before resolver trust" "rc=$RESOLVER_RC out=$RESOLVER_OUT"
run_resolver_case name.caiyao.fakegps.bench . '/data/misc/vd/prefs/name.caiyao.fakegps.bench/.'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g36 filename '.' is rejected" ||
    report fail "g36 dot filename must fail before resolver trust" "rc=$RESOLVER_RC out=$RESOLVER_OUT"
run_resolver_case name.caiyao.fakegps.bench .. '/data/misc/vd/prefs/name.caiyao.fakegps.bench/..'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g37 filename '..' is rejected" ||
    report fail "g37 parent filename must fail before resolver trust" "rc=$RESOLVER_RC out=$RESOLVER_OUT"
run_resolver_case bench spoof_config.xml '/data/misc/vd/prefs/bench/spoof_config.xml'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g38 non-package application id is rejected" ||
    report fail "g38 package grammar must require dot-separated Android identifiers" "rc=$RESOLVER_RC out=$RESOLVER_OUT"
run_resolver_case name.caiyao.fakegps.bench spoof_config.xml '/data/misc/../prefs/name.caiyao.fakegps.bench/spoof_config.xml'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -n "$RESOLVER_CALLS" ]; } && report ok "g38b returned live-zone '..' is rejected" ||
    report fail "g38b normalized escape in resolver output must fail" "rc=$RESOLVER_RC out=$RESOLVER_OUT calls=$RESOLVER_CALLS"
run_resolver_case name..fakegps spoof_config.xml '/data/misc/vd/prefs/name..fakegps/spoof_config.xml'
{ [ "$RESOLVER_RC" -ne 0 ] && [ -z "$RESOLVER_CALLS" ]; } && report ok "g38c empty package segment is rejected before root" ||
    report fail "g38c package grammar must reject adjacent dots" "rc=$RESOLVER_RC out=$RESOLVER_OUT calls=$RESOLVER_CALLS"

# ---- g39 (#90 review): a valid-looking hash line followed by shasum rc=42 is failure
HASH_SHIM="$WORK/hash-shim"; mkdir -p "$HASH_SHIM"
printf '%s\n' '#!/bin/sh' \
    'printf "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  %s\\n" "$3"' \
    'exit 42' >"$HASH_SHIM/shasum"
chmod +x "$HASH_SHIM/shasum"
printf 'artifact\n' >"$WORK/hash-input"
HASH_OUT="$(PATH="$HASH_SHIM:$PATH" VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh" \
    sh -c '. "$VE_LIB_PATH"; ve_hash_file "$1"' sh "$WORK/hash-input" 2>&1)"
HASH_RC=$?
[ "$HASH_RC" -ne 0 ] && report ok "g39 ve_hash_file preserves shasum producer rc=42" ||
    report fail "g39 hash parser must not bless output from a failed producer" "rc=$HASH_RC out=$HASH_OUT"

HASH_DUP_SHIM="$WORK/hash-duplicate-shim"; mkdir -p "$HASH_DUP_SHIM"
printf '%s\n' '#!/bin/sh' \
    'printf "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  first\\nbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  second\\n"' \
    'exit 0' >"$HASH_DUP_SHIM/shasum"
chmod +x "$HASH_DUP_SHIM/shasum"
HASH_OUT="$(PATH="$HASH_DUP_SHIM:$PATH" VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh" \
    sh -c '. "$VE_LIB_PATH"; ve_hash_file "$1"' sh "$WORK/hash-input" 2>&1)"
HASH_RC=$?
[ "$HASH_RC" -ne 0 ] && report ok "g39b ve_hash_file requires exactly one hash record" ||
    report fail "g39b multiple hash records are ambiguous" "rc=$HASH_RC out=$HASH_OUT"

# ---- g40 (#90 review): two canonical JSON rows are ambiguous and fatal
EVDIR="$WORK/ev40"
DUP_JSON_XML='<map><string name="json">{"row":1}</string><string name="json">{"row":2}</string></map>'
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$DUP_JSON_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock40"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|SEED_GATE_EVIDENCE" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && [ "$(force_stops)" -ge 2 ]; } &&
    report ok "g40 duplicate canonical JSON keys -> fail closed after quiescent handoff" ||
    report fail "g40 derived payload cardinality must be exactly 1" "rc=$RC force-stops=$(force_stops) out=$OUT"

# ---- g41 (#90 review): one nonempty row that is not JSON is fatal
EVDIR="$WORK/ev41"
BAD_JSON_XML='<map><string name="json">definitely-not-json</string></map>'
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$BAD_JSON_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock41"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|SEED_GATE_EVIDENCE" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && [ "$(force_stops)" -ge 2 ]; } &&
    report ok "g41 malformed canonical JSON -> fail closed after quiescent handoff" ||
    report fail "g41 derived payload must be JSON-parsed" "rc=$RC force-stops=$(force_stops) out=$OUT"

# ---- g42 (#90 review): parser output followed by parser failure is never published
JSON_SHIM="$WORK/json-parser-fails-after-output"
printf '%s\n' '#!/bin/sh' 'printf "{\\"shim\\":true}\\n" >"$3"' 'exit 42' >"$JSON_SHIM"
chmod +x "$JSON_SHIM"
EVDIR="$WORK/ev42"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
SEED_JSON_PYTHON="$JSON_SHIM" EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock42"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|SEED_GATE_EVIDENCE" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && [ "$(force_stops)" -ge 2 ]; } &&
    report ok "g42 failed JSON parser cannot publish its partial output" ||
    report fail "g42 parser status must be preserved" "rc=$RC force-stops=$(force_stops) out=$OUT"

# ---- g43 (#90 review): Vector public-directory race cannot nest stage or emit VE_OK
VECTOR_RACE_DEST="$WORK/ev43"
run_vector_publish_race "$VECTOR_RACE_DEST"
{ [ "$VECTOR_RACE_RC" -ne 0 ] && ! grep -q 'VE_OK' <<<"$VECTOR_RACE_OUT" \
  && [ "$(<"$VECTOR_RACE_DEST/foreign-owner.txt")" = foreign-owner ] \
  && [ ! -e "$VECTOR_RACE_DEST/vector-prefs" ] \
  && no_nested_stage "$VECTOR_RACE_DEST" 'ev43.vector-evidence'; } &&
    report ok "g43 Vector create-only publication loses race safely" ||
    report fail "g43 Vector publish must use atomic no-replace" "rc=$VECTOR_RACE_RC out=$VECTOR_RACE_OUT tree=$(find "$VECTOR_RACE_DEST" -maxdepth 2 -print 2>/dev/null | tr '\n' ' ')"

# ---- g44 (#90 review): outer bundle race cannot nest stage or emit success markers
EVDIR="$WORK/ev44"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
FAKE_OUTER_PUBLISH_RACE=1 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock44"
{ [ "$RC" -ne 0 ] && ! grep -q "VE_OK\|SEED_GATE_EVIDENCE\|SEED_GATE_PASS" <<<"$OUT" \
  && [ "$(<"$EVDIR/foreign-owner.txt")" = foreign-owner ] \
  && [ ! -e "$EVDIR/vector-prefs" ] && no_nested_stage "$EVDIR" 'ev44.seed-evidence' \
  && [ "$(force_stops)" -ge 2 ]; } &&
    report ok "g44 outer create-only publication loses race safely" ||
    report fail "g44 outer publish must be atomic and defer all success markers" "rc=$RC force-stops=$(force_stops) out=$OUT tree=$(find "$EVDIR" -maxdepth 2 -print 2>/dev/null | tr '\n' ' ')"

# ---- g45-g46 (#90 criterion 8): prod+bench coexist through --evidence-dir, exact bench only
PROD_LIVE_PATH='/data/misc/prod-zone/prefs/name.caiyao.fakegps/spoof_config.xml'
COEXIST_BENCH_XML='<map><string name="json">{"source":"bench"}</string></map>'
COEXIST_PROD_DIFFERENT='<map><string name="json">{"source":"production"}</string></map>'
EVDIR="$WORK/ev45"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$COEXIST_BENCH_XML" \
FAKE_PROD_VECTOR_PATH="$PROD_LIVE_PATH" FAKE_PROD_VECTOR_CONTENT="$COEXIST_BENCH_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock45"
{ [ "$RC" -eq 0 ] && grep -Fq "remotePath=$VE_LIVE_PATH" "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && [ "$(grep -c '^root:' "$EVENTS")" -eq 2 ] \
  && grep -Fq "$VE_LIVE_PATH" "$EVENTS" && ! grep -Fq "$PROD_LIVE_PATH" "$EVENTS" \
  && ! grep -Fq 'find /data/misc' "$EVENTS"; } &&
    report ok "g45 coexisting prod+bench identical payloads retain exact bench path" ||
    report fail "g45 identical payloads must not collapse package identity" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$EVENTS")"

EVDIR="$WORK/ev46"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$COEXIST_BENCH_XML" \
FAKE_PROD_VECTOR_PATH="$PROD_LIVE_PATH" FAKE_PROD_VECTOR_CONTENT="$COEXIST_PROD_DIFFERENT" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock46"
{ [ "$RC" -eq 0 ] && grep -Fq '"source":"bench"' "$EVDIR/seed-published-payload.json" \
  && ! grep -Fq 'production' "$EVDIR/seed-published-payload.json" \
  && [ "$(grep -c '^root:' "$EVENTS")" -eq 2 ] \
  && grep -Fq "$VE_LIVE_PATH" "$EVENTS" && ! grep -Fq "$PROD_LIVE_PATH" "$EVENTS" \
  && ! grep -Fq 'find /data/misc' "$EVENTS"; } &&
    report ok "g46 coexisting divergent prod+bench payloads publish bench only" ||
    report fail "g46 divergent production payload must never be read" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$EVENTS")"

# ---- g47-g49 (#90 review P2): optional mirror states preserve error truth
EVDIR="$WORK/ev47"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=unavailable \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock47"
{ [ "$RC" -eq 0 ] && grep -q 'mirror=unavailable' <<<"$OUT" \
  && grep -Fqx 'mirror=unavailable' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorAttemptedPath=run-as:name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorProbeRc=42' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorReadRc=not-attempted' "$EVDIR/vector-prefs/spoof_config.xml.provenance"; } &&
    report ok "g47 mirror probe failure remains unavailable, not absent" ||
    report fail "g47 optional mirror transport error must stay auditable" "rc=$RC out=$OUT"

EVDIR="$WORK/ev48"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML=partial FAKE_MIRROR_STATE=read-failed \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock48"
{ [ "$RC" -eq 0 ] && grep -q 'mirror=read-failed' <<<"$OUT" \
  && grep -Fqx 'mirrorAttemptedPath=run-as:name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorProbeRc=0' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorReadRc=41' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && [ ! -e "$EVDIR/app-private-mirror/spoof_config.xml" ]; } &&
    report ok "g48 existing mirror read failure is distinct from absence" ||
    report fail "g48 mirror read error must stay auditable" "rc=$RC out=$OUT"

EVDIR="$WORK/ev49"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=empty \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock49"
{ [ "$RC" -eq 0 ] && grep -q 'mirror=empty' <<<"$OUT" \
  && grep -Fqx 'mirrorAttemptedPath=run-as:name.caiyao.fakegps.bench/shared_prefs/spoof_config.xml' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorProbeRc=0' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && grep -Fqx 'mirrorReadRc=0' "$EVDIR/vector-prefs/spoof_config.xml.provenance" \
  && [ ! -e "$EVDIR/app-private-mirror/spoof_config.xml" ]; } &&
    report ok "g49 existing zero-byte mirror is empty, not absent" ||
    report fail "g49 empty mirror must stay auditable" "rc=$RC out=$OUT"

# ---- g50-g51 (#90 review): XML entities decode exactly once before JSON validation
EVDIR="$WORK/ev50"
NUMERIC_ENTITY_XML='<map><string name="json">{&quot;n&quot;:&#49;}</string></map>'
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$NUMERIC_ENTITY_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock50"
{ [ "$RC" -eq 0 ] && grep -Fqx '{"n":1}' "$EVDIR/seed-published-payload.json"; } &&
    report ok "g50 XML parser decodes numeric and named entities once into valid JSON" ||
    report fail "g50 extraction must use XML semantics, not a four-entity sed table" "rc=$RC out=$OUT"

EVDIR="$WORK/ev51"
DOUBLE_ENTITY_XML='<map><string name="json">{&amp;quot;n&amp;quot;:1}</string></map>'
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$DOUBLE_ENTITY_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock51"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|SEED_GATE_EVIDENCE" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch; } &&
    report ok "g51 XML entities are not recursively decoded into synthetic JSON" ||
    report fail "g51 entity decoding must occur exactly once" "rc=$RC out=$OUT"

# ---- g52 (#90 review): malformed XML cannot pass via a regex-looking substring
EVDIR="$WORK/ev52"
MALFORMED_XML='<map><string name="json">{"looks":"valid"}</string>'
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$MALFORMED_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock52"
{ [ "$RC" -ne 0 ] && ! grep -q "SEED_GATE_PASS\|SEED_GATE_EVIDENCE" <<<"$OUT" \
  && no_evidence_residue "$EVDIR" && quiesced_after_launch; } &&
    report ok "g52 malformed XML with a regex-looking JSON row fails closed" ||
    report fail "g52 canonical extraction must parse XML structure" "rc=$RC out=$OUT"

# ---- g53 (#90 review): failed handoff performs no post-verdict evidence read
EVDIR="$WORK/ev53"
FAKE_PID_MODE=absent FAKE_PID_MODE_AFTER_LAUNCH=alive \
FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock53"
{ [ "$RC" -ne 0 ] && [ "$(force_stops)" -ge 2 ] \
  && ! grep -qE '^(root|mirror):' "$EVENTS" \
  && ! grep -q "VE_OK\|SEED_GATE_EVIDENCE\|SEED_GATE_PASS" <<<"$OUT" \
  && no_evidence_residue "$EVDIR"; } &&
    report ok "g53 failed handoff blocks all evidence reads and success markers" ||
    report fail "g53 evidence must be sampled only after quiescent handoff" "rc=$RC force-stops=$(force_stops) out=$OUT events=$(tr '\n' ' ' <"$EVENTS")"

# ---- g54-g56 (terminal review): PID transport status outranks plausible stdout
FAKE_PID_MODE=transport-absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock54"
{ [ "$RC" -ne 0 ] && grep -qi 'probe' <<<"$OUT" && ! seed_launched; } &&
    report ok "g54 adb rc=42 + plausible remote absent marker -> fail closed" ||
    report fail "g54 PID transport rc must be checked before remote status" "rc=$RC out=$OUT"

FAKE_PID_MODE=transport-alive FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock55"
{ [ "$RC" -ne 0 ] && grep -qi 'probe' <<<"$OUT" && ! seed_launched; } &&
    report ok "g55 adb rc=42 + plausible remote alive marker -> fail closed" ||
    report fail "g55 plausible PID stdout cannot erase transport failure" "rc=$RC out=$OUT"

FAKE_PID_MODE=duplicate-status FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock56"
{ [ "$RC" -ne 0 ] && grep -qi 'probe' <<<"$OUT" && ! seed_launched; } &&
    report ok "g56 duplicate remote PID statuses are ambiguous and fail closed" ||
    report fail "g56 PID probe must require exactly one remote status" "rc=$RC out=$OUT"

# ---- g57-g59 (terminal review): verdict producer/parser errors cannot bless output
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_LOGCAT_RC=42 \
run_gate "$WORK/lock57"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g57 logcat rc=42 + valid terminal pair -> fail closed" ||
    report fail "g57 verdict must preserve log producer status" "rc=$RC out=$OUT"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_TOKEN_PARSER_RC=42 \
run_gate "$WORK/lock58"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g58 token parser rc=42 + plausible selected lines -> fail closed" ||
    report fail "g58 token parser status must be preserved" "rc=$RC out=$OUT"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_COUNT_PARSER_RC=42 \
run_gate "$WORK/lock59"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g59 marker parser rc=42 + plausible counts -> fail closed" ||
    report fail "g59 marker parser status must be preserved" "rc=$RC out=$OUT"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_DIGEST_PARSER_RC=42 \
run_gate "$WORK/lock59b"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g59b digest parser rc=42 + plausible match count -> fail closed" ||
    report fail "g59b digest parser status must be preserved" "rc=$RC out=$OUT"

# ---- g60-g61: other command-substitution authorities preserve their status
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_NEW_TOKEN_RC=42 \
run_gate "$WORK/lock60"
{ [ "$RC" -ne 0 ] && ! seed_launched && ! grep -q 'SEED_GATE_PASS' <<<"$OUT"; } &&
    report ok "g60 token producer rc=42 + valid-looking token -> fail closed" ||
    report fail "g60 launch token producer status must be authoritative" "rc=$RC out=$OUT"

for fixture_case in 'QkFTRTY0;echo-INJECTED' 'Qk FT TY0' "QkFTRTY0'quoted" 'Zh==' 'Zg==='; do
    case "$fixture_case" in
        *';'*) case_no=60a ;;
        *' '*) case_no=60b ;;
        *"'"*) case_no=60c ;;
        Zh==) case_no=60d ;;
        *) case_no=60e ;;
    esac
    FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
    FAKE_FIXTURE_ARG="$fixture_case" run_gate "$WORK/lock${case_no}"
    { [ "$RC" -ne 0 ] && ! seed_launched && ! grep -q 'SEED_GATE_PASS' <<<"$OUT"; } &&
        report ok "g${case_no} unsafe/noncanonical fixture base64 is rejected before adb shell" ||
        report fail "g${case_no} fixture must be canonical standard base64" "fixture=$fixture_case rc=$RC out=$OUT"
done

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_FIXTURE_ARG=Zg== \
run_gate "$WORK/lock60f"
{ [ "$RC" -eq 0 ] && seed_launched && grep -q 'SEED_GATE_PASS' <<<"$OUT"; } &&
    report ok "g60f canonical padded standard base64 remains accepted" ||
    report fail "g60f strict fixture validation must retain canonical padding" "rc=$RC out=$OUT"

sleep 0 & DEAD3=$!; wait "$DEAD3" 2>/dev/null
mkdir -p "$WORK/lock61"
printf 'pid=%s\nstarted=old\nhost=selftest\ntoken=old\n' "$DEAD3" >"$WORK/lock61/owner"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_LOCK_OWNER_VALUE="$DEAD3" FAKE_LOCK_OWNER_PARSER_RC=42 run_gate "$WORK/lock61"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ -d "$WORK/lock61" ]; } &&
    report ok "g61 lock-owner parser rc=42 + plausible dead PID -> refuse reclaim" ||
    report fail "g61 lock owner parser status must be authoritative" "rc=$RC out=$OUT"
rm -rf "$WORK/lock61"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_LOCK_OWNER_WRITE_RC=42 \
run_gate "$WORK/lock61b"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ ! -e "$WORK/lock61b" ]; } &&
    report ok "g61b fresh-lock owner write failure aborts and removes ownerless lock" ||
    report fail "g61b lock acquisition includes durable owner record" "rc=$RC out=$OUT"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_LOCK_OWNER_WRITE_RC=42 FAKE_LOCK_OWNER_WRITE_FOREIGN=1 run_gate "$WORK/lock61c"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ -d "$WORK/lock61c" ] \
  && grep -q 'could not remove ownerless lock' <<<"$OUT"; } &&
    report ok "g61c owner write + cleanup failure stays red with explicit residue diagnostic" ||
    report fail "g61c lock setup cleanup failure must remain visible" "rc=$RC out=$OUT"
rm -rf "$WORK/lock61c"

mkdir -p "$WORK/lock61d"
printf 'pid=not-a-positive-integer\nstarted=old\nhost=selftest\ntoken=old\npackage=name.caiyao.fakegps.bench\n' >"$WORK/lock61d/owner"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock61d"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ -f "$WORK/lock61d/owner" ]; } &&
    report ok "g61d malformed owner PID is never treated as a dead reclaimable process" ||
    report fail "g61d owner PID must be a positive integer" "rc=$RC out=$OUT"
rm -rf "$WORK/lock61d"

sleep 0 & DEAD4=$!; wait "$DEAD4" 2>/dev/null
mkdir -p "$WORK/lock61e"
write_test_lock_owner "$WORK/lock61e" "$DEAD4" old-safe-token
printf 'foreign-do-not-delete\n' >"$WORK/lock61e/foreign-sentinel"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock61e"
{ [ "$RC" -ne 0 ] && ! seed_launched \
  && [ "$(<"$WORK/lock61e/foreign-sentinel")" = foreign-do-not-delete ] \
  && [ -f "$WORK/lock61e/owner" ]; } &&
    report ok "g61e stale lock with foreign entry is refused without deleting anything" ||
    report fail "g61e stale reclaim must never recursively delete an arbitrary tree" "rc=$RC out=$OUT"
rm -rf "$WORK/lock61e"

sleep 0 & DEAD5=$!; wait "$DEAD5" 2>/dev/null
mkdir -p "$WORK/lock61f"
printf 'pid=%s\nstarted=old\nhost=selftest\ntoken=old-safe-token\npackage=name.caiyao.fakegps.bench\n' "$DEAD5" >"$WORK/lock61f/owner"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" run_gate "$WORK/lock61f"
{ [ "$RC" -ne 0 ] && ! seed_launched && [ -f "$WORK/lock61f/owner" ]; } &&
    report ok "g61f legacy owner record without magic is not auto-reclaimed" ||
    report fail "g61f unknown lock schema must remain untouched" "rc=$RC out=$OUT"
rm -rf "$WORK/lock61f"

PROD_LOCK_OVERRIDE_OUT="$(SEED_GATE_LOCK_DIR="$WORK/arbitrary-production-dir" PATH="$SHIM:$PATH" \
    "$GATE" --help 2>&1)"
PROD_LOCK_OVERRIDE_RC=$?
{ [ "$PROD_LOCK_OVERRIDE_RC" -ne 0 ] && [ ! -e "$WORK/arbitrary-production-dir" ]; } &&
    report ok "g61g production execution rejects external lock-directory override" ||
    report fail "g61g arbitrary lock paths are selftest-only" "rc=$PROD_LOCK_OVERRIDE_RC out=$PROD_LOCK_OVERRIDE_OUT"

# ---- g62 (ADB argv joining): root command must survive remote shell reparsing
EVDIR="$WORK/ev62"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
FAKE_REQUIRE_SINGLE_ROOT_COMMAND=1 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock62"
{ [ "$RC" -eq 0 ] && [ "$(grep -c '^root-argc:2$' "$EVENTS")" -eq 2 ] \
  && grep -Fq "root-remote-command:ls -d /data/misc/*/prefs/name.caiyao.fakegps.bench/spoof_config.xml" "$EVENTS" \
  && grep -Fq "root-remote-command:cat $VE_LIVE_PATH" "$EVENTS"; } &&
    report ok "g62 adb Join + remote reparse preserves one complete su -c command" ||
    report fail "g62 root seam must pass one remotely quoted command string after shell" "rc=$RC out=$OUT events=$(tr '\n' ' ' <"$EVENTS")"

# ---- g63-g65: outer evidence inventory is exact and mirror-state consistent
for residue_case in unknown inner-lock missing-mirror; do
    case "$residue_case" in
        unknown) case_no=63 ;;
        inner-lock) case_no=64 ;;
        missing-mirror) case_no=65 ;;
    esac
    EVDIR="$WORK/ev${case_no}"
    FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
    FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
    FAKE_CAPTURE_RESIDUE="$residue_case" EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock${case_no}"
    { [ "$RC" -ne 0 ] && ! grep -q 'VE_OK\|SEED_GATE_EVIDENCE\|SEED_GATE_PASS' <<<"$OUT" \
      && no_evidence_residue "$EVDIR"; } &&
        report ok "g${case_no} staged evidence residue/state mismatch '$residue_case' blocks publication" ||
        report fail "g${case_no} outer stage must match exact inventory" "rc=$RC out=$OUT tree=$(find "$EVDIR" -maxdepth 4 -print 2>/dev/null | tr '\n' ' ')"
done

EVDIR="$WORK/ev65b"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_PREFS_XML="$VE_MIRROR_SAME" \
FAKE_CAPTURE_RESIDUE=unknown FAKE_STAGE_CLEANUP_RC=44 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock65b"
{ [ "$RC" -ne 0 ] && [ ! -e "$EVDIR" ] && ! grep -q 'VE_OK\|SEED_GATE_EVIDENCE\|SEED_GATE_PASS' <<<"$OUT" \
  && grep -q '^stage-cleanup-attempt:' "$EVENTS"; } &&
    report ok "g65b inventory failure + staging cleanup failure remains non-consumable and red" ||
    report fail "g65b artifact cleanup failure must not publish or pass" "rc=$RC out=$OUT events=$(tr '\n' ' ' <"$EVENTS")"

# ---- g66-g68: lock cleanup is a pre-publication/pre-PASS transaction gate
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_RELEASE_LOCK_RC=42 \
run_gate "$WORK/lock66"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" \
  && ! grep -q '^outer-publish-attempt$' "$EVENTS" && [ -d "$WORK/lock66" ]; } &&
    report ok "g66 no-evidence success cannot PASS when primary lock cleanup fails" ||
    report fail "g66 lock cleanup is part of success" "rc=$RC out=$OUT events=$(tr '\n' ' ' <"$EVENTS")"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_RELEASE_LOCK_LEAVES_PATH=1 \
run_gate "$WORK/lock66b"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && [ -d "$WORK/lock66b" ] \
  && grep -q 'reported success but the lock still exists' <<<"$OUT"; } &&
    report ok "g66b cleanup rc=0 cannot PASS while lock postcondition is false" ||
    report fail "g66b lock absence postcondition is authoritative" "rc=$RC out=$OUT"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" FAKE_LOCK_FOREIGN_AFTER_LAUNCH=1 \
run_gate "$WORK/lock66c"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" \
  && [ "$(<"$WORK/lock66c/foreign-sentinel")" = foreign-do-not-delete ] \
  && [ -f "$WORK/lock66c/owner" ]; } &&
    report ok "g66c owned lock polluted with foreign entry fails without recursive deletion" ||
    report fail "g66c release may remove only the exact owned owner file + empty directory" "rc=$RC out=$OUT"
rm -rf "$WORK/lock66c"

EVDIR="$WORK/ev67"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
FAKE_RELEASE_LOCK_RC=42 EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock67"
{ [ "$RC" -ne 0 ] && [ ! -e "$EVDIR" ] && [ ! -L "$EVDIR" ] \
  && ! grep -q 'VE_OK\|SEED_GATE_EVIDENCE\|SEED_GATE_PASS' <<<"$OUT" \
  && grep -q 'SEED_GATE_UNPUBLISHED_STAGE.*not consumable evidence' <<<"$OUT" \
  && compgen -G "$WORK/.ev67.seed-evidence.*" >/dev/null \
  && ! grep -q '^outer-publish-attempt$' "$EVENTS" && [ -d "$WORK/lock67" ]; } &&
    report ok "g67 lock cleanup failure leaves no consumable final evidence or success marker" ||
    report fail "g67 lock must release before atomic final publication" "rc=$RC out=$OUT events=$(tr '\n' ' ' <"$EVENTS")"

FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_FAIL" FAKE_RELEASE_LOCK_RC=42 \
run_gate "$WORK/lock68"
{ [ "$RC" -ne 0 ] && ! grep -q 'SEED_GATE_PASS' <<<"$OUT" && quiesced_after_launch; } &&
    report ok "g68 pre-existing verdict failure remains nonzero when cleanup also fails" ||
    report fail "g68 cleanup failure must never turn a primary failure green" "rc=$RC out=$OUT"

# ---- g69: frozen stage precedes lock release; final publish/success follow it
EVDIR="$WORK/ev69"
FAKE_PID_MODE=absent FAKE_LOGS_BEFORE="$NOISE" FAKE_LOGS_AFTER="$NEW_OK" \
FAKE_VECTOR_PATHS="$VE_LIVE_PATH" FAKE_VECTOR_CONTENT="$VE_LIVE_XML" FAKE_MIRROR_STATE=absent \
EXTRA_ARGS="--evidence-dir $EVDIR" run_gate "$WORK/lock69"
LAST_DEVICE_LINE=$(grep -nE '^(root|mirror):' "$EVENTS" | tail -1 | sed 's/:.*//')
RELEASE_LINE=$(grep -n '^lock-release-attempt$' "$EVENTS" | head -1 | sed 's/:.*//')
PUBLISH_LINE=$(grep -n '^outer-publish-attempt$' "$EVENTS" | head -1 | sed 's/:.*//')
SUCCESS_LINE=$(grep -n '^capture-success-marker$' "$EVENTS" | head -1 | sed 's/:.*//')
{ [ "$RC" -eq 0 ] && [ -n "$LAST_DEVICE_LINE" ] && [ -n "$RELEASE_LINE" ] \
  && [ -n "$PUBLISH_LINE" ] && [ -n "$SUCCESS_LINE" ] \
  && [ "$LAST_DEVICE_LINE" -lt "$RELEASE_LINE" ] && [ "$RELEASE_LINE" -lt "$PUBLISH_LINE" ] \
  && [ "$PUBLISH_LINE" -lt "$SUCCESS_LINE" ] && [ ! -e "$WORK/lock69" ]; } &&
    report ok "g69 state order: frozen capture -> lock absent -> atomic final publish -> success" ||
    report fail "g69 transaction state ordering drift" "rc=$RC lines=$LAST_DEVICE_LINE/$RELEASE_LINE/$PUBLISH_LINE/$SUCCESS_LINE events=$(tr '\n' ' ' <"$EVENTS")"

# ---- g70-g80: strict byte/encoding envelope before XML/DTD parsing
python3 - "$WORK" <<'PY'
import codecs
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
plain = '<?xml version="1.0" encoding="UTF-8"?><map><string name="json">{"ok":true}</string></map>'
entity = '<?xml version="1.0"?><!DOCTYPE map [<!ENTITY x "{&quot;a&quot;:1}">]><map><string name="json">&x;</string></map>'
(root / 'xml-utf8.xml').write_bytes(plain.encode('utf-8'))
(root / 'xml-utf8-bom.xml').write_bytes(codecs.BOM_UTF8 + plain.encode('utf-8'))
(root / 'xml-utf16-dtd.xml').write_bytes(entity.encode('utf-16'))
(root / 'xml-utf32-dtd.xml').write_bytes(entity.encode('utf-32'))
(root / 'xml-utf16-plain.xml').write_bytes(plain.replace('UTF-8', 'UTF-16').encode('utf-16'))
(root / 'xml-utf32-plain.xml').write_bytes(plain.replace('UTF-8', 'UTF-32').encode('utf-32'))
(root / 'xml-iso-ascii.xml').write_bytes(plain.replace('UTF-8', 'ISO-8859-1').encode('ascii'))
latin = '<?xml version="1.0" encoding="ISO-8859-1"?><map><string name="json">{"label":"café"}</string></map>'
(root / 'xml-latin1.xml').write_bytes(latin.encode('latin-1'))
(root / 'xml-nul.xml').write_bytes(b'<map><string name="json">{"a":1}</string>\x00</map>')
(root / 'xml-invalid-utf8.xml').write_bytes(b'<map><string name="json">{"a":"\xff"}</string></map>')
conflict = plain.replace('UTF-8', 'UTF-16')
(root / 'xml-conflict.xml').write_bytes(conflict.encode('utf-8'))
(root / 'xml-utf8-dtd.xml').write_bytes(entity.encode('utf-8'))
(root / 'json-finite-exponent.xml').write_text(
    '<map><string name="json">{"finite":1e308}</string></map>',
    encoding='utf-8',
)
(root / 'json-overflow-exponent.xml').write_text(
    '<map><string name="json">{"overflow":1e999}</string></map>',
    encoding='utf-8',
)
(root / 'json-nested-negative-overflow.xml').write_text(
    '<map><string name="json">{"outer":[{"overflow":-1e999}]}</string></map>',
    encoding='utf-8',
)
PY

run_extractor_case() { # input -> EXTRACT_RC / EXTRACT_OUT / EXTRACT_FILE
    local input="$1" outf="$WORK/extracted.$RANDOM$RANDOM.json" logf="$WORK/extract-log.$RANDOM$RANDOM"
    (
        export SEED_GATE_SOURCE_ONLY=1
        export VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        export PATH="$SHIM:$PATH"
        export FAKE_ADB_SENTINEL="$ADB_SENTINEL"
        # shellcheck disable=SC1090
        . "$GATE"
        extract_single_json_payload "$input" "$outf"
    ) >"$logf" 2>&1
    EXTRACT_RC=$?
    EXTRACT_OUT="$(cat "$logf")"
    EXTRACT_FILE="$outf"
}

run_extractor_case "$WORK/xml-utf8.xml"
{ [ "$EXTRACT_RC" -eq 0 ] && grep -Fqx '{"ok":true}' "$EXTRACT_FILE"; } &&
    report ok "g70 strict UTF-8 with UTF-8 declaration is accepted" ||
    report fail "g70 valid UTF-8 envelope" "rc=$EXTRACT_RC out=$EXTRACT_OUT"

for reject_case in utf8-bom utf16-dtd utf32-dtd utf16-plain utf32-plain iso-ascii latin1 nul invalid-utf8 conflict utf8-dtd; do
    case "$reject_case" in
        utf8-bom) case_no=71 ;;
        utf16-dtd) case_no=72 ;;
        utf32-dtd) case_no=73 ;;
        utf16-plain) case_no=74 ;;
        utf32-plain) case_no=75 ;;
        iso-ascii) case_no=76 ;;
        latin1) case_no=77 ;;
        nul) case_no=78 ;;
        invalid-utf8) case_no=79 ;;
        conflict) case_no=80a ;;
        utf8-dtd) case_no=80b ;;
    esac
    run_extractor_case "$WORK/xml-${reject_case}.xml"
    { [ "$EXTRACT_RC" -ne 0 ] && [ ! -e "$EXTRACT_FILE" ]; } &&
        report ok "g${case_no} rejects XML byte/encoding case '$reject_case'" ||
        report fail "g${case_no} XML byte envelope must fail closed" "case=$reject_case rc=$EXTRACT_RC out=$EXTRACT_OUT payload=$(cat "$EXTRACT_FILE" 2>/dev/null)"
done

# ---- g80c-g80e: JSON exponent overflow must not bypass non-finite rejection
run_extractor_case "$WORK/json-finite-exponent.xml"
{ [ "$EXTRACT_RC" -eq 0 ] && grep -Fqx '{"finite":1e308}' "$EXTRACT_FILE"; } &&
    report ok "g80c a large finite JSON exponent remains accepted" ||
    report fail "g80c finite parse_float values must not be over-rejected" "rc=$EXTRACT_RC out=$EXTRACT_OUT"

for overflow_case in overflow-exponent nested-negative-overflow; do
    case "$overflow_case" in
        overflow-exponent) case_no=80d ;;
        nested-negative-overflow) case_no=80e ;;
    esac
    run_extractor_case "$WORK/json-${overflow_case}.xml"
    { [ "$EXTRACT_RC" -ne 0 ] && [ ! -e "$EXTRACT_FILE" ]; } &&
        report ok "g${case_no} rejects non-finite JSON exponent '$overflow_case'" ||
        report fail "g${case_no} parse_float overflow must fail closed" \
            "case=$overflow_case rc=$EXTRACT_RC out=$EXTRACT_OUT payload=$(cat "$EXTRACT_FILE" 2>/dev/null)"
done

# ---- g81-g83 (same-family sweep): coordinate producers cannot launder rc
run_vector_coordinate_producer_case() { # glob-format | dirname | basename
    local producer="$1" case_dir="$WORK/vector-coordinate-$producer"
    local dest="$case_dir/evidence" outf="$case_dir/out" calls="$case_dir/device-calls"
    mkdir -p "$case_dir"
    : >"$calls"
    (
        export VE_LIB_PATH="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        # shellcheck disable=SC1090
        . "$VE_LIB_PATH"
        ve_live_root_shell() {
            printf 'root:%s\n' "$1" >>"$calls"
            case "$1" in
                'ls -d '*) printf '%s\n' "$VE_LIVE_PATH" ;;
                'cat '*) printf '%s' "$VE_LIVE_XML" ;;
                *) return 91 ;;
            esac
        }
        dev() { printf 'mirror:%s\n' "$*" >>"$calls"; printf '__VE_MIRROR_EXISTS=0\n'; }
        case "$producer" in
            glob-format)
                printf() {
                    if [ "${1:-}" = "$VE_LIVE_GLOB_TEMPLATE" ]; then
                        builtin printf "$@"
                        return 42
                    fi
                    builtin printf "$@"
                }
                ;;
            dirname)
                dirname() {
                    command dirname "$@"
                    [ "${2:-${1:-}}" != "$dest" ] || return 42
                }
                ;;
            basename)
                basename() {
                    command basename "$@"
                    [ "${2:-${1:-}}" != "$dest" ] || return 42
                }
                ;;
        esac
        ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$dest"
    ) >"$outf" 2>&1
    VECTOR_COORDINATE_RC=$?
    VECTOR_COORDINATE_OUT="$(cat "$outf")"
    VECTOR_COORDINATE_DEST="$dest"
    VECTOR_COORDINATE_CALLS="$calls"
}

for producer in glob-format dirname basename; do
    case "$producer" in glob-format) case_no=81 ;; dirname) case_no=82 ;; basename) case_no=83 ;; esac
    run_vector_coordinate_producer_case "$producer"
    { [ "$VECTOR_COORDINATE_RC" -ne 0 ] \
      && [ ! -e "$VECTOR_COORDINATE_DEST" ] && [ ! -L "$VECTOR_COORDINATE_DEST" ] \
      && [ ! -s "$VECTOR_COORDINATE_CALLS" ]; } &&
        report ok "g${case_no} $producer output plus rc=42 stops before device capture" ||
        report fail "g${case_no} coordinate producer status must outrank plausible output" \
            "producer=$producer rc=$VECTOR_COORDINATE_RC out=$VECTOR_COORDINATE_OUT calls=$(tr '\n' ' ' <"$VECTOR_COORDINATE_CALLS")"
done

# ---- g84 (#90 P1): model adb argv Join and a second remote-shell parse
REMOTE_SHIM="$WORK/vector-remote-shim"
mkdir -p "$REMOTE_SHIM"
printf '%s\n' \
    '#!/bin/sh' \
    '[ "$1" = name.caiyao.fakegps.bench ] || exit 93' \
    'shift' \
    'exec "$@"' >"$REMOTE_SHIM/run-as"
chmod +x "$REMOTE_SHIM/run-as"
VECTOR_REMOTE_CASE="$WORK/vector-remote-reparse"
mkdir -p "$VECTOR_REMOTE_CASE/shared_prefs"
printf '%s' "$VE_LIVE_XML" >"$VECTOR_REMOTE_CASE/shared_prefs/spoof_config.xml"
VECTOR_REMOTE_DEST="$VECTOR_REMOTE_CASE/evidence"
VECTOR_REMOTE_LOG="$VECTOR_REMOTE_CASE/out"
(
    cd "$VECTOR_REMOTE_CASE" || exit 2
    PATH="$REMOTE_SHIM:$PATH"
    export PATH
    # shellcheck disable=SC1090
    . "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
    ve_live_root_shell() {
        case "$1" in
            'ls -d '*) printf '%s\n' "$VE_LIVE_PATH" ;;
            'cat '*) printf '%s' "$VE_LIVE_XML" ;;
            *) return 91 ;;
        esac
    }
    dev() {
        [ "$1" = shell ] || return 92
        shift
        remote_joined=$*
        sh -c "$remote_joined"
    }
    ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$VECTOR_REMOTE_DEST"
) >"$VECTOR_REMOTE_LOG" 2>&1
VECTOR_REMOTE_RC=$?
VECTOR_REMOTE_OUT="$(cat "$VECTOR_REMOTE_LOG")"
{ [ "$VECTOR_REMOTE_RC" -eq 0 ] \
  && grep -Fqx 'mirror=identical' "$VECTOR_REMOTE_DEST/vector-prefs/spoof_config.xml.provenance" \
  && [ -s "$VECTOR_REMOTE_DEST/app-private-mirror/spoof_config.xml" ] \
  && [ -s "$VECTOR_REMOTE_DEST/app-private-mirror/spoof_config.xml.provenance" ]; } &&
    report ok "g84 mirror probe survives adb argv Join + remote shell reparse" ||
    report fail "g84 mirror probe remote quoting" "rc=$VECTOR_REMOTE_RC out=$VECTOR_REMOTE_OUT"

# ---- g85-g87: transport rc=0 is not a valid mirror probe protocol by itself
for protocol_kind in noise duplicate garbage; do
    case "$protocol_kind" in noise) case_no=85 ;; duplicate) case_no=86 ;; garbage) case_no=87 ;; esac
    case "$protocol_kind" in
        noise) protocol_output=$'__VE_MIRROR_EXISTS=0\nnoise' ;;
        duplicate) protocol_output=$'__VE_MIRROR_EXISTS=1\n__VE_MIRROR_EXISTS=1' ;;
        garbage) protocol_output='__VE_MIRROR_EXISTS=maybe' ;;
    esac
    protocol_case="$WORK/vector-protocol-$protocol_kind"
    protocol_dest="$protocol_case/evidence"
    protocol_reads="$protocol_case/mirror-reads"
    protocol_log="$protocol_case/out"
    mkdir -p "$protocol_case"
    : >"$protocol_reads"
    (
        # shellcheck disable=SC1090
        . "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        ve_live_root_shell() {
            case "$1" in
                'ls -d '*) printf '%s\n' "$VE_LIVE_PATH" ;;
                'cat '*) printf '%s' "$VE_LIVE_XML" ;;
                *) return 91 ;;
            esac
        }
        dev() {
            case "$*" in
                *'__VE_MIRROR_EXISTS'*) printf '%s\n' "$protocol_output" ;;
                *) printf 'read\n' >>"$protocol_reads"; return 92 ;;
            esac
        }
        ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$protocol_dest"
    ) >"$protocol_log" 2>&1
    protocol_rc=$?
    { [ "$protocol_rc" -eq 0 ] \
      && grep -Fqx 'mirror=unavailable' "$protocol_dest/vector-prefs/spoof_config.xml.provenance" \
      && grep -Fqx 'mirrorProbeRc=protocol-invalid' "$protocol_dest/vector-prefs/spoof_config.xml.provenance" \
      && [ ! -s "$protocol_reads" ] \
      && grep -q 'VE_MIRROR_UNAVAILABLE' "$protocol_log"; } &&
        report ok "g${case_no} invalid mirror protocol '$protocol_kind' is explicit and never read" ||
        report fail "g${case_no} mirror protocol parser" \
            "kind=$protocol_kind rc=$protocol_rc out=$(cat "$protocol_log") reads=$(wc -l <"$protocol_reads" | tr -d ' ')"
done

# ---- g88: command construction output cannot erase its producer failure
VECTOR_CONSTRUCTOR_CASE="$WORK/vector-constructor"
VECTOR_CONSTRUCTOR_DEST="$VECTOR_CONSTRUCTOR_CASE/evidence"
VECTOR_CONSTRUCTOR_LOG="$VECTOR_CONSTRUCTOR_CASE/out"
mkdir -p "$VECTOR_CONSTRUCTOR_CASE"
(
    # shellcheck disable=SC1090
    . "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
    ve_live_root_shell() {
        case "$1" in
            'ls -d '*) builtin printf '%s\n' "$VE_LIVE_PATH" ;;
            'cat '*) builtin printf '%s' "$VE_LIVE_XML" ;;
            *) return 91 ;;
        esac
    }
    dev() { builtin printf '__VE_MIRROR_EXISTS=0\n'; }
    printf() {
        case "${1:-}" in
            'run-as %s sh -c '*) builtin printf "$@"; return 42 ;;
            *) builtin printf "$@" ;;
        esac
    }
    ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$VECTOR_CONSTRUCTOR_DEST"
) >"$VECTOR_CONSTRUCTOR_LOG" 2>&1
VECTOR_CONSTRUCTOR_RC=$?
{ [ "$VECTOR_CONSTRUCTOR_RC" -ne 0 ] \
  && [ ! -e "$VECTOR_CONSTRUCTOR_DEST" ] && [ ! -L "$VECTOR_CONSTRUCTOR_DEST" ]; } &&
    report ok "g88 mirror command output + rc=42 cannot publish" ||
    report fail "g88 mirror command constructor status" \
        "rc=$VECTOR_CONSTRUCTOR_RC out=$(cat "$VECTOR_CONSTRUCTOR_LOG")"

# ---- g89-g91: every mirror raw cleanup branch is publication-critical
for raw_state in read-failed empty present; do
    case "$raw_state" in read-failed) case_no=89 ;; empty) case_no=90 ;; present) case_no=91 ;; esac
    raw_case="$WORK/vector-raw-$raw_state"
    raw_dest="$raw_case/evidence"
    raw_log="$raw_case/out"
    mkdir -p "$raw_case"
    (
        # shellcheck disable=SC1090
        . "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        ve_live_root_shell() {
            case "$1" in
                'ls -d '*) printf '%s\n' "$VE_LIVE_PATH" ;;
                'cat '*) printf '%s' "$VE_LIVE_XML" ;;
                *) return 91 ;;
            esac
        }
        dev() {
            case "$*" in
                *'__VE_MIRROR_EXISTS'*) printf '__VE_MIRROR_EXISTS=1\n' ;;
                *)
                    case "$raw_state" in
                        read-failed) printf 'partial'; return 41 ;;
                        empty) return 0 ;;
                        present) printf '%s' "$VE_LIVE_XML" ;;
                    esac ;;
            esac
        }
        rm() {
            for candidate in "$@"; do
                case "$candidate" in */app-private-mirror/*.raw) return 74 ;; esac
            done
            command rm "$@"
        }
        ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$raw_dest"
    ) >"$raw_log" 2>&1
    raw_rc=$?
    raw_residue=$(find "$raw_case" -maxdepth 1 -name '.evidence.vector-evidence.*' -print -quit)
    { [ "$raw_rc" -ne 0 ] && [ ! -e "$raw_dest" ] && [ ! -L "$raw_dest" ] \
      && [ -z "$raw_residue" ] && grep -q 'cannot remove staged mirror raw file' "$raw_log"; } &&
        report ok "g${case_no} $raw_state mirror raw cleanup failure blocks publish" ||
        report fail "g${case_no} mirror raw cleanup is authoritative" \
            "state=$raw_state rc=$raw_rc residue=${raw_residue:-none} out=$(cat "$raw_log")"
done

# ---- g92-g94: exact inner inventory rejects unknown/symlink/parser failure
for inventory_kind in unknown symlink parser-rc; do
    case "$inventory_kind" in unknown) case_no=92 ;; symlink) case_no=93 ;; parser-rc) case_no=94 ;; esac
    inventory_case="$WORK/vector-inventory-$inventory_kind"
    inventory_dest="$inventory_case/evidence"
    inventory_log="$inventory_case/out"
    mkdir -p "$inventory_case"
    inventory_python=""
    if [ "$inventory_kind" = parser-rc ]; then
        inventory_python="$inventory_case/python-fail"
        printf '%s\n' '#!/bin/sh' 'cat >/dev/null' 'exit 42' >"$inventory_python"
        chmod +x "$inventory_python"
    fi
    (
        # shellcheck disable=SC1090
        . "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
        ve_live_root_shell() {
            case "$1" in
                'ls -d '*) printf '%s\n' "$VE_LIVE_PATH" ;;
                'cat '*) printf '%s' "$VE_LIVE_XML" ;;
                *) return 91 ;;
            esac
        }
        dev() { printf '__VE_MIRROR_EXISTS=0\n'; }
        if [ "$inventory_kind" = parser-rc ]; then
            VE_PUBLISH_PYTHON="$inventory_python"
        else
            shasum() {
                local artifact=""
                for artifact in "$@"; do :; done
                case "$artifact" in */vector-prefs/spoof_config.xml)
                    if [ "$inventory_kind" = unknown ]; then
                        printf 'unknown\n' >"$(dirname -- "$artifact")/unknown.bin"
                    else
                        ln -s spoof_config.xml "$(dirname -- "$artifact")/unexpected-link"
                    fi
                    ;;
                esac
                command shasum "$@"
            }
        fi
        ve_capture_evidence name.caiyao.fakegps.bench spoof_config.xml "$inventory_dest"
    ) >"$inventory_log" 2>&1
    inventory_rc=$?
    { [ "$inventory_rc" -ne 0 ] \
      && [ ! -e "$inventory_dest" ] && [ ! -L "$inventory_dest" ]; } &&
        report ok "g${case_no} Vector inventory case '$inventory_kind' cannot publish" ||
        report fail "g${case_no} exact Vector staged inventory" \
            "kind=$inventory_kind rc=$inventory_rc out=$(cat "$inventory_log")"
done

# The native no-replace rename is the sole serialization authority; the old
# sibling inner lock and its after-publish cleanup state no longer exist.
if ! grep -q 'vector-evidence\.lock' "$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"; then
    report ok "g95 redundant Vector inner lock is absent from the shipped helper"
else
    report fail "g95 no post-publish inner-lock cleanup state" "vector-evidence.lock still present"
fi

[ ! -e "$ADB_SENTINEL" ] &&
    report ok "gZ device-free selftest never executes the adb shim" ||
    report fail "gZ selftest escaped the fake dev seam" "unexpected adb calls=$(wc -l <"$ADB_SENTINEL" | tr -d ' ')"

printf 'seed-10a-gate selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
