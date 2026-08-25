#!/usr/bin/env bash
#
# check-runbook-evidence-tiers.sh — verify the G1 smoke runbook's evidence-tier
# structural invariants (F-19).
#
# Why this exists:
# §11-7 originally said "每步探针屏幕截图" with no validity definition and the
# same "缺项即不可判定" header as judgment-bearing items.  C5 run#2 proved three
# failure modes — black screen, stale frame, transition half-frame — all pass
# naïve checks (file count, hash uniqueness, non-black count) yet carry zero
# evidence value.  The fix tiers §11 items into Tier A (judgment-bearing, §7/§12
# consume) and Tier B (auxiliary).  This script prevents the invariants from
# silently regressing.
#
# What it checks:
#   1. §7 (verdict criteria) does NOT reference screenshots as judgment inputs
#   2. §12 (exit criteria) does NOT reference screenshots as judgment inputs
#   3. §11 contains the two-tier classification (Tier A / Tier B)
#   4. §11 item 7 (screenshots) is classified as Tier B / 辅助附件
#   5. The three known failure modes are documented in §11
#
# Exit codes: 0 = all checks passed; 1 = at least one check failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNBOOK="$REPO_ROOT/docs/acceptance/issue7-auto-qwy-g1-smoke-runbook.md"
PASS=0
FAIL=0

check() {
  local label="$1" result="$2"
  if [ "$result" -eq 0 ]; then
    echo "  PASS  $label"
    PASS=$((PASS + 1))
  else
    echo "  FAIL  $label"
    FAIL=$((FAIL + 1))
  fi
}

if [ ! -f "$RUNBOOK" ]; then
  echo "FATAL: runbook not found at $RUNBOOK"
  exit 1
fi

echo "check-runbook-evidence-tiers: $RUNBOOK"
echo ""

# ── Helper: extract a section by its ## heading ──────────────────────────────

# Extract text between two ## headings (or to EOF if last section).
# Usage: section "## 7." → all lines from that heading to the next ## heading.
extract_section() {
  local heading="$1"
  awk -v h="$heading" '
    BEGIN { found=0 }
    $0 ~ "^## " && found { exit }
    $0 ~ "^"h { found=1 }
    found { print }
  ' "$RUNBOOK"
}

# ── 1. §7 must NOT reference screenshots as judgment inputs ──────────────────

S7=$(extract_section "## 7\\.")
if [ -z "$S7" ]; then
  check "§7 section found (extract_section)" 1
else
  # These terms appearing in §7 would indicate screenshots are being used as
  # judgment criteria.  Bidirectional: catch both "截图…PASS" and "PASS…截图".
  S7_SCREENSHOT_REFS=$(echo "$S7" | grep -ciE '截图.*(PASS|FAIL|判据|判定|通过|失败)|(PASS|FAIL|判据|判定|通过|失败).*截图|screenshot.*(PASS|FAIL|verdict|criteria)|(PASS|FAIL|verdict|criteria).*screenshot' || true)
  check "§7 does not use screenshots as verdict criteria" "$( [ "$S7_SCREENSHOT_REFS" -eq 0 ] && echo 0 || echo 1 )"
fi

# ── 2. §12 must NOT reference screenshots ────────────────────────────────────

S12=$(extract_section "## 12\\.")
if [ -z "$S12" ]; then
  check "§12 section found (extract_section)" 1
else
  S12_SCREENSHOT_REFS=$(echo "$S12" | grep -ciE '截图|screenshot|screencap|\.png' || true)
  check "§12 does not reference screenshots" "$( [ "$S12_SCREENSHOT_REFS" -eq 0 ] && echo 0 || echo 1 )"
fi

# ── 3. §11 contains two-tier classification ──────────────────────────────────

S11=$(extract_section "## 11\\.")
if [ -z "$S11" ]; then
  check "§11 section found (extract_section)" 1
  # All downstream checks depend on §11 existing; fail fast.
  echo ""
  echo "check-runbook-evidence-tiers: 0 passed, 1 failed (§11 not found)"
  exit 1
fi
HAS_TIER_A=$(echo "$S11" | grep -c 'Tier A' || true)
HAS_TIER_B=$(echo "$S11" | grep -c 'Tier B' || true)
check "§11 defines Tier A classification" "$( [ "$HAS_TIER_A" -ge 1 ] && echo 0 || echo 1 )"
check "§11 defines Tier B classification" "$( [ "$HAS_TIER_B" -ge 1 ] && echo 0 || echo 1 )"

HAS_JUDGMENT_BEARING=$(echo "$S11" | grep -c '判定承重' || true)
HAS_AUXILIARY=$(echo "$S11" | grep -c '辅助附件' || true)
check "§11 labels Tier A as 判定承重" "$( [ "$HAS_JUDGMENT_BEARING" -ge 1 ] && echo 0 || echo 1 )"
check "§11 labels Tier B as 辅助附件" "$( [ "$HAS_AUXILIARY" -ge 1 ] && echo 0 || echo 1 )"

# ── 4. §11-7 is in Tier B (not承重) ──────────────────────────────────────────

# Item 7 must NOT appear in the Tier A table, and MUST appear in the Tier B
# section.  We check by looking for "| 7 |" rows in each sub-section.
TIER_A_SECTION=$(echo "$S11" | awk '/^### Tier A/,/^### Tier B/' )
TIER_B_SECTION=$(echo "$S11" | awk '/^### Tier B/,/^## [0-9]/' )

ITEM7_IN_A=$(echo "$TIER_A_SECTION" | grep -c '| 7 |' || true)
ITEM7_IN_B=$(echo "$TIER_B_SECTION" | grep -c '| 7 |' || true)
check "§11 item 7 is NOT in Tier A table" "$( [ "$ITEM7_IN_A" -eq 0 ] && echo 0 || echo 1 )"
check "§11 item 7 IS in Tier B table" "$( [ "$ITEM7_IN_B" -ge 1 ] && echo 0 || echo 1 )"

# Item 7's description must contain non-participation disclaimer
ITEM7_LINE=$(echo "$S11" | grep '| 7 |' | head -1)
HAS_NO_VERDICT=$(echo "$ITEM7_LINE" | grep -c '不参与 §7 判定' || true)
HAS_NO_EXIT=$(echo "$ITEM7_LINE" | grep -c '不影响 §12' || true)
check "§11-7 declares non-participation in §7 verdict" "$( [ "$HAS_NO_VERDICT" -ge 1 ] && echo 0 || echo 1 )"
check "§11-7 declares non-impact on §12 exit criteria" "$( [ "$HAS_NO_EXIT" -ge 1 ] && echo 0 || echo 1 )"

# ── 5. Three failure modes documented ────────────────────────────────────────

HAS_BLACK_SCREEN=$(echo "$S11" | grep -c '黑屏' || true)
HAS_STALE_FRAME=$(echo "$S11" | grep -c '陈旧帧' || true)
HAS_TRANSITION_FRAME=$(echo "$S11" | grep -c '转场半帧' || true)
check "§11 documents black-screen failure mode" "$( [ "$HAS_BLACK_SCREEN" -ge 1 ] && echo 0 || echo 1 )"
check "§11 documents stale-frame failure mode" "$( [ "$HAS_STALE_FRAME" -ge 1 ] && echo 0 || echo 1 )"
check "§11 documents transition-half-frame failure mode" "$( [ "$HAS_TRANSITION_FRAME" -ge 1 ] && echo 0 || echo 1 )"

# ── Summary ──────────────────────────────────────────────────────────────────

echo ""
echo "check-runbook-evidence-tiers: $PASS passed, $FAIL failed"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
