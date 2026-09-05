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
