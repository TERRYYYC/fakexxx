#!/usr/bin/env bash
# check-derived-counts.sh -- the §10.1 ledger is the only source of derived counts.
#
# WHY THIS EXISTS.
# Every count that appears in prose (§15 lane selectors, §16 task scopes, the
# aggregation table, the class-responsibility table) is a CACHE of a number that
# is actually derived from the §10.1 ledger rows. Prose-to-prose synchronisation
# of those caches has now failed four separate times:
#
#   v1.38  appended M-AD-01..11, synced the ledger, left the lane selector table
#   v1.39  fixed the lane selector table, left the aggregation and class tables
#          20 lines away -- while its own note claimed all four were repaired
#   v1.46  recomputed 29 cache points by hand and still missed one
#   v1.46  the miss was a SECOND stale number later in a line already edited in
#          the same pass, which is the exact failure mode that commit's own
#          message called out by name
#
# Four rounds is not carelessness, it is a missing guard. v1.46 froze the rule
# "derived counts may only be computed from the §10.1 ledger" and shipped it
# with nothing enforcing it -- a documented decision with no guard is a former
# decision. This is the guard.
#
# WHAT THIS DOES NOT DO.
# It cannot tell whether a count is semantically the right thing to state. It
# recomputes the ledger truth and fails when an ACTIVE prose cache disagrees.
# Historical revision records (§0.1) are frozen history and are excluded by
# design: rewriting them would destroy the audit trail that shows how each
# count drifted.
set -uo pipefail

SPEC="${1:-feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md}"
FAILURES=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

if [ ! -f "$SPEC" ]; then
  printf 'check-derived-counts: spec not found: %s\n' "$SPEC" >&2
  exit 2
fi

# ---------------------------------------------------------------- ledger truth
# A §10.1 ledger row is: | `M-XX-NN` | category | `class` | owner | `entry` |
# A §10 matrix row is the same ID with NO evidence class -- that is what
# distinguishes the two tables, and it is why an ID-prefix match alone is not
# enough to address a row (a lesson from v1.46: the first attempt at inserting
# these rows matched both tables and was caught only by an assertion).
ledger_rows() {
  grep -E '^\| `M-[A-Z]{2}-[0-9]+` \| [a-z-]+ \| `(owner-red|sol-blackbox|static-guard|device)` \|' "$SPEC"
}
matrix_rows() {
  grep -E '^\| `M-[A-Z]{2}-[0-9]+` \| [a-z-]+ \|' "$SPEC" \
    | grep -vE '`(owner-red|sol-blackbox|static-guard|device)`'
}

TOTAL=$(ledger_rows | wc -l | tr -d ' ')
MATRIX=$(matrix_rows | wc -l | tr -d ' ')
RED=$(ledger_rows | grep -cE '`owner-red`')
BLACKBOX=$(ledger_rows | grep -cE '`sol-blackbox`')
STATIC=$(ledger_rows | grep -cE '`static-guard`')
DEVICE=$(ledger_rows | grep -cE '`device`')
PR3=$(ledger_rows | grep -E '`owner-red`' | grep -E '\| Fable5 \|' | grep -c 'apps/qianwangyou/')
PR4=$(ledger_rows | grep -E '`owner-red`' | grep -E '\| GLM \|' | grep -c 'apps/cellrebel-auto/')

printf '\n== derived from the §10.1 ledger ==\n'
printf '  ledger=%s matrix=%s owner-red=%s (pr-3=%s pr-4=%s) blackbox=%s static=%s device=%s\n' \
  "$TOTAL" "$MATRIX" "$RED" "$PR3" "$PR4" "$BLACKBOX" "$STATIC" "$DEVICE"

printf '\n== 1. the two tables describe the same row set ==\n'
if [ "$TOTAL" -eq "$MATRIX" ]; then
  pass "§10 and §10.1 agree on row count ($TOTAL)"
else
  fail "§10 has $MATRIX rows but §10.1 has $TOTAL -- one table was edited alone"
fi

DUPES=$(ledger_rows | sed -E 's/^\| `(M-[A-Z]{2}-[0-9]+)`.*/\1/' | sort | uniq -d)
if [ -z "$DUPES" ]; then
  pass "no duplicate ledger row IDs"
else
  fail "duplicate ledger row IDs: $(echo "$DUPES" | tr '\n' ' ')"
fi

printf '\n== 2. class partition is exhaustive ==\n'
SUM=$((RED + BLACKBOX + STATIC + DEVICE))
if [ "$SUM" -eq "$TOTAL" ]; then
  pass "owner-red $RED + blackbox $BLACKBOX + static $STATIC + device $DEVICE = $TOTAL"
else
  fail "class counts sum to $SUM but the ledger has $TOTAL rows -- a row carries an unknown class"
fi

if [ "$((PR3 + PR4))" -eq "$RED" ]; then
  pass "lane selectors partition owner-red: pr-3 $PR3 + pr-4 $PR4 = $RED"
else
  fail "pr-3 $PR3 + pr-4 $PR4 = $((PR3 + PR4)) but owner-red is $RED -- a lane selector lost rows"
fi

# ------------------------------------------------- active prose cache scanning
# §0.1's revision records are frozen history. Everything at or after §7 is
# active normative text whose counts must agree with the ledger.
ACTIVE_FROM=$(grep -n '^## 7\.' "$SPEC" | head -1 | cut -d: -f1)
[ -z "$ACTIVE_FROM" ] && ACTIVE_FROM=2000

# Lines that quote a superseded number on purpose. These must SAY they are
# quoting; a bare stale number is never allowed to hide behind this.
# NARROW ON PURPOSE. The first version of this guard also excluded any line
# matching 'v1.NN ', which silently exempted the very line the guard was written
# to catch ("...evidence audit（v1.23 新增）...其中 36 行...因此这 33 行..."):
# a version CITATION is not a historical quote. Only an explicit statement that
# the line is quoting superseded text may exempt it.
HISTORY_MARKER='更正|旧文|逐字引用|上一版|此前写作|此前只列'

printf '\n== 3. active prose caches agree with the ledger ==\n'
# Scoped to lines that actually reference the ledger's own vocabulary. An
# earlier version scanned every "<n> 行" in active text and drowned in false
# positives from unrelated domains that happen to count rows. `owner-red` /
# 台账 / 矩阵 are the tokens that make a number a CACHE of this ledger.
#
# Every such number on those lines must be one the ledger can produce. That is
# what catches a second stale number further along a line whose first number was
# already fixed -- the v1.46 failure mode.
LEGAL=" $TOTAL $RED $PR3 $PR4 $BLACKBOX $STATIC $DEVICE $((BLACKBOX + STATIC + DEVICE)) "
# Frozen: the appid-cutover rows moved to Issue #13 and are not ledger rows.
APPID_CUTOVER_ROWS=5
if ledger_rows | grep -q "appid-cutover"; then
  fail "appid-cutover rows are back in the ledger -- §16 says they were split to Issue #13"
fi
BAD=$(awk -v from="$ACTIVE_FROM" 'NR>=from' "$SPEC" \
  | grep -nE '`owner-red`|台账|矩阵行' \
  | grep -E '[0-9]+ (行|个 `owner-red`)' \
  | grep -vE "$HISTORY_MARKER" \
  | while IFS= read -r line; do
      # `appid-cutover` rows were SPLIT OUT of the ledger to Issue #13, so a
      # count of them is by definition not a cache of a current ledger number.
      # Allowed only on a line that names them, rather than by widening LEGAL
      # for every line (which would let a stale 5 hide anywhere).
      line_legal="$LEGAL"
      if printf '%s' "$line" | grep -q 'appid-cutover'; then
        line_legal="$LEGAL$APPID_CUTOVER_ROWS "
      fi
      nums=$(printf '%s' "$line" | grep -oE '[0-9]+ (行|个 `owner-red`)' | grep -oE '^[0-9]+')
      for n in $nums; do
        if [[ "$line_legal" != *" $n "* ]]; then
          printf '%s\n' "$line"
          break
        fi
      done
    done)

if [ -z "$BAD" ]; then
  pass "every active count is a value the ledger can produce"
else
  fail "active prose states counts the ledger cannot produce:"
  printf '%s\n' "$BAD" | cut -c1-190 | sed 's/^/          /'
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'check-derived-counts: PASS (ledger is the single source; %s rows)\n' "$TOTAL"
  exit 0
fi
printf 'check-derived-counts: FAIL (%s check(s) failed)\n' "$FAILURES"
exit 1
