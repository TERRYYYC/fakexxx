---
feature_ids: [G2-66]
topics: [codex-bench, android, isolated-validation]
doc_kind: implementation-plan
created: 2026-09-02
---

# codex-bench Implementation Plan

**Feature:** Isolated validation packaging for Issue #66, based on PR #69 at `bc62767c626eb247b64b17d97ff82c807262c5b8`.
**Goal:** Produce two separately installable, distinctly named codex-bench debug APKs without replacing the Moto's existing apps or widening production trust.
**Acceptance Criteria:** Both app labels include `codex-bench`; application IDs are `name.caiyao.fakegps.codexbench` and `com.example.cellrebelauto.codexbench`; default debug/release identities remain unchanged; codex-bench Auto only accepts its pinned codex-bench provider; every pairing still checks the actual application ID and signer; new artifact evidence is measured independently; no device FULL claim.
**Architecture cell:** Existing Android build packaging and Auto provider-principal selector; no ownership map exists for this external repository.
**Map delta:** none
**Map delta why:** Add an explicit isolated debug build type using the existing composition boundary, without adding a production API or migrating data.
**Architecture:** Add `codexBench` build types inheriting debug signing and debug-only probes. Use a compile-time CODEX_BENCH flag in the existing variant adapter and one closed provider-principal selector. Keep the frozen v1 contract constants and production fingerprint allowlist unchanged.
**Tech Stack:** Android Gradle Plugin, Kotlin, JUnit/Robolectric, existing shell gates and Android APK inspection tools.
**前端验证:** Android launcher/application labels verified from compiled APK resources and, before device acceptance, observed on the authorized device. No web UI.

## Scope and safety

- User selected `codex-bench` as the isolated build identifier after reporting that current app names must not collide.
- User authorized only Moto `ZY22JHW9M4`; prior read-only inventory found both existing app processes alive and old Auto bound to old QWY bench. No existing app may be overwritten, stopped, cleared, or have its pairing/data migrated in this work.
- `codex-bench` alone is not interpreted as permission to pause existing automation. Installing a new identity does not isolate system mock providers or LSPosed system-server hooks; those mutations require a coordinated idle validation window.
- Current `ATTESTED_FINGERPRINTS` is empty and the oracle installer returns before installing hooks. This work does not bypass that gate and cannot satisfy #66 FULL/AC7 by itself.
- Main has unrelated user changes. This plan lives with its isolated implementation branch, not on the team's shared main. No merging or main mutation is included.

## Lifecycle census and invariants

Build selection is a pure projection, not a new stored state. Each new Android application gets a separate existing trust store/database lifecycle:

| State | Event | Result / owner |
| --- | --- | --- |
| Absent | Install codex-bench | Fresh independent Android sandbox; package manager owns installation |
| Unpaired | Handshake | Refused; neither side silently inherits old pairings |
| Unpaired | Explicit ID+signer approval | Paired only in the new app's store |
| Paired | Wrong ID/signer or unavailable provider | Fail closed, no fallback to existing apps |
| Paired | Reopen | Existing durable approval semantics, same isolated app identity |

- INV-1: Old package IDs and all default debug/release identities stay unchanged (compiled manifest comparison).
- INV-2: Every codex-bench label is distinct across locales and both APKs (compiled badging check).
- INV-3: Codex Auto selection and known IDs are exactly the pinned codex QWY ID; old/unknown IDs are rejected (behavior tests, explicit Binder target tests).
- INV-4: Standard variants reject codex ID; release cannot enable codex via a runtime override (variant tests and source mutation guards).
- INV-5: Authority and signature permission names follow the application ID while component class names keep their original namespace (compiled manifests).
- INV-6: No oracle attestation, signing trust, old app data, or frozen contract constant changes (diff check + existing regression gates).

Adversarial cases: selecting codex with a non-debug flag must fail; missing codex provider must not bind its old sibling; forged/old principal must fail before external effects; process reopening cannot change the compile-time target. No new migration or shared persistent state is introduced.

## Task 1 — Packaging (delegated, disjoint files)

Files: both app build files, main manifests, and a compiled APK isolation checker.

1. Write and run an APK checker against the existing debug artifacts; observe RED for the old IDs/labels.
2. Add explicit `codexBench` types with debug signing, `.codexbench` suffixes, unique literal labels, and reused debug probes. Preserve existing task names and artifact paths for debug/release.
3. Auto gets CODEX_BENCH=false in ordinary builds and true only in codexBench, plus a manifest query for the pinned codex provider. No arbitrary Gradle identity override.
4. Assemble with each app's `./gradlew --no-daemon :app:assembleCodexBench`; inspect package, labels, authorities, permissions, debug probes, debuggable bit and signer.

## Task 2 — Closed principal routing (main agent)

Files: ProviderPrincipal.kt, debug/release ProviderPrincipalBuild.kt, provider-principal behavior tests, scripts/check-principal-routing.sh and its selftest.

1. Add failing tests for codex selection, singleton known identity, rejected old IDs, invalid release+codex combination and default variant preservation.
2. Add the compile-time adapter flag and extend the one pure resolver; no generic override or fallback.
3. Update the existing structural guard to pin both flags; preserve all old mutation cases and add a codex flag bypass case.
4. Run `:app:testDebugUnitTest` and targeted `:app:testCodexBenchUnitTest` for routing, default composition, Binder identity and principal validation.

## Task 3 — Verification and handoff

1. Run `scripts/selftest-principal-routing.sh`, `scripts/check-principal-routing.sh apps/cellrebel-auto/app`, `scripts/verify-a-plus.sh`, the compiled codex APK checker, and `git diff --check`. These are the repository's existing shell/Gradle verification entry points; no unconfigured formatter is assumed.
2. Obtain a non-author review of the exact isolated branch diff and evidence. Fix actionable findings before installing.
3. Capture new HEAD, APK SHA-256, signer, labels and exact IDs. Old emulator evidence does not cover these new bytes.
4. Device operations remain serial-qualified and limited to the authorized Moto. Re-read current state before installation; do not pause old automation or alter global mock/LSPosed state without resolving the live-run boundary.
5. Keep #66 open and PRs unmerged; separately report packaging completion versus physical oracle acceptance.
