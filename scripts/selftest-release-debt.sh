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
