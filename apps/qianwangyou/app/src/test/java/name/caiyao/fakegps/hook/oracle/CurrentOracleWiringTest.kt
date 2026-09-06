package name.caiyao.fakegps.hook.oracle

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
        val platformGate = installer.indexOf("if (!supportedPlatform)")
        val buildGate = installer.indexOf("if (!buildAttested)")
        val construction = installer.indexOf("oracleBinder = SystemServerOracleBinder.create(")
        assertTrue(platformGate >= 0 && platformGate < construction)
        assertTrue(buildGate >= 0 && buildGate < construction)
        assertTrue(installer.contains("SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(phase)"))
        assertTrue(installer.contains("new ComponentName(BuildConfig.APPLICATION_ID, BRIDGE_SERVICE_CLASS)"))
        assertTrue(installer.contains("registrar.registerOracle(oracleBinder)"))
        assertTrue(installer.contains("onBridgeBindingDied(connectionGeneration)"))
        assertTrue(installer.contains("context.unbindService(this)"))
        val binder = File(root, "src/main/java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java").readText()
        assertTrue(binder.contains("new SystemServerOracleState("))
        assertTrue(binder.contains("Binder.getCallingUid()"))
        assertTrue(binder.contains("OracleBundleCodec.encode(state.snapshot())"))
        assertTrue(binder.contains("attested && Android15OracleHookPlan.isFingerprintAttested(fingerprint)"))
    }
}
