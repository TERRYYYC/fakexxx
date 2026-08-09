#!/usr/bin/env bash
#
# check-provenance.sh — prove that the vendored app baselines are byte-identical
# to the exact upstream SHAs recorded in docs/provenance/upstream-imports.md.
#
# Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §1.2, §13 Task 1.
#
# Why a tree digest and not `git -C <dir> rev-parse --is-inside-work-tree`:
# the subtree directories live inside the fakexxx worktree, so that command
# returns `true` for any empty directory created with `mkdir`. It proves
# neither that the import happened nor that the SHA is correct — it is a
# tautology. This checker compares the committed tree object of each prefix
# against the upstream commit's root tree object, which can only match if the
# content is identical.
#
# Exit codes: 0 = every check passed; 1 = at least one check failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

PROVENANCE_DOC="docs/provenance/upstream-imports.md"

# prefix|upstream url|branch|exact sha
# These are the frozen facts from spec §1.2. The checker asserts the provenance
# document records the same values, so doc and gate cannot drift apart silently.
IMPORTS="
apps/cellrebel-auto|https://github.com/TERRYYYC/Faketest.git|main|48d8ec93adb84cdb9c4282c376ec97476648683e
apps/qianwangyou|https://github.com/TERRYYYC/FakeGps-test.git|master|285e4cae438ab6feea1f70f984f433c7a424b944
"

# Entry files that must exist after a successful import (spec §13 Task 1.3).
ENTRY_FILES="
apps/cellrebel-auto/gradlew
apps/cellrebel-auto/app/build.gradle.kts
apps/cellrebel-auto/app/src/main/AndroidManifest.xml
apps/qianwangyou/gradlew
apps/qianwangyou/app/build.gradle
apps/qianwangyou/app/src/main/AndroidManifest.xml
"

FAILURES=0

pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
section() { printf '\n== %s ==\n' "$1"; }

# ---------------------------------------------------------------------------
section "1. provenance document"

if [ -f "$PROVENANCE_DOC" ]; then
  pass "$PROVENANCE_DOC exists"
  DOC_PRESENT=1
else
  fail "$PROVENANCE_DOC is missing"
  DOC_PRESENT=0
fi

if [ "$DOC_PRESENT" -eq 1 ]; then
  while IFS='|' read -r prefix url branch sha; do
    [ -z "${prefix:-}" ] && continue
    for token in "$url" "$branch" "$sha" "$prefix"; do
      if grep -qF -- "$token" "$PROVENANCE_DOC"; then
        pass "$PROVENANCE_DOC records '$token'"
      else
        fail "$PROVENANCE_DOC does not record '$token'"
      fi
    done
  done <<EOF
$(printf '%s\n' "$IMPORTS")
EOF

  # Every import must name the fakexxx commit that introduced it, and that
  # commit must itself carry the upstream tree at the prefix.
  while IFS='|' read -r prefix url branch sha; do
    [ -z "${prefix:-}" ] && continue
    import_commit="$(sed -n "s@^.*${prefix}.*import commit[^0-9a-f]*\([0-9a-f]\{7,40\}\).*@\1@p" "$PROVENANCE_DOC" | head -1)"
    if [ -z "$import_commit" ]; then
      fail "$PROVENANCE_DOC does not record an import commit for $prefix"
      continue
    fi
    if ! git cat-file -e "${import_commit}^{commit}" 2>/dev/null; then
      fail "recorded import commit $import_commit for $prefix does not exist in this repository"
      continue
    fi
    upstream_tree="$(git rev-parse --quiet --verify "${sha}^{tree}" 2>/dev/null)"
    import_tree="$(git rev-parse --quiet --verify "${import_commit}:${prefix}" 2>/dev/null)"
    if [ -n "$upstream_tree" ] && [ "$import_tree" = "$upstream_tree" ]; then
      pass "recorded import commit ${import_commit} carries the upstream tree at $prefix"
    else
      fail "recorded import commit ${import_commit} does not carry upstream tree at $prefix (found '${import_tree:-<none>}')"
    fi
  done <<EOF
$(printf '%s\n' "$IMPORTS")
EOF
fi

# ---------------------------------------------------------------------------
section "2. upstream tree digest equality"

while IFS='|' read -r prefix url branch sha; do
  [ -z "${prefix:-}" ] && continue

  # CI checkouts are shallow and do not contain upstream objects. Fetch the
  # exact object explicitly. An unobtainable object is a hard failure: silently
  # skipping the comparison would turn this gate into a no-op.
  if ! git rev-parse --quiet --verify "${sha}^{commit}" >/dev/null 2>&1; then
    if ! git fetch --no-tags --quiet "$url" "$sha" 2>/dev/null; then
      fail "cannot fetch $sha from $url (required to verify $prefix; not skippable)"
      continue
    fi
  fi

  upstream_tree="$(git rev-parse --quiet --verify "${sha}^{tree}" 2>/dev/null)"
  if [ -z "$upstream_tree" ]; then
    fail "upstream object $sha is unavailable after fetch from $url"
    continue
  fi

  local_tree="$(git rev-parse --quiet --verify "HEAD:${prefix}" 2>/dev/null)"
  if [ -z "$local_tree" ]; then
    fail "$prefix is not present in HEAD (expected upstream tree $upstream_tree)"
    continue
  fi

  if [ "$local_tree" = "$upstream_tree" ]; then
    pass "$prefix tree $local_tree == ${url##*/}@${sha:0:9} root tree"
  else
    fail "$prefix tree $local_tree != ${url##*/}@${sha:0:9} root tree $upstream_tree"
  fi

  # The digest above describes committed content. Assert the working tree has
  # not drifted from it, otherwise the gate could pass while the checkout on
  # disk differs from what was verified.
  if ! git diff --quiet HEAD -- "$prefix" 2>/dev/null; then
    fail "$prefix has uncommitted modifications; verified digest does not describe the working tree"
  elif [ -n "$(git ls-files --others --exclude-standard -- "$prefix" 2>/dev/null)" ]; then
    fail "$prefix has untracked files; verified digest does not describe the working tree"
  else
    pass "$prefix working tree matches HEAD"
  fi
done <<EOF
$(printf '%s\n' "$IMPORTS")
EOF

# ---------------------------------------------------------------------------
section "3. entry files"

while read -r entry; do
  [ -z "${entry:-}" ] && continue
  if [ -f "$entry" ]; then
    pass "$entry exists"
  else
    fail "$entry is missing"
  fi
done <<EOF
$(printf '%s\n' "$ENTRY_FILES")
EOF

# ---------------------------------------------------------------------------
printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'check-provenance: PASS (all checks)\n'
  exit 0
fi
printf 'check-provenance: FAIL (%d check(s) failed)\n' "$FAILURES"
exit 1
