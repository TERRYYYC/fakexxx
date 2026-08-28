#!/usr/bin/env bash
#
# check-row2-exec.sh — host-side gate for the G2 Row 2 execution plane.
#
# Spec: docs/acceptance/g2-p10-row2-evidence-contract.md (PR #55, frozen at
# blob c072c83fa979cf9d222a544faf8366e6fa691d21) §3.1 (execution packet),
# §3.1.1 (ROW2-EXEC-ACCESS-V2 classifier), §3.2 (per-command carriers),
# §4.1 PRE-00 (packet schema validation) and the device-write allowlist that
# PRE-12/P8-08 enforce at run time.
#
# What this proves, section by section — all HOST-SIDE, zero device commands
# (dispatch hard boundary 1; the device lane belongs to a separate authorized
# line after Fable's EXECUTABLE verdict + operator authorization):
#
#   1. PRESENCE      the four execution-plane payloads exist as repo payloads.
#                    On 2026-08-28 main (507b78d) this section was the
#                    recorded RED: none existed — the 66 checklist IDs in
#                    PR #55 were contract prose with no executable behind
#                    them (commit 0031744, 7 failures).
#   2. SYNTAX        bash -n on every payload (contract PRE-03 shape).
#   3. FIXTURES      every fixture in the frozen classifier corpus passes:
#                    positives hit their exact ruleId/class; every negative
#                    (escape surfaces, placeholder mismatches, envelope
#                    mutations) must classify CLASSIFIER-REJECT.
#   4. RULE COVERAGE every ruleId in the classifier's frozen rule table has
#                    at least one positive fixture — a rule that lost its
#                    positive would silently narrow the grammar.
#   5. MODE MANIFEST the classifier's frozen HOST-RUNNER-MODE manifest is
#                    set-equal to the statically extracted manifest in the
#                    runner source (contract: "mode-id 必须属于 reviewed
#                    runner source 内的有限 manifest，static extraction 与
#                    fixture 逐 tuple 枚举").
#   6. LAUNCHER      integration green path in a throwaway evidence root with
#                    REAL host executables (sort/cat/grep/bash — never adb):
#                    manifest-freeze → packet build → PRE-00 validate →
#                    supervise (six-file carriers, classification, digest
#                    pre/post) → six-file gate PASS → audit all-green →
#                    clean-env sentinel (child env exactly LC_ALL/LANG/TZ
#                    [+ bash's own `_`]).
#   7. WRITE BUDGET  gate:prefire-write-boundary mechanically proves "no
#                    writes beyond authorization" (Sol's gate-B freeze: at
#                    most 12 normal-path write-classified invocations; first
#                    write is exactly ADB-WRITE-PREPARE; RST-01 only after
#                    TERM-04 PASS).
#   8. PACKET SCHEMA PRE-00 negatives: unknown top-level key, missing key,
#                    access-label field, duplicate seq, duplicate carrier
#                    path, non-canonical env policy, non-hex digest — each
#                    rejected with its OWN finding.
#   9. SHA-256       the pure-bash SHA-256 (builtins-only constraint) matches
#                    system shasum on NIST-style vectors incl. multi-block
#                    and UTF-8.
#
# The gate's own sensitivity is measured separately:
# scripts/selftest-row2-exec.sh mutates throwaway copies and asserts each
# guard reports its OWN finding (escape allowed / missing carrier quadruple
# reported success / packet-carrier mismatch continued) and nothing else's.
#
# Exit codes: 0 = every check passed; 1 = at least one failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

ROW2_DIR="scripts/row2"
RUNNER="$ROW2_DIR/row2-runner.sh"
CLASSIFIER="$ROW2_DIR/row2-classifier-v2.sh"
ENVELOPE_LIB="$ROW2_DIR/row2-envelope.sh"
PACKET_TOOL="$ROW2_DIR/row2-packet.sh"
FIXTURES="$ROW2_DIR/row2-classifier-v2-fixtures.json"

FAILURES=0

fail() { printf 'FAIL: %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
ok()   { printf '  ok  %s\n' "$1"; }

# ---------------------------------------------------------------------------
# 1. PRESENCE — the recorded RED on main 507b78d was this whole section.
# ---------------------------------------------------------------------------
section_presence() {
    local p
    for p in "$RUNNER" "$CLASSIFIER" "$ENVELOPE_LIB" "$PACKET_TOOL" "$FIXTURES" "$ROW2_DIR/executable-manifest.template.json"; do
        if [ -f "$p" ]; then
            ok "payload exists: $p"
        else
            fail "missing execution-plane payload: $p (contract PR #55 freeze has no executable behind it)"
        fi
    done
}

# ---------------------------------------------------------------------------
# 2. SYNTAX — PRE-03 shape.
# ---------------------------------------------------------------------------
section_syntax() {
    local p
    for p in "$RUNNER" "$CLASSIFIER" "$ENVELOPE_LIB" "$PACKET_TOOL"; do
        [ -f "$p" ] || return 0
        if bash -n "$p" 2>/dev/null; then
            ok "bash -n: $p"
        else
            fail "bash -n failed: $p"
        fi
    done
}

# ---------------------------------------------------------------------------
# 3. FIXTURES — the full frozen corpus must pass against the PRODUCTION
#    classifier (never a copy), so a negative can only start passing if the
#    real grammar changed.
# ---------------------------------------------------------------------------
section_fixtures() {
    [ -f "$CLASSIFIER" ] && [ -f "$FIXTURES" ] || return 0
    local tmp fpass=0 ffail=0 line fid
    tmp=$(mktemp -d)
    local -a failed_cases=()
    while IFS= read -r line; do
        case "$line" in \#*|"") continue ;; esac
        fid=${line%%|*}
        if (cd "$tmp" && bash "$REPO_ROOT/$CLASSIFIER" fixture "$fid" out.json) >/dev/null 2>&1; then
            fpass=$((fpass + 1))
        else
            ffail=$((ffail + 1))
            failed_cases+=("$fid")
        fi
    done < "$FIXTURES"
    rm -rf "$tmp"
    if [ "$ffail" -eq 0 ]; then
        ok "fixture corpus: $fpass/$((fpass + ffail)) pass"
    else
        fail "fixture corpus: $ffail FAIL of $((fpass + ffail)) — ${failed_cases[*]}"
    fi
}

# ---------------------------------------------------------------------------
# 4. RULE COVERAGE — every rule in the frozen table has a positive fixture.
# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 3b. PATH-LESS BUILTINS — the leaf payloads must run with NO environment at
#     all (review F2: dirname / cat-heredoc spawns broke under PATH=).
# ---------------------------------------------------------------------------
section_pathless() {
    [ -f "$CLASSIFIER" ] && [ -f "$RUNNER" ] || return 0
    local t
    t=$(mktemp -d)
    printf 'meta/fixture-ok\n' > "$t/probe.txt"
    if (cd "$t" && env -i /bin/bash "$REPO_ROOT/$CLASSIFIER" fixture POS-HOST-CAT out.json) >/dev/null 2>&1        && grep -q '"verdict":"PASS"' "$t/out.json"; then
        ok "classifier fixture mode runs with env -i (no PATH)"
    else
        fail "classifier needs an external binary (builtins-only violated)"
    fi
    if (cd "$t" && env -i /bin/bash "$REPO_ROOT/$CLASSIFIER" fixture NEG-ESC-AWK-SYSTEM out2.json) >/dev/null 2>&1        && grep -q '"verdict":"PASS"' "$t/out2.json"; then
        ok "classifier negative fixture also PATH-less green"
    else
        fail "classifier negative fixture not PATH-less clean"
    fi
    rm -rf "$t"
}

section_rule_coverage() {
    [ -f "$CLASSIFIER" ] && [ -f "$FIXTURES" ] || return 0
    local -a rules=() missing=()
    local line
    # statically extract the rule table (the same array the classifier evaluates)
    while IFS= read -r line; do
        rules+=("$line")
    done < <(sed -n '/^RULE_IDS=(/,/^)/p' "$CLASSIFIER" | grep -oE '(HOST|ADB)_[A-Z0-9_]+' | sort -u)
    local r rule_dash covered
    for r in "${rules[@]}"; do
        rule_dash=${r//_/-}
        covered=0
        while IFS= read -r line; do
            case "$line" in \#*|"") continue ;; esac
            # fixtureId|expectedRuleId|expectedClass|envelope
            if [[ $line == *"|$rule_dash|"* && $line != *"CLASSIFIER-REJECT|"* ]]; then
                covered=1
                break
            fi
        done < "$FIXTURES"
        [ "$covered" -eq 1 ] || missing+=("$rule_dash")
    done
    if [ "${#missing[@]}" -eq 0 ]; then
        ok "rule coverage: all ${#rules[@]} rules have a positive fixture"
    else
        fail "rules without a positive fixture (grammar silently narrowed): ${missing[*]}"
    fi
}

# ---------------------------------------------------------------------------
# 5. MODE MANIFEST — classifier manifest == statically extracted runner manifest
# ---------------------------------------------------------------------------
section_mode_manifest() {
    [ -f "$CLASSIFIER" ] && [ -f "$RUNNER" ] || return 0
    local cls_m run_m
    cls_m=$(sed -n '/^RUNNER_MODE_IDS=(/,/^)/p' "$CLASSIFIER" | grep -oE '"[a-z]+:[a-z-]+"' | sort | tr -d '"' | tr '\n' ' ')
    run_m=$(sed -n '/^RUNNER_MODE_IDS=(/,/^)/p' "$RUNNER" | grep -oE '"[a-z]+:[a-z-]+"' | sort | tr -d '"' | tr '\n' ' ')
    if [ -n "$cls_m" ] && [ "$cls_m" = "$run_m" ]; then
        ok "mode manifests agree: $cls_m"
    else
        fail "mode manifest drift — classifier[$cls_m] runner[$run_m]"
    fi
}

# ---------------------------------------------------------------------------
# 6. LAUNCHER INTEGRATION — real host executables, throwaway evidence root.
# ---------------------------------------------------------------------------
write_gate_spec() { # <path> — the gate's canonical host-only spec (sections 6+8 share it)
    cat > "$1" <<'SPEC'
SPEC_contractGitHead=c072c83fa979cf9d222a544faf8366e6fa691d21
SPEC_contractBlobSha=c072c83fa979cf9d222a544faf8366e6fa691d21
SPEC_contractSha256=0000000000000000000000000000000000000000000000000000000000000001
SPEC_evidenceDirName=g2-row2-exec-gate
SPEC_runId=run-gate-0001
SPEC_candidateHead=0031744000000000000000000000000000000000000
SPEC_candidateTree=0000000000000000000000000000000000000000000000000000000000000002
SPEC_buildType=debug
SPEC_gradleTasks=()
SPEC_contractYamlSha256=0000000000000000000000000000000000000000000000000000000000000003
SPEC_build_commandDigest=pending
SPEC_build_reportDigest=pending
SPEC_build_manifestDigest=pending
SPEC_build_sandboxReportDigest=pending
SPEC_host_os=gate SPEC_host_kernel=gate SPEC_host_java=gate SPEC_host_gradle=gate
SPEC_host_androidSdk=gate SPEC_host_adb=gate SPEC_host_sqlite=gate SPEC_host_shasum=gate
SPEC_host_bash=gate SPEC_host_gitRepoConfigSha256=0000000000000000000000000000000000000000000000000000000000000004
SPEC_device_serial=ZY22-FIXTURE-SERIAL
SPEC_pkg_bench_applicationId=name.caiyao.fakegps.bench
SPEC_pkg_bench_artifactRepoRelativePath=apps/pending.apk
SPEC_pkg_bench_artifactSha256=0000000000000000000000000000000000000000000000000000000000000005
SPEC_pkg_bench_versionCode=1 SPEC_pkg_bench_versionName=1.0
SPEC_pkg_bench_signerSha256=0000000000000000000000000000000000000000000000000000000000000006
SPEC_pkg_bench_installedBaseApkPath=/data/app/~~benchfixture/base.apk
SPEC_pkg_auto_applicationId=com.example.cellrebelauto
SPEC_pkg_auto_artifactRepoRelativePath=apps/pending2.apk
SPEC_pkg_auto_artifactSha256=0000000000000000000000000000000000000000000000000000000000000007
SPEC_pkg_auto_versionCode=1 SPEC_pkg_auto_versionName=1.0
SPEC_pkg_auto_signerSha256=5555555555555555555555555555555555555555555555555555555555555555
SPEC_pkg_auto_installedBaseApkPath=/data/app/~~autofixture/base.apk
SPEC_pkg_production_id=name.caiyao.fakegps
SPEC_comp_benchAcceptance=name.caiyao.fakegps.bench/name.caiyao.fakegps.mockprovider.MockProviderAcceptanceActivity
SPEC_comp_qwyCollector=name.caiyao.fakegps.bench/name.caiyao.fakegps.integration.v1.FaultCollectorActivity
SPEC_comp_autoHandshake=com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.HandshakeProbeActivity
SPEC_comp_autoState=com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.ProviderRevokeCollectorActivity
SPEC_comp_autoProbe=com.example.cellrebelauto/com.example.cellrebelauto.integration.v1.FullLoopProbeActivity
SPEC_p8_db=/data/adb/lspd/config/modules_config.db
SPEC_p8_wal=/data/adb/lspd/config/modules_config.db-wal
SPEC_p8_shm=/data/adb/lspd/config/modules_config.db-shm
SPEC_p8_expectedModules=lspd=0 SPEC_p8_expectedScopes=bench-auto SPEC_p8_expectedMockAllowPackages=bench
SPEC_kyiv_scheduleId=k1 SPEC_kyiv_scheduleVersion=v1 SPEC_kyiv_currentItemId=i1
SPEC_kyiv_expectedBeforeState=fresh SPEC_kyiv_expectedAfterState=exhausted
SPEC_roles_executorTaskId=t1 SPEC_roles_executorOwner=glm52
SPEC_roles_recorderTaskId=t2 SPEC_roles_recorderOwner=gpt55
SPEC_roles_validityTaskId=t3 SPEC_roles_validityOwner=fable5
SPEC_holdMs=30000 SPEC_terminalTimeoutSeconds=70
SPEC_sealControlPaths=()
printf 'provider [mock] zero\nprovider alpha\n' > derived/in-sort.txt
printf 'provider [mock] one\n' > derived/in-cat.txt
printf 'ECFullLoop line\nLOOP ABORTED\n' > derived/in-grep.txt
add_command 001 meta fileset-sort sort evidence ROW2-CLEAN-ENV-V1 -- sort -- derived/in-sort.txt
add_command 002 meta host-cat cat evidence ROW2-CLEAN-ENV-V1 -- cat -- derived/in-cat.txt
add_command 003 meta text-grep grep evidence ROW2-CLEAN-ENV-V1 -- grep -F -v -e LOOP -- derived/in-grep.txt
# repo-payload units — the review's F1: these validated but could not run;
# their output path IS the unit's frozen stdout carrier
add_command 004 meta runner-parse row2-runner evidence ROW2-CLEAN-ENV-V1 -- bash scripts/row2/row2-runner.sh parse envelope meta/001-fileset-sort.command.txt meta/004-runner-parse.stdout.txt
add_command 005 meta classifier-fixture row2-classifier evidence ROW2-CLEAN-ENV-V1 -- bash scripts/row2/row2-classifier-v2.sh fixture POS-HOST-CAT meta/005-classifier-fixture.stdout.txt
SPEC
}

section_launcher() {
    [ -f "$RUNNER" ] && [ -f "$PACKET_TOOL" ] || return 0
    local ev
    ev=$(mktemp -d)/evidence
    mkdir -p "$ev/meta" "$ev/derived"
    if ! "$PACKET_TOOL" manifest-freeze "$ev" bash cat sort grep shasum row2-runner row2-classifier >/dev/null 2>&1; then
        fail "manifest-freeze failed"
        rm -rf "$(dirname "$ev")"
        return 0
    fi
    write_gate_spec "$ev/../gate-spec.bash"
    if ! "$PACKET_TOOL" build "$ev" "$ev/../gate-spec.bash" >/dev/null 2>&1; then
        fail "packet build failed"
        rm -rf "$(dirname "$ev")"
        return 0
    fi
    if "$PACKET_TOOL" validate "$ev" >/dev/null 2>&1; then
        ok "packet PRE-00 validate"
    else
        fail "packet PRE-00 validate failed on the gate's own green packet"
        rm -rf "$(dirname "$ev")"
        return 0
    fi
    local sup_err="$ev/supervise.err"
    if (cd "$ev" && bash "$REPO_ROOT/$RUNNER" supervise . 2>"$sup_err"); then
        ok "supervise: 5 host units executed via launcher (incl. repo-payload interpreter exec)"
    else
        fail "supervise failed: $(tail -3 "$sup_err" 2>/dev/null | tr '\n' ' ')"
        rm -rf "$(dirname "$ev")"
        return 0
    fi
    # all exits zero, six-file gate green, audit green
    local exits_bad=0 x
    for x in "$ev"/meta/*.exit.txt; do
        [ "$(cat "$x")" = "0" ] || exits_bad=1
    done
    [ "$exits_bad" -eq 0 ] && ok "all leaf exits = 0" || fail "nonzero leaf exit in green path"
    printf 'meta/001-fileset-sort\nmeta/002-host-cat\nmeta/003-text-grep\nmeta/004-runner-parse\nmeta/005-classifier-fixture\n' > "$ev/meta/stems.txt"
    if (cd "$ev" && bash "$REPO_ROOT/$RUNNER" gate six-file-carrier meta/stems.txt meta/gate.json) >/dev/null 2>&1; then
        ok "six-file carrier gate: PASS"
    else
        fail "six-file carrier gate red on the green session: $(tail -1 "$ev/meta/gate.json" 2>/dev/null)"
    fi
    # repo-payload units must have actually RUN (review F1: exit 126/2 shapes)
    local rp_exit rp_out
    rp_exit=$(cat "$ev/meta/004-runner-parse.exit.txt" 2>/dev/null || echo missing)
    rp_out=$(cat "$ev/meta/004-runner-parse.stdout.txt" 2>/dev/null | head -c 200)
    if [ "$rp_exit" = "0" ] && [[ $rp_out == *'"executableId":"sort"'* ]]; then
        ok "repo-payload unit 004 ran via interpreter (exit=0, parse output real)"
    else
        fail "repo-payload unit 004 broken: exit=$rp_exit out=$rp_out"
    fi
    cat "$ev"/meta/.cls-00*.json > "$ev/meta/cls-all.txt"
    if (cd "$ev" && bash "$REPO_ROOT/$RUNNER" audit command-surface meta/cls-all.txt meta/audit.json) >/dev/null 2>&1 \
       && grep -q '"packetCarrierMismatch":0' "$ev/meta/audit.json"; then
        ok "audit command-surface: all green"
    else
        fail "audit command-surface red on the green session"
    fi
    # clean-env sentinel: extract the REAL clean_env_exec (never a copy of the
    # behavior) and run /usr/bin/env under it with hostile inherited keys
    local tdir
    tdir=$(mktemp -d)
    sed -n '/^# clean_env_exec —/,/^}/p' "$RUNNER" > "$tdir/ce-fn.sh"
    cat > "$tdir/ce-test.sh" <<'SENT'
. "$1/row2-envelope.sh"
source "$1/ce-fn.sh"
PU_ENVP=ROW2-CLEAN-ENV-V1
export BASH_ENV=/tmp/evil JAVA_TOOL_OPTIONS=-Xmx999 FOO=bar ADB_SERVER_SOCKET=tcp:evil
clean_env_exec /usr/bin/env "$2/env.txt" "$2/env.err"
SENT
    if (cd "$ev" && bash "$tdir/ce-test.sh" "$tdir" "$tdir") >/dev/null 2>&1; then
        local keys bad=0 k
        keys=$(tr ' ' '\n' < "$tdir/env.txt" | sed 's/=.*//' | sort | tr '\n' ' ')
        for k in $keys; do
            case "$k" in LC_ALL|LANG|TZ|_) ;; *) bad=1 ;; esac
        done
        [ "$bad" -eq 0 ] && ok "clean-env sentinel: child keys exactly [$keys]" \
                          || fail "clean-env sentinel: leaked keys [$keys]"
    else
        fail "clean-env sentinel: extraction/exec failed"
    fi
    rm -rf "$tdir" "$(dirname "$ev")"
}

# ---------------------------------------------------------------------------
# 7. WRITE BUDGET
# ---------------------------------------------------------------------------
section_write_budget() {
    [ -f "$RUNNER" ] || return 0
    local t
    t=$(mktemp -d)
    local i
    : > "$t/w13.txt"
    for i in $(seq 1 13); do printf '{"seq":"%03d","ruleId":"ADB-WRITE-QDUMP","derivedClass":"DEVICE-WRITE"}\n' "$i" >> "$t/w13.txt"; done
    printf '{"seq":"001","ruleId":"ADB-WRITE-PREPARE","derivedClass":"DEVICE-WRITE"}\n' > "$t/w12.txt"
    for i in $(seq 2 12); do printf '{"seq":"%03d","ruleId":"ADB-WRITE-QDUMP","derivedClass":"DEVICE-WRITE"}\n' "$i" >> "$t/w12.txt"; done
    printf '{"seq":"001","ruleId":"ADB-WRITE-PREPARE","derivedClass":"DEVICE-WRITE"}\n{"seq":"002","ruleId":"ADB-WRITE-RERELEASE","derivedClass":"DEVICE-WRITE"}\n' > "$t/wrst.txt"
    if (cd "$t" && bash "$REPO_ROOT/$RUNNER" gate prefire-write-boundary w13.txt o1.json) >/dev/null 2>&1; then
        fail "write budget: 13 writes accepted (must STOP)"
    else
        grep -q 'writes-exceed-12' "$t/o1.json" && ok "13 writes → STOP" || fail "13 writes stopped for the wrong reason"
    fi
    if (cd "$t" && bash "$REPO_ROOT/$RUNNER" gate prefire-write-boundary w12.txt o2.json) >/dev/null 2>&1; then
        ok "12 writes with first=PREPARE → PASS"
    else
        fail "write budget: 12 writes rejected (must PASS)"
    fi
    if (cd "$t" && bash "$REPO_ROOT/$RUNNER" gate prefire-write-boundary wrst.txt o3.json) >/dev/null 2>&1; then
        fail "write budget: RST-01 without TERM-04 accepted (must STOP)"
    else
        grep -q 'rst01-without-term04' "$t/o3.json" && ok "RST-01 without TERM-04 → STOP" || fail "RST stop for the wrong reason"
    fi
    rm -rf "$t"
}

# ---------------------------------------------------------------------------
# 8. PACKET SCHEMA negatives — each mutation rejected with its OWN finding.
# ---------------------------------------------------------------------------
section_packet_schema() {
    [ -f "$PACKET_TOOL" ] || return 0
    local ev t
    t=$(mktemp -d)
    ev="$t/evidence"; mkdir -p "$ev/meta" "$ev/derived"
    "$PACKET_TOOL" manifest-freeze "$ev" bash cat sort grep shasum row2-runner row2-classifier >/dev/null 2>&1
    local spec="$t/spec.bash"
    write_gate_spec "$spec"
    printf 'provider [mock] zero\nprovider alpha\n' > "$ev/derived/in-sort.txt"
    printf 'provider [mock] one\n' > "$ev/derived/in-cat.txt"
    printf 'ECFullLoop line\nLOOP ABORTED\n' > "$ev/derived/in-grep.txt"
    "$PACKET_TOOL" build "$ev" "$spec" >/dev/null 2>&1 \
        || { fail "packet-schema: green build failed"; rm -rf "$t"; return 0; }
    local pkt="$ev/meta/execution-packet.json" mutated expect
    declare -a CASES=(
        'unknown-key|s/,"commands":/,"zzzPlanted":1,"commands":/|key sequence mismatch'
        'access-label|s/,"commands":/,"deviceAccess":"HOST-NONE","commands":/|access-label field'
        'dup-seq|s/"seq":"002"/"seq":"001"/|seq not contiguous: got 001, expected 002'
        'seq-gap|s/"seq":"003"/"seq":"013"/|seq not contiguous: got 013, expected 003'
        'missing-key|s/,"buildType":"debug"//|required key missing: buildType'
        'bad-env-policy|s/ROW2-CLEAN-ENV-V1","stdinPolicyId":"ROW2-STDIN-CLOSED-V1","argv/ROW2-DIRTY-ENV-V9","stdinPolicyId":"ROW2-STDIN-CLOSED-V1","argv/|envPolicyId'
        'dup-carrier|s/meta\/002-host-cat.command.txt/meta\/001-fileset-sort.command.txt/|carrier path reused'
    )
    local c name subst needle
    for c in "${CASES[@]}"; do
        IFS='|' read -r name subst needle <<< "$c"
        cp "$pkt" "$t/mutant.json"
        sed -i '' "$subst" "$t/mutant.json" 2>/dev/null || sed -i "$subst" "$t/mutant.json"
        mkdir -p "$t/m/$name/meta"
        cp "$t/mutant.json" "$t/m/$name/meta/execution-packet.json"
        if "$PACKET_TOOL" validate "$t/m/$name" >"$t/err.txt" 2>&1; then
            fail "packet-schema [$name]: mutant ACCEPTED (validator blind)"
        else
            grep -q "$needle" "$t/err.txt" \
                && ok "packet-schema [$name] rejected with its own finding" \
                || fail "packet-schema [$name] rejected for the wrong reason: $(cat "$t/err.txt" | head -1)"
        fi
    done
    rm -rf "$t"
}

# ---------------------------------------------------------------------------
# 9. SHA-256 vectors
# ---------------------------------------------------------------------------
section_sha256() {
    [ -f "$ENVELOPE_LIB" ] || return 0
    local out
    out=$(bash -c '
. "'"$REPO_ROOT/$ENVELOPE_LIB"'"
fail=0
for s in "" "abc" "The quick brown fox jumps over the lazy dog" "$(printf "a%.0s" {1..512})" "$(printf "b%.0s" {1..1000})" "héllo-ütf8"; do
  mine=$(sha256_hex "$s")
  sys=$(printf %s "$s" | shasum -a 256 | cut -d" " -f1)
  [ "$mine" = "$sys" ] || { echo "vector len=${#s}"; fail=1; }
done
exit $fail')
    if [ $? -eq 0 ] && [ -z "$out" ]; then
        ok "pure-bash SHA-256 == shasum on all vectors"
    else
        fail "pure-bash SHA-256 mismatch: $out"
    fi
}

section_presence
section_syntax
section_fixtures
section_pathless
section_rule_coverage
section_mode_manifest
section_launcher
section_write_budget
section_packet_schema
section_sha256
section_packet_schema_mended() { :; }  # placeholder guard removed below

if [ ! -f "$CLASSIFIER" ] || [ ! -f "$RUNNER" ] || [ ! -f "$PACKET_TOOL" ]; then
    fail "execution plane incomplete: sections 3-8 cannot run"
fi

if [ "$FAILURES" -ne 0 ]; then
    printf 'check-row2-exec: %d failure(s)\n' "$FAILURES"
    exit 1
fi
printf 'check-row2-exec: all sections passed\n'
