package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeIdentityResolver
import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Reachability guard for the v1 provider.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The 614 unit tests on this branch are green, and every one of them enters the
 * provider through [EnvironmentControlHandler] with a fake environment. That is
 * the right shape for behavior tests, but it means the whole lane is blind to
 * the question CellRebel Auto actually asks at runtime:
 *
 *     "can I bind this service at all, and does it drive the real device?"
 *
 * Nothing in the behavior matrix touches [EnvironmentControlService] (the only
 * cross-app entry point, §6.1) or [QwyEnvironmentController] (the only path to
 * real System Mock / Hook / schedule capability). So both could stay `TODO()`
 * stubs forever while the board reads 614/614 — a false green that survives
 * exactly until the first real bind, where it becomes a crash instead of a
 * failing test.
 *
 * These cases are deliberately NOT §10 matrix rows: they carry no M-* ledger id
 * and assert no contract semantics. They assert that the surface the contract
 * semantics ride on exists. Behavior coverage and reachability coverage are
 * different claims, and the ledger should not blur them.
 */
class ProviderReachabilityGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("cannot locate the app module root from ${File(".").absolutePath}")

    private val manifest: File = File(moduleRoot, "src/main/AndroidManifest.xml")

    private val v1SourceDir: File =
        File(moduleRoot, "src/main/java/name/caiyao/fakegps/integration/v1")

    /**
     * §6.1 / spec §1183 / §1188: Auto binds an EXPLICIT ComponentName whose
     * class half is frozen as `name.caiyao.fakegps.integration.v1
     * .EnvironmentControlService`. An undeclared service is not "an
     * implementation detail pending" — it is unresolvable, so bindService()
     * returns false and Auto cannot distinguish it from "qwy not installed".
     *
     * exported=true is load-bearing for the same reason: the two apps are
     * separate uids, and the whole point of the contract is a cross-app call.
     * Every OTHER service in this manifest is exported=false on purpose (they
     * are internal), so inheriting that default here would be silently wrong.
     */
    @Test
    fun binderEntryIsDeclaredInManifestAndExportedForCrossAppBind() {
        val xml = manifest.readText()

        val declaration = Regex("""<service\b[^>]*?EnvironmentControlService[^>]*?>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?: Regex("""<service\b(?:(?!</?service)[\s\S])*?EnvironmentControlService[\s\S]*?/?>""")
                .find(xml)

        if (declaration == null) {
            fail(
                "EnvironmentControlService is not declared in AndroidManifest.xml. " +
                    "The contract's only cross-app entry point is unreachable: " +
                    "bindService() on the frozen ComponentName resolves to nothing."
            )
            return
        }

        val block = declaration.value
        assertTrue(
            "EnvironmentControlService must be android:exported=\"true\" — a cross-app " +
                "bind from CellRebel Auto (a different uid) cannot reach a non-exported " +
                "service. Declared as: $block",
            Regex("""android:exported\s*=\s*"true"""").containsMatchIn(block)
        )
    }

    /**
     * A `TODO()` in production is a runtime landmine, not a marker: it throws
     * NotImplementedError on the first real call. The handler lane is fully
     * implemented, so any remaining stub is concentrated precisely in the files
     * no behavior test can reach — which is why "all tests pass" and "all code
     * implemented" drifted apart on this branch in the first place.
     */
    @Test
    fun noProductionStubsRemainInTheV1IntegrationSurface() {
        val stubs = v1SourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { idx, line ->
                    val code = line.substringBefore("//")
                    if (Regex("""\bTODO\s*\(""").containsMatchIn(code)) {
                        "${file.name}:${idx + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertEquals(
            "production stubs left in the v1 integration surface — these throw " +
                "NotImplementedError on first real use and are invisible to the " +
                "behavior matrix:\n" + stubs.joinToString("\n"),
            emptyList<String>(),
            stubs
        )
    }

    /**
     * §6.4 / M-RC-03: the revision owner must be subscribed to relevant-change
     * sources, and subscribed ONCE.
     *
     * onOwnerProcessStart already installs that listener. Composition used to
     * install a second one right after it. With a stub adapter both calls merely
     * threw, so nothing was visibly wrong; with a real adapter the second
     * registration would replace the first with an identical-looking lambda and
     * leave two registrations racing to be the survivor — and a revision owner
     * reads as "wired" in either case, which is precisely the INV-08 false-trust
     * shape.
     *
     * A reviewer found it by reading the call chain. This asserts it instead.
     */
    @Test
    fun compositionSubscribesTheRevisionOwnerExactlyOnce() {
        val env = WiringProbeEnvironment()

        ProviderRuntime.compose(
            kv = InMemoryDurableKv(),
            clock = FakeMonotonicClock(),
            resolver = FakeIdentityResolver(),
            environment = env,
        )

        assertEquals(
            "the relevant-change listener must be installed exactly once — " +
                "zero means an unwired revision owner (INV-08), more than one " +
                "means registrations competing to be the survivor",
            1,
            env.listenerRegistrations
        )
    }

    /**
     * Minimal QwyEnvironment that answers everything blandly and counts listener
     * registrations. Deliberately not FakeQwyEnvironment: that one is the
     * behavior matrix's shared fixture, and adding an instrumentation counter to
     * it for one wiring assertion would couple this guard to it.
     */
    private class WiringProbeEnvironment : QwyEnvironment {
        var listenerRegistrations = 0

        override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
            listenerRegistrations++
        }

        override fun scheduleSnapshot(): ScheduleSnapshot? = null

        override fun advancePointer(fromItemId: String): AdvancePointerOutcome =
            AdvancePointerOutcome.Exhausted(versionAfter = 0L)

        override fun applyEnvironment(
            intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        ): ApplyOutcome = ApplyOutcome(
            effectiveLatitude = null,
            effectiveLongitude = null,
            deliveryModeWire = null,
            verificationLevelWire = 0,
        )

        override fun cleanup(leaseId: String): CleanupOutcome = CleanupOutcome.Complete

        override fun observeEffective(): EffectiveEnvironment = EffectiveEnvironment(
            latitude = null,
            longitude = null,
            isMock = null,
            deliveryModeWire = null,
            verificationLevelWire = 0,
            environmentFingerprint = "probe",
            evidenceRefs = emptyList(),
        )

        override fun scheduleDecisionWire(scheduleRef: String): Int = 0
    }

    /**
     * NOTE — why there is no "instantiate QwyEnvironmentController and probe it"
     * case here.
     *
     * An earlier draft did exactly that. Once the controller took a Context (it
     * must: every real capability it adapts is Context-bound), the probe could no
     * longer run in a JVM unit lane, and the honest options were to pull in
     * Robolectric or to drop it.
     *
     * Dropping it costs nothing, because it and
     * [noProductionStubsRemainInTheV1IntegrationSurface] were asserting the same
     * fact — "the adapter is still a stub" — and the source scan asserts it
     * without needing an Android runtime at all. Keeping both would have bought
     * one claim twice and paid for it with a runtime dependency.
     *
     * What genuinely CANNOT be asserted from a unit lane is whether the adapter,
     * once written, drives the device correctly. That is an instrumented /
     * on-device claim and belongs to #7 acceptance, not here. This file's scope
     * is reachability, and it should not pretend otherwise.
     */
}
