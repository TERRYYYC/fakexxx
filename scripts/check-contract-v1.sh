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

DTOS="CapabilitySnapshotV1 EnvironmentIntentV1 PreflightRequestV1 PreflightReportV1 ApplyRequestV1 ApplyReceiptV1 ObserveRequestV1 EnvironmentObservationV1 ReleaseRequestV1 ReleaseReceiptV1"
for dto in $DTOS; do
  missing=""
  [ -f "$KT_DIR/$dto.kt" ] || missing="$missing .kt"
  [ -f "$AIDL_DIR/$dto.aidl" ] || missing="$missing .aidl"
  if [ -z "$missing" ]; then pass "$dto has .kt and .aidl"; else fail "$dto missing:$missing"; fi
done
for extra in ContractEnumsV1 ContractErrorCodeV1 CanonicalIntentDigestV1; do
  if [ -f "$KT_DIR/$extra.kt" ]; then pass "$extra.kt present"; else fail "$extra.kt missing"; fi
done
if [ -f "$AIDL_DIR/IEnvironmentControlV1.aidl" ]; then
  for method in discover preflight apply observe release; do
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
