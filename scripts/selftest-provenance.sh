#!/usr/bin/env bash
#
# selftest-provenance.sh — regression gate for check-provenance.sh.
#
# Every negative below is a state this repository actually shipped at some point
# and a reviewer had to find by hand. Writing the table into a document is not a
# gate; this file is the gate. It runs the *production* script — never a copy —
# so a negative can only pass by the real predicate changing.
#
# All work happens in throwaway clones under a temp dir; the worktree is never
# touched.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-provenance.sh"
TREE_AUTO=0553fcb46f02e7211f4496e4a98b846ec70ef9a2
TREE_QWY=f4bdce23c65e6227cf43dab5fe0416120b95134e
FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# A fresh single-commit clone: both import commits are genuinely unreachable,
# which is what a squash or rebase merge leaves behind.
mk_history_lost() {
  local d; d="$(mktemp -d)"
  ( cd "$d" && git init -q . && git config user.email s@s && git config user.name s )
  ( cd "$REPO_ROOT" && git archive HEAD ) | tar -x -C "$d"
  cp "$PROD" "$d/scripts/check-provenance.sh"
  ( cd "$d" && git add -A >/dev/null && git commit -qm "history-lost baseline" )
  printf '%s\n' "$d"
}

expect() { # <label> <expected-rc> <dir> <args...>
  local label="$1" want="$2" dir="$3"; shift 3
  local got; ( cd "$dir" && ./scripts/check-provenance.sh "$@" ) >/dev/null 2>&1; got=$?
  if [ "$got" -eq "$want" ]; then ok "$label (rc=$got)"; else bad "$label — expected rc=$want, got rc=$got"; fi
}

printf '== positives ==\n'
expect "P-1 production repo, --stage import"   0 "$REPO_ROOT" --stage import
expect "P-2 production repo, --stage contract" 0 "$REPO_ROOT" --stage contract
D="$(mk_history_lost)"
expect "P-3 history-lost + pristine trees"     0 "$D" --stage contract

printf '\n== negatives ==\n'

# N-1 the two rows' root trees swapped: each hash is still present in the file,
# so a document-wide grep passed both. Binding must be per row.
D="$(mk_history_lost)"
( cd "$D" && python3 - <<PY && git add -A && git commit -qm swap -q
p='docs/provenance/upstream-imports.md'; s=open(p).read()
open(p,'w').write(s.replace('$TREE_AUTO','@T@').replace('$TREE_QWY','$TREE_AUTO').replace('@T@','$TREE_QWY'))
PY
) >/dev/null 2>&1
expect "N-1 root-tree rows swapped" 1 "$D" --stage import

# N-2 history lost AND the tree diverged: descent from the baseline is not
# provable by anything left in the repo.
D="$(mk_history_lost)"
( cd "$D" && echo x > apps/qianwangyou/ZZZ.txt && git add -A && git commit -qm tamper -q ) >/dev/null 2>&1
expect "N-2 history-lost + tracked divergence" 1 "$D" --stage contract

# N-3 a prefix dropped from the frozen record set: the gate would skip an entire
# app while every executed check still passed.
D="$(mk_history_lost)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944\n","",1))
PY
) >/dev/null 2>&1
expect "N-3 qwy record removed from IMPORTS" 1 "$D" --stage import

# N-4 a prefix name hidden in the branch field. A substring test over the whole
# record block reported both prefixes present while the loops saw only one.
D="$(mk_history_lost)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
s=s.replace("apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|main|48d8ec93adb84cdb9c4282c376ec97476648683e",
            "apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|apps/qianwangyou|48d8ec93adb84cdb9c4282c376ec97476648683e",1)
s=s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944\n","",1)
open(p,'w').write(s)
PY
) >/dev/null 2>&1
expect "N-4 prefix smuggled into the branch field" 1 "$D" --stage import

# N-5 a malformed record: a missing field silently shifts every later read.
D="$(mk_history_lost)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944",
                            "apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|285e4cae438ab6feea1f70f984f433c7a424b944",1))
PY
) >/dev/null 2>&1
expect "N-5 malformed record (3 fields)" 1 "$D" --stage import

# N-6 the recorded root tree corrupted on its own row.
D="$(mk_history_lost)"
( cd "$D" && sed -i.bak "s/$TREE_QWY/0000000000000000000000000000000000000000/" docs/provenance/upstream-imports.md \
    && rm -f docs/provenance/upstream-imports.md.bak && git add -A && git commit -qm badtree -q ) >/dev/null 2>&1
expect "N-6 recorded root tree corrupted" 1 "$D" --stage import

# N-7 --print-import must not be able to disagree with the gate. The fork used a
# reassignment between the handler and the loops; both readonly guards make that
# a hard error rather than a silent divergence.
D="$(mk_history_lost)"
QUERY="$( cd "$D" && ./scripts/check-provenance.sh --print-import apps/qianwangyou 2>/dev/null )"
if [ "$QUERY" = "285e4cae438ab6feea1f70f984f433c7a424b944" ]; then
  ok "N-7a --print-import returns the frozen SHA"
else
  bad "N-7a --print-import returned '$QUERY'"
fi
if ( cd "$D" && bash -c 'IMPORTS=x' 2>&1 | grep -q 'readonly' ); then :; fi
if grep -q 'readonly -f each_import' "$PROD" && grep -q '^readonly IMPORTS' "$PROD"; then
  ok "N-7b both the record set and the iterator function are frozen"
else
  bad "N-7b IMPORTS / each_import are not both readonly — a redefinition could fork query from gate"
fi

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-provenance: PASS (3 positive, 8 negative)\n'; exit 0
fi
printf 'selftest-provenance: FAIL (%d case(s) failed)\n' "$FAILURES"; exit 1
