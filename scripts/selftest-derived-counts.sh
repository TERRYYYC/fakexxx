#!/usr/bin/env bash
#
# selftest-derived-counts.sh — regression gate for check-derived-counts.sh.
#
# WHY THIS EXISTS.
# check-derived-counts.sh shipped green for rounds while four notations of
# real cache sites sat in its blind spot, and nothing asked it to fail: the
# same "a guard that closed a gap was never measured" shape that
# selftest-contract-v1.sh recorded for §5/§6b/§7b. A guard whose PASS is
# measured in the units of its own recognition looks exactly like coverage
# until someone plants each notation it claims to read.
#
# So each case mutates a throwaway copy of the spec, runs the PRODUCTION
# guard, and asserts the SPECIFIC finding (arm + value) that the planted
# drift must provoke -- not merely that the guard went red. A guard that is
# red anyway "catches" every plant for free, so planted values are chosen
# DISTINCT from every value the pristine tree already reports.
#
# The M-* cases close the remaining hole: an N-case proves "red while the
# drift is present", not WHICH arm caught it. Each M-* disables exactly one
# named arm in the production guard (the arms are knobs for precisely this
# reason), re-plants the matching drift, and requires that arm's finding to
# DISAPPEAR. Disabling two arms at once would prove neither -- the rule the
# v1.49 revision recorded when it extracted the BOLD tolerance into a knob.
#
# P-1 WAS deliberately inverted while the recompute was still outstanding: the
# guard was EXPECTED red, because the caches it could newly see were genuinely
# stale and the recompute belonged to the mainline (F4b), not to that branch.
# That inversion carried an explicit expiry -- "when the mainline recompute
# lands, P-1 must flip to asserting green in the same commit". The recompute
# landed at v1.60 and P-1 is flipped. Recorded rather than deleted because a
# lifecycle instruction that vanishes on completion leaves no way to tell a
# discharged plan from one nobody ever executed.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-derived-counts.sh"
SPEC="feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md"

POS=0; NEG=0; MUT=0; FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
detail() { printf '%s\n' "$1" | grep -E 'FAIL|=>|check-derived-counts:' | sed 's/^/          /'; }

command -v python3 >/dev/null 2>&1 || { printf 'selftest-derived-counts: python3 required\n' >&2; exit 1; }

# A throwaway copy of everything the guard reads, taken from the WORKING TREE
# (not HEAD): a weakened guard is cheapest to catch exactly when it is uncommitted.
mk() {
  local d; d="$(mktemp -d)"
  mkdir -p "$d/scripts" "$d/$(dirname "$SPEC")"
  cp "$PROD" "$d/scripts/check-derived-counts.sh"
  chmod +x "$d/scripts/check-derived-counts.sh"
  cp "$REPO_ROOT/$SPEC" "$d/$SPEC"
  printf '%s\n' "$d"
}

# Exact-count-1 replacement: an anchor matching 0 times means the case never
# ran; matching more than once means it edited more than it claims. Both are
# setup failures and both must be loud.
apply() { # $1=dir $2=relpath $3=old $4=new
  F="$1/$2" OLD="$3" NEW="$4" python3 - <<'PY'
# -*- coding: utf-8 -*-
import io, os, sys
p = os.environ["F"]
t = io.open(p, encoding="utf-8").read()
old, new = os.environ["OLD"], os.environ["NEW"]
n = t.count(old)
if n != 1:
    sys.stderr.write("anchor matched %d time(s), expected exactly 1\n" % n)
    sys.exit(1)
io.open(p, "w", encoding="utf-8").write(t.replace(old, new))
PY
}

run_gate() { ( cd "$1" && ./scripts/check-derived-counts.sh 2>&1 ); }

# ---------------------------------------------------------------------------
printf '\n== positive ==\n'

# P-1: FLIPPED at v1.60, exactly as this file's header required -- the mainline
# recompute landed, so the pristine tree must now be GREEN. What is pinned is no
# longer a list of surviving defects but two properties that outlive any recount:
#   - zero stale sites, and
#   - the per-arm ENUMERATION still printed.
# The enumeration is the load-bearing half. "0 stale sites" is also what a guard
# that stopped looking would print, and those two states are indistinguishable
# from the verdict line alone; the arm enumeration is the only thing that shows
# the guard still SEES the sites it is calling clean. Same reason the guard's own
# author widened it: enumerate, never count.
D="$(mk)"
OUT="$(run_gate "$D")"
P1_REQUIRED=(
  '    bare :'  '    cn   :'  '    cell :'  '    redct:'
  '=> section 3: 0 stale cache site(s)'
  'check-derived-counts: PASS'
)
p1_ok=1
for needle in "${P1_REQUIRED[@]}"; do
  if ! printf '%s' "$OUT" | grep -qF -- "$needle"; then
    bad "P-1 pristine tree lost a required property: '$needle'"
    p1_ok=0
  fi
done
# bold is pinned the other way round: the pristine tree has ZERO bold-notation
# sites, which is what makes N-B (a planted bold site) meaningful at all.
if printf '%s' "$OUT" | grep -qF 'bold :'; then
  :
fi
if ! printf '%s' "$OUT" | grep -qF 'bold : (no site'; then
  bad "P-1 the enumeration no longer shows bold's empty-site state"
  p1_ok=0
fi
if [ "$p1_ok" -eq 1 ]; then
  ok "P-1 pristine tree: guard green with zero stale sites, all arms still enumerated"
  POS=$((POS + 1))
fi
rm -rf "$D"

# ---------------------------------------------------------------------------
printf '\n== negative (a planted notation drift; the guard must name it) ==\n'

neg() { # $1=label $2=old $3=new $4=expected finding substring
  local d out
  d="$(mk)"
  if ! apply "$d" "$SPEC" "$2" "$3" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if ! printf '%s' "$out" | grep -qF -- "$4"; then
    bad "$1 - guard never reported the planted finding: '$4'"
    detail "$out"
  else
    ok "$1"
    NEG=$((NEG + 1))
  fi
  rm -rf "$d"
}

# N-A: class 3 + class 1 combined -- a plain-text owner-red verify-comment,
# invisible to the old backticked selector, carrying a bare "<n> 行".
neg "N-A plain-text owner-red + bare count (39 -> planted 35)" \
  '# 39 行 owner-red' \
  '# 35 行 owner-red' \
  ' bare 35 '

# N-B: class 2 -- bold-wrapped digits where the number never touches 行 in
# plain form. The pristine tree has no bold site at all, so this plant is the
# ONLY thing exercising the bold arm's existence.
neg "N-B bold-wrapped count (**86** 行)" \
  '**只出现在 `pr-6`**。' \
  '**只出现在 `pr-6`**（另见 **86** 行注记）。' \
  ' bold 86 '

# N-C: class 4 -- Chinese numerals. 90 appears nowhere else as a live claim
# (the 90 on correction blockquotes is exempt), so the plant is unambiguous.
neg "N-C Chinese-numeral count (九十行)" \
  '，但仍**零覆盖** proof/CAS provider 侧判定' \
  '、峰值曾达九十行，但仍**零覆盖** proof/CAS provider 侧判定' \
  ' cn 90 '

# N-D: class 1 -- a bare digit cell in the class-responsibility table.
neg "N-D bare digit cell (sol-blackbox 22 -> planted 23)" \
  '| `sol-blackbox` | 22 | 编写并执行' \
  '| `sol-blackbox` | 23 | 编写并执行' \
  ' cell 23 '

# N-E: "<n> 个 owner-red" with NO backticks -- the plain half of the redct arm
# the old extractor could not read. The pristine tree has zero plain-redct
# sites, so this plant is the only coverage of the widening.
neg "N-E plain '个 owner-red' count (85)" \
  '做 evidence audit——核对 evidence manifest 中该 ID' \
  '做 evidence audit（覆盖 85 个 owner-red）——核对 evidence manifest 中该 ID' \
  ' redct 85 '

# N-F: bold-wrapped CELL (**37**) -- cell arm must tolerate bold inside the
# cell, which is how the real aggregation table spells its counts.
neg "N-F bold digit cell (**39** -> planted **37**)" \
  '| **39** |' \
  '| **37** |' \
  ' cell 37 '

# ---------------------------------------------------------------------------
printf '\n== mutation (disable one named arm; its finding must vanish) ==\n'

mut() { # $1=label $2=sed expr against the guard $3=old $4=new $5=finding that must disappear
  local d out base

  # Intact gate must produce the finding first: an arm that never fires also
  # "loses" its finding when disabled, and would pass as load-bearing on the
  # strength of an absence that was already there.
  base="$(mk)"
  if ! apply "$base" "$SPEC" "$3" "$4" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply to the intact gate; the case never ran"
    rm -rf "$base"; return
  fi
  out="$(run_gate "$base")"
  rm -rf "$base"
  if ! printf '%s' "$out" | grep -qF -- "$5"; then
    bad "$1 - INCONCLUSIVE: the intact gate never produced '$5', so its disappearance proves nothing"
    detail "$out"
    return
  fi

  d="$(mk)"
  # sed reports success even when its pattern matched nothing; the file having
  # actually changed is the evidence the arm was disabled.
  if ! sed -i.bak "$2" "$d/scripts/check-derived-counts.sh" || \
     cmp -s "$d/scripts/check-derived-counts.sh" "$d/scripts/check-derived-counts.sh.bak"; then
    bad "$1 - INCONCLUSIVE: the arm-disabling edit did not change the guard"
    rm -rf "$d"; return
  fi
  rm -f "$d/scripts/check-derived-counts.sh.bak"
  if ! apply "$d" "$SPEC" "$3" "$4" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if printf '%s' "$out" | grep -qF -- "$5"; then
    bad "$1 - finding survived with the arm disabled, so that arm is not what catches it"
    detail "$out"
  else
    ok "$1 - disabling it makes the finding disappear, so the arm is load-bearing"
    MUT=$((MUT + 1))
  fi
  rm -rf "$d"
}

mut "M-BARE bare-count arm catches N-A" \
  's/^ARM_BARE = .*/ARM_BARE = None/' \
  '# 39 行 owner-red' \
  '# 35 行 owner-red' \
  ' bare 35 '

mut "M-BOLD bold arm catches N-B" \
  's/^ARM_BOLD = .*/ARM_BOLD = None/' \
  '**只出现在 `pr-6`**。' \
  '**只出现在 `pr-6`**（另见 **86** 行注记）。' \
  ' bold 86 '

mut "M-CN chinese-numeral arm catches N-C" \
  's/^ARM_CN = .*/ARM_CN = None/' \
  '，但仍**零覆盖** proof/CAS provider 侧判定' \
  '、峰值曾达九十一行，但仍**零覆盖** proof/CAS provider 侧判定' \
  ' cn 91 '

# NOTE: M-CN plants 91 (N-C planted 90) so each case's finding substring is
# distinct and one case's output can never satisfy another's assertion.
mut "M-CELL keyed-cell arm catches N-D" \
  's/^CELL_KEYS = .*/CELL_KEYS = ()/' \
  '| `sol-blackbox` | 22 | 编写并执行' \
  '| `sol-blackbox` | 23 | 编写并执行' \
  ' cell 23 '

mut "M-REDCT owner-red-count arm catches N-E" \
  's/^ARM_REDCT = .*/ARM_REDCT = None/' \
  '做 evidence audit——核对 evidence manifest 中该 ID' \
  '做 evidence audit（覆盖 85 个 owner-red）——核对 evidence manifest 中该 ID' \
  ' redct 85 '

# M-PLAIN-SCOPE pins the class-3 widening itself: without plain-text owner-red
# in the scope selector, the whole verify-comment line leaves scope and BOTH of
# its counts (planted 35 and pristine 36/112) become invisible.
mut "M-PLAIN-SCOPE plain-owner-red scoping catches N-A" \
  's/^SCOPE_PLAIN_OWNER_RED = .*/SCOPE_PLAIN_OWNER_RED = ""/' \
  '# 39 行 owner-red' \
  '# 35 行 owner-red' \
  ' bare 35 '

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-derived-counts: PASS (%d positive, %d negative, %d mutation self-check(s) — every case ran against the production guard)\n' \
    "$POS" "$NEG" "$MUT"
  exit 0
fi
printf 'selftest-derived-counts: FAIL (%d failure(s) across %d executed case(s))\n' "$FAILURES" "$((POS + NEG + MUT))"
exit 1
