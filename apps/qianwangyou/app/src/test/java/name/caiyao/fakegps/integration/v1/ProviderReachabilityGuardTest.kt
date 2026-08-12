package name.caiyao.fakegps.integration.v1

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
     * [QwyEnvironmentController] is the production adapter: the seam where the
     * contract stops being arithmetic over fakes and starts moving a real
     * device. Instantiating it and calling the read-only accessors is enough to
     * prove it is not a stub — a NotImplementedError here means the provider
     * would answer every observe/advance with a crash.
     *
     * Read-only calls only: this test must not mutate device state.
     */
    @Test
    fun productionEnvironmentControllerIsNotAStub() {
        val controller = QwyEnvironmentController()

        val notImplemented = mutableListOf<String>()
        fun probe(name: String, call: () -> Unit) {
            try {
                call()
            } catch (e: NotImplementedError) {
                notImplemented += name
            } catch (e: Throwable) {
                // Any other throwable is a real (possibly environment-dependent)
                // failure, not the stub signature this guard is about.
            }
        }

        probe("scheduleSnapshot") { controller.scheduleSnapshot() }
        probe("observeEffective") { controller.observeEffective() }
        probe("scheduleDecisionWire") { controller.scheduleDecisionWire("schedule-probe") }
        probe("setRelevantChangeListener") { controller.setRelevantChangeListener { } }

        assertEquals(
            "QwyEnvironmentController still throws NotImplementedError — the provider " +
                "cannot observe or drive the real qwy environment: $notImplemented",
            emptyList<String>(),
            notImplemented
        )
    }
}
