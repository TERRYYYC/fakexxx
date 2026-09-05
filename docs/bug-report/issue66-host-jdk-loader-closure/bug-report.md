---
feature_ids:
  - ISSUE-66
topics:
  - host-gate
  - java-runtime
  - supply-chain
  - macho
doc_kind: bug_report
created: 2026-09-05
status: fixed_pending_exact_head
github_issue: 66
---

# Issue #66 host JDK loader closure

## Diagnostic capsule

| Field | Result |
| --- | --- |
| Symptom | The registered macOS Homebrew JDK tree digest covered its Cellar directory, but native libraries could load user-controlled absolute paths below `/opt/homebrew/opt`. Linux CI also reproduced only its local tree, so a Darwin digest edit was not statically fixed on every host. |
| Evidence | `otool -L` showed external load commands in `libfontmanager.dylib`, `liblcms.dylib` and other native libraries. The initial regression run had 11 tests: 3 failures and 1 error, including acceptance of a synthetic external dylib and rejection of the replacement runtime/profile. |
| Root cause | The trust boundary hashed files and in-tree symlinks but did not parse native loader metadata; profile tests asserted identities but not the exact two-platform ID-to-tree-digest map. |
| Diagnostic strategy | Compare every Mach-O load command in the registered tree with an official Eclipse Temurin archive, then reproduce the escape with a minimal synthetic Mach-O fixture. |
| Timeout strategy | If the official archive did not form a closed loader graph, stop rather than extending the digest to Homebrew dependencies. |
| Warning strategy | Any unsupported/fat Mach-O, malformed bounded command table, external absolute load path, escaping loader path, unresolved in-tree `@rpath` target or profile-map drift fails closed. |
| User-visible change | The macOS host gate now requires the self-contained Eclipse Temurin profile; the former Homebrew Java home is intentionally refused. |
| Acceptance | Official asset SHA-256 matches, source and staged tree digests are identical, the runtime binding is executable, Homebrew is rejected, and the complete standalone Python security set is green without ADB. |

## Bug report

### Reporter and reproduction

Fresh-context review found the gap in PR #81. On macOS arm64, inspect the registered Homebrew JDK
with `otool -L`: its tree contains load commands for mutable paths such as
`/opt/homebrew/opt/harfbuzz/lib/libharfbuzz.0.dylib`. Before this repair,
`compute_jdk_tree_digest` still returned the registered digest because those bytes were outside the
walked tree.

### Root cause and repair

The validator now parses bounded thin Mach-O load-command tables before Java is executed. System
paths are restricted to normalized `/usr/lib` and `/System/Library` entries; embedded rpaths must
resolve within the JDK; loader-relative dependencies must resolve there directly; and rpath
dependencies must close against the complete reviewed Mach-O set. Universal binaries are rejected
because accepting only one slice would leave another loader graph unaudited. The checked-in
registry and a validator constant both bind the two supported profile IDs to their exact tree
SHA-256 values.

The replacement macOS runtime is Eclipse Adoptium's
[official jdk-17.0.20.1+1 release asset](https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz), asset SHA-256
`196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8`. Its deterministic JDK-tree
SHA-256 is `f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8`.

### Verification and remaining gate

The focused traversal-spoof regression was RED then GREEN. The Java validator has 20 passing
tests, the private stager has 9, and the Android SDK validator has 11, for 40/40 standalone Python
security tests. Both the safely extracted source and descriptor-copied private tree reproduce the
same macOS tree digest, and the staged runtime emits the expected Eclipse Adoptium binding. No ADB,
emulator or physical-device operation was performed. These are working-tree results until the
clean exact-HEAD host gate and independent review complete.

## Linux CI cache-container permissions (2026-09-05 follow-up)

### Reproduction and diagnosis

[Run 33961496689](https://github.com/TERRYYYC/fakexxx/actions/runs/33961496689) completed 9 of 10
jobs successfully, including both apps and codex-bench. Its host job downloaded and verified the
correct `17.0.20.1+1` archive, then the standalone validator rejected the cache root at
`visit_directory(root_fd, (), 0) -> require_safe_tree_entry`: group/other write permissions are
forbidden. This is a CI preparation defect, not a reason to admit a writable runtime tree.

The [exact runner-image setup](https://github.com/actions/runner-images/blob/ubuntu24/20260831.293/images/ubuntu/scripts/build/configure-environment.sh)
makes the tool cache writable. The pinned setup-java's tool-cache dependency creates its target
root and copies the archive children, rather than copying the archive root's `0755` mode. The
official archive and local reviewed source retain that `0755` root mode.

### First repair and local verification

The first repair checks the exact expected JDK path, every cache-container type and the resolved
physical path. Only then does it remove group/other write permission from the three fixed cache
ancestors and set the selected `x64` root to `0755`. These operations are non-recursive; JDK
descendants and unrelated cache entries are unchanged. The existing complete tree-digest and
runtime checks still run immediately afterward. No validator policy or download source changes.

Its Java-suite regression executes the workflow's actual shell step against five private
fixtures: valid, wrong JAVA_HOME, root symlink, parent symlink and non-directory root. It checks
that invalid inputs cause no writes and that valid normalization preserves every descendant and
unrelated payload/mode. The old workflow is RED; the reviewed candidate plus the existing Java
suite is 21/21 GREEN. The stager's 9 and Android validator's 11 checks are unchanged. The real Linux
rerun, rather than this fixture result, decides whether the CI preparation is fixed. Final exact-run
results and approval are recorded in [PR #81](https://github.com/TERRYYYC/fakexxx/pull/81).

### Missing `/opt` ancestor: reproduced follow-up

[Run 33964605023](https://github.com/TERRYYYC/fakexxx/actions/runs/33964605023), for branch commit
`0e89fa910fe579f9732562a351722b8532f22aaa` through merge reference
`a5f8f442ddce3d40305e831a6b0925e7a22d58b1`, passed the new normalization step and the complete
Linux JDK-tree digest regression. Its sole failure among the 21 Java tests was the real runtime
binding: the CLI returned 1 without diagnostic output before a binding was emitted. The first
repair therefore fixed the cache-tree root but did not establish safe path authority above it.

The same runner image's [configure-system.sh](https://github.com/actions/runner-images/blob/ubuntu24/20260831.293/images/ubuntu/scripts/build/configure-system.sh)
makes `/opt` itself world-writable. `validate_runtime` checks every resolved ancestor before it
hashes the tree or executes Java; `require_safe_path` rejects that mode at line 130. The first
normalization array began at `/opt/hostedtoolcache`, so it left `/opt` unchanged. A private fixture
using the actual workflow step reproduced this precise rejection; no JVM-output-filtering or
validator-policy change was needed.

The correction adds only the fixed `/opt` ancestor to the non-recursive normalization array. The
existing regression now maps the entire `/opt` prefix into its private temporary tree and covers
seven layouts: valid, wrong JAVA_HOME, JDK-root symlink, cache-parent symlink, non-directory JDK
root, `/opt` symlink, and non-directory `/opt`. Its snapshots cover the entire private fixture,
including renamed `.original` trees and unrelated siblings. Valid normalization must leave all
five selected containers at `0755` and pass the production `require_safe_path` check for each;
all other bytes and modes must remain unchanged. Invalid layouts must make no changes.

The old four-container workflow is RED against the ancestor fixture; the five-container candidate
is GREEN across the same seven layouts. This is local regression evidence, not a claim that the
real Linux runtime binding or complete CI is already green. The follow-up CI run and independent
review determine closure; the validator, pinned JDK source, and exact JVM challenge are unchanged.
