---
feature_ids: [G2-66]
topics: [implementation-plan, qianwangyou, continuity-oracle, system-server]
doc_kind: implementation-plan
created: 2026-08-31
tips_exempt:
  reason: Internal fail-closed continuity infrastructure with no operator-facing action or UI.
---

# G2-66 Authoritative Continuity Oracle Implementation Plan

**Feature:** G2-66 — `docs/architecture/g2-66-authoritative-continuity-oracle.md`
**Goal:** QWY reports FULL only when one authoritative API-35 system-server oracle proves an unchanged PRE→raw GPS/network read→POST interval and the matching revision/ACK is durable.
**Acceptance Criteria:** Owner away→restore and provider disable→enable change sequence; concurrent mutation is rejected; boot/oracle change, sequence regression, missing hook/build/session, Binder failure, and read/ACK crash fail closed; same-coordinate refresh does not change sequence; public callbacks remain PARTIAL/NONE; a complete controlled source alone can reach FULL; exact normal/recovered advance correlation may commit the reserved receipt revision while uncorrelatable recovery quarantines that stale receipt and fails loud; exact-build emulator and authorized rooted-device proof remain deferred before production health.
**Architecture cell:** `fakexxx::android-dual-app-contract`
**Map delta:** none
**Map delta why:** The ownership map already assigns continuity production/revision to QWY and consumption to Auto; this change strengthens QWY's implementation without moving that boundary or changing the frozen Binder v1 wire.
**Architecture:** A legacy LSPosed system-server branch owns an odd/even oracle and passes its Binder to a system-only QWY registrar after boot phase 600. QWY double-snapshots that internal Binder around effective framework readback and atomically reconciles the stable proof with its existing durable revision owner. A correlation reservation prevents locally pre-accounted schedule advances from being counted twice.
**Tech Stack:** Kotlin/JVM, Java, Android AIDL/Binder, legacy Xposed API 82, FileDurableKv, JUnit 4
**前端验证:** No

---

## Finish line and exclusions

Finish line B is a draft implementation that is JVM-proven, builds as debug/release, keeps both
exact-build fingerprint lists empty, and cannot reach production FULL before separately reviewed
evidence-only validation and a later attestation-promotion change.

**Issue #66 AC7 status: NOT PASSED — quarantined/deferred.** Exact-build emulator and authorized rooted-device evidence are outside this implementation pass and must not be reported as complete.

This plan does **not** change Environment Control v1, merge/close issue #66, operate a real phone, claim rooted-device PASS, migrate to libxposed API 102, add a public system service, or treat polling/refresh as history.

## Terminal schema

```kotlin
data class AuthoritativeContinuitySnapshot(
    val protocolVersion: Int,
    val bootId: String,
    val oracleInstanceId: String,
    val sequence: Long,
    val ownerUid: Int?,
    val ownerPackage: String?,
    val gpsProviderEnabled: Boolean,
    val networkProviderEnabled: Boolean,
    val requiredCoverageMask: Long,
    val installedCoverageMask: Long,
    val health: OracleHealth,
    val qwySemanticDigest: String?,
    val lastCompletedQwyMutationId: String?,
)

fun interface AuthoritativeContinuitySource {
    fun snapshot(): AuthoritativeContinuitySnapshot?
}

data class AuthoritativeObservationWindow(
    val pre: AuthoritativeContinuitySnapshot?,
    val post: AuthoritativeContinuitySnapshot?,
    val windowStartElapsedRealtimeMs: Long,
)
```

Internal AIDL exposes synchronous snapshot, QWY session registration, and begin/finish semantic mutation. It is separate from `IEnvironmentControlV1`.

## Stateful object census

### S1 — system-server oracle journal

Owner: the one injected module instance in `system_server`.

| State | Event | Next state / output |
|---|---|---|
| uninitialized | validated boot ID + baseline | stable even `0`, health still gated by masks/build/session |
| stable even `n`, depth 0 | first mutation begin | odd `n+1`, depth 1 |
| odd, depth N | nested/concurrent begin | same odd, depth N+1 |
| odd, depth N>1 | finish | same odd, depth N-1; aggregate changed/uncertain |
| odd, depth 1 | finish changed | even `n+2`, resample endpoint, publish mutation correlation |
| odd, depth 1 | proved no-op | original even `n` |
| any | exception/underflow/overflow/resample failure | poison health; never treat as no-op |

### S2 — QWY oracle Binder registry/session

Owner: `OracleBridgeService`/process singleton.

| State | Event | Next state / output |
|---|---|---|
| absent | non-system registration | reject; absent |
| absent | UID 1000 registration | link death; connected-unbaselined |
| connected | QWY registers current generation/digest | active session |
| connected/active | Binder death/disconnect | absent; every read NONE |
| active | QWY process death token fires | system oracle session unhealthy until new explicit baseline |

### S3 — durable revision ACK/reservation

Owner: existing single `ContinuityTracker` over `REVISION_NAMESPACE`.

| Durable state | Event | Transactional result |
|---|---|---|
| ACK absent/old instance | valid window | bump + ACK + clear FULL; current observation NONE |
| ACK same instance, lower sequence | valid newer window | bump + ACK + establish window |
| ACK equals sequence | valid window | retain revision/window |
| ACK higher, same instance | regression | bump/degrade once; retain higher ACK |
| any | invalid window | clear FULL; evidence state recorded conservatively |
| reservation `(id, R, R+1)` | same starting boot/instance, sequence exactly `start+2`, exact id | CAS `R→R+1` + ACK + consume in one transaction |
| reservation during owner recovery | same starting identity, sequence exactly `start+6`, exact id | CAS `R→R+1` + ACK + consume; recovery fence makes first stable window NONE |
| reservation | healthy changed identity or unrelated sequence/id interleaving | retire at `R+2` + ACK current cursor + quarantine id + clear pending; stale `R+1` replay fails loud; first stable window NONE |
| reservation | missing/unhealthy oracle | do not ACK or retire; fail closed |

### S4 — QWY semantic mutation token

Owner: `QwySemanticMutationCoordinator`; token is remote and carries a client-death Binder.

| State | Event | Next state / output |
|---|---|---|
| idle | begin(id, digest) | remote token active; oracle odd |
| active | local commit + finish(changed, digest) | token retired; stable sequence |
| active | exception | finish uncertain; health poisoned if outcome cannot be proved |
| active | process death | oracle advances/poisons session; never apparently healthy |

## Invariants

- **INV-O1:** FULL requires identical PRE/POST protocol, boot ID, instance ID, stable even sequence, semantic digest, healthy state, exact coverage, unique effective QWY owner, and both providers enabled. Test: acceptance matrix unit cases.
- **INV-O2:** Each changed outer mutation advances stable sequence by exactly two; nesting/concurrency never exposes a stable intermediate state. Test: state-machine concurrency test.
- **INV-O3:** Same-coordinate refresh never invokes semantic begin and never changes stable sequence. Test: refresh-session regression test.
- **INV-O4:** Every committed oracle ACK and its required environmentRevision bump share one `DurableKv.transaction`. Test: fault-injected transaction/reopen.
- **INV-O5:** Boot/instance change and same-instance regression cannot inherit FULL. Test: durable reconciliation restart/regression cases.
- **INV-O6:** Public AppOps callback capability stays INCOMPLETE/UNAVAILABLE. Test: existing `QwyRelevantChangeMonitorTest` plus production source guard.
- **INV-O7:** Advance receipt and immediate observation share `R+1` only after exact normal (`start+2`) or exact owner-recovery (`start+6`) correlation on the same identity with the reserved mutation ID. A healthy uncorrelatable recovery retires at `R+2`, quarantines the stale `R+1` ID, and makes receipt replay fail loudly; no stale receipt is returned as success. Test: provider advance normal/crash/recovery/reboot/interleaving tests.
- **INV-O8:** Missing/ambiguous method, delegate, build, mask, bridge, baseline, endpoint, or callback success cannot produce healthy. Test: health truth table and static hook-plan tests.
- **INV-O9:** Only UID 1000 can register the oracle; snapshot/mutation calls accept only the resolved QWY UID, and Binder death clears authority. Test: Binder policy pure tests/static service guard.
- **INV-O10:** Frozen `contracts/environment-control-v1/**` is byte-unchanged. Test: `./scripts/check-contract-v1.sh` and git diff path guard.

## Adversarial matrix

| Scenario | Expected executable evidence |
|---|---|
| owner QWY→other→QWY between snapshots | PRE/POST sequence mismatch → NONE |
| GPS/network enabled→disabled→enabled | PRE/POST sequence mismatch → NONE |
| reader overlaps odd interval | odd PRE/POST → NONE |
| two concurrent writers | odd until final exit; one stable +2 commit |
| boot or system-server instance changes | first window bumps/ACKs and returns NONE |
| sequence rollback in same instance | sticky regression NONE |
| required hook/install/build missing | health not healthy; NONE |
| Binder dies before/after PRE | missing/throwing POST; NONE |
| crash before local ACK write | transaction rolls back ACK and bump; retry accounts change |
| crash after ACK transaction/response lost | ACK and bump both persist; retry does not lose/double-count |
| process dies with QWY mutation active | session poisoned; restart must rebaseline |
| advance crashes after receipt, before pointer convergence | pending ticket reuses correlation; exact same-identity `start+2` finalizes `R+1` before replay |
| owner dies during pending advance | death + explicit registration + replay yields exact same-identity `start+6`; finalize `R+1`; first stable window NONE |
| reboot/instance change or unrelated sequence interleaves reservation | retire at `R+2`, ACK current cursor, quarantine stale `R+1`; replay fails loudly; first stable window NONE, then recovery |
| many identical refresh ticks | stable sequence unchanged |

### Task 1: Pure oracle state and proof classifier

**Files:**
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/AuthoritativeContinuityOracle.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/AuthoritativeContinuityOracleTest.kt`

1. Write RED tests for away→restore, provider disable→enable, nested/concurrent mutation, no-op, odd observation, boot/instance mismatch, regression, missing mask/health/owner/providers, and identical valid window.
2. Run `./gradlew :app:testDebugUnitTest --tests 'name.caiyao.fakegps.integration.v1.AuthoritativeContinuityOracleTest' --no-daemon`; expect unresolved oracle types/behavior failures, not toolchain failure.
3. Add immutable snapshot/health/mask types, a synchronized journal with mutation tokens, and a pure window classifier implementing INV-O1/O2/O8.
4. Re-run the same command; expect PASS.

### Task 2: Durable ACK/revision reconciliation

**Files:**
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/ContinuityTracker.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/IntegrationTypes.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/AuthoritativeContinuityReconciliationTest.kt`
- Test support: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/Fakes.kt`

1. Write RED cases for first instance, sequence advance, same ACK, boot/instance change, regression, incomplete health, reservation match/mismatch, crash before ACK, and response-loss retry.
2. Run the single new test class; expect missing reconciliation API/keys.
3. Add ACK/reservation keys and transaction APIs for ordinary bump+ACK and exact reservation CAS+ACK, returning the exact `RevisionSnapshot` used by the observation. Never write ACK or its corresponding bump outside that transaction.
4. Re-run new tests plus `DurableKvTransactionContractTest`; expect PASS.

### Task 3: PRE/raw-read/POST observation integration

**Files:**
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentObserver.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt`
- Modify: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/ProviderHarness.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/AuthoritativeObservationProviderTest.kt`

1. Write RED provider tests that mutate the fake oracle during `observeEffective`, throw on PRE/POST, change boot/instance/sequence, and prove COMPLETE only for an identical valid window.
2. Run the test; expect current observer to reuse tracker coverage and incorrectly miss transitions.
3. Inject `AuthoritativeContinuitySource`, snapshot before/after raw read, classify, and reconcile before constructing the existing v1 observation. Public/no source maps to NONE/PARTIAL as frozen.
4. Re-run observation/freshness/reachability tests; expect PASS.

### Task 4: Semantic mutation coordinator and advance reservation

**Files:**
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwySemanticMutationCoordinator.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwySemanticWriterRuntime.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwySemanticDigestV1.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/PendingAdvanceTicket.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/EnvironmentControlHandler.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwyEnvironmentController.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/data/SpoofSettings.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/data/repository/ProfileRepository.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/QwySemanticMutationCoordinatorTest.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/QwySemanticWriterRuntimeTest.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/AuthoritativeAdvanceProviderTest.kt`

1. Write RED tests for client death/exception, identical semantic no-op, profile/mode/schedule/effective-coordinate mutation, central writer coverage, normal reserved advance, crash roll-forward reuse, exact owner recovery, and uncorrelatable reboot/interleaving quarantine.
2. Run coordinator and targeted advance cases; record expected failures.
3. Implement synchronous/suspending coordinator wrappers and one central writer runtime. Route authoritative settings, profile repository, config publication, and environment apply/converge/cleanup through it; nested calls join the outer correlation. Keep exact same-coordinate cadence publications excluded, but journal coordinate-bit changes at the API-35 provider-lock boundary.
4. Extend the pending advance marker with backward-compatible base/reserved revisions, mutation ID, and starting oracle identity. Replace the receipt-time bump with a reservation. Accept only same-identity `start+2` normal or `start+6` owner-recovery correlation for `R+1`; otherwise, for a healthy uncorrelatable current cursor, atomically retire at `R+2`, ACK it, quarantine the stale receipt ID, clear pending, and install the recovery fence. Missing/unhealthy proof remains pending and fail closed.
5. Re-run coordinator, advance, apply/release, settings, profile repository, and refresh tests; expect PASS and unchanged refresh sequence.

### Task 5: Internal Binder bridge

**Files:**
- Create: `apps/qianwangyou/app/src/main/aidl/name/caiyao/fakegps/oracle/IAuthoritativeContinuityOracle.aidl`
- Create: `apps/qianwangyou/app/src/main/aidl/name/caiyao/fakegps/oracle/IContinuityOracleRegistrar.aidl`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/oracle/OracleBundleCodec.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/oracle/OracleClientRegistry.kt`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/oracle/OracleBridgeService.kt`
- Modify: `apps/qianwangyou/app/src/main/AndroidManifest.xml`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/oracle/OracleBundleCodecTest.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/oracle/OracleBridgePolicyTest.kt`

1. Write RED codec/policy tests for missing/unknown fields, protocol mismatch, UID 1000 registration, exact QWY caller, and Binder death clearing.
2. Run those tests; expect missing AIDL/codec/registry.
3. Add the private Bundle schema, registry death handling, and an explicit exported/no-filter service whose registrar rejects non-system callers. Keep it in the main process.
4. Run codec/policy tests and `assembleDebug`; expect PASS.

### Task 6: Legacy LSPosed system-server producer

**Files:**
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java`
- Create: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlan.java`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/hook/MainHook.java`
- Modify: `apps/qianwangyou/app/src/main/AndroidManifest.xml`
- Create: `apps/qianwangyou/app/src/main/res/values/xposed_scope.xml`
- Modify: `apps/qianwangyou/app/proguard-rules.pro`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/hook/oracle/Android15OracleHookPlanTest.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleWiringGuardTest.kt`

1. Write RED static/pure tests for exact `(android,android)` early return, `system` scope, API-35 Access Checking wrapper+delegate+lifecycle symbols, location enabled symbol, phase-600 bridge, empty evidence/attestation lists, exact masks, and callback-poison behavior.
2. Run targeted tests; expect missing hook plan/branch/resources.
3. Add the dedicated early branch and exact resolver manifest. Classify exact fingerprints as
   `UNLISTED`, `EVIDENCE_ONLY`, or `ATTESTED`. Unlisted builds return before Binder construction or
   hooks. Evidence-only builds may expose specific runtime failures but never set the attestation
   bit or exceed `EVIDENCE_ONLY_READY`; only attested builds can become healthy. Keep both lists
   empty in this implementation pass.
4. Add phase-600 explicit service binding, kernel boot-ID validation, effective owner/provider sampling, locked Bundle snapshot, UID checks, and internal callback poison handling.
5. Re-run hook tests and debug/release assemble; expect PASS. Inspect release mapping/DEX guards so R8 keeps the AIDL stubs and hook entry.

### Task 7: Production composition and no-false-FULL guards

**Files:**
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/ProviderRuntime.kt`
- Modify: `apps/qianwangyou/app/src/main/java/name/caiyao/fakegps/integration/v1/QwyRelevantChangeMonitor.kt`
- Modify: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/QwyActualReadbackWiringTest.kt`
- Test: `apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/AuthoritativeOracleProductionGuardTest.kt`

1. Write RED source/composition guards proving production observer reads the registry, public callbacks remain INCOMPLETE, required coverage is exact, both admission lists are empty, evidence-only health collapses to `BUILD_UNATTESTED`, and refresh does not call semantic mutation.
2. Run targeted guards; expect missing composition.
3. Wire registry source/coordinator at the one ProviderRuntime composition root; registration/death/startup failure stays NONE.
4. Re-run all QWY JVM tests; expect PASS.

### Task 8: Verification, provenance, and handoff

**Files:**
- Update: `project-research/2026-08-31-authoritative-continuity-oracle/synthesis.md`
- Update: `docs/architecture/g2-66-authoritative-continuity-oracle.md`

1. Run targeted oracle/advance/refresh tests with JDK 17 and the configured Android SDK; expect PASS.
2. Run `./gradlew testDebugUnitTest assembleDebug assembleRelease --no-daemon` from `apps/qianwangyou`; expect PASS.
3. Run `./scripts/check-contract-v1.sh`; expect PASS and no contract diff.
4. Run risk-matched repository checks from the quality gate; record inherited/deferred failures separately and never call them feature green.
5. Confirm both exact fingerprint lists are still empty and record that no real phone was operated and no real-device PASS is claimed.
6. Commit with the required `Thread-Context: threadId=01a0551d-0e1b-7a11-abbb-c3be783df747 catId=codex` footer.
7. Obtain a non-author fresh-context review, fix findings through RED→GREEN, and create/update a draft PR linked to #66. Do not merge or close the issue.

## Open questions

All remaining questions are technical and fail closed: exact target fingerprint/method set, target SELinux/system scope, bridge reconnect behavior, read-only late-bridge detection, and vendor mutation paths. They are resolved first by a separately reviewed `EVIDENCE_ONLY` exact-build change and authorized device evidence, then by a distinct independently reviewed promotion to `ATTESTED`. The read-only collector only emits `STOP_LATE_BRIDGE`; any diagnostic registration or controlled process restart is a separate component requiring itemized additional authorization. That diagnostic lane must not relax production semantic registration, stable-complete, ACK, FULL, or Auto trust. None permits a non-empty list in this pass.
