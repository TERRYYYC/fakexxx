---
feature_ids: ["PR-63", "ISSUE-66"]
topics: ["durable-provider-principal", "authoritative-continuity", "host-integration"]
doc_kind: "integration-gate"
created: "2026-09-01"
---

# PR 63 on issue 66 host integration gate

This test-only Gradle root loads the real CellRebel Auto release consumer and the real Qianwangyou
debug production-handler artifact into one host JVM, with Qianwangyou's canonical fake
oracle/environment support. The transport is a local Binder adapter with an injected UID/signer
resolver. Qianwangyou
decisions still run through `EnvironmentControlHandler` and its authoritative oracle; Auto recovery
still runs through its production registry, Binder executor, Room backend, composition root, and
engine factory.

The dynamic lane does not instantiate either Android Service. Exact source/mutation prerequisites
pin Auto's durable `(P,S) -> guard -> acquire` wiring and Qianwangyou's six AIDL-method mappings plus
`Binder.getCallingUid()` lookup; the behavior tests then exercise their real downstream composition.

Run the zero-argument gate with the repository-pinned Gradle wrapper:

```sh
JAVA_HOME=/path/to/registered-jdk17 ANDROID_HOME=/path/to/android-sdk \
  integration-tests/pr63-on-issue66/run-host-gate.sh
```

`JAVA_HOME` is not an arbitrary Java 17 installation. The checked-in registry currently admits
exactly two reviewed profiles: macOS arm64 Eclipse Temurin
`darwin-aarch64-eclipse-temurin-17.0.20.1+1` with JDK-tree SHA-256
`f89313615112db89abbaf64f7c5769432f3450e2c2d6059144e14b11104413d8`, and Linux x86_64
Eclipse Temurin `linux-x86_64-eclipse-temurin-17.0.20.1+1` with JDK-tree SHA-256
`427182064043c17bb698c7f9c5949f755f6dd80dddaf760b6fa7413178189a97`.
The macOS tree comes from the official Adoptium aarch64 archive whose SHA-256 is
`196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8`; unlike the superseded
Homebrew tree, every non-system Mach-O dependency remains inside the reviewed JDK tree.
The host runner stages the selected JDK privately, requires both the Gradle VM and test launcher to
be Java 17, and shares one newly created per-run Gradle home across its Auto, QWY and harness test
phases. The outer `verify-a-plus.sh` aggregate stages the JDK in its own private root and gives each
of its twelve manifest gates a different isolated Gradle home.

The Android validator binds only the AGP 9.1 trust-computing-base inputs selected from
`platforms/android-35`, `build-tools/36.0.0` and `platform-tools`, plus their safety-checked
ancestors. That binding proves stable, safely owned AGP inputs; it is not provenance for every byte
elsewhere in the SDK. The Ubuntu 24 CI job separately freezes the entire preinstalled SDK to
root-owned, non-writable state before running any repository command.

The gate covers a release consumer following a manually seeded durable bench tuple after a file
Room database close/reopen, null/foreign/mixed principal fail-closed behavior, signer rotation,
release identity/idempotency, and Qianwangyou's exact `R+1`, owner-recovery `R+1`, and quarantine
`R+2` paths. It also runs the canonical Auto principal-routing and Qianwangyou production-oracle
guard suites.

## Evidence boundary

This is host evidence only. It does not exercise PackageManager identity, kernel Binder calling
identity, Android Service lifecycle, OS process death, a real debug-to-release build switch, two
installed APKs, LSPosed, an emulator, or a physical device.
Both `EVIDENCE_ONLY_FINGERPRINTS` and `ATTESTED_FINGERPRINTS` intentionally remain empty, so every
real build is unlisted and production health is `BUILD_UNATTESTED`. The source guard also proves
that a future evidence-only runtime cannot set the attestation bit and is collapsed to
`BUILD_UNATTESTED` before continuity or Auto trust.

The Moto authorization is not absent: it covers the named device, two non-colliding debug APKs,
mock-location configuration, LSPosed scope inspection/configuration, and cleanup of that test
state. This host-only gate neither consumes that authorization nor produces device evidence, so
its receipt reports `physicalDevice=NOT_RUN`, not `BLOCKED_NO_AUTHORIZATION`. Activation and cleanup
reboots/soft reboots, global Location/provider toggles (which are not implied by mock-location
authorization), process force-stop/crash/restart, and adversarial device mutations still require
separate, itemized authorization. A host pass therefore leaves device/FULL and overall status as
**BLOCKED**; it must never be presented as a healthy production FULL result. The runner writes a
schema-v4 JSON receipt under
`harness/build/reports/pr63-on-issue66/host-gate-receipt.json`. Its exact 19 keys are
`schemaVersion`, `sourceHead`, `sourceTree`, `sourceState`, `runnerSha256`, `runId`, `jdkProfileId`,
`jdkRuntimeVersion`, `jdkTreeSha256`, `gradleAttestationAutoSha256`,
`gradleAttestationQwySha256`, `gradleAttestationHarnessSha256`, `hostIntegration`, `issue66Ac7`,
`emulator`, `physicalDevice`, `deviceFull`, `overall`, and `reason`.

A PASS receipt is accompanied by three mode-`0600` schema-v2 files named
`gradle-attestation-{auto,qwy,harness}-$runId.txt`. Each contains exactly 15 ordered lines:
`schemaVersion`, `runId`, `stage`, `taskPath`, `jdkHome`, `jdkProfileId`, `javaVendor`,
`javaVmVendor`, `jdkRuntimeVersion`, `jdkTreeSha256`, `jdkMajor`, `testLauncherMajor`, `testCount`,
`failureCount`, and `classes`. The aggregate consumer opens each sibling no-follow, validates and
re-reads stable bytes, checks its SHA-256 against the receipt, and binds run/JDK/task/stage/classes
to the same execution. The receipt is invalid while its sibling lock exists. Even after clean
release, a copied standalone receipt does not authorize a device run: authority requires its exact
commit and CI-artifact association plus the separately recorded independent-review verdict.

The two authorized future install targets remain non-colliding:
`name.caiyao.fakegps.codexbench` / `千网游 · codex-bench` launches
`.ui.ComposeActivity`, while `com.example.cellrebelauto.codexbench` /
`CellRebel Auto · codex-bench` launches `.ui.MainActivity`. Both fingerprint lists are still empty,
so production remains `BUILD_UNATTESTED`. Current author-side evidence is: the complete local
harness 15 suites / 141 tests with zero failures, errors or skips; the three main boundary classes
54 + 21 + 42 = 117 tests, plus 2 `HostEphemeralCleanupGuardTest` tests, for 119 related guard tests;
the three standalone Python runtime-security suites 40/40; and services compatibility 131/131.
The earlier collector result was 1718/1718; it predates the final process/environment and argv-budget
repairs and is not evidence for them. Their complete rerun belongs to the clean exact-commit gate.
These are host-only results produced with `ADB=/usr/bin/false`; no ADB,
emulator or physical-device run occurred. A clean exact-commit gate and independent review remain
required.
