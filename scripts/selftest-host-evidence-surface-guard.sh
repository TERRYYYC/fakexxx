#!/usr/bin/env bash
# Host-only negative matrix for host-evidence-surface-guard.py.
#
# This test deliberately gives the guard no device transport. PATH starts with
# a poison `adb`; touching it leaves a sentinel and fails the suite. All UI
# evidence is an existing XML fixture and every code-search scope is a local
# fixture under a temporary repository root.

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GUARD="$HERE/host-evidence-surface-guard.py"
PYTHON_BIN="$(command -v python3 || true)"

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

if [ -z "$PYTHON_BIN" ]; then
  echo "python3 is required" >&2
  exit 1
fi
if [ ! -f "$GUARD" ]; then
  echo "selftest target missing: $GUARD" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/poison-bin"
cat >"$WORK/poison-bin/adb" <<'EOF'
#!/usr/bin/env bash
: >"$ADB_SENTINEL"
echo "host evidence guard must never invoke adb" >&2
exit 97
EOF
chmod +x "$WORK/poison-bin/adb"
ADB_SENTINEL="$WORK/adb-was-called"

run_guard() {
  local name="$1"
  shift
  local out_file="$WORK/$name.stdout"
  local err_file="$WORK/$name.stderr"
  env PATH="$WORK/poison-bin:$PATH" ADB_SENTINEL="$ADB_SENTINEL" \
    "$PYTHON_BIN" "$GUARD" "$@" >"$out_file" 2>"$err_file"
  RC=$?
  OUT="$(<"$out_file")"
  ERR="$(<"$err_file")"
}

assert_json() {
  local label="$1" manifest="$2" expression="$3"
  if "$PYTHON_BIN" - "$manifest" "$expression" <<'PY'
import json
import sys

path, expression = sys.argv[1:]
with open(path, "r", encoding="utf-8") as handle:
    data = json.load(handle)
if not eval(expression, {"__builtins__": {}}, {"m": data, "all": all, "len": len}):
    raise SystemExit("assertion was false: " + expression)
PY
  then
    report ok "$label"
  else
    report fail "$label" "manifest=$manifest expression=$expression"
  fi
}

expect_no_manifest() {
  local label="$1" manifest="$2"
  if [ "$RC" -ne 0 ] && [ ! -e "$manifest" ]; then
    report ok "$label"
  else
    report fail "$label" "rc=$RC manifest_exists=$([ -e "$manifest" ] && echo yes || echo no) out=$OUT err=$ERR"
  fi
}

PKG="name.caiyao.fakegps.bench"
TARGET_ID="$PKG:id/resume_controls"

cat >"$WORK/ui-valid.xml" <<EOF
<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="$PKG" bounds="[0,0][1080,2400]">
    <node index="0" text="Resume" resource-id="$TARGET_ID" class="android.widget.Button" package="$PKG" bounds="[40,1800][1040,1980]" />
  </node>
</hierarchy>
EOF

# U1: a fully bound portrait dump produces a persistent manifest with the
# exact input digest and the selector match count.
U1="$WORK/ui-valid.manifest.json"
run_guard u1 ui \
  --xml "$WORK/ui-valid.xml" \
  --expected-package "$PKG" \
  --expected-orientation portrait \
  --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U1"
if [ "$RC" -eq 0 ] && [ -s "$U1" ]; then
  report ok "U1 valid UI dump publishes a manifest"
else
  report fail "U1 valid UI dump publishes a manifest" "rc=$RC out=$OUT err=$ERR"
fi
assert_json "U1 manifest binds package, orientation, bounds and target" "$U1" \
  "m['status'] == 'passed' and m['input']['sha256'] and m['assertions']['expectedPackage'] == '$PKG' and m['assertions']['derivedOrientation'] == 'portrait' and m['assertions']['rootBounds']['width'] == 1080 and m['assertions']['rootBounds']['height'] == 2400 and m['assertions']['targetScreenSelector']['matchCount'] == 1"

EXPECTED_SHA="$($PYTHON_BIN - "$WORK/ui-valid.xml" <<'PY'
import hashlib
import sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
)"
assert_json "U1 manifest carries the exact input SHA-256" "$U1" \
  "m['input']['sha256'] == '$EXPECTED_SHA'"

cat >"$WORK/ui-landscape.xml" <<EOF
<hierarchy rotation="1">
  <node index="0" package="$PKG" bounds="[0,0][2400,1080]">
    <node index="0" resource-id="$TARGET_ID" package="$PKG" bounds="[1800,40][2300,1040]" />
  </node>
</hierarchy>
EOF
U1L="$WORK/ui-landscape.manifest.json"
run_guard u1l ui --xml "$WORK/ui-landscape.xml" --expected-package "$PKG" \
  --expected-orientation landscape --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U1L"
if [ "$RC" -eq 0 ] && [ -s "$U1L" ]; then
  report ok "U1L valid landscape bounds derive landscape"
else
  report fail "U1L valid landscape bounds derive landscape" "rc=$RC out=$OUT err=$ERR"
fi
assert_json "U1L manifest records landscape derived from bounds" "$U1L" \
  "m['status'] == 'passed' and m['assertions']['derivedOrientation'] == 'landscape' and m['assertions']['rootBounds']['width'] == 2400 and m['assertions']['rootBounds']['height'] == 1080"

# U2-U6: every incomplete or contradictory UI surface fails closed and leaves
# no success artifact behind.
U2="$WORK/ui-wrong-package.manifest.json"
run_guard u2 ui --xml "$WORK/ui-valid.xml" --expected-package com.example.other \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U2"
expect_no_manifest "U2 wrong package cannot support UI evidence" "$U2"

sed 's/\[0,0\]\[1080,2400\]/[0,0][0,2400]/' "$WORK/ui-valid.xml" >"$WORK/ui-bad-bounds.xml"
U3="$WORK/ui-bad-bounds.manifest.json"
run_guard u3 ui --xml "$WORK/ui-bad-bounds.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U3"
expect_no_manifest "U3 invalid root bounds cannot support UI evidence" "$U3"

U4="$WORK/ui-wrong-orientation.manifest.json"
run_guard u4 ui --xml "$WORK/ui-valid.xml" --expected-package "$PKG" \
  --expected-orientation landscape --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U4"
expect_no_manifest "U4 orientation mismatch cannot support UI evidence" "$U4"

U5="$WORK/ui-missing-target.manifest.json"
run_guard u5 ui --xml "$WORK/ui-valid.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$PKG:id/not_here" \
  --output-manifest "$U5"
expect_no_manifest "U5 missing target selector cannot support UI evidence" "$U5"

sed "/<\/node>/i\\
    <node index=\"1\" text=\"Resume duplicate\" resource-id=\"$TARGET_ID\" class=\"android.widget.Button\" package=\"$PKG\" bounds=\"[40,2000][1040,2180]\" />" \
  "$WORK/ui-valid.xml" >"$WORK/ui-ambiguous-target.xml"
U6="$WORK/ui-ambiguous-target.manifest.json"
run_guard u6 ui --xml "$WORK/ui-ambiguous-target.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U6"
expect_no_manifest "U6 ambiguous target selector cannot support UI evidence" "$U6"

# U7: a previous artifact is never overwritten, so retries cannot silently
# relabel old bytes as the current run.
U7="$WORK/ui-existing.manifest.json"
printf 'historical-manifest-must-survive\n' >"$U7"
run_guard u7 ui --xml "$WORK/ui-valid.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U7"
if [ "$RC" -ne 0 ] && [ "$(<"$U7")" = "historical-manifest-must-survive" ]; then
  report ok "U7 manifest publication is create-only"
else
  report fail "U7 manifest publication is create-only" "rc=$RC content=$(<"$U7")"
fi

# U8: declarations are rejected across the whole bounded input, not only a
# convenient prefix. ElementTree accepts this harmless doctype, so this case
# specifically proves the guard performs the policy check itself.
{
  printf '%5000s' ''
  cat <<EOF
<!DOCTYPE hierarchy>
<hierarchy rotation="0">
  <node index="0" package="$PKG" bounds="[0,0][1080,2400]">
    <node index="0" resource-id="$TARGET_ID" package="$PKG" bounds="[40,1800][1040,1980]" />
  </node>
</hierarchy>
EOF
} >"$WORK/ui-late-doctype.xml"
U8="$WORK/ui-late-doctype.manifest.json"
run_guard u8 ui --xml "$WORK/ui-late-doctype.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U8"
expect_no_manifest "U8 declarations beyond byte 4096 are rejected" "$U8"

"$PYTHON_BIN" - "$WORK/ui-utf16-doctype.xml" "$PKG" "$TARGET_ID" <<'PY'
from pathlib import Path
import sys

path, package_name, target_id = sys.argv[1:]
xml = f'''<?xml version="1.0" encoding="utf-16"?>
<!DOCTYPE hierarchy>
<hierarchy rotation="0">
  <node index="0" package="{package_name}" bounds="[0,0][1080,2400]">
    <node index="0" resource-id="{target_id}" package="{package_name}" bounds="[40,1800][1040,1980]" />
  </node>
</hierarchy>
'''
Path(path).write_bytes(xml.encode("utf-16"))
PY
U9="$WORK/ui-utf16-doctype.manifest.json"
run_guard u9 ui --xml "$WORK/ui-utf16-doctype.xml" --expected-package "$PKG" \
  --expected-orientation portrait --target-selector "resource-id=$TARGET_ID" \
  --output-manifest "$U9"
expect_no_manifest "U9 non-UTF-8 UI XML cannot bypass declaration rejection" "$U9"

# P1: if durable publication fails after the atomic link, the linked success
# artifact must be retracted before the caller receives a non-zero result.
P1="$WORK/post-link-fsync-failure.manifest.json"
if "$PYTHON_BIN" - "$GUARD" "$P1" <<'PY'
import importlib.util
from pathlib import Path
import sys

guard_path, output_path = sys.argv[1:]
spec = importlib.util.spec_from_file_location("host_evidence_surface_guard", guard_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

real_fsync = module.os.fsync
calls = 0

def fail_second_fsync(fd):
    global calls
    calls += 1
    if calls == 2:
        raise OSError("injected directory fsync failure")
    return real_fsync(fd)

module.os.fsync = fail_second_fsync
caught = False
try:
    module.publish_create_only(Path(output_path), b'{"status":"passed"}\n')
except (module.GuardError, OSError):
    caught = True
finally:
    module.os.fsync = real_fsync

if not caught:
    raise SystemExit("injected publication failure was not reported")
if Path(output_path).exists():
    raise SystemExit("failed publication left a visible success manifest")
PY
then
  report ok "P1 failed post-link durability check leaves no success manifest"
else
  report fail "P1 failed post-link durability check leaves no success manifest" "linked artifact survived failure"
fi

mkdir -p "$WORK/repo/src"
cat >"$WORK/repo/src/Main.kt" <<'EOF'
package fixture
class Main {
    fun persistSchedule() = Unit
}
EOF
cat >"$WORK/repo/src/Store.kt" <<'EOF'
package fixture
const val CURRENT_PROFILE = "profile-1"
EOF

# C1: two distinct route kinds and patterns over an explicit, non-empty scope
# may support an absence statement only when both counts are zero.
C1="$WORK/code-clean.manifest.json"
run_guard c1 code-search --repo-root "$WORK/repo" --scope src \
  --route 'call-site::advancePointer\s*\(' \
  --route 'storage-key::SCHEDULE_BOUNDARY' \
  --output-manifest "$C1"
if [ "$RC" -eq 0 ] && [ -s "$C1" ]; then
  report ok "C1 two clean routes support scoped absence"
else
  report fail "C1 two clean routes support scoped absence" "rc=$RC out=$OUT err=$ERR"
fi
assert_json "C1 manifest records scope, patterns and zero hit counts" "$C1" \
  "m['status'] == 'passed' and m['absenceSupported'] is True and m['orthogonalityBasis'] == 'distinct-enumerated-route-kinds-and-patterns' and m['filesScanned'] == 2 and len(m['routes']) == 2 and all(r['hitCount'] == 0 and r['kindMeaning'] for r in m['routes']) and len(m['sourceSnapshotSha256']) == 64"

# C2-C4: one route, duplicate kinds, or duplicate patterns are not orthogonal
# and therefore cannot publish an absence assertion.
C2="$WORK/code-one-route.manifest.json"
run_guard c2 code-search --repo-root "$WORK/repo" --scope src \
  --route 'call-site::advancePointer' --output-manifest "$C2"
expect_no_manifest "C2 one route cannot support absence" "$C2"

C3="$WORK/code-same-kind.manifest.json"
run_guard c3 code-search --repo-root "$WORK/repo" --scope src \
  --route 'call-site::advancePointer' --route 'call-site::SCHEDULE_BOUNDARY' \
  --output-manifest "$C3"
expect_no_manifest "C3 duplicate route kinds cannot support absence" "$C3"

C4="$WORK/code-same-pattern.manifest.json"
run_guard c4 code-search --repo-root "$WORK/repo" --scope src \
  --route 'call-site::advancePointer' --route 'storage-key::advancePointer' \
  --output-manifest "$C4"
expect_no_manifest "C4 duplicate patterns cannot support absence" "$C4"

# C5: a hit produces a durable negative result (with counts) and exits
# non-zero; the manifest exists, but explicitly refuses the absence claim.
printf '\nfun advancePointer() = Unit\n' >>"$WORK/repo/src/Store.kt"
C5="$WORK/code-hit.manifest.json"
run_guard c5 code-search --repo-root "$WORK/repo" --scope src \
  --route 'call-site::advancePointer' --route 'storage-key::SCHEDULE_BOUNDARY' \
  --output-manifest "$C5"
if [ "$RC" -ne 0 ] && [ -s "$C5" ]; then
  report ok "C5 any route hit rejects absence and records the result"
else
  report fail "C5 any route hit rejects absence and records the result" "rc=$RC out=$OUT err=$ERR"
fi
assert_json "C5 hit manifest cannot claim absence" "$C5" \
  "m['status'] == 'failed' and m['absenceSupported'] is False and m['routes'][0]['hitCount'] > 0 and m['routes'][1]['hitCount'] == 0"

# C6-C7: missing and empty scopes are not searchable evidence surfaces.
C6="$WORK/code-missing-scope.manifest.json"
run_guard c6 code-search --repo-root "$WORK/repo" --scope missing \
  --route 'call-site::advancePointer' --route 'storage-key::SCHEDULE_BOUNDARY' \
  --output-manifest "$C6"
expect_no_manifest "C6 missing scope cannot support absence" "$C6"

mkdir -p "$WORK/repo/empty"
C7="$WORK/code-empty-scope.manifest.json"
run_guard c7 code-search --repo-root "$WORK/repo" --scope empty \
  --route 'call-site::advancePointer' --route 'storage-key::SCHEDULE_BOUNDARY' \
  --output-manifest "$C7"
expect_no_manifest "C7 empty scope cannot support absence" "$C7"

# C8: resolving a scope before checking its identity would silently accept a
# symlink alias. Evidence scope names must bind to real in-repo path entries.
ln -s src "$WORK/repo/src-link"
C8="$WORK/code-symlink-scope.manifest.json"
run_guard c8 code-search --repo-root "$WORK/repo" --scope src-link \
  --route 'call-site::NO_SUCH_CALL' --route 'storage-key::NO_SUCH_KEY' \
  --output-manifest "$C8"
expect_no_manifest "C8 symlink scope cannot support absence" "$C8"

# C9: route kinds are an enumerated semantic contract, not arbitrary labels
# that can make two equivalent greps look independent.
C9="$WORK/code-unknown-route-kind.manifest.json"
run_guard c9 code-search --repo-root "$WORK/repo" --scope src \
  --route 'invented-route::NO_SUCH_CALL' --route 'storage-key::NO_SUCH_KEY' \
  --output-manifest "$C9"
expect_no_manifest "C9 unknown route kind cannot support absence" "$C9"

# C10: directory enumeration errors must be surfaced. Python's os.walk drops
# them silently unless an onerror callback is supplied, which could hide the
# only file containing a hit and turn it into false absence evidence.
if "$PYTHON_BIN" - "$GUARD" "$WORK/repo/src" "$WORK/repo" <<'PY'
import importlib.util
from pathlib import Path
import sys

guard_path, scope_path, root_path = sys.argv[1:]
spec = importlib.util.spec_from_file_location("host_evidence_surface_guard", guard_path)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

real_walk = module.os.walk

def injected_walk(scope, *, followlinks=False, onerror=None):
    if onerror is not None:
        onerror(PermissionError("injected scandir failure"))
    return iter([(str(scope), [], ["Main.kt"])])

module.os.walk = injected_walk
caught = None
try:
    module.collect_regular_files(Path(scope_path), Path(root_path).resolve())
except module.GuardError as error:
    caught = str(error)
finally:
    module.os.walk = real_walk

if caught is None or "failed to enumerate" not in caught:
    raise SystemExit("directory enumeration failure was not surfaced: " + repr(caught))
PY
then
  report ok "C10 directory enumeration errors fail closed"
else
  report fail "C10 directory enumeration errors fail closed" "os.walk error was ignored"
fi

# C11: undecodable/NUL-interleaved sources cannot be counted as searched.
# UTF-16 text is valid source content but must be rejected under this guard's
# explicit UTF-8 contract instead of silently turning a real hit into zero.
mkdir -p "$WORK/repo-utf16/src"
"$PYTHON_BIN" - "$WORK/repo-utf16/src/Hidden.kt" <<'PY'
from pathlib import Path
import sys

Path(sys.argv[1]).write_bytes("fun advancePointer() = Unit\n".encode("utf-16"))
PY
C11="$WORK/code-utf16-source.manifest.json"
run_guard c11 code-search --repo-root "$WORK/repo-utf16" --scope src \
  --route 'call-site::advancePointer' --route 'storage-key::SCHEDULE_BOUNDARY' \
  --output-manifest "$C11"
expect_no_manifest "C11 non-UTF-8 source cannot support absence" "$C11"

if [ ! -e "$ADB_SENTINEL" ]; then
  report ok "host-only suite never invokes adb"
else
  report fail "host-only suite never invokes adb" "poison adb was called"
fi

echo
echo "selftest-host-evidence-surface-guard: pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
