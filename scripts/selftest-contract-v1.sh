#!/usr/bin/env bash
#
# selftest-contract-v1.sh — regression gate for check-contract-v1.sh.
#
# check-contract-v1.sh is green on this branch. That fact carries information
# only while the gate is still SENSITIVE to the drift it claims to catch, and
# nothing was measuring that: every mutation proof so far was a throwaway script
# run by hand, so the next change that quietly weakens a guard would land on a
# green board with no one to notice.
#
# So each case below mutates a throwaway copy of the CANONICAL SPEC, runs the
# PRODUCTION gate against it, and asserts two things, never one:
#
#   1. the gate reports the SPECIFIC finding that mutation should provoke --
#      not merely that it went red. A case that goes red for the wrong reason
#      is a false red, the exact mirror of the false greens the gate exists to
#      remove, and it reads identical on a dashboard;
#   2. exactly ONE check failed. A mutation that also trips section 5 or 6 has
#      stopped isolating the guard it is named after.
#
# The M-* cases close the remaining hole. A negative case proves "the gate is
# red while this drift is present"; it does NOT prove WHICH line caught it, and
# a guard already covered by an older section would look load-bearing while
# being dead code. So each M-* disables one specific guard in the production
# gate, re-applies the matching mutation, and asserts the finding DISAPPEARS.
# That is measured, not argued.
#
# No case greps the gate for a literal. Grepping proves a line exists, not that
# it does anything -- the lesson selftest-provenance.sh records in its own header.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-contract-v1.sh"
SPEC="feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md"
MODULE="contracts/environment-control-v1"

POS=0; NEG=0; MUT=0; FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
detail() { printf '%s\n' "$1" | grep -E '^\s+(FAIL|INCONCLUSIVE)|^check-contract-v1:' | sed 's/^/          /'; }

command -v python3 >/dev/null 2>&1 || { printf 'selftest-contract-v1: python3 required\n' >&2; exit 1; }

# A throwaway copy of everything the gate reads, taken from the WORKING TREE.
# Not from HEAD: the point is to test the gate as it stands right now, including
# uncommitted work, which is precisely when a weakened guard is cheapest to catch.
mk() {
  local d; d="$(mktemp -d)"
  mkdir -p "$d/scripts" "$d/$(dirname "$SPEC")" "$d/$(dirname "$MODULE")"
  cp "$PROD" "$d/scripts/check-contract-v1.sh"
  chmod +x "$d/scripts/check-contract-v1.sh"
  cp "$REPO_ROOT/$SPEC" "$d/$SPEC"
  cp -R "$REPO_ROOT/$MODULE" "$d/$(dirname "$MODULE")/"
  printf '%s\n' "$d"
}

# Exact-count-1 replacement. An anchor that matches 0 times means the mutation
# silently did not apply and the case would then "pass" while testing nothing;
# an anchor that matches more than once means it edited more than the case
# claims. Both are setup failures, and both must be loud.
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

# --static-only exits 1 even when every static guard passed (the Gradle half did
# not run), so a green static half is read from the verdict line, never from $?.
GREEN='check-contract-v1: INCOMPLETE (static checks passed'
ONE_FAILED='check-contract-v1: FAIL (1 check(s) failed'

run_gate() { ( cd "$1" && ./scripts/check-contract-v1.sh --static-only 2>&1 ); }

# ---------------------------------------------------------------------------
printf '\n== positive ==\n'

# P-1 the unmutated tree. Every negative below is meaningless if the baseline is
# not green: a gate that is red anyway would "catch" every mutation for free.
D="$(mk)"
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF "$GREEN"; then
  ok "P-1 pristine working tree: every static guard passes"
  POS=$((POS + 1))
else
  bad "P-1 pristine working tree is NOT green; every negative case below is unreliable"
  detail "$OUT"
fi
rm -rf "$D"

# ---------------------------------------------------------------------------
printf '\n== negative (canonical mutated; gate must catch the RIGHT thing, alone) ==\n'

neg() { # $1=label $2=relpath $3=old $4=new $5=expected finding substring
  local d out
  d="$(mk)"
  if ! apply "$d" "$2" "$3" "$4" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: mutation did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if ! printf '%s' "$out" | grep -qF "$5"; then
    bad "$1 - gate never reported the finding this case exists to provoke: '$5'"
    detail "$out"
  elif ! printf '%s' "$out" | grep -qF "$ONE_FAILED"; then
    bad "$1 - went red beyond the guard under test; the case no longer isolates it"
    detail "$out"
  else
    ok "$1"
    NEG=$((NEG + 1))
  fi
  rm -rf "$d"
}

# N-1 is the case the whole ordered comparison exists for. Two adjacent fields of
# the SAME type are transposed, so the field-name set is untouched and the
# per-position type tuple is untouched too -- a name-set check and a type check
# both see nothing. parcelize reads positionally, so on the wire the two values
# swap with no exception anywhere.
neg "N-1 transposed same-typed fields (order only)" "$SPEC" \
"    val profileRefs: List<String>,
    val scheduleRefs: List<String>," \
"    val scheduleRefs: List<String>,
    val profileRefs: List<String>," \
"same field names, DIFFERENT declaration order"

neg "N-2 field type changed (Long -> Int)" "$SPEC" \
"    val environmentRevision: Long,
    val profileRefs: List<String>," \
"    val environmentRevision: Int,
    val profileRefs: List<String>," \
"type differs"

neg "N-3 nullability dropped (String? -> String)" "$SPEC" \
"    val currentItemId: String?," \
"    val currentItemId: String," \
"nullability differs"

neg "N-4 default value removed" "$SPEC" \
"    val protocolVersion: Int = 1," \
"    val protocolVersion: Int," \
"default value present only in kotlin"

# N-5 pins the rule that an unreadable line FAILS rather than being skipped. A
# parser that drops what it cannot read shortens both field lists and then calls
# them identical.
neg "N-5 unparseable property line is fatal, not skipped" "$SPEC" \
"    val serviceVersion: String," \
"    serviceVersion: String," \
"unparseable property line"

# N-6 two exact schemas for one type inside one carrier. The dict that collects
# them would silently keep the later one, so the earlier schema stops being
# checked while still reading like frozen contract text.
neg "N-6 duplicate exact schema within one carrier" "$SPEC" \
"@Parcelize
data class ReleaseReceiptV1(" \
"@Parcelize
data class ReleaseRequestV1(
    val leaseId: String,
    val operationId: String,
    val idempotencyKey: String,
) : Parcelable

@Parcelize
data class ReleaseReceiptV1(" \
"duplicate exact schema in one carrier"

# N-7 guards the older name-set layer against regression while the ordered layer
# is added on top of it.
neg "N-7 field dropped from canonical (name-set layer)" "$SPEC" \
"    val currentItemId: String?,
" "" \
"in Kotlin but NOT in spec: ['currentItemId']"

# N-8/N-9 mutate the KOTLIN side, not canonical. Every case above drifts the
# document; section 5c exists for the opposite direction -- canonical is right and
# the KDoc an implementer reads is the stale half. Both cases restore the exact
# wording that shipped before 5c existed.
KT_ERR="$MODULE/src/main/java/io/github/terryyyc/fakexxx/contract/v1/ContractErrorCodeV1.kt"

# N-8 Rule A: drop ONE of the two methods canonical scopes wire 7 to. Removing
# both would also be caught, but the interesting case is a KDoc that looks scoped
# and silently covers half the surface.
neg "N-8 KDoc drops one canonical method scope (rule A)" "$KT_ERR" \
'     *  - `apply` (§6.6): a lease held by *another* caller, or for another intent.' \
'     *  - a lease held by another caller, or for another intent.' \
"LEASE_CONFLICT -- canonical 6.3.3 scopes it per method (apply, completeAndAdvance); its KDoc never names apply"

# N-9 Rule B: keep the corrected prose but remove the revision citation. This is
# the weaker, likelier drift -- someone rewords the KDoc and the audit trail back
# to the correcting revision goes with it.
neg "N-9 KDoc drops the correction citation (rule B)" "$KT_ERR" \
'**Corrected in spec v1.39.**' \
'**Corrected.**' \
"SCHEDULE_EXHAUSTED -- canonical 6.3.3 records a correction in v1.39"

# ---------------------------------------------------------------------------
printf '\n== mutation (disable one guard; its case must stop reporting) ==\n'

# Each case above proves the gate is red while the drift is present. None of them
# proves WHICH code did the catching -- an older, broader check could be covering
# for a new guard that never runs. These invert that: break one guard, re-run the
# matching mutation, and require the specific finding to vanish.
mut() { # $1=label $2=sed expr against the gate $3=relpath $4=old $5=new $6=finding that must disappear
  local d out base

  # First, with the gate INTACT, confirm the finding is actually produced. A
  # guard that never fires at all also "loses" its finding when disabled, and
  # would be reported load-bearing on the strength of an absence that was
  # already there. The matching N-* case asserts this too, but a case that can
  # only be read correctly alongside another case is one someone will misread.
  base="$(mk)"
  if ! apply "$base" "$3" "$4" "$5" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: mutation did not apply to the intact gate; the case never ran"
    rm -rf "$base"; return
  fi
  out="$(run_gate "$base")"
  rm -rf "$base"
  if ! printf '%s' "$out" | grep -qF "$6"; then
    bad "$1 - INCONCLUSIVE: the intact gate never produced '$6', so its disappearance proves nothing"
    detail "$out"
    return
  fi

  d="$(mk)"
  # sed reports success when its pattern matched nothing, so success is not
  # evidence the guard was disabled; the file having actually changed is.
  if ! sed -i.bak "$2" "$d/scripts/check-contract-v1.sh" || \
     cmp -s "$d/scripts/check-contract-v1.sh" "$d/scripts/check-contract-v1.sh.bak"; then
    bad "$1 - INCONCLUSIVE: the guard-disabling edit did not change the gate"
    rm -rf "$d"; return
  fi
  rm -f "$d/scripts/check-contract-v1.sh.bak"
  if ! apply "$d" "$3" "$4" "$5" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: mutation did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if printf '%s' "$out" | grep -qF "$6"; then
    bad "$1 - finding survived with the guard disabled, so that guard is not what catches it"
    detail "$out"
  else
    ok "$1 - disabling it makes N's finding disappear, so the guard is load-bearing"
    MUT=$((MUT + 1))
  fi
  rm -rf "$d"
}

# M-1: without the order layer, N-1 is invisible to EVERY other layer -- same
# names, same types, same defaults. This is the one guard with no fallback.
mut "M-1 ordered declaration check catches N-1" 's/if an != bn:/if False and an != bn:/' \
  "$SPEC" \
"    val profileRefs: List<String>,
    val scheduleRefs: List<String>," \
"    val scheduleRefs: List<String>,
    val profileRefs: List<String>," \
"same field names, DIFFERENT declaration order"

mut "M-2 per-position type check catches N-2" 's/if x\[1\] != y\[1\]:/if False and x[1] != y[1]:/' \
  "$SPEC" \
"    val environmentRevision: Long,
    val profileRefs: List<String>," \
"    val environmentRevision: Int,
    val profileRefs: List<String>," \
"type differs"

mut "M-3 default-presence check catches N-4" 's/if x\[2\] != y\[2\]:/if False and x[2] != y[2]:/' \
  "$SPEC" \
"    val protocolVersion: Int = 1," \
"    val protocolVersion: Int," \
"default value present only in kotlin"

# M-4: with the parse-fatal exit removed the tree still goes red, but for the
# WRONG reason -- the dropped line resurfaces as a name-set difference. That is
# why this asserts the disappearance of the parse finding specifically, not that
# the gate turned green.
mut "M-4 parse-fatal exit catches N-5" 's/^if parse_errs:/if False and parse_errs:/' \
  "$SPEC" \
"    val serviceVersion: String," \
"    serviceVersion: String," \
"unparseable property line"

# M-5/M-6 disable 5c's two rules independently. `if missing:` occurs four times in
# the gate, so it is NOT usable as an anchor -- sed would silently disable four
# guards and the case would "pass" for the wrong reason. The rule ENTRY conditions
# are unique, so each rule is switched off on its own.
mut "M-5 rule A (method scope) catches N-8" 's/if need:/if False and need:/' \
  "$KT_ERR" \
'     *  - `apply` (§6.6): a lease held by *another* caller, or for another intent.' \
'     *  - a lease held by another caller, or for another intent.' \
"scopes it per method (apply, completeAndAdvance)"

mut "M-6 rule B (correction citation) catches N-9" 's/if absent:/if False and absent:/' \
  "$KT_ERR" \
'**Corrected in spec v1.39.**' \
'**Corrected.**' \
"records a correction in v1.39"

# ---------------------------------------------------------------------------
printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-contract-v1: PASS (%d positive, %d negative, %d mutation self-check(s) — every case ran against the production gate)\n' \
    "$POS" "$NEG" "$MUT"
  exit 0
fi
printf 'selftest-contract-v1: FAIL (%d failure(s) across %d executed case(s))\n' "$FAILURES" "$((POS + NEG + MUT))"
exit 1
