#!/usr/bin/env bash
#
# selftest-runbook-evidence-tiers.sh — regression gate for check-runbook-evidence-tiers.sh.
#
# Every case runs the PRODUCTION script against a mutated copy of the runbook
# and asserts its exit code.  No case inspects source text — grepping the
# checker for a literal proves the line exists, not that it does anything.
#
# Mutation classes (F-19 real-world failure modes that must stay killed):
#
# Structural regressions:
#   M1   Remove Tier B entirely → item 7 silently rejoins judgment-bearing items
#   M2   Move item 7 into Tier A table → screenshots become承重 again
#   M3   Remove failure-mode documentation → naïve checks appear sufficient
#   M4   Add screenshot reference to §7 (same line) → false-green surface
#   M4b  Add screenshot reference to §7 (split line) → split-line bypass (P2-1)
#   M5   Add screenshot reference to §12 exit criteria → false-green surface
#   M6   Remove non-participation disclaimer from item 7 → weight ambiguity
#
# Isolated guard kills (P2-4/P3/P4: each guard must be independently load-bearing):
#   M7   Remove only "### Tier A" heading (keep Tier B) → classification heading guard
#   M8   Remove only "判定承重" label (keep ### Tier A) → Tier A label guard
#   M9   Remove "设备证据报告文件" from summary line → report-file term guard
#   M10  Add concatenation term to summary line → anti-concat guard
#   M11  Remove §10.1 reference from definition block → spec-authority guard
#   M12  Modify ASSERT line value → ASSERT exact-match guard (G18)
#   M13  Add raw-evidence term to summary (reversed word order) → category-ban guard (G15)
#   M14  Remove ASSERT line entirely → ASSERT exact-match guard (G18)
#   M15  Remove CANONICAL boundary markers → marker integrity guard (G17)
#   M16  Duplicate marker pair (2× START, 2× END) → marker integrity guard (G17)
#   M17  Reversed marker order (END before START) → marker integrity guard (G17)
#   M18  Markers inside fenced code block (decoy) → marker integrity guard (G17)
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
# Inject a line into §7 that mentions screenshots (fail-closed: any mention = fail)
sed -i.bak '/^## 7\./a\
截图显示 PASS 界面且对应日志一致时判定 PASS。' "$rb"
assert_fail "M4 adding screenshot mention to §7 is caught (fail-closed)" "$sb"

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

# ── M4b: Split-line screenshot→verdict in §7 (P2-1) ─────────────────────────

sb=$(setup_sandbox "m4b-split-line-s7")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
# Two adjacent lines: "截图条件" on one, "判定 PASS" on the next.
# The old same-line grep missed this; fail-closed catches it.
sed -i.bak '/^## 7\./a\
截图条件：每步必须附有效截图。\
只有此条件满足后，才可判定 PASS。' "$rb"
assert_fail "M4b split-line screenshot verdict in §7 is caught (P2-1)" "$sb"

# ── M7: Remove only "### Tier A" heading (isolated guard, P2-4/P3) ──────────
#
# Targets the heading only (s/^### Tier A/), NOT a global s/Tier A/ — the old
# global replacement also hit the digest definition block, triggering both the
# classification guard AND the digest guard.  Heading-specific replacement
# isolates M7 to the classification guard (Luna P3-1).

sb=$(setup_sandbox "m7-no-tier-a-heading")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/^### Tier A/### Tier-Primary/' "$rb"
assert_fail "M7 removing ### Tier A heading is caught (isolated, P2-4/P3)" "$sb"

# ── M8: Remove only "判定承重" label (isolated guard, P2-4) ──────────────────

sb=$(setup_sandbox "m8-no-judgment-label")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/判定承重/证据必填/g' "$rb"
assert_fail "M8 removing 判定承重 label is caught (isolated, P2-4)" "$sb"

# ── M9: Remove "设备证据报告文件" from summary line (P4) ────────────────────
#
# The summary line must contain "设备证据报告文件" to prove reportDigest refers
# to the report FILE (§10.1), not concatenated evidence bytes.  Shortening it
# to "设备证据" drops the file reference.

sb=$(setup_sandbox "m9-no-report-file-term")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/设备证据报告文件/设备证据/' "$rb"
assert_fail "M9 removing 设备证据报告文件 from summary is caught (P4)" "$sb"

# ── M10: Add concatenation term to summary line (P4) ────────────────────────
#
# The summary line must NOT contain terms implying byte concatenation (拼接,
# 串接, logcat.*截图, Tier A.*字节).  Appending such a term should trigger
# the anti-concat guard without affecting the report-file-term guard.

sb=$(setup_sandbox "m10-concat-in-summary")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/设备证据报告文件的 SHA-256/设备证据报告文件与拼接截图的 SHA-256/' "$rb"
assert_fail "M10 adding concatenation term to summary is caught (P4)" "$sb"

# ── M11: Remove §10.1 reference from definition block (P4) ─────────────────
#
# The definition block must reference §10.1 as the canonical authority for
# reportDigest semantics.  Removing that reference should kill the spec-
# authority guard without affecting the summary-line guards.

sb=$(setup_sandbox "m11-no-spec-ref")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/§10\.1/§10/g' "$rb"
assert_fail "M11 removing §10.1 from definition block is caught (P4)" "$sb"

# ── M12: Modify ASSERT line value (R6, Luna) ──────────────────────────────
#
# The ASSERT line is the machine-checkable canonical record.  Changing any
# key-value pair (here: preimage=report-file → preimage=byte-concat) must
# be caught by Guard 18's exact-string match.  Prose around it is irrelevant.

sb=$(setup_sandbox "m12-modify-assert-value")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/preimage=report-file/preimage=byte-concat/' "$rb"
assert_fail "M12 modifying ASSERT line value is caught (G18)" "$sb"

# ── M13: Add raw-evidence term to summary in reversed word order (P5-2) ───
#
# The old pattern "logcat.*截图" missed reversed word order ("截图+logcat").
# Guard 15 now bans individual raw-evidence terms (截图, logcat, .png, .log) on
# the summary line — same category-ban principle as §7.  M13 adds "截图" to the
# summary without adding any concatenation term, proving the individual-term ban
# is load-bearing.  Isolated to guard 15.

sb=$(setup_sandbox "m13-reversed-word-order")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak 's/设备证据报告文件的 SHA-256/设备证据报告文件与截图的 SHA-256/' "$rb"
assert_fail "M13 reversed-word-order raw-evidence term in summary is caught (P5-2)" "$sb"

# ── M14: Remove ASSERT line entirely (R6, Luna) ───────────────────────────
#
# Deleting the ASSERT line while keeping the CANONICAL markers and all prose
# intact must be caught.  Guard 18 checks for the exact ASSERT string inside
# the bounded block — absent line → no match → fail.

sb=$(setup_sandbox "m14-remove-assert-line")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak '/ASSERT:reportDigest/d' "$rb"
assert_fail "M14 removing ASSERT line is caught (G18)" "$sb"

# ── M15: Remove CANONICAL boundary markers (R5) ───────────────────────────
#
# Without the CANONICAL:reportDigest:START/END markers, the checker cannot
# extract the bounded block → Guard 17 fails.  Guards 18–19 pass vacuously
# (no block to check).  Isolated to Guard 17.

sb=$(setup_sandbox "m15-no-markers")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
sed -i.bak '/CANONICAL:reportDigest/d' "$rb"
assert_fail "M15 removing CANONICAL markers is caught (G17)" "$sb"

# ── M16: Duplicate marker pair (R6, Luna) ──────────────────────────────────
#
# Adding a second START/END pair creates ambiguity — the checker cannot know
# which block is canonical.  Guard 17 requires exactly 1 START + 1 END.
# Two of each → cardinality check fails.

sb=$(setup_sandbox "m16-duplicate-markers")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
python3 -c "
with open('$rb') as f: t = f.read()
# Append a second marker pair after the existing END marker
t = t.replace(
    '> <!-- CANONICAL:reportDigest:END -->',
    '> <!-- CANONICAL:reportDigest:END -->\n> <!-- CANONICAL:reportDigest:START -->\n> decoy\n> <!-- CANONICAL:reportDigest:END -->'
)
with open('$rb','w') as f: f.write(t)
"
assert_fail "M16 duplicate marker pair is caught (G17)" "$sb"

# ── M17: Reversed marker order (R6, Luna) ─────────────────────────────────
#
# Swapping START↔END (END appears first) must be caught.  Guard 17 checks
# START line < END line.

sb=$(setup_sandbox "m17-reversed-markers")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
python3 -c "
with open('$rb') as f: t = f.read()
t = t.replace('CANONICAL:reportDigest:START', 'CANONICAL:reportDigest:XSTART')
t = t.replace('CANONICAL:reportDigest:END', 'CANONICAL:reportDigest:START')
t = t.replace('CANONICAL:reportDigest:XSTART', 'CANONICAL:reportDigest:END')
with open('$rb','w') as f: f.write(t)
"
assert_fail "M17 reversed marker order is caught (G17)" "$sb"

# ── M18: Markers inside fenced code block (R6 P2-1, Luna) ─────────────────
#
# Wrapping the canonical block (markers + ASSERT line) inside a fenced code
# block (```) makes it a documentation example, not a live declaration.
# Guard 17 strips fenced code before looking for markers → no markers found
# outside fence → cardinality fails.

sb=$(setup_sandbox "m18-markers-in-fence")
rb="$sb/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
python3 -c "
with open('$rb') as f: t = f.read()
# Wrap the canonical block in a fenced code block
t = t.replace(
    '> <!-- CANONICAL:reportDigest:START -->',
    '> \`\`\`\n> <!-- CANONICAL:reportDigest:START -->'
)
t = t.replace(
    '> <!-- CANONICAL:reportDigest:END -->',
    '> <!-- CANONICAL:reportDigest:END -->\n> \`\`\`'
)
with open('$rb','w') as f: f.write(t)
"
assert_fail "M18 markers inside fenced code block is caught (G17, R6 P2-1)" "$sb"

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
