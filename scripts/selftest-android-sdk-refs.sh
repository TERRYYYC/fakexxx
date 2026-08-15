#!/usr/bin/env bash
#
# selftest-android-sdk-refs.sh — regression gate for check-android-sdk-refs.sh.
#
# WHY THIS EXISTS.
# The §6.1 rule "every android.* type the contract references exists in the
# public compile SDK" shipped as prose through the entire KB-7=A round: the
# ruling closed ServiceSpecificException while nothing could ever fire again.
# This suite is the guard's other half, and its cases are the guard's history:
#
#   N-A replays KB-7 exactly -- a ServiceSpecificException import against a
#       jar that does not contain it (a fixture jar, because zip-entry
#       enumeration is the whole check and needs no real bytecode).
#   N-B pins the empty-scan-is-red clause: a module with no android.* token
#       must never read as a pass.
#   N-C pins the fully-qualified arm: a type used inline WITHOUT an import is
#       still a reference (an imports-only matcher would read green here).
#   N-D pins the false-positive boundary the other way: PROSE that mentions an
#       android type inside a comment is not a reference and must stay green,
#       or the guard reds on its own history text.
#   M-* disable exactly one single-line arm and require that arm's finding to
#       disappear -- load-bearing is measured, not argued. A disabled scan arm
#       re-reds through the empty-scan clause; the assertion is that the
#       ORIGINAL finding vanishes, not that the gate turns green (the M-4
#       rule from selftest-contract-v1.sh).
#
# The fixture jar carries ONLY android/os/Parcel.class and
# android/os/Parcelable.class, so every "exists" verdict is measured against a
# known universe -- no dependence on whatever SDK happens to be installed.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROD="$REPO_ROOT/scripts/check-android-sdk-refs.sh"
MODULE="contracts/environment-control-v1"

POS=0; NEG=0; MUT=0; FAILURES=0

ok()  { printf '  PASS  %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
detail() { printf '%s\n' "$1" | grep -E 'FAIL|INCONCLUSIVE|check-android-sdk-refs:' | sed 's/^/          /'; }

command -v python3 >/dev/null 2>&1 || { printf 'selftest-android-sdk-refs: python3 required\n' >&2; exit 1; }

# ---- fixture SDK root: a jar whose entry list is the entire universe --------
FIXTURE_SDK="$(mktemp -d)"
mkdir -p "$FIXTURE_SDK/platforms/android-35"
python3 - "$FIXTURE_SDK/platforms/android-35/android.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    # Bytecode is irrelevant: the guard enumerates entries. Public types for
    # the nested-class shape ($ separator, how android-35 actually stores
    # Build.VERSION) plus the two baseline parcels.
    z.writestr('android/os/Parcel.class', b'')
    z.writestr('android/os/Parcelable.class', b'')
    z.writestr('android/os/Build$VERSION.class', b'')
PY

mk() { # throwaway repo copy: guard + module, from the WORKING TREE
  local d; d="$(mktemp -d)"
  mkdir -p "$d/scripts" "$d/$(dirname "$MODULE")"
  cp "$PROD" "$d/scripts/check-android-sdk-refs.sh"
  chmod +x "$d/scripts/check-android-sdk-refs.sh"
  cp -R "$REPO_ROOT/$MODULE" "$d/$(dirname "$MODULE")/"
  printf '%s\n' "$d"
}

apply() { # $1=dir $2=relpath $3=old $4=new -- exact-count-1 replacement
  F="$1/$2" OLD="$3" NEW="$4" python3 - <<'PY'
# -*- coding: utf-8 -*-
import io, os, sys
p = os.environ["F"]
t = io.open(p, encoding="utf-8").read()
old, new = os.environ["OLD"], os.environ["NEW"]
n = t.count(old)
if n != 1:
    sys.stderr.write("anchor matched %d time(s), expected exactly 1\n" % n)
    sys.exit(1)
io.open(p, "w", encoding="utf-8").write(t.replace(old, new))
PY
}

KT="$MODULE/src/main/java/io/github/terryyyc/fakexxx/contract/v1"
run_gate() { ( cd "$1" && ./scripts/check-android-sdk-refs.sh --sdk-root "$FIXTURE_SDK" 2>&1 ); }

# ---------------------------------------------------------------------------
printf '\n== positive ==\n'

D="$(mk)"
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "P-1 pristine module: Parcel + Parcelable both public in the fixture jar"
  POS=$((POS + 1))
else
  bad "P-1 pristine module is NOT green; every negative below is unreliable"
  detail "$OUT"
fi
rm -rf "$D"

D="$(mk)"
EMPTY_ROOT="$(mktemp -d)"
OUT="$( cd "$D" && ./scripts/check-android-sdk-refs.sh --sdk-root "$EMPTY_ROOT" 2>&1 )"; RC=$?
rm -rf "$EMPTY_ROOT"
if [ "$RC" -eq 2 ] && printf '%s' "$OUT" | grep -qF 'INCONCLUSIVE'; then
  ok "P-2 unresolvable SDK is INCONCLUSIVE (exit 2), never a silent pass"
  POS=$((POS + 1))
else
  bad "P-2 a missing SDK jar must exit 2 INCONCLUSIVE, got rc=$RC"
  detail "$OUT"
fi
rm -rf "$D"

# ---------------------------------------------------------------------------
printf '\n== negative (a planted drift; the guard must name it) ==\n'

neg() { # $1=label $2=relpath $3=old $4=new $5=expected finding substring
  local d out
  d="$(mk)"
  if ! apply "$d" "$2" "$3" "$4" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if ! printf '%s' "$out" | grep -qF -- "$5"; then
    bad "$1 - guard never reported the planted finding: '$5'"
    detail "$out"
  else
    ok "$1"
    NEG=$((NEG + 1))
  fi
  rm -rf "$d"
}

# N-A is the KB-7 replay: the exact type that ruling was written for, as an
# import, against a jar that does not carry it.
neg "N-A KB-7 replay: ServiceSpecificException import vs public jar" \
  "$KT/ReleaseRequestV1.kt" \
  'import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'import android.os.ServiceSpecificException
import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'ServiceSpecificException'

# N-C: an inline fully-qualified use with NO import. An imports-only matcher
# reads green here -- that is precisely the arm this case exists to pin.
neg "N-C fully-qualified inline use (no import line)" \
  "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val kb7Probe: Any get() = android.os.ServiceSpecificException(1, "kb7")' \
  'ServiceSpecificException'

# N-B: the empty-scan clause. Strips EVERY android.* import in the module
# (a broad mutation, but a throwaway copy); type positions keep compiling
# textually, the scan must find nothing and REFUSE to pass over nothing.
D="$(mk)"
python3 - "$D/$MODULE" <<'PY'
import io, os, sys
root = sys.argv[1]
n = 0
for dirpath, _, names in os.walk(root):
    for nm in names:
        if not nm.endswith('.kt'):
            continue
        p = os.path.join(dirpath, nm)
        lines = io.open(p, encoding='utf-8').read().splitlines(True)
        kept = [l for l in lines if not l.startswith('import android.')]
        n += len(lines) - len(kept)
        io.open(p, 'w', encoding='utf-8').write(''.join(kept))
print('stripped %d import line(s)' % n)
PY
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'found no android.* reference'; then
  ok "N-B empty scan is RED, not a pass"
  NEG=$((NEG + 1))
else
  bad "N-B an empty scan must refuse to pass"
  detail "$OUT"
fi
rm -rf "$D"

# N-D pins the false-positive boundary: prose that MENTIONS an android type
# (a KB-7-style history note) is not a reference, and android.os.Binder is
# deliberately absent from the fixture jar so a comment leak would fire here.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

/* History note (prose, not a reference): android.os.Binder is deliberately
   NOT referenced by this module. */' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "N-D prose mention inside a comment stays green (comments are not references)"
  NEG=$((NEG + 1))
else
  bad "N-D a prose mention must not fire the gate -- comment stripping is broken"
  detail "$OUT"
fi
rm -rf "$D"

# N-E (review R1 P1-1): a Kotlin TRIPLE-QUOTED string is not a type reference
# either. The first lexer only knew single/double quotes, so
# """android.os.ServiceSpecificException""" leaked straight through to the
# token matcher and produced a NOT-IN-PUBLIC-SDK FAIL about a string literal.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal const val KB7_NOTE = """android.os.ServiceSpecificException"""' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "N-E a triple-quoted string mentioning the type stays green (strings are not references)"
  NEG=$((NEG + 1))
else
  bad "N-E a triple-quoted string leaked into the token matcher -- lexer must skip \"\"\"...\"\"\" spans"
  detail "$OUT"
fi
rm -rf "$D"

# N-F (review R1 P1-2): a nested public type must resolve through the $ jar
# convention. android-35 stores Build.VERSION as android/os/Build$VERSION.class;
# the first mapping replaced every dot with '/' and read the type as missing.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val kb7BuildProbe: Any get() = android.os.Build.VERSION' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS' \
  && printf '%s' "$OUT" | grep -qF 'android.os.Build.VERSION'; then
  ok "N-F nested type (Build.VERSION, jar entry Build\$VERSION) resolves public"
  NEG=$((NEG + 1))
else
  bad "N-F a nested class resolved as missing -- entry mapping must try the \$ form"
  detail "$OUT"
fi
rm -rf "$D"

# N-G (review R1 P1-2, member-path boundary): a static MEMBER access off a
# nested type. The token matcher reads the whole qualified chain
# (Build.VERSION.SDK_INT); SDK_INT is a field, not a class, so the resolver
# must strip the member tail and check the TYPE it hangs off.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val kb7SdkIntProbe: Int get() = android.os.Build.VERSION.SDK_INT' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "N-G static member path (VERSION.SDK_INT) checks the type, not the field"
  NEG=$((NEG + 1))
else
  bad "N-G a member access off a nested type was read as a missing class"
  detail "$OUT"
fi
rm -rf "$D"

# N-I (review R2 P1-4): a NORMAL single/double-quoted string is not a type
# reference either. The R1 fix blanked only triple-quoted spans; the ordinary
# quote branch appended its content verbatim, so a string literal mentioning
# the type still produced a NOT-IN-PUBLIC-SDK FAIL about prose.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal const val NORMAL_STRING = "android.os.ServiceSpecificException"' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "N-I a normal string mentioning the type stays green (string content is not code)"
  NEG=$((NEG + 1))
else
  bad "N-I a normal string leaked into the token matcher -- the R1 fix only blanked triple quotes"
  detail "$OUT"
fi
rm -rf "$D"

# N-J (review R3 P1-5): a ${...} template expression is REAL CODE. A class
# literal inside a string template is a genuine type reference, and blanking
# it wholesale (the R2 "conservative side") turned the guard's false positive
# into a false NEGATIVE -- worse for a gate whose §6.1 mandate is every
# reference.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val TEMPLATE = "${android.os.ServiceSpecificException::class.java.name}"' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -q 'ServiceSpecificException.*NOT IN PUBLIC SDK'; then
  ok "N-J a type reference inside a \${...} template is caught (template code is code)"
  NEG=$((NEG + 1))
else
  bad "N-J the template class literal was blanked with the string -- false green over a real reference"
  detail "$OUT"
fi
rm -rf "$D"

# N-K pins the RECURSION: a template containing a nested string which itself
# contains a template. Simple brace-counting breaks on this shape; the lexer
# must handle strings-in-templates-in-strings. android.os.Binder is absent
# from the fixture jar, so the reference must fire.
D="$(mk)"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val NESTED = "pre${"inner${android.os.Binder}post"}suffix"' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -q 'android.os.Binder.*NOT IN PUBLIC SDK'; then
  ok "N-K a reference in a nested template (string-in-template-in-string) is caught"
  NEG=$((NEG + 1))
else
  bad "N-K nested template broke the lexer -- the reference vanished from measurement"
  detail "$OUT"
fi
rm -rf "$D"

# N-H (review R1 P1-3): the provenance banner must carry EVERY scanned input
# (relpath, line count, sha prefix), or a stale/mutated copy among many files
# cannot be told apart -- the exact diagnostic this lane's banner exists for.
D="$(mk)"
OUT="$(run_gate "$D")"
HIT="$(printf '%s' "$OUT" | grep -cE 'src/main/java/.*ContractEnumsV1\.kt +\([0-9]+ lines, sha256 [0-9a-f]{12}\)')"
if [ "$HIT" -ge 1 ] \
  && printf '%s' "$OUT" | grep -qE 'scanned input\(s\): [0-9]+ file'; then
  ok "N-H the banner names each scanned file with lines + sha (per-input provenance)"
  NEG=$((NEG + 1))
else
  bad "N-H banner lacks per-file provenance -- a verdict that does not name every input is about nothing"
  detail "$OUT"
fi
rm -rf "$D"

# ---------------------------------------------------------------------------
printf '\n== mutation (disable one single-line arm; its finding must vanish) ==\n'

mut() { # $1=label $2=sed expr $3=relpath $4=old $5=new $6=finding that must disappear
  local d out base
  base="$(mk)"
  if ! apply "$base" "$3" "$4" "$5" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply to the intact guard; the case never ran"
    rm -rf "$base"; return
  fi
  out="$(run_gate "$base")"
  rm -rf "$base"
  if ! printf '%s' "$out" | grep -qF -- "$6"; then
    bad "$1 - INCONCLUSIVE: the intact guard never produced '$6', so its disappearance proves nothing"
    detail "$out"
    return
  fi

  d="$(mk)"
  if ! sed -i.bak "$2" "$d/scripts/check-android-sdk-refs.sh" || \
     cmp -s "$d/scripts/check-android-sdk-refs.sh" "$d/scripts/check-android-sdk-refs.sh.bak"; then
    bad "$1 - INCONCLUSIVE: the arm-disabling edit did not change the guard"
    rm -rf "$d"; return
  fi
  rm -f "$d/scripts/check-android-sdk-refs.sh.bak"
  if ! apply "$d" "$3" "$4" "$5" 2>/dev/null; then
    bad "$1 - INCONCLUSIVE: plant did not apply; the case never ran"
    rm -rf "$d"; return
  fi
  out="$(run_gate "$d")"
  if printf '%s' "$out" | grep -qF -- "$6"; then
    bad "$1 - finding survived with the arm disabled, so that arm is not what catches it"
    detail "$out"
  else
    ok "$1 - disabling it makes the finding disappear, so the arm is load-bearing"
    MUT=$((MUT + 1))
  fi
  rm -rf "$d"
}

# M-1 with the scan arm off, the planted import is invisible -- but the gate
# stays red through the empty-scan clause; what must vanish is the ORIGINAL
# verdict specifically (the M-4 rule). The assertion anchors on the FAIL
# verdict text, not the type name: the type still appears in the enumeration
# when ARM_JAR is off, and a name-based assertion would read that as survival.
mut "M-1 scan arm catches N-A" \
  's/^ARM_SCAN = .*/ARM_SCAN = False/' \
  "$KT/ReleaseRequestV1.kt" \
  'import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'import android.os.ServiceSpecificException
import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'NOT IN PUBLIC SDK'

mut "M-2 jar-existence arm catches N-A" \
  's/^ARM_JAR = .*/ARM_JAR = False/' \
  "$KT/ReleaseRequestV1.kt" \
  'import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'import android.os.ServiceSpecificException
import android.os.Parcelable
import kotlinx.parcelize.Parcelize' \
  'NOT IN PUBLIC SDK'

# M-3 proves the empty-scan arm load-bearing. Its case (N-B) strips every
# android.* import across the module, which a single apply() cannot express,
# so it reuses the strip inline instead of the mut() helper. Preconditions
# mirror mut(): intact guard produces the finding first, then the disabled
# arm must lose it (the gate going green here is expected -- nothing else is
# wrong with a stripped-copy module).
M3_FINDING='found no android.* reference'
D="$(mk)"
python3 - "$D/$MODULE" <<'PY'
import io, os, sys
for dirpath, _, names in os.walk(sys.argv[1]):
    for nm in names:
        if nm.endswith('.kt'):
            p = os.path.join(dirpath, nm)
            lines = io.open(p, encoding='utf-8').read().splitlines(True)
            io.open(p, 'w', encoding='utf-8').write(
                ''.join(l for l in lines if not l.startswith('import android.')))
PY
OUT="$(run_gate "$D")"
rm -rf "$D"
if ! printf '%s' "$OUT" | grep -qF -- "$M3_FINDING"; then
  bad "M-3 empty-scan arm - INCONCLUSIVE: the intact guard never produced the finding, so its disappearance proves nothing"
  detail "$OUT"
else
  D="$(mk)"
  python3 - "$D/$MODULE" <<'PY'
import io, os, sys
for dirpath, _, names in os.walk(sys.argv[1]):
    for nm in names:
        if nm.endswith('.kt'):
            p = os.path.join(dirpath, nm)
            lines = io.open(p, encoding='utf-8').read().splitlines(True)
            io.open(p, 'w', encoding='utf-8').write(
                ''.join(l for l in lines if not l.startswith('import android.')))
PY
  sed -i.bak 's/^ARM_EMPTY = .*/ARM_EMPTY = False/' "$D/scripts/check-android-sdk-refs.sh"
  rm -f "$D/scripts/check-android-sdk-refs.sh.bak"
  OUT="$(run_gate "$D")"
  rm -rf "$D"
  if printf '%s' "$OUT" | grep -qF -- "$M3_FINDING"; then
    bad "M-3 empty-scan arm - finding survived with the arm disabled, so that arm is not what catches it"
    detail "$OUT"
  else
    ok "M-3 empty-scan arm catches N-B - disabling it makes the finding disappear, so the arm is load-bearing"
    MUT=$((MUT + 1))
  fi
fi

# M-4 (review R1 P1-1, reworked R2): the string-blanking invariant is carried
# JOINTLY by the two string arms. After P1-4's fix an ordinary-quote arm also
# blanks content, so disabling the triple-quote arm ALONE no longer reddens
# N-E (any quote pairing blanks the span) -- the TQ arm is now defence-in-depth
# against mis-pairing, not the sole guard. Disabling BOTH must flip N-E into a
# false red; that is the load-bearing proof for the invariant as it stands.
D="$(mk)"
sed -i.bak 's/^ARM_TQSKIP = .*/ARM_TQSKIP = False/; s/^ARM_QSKIP = .*/ARM_QSKIP = False/' \
  "$D/scripts/check-android-sdk-refs.sh"
rm -f "$D/scripts/check-android-sdk-refs.sh.bak"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal const val KB7_NOTE = """android.os.ServiceSpecificException"""' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'NOT IN PUBLIC SDK'; then
  ok "M-4 string-blanking arms jointly - disabling both turns N-E into a false red, so the invariant is load-bearing"
  MUT=$((MUT + 1))
else
  bad "M-4 with both string arms disabled nothing leaks -- one of the arms is dead code"
  detail "$OUT"
fi
rm -rf "$D"

# M-5 (review R1 P1-2 load-bearing): with nested-class ($-form) resolution
# disabled, N-F's nested type must read as missing again.
D="$(mk)"
sed -i.bak "s/^ARM_NESTED = .*/ARM_NESTED = False/" "$D/scripts/check-android-sdk-refs.sh"
rm -f "$D/scripts/check-android-sdk-refs.sh.bak"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val kb7BuildProbe: Any get() = android.os.Build.VERSION' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'NOT IN PUBLIC SDK'; then
  ok "M-5 nested-entry arm - disabling it turns N-F into a false red, so the arm is load-bearing"
  MUT=$((MUT + 1))
else
  bad "M-5 disabling ARM_NESTED changed nothing -- the \$-form arm is decorative"
  detail "$OUT"
fi
rm -rf "$D"

# M-6 (review R2 P1-4 load-bearing): with the ordinary-string blanking
# disabled, N-I's PASS must flip to a false FAIL -- the same proof shape as
# M-4, on the arm the R1 fix forgot to measure.
D="$(mk)"
sed -i.bak "s/^ARM_QSKIP = .*/ARM_QSKIP = False/" "$D/scripts/check-android-sdk-refs.sh"
rm -f "$D/scripts/check-android-sdk-refs.sh.bak"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal const val NORMAL_STRING = "android.os.ServiceSpecificException"' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'NOT IN PUBLIC SDK'; then
  ok "M-6 ordinary-string blank arm - disabling it turns N-I into a false red, so the arm is load-bearing"
  MUT=$((MUT + 1))
else
  bad "M-6 disabling ARM_QSKIP changed nothing -- the ordinary-string arm is decorative"
  detail "$OUT"
fi
rm -rf "$D"

# M-7 (review R3 P1-5 load-bearing): with template preservation disabled,
# string spans blank template code too (the R2 behaviour) and N-J's FAIL
# must vanish -- the false negative the template arm exists to prevent.
D="$(mk)"
sed -i.bak 's/^ARM_TEMPLATE = .*/ARM_TEMPLATE = False/' "$D/scripts/check-android-sdk-refs.sh"
rm -f "$D/scripts/check-android-sdk-refs.sh.bak"
apply "$D" "$KT/ContractEnumsV1.kt" \
  'package io.github.terryyyc.fakexxx.contract.v1' \
  'package io.github.terryyyc.fakexxx.contract.v1

internal val TEMPLATE = "${android.os.ServiceSpecificException::class.java.name}"' >/dev/null 2>&1
OUT="$(run_gate "$D")"
if printf '%s' "$OUT" | grep -qF 'check-android-sdk-refs: PASS'; then
  ok "M-7 template arm - disabling it restores the R2 false green, so the arm is load-bearing"
  MUT=$((MUT + 1))
else
  bad "M-7 disabling ARM_TEMPLATE did not restore the false green -- the arm is not the one keeping template code in measurement"
  detail "$OUT"
fi
rm -rf "$D"

rm -rf "$FIXTURE_SDK"

printf '\n'
if [ "$FAILURES" -eq 0 ]; then
  printf 'selftest-android-sdk-refs: PASS (%d positive, %d negative, %d mutation self-check(s) — every case ran against the production guard on a fixture SDK)\n' \
    "$POS" "$NEG" "$MUT"
  exit 0
fi
printf 'selftest-android-sdk-refs: FAIL (%d failure(s) across %d executed case(s))\n' "$FAILURES" "$((POS + NEG + MUT))"
exit 1
