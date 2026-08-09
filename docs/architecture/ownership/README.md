---
feature_ids: []
topics:
  - ownership
  - architecture
  - android-dual-app-contract
doc_kind: ownership_map
created: 2026-08-09
status: active
---

# Ownership map — `fakexxx::android-dual-app-contract`

Architecture cell established by
[`feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md`](../../../feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md).
This document answers two questions and nothing else: **who owns each runtime
responsibility**, and **who may write which files in parallel**.

## 1. Runtime authority boundaries (spec §5)

The two apps keep separate package names, separate builds and separate
releases. They collaborate only through a device-local, authenticated,
versioned Binder/AIDL contract.

| Boundary | CellRebel Auto owns | Qianwangyou owns | Forbidden |
|---|---|---|---|
| Address list / quota | plan order, per-address trusted quota | — | Qianwangyou counting CellRebel runs for Auto |
| profile / schedule | a stable reference and the plan snapshot only | sole authority; resolves the effective policy | Auto copying or reinterpreting the internal rules |
| Hook / System Mock | requests intent, consumes evidence | sole implementation and mode authority | Auto starting/stopping the provider, writing its prefs, or driving it through UI |
| CellRebel | sole executor and completion judge | — | Qianwangyou inferring CellRebel completion |
| Continuity | consumes and validates pre/post | produces a revision that must change on any relevant change, plus a coverage claim | using a heartbeat as continuity evidence |
| Trusted counting | the single transactional ledger | supplies verification evidence only | Hook or unknown evidence entering the trusted ledger |
| Recovery | attempt / execution / ledger owner | operation receipt / lease / revision owner | either side mutating the other's state directly |
| Logging | plan, CellRebel, decision, recovery | caller, environment operation, observation, release | recording pairing secrets, or treating logs as the state of record |

The load-bearing consequence: **a heartbeat proves a process is alive, never
that the environment did not change.** Trusted quota may only be credited when
independent verification and full continuity both hold.

## 2. File write ownership (spec §12.1)

Exclusive write scope. "Readable" means read-only reference; only the owner
commits changes to those paths.

| Owner | Exclusive write scope | May read | Must not touch in parallel |
|---|---|---|---|
| **Opus5** | `contracts/**`, `apps/cellrebel-auto/**`, root CI and `scripts/**`, this ownership map. Both apps' Gradle contract wiring **only inside the serial contract PR (PR-2)** | whole repo | `apps/qianwangyou/**` once PR-3 starts |
| **Kimi** | `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/**` (including its UI), the matching Qianwangyou tests, and the integration lines of the Qianwangyou Manifest / `build.gradle` | the frozen contract | `contracts/**`, `apps/cellrebel-auto/**`, `acceptance/**` |
| **Sol** | `acceptance/**`, `docs/acceptance/**`, acceptance issues and evidence | the contract and both apps | Opus5 and Kimi product implementation |
| **GLM** | review verdicts and adversarial execution reports; any test code it adds goes in a separate PR | whole repo | the author branch currently under review |

Parallelism is only valid **after the contract PR's exact HEAD is frozen**. At
that point the Auto consumer, the Qianwangyou provider and the acceptance
fake/scenarios occupy three non-overlapping directories. Any contract delta
stops all three and returns to the implementation main Thread for a new freeze —
the three lanes must not each invent their own compatibility.

## 3. Build and release independence (INV-19)

- `apps/cellrebel-auto` and `apps/qianwangyou` are **independent Gradle roots**,
  each with its own wrapper. Neither is a Gradle subproject of the other, and
  the repository root deliberately has no aggregating `settings.gradle*`.
- CI runs them as **separate jobs with no dependency edge**, so a green run on
  one never implies the other is releasable and neither can block the other's
  release.
- Cross-version compatibility is decided at runtime by the `discover()`
  handshake, not by building the two apps together.

## 4. Boundaries that are enforced mechanically, not by convention

| Gate | Enforces |
|---|---|
| `scripts/check-provenance.sh` | vendored trees are byte-identical to the recorded upstream SHAs |
| `scripts/check-contract-v1.sh` *(PR-2, Opus5)* | contract v1 exact schema and compatibility matrix |
| `scripts/check-forbidden-boundaries.sh` *(PR-5, Sol)* | Auto never writes Qianwangyou storage and never drives it through UI automation (INV-01/20) |
| `scripts/verify-a-plus.sh` | runs the gates required at a named stage; a missing required gate fails rather than being skipped |

## 5. Current state of the cell

| Component | Status | Owner / PR |
|---|---|---|
| Vendored baselines + provenance + CI | present at this HEAD | Opus5 / PR-1 |
| `contracts/environment-control-v1/**` | not yet created | Opus5 / PR-2 |
| Qianwangyou provider `integration/v1/**` | not yet created | Kimi / PR-3 |
| Auto trusted ledger, recovery, A+ template | not yet created | Opus5 / PR-4 |
| `acceptance/**` fake provider and matrices | not yet created | Sol / PR-5 |
| Dual-app integration + device evidence | not yet created | Sol / PR-6 |

No cat merges. Every PR stops at `ready for operator decision`.
