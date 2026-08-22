#!/usr/bin/env bash
#
# selftest-release-debt.sh — proves check-release-debt.sh red/green boundary.
#
# The gate's job is binary: open debt → fail; no debt → pass. The selftest
# must prove both sides without touching GitHub, which is why the gate
# accepts --issues-json. Each case below creates a throwaway JSON fixture
# that isolates the claim.
#
# A green-only test (or red-only test) is half a proof: it cannot show that
# the gate distinguishes the two states, only that it has one. So this file
# always runs in pairs — one fixture that must pass, one that must fail.
#
# Exit codes: 0 = all cases passed; 1 = at least one failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$REPO_ROOT/scripts/check-release-debt.sh"

POS=0; NEG=0; FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

# ================================================================ POSITIVE
# The gate must PASS (exit 0) when no issues are open.

printf 'positive cases\n'

# P-1: empty array — no open debt
cat > "$tmpdir/p1.json" <<'EOF'
[]
EOF
POS=$((POS + 1))
if "$GATE" --issues-json "$tmpdir/p1.json" --quiet >/dev/null 2>&1; then
  ok "P-1  empty array → pass"
else
  bad "P-1  empty array should pass (exit 0), got exit $?"
fi

# ================================================================ NEGATIVE
# The gate must FAIL (exit 1) when open debt exists.

printf 'negative cases\n'

# N-1: one open debt issue
cat > "$tmpdir/n1.json" <<'EOF'
[{"number": 99, "title": "[fixture] a single open debt issue"}]
EOF
NEG=$((NEG + 1))
if "$GATE" --issues-json "$tmpdir/n1.json" --quiet >/dev/null 2>&1; then
  bad "N-1  one issue should fail, got exit 0"
else
  ok "N-1  one open issue → fail"
fi

# N-2: multiple open debt issues
cat > "$tmpdir/n2.json" <<'EOF'
[
  {"number": 16, "title": "[fixture] debt 1"},
  {"number": 17, "title": "[fixture] debt 2"},
  {"number": 18, "title": "[fixture] debt 3"}
]
EOF
NEG=$((NEG + 1))
if "$GATE" --issues-json "$tmpdir/n2.json" --quiet >/dev/null 2>&1; then
  bad "N-2  three issues should fail, got exit 0"
else
  ok "N-2  three open issues → fail"
fi

# N-3: gate output must list the issue numbers
NEG=$((NEG + 1))
OUT="$("$GATE" --issues-json "$tmpdir/n2.json" 2>&1)" || true
if echo "$OUT" | grep -q '#16' && echo "$OUT" | grep -q '#17' && echo "$OUT" | grep -q '#18'; then
  ok "N-3  output lists all issue numbers"
else
  bad "N-3  output must list #16, #17, #18; got: $OUT"
fi

# N-4: gate reports correct count
NEG=$((NEG + 1))
if echo "$OUT" | grep -q '3 open release-blocking debt'; then
  ok "N-4  output reports correct count"
else
  bad "N-4  output must say '3 open release-blocking debt'; got first line: $(head -1 <<< "$OUT")"
fi

# ================================================================ EXACT-ID GUARD (Issue #19/#20)
# The gate must catch the EXACT issue numbers that this PR targets.
# Killing mutation: renumbering (e.g. #19 → #190) silently passes.

printf 'exact-ID guard (Issue #19/#20)\n'

# N-5: #19 alone → fail with exact rc=1
cat > "$tmpdir/n5.json" <<'EOF'
[{"number": 19, "title": "A+ advance recovery: quota-reached → advance → verify proves terminal truth"}]
EOF
NEG=$((NEG + 1))
rc=0; "$GATE" --issues-json "$tmpdir/n5.json" --quiet >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 1 ]; then
  ok "N-5  #19 alone → exact rc=1"
else
  bad "N-5  #19 alone should exit exactly 1 (release-blocking), got $rc"
fi

# N-6: #20 alone → fail with exact rc=1
cat > "$tmpdir/n6.json" <<'EOF'
[{"number": 20, "title": "Release-debt gate: CI proves open contract-v1.1-debt blocks release"}]
EOF
NEG=$((NEG + 1))
rc=0; "$GATE" --issues-json "$tmpdir/n6.json" --quiet >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 1 ]; then
  ok "N-6  #20 alone → exact rc=1"
else
  bad "N-6  #20 alone should exit exactly 1 (release-blocking), got $rc"
fi

# N-7: #19 + #20 together → fail with rc=1 AND output lists both numbers
cat > "$tmpdir/n7.json" <<'EOF'
[
  {"number": 19, "title": "A+ advance recovery: quota-reached → advance → verify proves terminal truth"},
  {"number": 20, "title": "Release-debt gate: CI proves open contract-v1.1-debt blocks release"}
]
EOF
NEG=$((NEG + 1))
rc_n7=0; OUT_N7="$("$GATE" --issues-json "$tmpdir/n7.json" 2>&1)" || rc_n7=$?
if [ "$rc_n7" -eq 1 ] && echo "$OUT_N7" | grep -q '#19' && echo "$OUT_N7" | grep -q '#20'; then
  ok "N-7  #19+#20 → rc=1 AND both listed"
else
  bad "N-7  #19+#20 should fail(1) listing both; rc=$rc_n7, output: $(head -2 <<< "$OUT_N7")"
fi

# N-8: after #19/#20 are closed, gate passes (proves the gate is structural, not hardcoded)
cat > "$tmpdir/n8.json" <<'EOF'
[]
EOF
NEG=$((NEG + 1))
rc=0; "$GATE" --issues-json "$tmpdir/n8.json" --quiet >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 0 ]; then
  ok "N-8  empty (post-closure) → rc=0 (gate is structural, not hardcoded)"
else
  bad "N-8  empty should pass (rc=0), got $rc"
fi

# ================================================================ MATRIX COVERAGE
# The advance matrix must have all M-AD-14..19 IDs in the test source.

printf 'matrix coverage\n'

# N-9: check-matrix-coverage.sh must pass
MATRIX_SCRIPT="$REPO_ROOT/scripts/check-matrix-coverage.sh"
NEG=$((NEG + 1))
if [ -x "$MATRIX_SCRIPT" ]; then
  rc_n9=0; "$MATRIX_SCRIPT" >/dev/null 2>&1 || rc_n9=$?
  if [ "$rc_n9" -eq 0 ]; then
    ok "N-9  check-matrix-coverage.sh → rc=0 (all M-AD IDs present)"
  else
    bad "N-9  check-matrix-coverage.sh failed (rc=$rc_n9) — missing M-AD test IDs"
  fi
else
  bad "N-9  check-matrix-coverage.sh not found or not executable at $MATRIX_SCRIPT"
fi

# N-10: KILLING selftest — removing an M-AD ID must cause check-matrix-coverage.sh to fail
# This proves the guard is load-bearing: a change that quietly drops a test ID is caught.
NEG=$((NEG + 1))
EXACT_FILE="$REPO_ROOT/apps/cellrebel-auto/app/src/test/java/com/example/cellrebelauto/matrix/AdvanceMatrixTest.kt"
if [ -f "$EXACT_FILE" ]; then
  cp "$EXACT_FILE" "$tmpdir/AdvanceMatrixTest.kt.bak"
  # Temporarily remove M_AD_17 from the file
  sed 's/fun M_AD_17()/fun REMOVED_AD_17()/' "$EXACT_FILE" > "$tmpdir/mutated.kt"
  cp "$tmpdir/mutated.kt" "$EXACT_FILE"
  rc_n10=0; "$MATRIX_SCRIPT" >/dev/null 2>&1 || rc_n10=$?
  # Restore immediately
  cp "$tmpdir/AdvanceMatrixTest.kt.bak" "$EXACT_FILE"
  if [ "$rc_n10" -ne 0 ]; then
    ok "N-10 killing selftest: removing M_AD_17 → guard fails (rc=$rc_n10)"
  else
    bad "N-10 killing selftest: removing M_AD_17 should cause guard failure, but it passed"
  fi
else
  bad "N-10 killing selftest: AdvanceMatrixTest.kt not found at exact coordinate"
fi

# ================================================================ EDGE CASES

printf 'edge cases\n'

# E-1: invalid JSON → exit 2 (inconclusive, not pass)
echo "not json" > "$tmpdir/e1.json"
NEG=$((NEG + 1))
rc=0; "$GATE" --issues-json "$tmpdir/e1.json" --quiet >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 2 ]; then
  ok "E-1  invalid JSON → exit 2 (inconclusive)"
else
  bad "E-1  invalid JSON should exit 2, got $rc"
fi

# E-2: missing file → exit 2
NEG=$((NEG + 1))
rc=0; "$GATE" --issues-json "$tmpdir/nonexistent.json" --quiet >/dev/null 2>&1 || rc=$?
if [ "$rc" -eq 2 ]; then
  ok "E-2  missing file → exit 2"
else
  bad "E-2  missing file should exit 2, got $rc"
fi

# ================================================================ SUMMARY

TOTAL=$((POS + NEG))
printf '\nselftest-release-debt: %d positive / %d negative\n' "$POS" "$NEG"

if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-release-debt: ALL PASS (%d cases)\n' "$TOTAL"
  exit 0
fi
printf 'selftest-release-debt: %d FAILURE(S)\n' "$FAILURES"
exit 1
