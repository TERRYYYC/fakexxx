#!/usr/bin/env bash
#
# check-inherited-lint-debt.sh — freeze the Android lint error debt that came in
# with the vendored baselines, so it can shrink but never grow.
#
# Why this exists
# ---------------
# Neither upstream repository (TERRYYYC/Faketest, TERRYYYC/FakeGps-test) has any
# CI workflow, so `lintDebug` had never been run as a gate before this import.
# Running it at the frozen SHAs surfaces 23 pre-existing lint *errors* in
# apps/qianwangyou and 0 in apps/cellrebel-auto.
#
# There are three ways to react and two of them are wrong:
#
#   - add `lint { baseline = ... }` / `abortOnError false` to the app: silences
#     23 real errors and touches a file this PR does not own;
#   - mark the lint step `continue-on-error`: prints green while lint is red;
#   - freeze the exact inventory and fail on any increase: the debt stays
#     visible, cannot grow, and shrinking it is rewarded.
#
# This script is the third option. It does not decide whether the 23 errors get
# fixed — that is a disposition for the Qianwangyou provider PR (Kimi / PR-3) —
# it only prevents them from being silently forgotten or quietly added to.
#
# Usage:
#   ./scripts/check-inherited-lint-debt.sh              # both apps
#   ./scripts/check-inherited-lint-debt.sh cellrebel-auto
#   ./scripts/check-inherited-lint-debt.sh --report-only # parse existing reports
#
# Exit codes: 0 = debt unchanged or reduced; 1 = debt grew, or lint/report could
# not be produced.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

# app|issueId=count,issueId=count,...   ("" means: zero errors allowed)
# Measured at the import HEAD against Faketest@48d8ec9 / FakeGps-test@285e4ca
# with AGP 9.1.0, compileSdk 35, JDK 17.
BUDGETS="
cellrebel-auto|
qianwangyou|MissingPermission=3,MissingTranslation=6,NewApi=9,Range=5
"

REPORT_ONLY=0
ONLY_APP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --report-only) REPORT_ONLY=1; shift ;;
    -h|--help) sed -n '2,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) ONLY_APP="$1"; shift ;;
  esac
done

command -v python3 >/dev/null 2>&1 || {
  printf 'check-inherited-lint-debt: python3 is required to parse the lint report\n' >&2
  exit 1
}

FAILURES=0
CHECKED=0

# An unknown --only-app used to select nothing, leave FAILURES at 0, and exit
# PASS. A gate that reports success after checking nothing is worse than no
# gate: it produces a green line in CI for work that never happened. Validate
# the target up front, and assert below that at least one app was examined.
if [ -n "$ONLY_APP" ]; then
  known=0
  while IFS='|' read -r a _b; do
    [ -z "${a:-}" ] && continue
    [ "$a" = "$ONLY_APP" ] && known=1
  done <<EOF
$(printf '%s\n' "$BUDGETS")
EOF
  if [ "$known" -eq 0 ]; then
    printf 'check-inherited-lint-debt: unknown app "%s"; known apps are:\n' "$ONLY_APP" >&2
    printf '%s\n' "$BUDGETS" | awk -F'|' 'NF{printf "  %s\n", $1}' >&2
    exit 1
  fi
fi

while IFS='|' read -r app budget; do
  [ -z "${app:-}" ] && continue
  [ -n "$ONLY_APP" ] && [ "$ONLY_APP" != "$app" ] && continue
  CHECKED=$((CHECKED + 1))

  dir="apps/$app"
  report="$dir/app/build/reports/lint-results-debug.xml"

  printf '\n== %s ==\n' "$app"

  if [ "$REPORT_ONLY" -eq 0 ]; then
    # lintDebug aborts the build when errors exist but still writes the XML
    # report, which is exactly what this gate consumes. The non-zero exit is
    # expected here and is not the verdict — the inventory comparison is.
    ( cd "$dir" && ./gradlew --no-daemon lintDebug ) >/dev/null 2>&1
  fi

  if [ ! -f "$report" ]; then
    printf '  FAIL  lint report not produced at %s\n' "$report"
    FAILURES=$((FAILURES + 1))
    continue
  fi

  if ! BUDGET="$budget" APP="$app" python3 - "$report" <<'PY'
import collections, os, sys, xml.etree.ElementTree as ET

report = sys.argv[1]
budget_raw = os.environ.get("BUDGET", "").strip()
app = os.environ["APP"]

budget = {}
if budget_raw:
    for part in budget_raw.split(","):
        k, _, v = part.partition("=")
        budget[k.strip()] = int(v)

root = ET.parse(report).getroot()
actual = collections.Counter(
    i.get("id") for i in root if i.get("severity") == "Error"
)

ok = True
for issue in sorted(set(actual) | set(budget)):
    have, allowed = actual.get(issue, 0), budget.get(issue, 0)
    if have > allowed:
        print(f"  FAIL  {issue}: {have} error(s), budget {allowed}"
              f"{' (new issue type)' if issue not in budget else ''}")
        ok = False
    elif have < allowed:
        print(f"  PASS  {issue}: {have} error(s), budget {allowed} "
              f"-- debt reduced by {allowed - have}; lower the budget in this script")
    else:
        print(f"  PASS  {issue}: {have} error(s) == frozen budget")

total, allowed_total = sum(actual.values()), sum(budget.values())
print(f"  total errors: {total} (frozen budget {allowed_total})")
if not ok:
    print(f"  -> {app} lint debt GREW. Fix the new errors; do not raise the budget "
          f"and do not add a lint baseline to silence them.")
sys.exit(0 if ok else 1)
PY
  then
    FAILURES=$((FAILURES + 1))
  fi
done <<EOF
$(printf '%s\n' "$BUDGETS")
EOF

printf '\n'
# Zero apps examined is never a pass. Reaching here with CHECKED=0 means the
# selection logic excluded everything, and reporting PASS would assert something
# the run never looked at.
if [ "$CHECKED" -eq 0 ]; then
  printf 'check-inherited-lint-debt: FAIL (no app was examined; refusing to report PASS for zero checks)\n'
  exit 1
fi
if [ "$FAILURES" -eq 0 ]; then
  printf 'check-inherited-lint-debt: PASS (%d app(s) checked; no app increased its lint error debt)\n' "$CHECKED"
  printf 'NOTE: passing means the debt did not grow. It does NOT mean lintDebug exits 0.\n'
  exit 0
fi
printf 'check-inherited-lint-debt: FAIL (%d app(s) increased lint error debt)\n' "$FAILURES"
exit 1
