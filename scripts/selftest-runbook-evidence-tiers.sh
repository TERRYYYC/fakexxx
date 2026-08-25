#!/usr/bin/env bash
#
# selftest-runbook-evidence-tiers.sh — regression gate for check-runbook-evidence-tiers.sh.
#
# Every case runs the PRODUCTION script against a mutated copy of the runbook
# and asserts its exit code.  No case inspects source text — grepping the
# checker for a literal proves the line exists, not that it does anything.
#
# Six mutation classes (F-19 real-world failure modes that must stay killed):
#   M1  Remove Tier B from §11 → item 7 silently rejoins judgment-bearing items
#   M2  Move item 7 back into the Tier A table → screenshots become承重 again
#   M3  Remove failure-mode documentation → naïve checks appear sufficient
#   M4  Add screenshot reference to §7 verdict criteria → false-green surface
#   M5  Add screenshot reference to §12 exit criteria → false-green surface
#   M6  Remove non-participation disclaimer from item 7 → weight ambiguity
#
# Plus one positive:
#   P1  Pristine (unmodified) runbook must pass
#
# Labels, counts and what actually runs must agree.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECKER="$REPO_ROOT/scripts/check-runbook-evidence-tiers.sh"
RUNBOOK="$REPO_ROOT/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"

POS=0; NEG=0; MUT=0; FAILURES=0

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT

# Create a minimal sandbox: the checker locates the runbook via REPO_ROOT, so we
# mirror just enough directory structure.
setup_sandbox() {
  local sandbox="$tmpdir/$1"
  mkdir -p "$sandbox/docs/acceptance" "$sandbox/scripts"
  cp "$CHECKER" "$sandbox/scripts/check-runbook-evidence-tiers.sh"
  chmod +x "$sandbox/scripts/check-runbook-evidence-tiers.sh"
  cp "$RUNBOOK" "$sandbox/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
  echo "$sandbox"
}

run_checker() {
  local sandbox="$1"
  "$sandbox/scripts/check-runbook-evidence-tiers.sh" >/dev/null 2>&1
  echo $?
}

assert_pass() {
  local label="$1" sandbox="$2"
  local rc
  rc=$(run_checker "$sandbox")
  if [ "$rc" -eq 0 ]; then
    echo "  PASS  [+] $label"
    POS=$((POS + 1))
  else
    echo "  FAIL  [+] $label (expected pass, got exit $rc)"
    FAILURES=$((FAILURES + 1))
  fi
}

assert_fail() {
  local label="$1" sandbox="$2"
  local rc
  rc=$(run_checker "$sandbox")
  if [ "$rc" -ne 0 ]; then
    echo "  PASS  [-] $label"
    NEG=$((NEG + 1))
  else
    echo "  FAIL  [-] $label (expected fail, got exit 0 — mutation survived)"
    FAILURES=$((FAILURES + 1))
    MUT=$((MUT + 1))
  fi
}

echo "selftest-runbook-evidence-tiers: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo ""

# ── P1: Pristine baseline ───────────────────────────────────────────────────

sb=$(setup_sandbox "p1-pristine")
assert_pass "P1 pristine runbook passes all checks" "$sb"

# ── M1: Remove Tier B entirely (merge all items into one flat table) ─────────

sb=$(setup_sandbox "m1-no-tier-b")
# Delete Tier B heading and its content, merge item 7 back into the main table
sed -i.bak '/^### Tier B/,/^## [0-9]/{ /^## [0-9]/!d; }' \
  "$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
assert_fail "M1 removing Tier B classification is caught" "$sb"

# ── M2: Move item 7 into Tier A table ───────────────────────────────────────

sb=$(setup_sandbox "m2-item7-in-tier-a")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
# Insert a row "| 7 | 每步探针屏幕截图 | screencap |" into the Tier A table
# (after the row for item 6)
sed -i.bak '/| 6 |.*logcat/a\
| 7 | 每步探针屏幕截图 | `adb exec-out screencap -p` |' "$rb"
assert_fail "M2 moving item 7 into Tier A is caught" "$sb"

# ── M3: Remove failure-mode documentation ───────────────────────────────────

sb=$(setup_sandbox "m3-no-failure-modes")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
# Strip the three failure mode keywords
sed -i.bak 's/黑屏/removed/g; s/陈旧帧/removed/g; s/转场半帧/removed/g' "$rb"
assert_fail "M3 removing failure-mode docs is caught" "$sb"

# ── M4: Add screenshot reference to §7 verdict criteria ─────────────────────

sb=$(setup_sandbox "m4-screenshot-in-s7")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
# Inject a line into §7 that uses screenshots for verdict
sed -i.bak '/^## 7\./a\
截图显示 PASS 界面且对应日志一致时判定 PASS。' "$rb"
assert_fail "M4 adding screenshot verdict to §7 is caught" "$sb"

# ── M5: Add screenshot reference to §12 exit criteria ───────────────────────

sb=$(setup_sandbox "m5-screenshot-in-s12")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
# Inject a screenshot reference into §12
sed -i.bak '/^## 12\./a\
截图必须全部有效（非黑屏、非陈旧）才可 PASS。' "$rb"
assert_fail "M5 adding screenshot to §12 exit criteria is caught" "$sb"

# ── M6: Remove non-participation disclaimer from item 7 ─────────────────────

sb=$(setup_sandbox "m6-no-disclaimer")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/不参与 §7 判定//g; s/不影响 §12 退出标准//g' "$rb"
assert_fail "M6 removing item-7 non-participation disclaimer is caught" "$sb"

# ── Summary ──────────────────────────────────────────────────────────────────

TOTAL=$((POS + NEG))
echo ""
echo "selftest-runbook-evidence-tiers: $TOTAL cases ($POS positive, $NEG negative), $FAILURES failures"
if [ "$MUT" -gt 0 ]; then
  echo "  ⚠️  $MUT mutation(s) survived — the checker has a gap"
fi
if [ "$FAILURES" -gt 0 ]; then
  exit 1
fi
