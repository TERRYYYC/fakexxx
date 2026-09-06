#!/usr/bin/env bash
# Reject device-dispatching Gradle tasks in the repository's regular host verification paths.
# Device work belongs to an explicitly isolated, operator-authorized workflow; this checker only
# reads command text and never invokes Gradle, adb, an emulator, or a physical device.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [ "$#" -eq 0 ]; then
  set -- scripts/verify-a-plus.sh .github/workflows/android-a-plus.yml
fi

failed=0
pattern='gradlew?[[:space:]].*(install[A-Za-z0-9_]*|connected[A-Za-z0-9_]*AndroidTest)'
for target in "$@"; do
  [ -f "$target" ] || {
    printf 'missing verification target: %s\n' "$target" >&2
    failed=1
    continue
  }
  if command -v rg >/dev/null 2>&1; then
    matches="$(rg -n -i "$pattern" "$target" || true)"
  else
    matches="$(grep -E -n -i "$pattern" "$target" || true)"
  fi
  if [ -n "$matches" ]; then
    printf '%s\n' "$matches"
    printf 'device-dispatching Gradle task is forbidden in regular verification: %s\n' "$target" >&2
    failed=1
  fi
done

[ "$failed" -eq 0 ] || exit 1
printf 'host verification device isolation holds\n'
