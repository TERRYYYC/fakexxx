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
for target in "$@"; do
  [ -f "$target" ] || {
    printf 'missing verification target: %s\n' "$target" >&2
    failed=1
    continue
  }
  if rg -n -i 'gradlew?[[:space:]].*(install[A-Za-z0-9_]*|connected[A-Za-z0-9_]*AndroidTest)' "$target"; then
    printf 'device-dispatching Gradle task is forbidden in regular verification: %s\n' "$target" >&2
    failed=1
  fi
done

[ "$failed" -eq 0 ] || exit 1
printf 'host verification device isolation holds\n'
