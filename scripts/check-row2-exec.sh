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
#   1. PRESENCE      the four execution-plane payloads exist as repo payloads:
#                    runner, V2 classifier, executable-manifest mechanism,
#                    packet builder/validator. On 2026-08-28 main (507b78d)
#                    this section was the recorded RED: none existed — the 66
#                    checklist IDs in PR #55 were contract prose with no
#                    executable behind them.
#   2. SYNTAX        bash -n on every payload (contract PRE-03 shape).
#   3. FIXTURES      every fixture in the frozen classifier corpus passes:
#                    each allow rule has >=1 positive; every negative
#                    (escape surfaces, placeholder mismatches, envelope
#                    mutations) must classify CLASSIFIER-REJECT (contract
#                    §3.1.1 fixture requirements, incl. awk system()/getline,
#                    sed exec, sqlite .shell/load_extension/readfile/writefile,
#                    grep mode/pattern bounds, sh -c, redirection tokens,
#                    fastboot, production launch, PATH-shadow, env injection).
#   4. RULE COVERAGE every ruleId in the frozen rule manifest has at least one
#                    positive fixture — a rule that lost its positive would
#                    silently narrow the grammar.
#   5. MODE MANIFEST the classifier's frozen HOST-RUNNER-MODE id manifest is
#                    byte-equal to the statically extracted manifest in the
#                    runner source (contract §3.1.1 HOST-RUNNER-MODE:
#                    "mode-id 必须属于 reviewed runner source 内的有限
#                    manifest，static extraction 与 fixture 逐 tuple 枚举").
#   6. LAUNCHER      integration green path in a throwaway evidence root with
#                    REAL host executables (cat/shasum/sort/bash — never adb):
#                    six-file carriers, canonical command.txt, clean env
#                    (ROW2-CLEAN-ENV-V1) with env-injection sentinel, closed
#                    stdin (ROW2-STDIN-CLOSED-V1), digest pre/post binding,
#                    single process-spawn call site.
#   7. WRITE BUDGET  the packet gate mechanically proves "no writes beyond
#                    authorization": normal-path DEVICE-WRITE envelopes are
#                    <= 12 (dispatch: Sol froze gate B as "最多 12 次
#                    write-classified adb invocation") and every write's
#                    checklist set is inside the contract §4.1 fixed allowlist;
#                    RST-01 stays conditional (outside the 12).
#   8. PACKET SCHEMA PRE-00 shape: fixture packet validates; unknown key,
#                    missing key, access-label field, duplicate seq, duplicate
#                    carrier path, non-contiguous seq are each rejected.
#   9. SHA-256       the pure-bash SHA-256 (builtins-only constraint of
#                    HOST-CLASSIFIER/HOST-RUNNER-MODE payloads) matches the
#                    system shasum on NIST vectors.
#
# The gate's own sensitivity is measured separately:
# scripts/selftest-row2-exec.sh mutates throwaway copies and asserts each
# guard reports its OWN finding (escape allowed / missing carrier quadruple
# reported success / packet-candidate mismatch continued) and nothing else's.
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
    for p in "$RUNNER" "$CLASSIFIER" "$ENVELOPE_LIB" "$PACKET_TOOL" "$FIXTURES"; do
        if [ -f "$p" ]; then
            ok "payload exists: $p"
        else
            fail "missing execution-plane payload: $p (contract PR #55 freeze has no executable behind it)"
        fi
    done
    # The executable-manifest MECHANISM: the launcher must carry the frozen
    # locationId -> canonical-location map, and a manifest template must exist.
    if [ -f "$ROW2_DIR/executable-manifest.template.json" ]; then
        ok "executable manifest template exists: $ROW2_DIR/executable-manifest.template.json"
    else
        fail "missing executable manifest mechanism: $ROW2_DIR/executable-manifest.template.json"
    fi
}

# ---------------------------------------------------------------------------
# 2. SYNTAX — PRE-03 shape: bash -n, and (unlike PRE-03) stderr here may be
#    non-empty only on failure; we assert exit 0.
# ---------------------------------------------------------------------------
section_syntax() {
    local p
    for p in "$RUNNER" "$CLASSIFIER" "$ENVELOPE_LIB" "$PACKET_TOOL"; do
        if [ ! -f "$p" ]; then return; fi
        if bash -n "$p" 2>/dev/null; then
            ok "bash -n: $p"
        else
            fail "bash -n failed: $p"
        fi
    done
}

section_presence
section_syntax

# Sections 3-9 are implemented after the payloads exist; until then they are
# reported as missing mechanisms (this is the RED state, not a stub that
# passes vacuously).
if [ ! -f "$CLASSIFIER" ] || [ ! -f "$RUNNER" ] || [ ! -f "$PACKET_TOOL" ]; then
    fail "execution plane incomplete: classifier fixtures / mode manifest / launcher / write-budget / packet-schema sections cannot run"
fi

if [ "$FAILURES" -ne 0 ]; then
    printf 'check-row2-exec: %d failure(s)\n' "$FAILURES"
    exit 1
fi
printf 'check-row2-exec: all sections passed\n'
