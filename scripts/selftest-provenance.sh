#!/usr/bin/env bash
#
# selftest-provenance.sh — regression gate for check-provenance.sh.
#
# Every case below runs the PRODUCTION script against a throwaway clone and
# asserts its exit code. No case inspects source text: grepping the checker for a
# literal proves the line exists, not that it does anything, and a previous
# version counted two such greps as "negatives" while claiming 8 when only 6
# behaviours were exercised. Labels, counts and what actually runs must agree.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-provenance.sh"
DOC=docs/provenance/upstream-imports.md
SHA_AUTO=48d8ec93adb84cdb9c4282c376ec97476648683e
SHA_QWY=285e4cae438ab6feea1f70f984f433c7a424b944
TREE_AUTO=0553fcb46f02e7211f4496e4a98b846ec70ef9a2
TREE_QWY=f4bdce23c65e6227cf43dab5fe0416120b95134e
POS=0; NEG=0; MUT=0; FAILURES=0; PASSED=""

ok()  { printf '  PASS  %s\n' "$1"; PASSED="$PASSED
$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# A squash/rebase merge leaves exactly this: one commit, no import-commit
# ancestry, pristine vendored trees.
mk_squashed() {
  local d; d="$(mktemp -d)"
  ( cd "$d" && git init -q . && git config user.email s@s && git config user.name s )
  ( cd "$REPO_ROOT" && git archive HEAD ) | tar -x -C "$d"
  cp "$PROD" "$d/scripts/check-provenance.sh"
  cp "$REPO_ROOT/$DOC" "$d/$DOC"          # working-tree doc, not the committed one
  ( cd "$d" && git add -A >/dev/null && git commit -qm "squash merge of PR #10" )
  # Fetch the upstream object into the fixture itself. Shape assertions resolve
  # ${SHA}^{tree} before the checker runs, so they must not depend on whichever
  # repository happens to be $REPO_ROOT — inside a mutation copy that repo does
  # not have the object, and the assertion then failed for a reason unrelated to
  # the mutation, producing an unreliable "not bound" verdict.
  ( cd "$d" && git fetch --no-tags -q https://github.com/TERRYYYC/FakeGps-test.git "$SHA_QWY" ) >/dev/null 2>&1
  printf '%s\n' "$d"
}

# A full-DAG working copy: unlike mk_squashed, the import commits stay reachable,
# so the checker takes its real-ancestry branch instead of the history-lost one.
# Both shapes are needed for the TAB cases below: the two stages reach the
# import-commit cell through different code paths, and a counterproof on only one
# of them says nothing about the other.
mk_fulldag() {
  local d sha; d="$(mktemp -d)"; sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
  # Do NOT depend on the source repo's HEAD being a branch. `git clone` resolves
  # the remote's symbolic HEAD to decide what to check out, so a detached source
  # HEAD yields a clone with an EMPTY working tree. That is environment-shaped:
  # it built fine on a developer machine sitting on a branch and failed on CI with
  # "no such file: docs/provenance/upstream-imports.md", which the old silent
  # setup() reported only as "FIXTURE SETUP FAILED". Clone the objects, then check
  # out the exact commit by SHA, which has no symbolic dependency at all.
  # Identity is persisted for the same reason mk_squashed persists it: the tampers
  # commit again inside the fixture, a clone inherits no user.email/user.name, and
  # macOS derives one from user@host while a CI runner refuses.
  git clone -q --no-checkout "$REPO_ROOT" "$d" >/dev/null 2>&1
  ( cd "$d" && git config user.email s@s && git config user.name s \
      && git checkout -q -B fixture "$sha" ) >/dev/null 2>&1
  cp "$PROD" "$d/scripts/check-provenance.sh"
  cp "$REPO_ROOT/$DOC" "$d/$DOC"
  ( cd "$d" && git add -A >/dev/null && git commit -qm "working-tree state under test" ) >/dev/null 2>&1
  ( cd "$d" && git fetch --no-tags -q https://github.com/TERRYYYC/FakeGps-test.git "$SHA_QWY" ) >/dev/null 2>&1
  printf '%s\n' "$d"
}

run() { ( cd "$2" && ./scripts/check-provenance.sh "${@:3}" ) >/dev/null 2>&1; return $?; }

# Every fixture must be built successfully AND be shaped the way its case claims.
# A previous version discarded the setup subshell's exit code: breaking P-4's
# first append left the case silently re-testing pristine P-3 while still
# printing "real Task-2 qwy delta (rc=0)". A green case whose fixture never
# existed is a false verification — precisely what the case exists to prevent.
setup() { # <label> <dir> <shell-body>
  local out
  # Capture instead of discarding. A previous version sent stdout and stderr to
  # /dev/null, so a fixture that failed only on CI reported "FIXTURE SETUP FAILED"
  # and nothing else: the one machine that could see the reason was the one nobody
  # could read. Diagnosing it cost a full push/CI round-trip of guessing. Silent
  # on success, verbatim on failure.
  if ! out="$( cd "$2" && eval "$3" 2>&1 )"; then
    bad "$1 — FIXTURE SETUP FAILED; the case below would have tested nothing"
    printf '        setup output: %s\n' "$(printf '%s' "$out" | tr '\n' '|' | cut -c1-400)"
    return 1
  fi; return 0
}
shape() { # <label> <predicate-cmd...>
  if ! "${@:2}" >/dev/null 2>&1; then bad "$1 — FIXTURE SHAPE ASSERTION FAILED"; return 1; fi; return 0
}
tree_at() { git -C "$1" rev-parse --quiet --verify "${2}:apps/qianwangyou" 2>/dev/null; }

# When SELFTEST_ONLY is set, run only the case whose label starts with it. The
# mutation section uses this so each mutation costs one case instead of a full
# re-run: three nested full runs took minutes and would have timed out in CI.
skip_case() { [ -n "${SELFTEST_ONLY:-}" ] && case "$1" in "$SELFTEST_ONLY"*) return 1;; *) return 0;; esac; return 1; }

pos() { # <label> <dir> <args...>
  local l="$1"; shift; skip_case "$l" && return 0; POS=$((POS+1))
  local rc; run x "$@"; rc=$?
  [ "$rc" -eq 0 ] && ok "$l (rc=0)" || bad "$l — expected rc=0, got $rc"
}
neg() { # <label> <dir> <args...>
  local l="$1"; shift; skip_case "$l" && return 0; NEG=$((NEG+1))
  local rc; run x "$@"; rc=$?
  [ "$rc" -ne 0 ] && ok "$l (rc=$rc)" || bad "$l — expected non-zero, got 0"
}

printf '== positives (legal states that must stay green) ==\n'
pos "P-1 production repo, full DAG, --stage import"   "$REPO_ROOT" --stage import
pos "P-2 production repo, full DAG, --stage contract" "$REPO_ROOT" --stage contract
D="$(mk_squashed)"
pos "P-3 squash-merged history, pristine trees"       "$D" --stage contract

# The combination the previous version got wrong: after a squash merge, a real
# contract-wiring delta lands on the vendored app. Ancestry still exists — the
# base commit carries the pristine tree — so this must be green. Making it red
# would forbid the operator from using the squash button.
D="$(mk_squashed)"
if setup "P-4 fixture" "$D" '
    for f in app/build.gradle build.gradle settings.gradle; do
      test -f "apps/qianwangyou/$f" || exit 1
      printf "\n// contract wiring (PR-2 Task 2)\n" >> "apps/qianwangyou/$f"
    done
    git add -A && git commit -qm "PR-2 contract wiring" -q'; then
  UP="$(git -C "$D" rev-parse "${SHA_QWY}^{tree}" 2>/dev/null)"
  if shape "P-4 parent prefix tree == upstream" test "$(tree_at "$D" HEAD^)" = "$UP" \
  && shape "P-4 HEAD prefix tree != upstream"   test "$(tree_at "$D" HEAD)" != "$UP" \
  && shape "P-4 exactly 3 qwy files in delta"   test "$(git -C "$D" diff --name-only HEAD^ HEAD -- apps/qianwangyou | wc -l | tr -d ' ')" = "3"; then
    pos "P-4 squash-merged history + Task-2-shaped qwy delta (3 build files)" "$D" --stage contract
  fi
fi

printf '\n== negatives (bad states that must go red) ==\n'

# No ancestor anywhere carries the recorded baseline tree.
D="$(mk_squashed)"
( cd "$D" && printf '\n// forged\n' >> apps/qianwangyou/app/build.gradle \
  && git add -A && git commit --amend -qm "forged single commit, no pristine ancestor" ) >/dev/null 2>&1
neg "N-1 no reachable commit carries the baseline tree" "$D" --stage contract

# Row renamed: index($2,pfx) matched the suffix and certified a document that no
# longer described the prefix.
D="$(mk_squashed)"
( cd "$D" && sed -i.bak 's#`apps/qianwangyou` |#`apps/qianwangyou-shadow` |#' "$DOC" && rm -f "$DOC.bak" \
  && git add -A && git commit -qm shadow -q ) >/dev/null 2>&1
neg "N-2 imports row renamed to a prefix-suffix" "$D" --stage import

# Duplicate row for the same prefix: which one is authoritative?
D="$(mk_squashed)"
( cd "$D" && python3 - <<PY && git add -A && git commit -qm dup -q
p='$DOC'; L=open(p).read().split(chr(10))
i=[n for n,l in enumerate(L) if l.startswith('| \`apps/qianwangyou\`')][0]
L.insert(i+1, L[i]); open(p,'w').write(chr(10).join(L))
PY
) >/dev/null 2>&1
neg "N-3 duplicate row for the same prefix" "$D" --stage import

# Cross-row swaps, one load-bearing field at a time. Each leaves both values
# present in the file, so any whole-file membership test still passes.
for fld in "SHA:$SHA_AUTO:$SHA_QWY" "TREE:$TREE_AUTO:$TREE_QWY"; do
  name="${fld%%:*}"; rest="${fld#*:}"; a="${rest%%:*}"; b="${rest#*:}"
  D="$(mk_squashed)"
  ( cd "$D" && python3 - <<PY && git add -A && git commit -qm swap -q
p='$DOC'; s=open(p).read()
open(p,'w').write(s.replace('$a','@T@').replace('$b','$a').replace('@T@','$b'))
PY
  ) >/dev/null 2>&1
  neg "N-4/$name cross-row swap of the $name cells" "$D" --stage import
done

# A real fork attempt, executed rather than grepped: redefine the iterator after
# the handler so the loops would see only cellrebel, and tamper qwy at the same
# time. If the fork worked the tampering would be invisible; the run must go red.
D="$(mk_squashed)"
# The fork must be the ONLY thing that can redden this case, and it must be a
# record set the rest of the gate accepts. Dropping qwy is already caught by the
# section-0a prefix-set check, so a dropping fork never exercised readonly -f at
# all: deleting that line left the whole suite green. This injects a complete,
# well-formed 5-field set whose qwy SHA is forged. With readonly -f the
# redefinition is a hard error, the loops read the true record, and the run is
# green; without it the loops would read the forged SHA and the document row
# would no longer match.
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
a="# Entry files that must exist after a successful import (spec §13 Task 1.3)."
fork = ("each_import() { printf '%s\\n' "
        "'apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|main|48d8ec93adb84cdb9c4282c376ec97476648683e|301da0f2925373dfe40cfd2a51d53ddaca4bba93' "
        "'apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|deadbeefdeadbeefdeadbeefdeadbeefdeadbeef|5687e319f978dcd9b76e413c06b2b0da91627518'; }\n\n")
assert a in s
open(p,'w').write(s.replace(a, fork + a, 1))
PY
) >/dev/null 2>&1
if shape "N-5 fork injection landed (valid 5-field, forged qwy SHA)" \
     grep -q "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" "$D/scripts/check-provenance.sh"; then
  NEG=$((NEG+1))
  n5_out="$( cd "$D" && ./scripts/check-provenance.sh --stage import 2>&1 )"; n5_rc=$?
  # Two independent facts, both required. An exit code alone is not enough, and a
  # bare grep for the prefix is worthless: "apps/qianwangyou" also appears in the
  # entry-file section no matter what the import loop consumed. The string below
  # can only be produced by the doc-binding loop that each_import drives.
  if [ "$n5_rc" -eq 0 ] && printf '%s' "$n5_out" | grep -q "row for apps/qianwangyou records upstream sha 285e4cae4"; then
    ok "N-5 iterator redefinition cannot fork the gate (loops still read the true qwy SHA, rc=0)"
  else
    bad "N-5 the forged record reached the production loops - rc=$n5_rc"
  fi
fi

# A prefix dropped from the frozen record set: the gate would skip an app.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944|5687e319f978dcd9b76e413c06b2b0da91627518\n","",1))
PY
) >/dev/null 2>&1
if shape "N-6 mutation landed (qwy record gone)" sh -c '! grep -q "^apps/qianwangyou|" "'"$D"'/scripts/check-provenance.sh"'; then
  neg "N-6 qwy record removed from the frozen set" "$D" --stage import
fi

# A prefix smuggled into the branch field: a substring membership test reported
# both prefixes present from a one-record set.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
s=s.replace("|main|48d8ec93","|apps/qianwangyou|48d8ec93",1)
s=s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944|5687e319f978dcd9b76e413c06b2b0da91627518\n","",1)
open(p,'w').write(s)
PY
) >/dev/null 2>&1
if shape "N-7 mutation landed (prefix in branch field)" grep -q "|apps/qianwangyou|48d8ec93" "$D/scripts/check-provenance.sh"; then
  neg "N-7 prefix smuggled into the branch field" "$D" --stage import
fi

# A malformed record silently shifts every later field read.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944|5687e319f978dcd9b76e413c06b2b0da91627518",
                            "apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|285e4cae438ab6feea1f70f984f433c7a424b944",1))
PY
) >/dev/null 2>&1
if shape "N-8 mutation landed (short record)" grep -q "FakeGps-test.git|285e4cae" "$D/scripts/check-provenance.sh"; then
  neg "N-8 malformed IMPORTS record (short)" "$D" --stage import
fi

# N-9 an upstream SHA cell with whitespace inside it. Trimming a cell's internal
# whitespace silently repairs a broken value into a valid-looking one, so a SHA
# nobody could copy out of the document was normalised and then certified.
D="$(mk_squashed)"
if setup "N-9 fixture" "$D" 'python3 -c "
p=\"docs/provenance/upstream-imports.md\"; s=open(p).read()
assert \"285e4cae438ab6feea1f70f984f433c7a424b944\" in s
open(p,\"w\").write(s.replace(\"285e4cae438ab6feea1f70f984f433c7a424b944\",\"285e4cae438ab6feea1f70f984f433c7a 424b944\",1))
" && git add -A && git commit -qm ws -q'; then
  neg "N-9 upstream SHA cell contains internal whitespace" "$D" --stage import
fi

# N-10 / N-11 the import-commit cell must be bound to the frozen canonical value.
# A whole-file lookup let the document name any hex string: writing 0000...0000
# printed "is not an ancestor", which the code then read as a legitimate squash.
for spec in "N-10|0000000000000000000000000000000000000000|a nonexistent object" \
            "N-11|301da0f2925373dfe40cfd2a51d53ddaca4bba93|the other prefix's commit"; do
  id="${spec%%|*}"; rest="${spec#*|}"; val="${rest%%|*}"; desc="${rest#*|}"
  D="$(mk_squashed)"
  if setup "$id fixture" "$D" "grep -q 5687e319f978dcd9b76e413c06b2b0da91627518 $DOC && sed -i.bak s/5687e319f978dcd9b76e413c06b2b0da91627518/$val/ $DOC && rm -f $DOC.bak && git add -A && git commit -qm importcell -q"; then
    neg "$id import-commit cell replaced with $desc" "$D" --stage import
  fi
done

# N-12 / N-13 a literal TAB INSIDE the import-commit cell. doc_row() serialises
# the six cells with TABs and every caller reads them back with `cut -f1..f6`, so
# an interior TAB manufactured a seventh transport field and `cut -f6` silently
# discarded the tail. The document said `<canonical sha>\tJUNK` and the checker
# printed "records the canonical import commit 5687e319f" and exited 0 — on
# full-DAG --stage import AND on depth-1 history-lost --stage contract. Trimming
# only the outer padding was the right call and still left the transport lossy:
# `[[:space:]]` covers TAB, but an interior TAB is neither leading nor trailing.
#
# EVERY cell is tampered, not only the sixth. The criterion this pins reads "put
# the delimiter into every field and require red", so testing one field would
# leave the claim wider than the test -- the exact failure this suite exists to
# stop. Cells 2..6 are caught by the interior-whitespace rule; cell 1 goes red
# through an earlier door (the row selector stops matching the prefix, so no row
# is found at all). Both are red, which is what the criterion actually asserts.
#
# The tamper edits the imports row in place BY CELL INDEX rather than replacing a
# value document-wide: `apps/qianwangyou` and `master` also occur in other tables,
# and a global replace would tamper rows the case does not name. chr(96)/chr(9)
# keep backticks and TABs out of the eval'd string -- a literal backtick there is
# command substitution, not a Markdown delimiter.
tab_tamper_for() { # <1-based cell index> -> prints the fixture command
  printf '%s' 'python3 -c "
p=\"docs/provenance/upstream-imports.md\"; n='"$1"'
L=open(p).read().split(chr(10))
r=[i for i,l in enumerate(L) if len(l.split(\"|\"))==8 and l.split(\"|\")[1].strip().strip(chr(96))==\"apps/qianwangyou\"]
assert len(r)==1, r
c=L[r[0]].split(\"|\"); v=c[n].strip().strip(chr(96))
c[n]=\" \"+chr(96)+v+chr(9)+\"JUNK\"+chr(96)+\" \"
L[r[0]]=\"|\".join(c)
open(p,\"w\").write(chr(10).join(L))
" && grep -q JUNK docs/provenance/upstream-imports.md && git add -A && git commit -qm tabcell -q'
}

for spec in "1|prefix" "2|url" "3|branch" "4|sha" "5|tree" "6|import-commit"; do
  n="${spec%%|*}"; name="${spec#*|}"
  D="$(mk_fulldag)"
  if setup "N-12/$name fixture" "$D" "$(tab_tamper_for "$n")"; then
    neg "N-12/$name cell $n carries a literal TAB (full DAG, stage=import)" "$D" --stage import
  fi
done

D="$(mk_squashed)"
if setup "N-13 fixture" "$D" "$(tab_tamper_for 6)"; then
  neg "N-13 import-commit cell carries a literal TAB (history-lost, stage=contract)" "$D" --stage contract
fi

# ---------------------------------------------------------------------------
# Mutation self-validation.
#
# A passing case proves nothing until the fix it guards is shown to be load
# bearing. Twice already a mutation of mine left its target passing and I nearly
# read that as confirmation; both times the mutation was what was wrong. So the
# check lives here, in the harness, instead of in a commit message: revert a
# fix, and the case guarding it MUST fail.
printf '\n== mutation self-validation (each fix must be load-bearing) ==\n'
mutate() { # <label> <target-case-id> <sed-script-applied-to-the-checker>
  local label="$1" target="$2" sedscript="$3"; MUT=$((MUT+1))
  # The target must be GREEN before the mutation, or "reverting the fix makes it
  # fail" is vacuously true. CI proved this is not hypothetical: N-12's fixture
  # broke there, the case was already red, and M-5 still printed "load-bearing".
  # A mutation whose target was already failing measures nothing at all.
  if ! printf '%s' "$PASSED" | grep -q "^$target"; then
    bad "$label - INCONCLUSIVE: $target was not green before the mutation, so reverting the fix proves nothing"
    return
  fi
  local m; m="$(mktemp -d)"
  # The copy must be a real git repository: the inner run's mk_squashed calls
  # `git archive HEAD` on it. A plain file copy made every fixture fail to build,
  # which the harness then reported as "not bound" — a mutation result produced
  # by a broken mutation harness, which is the very thing this section exists to
  # rule out.
  ( cd "$REPO_ROOT" && git archive HEAD ) | tar -x -C "$m" 2>/dev/null
  cp "$REPO_ROOT/scripts/check-provenance.sh" "$REPO_ROOT/scripts/selftest-provenance.sh" "$m/scripts/"
  cp "$REPO_ROOT/$DOC" "$m/$DOC"
  ( cd "$m" && git init -q . && git config user.email m@m && git config user.name m \
      && git add -A && git commit -qm "mutation baseline" ) >/dev/null 2>&1
  local before after
  before="$(shasum -a 256 "$m/scripts/check-provenance.sh" | cut -d" " -f1)"
  sed -i.bak "$sedscript" "$m/scripts/check-provenance.sh" 2>/dev/null
  rm -f "$m/scripts/check-provenance.sh.bak"
  after="$(shasum -a 256 "$m/scripts/check-provenance.sh" | cut -d" " -f1)"
  # Prove the mutation landed before interpreting the result. Without this,
  # "the case still passes" and "the mutation never applied" are the same
  # output, and the second one masquerades as the first.
  if [ "$before" = "$after" ]; then
    bad "$label - MUTATION DID NOT APPLY (checker unchanged); result says nothing"
    rm -rf "$m"; return
  fi
  # Capture first, match second. Piping the inner run into `grep -q` looked
  # right and was wrong: the inner run exits non-zero *by design* when the
  # mutation bites, and under `set -o pipefail` that makes the whole pipeline
  # non-zero even when grep matched. The mutation was landing, the case was
  # failing exactly as intended, and the harness read that as "not bound" —
  # the same pipefail shape this project already fixed once in the checker.
  local inner
  inner="$( cd "$m" && SELFTEST_MUTATION_PASS=1 SELFTEST_ONLY="$target" ./scripts/selftest-provenance.sh 2>&1 )"
  if printf '%s' "$inner" | grep -q "FAIL  $target.*FIXTURE"; then
    # A broken fixture also prints "FAIL  <target> ...", and a bare grep for that
    # accepted it as proof the fix was load-bearing. The case never ran.
    bad "$label - INCONCLUSIVE: $target's fixture broke under mutation; the case never ran"
  elif printf '%s' "$inner" | grep -q "FAIL  $target"; then
    ok "$label - reverting the fix makes $target fail, so the fix is load-bearing"
  else
    bad "$label - $target still passes without the fix; that case is not bound to it"
  fi
  rm -rf "$m"
}
export SELFTEST_NO_MUTATE=1
if [ "${SELFTEST_MUTATION_PASS:-0}" != "1" ]; then
  SELFTEST_MUTATION_PASS=1
  export SELFTEST_MUTATION_PASS
  # Exact first-cell binding is implemented in two places — the row selector and
  # the explicit r_prefix equality. Reverting only the selector leaves the second
  # layer catching N-2, so a one-layer mutation proves nothing about the pair.
  mutate "M-1 exact first-cell binding (both layers)" "N-2" 's/if (first != pfx) next/if (index(first, pfx) == 0) next/; s/\[ "\$r_prefix" = "\$prefix" \]/[ -n "\$r_prefix" ]/'
  mutate "M-2 ancestry walks ancestors"  "P-4" 's/for c in HEAD \$(git rev-list HEAD -- "\$prefix" 2>\/dev\/null); do/for c in HEAD; do/'
  mutate "M-3 outer-only cell trim"      "N-9" 's/\^\[`\[:space:\]\]+|\[`\[:space:\]\]+\$/[`[:space:]]/'

  # The protection N-5 exists to guard. Without this mutation, deleting
  # `readonly -f each_import` left the entire suite green: the case that was
  # supposed to notice reported PASS.
  mutate "M-4 readonly -f freezes the iterator" "N-5" '/readonly -f each_import/d'

  # Two layers again, exactly like M-1. The interior-whitespace rule and the
  # transport field-count assertion each catch the TAB on their own, so reverting
  # only one leaves N-12 red — the mutation would report "bound" while proving
  # nothing about the pair it is supposed to pin.
  mutate "M-5 lossless six-cell transport (both layers)" "N-12/import-commit" '/ERR interior-whitespace/d; /ERR transport-fields/d'
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-provenance: PASS (%d positive, %d negative, %d mutation self-check(s) — every case executed against the production checker)\n' "$POS" "$NEG" "$MUT"
  exit 0
fi
printf 'selftest-provenance: FAIL (%d failure(s) across %d executed case(s) plus fixture/mutation assertions)\n' "$FAILURES" "$((POS + NEG))"
exit 1
