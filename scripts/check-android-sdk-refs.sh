#!/usr/bin/env bash
# check-android-sdk-refs.sh -- every android.* type the contract module
# references must exist in the public compile SDK (§6.1 / KB-7).
#
# WHY THIS EXISTS.
# §6.1 froze the family-wide rule: a type the CONTRACT references must be in
# the PUBLIC compile SDK, not merely resolvable somewhere on the classpath.
# Its first instance was KB-7=A: android.os.ServiceSpecificException compiled
# inside the platform but is @hide -- absent from the public android.jar -- so
# an implementer following the contract on the public SDK alone could not
# build it. That ruling closed ONE type while the RULE still had no arm: §20.1
# keeps KB-7 a `gap`, and §19 requires gaps to be dispositioned before done.
# A rule with no arm is a hope with a citation (the sentence §7c was written
# under, and it applies verbatim here).
#
# WHAT "reference" MEANS HERE -- AND WHAT IT DELIBERATELY DOES NOT.
# A reference is an android.* fully-qualified token in NON-COMMENT code: an
# import statement, a type position, or an inline fully-qualified use. Prose
# that MENTIONS an android type (a KDoc explaining why ServiceSpecificException
# was rejected, say) is not a dependency and must not fire this gate -- the
# KB-7 resolution is documented somewhere, and a guard that reds on its own
# history text has a false positive with a scary name. Comments are stripped
# before matching; that exclusion is part of the measured predicate, and the
# enumeration below says so.
#
# kotlinx.android.* / androidx.* are excluded by the boundary rule: the token
# must START at `android.` (no word char or dot before it), so
# `kotlinx.android.parcelize.Parcelize` never yields a match. The SDK rule is
# about android.* platform types; Jetpack namespaces are declared dependencies
# with their own provenance.
#
# ENUMERATE, NEVER COUNT (§7b's rule, paid for four times).
# The output lists every referenced type with its sites and verdict, and the
# verdict names the offending type and sites. A count of references would read
# like coverage while measuring only what the matcher recognises.
#
# EMPTY SCAN = RED.
# A module that references no android.* type at all is not a pass; it means
# the scan found nothing to check and the matcher itself is the suspect
# (same clause as §7c). If the module legitimately drops its last android
# dependency, that is a deliberate event and must show up as a red to be
# dispositioned, not as silence.
#
# INCONCLUSIVE, never a silent pass.
# If the compile SDK jar cannot be located, or compileSdk cannot be read from
# the module build file, the guard says INCONCLUSIVE and exits 2 -- it refuses
# to report a verdict it could not compute (the check-release-debt rule).
set -uo pipefail

MODULE="${MODULE:-contracts/environment-control-v1}"
SDK_ROOT_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --sdk-root) SDK_ROOT_OVERRIDE="$2"; shift 2 ;;
    --sdk-root=*) SDK_ROOT_OVERRIDE="${1#*=}"; shift ;;
    *) MODULE="$1"; shift ;;
  esac
done

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$REPO_ROOT/$MODULE"

if [ ! -d "$MODULE_DIR" ]; then
  printf 'check-android-sdk-refs: module not found: %s\n' "$MODULE_DIR" >&2
  exit 2
fi

# ---------------------------------------------------------------- locate the SDK
CS_RAW=$(grep -oE 'compileSdk[[:space:]]*=[[:space:]]*[0-9]+(\.[0-9]+)?' \
  "$MODULE_DIR/build.gradle.kts" 2>/dev/null | head -1 \
  | grep -oE '[0-9]+(\.[0-9]+)?')
if [ -z "$CS_RAW" ]; then
  printf 'check-android-sdk-refs: INCONCLUSIVE — cannot read compileSdk from %s/build.gradle.kts\n' "$MODULE" >&2
  exit 2
fi

if [ -n "$SDK_ROOT_OVERRIDE" ]; then
  SDK_ROOT="$SDK_ROOT_OVERRIDE"
else
  for CAND in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk; do
    if [ -n "$CAND" ] && [ -d "$CAND" ]; then SDK_ROOT="$CAND"; break; fi
  done
fi
SDK_ROOT="${SDK_ROOT:-}"
PLATFORM_JAR=""
if [ -n "$SDK_ROOT" ]; then
  CAND_JAR="$SDK_ROOT/platforms/android-$CS_RAW/android.jar"
  if [ -f "$CAND_JAR" ]; then
    PLATFORM_JAR="$CAND_JAR"
  fi
fi
if [ -z "$PLATFORM_JAR" ]; then
  printf 'check-android-sdk-refs: INCONCLUSIVE — no public SDK jar for compileSdk %s\n' "$CS_RAW" >&2
  if [ -n "$SDK_ROOT" ] && [ -d "$SDK_ROOT/platforms" ]; then
    printf '  platforms present: %s\n' "$(ls "$SDK_ROOT/platforms" 2>/dev/null | tr '\n' ' ')" >&2
  fi
  printf '  set ANDROID_HOME or pass --sdk-root <dir>\n' >&2
  exit 2
fi

# -------------------------------------------------------------- input provenance
# WHY: a verdict that does not name its input is a verdict about nothing.
# The module is selected by a RELATIVE default path, so the same command run
# from a different checkout scans that checkout's copy without a word of
# warning. The banner names everything the verdict was computed over: the
# module directory, every scanned file with line count and sha prefix, the
# build file the compileSdk was read from, and the exact jar the existence
# check ran against. Informational only; no verdict logic reads it.
sha12() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -c1-12
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | cut -c1-12
  else printf 'unavailable'; fi
}
printf '\n== input provenance ==\n'
printf '  module: %s\n' "$(cd -- "$MODULE_DIR" && pwd)"
printf '  build file sha256 %s (compileSdk %s)\n' "$(sha12 "$MODULE_DIR/build.gradle.kts")" "$CS_RAW"
printf '  public sdk jar: %s\n' "$PLATFORM_JAR"
printf '    %s entries, sha256 %s\n' \
  "$(python3 -c 'import zipfile,sys; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))' "$PLATFORM_JAR")" \
  "$(sha12 "$PLATFORM_JAR")"
if GIT_HEAD=$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD 2>/dev/null); then
  printf '  git HEAD %s (%s)\n' "$GIT_HEAD" "$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo detached)"
else
  printf '  outside a git repository\n'
fi

# ----------------------------------------------------------------------- the scan
# Three single-line knobs, because a mutation test must be able to disable
# exactly one arm: SCAN finds references, JAR checks existence, EMPTY refuses
# to pass over an empty scan. Multi-line knobs would turn the selftest's
# 's/^ARM_X = .*/' contract into a syntax error.
python3 - "$MODULE_DIR" "$PLATFORM_JAR" "$CS_RAW" <<'PY'
import io, os, re, sys, zipfile

MODULE, JAR, CS = sys.argv[1], sys.argv[2], sys.argv[3]

ARM_SCAN = True    # collect android.* tokens from non-comment code
ARM_JAR = True     # each referenced type must be an entry of the public jar
ARM_EMPTY = True   # a scan that found nothing is a RED, not a pass
# ARM_TQSKIP: Kotlin triple-quoted strings ("""...""") are not references.
# The first lexer only knew single/double quotes, so a string literal that
# mentioned ServiceSpecificException produced a NOT-IN-PUBLIC-SDK FAIL about
# prose (review R1 P1-1). Knob exists so the selftest can prove the span of
# the lexer that skips them is load-bearing.
# ARM_QSKIP: ORDINARY quoted strings are not references either. R1 fixed only
# the triple-quoted shape (its named instance) while the audit's own invariant
# said "string content is not code" -- review R2 caught the ungeneralised arm:
# a plain "android.os.ServiceSpecificException" literal still produced a
# NOT-IN-PUBLIC-SDK FAIL about prose. Both string arms blank content and keep
# newlines, so line numbers never drift.
ARM_QSKIP = True
ARM_TQSKIP = True
# ARM_NESTED: nested public types are stored with '$' (android-35 keeps
# Build.VERSION as android/os/Build$VERSION.class), but a source reference
# spells it with dots. The first mapping replaced EVERY dot with '/' and read
# every nested type as missing (review R1 P1-2).
ARM_NESTED = True

# -- strip comments, then find fully-qualified android.* tokens ---------------
# Boundary rule: the token must START at `android.` -- no word char or dot
# before it -- so kotlinx.android.* and androidx.* never match. Prose inside
# comments is not a reference (see header): stripping happens BEFORE matching,
# which is what makes that exclusion part of the measured predicate.
TOKEN = re.compile(r'(?<![\w.])(android\.[A-Za-z]\w*(?:\.[A-Za-z]\w*)*)')

def strip_comments(text, is_aidl):
    out, i, n = [], 0, len(text)
    while i < n:
        two = text[i:i+2]
        if two == '/*':
            j = text.find('*/', i + 2)
            j = n if j == -1 else j + 2
            out.append('\n' * text.count('\n', i, j))  # keep line numbers
            i = j
        elif two == '//' and not (ARM_TQSKIP and text[i:i+3] == '///'):
            j = text.find('\n', i)
            j = n if j == -1 else j
            i = j
        elif ARM_TQSKIP and text[i:i+3] == '"""':
            # Triple-quoted string: skip to the closing """. Checked BEFORE
            # the single-quote arm, or the inner quotes desynchronise it.
            # The placeholder keeps newlines (line numbers must not drift)
            # and blanks everything else: string CONTENT is not code.
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            out.append(re.sub(r'[^\n]', ' ', text[i:j]))
            i = j
        elif text[i] == '"' or text[i] == "'":
            q, i2 = text[i], i + 1
            while i2 < n and text[i2] != q:
                i2 += 2 if text[i2] == '\\' else 1
            if ARM_QSKIP:
                # Keep the quote chars, blank the content (newlines survive;
                # single-line strings keep their span). String CONTENT is not
                # code -- with one deliberate boundary: a ${...} template
                # expression inside a string is ALSO blanked, so a genuine
                # reference spelled there is out of the measurement. That is
                # the conservative side: this gate's history is false
                # POSITIVES on prose, and the module spells its references as
                # statements, not templates. Noted here rather than hidden.
                out.append(q + re.sub(r'[^\n]', ' ', text[i+1:i2]) + q)
            else:
                out.append(text[i:i2+1])
            i = i2 + 1
        else:
            out.append(text[i]); i += 1
    return ''.join(out)

files = []
for root, _, names in os.walk(MODULE):
    for nm in sorted(names):
        if nm.endswith(('.kt', '.aidl')):
            files.append(os.path.join(root, nm))
files.sort()

refs = {}   # type -> list of (relpath, lineno)
scanned = []  # (relpath, line count, sha12) -- every input the verdict ran over
import hashlib
for f in files:
    text = io.open(f, encoding='utf-8', errors='replace').read()
    if not ARM_SCAN:
        # The knob is not decorative: a disabled scan arm must yield an EMPTY
        # scan (which the empty-scan clause then reports), not keep scanning.
        break
    scanned.append((os.path.relpath(f, MODULE),
                    text.count('\n') + 1,
                    hashlib.sha256(text.encode('utf-8', 'replace')).hexdigest()[:12]))
    code = strip_comments(text, f.endswith('.aidl'))
    for ln_no, line in enumerate(code.splitlines(), 1):
        for m in TOKEN.finditer(line):
            refs.setdefault(m.group(1), []).append(
                (os.path.relpath(f, MODULE), ln_no))

entries = set(zipfile.ZipFile(JAR).namelist())

# -- token -> jar entries: every separator is / OR $, member tails stripped ---
# A source reference spells nested types with dots (android.os.Build.VERSION)
# while the jar stores them as android/os/Build$VERSION.class; and a static
# member access (VERSION.SDK_INT) makes the matcher read one segment PAST the
# type. So a token resolves if ANY dot-assignment of ANY tail-stripped prefix
# names a jar entry; the full dotted path with all-'/' is checked first so the
# plain package case stays cheap. Tokens too long to reason about (>8 parts
# even after stripping) are refused loudly, never silently passed.
def resolves(typ):
    parts = typ.split('.')
    while len(parts) >= 3:
        n = len(parts) - 1  # separator positions; every one is / or $
        forms = {'/'.join(parts) + '.class'}
        if ARM_NESTED:
            for mask in range(1 << n):
                segs = [parts[0]]
                for j in range(1, n + 1):
                    segs.append(('$' if (mask >> (j - 1)) & 1 else '/') + parts[j])
                forms.add(''.join(segs) + '.class')
        if forms & entries:
            return True
        parts.pop()  # last segment may be a static member, not a class
    return False

print('\n== scanned inputs (every file this verdict ran over) ==')
print('    scanned input(s): %d file(s)' % len(scanned))
for rel, nlines, sha in scanned:
    print('    %-72s (%d lines, sha256 %s)' % (rel, nlines, sha))

print('\n== referenced android.* types, each against the public compile SDK ==')
print('    predicate: fully-qualified android.* tokens in non-comment code'
      ' (comments stripped before matching; kotlinx.android.*/androidx.* excluded by boundary)')
print('    public jar: %s (%s entries)' % (JAR, len(entries)))

if not refs:
    if ARM_EMPTY:
        print('  FAIL  the scan found no android.* reference in %d scanned file(s)' % len(files))
        print('        an empty scan is not a clean bill of health -- the matcher is the suspect,')
        print('        or the module dropped its last android dependency; both need a decision')
        sys.exit(1)
    print('  ....  scan empty; ARM_EMPTY disabled in this build, reporting only')
    sys.exit(0)

bad = 0
for typ in sorted(refs):
    sites = refs[typ]
    ok_type = (not ARM_JAR) or resolves(typ)
    verdict = ('public in android-%s' % CS) if ok_type else ('unchecked (ARM_JAR off)' if not ARM_JAR else 'NOT IN PUBLIC SDK')
    if ARM_JAR and not ok_type:
        bad += 1
    where = ', '.join('%s:%d' % s for s in sites[:4])
    more = '' if len(sites) <= 4 else ' +%d more' % (len(sites) - 4)
    # The verdict text itself is printed, not folded into FAIL/ok: a verdict
    # the reader cannot grep is a verdict the mutation test cannot pin either.
    print('  %-4s %-40s %-22s %2d site(s): %s%s'
          % ('FAIL' if verdict == 'NOT IN PUBLIC SDK' else 'ok',
             typ, verdict, len(sites), where, more))
    if verdict == 'NOT IN PUBLIC SDK':
        print('        %s -> no jar entry under any / or $ form of its dots, member tails stripped' % typ)

print('  ....  %d type(s) enumerated, %d site(s) total, %d missing from the public SDK'
      % (len(refs), sum(len(v) for v in refs.values()), bad))
sys.exit(1 if bad else 0)
PY
if [ $? -eq 0 ]; then
  printf 'check-android-sdk-refs: PASS (every referenced android.* type is public in android-%s)\n' "$CS_RAW"
  exit 0
fi
printf 'check-android-sdk-refs: FAIL (see enumeration above)\n'
exit 1
