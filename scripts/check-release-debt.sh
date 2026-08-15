#!/usr/bin/env bash
#
# check-release-debt.sh — machine-enforced gate for release-blocking debt.
#
# Spec: Issue #20 acceptance item 3.
#
# Any open issue carrying BOTH labels `contract-v1.1-debt` AND `release-blocking`
# makes Issue #7 acceptance fail, with the issue numbers listed. The gate is
# structural: it does not interpret the debt — it counts it and reports it. A
# debt item that should no longer block is closed on GitHub, not special-cased
# in this script.
#
# The gate has two modes:
#
#   1. Online (default): queries GitHub via `gh issue list`. Requires `gh` to
#      be installed and authenticated. If `gh` is missing or returns an error,
#      the gate is INCONCLUSIVE — never a silent pass.
#   2. Offline (`--issues-json <file>`): reads a JSON file in the same shape
#      as `gh issue list --json`. Used by selftest to prove red/green without
#      network.
#
# Usage:
#   ./scripts/check-release-debt.sh [--repo OWNER/REPO]
#   ./scripts/check-release-debt.sh --issues-json fixtures/open-debt.json
#
# Exit codes:
#   0 = no open release-blocking debt issues → gate passes
#   1 = at least one open release-blocking debt issue → gate fails
#   2 = inconclusive (gh unavailable, network error, bad input)

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

REPO="TERRYYYC/fakexxx"
ISSUES_JSON=""
QUIET=0

while [ $# -gt 0 ]; do
  case "$1" in
    --repo)
      if [ $# -lt 2 ]; then
        printf 'check-release-debt: --repo requires a value\n' >&2; exit 2
      fi
      REPO="$2"; shift 2 ;;
    --repo=*) REPO="${1#*=}"; shift ;;
    --issues-json)
      if [ $# -lt 2 ]; then
        printf 'check-release-debt: --issues-json requires a path\n' >&2; exit 2
      fi
      ISSUES_JSON="$2"; shift 2 ;;
    --issues-json=*) ISSUES_JSON="${1#*=}"; shift ;;
    --quiet) QUIET=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'check-release-debt: unknown argument "%s"\n' "$1" >&2; exit 2 ;;
  esac
done

# ------------------------------------------------------------------ fetch

if [ -n "$ISSUES_JSON" ]; then
  if [ ! -f "$ISSUES_JSON" ]; then
    printf 'check-release-debt: file not found: %s\n' "$ISSUES_JSON" >&2
    exit 2
  fi
  RAW="$(cat "$ISSUES_JSON")"
else
  if ! command -v gh >/dev/null 2>&1; then
    printf 'check-release-debt: INCONCLUSIVE — gh CLI not found\n' >&2
    printf 'Install: https://cli.github.com/\n' >&2
    exit 2
  fi
  RAW="$(gh issue list \
    --repo "$REPO" \
    --state open \
    --label "contract-v1.1-debt" \
    --label "release-blocking" \
    --json number,title \
    --limit 100 2>&1)" || {
    printf 'check-release-debt: INCONCLUSIVE — gh query failed:\n%s\n' "$RAW" >&2
    exit 2
  }
fi

# ------------------------------------------------------------------ parse

if ! command -v python3 >/dev/null 2>&1; then
  printf 'check-release-debt: INCONCLUSIVE — python3 required for JSON parsing\n' >&2
  exit 2
fi

RESULT="$(python3 -c "
import json, sys
try:
    issues = json.loads(sys.stdin.read())
except (json.JSONDecodeError, ValueError) as e:
    print('ERROR: invalid JSON: ' + str(e), file=sys.stderr)
    sys.exit(2)
if not isinstance(issues, list):
    print('ERROR: expected a JSON array', file=sys.stderr)
    sys.exit(2)
count = len(issues)
print(count)
for i in issues:
    num = i.get('number', '?')
    title = i.get('title', '(no title)')
    print(f'  #{num}: {title}')
" <<< "$RAW")" || exit 2

COUNT="$(head -1 <<< "$RESULT")"
DETAIL="$(tail -n +2 <<< "$RESULT")"

# ------------------------------------------------------------------ verdict

if [ "$COUNT" -eq 0 ]; then
  [ "$QUIET" -eq 0 ] && printf 'check-release-debt: PASS — no open release-blocking debt issues\n'
  exit 0
fi

if [ "$QUIET" -eq 0 ]; then
  printf 'check-release-debt: FAIL — %d open release-blocking debt issue(s)\n' "$COUNT"
  printf '%s\n' "$DETAIL"
  printf '\nIssue #7 acceptance is blocked until all of the above are closed.\n'
  printf 'This is §20.1: every unfrozen and gap item must be resolved before\n'
  printf 'completion can be declared.\n'
fi
exit 1
