#!/usr/bin/env bash
#
# verify-a-plus.sh — aggregate verification gate for the A+ baseline.
#
# Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §14.
#
# The full A+ gate is assembled across several PRs, so this script is stage
# aware. Two rules keep it honest while the repository is incomplete:
#
#   1. A gate that is required at the requested stage but whose script does not
#      exist is a FAILURE, never a skip. A skip would let a PR print a green
#      aggregate line that means nothing.
#   2. Gates belonging to a later stage are printed as PENDING with the PR that
#      owns them, and the summary never claims coverage they do not have.
#
# Usage:
#   ./scripts/verify-a-plus.sh                 # --stage full (strictest)
#   ./scripts/verify-a-plus.sh --stage import  # gates required as of PR-1
#   ./scripts/verify-a-plus.sh --list
#
# Exit codes: 0 = every gate required at this stage passed; 1 = otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

STAGE="full"
LIST_ONLY=0

while [ $# -gt 0 ]; do
  case "$1" in
    --stage) STAGE="${2:-}"; shift 2 ;;
    --stage=*) STAGE="${1#*=}"; shift ;;
    --list) LIST_ONLY=1; shift ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'verify-a-plus: unknown argument "%s"\n' "$1" >&2; exit 1 ;;
  esac
done

# Stage ordering. A stage runs every gate introduced at or before it.
case "$STAGE" in
  import)   STAGE_RANK=1 ;;
  contract) STAGE_RANK=2 ;;
  full)     STAGE_RANK=3 ;;
  *)
    printf 'verify-a-plus: unknown stage "%s" (expected: import | contract | full)\n' "$STAGE" >&2
    exit 1
    ;;
esac

# rank|gate name|owning PR|required file|command
#
# Lint is NOT run as `lintDebug` here. At the imported baseline that command
# exits non-zero on apps/qianwangyou because of 23 pre-existing upstream lint
# errors (neither upstream repo had CI, so lint had never been enforced). The
# `inherited-lint-debt` gate runs lint for both apps and fails only if the
# frozen error inventory grows — the debt stays visible instead of being
# silenced with a lint baseline or a continue-on-error step. Its disposition is
# tracked in docs/provenance/upstream-imports.md.
# Stage 3 ("full") is the A+ acceptance gate. It must contain the gates that
# prove scenarios actually ran, not only the ones that prove code compiles.
# Without them, a tree with zero acceptance scenarios and an empty evidence
# ledger would satisfy `--stage full` — a green light for something nobody
# tested. These entries are declared before their scripts exist precisely so
# that `--stage full` FAILS today with "REQUIRED but missing" instead of
# quietly passing.
GATES="
1|provenance|PR-1|scripts/check-provenance.sh|./scripts/check-provenance.sh --stage \$STAGE
1|auto-unit-tests|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew testDebugUnitTest
1|auto-assemble|PR-1|apps/cellrebel-auto/gradlew|cd apps/cellrebel-auto && ./gradlew assembleDebug
1|qwy-unit-tests|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew testDebugUnitTest
1|qwy-assemble|PR-1|apps/qianwangyou/gradlew|cd apps/qianwangyou && ./gradlew assembleDebug
1|inherited-lint-debt|PR-1|scripts/check-inherited-lint-debt.sh|./scripts/check-inherited-lint-debt.sh
2|contract-v1|PR-2|scripts/check-contract-v1.sh|./scripts/check-contract-v1.sh
3|acceptance-scenarios|PR-5|acceptance/scenarios|cd acceptance && ./gradlew test
3|matrix-coverage|PR-5|scripts/check-matrix-coverage.sh|./scripts/check-matrix-coverage.sh
3|forbidden-boundaries|PR-5|acceptance/scripts/check-forbidden-boundaries.sh|./acceptance/scripts/check-forbidden-boundaries.sh
"

if [ "$LIST_ONLY" -eq 1 ]; then
  printf 'Gate manifest (requested stage: %s)\n\n' "$STAGE"
  printf '%-24s %-8s %-8s %s\n' GATE STAGE OWNER PRESENT
  while IFS='|' read -r rank name pr file _cmd; do
    [ -z "${rank:-}" ] && continue
    case "$rank" in 1) s=import ;; 2) s=contract ;; *) s=full ;; esac
    if [ -e "$file" ]; then present=yes; else present=no; fi
    printf '%-24s %-8s %-8s %s\n' "$name" "$s" "$pr" "$present"
  done <<EOF
$(printf '%s\n' "$GATES")
EOF
  exit 0
fi

# Toolchain preconditions — reported once, explicitly, instead of surfacing as
# an opaque Gradle stack trace inside the first app gate.
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  printf 'verify-a-plus: no JVM on PATH and JAVA_HOME is unset (spec requires Java 17)\n' >&2
  exit 1
fi
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && [ ! -f apps/cellrebel-auto/local.properties ]; then
  printf 'verify-a-plus: Android SDK location unknown (set ANDROID_HOME or ANDROID_SDK_ROOT)\n' >&2
  exit 1
fi

RUN=0; PASSED=0; FAILED=0; PENDING=0
FAILED_NAMES=""; PENDING_NAMES=""

printf 'verify-a-plus: stage=%s\n' "$STAGE"

while IFS='|' read -r rank name pr file cmd; do
  [ -z "${rank:-}" ] && continue

  if [ "$rank" -gt "$STAGE_RANK" ]; then
    PENDING=$((PENDING + 1))
    PENDING_NAMES="$PENDING_NAMES $name(owner=$pr)"
    continue
  fi

  if [ ! -e "$file" ]; then
    # Required at this stage but absent: fail loudly. Never skip.
    printf '\n---- %-22s REQUIRED at stage %s but %s is missing\n' "$name" "$STAGE" "$file"
    FAILED=$((FAILED + 1))
    FAILED_NAMES="$FAILED_NAMES $name(missing)"
    continue
  fi

  printf '\n---- %s\n     $ %s\n' "$name" "$cmd"
  RUN=$((RUN + 1))
  if ( eval "$cmd" ); then
    PASSED=$((PASSED + 1))
    printf '     -> PASS\n'
  else
    FAILED=$((FAILED + 1))
    FAILED_NAMES="$FAILED_NAMES $name"
    printf '     -> FAIL\n'
  fi
done <<EOF
$(printf '%s\n' "$GATES")
EOF

printf '\n========================================\n'
printf 'verify-a-plus summary (stage=%s)\n' "$STAGE"
printf '  ran     : %d\n' "$RUN"
printf '  passed  : %d\n' "$PASSED"
printf '  failed  : %d%s\n' "$FAILED" "${FAILED_NAMES:+ ->$FAILED_NAMES}"
printf '  pending : %d%s\n' "$PENDING" "${PENDING_NAMES:+ ->$PENDING_NAMES}"

if [ "$PENDING" -gt 0 ]; then
  printf '\nNOTE: %d gate(s) belong to a later stage and were NOT evaluated.\n' "$PENDING"
  printf 'A pass at stage=%s does not mean the A+ acceptance criteria are met.\n' "$STAGE"
fi

if [ "$FAILED" -eq 0 ]; then
  printf '\nRESULT: all gates required at stage=%s passed.\n' "$STAGE"
  exit 0
fi
printf '\nRESULT: %d gate(s) failed at stage=%s.\n' "$FAILED" "$STAGE"
exit 1
