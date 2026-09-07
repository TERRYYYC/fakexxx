#!/usr/bin/env bash
# Reject device-dispatching Gradle tasks in the repository's regular host verification paths.
# Device work belongs to an explicitly isolated, operator-authorized workflow; this checker only
# reads command text and never invokes Gradle, adb, an emulator, or a physical device.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

if [ "$#" -eq 0 ]; then
  set -- scripts/verify-a-plus.sh .github/workflows/android-a-plus.yml
  # Test-only supplemental targets may add a hostile fixture without ever
  # replacing the two production entry points above.  Keeping the production
  # paths unconditional means a caller cannot use this hook to narrow the
  # scope of the real preflight.
  if [ -n "${HOST_VERIFICATION_ADDITIONAL_TARGETS:-}" ]; then
    # Newline is deliberate: repository paths cannot contain one and it keeps
    # the hook unambiguous without shell-evaluating its contents.
    while IFS= read -r additional_target; do
      [ -z "$additional_target" ] || set -- "$@" "$additional_target"
    done <<EOF
${HOST_VERIFICATION_ADDITIONAL_TARGETS}
EOF
  fi
fi

failed=0
for target in "$@"; do
  [ -f "$target" ] || {
    printf 'missing verification target: %s\n' "$target" >&2
    failed=1
    continue
  }
  # A line-based regexp is not sufficient here: Gradle invocations commonly
  # use shell continuations, and GitHub Actions permits folded `run: >-`
  # commands.  Normalize only those two statement forms, then look for a
  # Gradle launcher and a device-dispatching task in the same logical command.
  # `connectedCheck` is intentionally covered alongside connected*AndroidTest:
  # it aggregates connected device tests on Android Gradle Plugin variants.
  if ! matches="$(awk -v target="$target" '
    function indent_width(line,    n, c) {
      n = 0
      while (n < length(line)) {
        c = substr(line, n + 1, 1)
        if (c != " ") break
        n++
      }
      return n
    }
    function forbidden(statement) {
      quote_boundary = "[[:space:]" sprintf("%c", 39) sprintf("%c", 34) "]"
      gradle = "(^|[[:space:];|&])([^[:space:];|&]*/)?gradlew?([[:space:];|&]|$)"
      task = "(^|" quote_boundary ")((:[[:alnum:]_.-]+)*:(install[[:alnum:]_.-]*|connected[[:alnum:]_.-]*)|(install[[:alnum:]_.-]*|connected[[:alnum:]_.-]*))(" quote_boundary "|[;|&]|$)"
      return statement ~ gradle && statement ~ task
    }
    function report(statement, start_line) {
      if (forbidden(statement)) {
        gsub(/[[:space:]]+/, " ", statement)
        print target ":" start_line ":" statement
      }
    }
    function flush_shell() {
      if (shell_statement != "") report(shell_statement, shell_start)
      shell_statement = ""
    }
    function flush_yaml() {
      if (yaml_statement != "") report(yaml_statement, yaml_start)
      yaml_statement = ""
      yaml_active = 0
    }
    {
      line = $0
      if (yaml_active) {
        if (line ~ /^[[:space:]]*$/ || indent_width(line) > yaml_indent) {
          yaml_statement = yaml_statement " " line
          next
        }
        flush_yaml()
      }

      if (line ~ /^[[:space:]]*(-[[:space:]]+)?run:[[:space:]]*>[-+]?([[:space:]]*(#.*)?)$/) {
        yaml_active = 1
        yaml_indent = indent_width(line)
        yaml_start = NR
        yaml_statement = ""
        next
      }

      if (shell_statement == "") {
        shell_statement = line
        shell_start = NR
      } else {
        shell_statement = shell_statement " " line
      }
      if (line ~ /\\[[:space:]]*$/) {
        sub(/\\[[:space:]]*$/, "", shell_statement)
        next
      }
      flush_shell()
    }
    END {
      if (yaml_active) flush_yaml()
      flush_shell()
    }
  ' "$target")"; then
    printf 'could not parse verification target: %s\n' "$target" >&2
    failed=1
    continue
  fi
  if [ -n "$matches" ]; then
    printf '%s\n' "$matches"
    printf 'device-dispatching Gradle task is forbidden in regular verification: %s\n' "$target" >&2
    failed=1
  fi
done

[ "$failed" -eq 0 ] || exit 1
printf 'host verification device isolation holds\n'
