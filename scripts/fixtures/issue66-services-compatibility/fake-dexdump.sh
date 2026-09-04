#!/usr/bin/env bash
# Device-free dexdump fixture. Synthetic classes*.dex entries contain a
# dexdump-shaped text payload; this fixture emits that payload verbatim.

set -uo pipefail

if [ "${0##*/}" = shasum ]; then
  state="${FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_STATE:-}"
  if [ -n "$state" ] && [ -f "$state" ] && [ "$(sed -n '1p' "$state")" = armed ]; then
    printf 'hash-ready\n' >"$state" || exit 98
    attempts=0
    while [ "$attempts" -lt 5000 ]; do
      case "$(sed -n '1p' "$state" 2>/dev/null || true)" in
        swapped) break ;;
        swap-failed|swap-timeout) exit 98 ;;
      esac
      attempts=$((attempts + 1))
      sleep 0.001
    done
    [ "$(sed -n '1p' "$state" 2>/dev/null || true)" = swapped ] || exit 98
  fi
  python3 - "${@: -1}" <<'PY'
import hashlib
import sys

path = sys.argv[1]
digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
print(f"{digest}  {path}")
PY
  exit $?
fi

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

# Optional deterministic TOCTOU hook used only by the device-free selftest.
# The fake arms a background swap after it has been invoked. A test-only hash
# gate signals immediately before the checker's post-analysis digest read, so
# the replacement cannot race ahead of analysis or lose to the final hash.
swap_target="${FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_TARGET:-}"
swap_replacement="${FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_REPLACEMENT:-}"
swap_state="${FAKE_DEXDUMP_AFTER_ANALYSIS_SWAP_STATE:-}"
if [ -n "$swap_target$swap_replacement$swap_state" ]; then
  if [ -z "$swap_target" ] || [ -z "$swap_replacement" ] || [ -z "$swap_state" ]; then
    printf 'fake dexdump: incomplete after-analysis swap configuration\n' >&2
    exit 95
  fi
  printf 'armed\n' >"$swap_state" || exit 95
  (
    attempts=0
    while [ "$attempts" -lt 5000 ]; do
      if [ -f "$swap_state" ] && [ "$(sed -n '1p' "$swap_state")" = hash-ready ]; then
        if mv -f -- "$swap_replacement" "$swap_target"; then
          printf 'swapped\n' >"$swap_state"
        else
          printf 'swap-failed\n' >"$swap_state"
        fi
        exit
      fi
      attempts=$((attempts + 1))
      sleep 0.001
    done
    printf 'swap-timeout\n' >"$swap_state"
  ) &
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
esac

cat -- "$dex"
