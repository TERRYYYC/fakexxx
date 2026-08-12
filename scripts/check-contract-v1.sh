#!/usr/bin/env bash
#
# check-contract-v1.sh — gate for the frozen Environment Control contract v1.
#
# Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §6, §13 Task 2.
#
# Four things this proves, in order of how expensive they are to get wrong:
#
#   1. No Kotlin enum type appears inside any @Parcelize class. kotlin-parcelize
#      auto-encodes enums by name, so a constant added by a newer peer makes an
#      older reader throw IllegalArgumentException from the generated
#      createFromParcel — an unparcel crash inside a Binder transaction instead
#      of the typed fail-closed outcome INV-03 requires. Prose cannot enforce
#      this; a static check can.
#   2. compatibility.yaml and the Kotlin sources agree on every wire code, so the
#      machine-readable handshake surface cannot drift from the implementation.
#   3. Every DTO named in §6.3/§6.3.2 has both a .kt and a .aidl declaration.
#   4. The contract module compiles and its tests pass from BOTH app Gradle
#      roots, which is what makes the shared library claim real rather than
#      "it worked in Auto's build".
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
# Accept both ASCII and full-width parens, with or without backticks/spaces --
# the document uses every combination.
ref = re.compile(r'`?(' + '|'.join(sorted(truth, key=len, reverse=True)) +
                 r')`?\s*[（(](\d+)[）)]')

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
section "7. DTO fields agree between canonical §6.3 and Kotlin"

# Sections 5 and 6 bind the ERROR CODES and the METHOD names. Nothing bound the
# FIELDS, and the gap proved itself: three DTOs gained schedule identity in
# Kotlin while canonical's exact-schema snippets still showed the old field
# lists, and this gate stayed green through it.
#
# That is not a cosmetic mismatch. §6.3 says "a field not listed here is not part
# of v1", so an unlisted field is simultaneously shipped and denied -- the same
# contradiction P1 #2 created for methods, one level down.
if SPEC="$SPEC_PATH" KT_DIR="$KT_DIR" python3 - <<'PY'
import os, pathlib, re, sys

spec = pathlib.Path(os.environ["SPEC"]).read_text()
kt_dir = pathlib.Path(os.environ["KT_DIR"])

def fields(body):
    # `val name: Type` at property position; comments and KDoc are skipped by
    # requiring the line to start (after indent) with `val`.
    return set(re.findall(r"^\s*val\s+([A-Za-z0-9_]+)\s*:", body, re.M))

def classes(text):
    out = {}
    for m in re.finditer(r"data class\s+([A-Za-z0-9_]+V1)\s*\((.*?)\n\)\s*:\s*Parcelable", text, re.S):
        out[m.group(1)] = fields(m.group(2))
    return out

spec_c = classes(spec)
kt_c = {}
for p in kt_dir.glob("*V1.kt"):
    c = classes(p.read_text())
    kt_c.update(c)

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
    missing, extra = sorted(a - b), sorted(b - a)
    if missing or extra:
        fail = 1
        if missing: print(f"  FAIL  {name}: in spec but NOT in Kotlin: {missing}")
        if extra:   print(f"  FAIL  {name}: in Kotlin but NOT in spec: {extra}")
if fail == 0:
    total = sum(len(spec_c[n]) for n in shared)
    print(f"  PASS  {len(shared)} DTO(s), {total} field(s) identical in both directions")

sys.exit(fail)
PY
then
  pass "DTO field sets bound to canonical §6.3"
else
  fail "DTO field sets differ from canonical §6.3"
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
