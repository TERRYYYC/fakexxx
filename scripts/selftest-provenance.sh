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
POS=0; NEG=0; FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# A squash/rebase merge leaves exactly this: one commit, no import-commit
# ancestry, pristine vendored trees.
mk_squashed() {
  local d; d="$(mktemp -d)"
  ( cd "$d" && git init -q . && git config user.email s@s && git config user.name s )
  ( cd "$REPO_ROOT" && git archive HEAD ) | tar -x -C "$d"
  cp "$PROD" "$d/scripts/check-provenance.sh"
  ( cd "$d" && git add -A >/dev/null && git commit -qm "squash merge of PR #10" )
  printf '%s\n' "$d"
}

run() { ( cd "$2" && ./scripts/check-provenance.sh "${@:3}" ) >/dev/null 2>&1; return $?; }

pos() { # <label> <dir> <args...>
  local l="$1"; shift; POS=$((POS+1))
  local rc; run x "$@"; rc=$?
  [ "$rc" -eq 0 ] && ok "$l (rc=0)" || bad "$l — expected rc=0, got $rc"
}
neg() { # <label> <dir> <args...>
  local l="$1"; shift; NEG=$((NEG+1))
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
( cd "$D" \
  && printf '\n// contract wiring (PR-2 Task 2)\n' >> apps/qianwangyou/app/build.gradle \
  && printf '\n// contract wiring (PR-2 Task 2)\n' >> apps/qianwangyou/build.gradle \
  && printf '\n// contract wiring (PR-2 Task 2)\n' >> apps/qianwangyou/settings.gradle \
  && git add -A && git commit -qm "PR-2 contract wiring" -q ) >/dev/null 2>&1
pos "P-4 squash-merged history + real Task-2 qwy delta" "$D" --stage contract

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
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
a="# Entry files that must exist after a successful import (spec §13 Task 1.3)."
s=s.replace(a,"each_import() { printf '%s\\n' 'apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|main|48d8ec93adb84cdb9c4282c376ec97476648683e'; }\n\n"+a,1)
open(p,'w').write(s)
PY
printf '\n// tampered\n' >> apps/qianwangyou/app/build.gradle
git add -A && git commit -qm fork -q ) >/dev/null 2>&1
neg "N-5 handler-after iterator redefinition + qwy tamper" "$D" --stage import

# A prefix dropped from the frozen record set: the gate would skip an app.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944\n","",1))
PY
) >/dev/null 2>&1
neg "N-6 qwy record removed from the frozen set" "$D" --stage import

# A prefix smuggled into the branch field: a substring membership test reported
# both prefixes present from a one-record set.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
s=s.replace("apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|main|",
            "apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|apps/qianwangyou|",1)
s=s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944\n","",1)
open(p,'w').write(s)
PY
) >/dev/null 2>&1
neg "N-7 prefix smuggled into the branch field" "$D" --stage import

# A malformed record silently shifts every later field read.
D="$(mk_squashed)"
( cd "$D" && python3 - <<'PY'
p='scripts/check-provenance.sh'; s=open(p).read()
open(p,'w').write(s.replace("apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944",
                            "apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|285e4cae438ab6feea1f70f984f433c7a424b944",1))
PY
) >/dev/null 2>&1
neg "N-8 malformed IMPORTS record (3 fields)" "$D" --stage import

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-provenance: PASS (%d positive, %d negative — all executed against the production checker)\n' "$POS" "$NEG"
  exit 0
fi
printf 'selftest-provenance: FAIL (%d of %d case(s) failed)\n' "$FAILURES" "$((POS + NEG))"
exit 1
