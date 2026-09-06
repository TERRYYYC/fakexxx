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

WORK="$(mktemp -d "${TMPDIR:-/tmp}/test-hook-vector-prefs.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT
SHIM="$WORK/bin"
ADB_SENTINEL="$WORK/adb-called"
mkdir -p "$SHIM"
cat >"$SHIM/adb" <<'SH'
#!/bin/sh
printf 'unexpected adb call: %s\n' "$*" >>"${ADB_SENTINEL:?}"
exit 97
SH
chmod +x "$SHIM/adb"
REAL_PY="$(command -v python3)" || {
    echo "python3 is required for the host-only XML selftest" >&2
    exit 1
}

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

# Exercise the shipped bootstrap fragment in a subshell.  Do not launch the
# hook itself: that would be a device command.  A quoted/escaped parameter
# expansion turns VE_LIB_PATH into a literal filename and fails before the
# library can be sourced.
HOOK_VECTOR_SETUP="$(sed -n '/^ve_live_root_shell()/,/^\. "\$VE_LIB"/p' "$TEST_HOOK")"
HOOK_BOOTSTRAP_OUT="$(
    set -u
    root_shell() { :; }
    SCRIPT_DIR=/nondefault-vector-library-path
    VE_LIB_PATH="$VE_LIB"
    eval "$HOOK_VECTOR_SETUP"
    printf '%s\n' "$VE_LIB"
)"
HOOK_BOOTSTRAP_RC=$?

[ "$HOOK_BOOTSTRAP_RC" -eq 0 ] && [ "$HOOK_BOOTSTRAP_OUT" = "$VE_LIB" ] &&
    report ok "B test-hook bootstrap expands VE_LIB_PATH without a device command" ||
    report fail "B test-hook bootstrap must load caller-provided Vector library" "rc=$HOOK_BOOTSTRAP_RC out=$HOOK_BOOTSTRAP_OUT"

FN_SNAPSHOT="$(sed -n '/^snapshot_prefs()/,/^}/p' "$TEST_HOOK")"
[ -n "$FN_SNAPSHOT" ] || { echo "could not extract snapshot_prefs" >&2; exit 1; }
FN_XML_PARSER="$(sed -n '/^parse_vector_prefs_xml()/,/^}/p' "$TEST_HOOK")"
FN_TEMP_CLEANUP="$(sed -n '/^cleanup_vector_prefs_temp_dir()/,/^}/p' "$TEST_HOOK")"

[ -n "$FN_XML_PARSER" ] && [ -n "$FN_TEMP_CLEANUP" ] &&
    report ok "S0 snapshot and recovery share one semantic XML parser + checked cleanup helper" ||
    report fail "S0 shared semantic XML/cleanup helpers must exist" "parser=${FN_XML_PARSER:+present} cleanup=${FN_TEMP_CLEANUP:+present}"

run_snapshot() { # live paths, bench XML, production XML, read rc, parser rc, cleanup mode, optional byte file
    local live_paths="$1" bench_xml="$2" prod_xml="$3" read_rc="${4:-0}"
    local parser_rc="${5:-0}" cleanup_mode="${6:-ok}" bench_file="${7:-}"
    CALLS="$(mktemp)"
    CASE_TMP="$(mktemp -d "$WORK/snapshot.XXXXXX")"
    OUT="$(
        FAKE_LIVE_PATHS="$live_paths" FAKE_BENCH_XML="$bench_xml" FAKE_PROD_XML="$prod_xml" \
        FAKE_CALLS="$CALLS" FAKE_READ_RC="$read_rc" FAKE_XML_PARSER_RC="$parser_rc" \
        FAKE_CLEANUP_MODE="$cleanup_mode" FAKE_BENCH_FILE="$bench_file" \
        VE_LIB_PATH="$VE_LIB" BENCH_PACKAGE="$BENCH_ID" PY="$REAL_PY" \
        TMPDIR="$CASE_TMP" PATH="$SHIM:$PATH" ADB_SENTINEL="$ADB_SENTINEL" \
        bash -c '
            # Match the shipped script exactly: test-hook.sh uses `set -u`,
            # not pipefail. A regression test must not add stronger shell
            # semantics than production and accidentally hide a swallowed
            # privileged-read failure.
            set -u
            ve_live_root_shell() {
                printf "%s\n" "$1" >>"$FAKE_CALLS"
                case "$1" in
                    "ls -d /data/misc/*/prefs/"*"/spoof_config.xml")
                        printf "%s\n" "$FAKE_LIVE_PATHS"
                        ;;
                    "cat /data/misc/vector-bench/prefs/"*"/spoof_config.xml")
                        if [ -n "$FAKE_BENCH_FILE" ]; then
                            command cat -- "$FAKE_BENCH_FILE"
                        else
                            printf "%s" "$FAKE_BENCH_XML"
                        fi
                        return "$FAKE_READ_RC"
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
            rm() {
                local target="${!#}"
                if [ "${1:-}" = -rf ]; then
                    case "$target" in
                        "$TMPDIR"/fakegps-vector-prefs.*)
                            printf "cleanup-attempt:%s\n" "$target" >>"$FAKE_CALLS"
                            case "$FAKE_CLEANUP_MODE" in
                                rc42) return 42 ;;
                                zero-leave) return 0 ;;
                            esac
                            ;;
                    esac
                fi
                command rm "$@"
            }
            . "$VE_LIB_PATH"
            '"$FN_XML_PARSER"'
            '"$FN_TEMP_CLEANUP"'
            '"$FN_SNAPSHOT"'
            if [ "$FAKE_XML_PARSER_RC" -ne 0 ]; then
                parse_vector_prefs_xml() {
                    printf "<string name=\"json\">{\"plausible\":true}</string>\n"
                    return "$FAKE_XML_PARSER_RC"
                }
            fi
            snapshot_prefs
        ' 2>&1
    )"
    RC=$?
    command rm -rf -- "$CASE_TMP"
}

# RED before the shared read wiring: this assertion fails while test-hook owns
# a copy of the glob/cardinality/read logic or only shares path resolution
# while retaining a pipeline that can swallow the actual read status.
grep -q 've_read_single_live_file' <<<"$FN_SNAPSHOT" &&
    report ok "S snapshot_prefs delegates live-source identity and read status to vector-evidence" ||
    report fail "S snapshot_prefs must delegate the complete live read to vector-evidence" "shared read call missing"

BENCH_XML='<map><string name="json">{"source":"bench"}</string></map>'
XML_FIXTURES="$WORK/xml-fixtures"
mkdir -p "$XML_FIXTURES"
"$REAL_PY" - "$XML_FIXTURES" <<'PY'
import codecs
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
json_xml = '<?xml version="1.0" encoding="UTF-8"?><map><string name="json">{"source":"bench"}</string></map>'
(root / "json-bom.xml").write_bytes(codecs.BOM_UTF8 + json_xml.encode("utf-8"))
(root / "json-utf16le-bom.xml").write_bytes(
    codecs.BOM_UTF16_LE + json_xml.replace("UTF-8", "UTF-16").encode("utf-16-le")
)
(root / "json-utf16be-bom.xml").write_bytes(
    codecs.BOM_UTF16_BE + json_xml.replace("UTF-8", "UTF-16").encode("utf-16-be")
)
(root / "json-utf32le-bom.xml").write_bytes(
    codecs.BOM_UTF32_LE + json_xml.replace("UTF-8", "UTF-32").encode("utf-32-le")
)
(root / "json-utf32be-bom.xml").write_bytes(
    codecs.BOM_UTF32_BE + json_xml.replace("UTF-8", "UTF-32").encode("utf-32-be")
)
(root / "json-utf16le-no-bom.xml").write_bytes(json_xml.encode("utf-16-le"))
(root / "json-utf16be-no-bom.xml").write_bytes(json_xml.encode("utf-16-be"))
(root / "json-utf32le-no-bom.xml").write_bytes(json_xml.encode("utf-32-le"))
(root / "json-utf32be-no-bom.xml").write_bytes(json_xml.encode("utf-32-be"))
(root / "json-invalid-utf8.xml").write_bytes(b'<map><string name="json">{"bad":"\xff"}</string></map>')
(root / "json-nul.xml").write_bytes(b'<map><string name="json">{"bad":true}</string>\x00</map>')
(root / "json-empty.xml").write_bytes(b"")
(root / "json-non-utf8-declaration.xml").write_bytes(
    json_xml.replace("UTF-8", "ISO-8859-1").encode("ascii")
)
(root / "json-late-dtd.xml").write_bytes(
    (
        '<?xml version="1.0" encoding="UTF-8"?>'
        + (" " * 5000)
        + '<!DOCTYPE map [<!ENTITY injected "true">]>'
        + '<map><string name="json">{"source":&injected;}</string></map>'
    ).encode("utf-8")
)
PY

# Equal payloads used to collapse in a broad find scan. Both files exist, but
# the exact bench path is the only candidate and only the bench file is read.
run_snapshot "$BENCH_PATH" "$BENCH_XML" "$BENCH_XML"
[ "$RC" -eq 0 ] && grep -q '"source":"bench"' <<<"$OUT" &&
    ! grep -q "$PROD_PATH" "$CALLS" && grep -q "$BENCH_PATH" "$CALLS" &&
    report ok "G1 prod+bench identical payloads retain exact bench identity" ||
    report fail "G1 identical prod+bench must read only bench" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

DECLARED_JSON_XML='<?xml version="1.0" encoding="UTF-8"?><map><string name="json">{"source":"declared-utf8"}</string></map>'
run_snapshot "$BENCH_PATH" "$DECLARED_JSON_XML" ''
{ [ "$RC" -eq 0 ] && grep -q '"source":"declared-utf8"' <<<"$OUT"; } &&
    report ok "G1b valid explicit UTF-8 declaration remains accepted" ||
    report fail "G1b strict envelope must accept its valid UTF-8 form" "rc=$RC out=$OUT"
rm -f "$CALLS"

FINITE_EXPONENT_XML='<map><string name="json">{"largeFinite":1e308}</string></map>'
run_snapshot "$BENCH_PATH" "$FINITE_EXPONENT_XML" ''
{ [ "$RC" -eq 0 ] && grep -q '"largeFinite":1e308' <<<"$OUT"; } &&
    report ok "G1c large but finite JSON exponent remains accepted without rewriting" ||
    report fail "G1c finite exponent must not be over-rejected" "rc=$RC out=$OUT"
rm -f "$CALLS"

# Different bytes prove the selected value is from bench rather than whichever
# copy happens to be enumerated first.
run_snapshot "$BENCH_PATH" "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>'
[ "$RC" -eq 0 ] && grep -q '"source":"bench"' <<<"$OUT" &&
    ! grep -q '"source":"production"' <<<"$OUT" && ! grep -q "$PROD_PATH" "$CALLS" &&
    report ok "G2 prod+bench divergent payloads retain bench value" ||
    report fail "G2 divergent prod+bench must not read production" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_snapshot '' "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>'
[ "$RC" -ne 0 ] && ! grep -q 'cat /data/misc' "$CALLS" &&
    report ok "G3 zero exact bench sources fails closed without a read" ||
    report fail "G3 zero source must fail closed" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_snapshot "$BENCH_PATH
/data/misc/vector-bench-duplicate/prefs/$BENCH_ID/spoof_config.xml" "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>'
[ "$RC" -ne 0 ] && ! grep -q 'cat /data/misc' "$CALLS" &&
    report ok "G4 multiple exact bench sources fails closed without a read" ||
    report fail "G4 multiple source must fail closed" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

# Even a one-line resolver result is not trusted unless it repeats the exact
# package/file coordinate requested by the caller.
run_snapshot "/data/misc/vector-prod/prefs/$PROD_ID/spoof_config.xml" "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>'
[ "$RC" -ne 0 ] && grep -q 'mismatched or unsafe path' <<<"$OUT" && ! grep -q '^cat ' "$CALLS" &&
    report ok "G4b mismatched one-line resolver output is rejected before privileged read" ||
    report fail "G4b resolver output must bind exact requested package/file" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

# A unique source identity is not enough when the privileged read itself
# fails. This runs without pipefail, exactly like test-hook.sh, so a trailing
# sed/sort stage must not turn the read failure into rc=0.
run_snapshot "$BENCH_PATH" "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>' 41
[ "$RC" -eq 41 ] &&
    report ok "G5 unique source preserves failed privileged-read rc=41" ||
    report fail "G5 live read failure must propagate without production pipefail" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

# A semantic parser may emit a plausible canonical row and then fail. The
# caller must not publish that stdout or turn the parser failure green.
run_snapshot "$BENCH_PATH" "$BENCH_XML" '<map><string name="json">{"source":"production"}</string></map>' 0 42
[ "$RC" -ne 0 ] && ! grep -q 'plausible' <<<"$OUT" &&
    report ok "G5b snapshot parser output followed by rc=42 remains a failure" ||
    report fail "G5b snapshot must preserve semantic parser status and discard its stdout" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

# The source identity can be exact while the bytes are not a trustworthy
# SharedPreferences transport. Every invalid envelope/structure must fail
# before a canonical json row is exposed to the fingerprint consumers.
for json_case in \
    'malformed|<map><string name="json">{"source":"bench"}</string>' \
    'wrong-root|<prefs><string name="json">{"source":"bench"}</string></prefs>' \
    'wrong-type|<map><boolean name="json" value="true" /></map>' \
    'duplicate-pref-key|<map><string name="json">{"a":1}</string><string name="json">{"a":2}</string></map>' \
    'duplicate-json-key|<map><string name="json">{"a":1,"a":2}</string></map>' \
    'nonfinite-json|<map><string name="json">{"a":NaN}</string></map>' \
    'positive-float-overflow|<map><string name="json">{"x":1e999}</string></map>' \
    'negative-float-overflow|<map><string name="json">{"x":-1e999}</string></map>' \
    'nested-float-overflow|<map><string name="json">{"x":{"values":[1,1e999]}}</string></map>'
do
    json_name=${json_case%%|*}
    json_bytes=${json_case#*|}
    run_snapshot "$BENCH_PATH" "$json_bytes" ''
    { [ "$RC" -ne 0 ] && ! grep -q '^<string name="json">' <<<"$OUT"; } &&
        report ok "GJ-$json_name invalid semantic json transport fails closed" ||
        report fail "GJ-$json_name semantic XML/JSON parser" "rc=$RC out=$OUT"
    rm -f "$CALLS"
done

for json_file in \
    json-bom.xml \
    json-utf16le-bom.xml json-utf16be-bom.xml json-utf32le-bom.xml json-utf32be-bom.xml \
    json-utf16le-no-bom.xml json-utf16be-no-bom.xml json-utf32le-no-bom.xml json-utf32be-no-bom.xml \
    json-invalid-utf8.xml json-nul.xml json-empty.xml \
    json-non-utf8-declaration.xml json-late-dtd.xml
do
    run_snapshot "$BENCH_PATH" '' '' 0 0 ok "$XML_FIXTURES/$json_file"
    { [ "$RC" -ne 0 ] && ! grep -q '^<string name="json">' <<<"$OUT"; } &&
        report ok "GJ-$json_file invalid byte/DTD envelope fails closed" ||
        report fail "GJ-$json_file strict plain-UTF-8 envelope" "rc=$RC out=$OUT"
    rm -f "$CALLS"
done

run_snapshot "$BENCH_PATH" "$BENCH_XML" '' 0 0 rc42
{ [ "$RC" -eq 2 ] && ! grep -q '"source":"bench"' <<<"$OUT" \
  && grep -q 'temporary Vector prefs.*cleanup' <<<"$OUT"; } &&
    report ok "GJ-cleanup-rc snapshot cleanup rc=42 blocks plausible stdout" ||
    report fail "GJ-cleanup-rc cleanup status is part of snapshot success" "rc=$RC out=$OUT"
rm -f "$CALLS"

run_snapshot "$BENCH_PATH" "$BENCH_XML" '' 0 0 zero-leave
{ [ "$RC" -eq 2 ] && ! grep -q '"source":"bench"' <<<"$OUT" \
  && grep -q 'temporary Vector prefs.*survived cleanup' <<<"$OUT"; } &&
    report ok "GJ-cleanup-postcondition snapshot cleanup rc=0 cannot hide residue" ||
    report fail "GJ-cleanup-postcondition cleanup postcondition is authoritative" "rc=$RC out=$OUT"
rm -f "$CALLS"

run_snapshot "$BENCH_PATH" "$BENCH_XML" '' 41 0 rc42
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector prefs.*cleanup' <<<"$OUT"; } &&
    report ok "GJ-read-cleanup snapshot read failure still reports cleanup failure" ||
    report fail "GJ-read-cleanup every snapshot exit path checks cleanup" "rc=$RC out=$OUT"
rm -f "$CALLS"

run_snapshot "$BENCH_PATH" "$BENCH_XML" '' 0 42 rc42
{ [ "$RC" -eq 2 ] && ! grep -q 'plausible' <<<"$OUT" \
  && grep -q 'temporary Vector prefs.*cleanup' <<<"$OUT"; } &&
    report ok "GJ-parser-cleanup snapshot parser failure still reports cleanup failure" ||
    report fail "GJ-parser-cleanup every snapshot parser exit checks cleanup" "rc=$RC out=$OUT"
rm -f "$CALLS"

FN_PENDING="$(sed -n '/^has_pending_recovery()/,/^}/p' "$TEST_HOOK")"
[ -n "$FN_PENDING" ] || { echo "could not extract has_pending_recovery" >&2; exit 1; }
grep -q 've_read_single_live_file' <<<"$FN_PENDING" &&
    ! grep -q 'find[[:space:]].*/data/misc' <<<"$FN_PENDING" &&
    report ok "S2 recovery lookup delegates the exact-package live read and contains no broad /data/misc scan" ||
    report fail "S2 recovery lookup must share the exact-package reader" "shared read missing or broad scan present"

run_pending() { # live paths, bench XML, production XML, read rc, parser rc, cleanup mode, optional byte file
    local live_paths="$1" bench_xml="$2" prod_xml="$3" read_rc="${4:-0}"
    local parser_rc="${5:-0}" cleanup_mode="${6:-ok}" bench_file="${7:-}"
    local parser_protocol="${8:-native}"
    CALLS="$(mktemp)"
    CASE_TMP="$(mktemp -d "$WORK/pending.XXXXXX")"
    OUT="$(
        FAKE_LIVE_PATHS="$live_paths" FAKE_BENCH_XML="$bench_xml" FAKE_PROD_XML="$prod_xml" \
        FAKE_CALLS="$CALLS" FAKE_READ_RC="$read_rc" FAKE_XML_PARSER_RC="$parser_rc" \
        FAKE_CLEANUP_MODE="$cleanup_mode" FAKE_BENCH_FILE="$bench_file" \
        FAKE_XML_PARSER_PROTOCOL="$parser_protocol" \
        VE_LIB_PATH="$VE_LIB" BENCH_PACKAGE="$BENCH_ID" PY="$REAL_PY" \
        TMPDIR="$CASE_TMP" PATH="$SHIM:$PATH" ADB_SENTINEL="$ADB_SENTINEL" \
        bash -c '
            set -u
            ve_live_root_shell() {
                printf "%s\n" "$1" >>"$FAKE_CALLS"
                case "$1" in
                    "ls -d /data/misc/*/prefs/"*"/hook_acceptance_recovery.xml")
                        printf "%s\n" "$FAKE_LIVE_PATHS"
                        ;;
                    "cat /data/misc/vector-bench/prefs/"*"/hook_acceptance_recovery.xml")
                        if [ -n "$FAKE_BENCH_FILE" ]; then
                            command cat -- "$FAKE_BENCH_FILE"
                        else
                            printf "%s" "$FAKE_BENCH_XML"
                        fi
                        return "$FAKE_READ_RC"
                        ;;
                    "cat /data/misc/vector-prod/prefs/"*"/hook_acceptance_recovery.xml")
                        printf "%s" "$FAKE_PROD_XML"
                        return "$FAKE_READ_RC"
                        ;;
                    *)
                        return 91
                        ;;
                esac
            }
            root_shell() {
                printf "%s\n" "$1" >>"$FAKE_CALLS"
                case "$1" in
                    "find /data/misc"*)
                        # Model the old broad scan: both packages contribute.
                        printf "%s\n%s\n" "$FAKE_BENCH_XML" "$FAKE_PROD_XML"
                        ;;
                    *) ve_live_root_shell "$1" ;;
                esac
            }
            rm() {
                local target="${!#}"
                if [ "${1:-}" = -rf ]; then
                    case "$target" in
                        "$TMPDIR"/fakegps-vector-recovery.*)
                            printf "cleanup-attempt:%s\n" "$target" >>"$FAKE_CALLS"
                            case "$FAKE_CLEANUP_MODE" in
                                rc42) return 42 ;;
                                zero-leave) return 0 ;;
                            esac
                            ;;
                    esac
                fi
                command rm "$@"
            }
            . "$VE_LIB_PATH"
            '"$FN_XML_PARSER"'
            '"$FN_TEMP_CLEANUP"'
            '"$FN_PENDING"'
            if [ "$FAKE_XML_PARSER_RC" -ne 0 ] || [ "$FAKE_XML_PARSER_PROTOCOL" != native ]; then
                parse_vector_prefs_xml() {
                    case "$FAKE_XML_PARSER_PROTOCOL" in
                        native|true) printf "true\n" ;;
                        empty) : ;;
                        truthy) printf "truthy\n" ;;
                        multiline) printf "true\nfalse\n" ;;
                        *) printf "%s\n" "$FAKE_XML_PARSER_PROTOCOL" ;;
                    esac
                    return "$FAKE_XML_PARSER_RC"
                }
            fi
            has_pending_recovery
        ' 2>&1
    )"
    RC=$?
    command rm -rf -- "$CASE_TMP"
}

RECOVERY_BENCH_PATH="/data/misc/vector-bench/prefs/$BENCH_ID/hook_acceptance_recovery.xml"
RECOVERY_PROD_PATH="/data/misc/vector-prod/prefs/$PROD_ID/hook_acceptance_recovery.xml"
PENDING_XML='<map><boolean name="pending" value="true" /></map>'
CLEAR_XML='<map><boolean name="pending" value="false" /></map>'

# Production pending=true must not contaminate a bench pending=false result.
run_pending "$RECOVERY_BENCH_PATH" "$CLEAR_XML" "$PENDING_XML"
[ "$RC" -eq 1 ] && grep -q "$RECOVERY_BENCH_PATH" "$CALLS" &&
    ! grep -q "$RECOVERY_PROD_PATH" "$CALLS" &&
    report ok "G6 recovery lookup retains exact bench identity" ||
    report fail "G6 broad recovery scan must not borrow production pending state" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML"
[ "$RC" -eq 0 ] && ! grep -q "$RECOVERY_PROD_PATH" "$CALLS" &&
    report ok "G7 exact bench pending marker is detected" ||
    report fail "G7 bench pending marker" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 41
[ "$RC" -eq 2 ] &&
    report ok "G8 recovery live-read failure is distinct from pending=false" ||
    report fail "G8 recovery read failure must return error status 2" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending '' "$CLEAR_XML" "$PENDING_XML"
[ "$RC" -eq 2 ] &&
    report ok "G9 missing exact recovery source fails closed" ||
    report fail "G9 missing recovery source must return error status 2" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 0 42
[ "$RC" -eq 2 ] &&
    report ok "G10 recovery semantic parser stdout + rc=42 remains source error" ||
    report fail "G10 parser error must not collapse into pending=false" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

for parser_protocol in empty truthy multiline; do
    run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 0 0 ok '' "$parser_protocol"
    [ "$RC" -eq 2 ] &&
        report ok "G10-$parser_protocol parser rc=0 still requires one exact true|false token" ||
        report fail "G10-$parser_protocol pending parser protocol must be exact" "rc=$RC out=$OUT"
    rm -f "$CALLS"
done

ATTR_ORDER_PENDING_XML=$'<?xml version="1.0" encoding="UTF-8"?>\n<map>\n  <string name="previous_json">{&quot;old&quot;:true}</string>\n  <boolean value="true" name="pending" />\n</map>'
run_pending "$RECOVERY_BENCH_PATH" "$ATTR_ORDER_PENDING_XML" "$CLEAR_XML"
[ "$RC" -eq 0 ] &&
    report ok "GP-attribute-order semantic pending=true accepts reordered attributes" ||
    report fail "GP-attribute-order XML attributes are unordered" "rc=$RC out=$OUT"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" '<map><string name="previous_json">{"old":true}</string></map>' "$CLEAR_XML"
[ "$RC" -eq 1 ] &&
    report ok "GP-missing absent pending key is a valid clear record" ||
    report fail "GP-missing missing pending key must not be a parse error" "rc=$RC out=$OUT"
rm -f "$CALLS"

for pending_case in \
    'malformed|<map><boolean name="pending" value="true" />' \
    'wrong-root|<prefs><boolean name="pending" value="true" /></prefs>' \
    'namespace-root|<map xmlns="urn:not-shared-prefs"><boolean name="pending" value="true" /></map>' \
    'wrong-type|<map><string name="pending" value="true">true</string></map>' \
    'duplicate|<map><boolean name="pending" value="true" /><boolean name="pending" value="false" /></map>' \
    'invalid-value|<map><boolean name="pending" value="truthy" /></map>' \
    'extra-attribute|<map><boolean value="true" name="pending" extra="forbidden" /></map>' \
    'nonblank-body|<map><boolean name="pending" value="true">not-empty</boolean></map>' \
    'nested|<map><wrapper name="other"><boolean name="pending" value="true" /></wrapper></map>'
do
    pending_name=${pending_case%%|*}
    pending_bytes=${pending_case#*|}
    run_pending "$RECOVERY_BENCH_PATH" "$pending_bytes" "$CLEAR_XML"
    [ "$RC" -eq 2 ] &&
        report ok "GP-$pending_name invalid pending XML is a parse/source error" ||
        report fail "GP-$pending_name pending XML must fail closed" "rc=$RC out=$OUT"
    rm -f "$CALLS"
done

for pending_file in \
    json-bom.xml \
    json-utf16le-bom.xml json-utf16be-bom.xml json-utf32le-bom.xml json-utf32be-bom.xml \
    json-utf16le-no-bom.xml json-utf16be-no-bom.xml json-utf32le-no-bom.xml json-utf32be-no-bom.xml \
    json-invalid-utf8.xml json-nul.xml json-empty.xml \
    json-non-utf8-declaration.xml json-late-dtd.xml
do
    run_pending "$RECOVERY_BENCH_PATH" '' "$CLEAR_XML" 0 0 ok "$XML_FIXTURES/$pending_file"
    [ "$RC" -eq 2 ] &&
        report ok "GP-$pending_file invalid byte/DTD envelope is a parse/source error" ||
        report fail "GP-$pending_file strict plain-UTF-8 pending envelope" "rc=$RC out=$OUT"
    rm -f "$CALLS"
done

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 0 0 rc42
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector recovery.*cleanup' <<<"$OUT" \
  && [ "$(grep -c '^cleanup-attempt:' "$CALLS")" -eq 1 ]; } &&
    report ok "GP-cleanup-rc pending cleanup rc=42 blocks pending=true" ||
    report fail "GP-cleanup-rc cleanup status is part of pending success" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$CLEAR_XML" "$PENDING_XML" 0 0 rc42
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector recovery.*cleanup' <<<"$OUT" \
  && [ "$(grep -c '^cleanup-attempt:' "$CALLS")" -eq 1 ]; } &&
    report ok "GP-clear-cleanup pending=false cleanup failure cannot return clear" ||
    report fail "GP-clear-cleanup cleanup status outranks valid clear state" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 0 0 zero-leave
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector recovery.*survived cleanup' <<<"$OUT" \
  && [ "$(grep -c '^cleanup-attempt:' "$CALLS")" -eq 1 ]; } &&
    report ok "GP-cleanup-postcondition pending cleanup rc=0 cannot hide residue" ||
    report fail "GP-cleanup-postcondition cleanup postcondition is authoritative" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 0 42 rc42
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector recovery.*cleanup' <<<"$OUT" \
  && [ "$(grep -c '^cleanup-attempt:' "$CALLS")" -eq 1 ]; } &&
    report ok "GP-parser-cleanup parser failure still checks cleanup exactly once" ||
    report fail "GP-parser-cleanup every pending parser exit checks cleanup" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

run_pending "$RECOVERY_BENCH_PATH" "$PENDING_XML" "$CLEAR_XML" 41 0 rc42
{ [ "$RC" -eq 2 ] && grep -q 'temporary Vector recovery.*cleanup' <<<"$OUT" \
  && [ "$(grep -c '^cleanup-attempt:' "$CALLS")" -eq 1 ]; } &&
    report ok "GP-read-cleanup read failure still checks cleanup exactly once" ||
    report fail "GP-read-cleanup every pending read exit checks cleanup" "rc=$RC out=$OUT calls=$(tr '\n' ' ' <"$CALLS")"
rm -f "$CALLS"

[ ! -e "$ADB_SENTINEL" ] &&
    report ok "GZ poison adb shim was never invoked" ||
    report fail "GZ host-only selftest must never cross the device seam" "calls=$(cat "$ADB_SENTINEL")"

printf 'test-hook Vector prefs selftest: %d passed, %d failed\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
