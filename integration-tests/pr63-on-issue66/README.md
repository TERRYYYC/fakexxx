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
JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk \
  integration-tests/pr63-on-issue66/run-host-gate.sh
```

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
**BLOCKED**; it must never be presented as a healthy production FULL result. The runner writes this
schema-v2 outcome as JSON under
`harness/build/reports/pr63-on-issue66/host-gate-receipt.json`.
