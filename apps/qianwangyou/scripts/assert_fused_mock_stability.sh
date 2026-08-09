#!/usr/bin/env bash
# Read-only temporal gate for the user-visible Google Maps location source.
# It deliberately owns no app-op or lifecycle setup so the same probe can test old and new APKs.
set -euo pipefail

SERIAL="${SERIAL:?Set SERIAL to the adb device serial}"
EXPECTED_LATITUDE="${EXPECTED_LATITUDE:-50.450100}"
EXPECTED_LONGITUDE="${EXPECTED_LONGITUDE:-30.523400}"
MOCK_STABILITY_SAMPLES="${MOCK_STABILITY_SAMPLES:-120}"
MOCK_STABILITY_INTERVAL_SECONDS="${MOCK_STABILITY_INTERVAL_SECONDS:-0.5}"
ADB=(adb -s "$SERIAL")

for sample in $(seq 1 "$MOCK_STABILITY_SAMPLES"); do
    dump=$("${ADB[@]}" shell dumpsys location 2>/dev/null)
    fused=$(printf '%s\n' "$dump" \
        | grep -m1 -oE 'Location\[fused [^]]+\]' \
        || true)
    expected_prefix="Location[fused $EXPECTED_LATITUDE,$EXPECTED_LONGITUDE "
    if [[ "$fused" != "$expected_prefix"* ]] || [[ "$fused" != *" mock]" ]]; then
        echo "FUSED_REAL_LOCATION_LEAK sample=$sample observed=${fused:-missing}" >&2
        exit 1
    fi
    if [[ "$sample" -lt "$MOCK_STABILITY_SAMPLES" ]]; then
        sleep "$MOCK_STABILITY_INTERVAL_SECONDS"
    fi
done

echo "FUSED_MOCK_STABILITY_COMPLETE samples=$MOCK_STABILITY_SAMPLES interval=${MOCK_STABILITY_INTERVAL_SECONDS}s coordinate=$EXPECTED_LATITUDE,$EXPECTED_LONGITUDE"
