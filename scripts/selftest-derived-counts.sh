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
# The provenance banner is part of the diagnosis, not noise: when P-4 fails, the
# one thing worth reading is which file the guard actually opened.
detail() { printf '%s\n' "$1" | grep -E 'FAIL|=>|check-derived-counts:|^  spec:|^  git HEAD|not a git' | sed 's/^/          /'; }

# healthy() proves the run FINISHED. verdict() says what it CONCLUDED, and the
# two are different questions -- conflating them is what let the suite stay green
# while the production guard could no longer block anything: every negative case
# asserted that the finding was PRINTED, none asserted that the gate came out
# FAIL. A guard that sees every stale site and exits 0 satisfied all ten.
verdict() { # $1=gate output -> PASS | FAIL | NONE
  if printf '%s' "$1" | grep -qE '^check-derived-counts: PASS'; then printf 'PASS'
  elif printf '%s' "$1" | grep -qE '^check-derived-counts: FAIL'; then printf 'FAIL'
  else printf 'NONE'; fi
}

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

# A mutated guard that CRASHES also stops printing findings, and "the finding is
# gone" is the whole assertion every mutation case makes. Without this the
# arm-census mutation passed while the guard was dying on an IndentationError:
# the case reported a load-bearing arm on the strength of a traceback. So a
# mutated run must still look like a run -- it must reach the section-3
# enumeration AND print a verdict line -- before its silence means anything.
healthy() { # $1=gate output
  printf '%s' "$1" | grep -qF '=> section 3:' && \
  printf '%s' "$1" | grep -qE '^check-derived-counts: (PASS|FAIL)'
}

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
  # pair/curval pinned too: an enumeration that quietly stops listing an arm is
  # invisible in the verdict line, and "0 stale sites" reads identical either
  # way. Leaving them unpinned let the census regress while P-1 stayed green.
  '    pair :'  '    curval:'
  '=> section 3: 0 stale cache site(s)'
  'check-derived-counts: PASS'
  # The provenance banner is pinned here for the same reason the arm
  # enumeration is: a verdict that stops naming its input is exactly as
  # readable as one that never did, and the verdict line cannot tell them
  # apart. Its content-fidelity is pinned by P-4 below.
  '== input provenance =='
  'revision marker'
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

# P-4: the provenance banner must describe the file that was SCANNED, not the
# checkout the reader assumed. This is the incident that blocked a merge gate:
# the guard was run against a spec ~30 revisions stale, its five findings were
# real cache sites in THAT file, and the reader verified their line numbers
# against the CURRENT spec -- the output carried nothing that could break the
# tie. So: APPEND a revision row to the throwaway copy carrying a version that
# exists nowhere else (v1.66 replaced the older rewrite-the-latest-row design,
# because rewriting has to know whether that row is bold or plain and that
# knowledge is exactly what diverged), and require the banner to follow the
# scanned file. A banner that reads the repo's pristine spec instead, or a
# banner deleted outright, fails here -- and so does a guard that stops
# completing a scan, because the verdict line must still be PASS.
#
# The sentinel is DERIVED, not hardcoded. It used to be a literal v1.99, which
# borrowed a version number the document will legitimately reach: the guard takes
# the MAXIMUM marker, so the day the spec passes v1.99 the sentinel stops being
# the maximum and this case reds for a reason that has nothing to do with
# provenance. Verified by bumping the live spec to v1.100 -- every real gate
# stayed green and P-4 alone went red. max+1 cannot already exist in the file by
# the definition of max, so it is collision-proof for every future revision.
# The marker domain is READ WITH THE PRODUCTION EXPRESSION, not a lookalike.
# v1.65 derived the sentinel from bold rows only (`| **v1.N** |`) while the guard
# accepts `\*{0,2}` -- bold OR plain -- and sorts with `sort -V`. On a document
# whose newest row is legitimately plain, P-4 computed max = the second-newest and
# a "max+1" that ALREADY EXISTED, so a guard hardcoding that version passed. The
# proof "max+1 cannot exist" was true over P-4's narrower syntax and was written
# as a claim about the file: the same over-wide conclusion this family keeps
# producing, one level further in.
# NOT a second copy of the production regex. v1.65 mirrored it and the mirror
# drifted within one round -- bold-only here, `\*{0,2}` there -- so P-4 derived a
# sentinel that already existed. A mirror can always drift again; asking the
# guard is the only version that cannot. The banner already publishes the maximum
# marker the PRODUCTION code computed, so read that.
#
# This also supplies the non-existence proof for free: the guard reports the
# MAXIMUM, so any version strictly greater than it is absent from the file by the
# guard's own computation -- no independent scan of the document is needed, and
# therefore no second syntax exists to disagree with the first.
p4_reported_marker() { # $1=dir -> the marker the production banner names
  run_gate "$1" | grep -oE 'revision marker v1\.[0-9]+' | grep -oE 'v1\.[0-9]+' | head -1
}

# Three assertions, factored so the negative regression below reuses the exact
# predicate P-4 passes on -- a regression case that re-implements the check it is
# guarding proves only that two copies agree.
p4_assert() { # $1=gate output $2=expected sentinel $3=pristine max -> 0 = provenance correct
  printf '%s' "$1" | grep -qF "revision marker $2" || return 1
  printf '%s' "$1" | grep -qE '^check-derived-counts: PASS' || return 1
  if printf '%s' "$1" | grep -qF "revision marker $3"; then return 1; fi
  return 0
}

D="$(mk)"
P4_MAX="$(p4_reported_marker "$D")"
P4_SENTINEL="v1.$(( ${P4_MAX#v1.} + 1 ))"
# APPEND the sentinel row instead of rewriting an existing one. Rewriting needs to
# know the row's exact form (bold or plain) and re-derives it from the document --
# reintroducing the divergence this case exists to catch. Appending needs to know
# nothing about the existing rows at all.
P4_ANCHOR='## 21. operator Decision Packets'
P4_PLANT="| **$P4_SENTINEL** | P-4 provenance probe | probe row |

$P4_ANCHOR"
if [ -z "$P4_MAX" ]; then
  bad "P-4 INCONCLUSIVE: the guard reported no revision marker, so no sentinel can be derived"
elif apply "$D" "$SPEC" "$P4_ANCHOR" "$P4_PLANT"; then
  OUT="$(run_gate "$D")"
  if p4_assert "$OUT" "$P4_SENTINEL" "$P4_MAX"; then
    ok "P-4 provenance banner names the scanned file (marker follows the copy, verdict undisturbed)"
    POS=$((POS + 1))
  else
    bad "P-4 provenance banner did not follow the scanned copy (expected $P4_SENTINEL, not $P4_MAX, verdict still PASS)"
    detail "$OUT"
  fi
else
  bad "P-4 - INCONCLUSIVE: plant did not apply; the case never ran"
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
  # Family sweep (v1.64): all three helpers assert on substrings, and a crashed
  # guard prints no substrings at all. mut() and legal() were corrected because
  # a crash made them GREEN; neg() fails safe by comparison, but it fails for the
  # WRONG REASON -- "guard never reported the planted finding" reads like a
  # missing recogniser when the guard actually died. A red whose stated cause is
  # wrong is the mirror of a false green and just as unusable on a board, which
  # is this repo's own standing rule. So every helper now proves the run finished
  # before it interprets the run.
  if ! healthy "$out"; then
    bad "$1 - INCONCLUSIVE: the guard did not complete a scan (crash?), so a missing finding proves nothing"
    detail "$out"
    rm -rf "$d"; return
  fi
  if ! printf '%s' "$out" | grep -qF -- "$4"; then
    bad "$1 - guard never reported the planted finding: '$4'"
    detail "$out"
  elif [ "$(verdict "$out")" != FAIL ]; then
    bad "$1 - the guard SAW the planted drift and did not BLOCK on it (verdict is not FAIL)"
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

# M-P4-SYNTAX: the regression for the defect P-4 itself shipped, kept after the
# rewrite because it guards a property the rewrite does not make impossible --
# FORM independence. The production banner accepts `| v1.N |` exactly as it
# accepts `| **v1.N** |`. v1.65 read only the bold form, so on a document whose
# newest row is plain it computed a "max+1" that already existed and a guard
# hardcoded to that version passed every assertion.
#
# Scenario: newest row written plain, guard reporting a FIXED version instead of
# reading the file it scanned. P-4 must catch it.
d="$(mk)"
p4r_max="$(p4_reported_marker "$d")"
if [ -z "$p4r_max" ]; then
  bad "M-P4-SYNTAX - INCONCLUSIVE: the guard reported no marker on a pristine copy"
  rm -rf "$d"
else
  # Force the plain form through apply(), which is an exact-count-1 literal
  # replacement and FAILS LOUDLY. The first version of this used sed with `|` as
  # both the delimiter and a Markdown cell character: it exited 1, the error went
  # to /dev/null, `|| true` swallowed the status, the row stayed bold -- and the
  # case still announced "form-independent". That is the fourth appearance in
  # this file of one shape: a plant that did not happen, counted as an experiment
  # that did. This time I wrote the swallow myself, one round after fixing the
  # same shape in mut().
  p4r_bold="| **${p4r_max}** |"
  p4r_plain="| ${p4r_max} |"
  p4r_converted=0
  if grep -qF -- "$p4r_bold" "$d/$SPEC"; then
    # apply() already fails loudly on anything other than exactly one match; its
    # status is USED, never swallowed.
    if apply "$d" "$SPEC" "$p4r_bold" "$p4r_plain"; then p4r_converted=1; fi
  elif grep -qF -- "$p4r_plain" "$d/$SPEC"; then
    p4r_converted=1   # already plain; the scenario holds as-is
  fi
  # The conversion must be OBSERVABLE, not assumed: plain present, bold absent.
  if [ "$p4r_converted" -ne 1 ]; then
    bad "M-P4-SYNTAX - INCONCLUSIVE: could not put the newest revision row into plain form"
  elif ! grep -qF -- "$p4r_plain" "$d/$SPEC"; then
    bad "M-P4-SYNTAX - INCONCLUSIVE: plain form of $p4r_max is absent after conversion"
  elif grep -qF -- "$p4r_bold" "$d/$SPEC"; then
    bad "M-P4-SYNTAX - INCONCLUSIVE: bold form of $p4r_max survived the conversion"
  else
  p4r_after="$(p4_reported_marker "$d")"
  p4r_sent="v1.$(( ${p4r_max#v1.} + 1 ))"
  if [ "$p4r_after" != "$p4r_max" ]; then
    bad "M-P4-SYNTAX - INCONCLUSIVE: rewriting the newest row to plain form changed the reported marker ($p4r_max -> $p4r_after)"
    rm -rf "$d"
  elif ! apply "$d" "$SPEC" '## 21. operator Decision Packets' \
        "| **$p4r_sent** | P-4 provenance probe | probe row |

## 21. operator Decision Packets" 2>/dev/null; then
    bad "M-P4-SYNTAX - INCONCLUSIVE: sentinel plant did not apply"
    rm -rf "$d"
  else
    # A guard that reports a fixed version rather than the file it opened. The
    # single-line fallback is the target on purpose: rewriting the two-line
    # SPEC_VER assignment would orphan its continuation and kill the guard, and
    # healthy() would correctly call that INCONCLUSIVE rather than a catch.
    sed -i.bak "s|^\[ -n \"\$SPEC_VER\" \].*|SPEC_VER=$p4r_max|" "$d/scripts/check-derived-counts.sh"
    if cmp -s "$d/scripts/check-derived-counts.sh" "$d/scripts/check-derived-counts.sh.bak"; then
      bad "M-P4-SYNTAX - INCONCLUSIVE: the fixed-version edit did not change the guard"
      rm -rf "$d"
    else
      rm -f "$d/scripts/check-derived-counts.sh.bak"
      p4rout="$(run_gate "$d")"
      if ! healthy "$p4rout"; then
        bad "M-P4-SYNTAX - INCONCLUSIVE: the mutated guard did not complete a scan"
        detail "$p4rout"
      elif p4_assert "$p4rout" "$p4r_sent" "$p4r_max"; then
        bad "M-P4-SYNTAX - a guard reporting a FIXED version still satisfied P-4 on a plain newest row"
        detail "$p4rout"
      else
        ok "M-P4-SYNTAX plain newest row + fixed-version guard is caught (P-4 is form-independent)"
        MUT=$((MUT + 1))
      fi
      rm -rf "$d"
    fi
  fi
  fi
fi

# ---------------------------------------------------------------------------
printf '\n== false-red control (legal text the guard must NOT flag) ==\n'

# Every case above asks "does it go red when it should". Nothing asked "does it
# stay green when it should", and that half is not cosmetic: a guard that cries
# wolf on legitimate prose gets disabled, which costs more coverage than the
# blind spot it was widened to close. Both controls below are the exact shapes
# the v1.61 widening put at risk.
legal() { # $1=label $2=old $3=new $4=substring that must NOT appear
  local d out
  d="$(mk)"
  if ! apply "$d" "$SPEC" "$2" "$3" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if ! healthy "$out"; then
    bad "$1 - INCONCLUSIVE: the guard did not complete a scan"
    detail "$out"; rm -rf "$d"; return
  fi
  # The named substring is reported FIRST because it is the specific claim this
  # case makes, and a generic "not PASS" would bury it.
  if printf '%s' "$out" | grep -qF -- "$4"; then
    bad "$1 - FALSE RED: the guard flagged legal text as a stale cache ('$4')"
    detail "$out"
    rm -rf "$d"; return
  fi
  # But absence of ONE substring is not greenness, and this helper's whole
  # promise is greenness. healthy() deliberately accepts PASS *or* FAIL -- it
  # proves the process finished, nothing more -- so a guard that goes red for any
  # OTHER reason inside the planted text used to satisfy this case: the expected
  # finding was missing, so the case declared "legal text stays green" about a
  # run that was red. Same defect one level down as a guard whose verdict claims
  # more than it measured.
  if ! printf '%s' "$out" | grep -qE '^check-derived-counts: PASS'; then
    bad "$1 - FALSE RED: the guard did not end green on legal text (a different diagnostic fired)"
    detail "$out"
  else
    ok "$1 - legal text stays green (verdict is PASS, not merely missing one finding)"
    POS=$((POS + 1))
  fi
  rm -rf "$d"
}

# L-1: a correction line quoting the superseded sentence VERBATIM. curval reads
# present-tense claims and must not exempt on a loose （…） span, but 「…」 is an
# explicit quotation and a whole correction blockquote is wholesale history.
# Without this control, "现行为 never exempts" reported legal history as stale.
legal "L-1 verbatim quotation of a superseded 现行为 claim" \
  '## 21. operator Decision Packets' \
  '> v1.62 更正：上一版台账逐字写作「现行为 **84**」；该句描述的是旧文。

## 21. operator Decision Packets' \
  ' curval 84 '

# L-2: a row count that is NOT a cache of this ledger. The guard already records
# why 矩阵行 is scoped and bare 矩阵 is not; the row-count widening re-opened the
# same door via 这 N 行 until it was narrowed to 全/全部.
legal "L-2 non-ledger row count (§6.4.1 negative cases)" \
  '## 21. operator Decision Packets' \
  '这 8 行是 §6.4.1 的独立负例。

## 21. operator Decision Packets' \
  ' bare 8 '

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
  if [ "$(verdict "$out")" != FAIL ]; then
    bad "$1 - INCONCLUSIVE: the intact gate printed '$5' but still came out PASS, so it was never blocking"
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
  if ! healthy "$out"; then
    bad "$1 - INCONCLUSIVE: the mutated guard did not complete a scan (crash?), so a missing finding proves nothing"
    detail "$out"
    rm -rf "$d"; return
  fi
  if printf '%s' "$out" | grep -qF -- "$5"; then
    bad "$1 - finding survived with the arm disabled, so that arm is not what catches it"
    detail "$out"
  elif [ "$(verdict "$out")" != PASS ]; then
    # The plant is the only stale value in a pristine-green copy, so with its arm
    # disabled the gate must come out PASS. Anything else means the mutation
    # perturbed something beyond the arm and the case no longer isolates it.
    bad "$1 - the finding vanished but the gate still fails, so the mutation did not isolate that arm"
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
  '、峰值曾达九十三行，但仍**零覆盖** proof/CAS provider 侧判定' \
  ' cn 93 '

# NOTE: M-CN plants 93 (N-C planted 90) so each case's finding substring is
# distinct. The planted value must be one the ledger CANNOT produce, or the
# mutation disarms itself. This has now happened TWICE for the same reason:
# 91 was used until v1.68 made it the live owner-red count, and 92 until v1.72
# did the same (M-AD-28 moved owner-red 91 -> 92). Both times the planted "bad"
# value silently became legal. Both times the selftest refused to pass on the
# absence and reported INCONCLUSIVE rather than green -- which is the only reason
# the disarm was visible at all. When picking this value, check it against the
# ledger's producible set (check-derived-counts prints it), not against intuition.
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
mut "M-PLAIN-SCOPE plain-owner-red scoping catches its own isolated line" \
  's/^SCOPE_PLAIN_OWNER_RED = .*/SCOPE_PLAIN_OWNER_RED = ""/' \
  '## 21. operator Decision Packets' \
  '其中 35 行 owner-red 待审。

## 21. operator Decision Packets' \
  ' bare 35 '

# ---------------------------------------------------------------------------
# v1.61 additions. The v1.60 recompute was reported complete while a SECOND
# group of 15 active stale values sat outside this guard's scope, and the six
# cases above could not have found them: every one of them plants inside a line
# the OLD scope already reached. A negative case can only measure the recogniser
# it plants into. So each case below plants into a line that is in scope ONLY
# through the widening it is testing, and each planted value (41/43/47/83) is
# distinct from every legal value and from every other plant.

# N-G: an owner-split of owner-red that never touches 行. The line was already
# in scope; being in scope is not being readable, and no 行-anchored arm saw it.
neg "N-G owner-pair notation (GLM 52 / Fable5 39 -> planted 47)" \
  '已归各自 code owner（GLM 53 / Fable5 39）' \
  '已归各自 code owner（GLM 47 / Fable5 39）' \
  ' pair 47 '

# N-H: "现行为 **N**" -- a claim whose whole purpose is to state the CURRENT
# ledger value. ARM_BOLD requires a trailing 行 and walked past it.
neg "N-H current-value notation (现行为 **91** -> planted 83)" \
  '现行为 **92**（§10.1 现算）' \
  '现行为 **83**（§10.1 现算）' \
  ' curval 83 '

# N-I: a line whose ONLY scope token is lane discourse (`lane selector`). It
# carries no owner-red / 台账 / 矩阵行 and no 全|全部 + N + 行.
neg "N-I lane-discourse scope (PR-3 的 39 行 -> planted 41)" \
  'Fable5 同时拥有 PR-3 的 39 行' \
  'Fable5 同时拥有 PR-3 的 41 行' \
  ' bare 41 '

# N-J: a line whose ONLY scope token is the row-count phrase itself (全 N 行).
# NOT 这 N 行: v1.63 removed that form from the scope after it collected
# "§6.4.1 的 8 行独立负例", which is not a cache of this ledger. Anchoring this
# case on 这 would re-teach the shape the L-2 control exists to forbid.
neg "N-J row-count-phrase scope (全 117 行 -> planted 43)" \
  '**本 task 是唯一验证全 118 行的地方**' \
  '**本 task 是唯一验证全 43 行的地方**' \
  ' bare 43 '

mut "M-PAIR owner-pair arm catches N-G" \
  's/^ARM_OWNER_PAIR = .*/ARM_OWNER_PAIR = None/' \
  '已归各自 code owner（GLM 53 / Fable5 39）' \
  '已归各自 code owner（GLM 47 / Fable5 39）' \
  ' pair 47 '

mut "M-CURVAL current-value arm catches N-H" \
  's/^ARM_CURVAL = .*/ARM_CURVAL = None/' \
  '现行为 **92**（§10.1 现算）' \
  '现行为 **83**（§10.1 现算）' \
  ' curval 83 '

mut "M-LANE-SCOPE lane-discourse scoping catches N-I" \
  's/^SCOPE_LANE_DISCOURSE = .*/SCOPE_LANE_DISCOURSE = ""/' \
  'Fable5 同时拥有 PR-3 的 39 行' \
  'Fable5 同时拥有 PR-3 的 41 行' \
  ' bare 41 '

mut "M-ROWCOUNT-SCOPE row-count-phrase scoping catches N-J" \
  's/^SCOPE_ROW_COUNT = .*/SCOPE_ROW_COUNT = ""/' \
  '**本 task 是唯一验证全 118 行的地方**' \
  '**本 task 是唯一验证全 43 行的地方**' \
  ' bare 43 '

# M-ARMS-CENSUS: the enumeration used to hardcode its own copy of the arm list,
# so an arm could be scanned and never enumerated -- invisible in the one output
# whose job is to expose blind spots. Collapsing ARMS must make the finding go
# away; if it does not, the scan is reading some other list again.
mut "M-ARMS-CENSUS scanner reads the ARMS table" \
  's/^ARMS = .*/ARMS = ()/' \
  '现行为 **92**（§10.1 现算）' \
  '现行为 **83**（§10.1 现算）' \
  ' curval 83 '

# M-ENUM-CENSUS is the OTHER half, and it cannot be folded into the one above:
# collapsing ARMS kills the scanner, so the finding vanishes and the case says
# nothing about whether the INVENTORY reads the same table. Here the scanner is
# left intact -- the finding must SURVIVE -- while the enumeration's own knob is
# collapsed, and the arm's inventory line must disappear. Two paths, two
# assertions, in opposite directions.
d="$(mk)"
if ! apply "$d" "$SPEC" '现行为 **92**（§10.1 现算）' '现行为 **83**（§10.1 现算）' 2>/dev/null; then
  bad "M-ENUM-CENSUS - INCONCLUSIVE: plant did not apply; the case never ran"
else
  sed -i.bak 's/^ENUM_ARMS = .*/ENUM_ARMS = ["cell"]/' "$d/scripts/check-derived-counts.sh"
  if cmp -s "$d/scripts/check-derived-counts.sh" "$d/scripts/check-derived-counts.sh.bak"; then
    bad "M-ENUM-CENSUS - INCONCLUSIVE: the enumeration-knob edit did not change the guard"
  else
    rm -f "$d/scripts/check-derived-counts.sh.bak"
    mout="$(run_gate "$d")"
    if ! healthy "$mout"; then
      bad "M-ENUM-CENSUS - INCONCLUSIVE: the mutated guard did not complete a scan"
      detail "$mout"
    elif ! printf '%s' "$mout" | grep -qF -- ' curval 83 '; then
      bad "M-ENUM-CENSUS - the scanner finding vanished too, so this collapsed both paths into one"
      detail "$mout"
    elif [ "$(verdict "$mout")" != FAIL ]; then
      bad "M-ENUM-CENSUS - the scanner kept its finding but the gate came out PASS, so it is not blocking on it"
      detail "$mout"
    elif printf '%s' "$mout" | grep -qF -- '    curval:'; then
      bad "M-ENUM-CENSUS - the inventory still lists curval with ENUM_ARMS collapsed, so it reads some other list"
      detail "$mout"
    else
      ok "M-ENUM-CENSUS inventory reads ENUM_ARMS while the scanner keeps finding — two dimensions, not one"
      MUT=$((MUT + 1))
    fi
  fi
fi
rm -rf "$d"

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-derived-counts: PASS (%d positive, %d negative, %d mutation self-check(s) — every case ran against the production guard)\n' \
    "$POS" "$NEG" "$MUT"
  exit 0
fi
printf 'selftest-derived-counts: FAIL (%d failure(s) across %d executed case(s))\n' "$FAILURES" "$((POS + NEG + MUT))"
exit 1
