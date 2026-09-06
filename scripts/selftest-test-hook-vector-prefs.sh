#!/usr/bin/env bash
# Device-free regression test for #90's Vector preference resolver.
#
# Executes the shipped snapshot_prefs() body with a fake privileged read seam.
# The production and bench files deliberately coexist; equal bytes must not
# collapse their identities, and different bytes must never select production.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_HOOK="$HERE/../apps/qianwangyou/scripts/test-hook.sh"
VE_LIB="$HERE/../apps/qianwangyou/scripts/vector-evidence.sh"
BENCH_ID="name.caiyao.fakegps.bench"
PROD_ID="name.caiyao.fakegps"
BENCH_PATH="/data/misc/vector-bench/prefs/$BENCH_ID/spoof_config.xml"
PROD_PATH="/data/misc/vector-prod/prefs/$PROD_ID/spoof_config.xml"

pass=0
fail=0

report() {
    if [ "$1" = ok ]; then
        printf 'ok   %s\n' "$2"
        pass=$((pass + 1))
    else
        printf 'FAIL %s :: %s\n' "$2" "$3"
        fail=$((fail + 1))
    fi
}

[ -r "$TEST_HOOK" ] && [ -r "$VE_LIB" ] || {
    echo "selftest target missing" >&2
    exit 1
}

FN_SNAPSHOT="$(sed -n '/^snapshot_prefs()/,/^}/p' "$TEST_HOOK")"
[ -n "$FN_SNAPSHOT" ] || { echo "could not extract snapshot_prefs" >&2; exit 1; }

run_snapshot() { # live paths, bench XML, production XML
    local live_paths="$1" bench_xml="$2" prod_xml="$3"
    CALLS="$(mktemp)"
    OUT="$(
        FAKE_LIVE_PATHS="$live_paths" FAKE_BENCH_XML="$bench_xml" FAKE_PROD_XML="$prod_xml" \
        FAKE_CALLS="$CALLS" VE_LIB_PATH="$VE_LIB" BENCH_PACKAGE="$BENCH_ID" \
        bash -c '
            set -uo pipefail
            ve_live_root_shell() {
                printf "%s\n" "$1" >>"$FAKE_CALLS"
                case "$1" in
                    "ls -d /data/misc/*/prefs/"*"/spoof_config.xml")
                        printf "%s\n" "$FAKE_LIVE_PATHS"
                        ;;
                    "cat /data/misc/vector-bench/prefs/"*"/spoof_config.xml")
                        printf "%s" "$FAKE_BENCH_XML"
                        ;;
                    "cat /data/misc/vector-prod/prefs/"*"/spoof_config.xml")
                        printf "%s" "$FAKE_PROD_XML"
                        ;;
                    *)
                        exit 91
                        ;;
                esac
            }
            root_shell() { ve_live_root_shell "$1"; }
            . "$VE_LIB_PATH"
            '"$FN_SNAPSHOT"'
            snapshot_prefs
        ' 2>&1
    )"
    RC=$?
}

# RED before the shared resolver wiring: this assertion fails while test-hook
# owns a copy of the glob/cardinality/read logic.
grep -q 've_resolve_single_live_path' <<<"$FN_SNAPSHOT" &&
    report ok "S snapshot_prefs delegates live-source identity to vector-evidence" ||
    report fail "S snapshot_prefs must delegate to vector-evidence" "shared resolver call missing"

BENCH_XML='<map><string name="json">{bench}</string></map>'

# Equal payloads used to collapse in a broad find scan. Both files exist, but
# the exact bench path is the only candidate and only the bench file is read.
run_snapshot "$BENCH_PATH" "$BENCH_XML" "$BENCH_XML"
[ "$RC" -eq 0 ] && grep -q '{bench}' <<<"$OUT" &&
    ! grep -q "$PROD_PATH" "$CALLS" && grep -q "$BENCH_PATH" "$CALLS" &&
    report ok "G1 prod+bench identical payloads retain exact bench identity" ||
    report fail "G1 identical prod+bench must read only bench" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

# Different bytes prove the selected value is from bench rather than whichever
# copy happens to be enumerated first.
run_snapshot "$BENCH_PATH" "$BENCH_XML" '<map><string name="json">{production}</string></map>'
[ "$RC" -eq 0 ] && grep -q '{bench}' <<<"$OUT" &&
    ! grep -q '{production}' <<<"$OUT" && ! grep -q "$PROD_PATH" "$CALLS" &&
    report ok "G2 prod+bench divergent payloads retain bench value" ||
    report fail "G2 divergent prod+bench must not read production" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_snapshot '' "$BENCH_XML" '<map><string name="json">{production}</string></map>'
[ "$RC" -ne 0 ] && ! grep -q 'cat /data/misc' "$CALLS" &&
    report ok "G3 zero exact bench sources fails closed without a read" ||
    report fail "G3 zero source must fail closed" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_snapshot "$BENCH_PATH
/data/misc/vector-bench-duplicate/prefs/$BENCH_ID/spoof_config.xml" "$BENCH_XML" '<map><string name="json">{production}</string></map>'
[ "$RC" -ne 0 ] && ! grep -q 'cat /data/misc' "$CALLS" &&
    report ok "G4 multiple exact bench sources fails closed without a read" ||
    report fail "G4 multiple source must fail closed" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

printf 'test-hook Vector prefs selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
