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

# --stage is REQUIRED and has no default, on purpose.
#
# Two different facts are checked here and they have different lifetimes:
#
#   * The import commit carries the upstream root tree. This is IMMUTABLE — it
#     is a statement about a commit that already exists, so it stays true no
#     matter how the apps evolve. Always checked.
#
#   * The CURRENT HEAD tree still equals the upstream root tree. This is only
#     true while nobody has legitimately changed the vendored apps. PR-2/3/4
#     must change them, so asserting it forever would make the first legal app
#     change fail CI permanently and pressure people into weakening the gate.
#
# Defaulting either way is a trap: default-strict breaks later stages, and
# default-lenient silently drops PR-1's strongest check. So the caller must say
# which stage it is, and a missing/unknown stage is a usage failure.
STAGE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --stage=*) STAGE="${1#*=}"; shift ;;
    -h|--help) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'check-provenance: unknown argument "%s"\n' "$1" >&2; exit 1 ;;
  esac
done

case "$STAGE" in
  import|contract|full) ;;
  "") printf 'check-provenance: --stage is required (import | contract | full)\n' >&2; exit 1 ;;
  *)  printf 'check-provenance: unknown stage "%s" (expected: import | contract | full)\n' "$STAGE" >&2; exit 1 ;;
esac

# Pristine-HEAD equality is asserted only while the vendored apps are supposed
# to be untouched, i.e. at the import stage (PR-1).
if [ "$STAGE" = "import" ]; then PRISTINE_HEAD_EXPECTED=1; else PRISTINE_HEAD_EXPECTED=0; fi

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
section "0. fetch upstream objects"

# This runs before every other section because both the document check and the
# digest check resolve `<sha>^{tree}`. A fresh CI clone contains none of these
# objects; a developer machine that fetched them earlier does, which is exactly
# how an ordering bug here passes locally and fails only in CI.
#
# An unobtainable object is a hard failure, never a skip: skipping would reduce
# the whole gate to a no-op.
while IFS='|' read -r prefix url branch sha; do
  [ -z "${prefix:-}" ] && continue
  if git rev-parse --quiet --verify "${sha}^{commit}" >/dev/null 2>&1; then
    pass "upstream object ${sha:0:9} already present"
  elif git fetch --no-tags --quiet "$url" "$sha" 2>/dev/null &&
       git rev-parse --quiet --verify "${sha}^{commit}" >/dev/null 2>&1; then
    pass "fetched ${sha:0:9} from $url"
  else
    fail "cannot fetch $sha from $url (required to verify $prefix; not skippable)"
  fi
done <<EOF
$(printf '%s\n' "$IMPORTS")
EOF

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
    if [ -z "$upstream_tree" ]; then
      # Distinguish "the objects are not here" from "the trees disagree".
      # Reporting a mismatch when the expected side is simply absent sends the
      # reader hunting for a content difference that does not exist.
      fail "upstream object $sha unavailable, cannot check import commit ${import_commit} for $prefix"
    elif [ "$import_tree" = "$upstream_tree" ]; then
      pass "recorded import commit ${import_commit} carries the upstream tree at $prefix"
    else
      fail "recorded import commit ${import_commit} does not carry upstream tree at $prefix (expected '$upstream_tree', found '${import_tree:-<none>}')"
    fi
  done <<EOF
$(printf '%s\n' "$IMPORTS")
EOF
fi

# ---------------------------------------------------------------------------
section "2. current HEAD state (stage=$STAGE)"

while IFS='|' read -r prefix url branch sha; do
  [ -z "${prefix:-}" ] && continue

  upstream_tree="$(git rev-parse --quiet --verify "${sha}^{tree}" 2>/dev/null)"
  if [ -z "$upstream_tree" ]; then
    fail "upstream object $sha is unavailable after fetch from $url"
    continue
  fi

  local_tree="$(git rev-parse --quiet --verify "HEAD:${prefix}" 2>/dev/null)"
  if [ -z "$local_tree" ]; then
    # Absent at any stage is always wrong: the vendored app must exist.
    fail "$prefix is not present in HEAD (expected upstream tree $upstream_tree)"
    continue
  fi

  if [ "$PRISTINE_HEAD_EXPECTED" -eq 1 ]; then
    if [ "$local_tree" = "$upstream_tree" ]; then
      pass "$prefix tree $local_tree == ${url##*/}@${sha:0:9} root tree (pristine, required at stage import)"
    else
      fail "$prefix tree $local_tree != ${url##*/}@${sha:0:9} root tree $upstream_tree"
    fi
  elif [ "$local_tree" = "$upstream_tree" ]; then
    pass "$prefix is still pristine at ${sha:0:9} (not required at stage $STAGE)"
  else
    # Divergence is EXPECTED here. The immutable proof is section 1: the
    # recorded import commit still carries the upstream tree, so the baseline
    # remains provable no matter what later PRs change on top of it.
    pass "$prefix has diverged from upstream (allowed at stage $STAGE; baseline proven at the import commit in section 1)"
  fi

  # The digest above describes committed content. Assert the working tree has
  # not drifted from it, otherwise the gate could pass while the checkout on
  # disk differs from what was verified.
  if ! git diff --quiet HEAD -- "$prefix" 2>/dev/null; then
    fail "$prefix has uncommitted modifications; the verified digest does not describe the working tree"
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
