package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source boundary complements the executable producer lifecycle tests, not device evidence. */
class CurrentOracleWiringTest {
    private val root = sequenceOf(File("."), File("app"), File("../app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `system server enters its producer before generic hooks and cannot fall through`() {
        val main = File(root, "src/main/java/name/caiyao/fakegps/hook/MainHook.java").readText()
        val entry = main.indexOf("SystemServerOracleEntryPolicy.isSystemServer(lpparam.packageName, lpparam.processName)")
        val normal = main.indexOf("RuntimeSelfHookPolicy.shouldHook(")
        assertTrue("missing real system-server entry before normal app hooks", entry >= 0 && entry < normal)
        val branch = main.substring(entry, normal)
        assertTrue(branch.contains("SystemServerOracleInstaller.install(lpparam.classLoader)"))
        assertTrue(branch.contains("return;"))
    }

    @Test
    fun `legacy module advertises system scope and existing in-process UID-gated bridge`() {
        val manifest = File(root, "src/main/AndroidManifest.xml").readText()
        assertTrue("missing system scope metadata", manifest.contains("android:resource=\"@array/xposed_scope\""))
        val scope = File(root, "src/main/res/values/xposed_scope.xml")
        assertTrue("missing system scope resource", scope.isFile)
        assertTrue(scope.readText().contains("<item>system</item>"))
        assertTrue(manifest.contains(".oracle.OracleBridgeService"))
        val bridge = File(root, "src/main/java/name/caiyao/fakegps/oracle/OracleBridgeService.kt").readText()
        assertTrue(bridge.contains("Binder.getCallingUid()"))
        assertTrue(bridge.contains("OracleBridgePolicy.acceptsRegistrarCaller(callingUid)"))
        val consumer = File(root, "src/main/java/name/caiyao/fakegps/integration/v1/ProviderRuntime.kt").readText()
        assertTrue(consumer.contains("authoritativeSource = BinderAuthoritativeContinuitySource()"))
    }

    @Test
    fun `installer refuses unattested builds before producer construction and uses explicit registrar`() {
        val installer = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java").readText()
        val planGate = installer.indexOf("SystemServerOraclePlanGate.resolvePlan(")
        val inertBranch = installer.indexOf("if (plan == null)")
        val construction = installer.indexOf("oracleBinder = SystemServerOracleBinder.create(")
        assertTrue("plan resolution must gate producer construction", planGate >= 0 && planGate < construction)
        assertTrue("inert null-plan branch must sit between gate and construction", inertBranch > planGate && inertBranch < construction)
        assertTrue(installer.contains("SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(phase)"))
        assertTrue(installer.contains("new ComponentName(BuildConfig.APPLICATION_ID, BRIDGE_SERVICE_CLASS)"))
        assertTrue(installer.contains("registrar.registerOracle(oracleBinder)"))
        assertTrue(installer.contains("onBridgeBindingDied(connectionGeneration)"))
        assertTrue(installer.contains("context.unbindService(this)"))
        val binder = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java").readText()
        assertTrue(binder.contains("new SystemServerOracleState("))
        assertTrue(binder.contains("Binder.getCallingUid()"))
        assertTrue(binder.contains("OracleBundleCodec.encode(state.snapshot())"))
        assertTrue(binder.contains("plan.attests(buildFingerprint, buildIncremental)"))
    }

    @Test
    fun `actual bridge adapter publishes non null context before enabling state sampling`() {
        val binder = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java").readText()
        val connection = binder.substringAfter("void onBridgeConnected(Context context, long generation)")
            .substringBefore("void onBridgeDisconnected(")
        val contextAt = connection.indexOf("systemContext = java.util.Objects.requireNonNull(context,")
        val readyAt = connection.indexOf("state.onBridgeConnected(generation)")
        assertTrue(contextAt >= 0 && readyAt > contextAt)
        assertTrue(binder.contains("() -> AndroidOracleEndpointReader.sample(systemContext)"))
        val installer = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java").readText()
        assertTrue(installer.contains("oracleBinder.finishCoveredMutation(token, uncertain)"))
        assertTrue(installer.contains("oracleBinder.onBridgeConnected(context, connectionGeneration)"))
    }

    /**
     * #194 source boundary: every phase-600 bridge registration failure path (bind rejected,
     * bindService threw, null binding, registerOracle failure, bound-but-never-connected
     * watchdog) must feed the backoff retry budget instead of ending in a silent poison.
     */
    @Test
    fun `every bridge registration failure path feeds the backoff retry budget`() {
        val installer = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java").readText()
        val policy = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/BridgeBindRetry.java").readText()

        // Five feeders: bindService threw + bind rejected + null binding + registerOracle
        // failure + never-connected watchdog. BridgeBindRetry itself owns the sixth (definition).
        assertEquals(5, installer.split("onRegistrationFailed(").size - 1)
        assertTrue(
            "registration success must reset the retry budget",
            installer.contains("bridgeRetry(context).onRegistrationSucceeded()"),
        )

        val bind = installer.substringAfter("private static void bindBridge")
        val boundCheckAt = bind.indexOf("if (!bound)")
        val watchdogPostAt = bind.indexOf("MAIN_HANDLER.postDelayed(connectWatchdog")
        assertTrue(
            "watchdog may only be armed after bindService actually accepted the bind",
            boundCheckAt in 0 until watchdogPostAt,
        )
        val connected = bind.substringAfter("onServiceConnected")
        val registeredAt = connected.indexOf("REGISTERED_GENERATION.set(connectionGeneration)")
        val stateUpdatedAt = connected.indexOf("oracleBinder.onBridgeConnected(context, connectionGeneration)")
        assertTrue(
            "watchdog retirement must be ordered before the state transition it protects",
            registeredAt in 0 until stateUpdatedAt,
        )
        // #195 review (Medium): the framework's onBindingDied rebind must reset the retry
        // budget before rebinding — a post-give-up rebind used to fail with zero logs and
        // zero retries. The reset must sit inside the onBindingDied branch, ahead of the
        // rebind call, and must not touch the generation guards.
        val died = bind.substringAfter("public void onBindingDied(ComponentName name)")
        val resetAt = died.indexOf("bridgeRetry(context).resetBudget()")
        val rebindAt = died.indexOf("bindBridge(context)")
        assertTrue(
            "framework binding-death rebind must start from a fresh budget",
            resetAt in 0 until rebindAt,
        )
        assertTrue(
            "budget reset must not bump generation guards (stale-retry protection stays)",
            !died.substringBefore("bindBridge(context)").contains("GENERATION.set"),
        )
        assertTrue(
            "retry budget must keep the 1s/5s/30s escalation",
            policy.contains("{1_000L, 5_000L, 30_000L, 30_000L}"),
        )
    }
}
