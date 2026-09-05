#!/bin/bash -p
# Device-free dexdump fixture. Synthetic classes*.dex entries contain a
# dexdump-shaped text payload; this fixture emits that payload verbatim.

unset BASH_ENV ENV
unset DEVELOPER_DIR SDKROOT TOOLCHAINS
PATH=/usr/bin:/bin
export PATH
set -uo pipefail

if [ -n "${FAKE_DEXDUMP_LOG:-}" ]; then
  {
    printf 'invoke'
    printf ' %q' "$@"
    printf '\n'
  } >>"$FAKE_DEXDUMP_LOG"
fi

# Optional deterministic output-path replacement hook used only by the
# device-free selftest. The checker has already reserved its output pathname
# before invoking dexdump, which makes this the exact boundary needed to prove
# that later writes stay bound to the originally opened file.
output_swap_target="${FAKE_DEXDUMP_OUTPUT_SWAP_TARGET:-}"
output_swap_mode="${FAKE_DEXDUMP_OUTPUT_SWAP_MODE:-}"
output_swap_victim="${FAKE_DEXDUMP_OUTPUT_SWAP_VICTIM:-}"
output_swap_state="${FAKE_DEXDUMP_OUTPUT_SWAP_STATE:-}"
if [ -n "$output_swap_target$output_swap_mode$output_swap_victim$output_swap_state" ]; then
  if [ -z "$output_swap_target" ] || [ -z "$output_swap_mode" ] \
      || [ -z "$output_swap_state" ]; then
    printf 'fake dexdump: incomplete output-swap configuration\n' >&2
    exit 94
  fi
  if [ ! -f "$output_swap_target" ] || [ -L "$output_swap_target" ]; then
    printf 'fake dexdump: output-swap target is not the reserved regular file\n' >&2
    exit 94
  fi
  case "$output_swap_mode" in
    symlink)
      if [ -z "$output_swap_victim" ] || [ ! -f "$output_swap_victim" ] \
          || [ -L "$output_swap_victim" ]; then
        printf 'fake dexdump: invalid output-swap victim\n' >&2
        exit 94
      fi
      rm -f -- "$output_swap_target" \
        && ln -s -- "$output_swap_victim" "$output_swap_target" \
        || exit 94
      ;;
    directory)
      [ -z "$output_swap_victim" ] || exit 94
      rm -f -- "$output_swap_target" \
        && mkdir -- "$output_swap_target" \
        || exit 94
      ;;
    *)
      printf 'fake dexdump: unsupported output-swap mode: %s\n' \
        "$output_swap_mode" >&2
      exit 94
      ;;
  esac
  printf 'swapped\n' >"$output_swap_state" || exit 94
fi

dex=""
for argument in "$@"; do
  if [ -f "$argument" ]; then
    dex="$argument"
  fi
done

if [ -z "$dex" ]; then
  printf 'fake dexdump: no readable dex argument\n' >&2
  exit 96
fi

first_line=""
IFS= read -r first_line <"$dex" || true
case "$first_line" in
  FAKE_DEXDUMP_EXIT=*)
    rc="${first_line#FAKE_DEXDUMP_EXIT=}"
    case "$rc" in
      ''|*[!0-9]*) rc=97 ;;
    esac
    printf 'fake dexdump: requested failure rc=%s\n' "$rc" >&2
    exit "$rc"
    ;;
  FAKE_DEXDUMP_HANG)
    /bin/sleep 5
    exit 98
    ;;
  FAKE_DEXDUMP_STDERR_BYTES=*)
    stderr_bytes="${first_line#FAKE_DEXDUMP_STDERR_BYTES=}"
    case "$stderr_bytes" in
      ''|*[!0-9]*) exit 97 ;;
    esac
    /usr/bin/python3 -I - "$stderr_bytes" <<'PY'
import os
import sys

remaining = int(sys.argv[1])
chunk = b"e" * 65536
while remaining:
    written = os.write(2, chunk[:remaining])
    if written <= 0:
        raise SystemExit(97)
    remaining -= written
PY
    /usr/bin/tail -n +2 -- "$dex"
    exit
    ;;
  FAKE_DEXDUMP_LATE_WRITE)
    late_marker="${FAKE_DEXDUMP_LATE_WRITE_MARKER:-}"
    [ -n "$late_marker" ] || exit 97
    (
      /bin/sleep 1
      printf 'late-write\n' >"$late_marker"
    ) </dev/null >/dev/null 2>&1 9>&- &
    /usr/bin/tail -n +2 -- "$dex"
    exit
    ;;
esac

cat -- "$dex"
