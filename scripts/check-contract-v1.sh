#!/usr/bin/env bash
#
# check-contract-v1.sh — gate for the frozen Environment Control contract v1.
#
# Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §6, §13 Task 2.
#
# What this proves, section by section:
#
#   1.  No Kotlin enum type appears inside any @Parcelize class. kotlin-parcelize
#       auto-encodes enums by name, so a constant added by a newer peer makes an
#       older reader throw IllegalArgumentException from the generated
#       createFromParcel — an unparcel crash inside a Binder transaction instead
#       of the typed fail-closed outcome INV-03 requires. Prose cannot enforce
#       this; a static check can.
#   2.  compatibility.yaml and the Kotlin sources agree on every wire code, so the
#       machine-readable handshake surface cannot drift from the implementation.
#   3.  Every DTO named in §6.3/§6.3.2 has both a .kt and a .aidl declaration.
#   4.  The contract module compiles and its tests pass from BOTH app Gradle
#       roots, which is what makes the shared library claim real rather than
#       "it worked in Auto's build".
#   5.  The canonical spec, Kotlin and compatibility.yaml agree on the error-code
#       table. Sections 1-3 are internal-consistency only: they cannot notice that
#       the document being frozen has been superseded.
#   5b. Inline NAME(wire) references in prose match the authoritative enum, so a
#       hand-written wire number in a sentence cannot contradict the table.
#   5c. The KDoc on each error constant carries the canonical scope of its §6.3.3
#       row. 5 and 5b bind wire NUMBERS; nobody opens a 3800-line spec to learn
#       what STALE_LEASE means, they hover the symbol. Three revisions fixed a
#       definition on the canonical side only, and every other section stayed green.
#   6.  The method surface matches across all four carriers (AIDL, yaml, README,
#       canonical §6.1) — by NAME, which is all README can structurally express.
#   6b. AIDL and canonical §6.1 additionally agree on the ORDERED full signature:
#       return type, parameter direction/type/name, and declaration order. Binder
#       dispatch is positional, so a swapped declaration renumbers transaction ids.
#   7.  Canonical §6.3/§6.3.2 and Kotlin agree on the DTO schemas: class set, field
#       names, and the ordered (name, type, nullability, default-presence) tuples.
#       parcelize is positional too, one level down.
#
# The gate's own sensitivity is not self-evident from any of the above, so it is
# measured separately: scripts/selftest-contract-v1.sh mutates a throwaway copy of
# the canonical spec once per guard and asserts each guard reports its own finding
# and nothing else's. Weakening a guard here turns that suite red.
#
# Exit codes: 0 = every check passed; 1 = at least one failed.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

MODULE="contracts/environment-control-v1"
# The canonical document this contract claims to freeze. Section 5 binds the
# code to it; without that binding every other check is internal-consistency
# only and cannot notice that the frozen version has been superseded.
SPEC_PATH="feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md"
KT_DIR="$MODULE/src/main/java/io/github/terryyyc/fakexxx/contract/v1"
KT_DIGEST="$KT_DIR/CanonicalDigestV1.kt"
AIDL_DIR="$MODULE/src/main/aidl/io/github/terryyyc/fakexxx/contract/v1"

SKIP_GRADLE=0
[ "${1:-}" = "--static-only" ] && SKIP_GRADLE=1

FAILURES=0
INCONCLUSIVE=0
pass() { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
# A check whose TOOLCHAIN could not run is not a failing check and is not a
# passing one. Section 4 used to print the same `FAIL <target>` line for "the
# contract tests failed" and for "this machine has no JDK, so Gradle never
# started" — two states that call for opposite actions, rendered identically.
inconc() { printf '  INCONCLUSIVE  %s\n' "$1"; INCONCLUSIVE=$((INCONCLUSIVE + 1)); }
section() { printf '\n== %s ==\n' "$1"; }

command -v python3 >/dev/null 2>&1 || { printf 'check-contract-v1: python3 required\n' >&2; exit 1; }

# ---------------------------------------------------------------------------
section "1. no enum type inside any @Parcelize class"

if KT_DIR="$KT_DIR" python3 - <<'PY'
import os, re, sys, pathlib

kt_dir = pathlib.Path(os.environ["KT_DIR"])
sources = sorted(kt_dir.glob("*.kt"))
if not sources:
    print(f"  FAIL  no Kotlin sources under {kt_dir}")
    sys.exit(1)

# Enum types declared by the contract module itself.
enum_names = set()
for f in sources:
    enum_names.update(re.findall(r"^enum\s+class\s+(\w+)", f.read_text(), re.M))
if not enum_names:
    print("  FAIL  no enum classes found; the guard would be vacuous")
    sys.exit(1)

ok = True
checked = 0
for f in sources:
    text = f.read_text()
    # Each @Parcelize class body: from the annotation to the closing ") :".
    for m in re.finditer(r"@Parcelize\s+data class\s+(\w+)\s*\((.*?)\n\)\s*:", text, re.S):
        cls, body = m.group(1), m.group(2)
        checked += 1
        for line in body.splitlines():
            line = line.split("//")[0]
            if "val " not in line:
                continue
            # Declared type is everything after the first ':' on the property line.
            decl = line.split(":", 1)[1] if ":" in line else ""
            for enum in enum_names:
                if re.search(rf"\b{enum}\b", decl):
                    print(f"  FAIL  {f.name}: {cls} field carries enum type {enum}: {line.strip()}")
                    ok = False

if checked == 0:
    print("  FAIL  no @Parcelize classes matched; the guard would be vacuous")
    sys.exit(1)

print(f"  ----  enum types known to the guard: {', '.join(sorted(enum_names))}")
if ok:
    print(f"  PASS  {checked} @Parcelize class(es) scanned, none carries an enum-typed field")
else:
    print(f"  ----  {checked} @Parcelize class(es) scanned; see FAIL lines above")
sys.exit(0 if ok else 1)
PY
then :; else FAILURES=$((FAILURES + 1)); fi

# ---------------------------------------------------------------------------
section "2. compatibility.yaml matches the Kotlin wire codes"

if MODULE="$MODULE" KT_DIR="$KT_DIR" python3 - <<'PY'
import os, re, sys, pathlib

yaml_path = pathlib.Path(os.environ["MODULE"]) / "compatibility.yaml"
kt_dir = pathlib.Path(os.environ["KT_DIR"])
if not yaml_path.exists():
    print(f"  FAIL  {yaml_path} is missing")
    sys.exit(1)

# Minimal parse of the `enums:` block; avoids a PyYAML dependency in CI.
declared, current, in_enums = {}, None, False
for raw in yaml_path.read_text().splitlines():
    if raw.startswith("enums:"):
        in_enums = True
        continue
    if in_enums:
        if raw and not raw.startswith(" "):
            break
        m = re.match(r"^  (\w+):\s*$", raw)
        if m:
            current = m.group(1); declared[current] = {}; continue
        m = re.match(r"^    (\w+):\s*(\d+)\s*$", raw)
        if m and current:
            declared[current][m.group(1)] = int(m.group(2))

actual = {}
for f in sorted(kt_dir.glob("*.kt")):
    text = f.read_text()
    for em in re.finditer(r"enum\s+class\s+(\w+)\s*\(val wire: Int\)\s*\{(.*?)\n\}", text, re.S):
        name, body = em.group(1), em.group(2)
        # NOTE: this comprehension has the dict-collapse weakness section 5
        # documents (duplicate names overwrite, duplicate wires invisible,
        # unparseable rows silently dropped). It is NOT hardened here on
        # purpose: a second hand-written strict parser would be a second drift
        # point. Section 5 parses these same Kotlin files AND the same yaml with
        # strict_members(), so the duplicate/malformed class is caught there for
        # all three carriers before any comparison runs. If section 5 is ever
        # weakened or removed, this line stops being covered -- harden it then
        # by extracting the shared parser, not by copying it.
        actual[name] = {c: int(w) for c, w in re.findall(r"(\b[A-Z][A-Z0-9_]*)\((\d+)\)", body)}

ok = True
if set(declared) != set(actual):
    print(f"  FAIL  enum sets differ: yaml={sorted(declared)} kotlin={sorted(actual)}")
    ok = False
for name in sorted(set(declared) & set(actual)):
    if declared[name] != actual[name]:
        print(f"  FAIL  {name}: yaml={declared[name]} kotlin={actual[name]}")
        ok = False
    else:
        print(f"  PASS  {name}: {len(actual[name])} wire code(s) match compatibility.yaml")
sys.exit(0 if ok else 1)
PY
then :; else FAILURES=$((FAILURES + 1)); fi

# ---------------------------------------------------------------------------
section "3. every v1 DTO has a .kt and a .aidl declaration"

# The DTO set is DERIVED from the two carriers, not hardcoded.
#
# It used to be a literal list in this script. A hardcoded list is the same drift
# hazard that let the module freeze a superseded spec: adding a DTO to one
# carrier and forgetting the other stays green, because the list never mentioned
# the new name. A list you must remember to update is not a gate, it is a note.
#
# Parity is checked in BOTH directions: a .kt @Parcelize class with no .aidl
# cannot cross Binder, and a `parcelable` declaration with no Kotlin class is a
# dangling declaration. Either one alone is a defect.
if KT_DIR="$KT_DIR" AIDL_DIR="$AIDL_DIR" python3 - <<'PY'
import os, pathlib, re, sys
kt_dir, aidl_dir = pathlib.Path(os.environ["KT_DIR"]), pathlib.Path(os.environ["AIDL_DIR"])

kt = {p.stem for p in kt_dir.glob("*V1.kt")
      if re.search(r"^@Parcelize", p.read_text(), re.M)}
aidl = set()
for p in aidl_dir.glob("*V1.aidl"):
    if re.search(r"^\s*parcelable\s+" + re.escape(p.stem) + r"\s*;", p.read_text(), re.M):
        aidl.add(p.stem)

only_kt, only_aidl = sorted(kt - aidl), sorted(aidl - kt)
if not kt or not aidl:
    print(f"  FAIL  empty carrier: kotlin={len(kt)} aidl={len(aidl)}"); sys.exit(1)
if only_kt:   print(f"  FAIL  @Parcelize class without a .aidl declaration: {only_kt}")
if only_aidl: print(f"  FAIL  .aidl parcelable without a Kotlin class: {only_aidl}")
if only_kt or only_aidl: sys.exit(1)
print(f"  PASS  {len(kt)} DTO(s) declared in BOTH carriers (derived, not a hardcoded list)")
PY
then
  pass "DTO .kt <-> .aidl parity"
else
  fail "DTO .kt <-> .aidl parity"
fi
for extra in ContractEnumsV1 ContractErrorCodeV1 CanonicalIntentDigestV1; do
  if [ -f "$KT_DIR/$extra.kt" ]; then pass "$extra.kt present"; else fail "$extra.kt missing"; fi
done
if [ -f "$AIDL_DIR/IEnvironmentControlV1.aidl" ]; then
  # A required-floor list, deliberately explicit: unlike the DTO set above it
  # cannot be derived from the interface itself, because deriving "what the
  # interface declares" from the interface is circular and always passes. These
  # names come from spec §6.1 plus §6.7.3's completeAndAdvance seam. Binding this
  # floor to canonical prose is the remaining spec-binding work; until then this
  # list can only under-specify, never falsely accept a missing method.
  for method in discover preflight apply observe release completeAndAdvance; do
    if grep -q "$method(" "$AIDL_DIR/IEnvironmentControlV1.aidl"; then
      pass "IEnvironmentControlV1 declares $method"
    else
      fail "IEnvironmentControlV1 is missing $method"
    fi
  done
else
  fail "IEnvironmentControlV1.aidl missing"
fi

# ---------------------------------------------------------------------------
section "4. contract module builds and tests green from BOTH app roots"

if [ "$SKIP_GRADLE" -eq 1 ]; then
  printf '  SKIP  --static-only requested (Gradle checks not run)\n'
  printf '  NOTE  --static-only is NOT a pass of this gate.\n'
else
  for app in cellrebel-auto qianwangyou; do
    # Capture instead of discarding. The old form sent stdout+stderr to
    # /dev/null, so the single most useful fact — WHY Gradle exited non-zero —
    # was destroyed at the exact moment it was produced.
    log="$(mktemp)"
    if ( cd "apps/$app" && ./gradlew --no-daemon :environment-control-v1:testDebugUnitTest ) >"$log" 2>&1; then
      pass "apps/$app :environment-control-v1:testDebugUnitTest"
    elif grep -qE 'Unable to locate a Java Runtime|JAVA_HOME is not set|no Java (runtime|installation)' "$log"; then
      # No JDK on this machine: Gradle never started, so the contract was never
      # exercised. Reporting FAIL here would be a false red, and — worse — it
      # would look exactly like a genuine contract regression.
      inconc "apps/$app :environment-control-v1:testDebugUnitTest — NO JDK; Gradle never started, contract not exercised"
    else
      fail "apps/$app :environment-control-v1:testDebugUnitTest"
      printf '        ---- last 15 lines ----\n'
      tail -15 "$log" | sed 's/^/        /'
    fi
    rm -f "$log"
  done
fi

# ---------------------------------------------------------------------------
section "5. canonical spec <-> Kotlin <-> compatibility.yaml (spec binding)"

# Sections 1-3 are all INTERNAL-consistency checks. A contract can satisfy every
# one of them and still freeze the wrong version of the spec -- which is exactly
# what happened: the module froze §6 v1.4 while canonical moved to v1.36, and no
# gate could see it. Worse, ContractWireCompatibilityTest asserts the frozen set
# is "complete", so a green test was actively certifying the superseded set.
#
# This section binds the code to the canonical document. It compares SETS of
# (name, wire code) in BOTH directions across all three carriers. A count, a
# token grep, or a section hash cannot prove semantic set equality: equal counts
# with a renamed constant, or a hash that changes for an unrelated typo, both
# report the wrong thing.
if SPEC="$SPEC_PATH" KT_DIR="$KT_DIR" MODULE="$MODULE" python3 - <<'PY'
import os, pathlib, re, sys

full_spec = pathlib.Path(os.environ["SPEC"]).read_text()
kt_dir = pathlib.Path(os.environ["KT_DIR"])

# Scope to section 6. The contract's wire domains live there; section 8 declares
# Auto-INTERNAL enums in the same Kotlin syntax (e.g. CellRebelCompletionEvidenceV1
# in 8.6.2), which are not part of the shared v1 surface. An unscoped scan reports
# them as "missing from Kotlin/yaml", and obeying that would drag an internal Auto
# type into the cross-app contract -- a gate over-reaching is not a stricter gate,
# it is a wrong one.
_s6 = re.search(r"^## 6\. .*?^## 7\. ", full_spec, re.S | re.M)
if not _s6:
    print("  FAIL  section 6 anchor not found in canonical spec"); sys.exit(1)
spec_text = _s6.group(0)

# Two shapes carry enum wire values in canonical and BOTH must be read, or a
# domain declared in one shape escapes the gate entirely:
#   6.2   one-line  `enum class X(val wire: Int) { A(1), B(2) }`
#   6.3.3 markdown table, one row per code
# A dict comprehension over re.findall was the wrong tool for all three carriers
# and hid four distinct defects at once, because findall CANNOT fail: it returns
# fewer matches. So the gate compared what it managed to parse, never what was
# actually written, and any row it could not read simply left the comparison --
# after which all three carriers agreed on the same reduced set and went green.
# Keying by name additionally made duplicate wires invisible (two names, one
# number) and let a repeated name silently overwrite its earlier entry.
#
# strict_members() replaces skip-on-unparseable with fail-on-unparseable, and
# reports duplicate names, duplicate wires and duplicate enum types by name.
PARSE_ERRORS = []

def strict_members(body, where):
    """Every member of an enum body must parse, or the gate fails loudly."""
    body = body.split(";", 1)[0]                       # members end at first ';'
    body = re.sub(r"/\*.*?\*/", " ", body, flags=re.S)  # block comments
    body = re.sub(r"//[^\n]*", " ", body)               # line comments
    out, seen_wire = {}, {}
    for frag in body.split(","):
        if not frag.strip():
            continue
        m = re.fullmatch(r"\s*([A-Z][A-Z0-9_]*)\s*\(\s*(\d+)\s*\)\s*", frag)
        if not m:
            PARSE_ERRORS.append("%s: unparseable enum member %r" % (where, frag.strip()[:60]))
            continue
        name, wire = m.group(1), int(m.group(2))
        if name in out:
            PARSE_ERRORS.append("%s: duplicate member name %s (%d then %d)"
                                % (where, name, out[name], wire))
        if wire in seen_wire:
            PARSE_ERRORS.append("%s: wire %d used by both %s and %s"
                                % (where, wire, seen_wire[wire], name))
        out[name], seen_wire[wire] = wire, name
    return out

def put(store, key, value, where):
    """Declaring the same enum type twice must not silently keep only the last."""
    if key in store:
        PARSE_ERRORS.append("%s: enum type %s declared more than once" % (where, key))
    store[key] = value

spec_enums = {}
for m in re.finditer(r"enum class\s+([A-Za-z0-9_]+V1)\s*\(val wire: Int\)\s*\{([^}]*)\}", spec_text):
    put(spec_enums, m.group(1),
        strict_members(m.group(2), "canonical §6.2 %s" % m.group(1)), "canonical §6.2")

t = re.search(r"^#### 6\.3\.3 .*?$(.*?)^#### ", spec_text, re.S | re.M)
if not t:
    print("  FAIL  6.3.3 anchor not found in canonical spec"); sys.exit(1)
table, tbl_wire = {}, {}
for line in t.group(1).splitlines():
    r = re.match(r"^\|\s*\*{0,2}(\d+)\*{0,2}\s*\|\s*\*{0,2}`([A-Z_]+)`", line)
    if r:
        name, wire = r.group(2), int(r.group(1))
        if name in table:
            PARSE_ERRORS.append("canonical §6.3.3: duplicate row for %s (%d then %d)"
                                % (name, table[name], wire))
        if wire in tbl_wire:
            PARSE_ERRORS.append("canonical §6.3.3: wire %d claimed by both %s and %s"
                                % (wire, tbl_wire[wire], name))
        table[name], tbl_wire[wire] = wire, name
if table:
    spec_enums.setdefault("ContractErrorCodeV1", {}).update(table)

kt_enums = {}
for f in sorted(kt_dir.glob("*.kt")):
    for m in re.finditer(r"enum class\s+([A-Za-z0-9_]+)\s*\(val wire: Int\)\s*\{(.*?)\n\}", f.read_text(), re.S):
        put(kt_enums, m.group(1),
            strict_members(m.group(2), "kotlin %s (%s)" % (m.group(1), f.name)), "kotlin " + f.name)

yml_enums, cur, in_enums = {}, None, False
for raw in (pathlib.Path(os.environ["MODULE"]) / "compatibility.yaml").read_text().splitlines():
    if raw.startswith("enums:"):
        in_enums = True; continue
    if in_enums:
        if raw and not raw.startswith(" "): break
        m = re.match(r"^  (\w+):\s*$", raw)
        if m: cur = m.group(1); yml_enums[cur] = {}; continue
        m = re.match(r"^    (\w+):\s*(\d+)\s*$", raw)
        if m and cur:
            nm, wr = m.group(1), int(m.group(2))
            if nm in yml_enums[cur]:
                PARSE_ERRORS.append("yaml %s: duplicate key %s (%d then %d)"
                                    % (cur, nm, yml_enums[cur][nm], wr))
            if wr in yml_enums[cur].values():
                dup = [k for k, v in yml_enums[cur].items() if v == wr][0]
                PARSE_ERRORS.append("yaml %s: wire %d used by both %s and %s" % (cur, wr, dup, nm))
            yml_enums[cur][nm] = wr
        elif in_enums and cur and raw.strip() and not re.match(r"^  \w+:\s*$", raw):
            # A member line that does not parse must not vanish from the compare.
            PARSE_ERRORS.append("yaml %s: unparseable line %r" % (cur, raw.strip()[:60]))

carriers = {"canonical": spec_enums, "kotlin": kt_enums, "yaml": yml_enums}
empty = [n for n, c in carriers.items() if not c]
if empty:
    print("  FAIL  empty carrier(s): %s" % empty); sys.exit(1)

# Fail BEFORE comparing. An unparseable or duplicated row makes the three sets
# agree on a reduced view, so if this ran after the comparison the gate would
# report "all carriers agree" about a document it could not fully read.
if PARSE_ERRORS:
    for e in PARSE_ERRORS:
        print("  FAIL  %s" % e)
    sys.exit(1)

fail = 0

# Enum-SET equality first, all three ways. The previous version hardcoded
# ContractErrorCodeV1, so a domain present in canonical and Kotlin but named by
# no gate was simply never looked at -- which is how AdvanceOutcomeV1 could
# diverge 2 -> 7 in canonical while every check stayed green. A gate that names
# what it checks can only check what someone remembered to name.
names = {n: set(c) for n, c in carriers.items()}
if not (names["canonical"] == names["kotlin"] == names["yaml"]):
    fail = 1
    seen = []
    for a in carriers:
        for b in carriers:
            if a < b:
                only_a, only_b = sorted(names[a] - names[b]), sorted(names[b] - names[a])
                if only_a: print("  FAIL  enum in %s but NOT in %s: %s" % (a, b, only_a))
                if only_b: print("  FAIL  enum in %s but NOT in %s: %s" % (b, a, only_b))
else:
    print("  PASS  enum set identical across all three carriers: %d enum(s)" % len(names["kotlin"]))

for name in sorted(set(spec_enums) & set(kt_enums) & set(yml_enums)):
    sp, kt, ym = spec_enums[name], kt_enums[name], yml_enums[name]
    bad = False
    for a_name, a, b_name, b in (("canonical", sp, "kotlin", kt),
                                 ("canonical", sp, "yaml", ym),
                                 ("kotlin", kt, "yaml", ym)):
        missing, extra = sorted(set(a) - set(b)), sorted(set(b) - set(a))
        mism = sorted(n for n in set(a) & set(b) if a[n] != b[n])
        if missing: print("  FAIL  %s: in %s but NOT in %s: %s" % (name, a_name, b_name, missing)); bad = True
        if extra:   print("  FAIL  %s: in %s but NOT in %s: %s" % (name, b_name, a_name, extra)); bad = True
        for n in mism:
            print("  FAIL  %s.%s wire differs: %s=%d %s=%d" % (name, n, a_name, a[n], b_name, b[n])); bad = True
    if bad: fail = 1
    else:   print("  PASS  %s: %d (name, code) pair(s) agree across canonical/kotlin/yaml" % (name, len(sp)))
sys.exit(fail)
PY
then
  pass "every wire domain is bound across canonical / Kotlin / compatibility.yaml"
else
  fail "wire domains differ between canonical / Kotlin / compatibility.yaml"
fi

# ---------------------------------------------------------------------------
section "5b. inline NAME(wire) references in prose match the authoritative enum"

# Section 5 binds the §6.3.3 TABLE to Kotlin and to compatibility.yaml. It says
# nothing about the dozens of places the prose writes a wire number inline --
# "not REQUEST_INVALID(4)", "returns LEASE_CONFLICT(7)". Those references carry
# real normative weight: an implementer reading §6.7.4b writes the number that
# is printed there, not the one in a table 400 lines up.
#
# This gate exists because that gap was exercised, not hypothesised. A revision
# froze an advance precedence order citing REQUEST_INVALID(4) and
# IDEMPOTENCY_CONFLICT(13); the authoritative values are 13 and 12, and 4 is
# CAPABILITY_UNAVAILABLE -- so a whole paragraph of reasoning argued about an
# error code unrelated to the case. Sections 1-7 all passed and CI was 4/4,
# because nothing compared prose numbers against the enum. A reviewer caught it.
#
# Deliberately not restricted to §6.3.3 or to any section: the failure mode is a
# number written far away from the table, so the scan is whole-document.
if python3 - "$KT_DIR/ContractErrorCodeV1.kt" "$SPEC_PATH" <<'PY'
import re, sys
kt_path, spec_path = sys.argv[1], sys.argv[2]

kt = open(kt_path, encoding="utf-8").read()
# Authoritative source: the Kotlin enum constants, e.g. REQUEST_INVALID(13).
truth = {m.group(1): int(m.group(2))
         for m in re.finditer(r'^\s*([A-Z][A-Z0-9_]*)\((\d+)\)', kt, re.M)}
if not truth:
    print("  FAIL  5b: parsed zero enum constants from ContractErrorCodeV1.kt")
    sys.exit(1)

spec = open(spec_path, encoding="utf-8").read().split("\n")
# Accept both ASCII and full-width parens, with or without backticks/spaces,
# AND with markdown bold around either the name or the number -- the document
# uses every combination.
#
# The bold tolerance was missing until now, and it mattered: the canonical text
# really does contain `REQUEST_INVALID`(**13**), and this regex could not see
# it. So the one inline citation inside the revision note that announced "5b
# makes this class of error impossible to slip through" was itself the citation
# 5b could not read. Mutating it to (**4**) produced zero FAIL.
#
# The 6.3.3 TABLE parser above already tolerated \*{0,2} on both the wire and
# the name. Two parsers in one file disagreeing about the same markdown is how a
# guard ends up covering less than its own documentation claims.
# Named on purpose: the bold tolerance is the knob this guard can lose, so it
# must be mutable in ONE place. Inlining it four times would make the selftest
# unable to disable it without also disabling the 6.3.3 table parser, and a
# mutation that turns off two guards cannot prove which one was load-bearing.
BOLD = r'\*{0,2}'
ref = re.compile(BOLD + r'`?(' + '|'.join(sorted(truth, key=len, reverse=True)) +
                 r')`?' + BOLD + r'\s*[（(]\s*' + BOLD + r'(\d+)' + BOLD + r'\s*[）)]')

bad = checked = 0
for i, line in enumerate(spec, 1):
    for m in ref.finditer(line):
        name, cited = m.group(1), int(m.group(2))
        checked += 1
        if truth[name] != cited:
            bad += 1
            owner = next((n for n, w in truth.items() if w == cited), "no enum constant")
            print("  FAIL  %s:%d cites %s(%d); authoritative is %s(%d) and wire %d is %s"
                  % (spec_path, i, name, cited, name, truth[name], cited, owner))

if bad == 0:
    print("  PASS  %d inline wire reference(s) match ContractErrorCodeV1" % checked)
sys.exit(1 if bad else 0)
PY
then
  pass "prose wire references are bound to the authoritative enum"
else
  fail "at least one inline NAME(wire) reference contradicts ContractErrorCodeV1"
fi

# ---------------------------------------------------------------------------
section "5c. error-code KDoc carries the canonical scope of its 6.3.3 row"

# Sections 5 and 5b bind wire NUMBERS -- the table to the code, and inline prose
# citations to the enum. Neither reads the one thing an implementer actually
# reads: the KDoc on the constant. Nobody opens a 3800-line spec to find out what
# STALE_LEASE means; they hover the symbol their IDE imported.
#
# That gap was exercised three times in this document's own history, and all three
# survived every other section plus 4/4 CI:
#
#   7  canonical widened LEASE_CONFLICT to "any non-RELEASED lease REGARDLESS OF
#      WHICH CALLER owns it" for completeAndAdvance (v1.39 (2)). The KDoc still said
#      "ANOTHER lease already holds the environment" -- the literal reading that
#      v1.39 had just recorded as a contradiction, preserved on the code side.
#   8  canonical NARROWED STALE_LEASE and excepted release / completeAndAdvance
#      (v1.42 (1)). The KDoc still said "no longer current", unscoped: implemented
#      literally, completeAndAdvance is unconditionally unusable and 8.4's
#      EXPIRED -> RELEASING recovery is unreachable.
#   16 canonical explicitly retired the reading "terminal, not a failure" and froze
#      the code as a genuine CALLER error (v1.39 correction note). The KDoc still
#      carried the retired sentence verbatim, in English.
#
# Same shape each time: a revision fixed the definition on ONE side. So the two
# rules below are both derived from canonical, never hardcoded:
#
#   A. If a 6.3.3 row scopes the code to specific interface METHODS, the KDoc must
#      name every one of them. The method universe comes from the AIDL interface,
#      so adding a method to the contract extends this check for free.
#   B. If a row carries a correction note (更正 / 旧文), the KDoc must cite the
#      revision that made it. A recorded correction has to leave a trace on the
#      side being corrected.
#
# WHAT THIS DOES NOT DO. It cannot verify that KDoc prose is semantically right --
# no string comparison across two natural languages can. It makes OMISSION
# impossible and forces a human to touch the KDoc when canonical scopes or
# corrects a code; a wrong sentence that names the right methods still passes.
# Rule A is also case-insensitive and word-boundary based, so an English "apply"
# used as a verb would satisfy it. That is a deliberate false-NEGATIVE bias: a
# gate that cries wolf on legitimate prose gets disabled, and section 5c would
# then protect nothing. Known uncovered class: a row listing two trigger branches
# where the KDoc names only one (wire 1 was exactly this) has no method and no
# correction marker, so neither rule sees it.
if SPEC="$SPEC_PATH" KT="$KT_DIR/ContractErrorCodeV1.kt" \
   AIDL="$AIDL_DIR/IEnvironmentControlV1.aidl" python3 - <<'PY'
import os, pathlib, re, sys

spec = pathlib.Path(os.environ["SPEC"]).read_text(encoding="utf-8")
kt = pathlib.Path(os.environ["KT"]).read_text(encoding="utf-8")
aidl = pathlib.Path(os.environ["AIDL"]).read_text(encoding="utf-8")

# Method universe from the interface itself: the contract's own surface decides
# what "scoped to a method" can mean, so a v1.x method addition is covered without
# editing this gate.
body = re.search(r"interface\s+IEnvironmentControlV1\s*\{(.*?)\n\}", aidl, re.S)
if not body:
    print("  FAIL  5c: could not locate the IEnvironmentControlV1 interface body")
    sys.exit(1)
methods = sorted(set(re.findall(r"\b([a-z][A-Za-z0-9]*)\s*\(", body.group(1))))
if not methods:
    print("  FAIL  5c: parsed zero methods from the AIDL interface")
    sys.exit(1)

# Anchor on the 6.3.3 heading. An unscoped scan would also match the revision-log
# rows near the top, which quote these same code names and method names while
# describing history rather than current normative scope.
sec = re.search(r"^#### 6\.3\.3 .*?(?=^#### |\Z)", spec, re.S | re.M)
if not sec:
    print("  FAIL  5c: canonical 6.3.3 anchor not found")
    sys.exit(1)

rows = {}
for line in sec.group(0).split("\n"):
    if not line.startswith("|"):
        continue
    cells = [c.strip() for c in line.strip().strip("|").split("|")]
    if len(cells) < 3:
        continue
    name = re.sub(r"[`*]", "", cells[1]).strip()
    if re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
        rows[name] = cells[2]
if not rows:
    print("  FAIL  5c: parsed zero code rows out of canonical 6.3.3")
    sys.exit(1)

# KDoc immediately preceding each constant. Comment lines accumulate; any other
# non-constant line resets, so the file-level KDoc cannot leak onto NOT_PAIRED.
kdoc, buf = {}, []
for line in kt.split("\n"):
    s = line.strip()
    if s.startswith("/**") or s.startswith("*"):
        buf.append(s)
        continue
    m = re.match(r"([A-Z][A-Z0-9_]*)\(\d+\)", s)
    if m:
        kdoc[m.group(1)] = " ".join(buf)
    buf = []

fail = 0
scoped = corrected = 0
for name, cell in sorted(rows.items()):
    doc = kdoc.get(name)
    if doc is None:
        print("  FAIL  5c: %s is a canonical 6.3.3 row with no KDoc'd constant" % name)
        fail = 1
        continue

    # Rule A -- per-method scope.
    need = [m for m in methods if re.search(r"\b%s\b" % m, cell)]
    if need:
        scoped += 1
        missing = [m for m in need if not re.search(r"\b%s\b" % m, doc, re.I)]
        if missing:
            print("  FAIL  5c: %s -- canonical 6.3.3 scopes it per method (%s); "
                  "its KDoc never names %s" % (name, ", ".join(need), ", ".join(missing)))
            fail = 1

    # Rule B -- a recorded correction must leave a trace on the corrected side.
    if re.search(r"更正|旧文", cell):
        revs = sorted(set(re.findall(r"v(\d+\.\d+)", cell)))
        if revs:
            corrected += 1
            absent = [r for r in revs if ("v" + r) not in doc]
            if absent:
                print("  FAIL  5c: %s -- canonical 6.3.3 records a correction in %s; "
                      "its KDoc cites neither the revision nor the corrected reading"
                      % (name, ", ".join("v" + r for r in absent)))
                fail = 1

if not fail:
    print("  PASS  %d row(s) checked: %d method-scoped, %d carrying a correction note"
          % (len(rows), scoped, corrected))
sys.exit(fail)
PY
then
  pass "error-code KDoc agrees with the canonical scope of its 6.3.3 row"
else
  fail "at least one error-code KDoc drops the canonical scope of its 6.3.3 row"
fi

# ---------------------------------------------------------------------------
section "6. the method surface agrees across all four carriers"

# completeAndAdvance was added to the real AIDL interface and to nowhere else:
# §6.1's AIDL example, compatibility.yaml's methods list and README's Surface
# block all still described a five-method contract. §6.3 says "a field not listed
# here is not part of v1", so the contract was simultaneously declaring and
# denying the same method.
#
# Nothing caught it, because section 3 only asks whether a hardcoded floor of
# method names EXISTS in the interface. Existence is one direction. Four carriers
# describing the same surface need set equality across all of them, or three of
# them are decoration that drifts the moment someone edits the fourth.
if SPEC="$SPEC_PATH" AIDL_DIR="$AIDL_DIR" MODULE="$MODULE" python3 - <<'PY'
import os, pathlib, re, sys

aidl = (pathlib.Path(os.environ["AIDL_DIR"]) / "IEnvironmentControlV1.aidl").read_text()
body = re.search(r"interface\s+IEnvironmentControlV1\s*\{(.*?)\n\}", aidl, re.S)
if not body: print("  FAIL  cannot parse IEnvironmentControlV1 body"); sys.exit(1)
iface = set(re.findall(r"^\s*[A-Za-z0-9_]+\s+([a-zA-Z0-9_]+)\s*\(", body.group(1), re.M))

yml = (pathlib.Path(os.environ["MODULE"]) / "compatibility.yaml").read_text()
m = re.search(r"^\s*methods:\s*\[([^\]]*)\]", yml, re.M)
if not m: print("  FAIL  compatibility.yaml has no methods: [...] list"); sys.exit(1)
manifest = {x.strip() for x in m.group(1).split(",") if x.strip()}

rd = (pathlib.Path(os.environ["MODULE"]) / "README.md").read_text()
sur = re.search(r"IEnvironmentControlV1\n(.*?)```", rd, re.S)
if not sur: print("  FAIL  README has no IEnvironmentControlV1 surface block"); sys.exit(1)
readme = set(re.findall(r"^\s*([a-zA-Z0-9_]+)\(\)", sur.group(1), re.M))

spec = pathlib.Path(os.environ["SPEC"]).read_text()
sm = re.search(r"^### 6\.1 .*?$(.*?)^### ", spec, re.S | re.M)
if not sm: print("  FAIL  §6.1 anchor not found in canonical spec"); sys.exit(1)
sb = re.search(r"interface\s+IEnvironmentControlV1\s*\{(.*?)\n\}", sm.group(1), re.S)
if not sb: print("  FAIL  §6.1 has no IEnvironmentControlV1 example block"); sys.exit(1)
specm = set(re.findall(r"^\s*[A-Za-z0-9_]+\s+([a-zA-Z0-9_]+)\s*\(", sb.group(1), re.M))

carriers = {"aidl": iface, "yaml": manifest, "readme": readme, "spec-6.1": specm}
empty = [n for n, s in carriers.items() if not s]
if empty:
    print(f"  FAIL  empty carrier(s): {empty}"); sys.exit(1)

ref = iface
fail = 0
for name, s in carriers.items():
    if name == "aidl": continue
    missing, extra = sorted(ref - s), sorted(s - ref)
    if missing or extra:
        fail = 1
        if missing: print(f"  FAIL  in aidl but NOT in {name}: {missing}")
        if extra:   print(f"  FAIL  in {name} but NOT in aidl: {extra}")
    else:
        print(f"  PASS  aidl <-> {name}: {len(ref)} method(s) match in both directions")
sys.exit(fail)
PY
then
  pass "method surface identical across aidl / compatibility.yaml / README / spec §6.1"
else
  fail "method surface differs between carriers"
fi

# ---------------------------------------------------------------------------
section "6b. AIDL and canonical 6.1 agree on the ORDERED full signature"

# Section 6 compares unordered method NAMES across four carriers. That proves the
# surface exists and nothing at all about its shape: return type, parameter
# direction/type/name, and declaration order are invisible to it. An AIDL that
# returns the wrong DTO, takes `out` where the spec froze `in`, renames a
# parameter, or swaps two methods passes section 6 unchanged.
#
# Order is not cosmetic here. Binder dispatch is positional: swapping two method
# declarations silently renumbers every transaction id after them, so an old peer
# calls what it believes is `observe` and reaches `release`. That failure is
# invisible at compile time on both sides.
#
# README lists bare `name()` and structurally cannot express a signature, so it
# stays in section 6's name-only comparison; AIDL and canonical 6.1 both carry the
# full declaration and are compared here as ordered tuples.
if python3 - "$AIDL_DIR/IEnvironmentControlV1.aidl" "$SPEC_PATH" <<'PY'
import io, re, sys
aidl_path, spec_path = sys.argv[1], sys.argv[2]

DECL = re.compile(r"^\s*([A-Za-z_]\w*)\s+([a-zA-Z_]\w*)\s*\(([^)]*)\)\s*;", re.M)

def sigs(text, where, errs):
    out = []
    for m in DECL.finditer(text):
        ret, name, raw = m.group(1), m.group(2), m.group(3).strip()
        params = []
        if raw:
            for frag in raw.split(","):
                pm = re.fullmatch(r"\s*(in|out|inout)\s+([A-Za-z_][\w.]*)\s+([a-zA-Z_]\w*)\s*", frag)
                if not pm:
                    errs.append("%s: %s() has an unparseable parameter %r" % (where, name, frag.strip()))
                    params.append(("?", "?", "?"))
                else:
                    params.append(pm.groups())
        out.append((ret, name, tuple(params)))
    return out

errs = []
aidl_body = io.open(aidl_path, encoding="utf-8").read()
aidl_body = aidl_body[aidl_body.index("{") + 1:] if "{" in aidl_body else aidl_body
a = sigs(aidl_body, "aidl", errs)

spec = io.open(spec_path, encoding="utf-8").read()
# Same anchoring section 6 already uses and proves: '### 6.1' heading, then the
# interface block inside it. Deriving my own anchor produced a gate that failed
# for a reason unrelated to the contract, which is a false red -- the mirror of
# the false greens this file exists to remove, and just as misleading.
sm = re.search(r"^### 6\.1 .*?$(.*?)^### ", spec, re.S | re.M)
if not sm:
    print("  FAIL  6b: canonical 6.1 heading not found"); sys.exit(1)
sb = re.search(r"interface\s+IEnvironmentControlV1\s*\{(.*?)\n\}", sm.group(1), re.S)
if not sb:
    print("  FAIL  6b: canonical 6.1 has no IEnvironmentControlV1 interface block"); sys.exit(1)
s = sigs(sb.group(1), "canonical 6.1", errs)

if not a or not s:
    print("  FAIL  6b: parsed %d aidl and %d canonical signature(s); both must be non-empty" % (len(a), len(s)))
    sys.exit(1)

def fmt(t):
    return "%s %s(%s)" % (t[0], t[1], ", ".join("%s %s %s" % p for p in t[2]))

if a != s:
    for i in range(max(len(a), len(s))):
        x = fmt(a[i]) if i < len(a) else "<missing>"
        y = fmt(s[i]) if i < len(s) else "<missing>"
        if x != y:
            errs.append("position %d: aidl %r != canonical 6.1 %r" % (i, x, y))

if errs:
    for e in errs:
        print("  FAIL  %s" % e)
    sys.exit(1)
print("  PASS  %d method(s) identical in return, name, params and order" % len(a))
sys.exit(0)
PY
then
  pass "ordered full method signature agrees between AIDL and canonical 6.1"
else
  fail "AIDL and canonical 6.1 disagree on ordered full method signature"
fi

# ---------------------------------------------------------------------------
section "7b. canonical digest preimages declare the same domain the module frames"
# WHY THIS EXISTS.
# §6.3.1 freezes the domain tag as the FIRST framed field of every preimage, and
# CanonicalDigestV1 implements exactly that. But the canonical advance-REQUEST
# preimage block listed its fields starting at leaseId with no domain line at
# all, while the advance-RECEIPT block did declare one. An independent provider
# implementing from the canonical text would therefore compute a domain-less
# digest, Auto would compute a domain-ful one, and §6.7.4b step 1 ("recompute
# requestDigest from the received fields") would reject EVERY well-formed
# request with REQUEST_INVALID(13). The contract was not interoperable on paper.
#
# Nothing could see it. The module's own tests agree with the module. The golden
# vectors added in v1.47 were computed by a second implementation written from
# the CODE, so both readings shared one source and the divergence was outside
# what they could express. Two implementations only cross-check each other when
# they are derived from two different SOURCES -- otherwise it is one reading
# spelled twice.
#
# This binds the canonical text itself: each preimage block must declare a
# domain, and that literal must equal the module constant it claims to freeze.
if python3 - "$SPEC_PATH" "$KT_DIGEST" <<'PY'
import re, sys
spec, kt = open(sys.argv[1], encoding="utf-8").read(), open(sys.argv[2], encoding="utf-8").read()

consts = dict(re.findall(r'const val (DOMAIN_[A-Z_]+): String = "([^"]+)"', kt))
if len(consts) != 3:
    print("  FAIL  7b: expected 3 DOMAIN_* constants in the module, found %d" % len(consts))
    sys.exit(1)

# Each fenced block that defines a preimage must carry a domain line.
# Fences carry an optional language tag (```text) and blocks open with either
# `canonical = ` or `domain = `. Accepting only a bare fence missed the intent
# block entirely -- the third time in this file that a guard's pattern was
# narrower than the notation the document actually uses. Each time the symptom
# was the same: it reported absence where there was presence.
blocks = re.findall(r"```(?:[a-z]*)\n((?:canonical|domain)\s*[:=].*?)```", spec, re.S)
if len(blocks) < 3:
    print("  FAIL  7b: found %d preimage block(s); the module defines 3 domains, so the"
          " block matcher is not seeing them all" % len(blocks))
    sys.exit(1)
found = []
for b in blocks:
    # BOTH notations are in use: `domain = "..."` (receipt) and
    # `domain : ASCII "..."` (intent). The first version of this check accepted
    # only `=` and therefore reported the intent block as declaring no domain --
    # a guard whose pattern is narrower than the document's real notation
    # reports absence where there is presence. That is the §5b failure repeated
    # by the person who had just fixed §5b.
    m = re.search(r'domain\s*[:=]\s*(?:ASCII\s+)?"([^"]+)"', b)
    first = next((l.strip() for l in b.splitlines() if l.strip() and not l.strip().startswith("canonical")), "")
    found.append((m.group(1) if m else None, first[:60]))

missing = [f for d, f in found if d is None]
declared = {d for d, _ in found if d}

bad = 0
if missing:
    bad += 1
    for f in missing:
        print("  FAIL  7b: a canonical preimage block declares no domain; first field is %r" % f)
        print("            §6.3.1 freezes the domain as the FIRST framed field, and the module")
        print("            frames one. A provider implementing from this block computes a")
        print("            different digest than the module for identical field values.")

for d in declared:
    if d not in consts.values():
        bad += 1
        print("  FAIL  7b: canonical declares domain %r, which no module constant defines" % d)

for name, val in sorted(consts.items()):
    if val not in declared:
        bad += 1
        print("  FAIL  7b: module %s = %r is never declared by any canonical preimage block" % (name, val))

if not bad:
    print("  PASS  %d canonical preimage block(s) declare domains matching all 3 module constants"
          % len(found))
sys.exit(1 if bad else 0)
PY
then
  pass "canonical preimages and the module agree on the domain tag"
else
  fail "a canonical preimage block disagrees with the module about its domain tag"
fi

section "7. DTO schemas agree between canonical §6.3 and Kotlin (name, type, order)"

# Sections 5 and 6 bind the ERROR CODES and the METHOD names. Nothing bound the
# FIELDS, and the gap proved itself: three DTOs gained schedule identity in
# Kotlin while canonical's exact-schema snippets still showed the old field
# lists, and this gate stayed green through it.
#
# That is not a cosmetic mismatch. §6.3 says "a field not listed here is not part
# of v1", so an unlisted field is simultaneously shipped and denied -- the same
# contradiction P1 #2 created for methods, one level down.
#
# The comparison runs in three layers, coarsest failure first, because one
# ordered-tuple diff reports a single inserted field as a cascade of position
# mismatches and buries the one fact worth reading:
#
#   A. the CLASS set, both directions;
#   B. the field-NAME set per class, both directions;
#   C. the ORDERED (name, type, default-presence) tuples per class.
#
# Layer C is not decoration on layer B, and it is not a second section: an
# ordered tuple comparison already contains the name-set comparison, so splitting
# it out would report every drift twice and imply that getting layer B green were
# a fix. What layer C adds is everything a name set structurally cannot see.
# kotlin-parcelize writes fields in DECLARATION ORDER and reads them back in
# declaration order, so swapping two same-typed fields preserves every name,
# compiles on both sides, throws nothing, and silently transposes the two values
# on the wire. A changed type or an added/removed `?` is the same class of
# defect: invisible to a name set, decisive at unparcel time. That is the §6b
# argument about Binder transaction ids one level down -- a positional encoding
# with no positional check.
if SPEC="$SPEC_PATH" KT_DIR="$KT_DIR" python3 - <<'PY'
import os, pathlib, re, sys

spec = pathlib.Path(os.environ["SPEC"]).read_text()
kt_dir = pathlib.Path(os.environ["KT_DIR"])

parse_errs = []
PROP = re.compile(r"^val\s+([A-Za-z0-9_]+)\s*:\s*(\S.*)$")

def props(body, where):
    """Ordered [(name, normalised type, has-default)] for one class body."""
    out = []
    # KDoc and block comments first: a `val x: Int` inside prose is not a field,
    # and §6.3's snippets carry per-field KDoc.
    b = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    for raw in b.splitlines():
        line = raw.split("//")[0].strip()
        if not line:
            continue
        decl, has_default = line, False
        if "=" in decl:
            # No Kotlin type contains '='; everything after the first one is the
            # default expression. Its TEXT is deliberately not compared: canonical
            # writes `= 1` where Kotlin writes `= ContractV1.PROTOCOL_VERSION`,
            # which is the same value spelled two ways. Its PRESENCE is compared,
            # because that is a real difference in the constructor contract.
            decl, has_default = decl.split("=", 1)[0], True
        decl = decl.strip().rstrip(",").strip()
        m = PROP.match(decl)
        if not m:
            # An unreadable property line FAILS; it is never skipped. A parser
            # that silently drops what it cannot read answers "identical" the
            # moment either side adopts a syntax it does not handle -- the field
            # would be absent from both the comparison and the report.
            parse_errs.append("%s: unparseable property line %r" % (where, line))
            continue
        out.append((m.group(1), re.sub(r"\s+", "", m.group(2)), has_default))
    if not out:
        parse_errs.append("%s: no property parsed from the class body" % where)
    return out

def classes(text, where, seen):
    out = {}
    for m in re.finditer(r"data class\s+([A-Za-z0-9_]+V1)\s*\((.*?)\n\)\s*:\s*Parcelable", text, re.S):
        name = m.group(1)
        # Two exact schemas for one type WITHIN one carrier are two sources of
        # truth, and the dict below would silently let the later one win. The
        # bookkeeping is per-carrier: canonical and Kotlin each declaring the
        # type once is not a duplicate, it is the entire premise of comparing
        # them.
        if name in seen:
            parse_errs.append("%s: %s already declared in %s; duplicate exact schema in one carrier"
                              % (where, name, seen[name]))
        seen[name] = where
        out[name] = props(m.group(2), "%s %s" % (where, name))
    return out

spec_c = classes(spec, "canonical", {})
kt_c, kt_seen = {}, {}
for p in sorted(kt_dir.glob("*V1.kt")):
    kt_c.update(classes(p.read_text(), "kotlin " + p.name, kt_seen))

# Parsing is reported BEFORE any verdict and is fatal on its own. A comparison
# run over a partially-read schema can only produce an unearned green.
if parse_errs:
    for e in parse_errs:
        print("  FAIL  %s" % e)
    sys.exit(1)

# CLASS-SET equality first, in BOTH directions, BEFORE comparing fields.
#
# The first version of this section compared only the INTERSECTION and checked
# only spec-minus-Kotlin. Anything Kotlin declared that canonical never described
# was silently dropped from the comparison and then counted as green -- and what
# escaped was precisely the three advance DTOs, the most load-bearing types on
# the branch. "10 DTO(s), 73 field(s) identical in both directions" was true and
# meaningless: a set can be internally consistent while not being the whole set.
#
# That is the same failure mode this branch keeps dismantling, built into the
# gate written to dismantle it. So the class set is now proven equal first; a
# type present on one side only is a failure, never a skip.
only_kt = sorted(set(kt_c) - set(spec_c))
only_spec_cls = sorted(set(spec_c) - set(kt_c))
class_fail = 0
if only_kt:
    print(f"  FAIL  Kotlin DTO(s) with no canonical §6.3 exact schema: {only_kt}")
    class_fail = 1
if only_spec_cls:
    print(f"  FAIL  canonical DTO(s) absent from Kotlin: {only_spec_cls}")
    class_fail = 1
if not class_fail:
    print(f"  PASS  class set identical: {len(kt_c)} DTO(s) in canonical and Kotlin")

shared = sorted(set(spec_c) & set(kt_c))
if not shared:
    print("  FAIL  no DTO appears in both canonical §6.3 and Kotlin"); sys.exit(1)

fail = class_fail
for name in shared:
    a, b = spec_c[name], kt_c[name]
    an, bn = [p[0] for p in a], [p[0] for p in b]

    # Layer B: the name set. Reported alone, because an inserted or dropped field
    # shifts every position after it and layer C would bury this line in cascade.
    missing, extra = sorted(set(an) - set(bn)), sorted(set(bn) - set(an))
    if missing or extra:
        fail = 1
        if missing: print(f"  FAIL  {name}: in spec but NOT in Kotlin: {missing}")
        if extra:   print(f"  FAIL  {name}: in Kotlin but NOT in spec: {extra}")
        continue

    # Layer C1: same names, different order. Called out on its own line because
    # the consequence is specific -- parcelize is positional, so two transposed
    # same-typed fields swap their values on the wire with nothing to observe.
    if an != bn:
        fail = 1
        print(f"  FAIL  {name}: same field names, DIFFERENT declaration order; "
              f"parcelize reads positionally, so this transposes values on the wire")
        print(f"  ----  {name}: canonical {an}")
        print(f"  ----  {name}: kotlin    {bn}")
        continue

    # Layer C2: same names in the same order -- compare what the names hide.
    for i, (x, y) in enumerate(zip(a, b)):
        if x[1] != y[1]:
            fail = 1
            kind = "nullability" if x[1].rstrip("?") == y[1].rstrip("?") else "type"
            print(f"  FAIL  {name}.{x[0]} (position {i}): {kind} differs; "
                  f"canonical {x[1]!r}, kotlin {y[1]!r}")
        if x[2] != y[2]:
            fail = 1
            only = "canonical" if x[2] else "kotlin"
            print(f"  FAIL  {name}.{x[0]} (position {i}): default value present only in {only}")

if fail == 0:
    total = sum(len(spec_c[n]) for n in shared)
    print(f"  PASS  {len(shared)} DTO(s), {total} field(s) identical in name, type, "
          f"nullability, default-presence and declaration order")

sys.exit(fail)
PY
then
  pass "DTO schemas bound to canonical §6.3, ordering included"
else
  fail "DTO schemas differ from canonical §6.3"
fi

# ---------------------------------------------------------------------------
printf '\n'
if [ "$FAILURES" -eq 0 ] && [ "$SKIP_GRADLE" -eq 0 ] && [ "$INCONCLUSIVE" -eq 0 ]; then
  printf 'check-contract-v1: PASS (all checks)\n'
  exit 0
fi
if [ "$FAILURES" -eq 0 ] && [ "$SKIP_GRADLE" -eq 1 ]; then
  printf 'check-contract-v1: INCOMPLETE (static checks passed, Gradle checks skipped)\n'
  exit 1
fi
if [ "$FAILURES" -eq 0 ]; then
  # Static guards green, but the build/test half never ran. This is NOT a pass
  # and must never be reported as one: the gate's whole claim is "the contract
  # compiles and its tests pass from BOTH app roots", and that claim is exactly
  # what went unmeasured.
  printf 'check-contract-v1: INCONCLUSIVE (static guards passed; %d Gradle check(s) could not run — install a JDK 17 or run in CI)\n' \
    "$INCONCLUSIVE"
  exit 1
fi
printf 'check-contract-v1: FAIL (%d check(s) failed' "$FAILURES"
[ "$INCONCLUSIVE" -gt 0 ] && printf ', %d inconclusive' "$INCONCLUSIVE"
printf ')\n'
exit 1
