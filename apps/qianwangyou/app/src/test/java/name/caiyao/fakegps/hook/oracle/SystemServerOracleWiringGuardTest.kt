package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SystemServerOracleWiringGuardTest {

    private val moduleRoot: File = sequenceOf(File("."), File("app"), File("../app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("cannot locate qianwangyou app module")

    @Test
    fun `MainHook takes exact system-server branch before self-hook policy and returns`() {
        val source = source("java/name/caiyao/fakegps/hook/MainHook.java")
        val branch = source.indexOf("SystemServerOracleEntryPolicy.isSystemServer(lpparam.packageName, lpparam.processName)")
        val normalPolicy = source.indexOf("RuntimeSelfHookPolicy.shouldHook(")

        assertTrue("exact system-server branch must exist", branch >= 0)
        assertTrue("system-server branch must precede generic hook policy", branch < normalPolicy)
        val branchTail = source.substring(branch, normalPolicy)
        assertTrue(branchTail.contains("SystemServerOracleInstaller.install(lpparam.classLoader)"))
        assertTrue(branchTail.contains("return;"))
    }

    @Test
    fun `legacy scope resource contains system and manifest publishes no intent filter registrar`() {
        val scope = source("res/values/xposed_scope.xml")
        val manifest = source("AndroidManifest.xml")

        assertTrue(scope.contains("<item>system</item>"))
        assertTrue(manifest.contains("android:name=\"xposedscope\""))
        assertTrue(manifest.contains("android:resource=\"@array/xposed_scope\""))
        val serviceAt = manifest.indexOf(".oracle.OracleBridgeService")
        assertTrue("OracleBridgeService must be declared", serviceAt >= 0)
        val serviceEnd = manifest.indexOf("/>", serviceAt)
        val declaration = manifest.substring(serviceAt, serviceEnd)
        assertTrue(declaration.contains("android:exported=\"true\""))
        assertTrue("registrar must remain in the main process", !declaration.contains("android:process"))
    }

    @Test
    fun `installer binds explicit bridge only from phase 600 callback`() {
        val source = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val policy = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleEntryPolicy.java")

        assertTrue(policy.contains("PHASE_THIRD_PARTY_APPS_CAN_START = 600"))
        assertTrue(source.contains("SystemServerOracleEntryPolicy.shouldBindBridgeAtPhase(phase)"))
        assertTrue(source.contains("new ComponentName("))
        assertTrue(source.contains("name.caiyao.fakegps.oracle.OracleBridgeService"))
        assertTrue(source.contains("registerOracle(oracleBinder)"))
    }

    @Test
    fun `API gated installer constructs and samples only behind explicit runtime guards`() {
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val platformGate = installer.indexOf("if (!supportedPlatform)")
        val attestationGate = installer.indexOf("if (!buildAttested)")
        val binderConstruction = installer.indexOf("oracleBinder = SystemServerOracleBinder.create(")
        val hookInstallation = installer.indexOf("tryInstallMutationGroup(")

        assertTrue("the exact API gate must precede Binder construction",
            platformGate >= 0 && platformGate < binderConstruction)
        assertTrue("build attestation must precede Binder construction",
            attestationGate >= 0 && attestationGate < binderConstruction)
        assertTrue("the guarded Binder must exist before any hook can invoke it",
            binderConstruction >= 0 && binderConstruction < hookInstallation)
        assertTrue("endpoint sampling must independently fail closed below its API floor",
            binder.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.Q"))
        assertTrue(binder.contains("effective AppOps sampling requires API 29 or newer"))
    }

    @Test
    fun `kernel boot id instance id and callback poison are explicit fail-closed inputs`() {
        val binder = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleBinder.java")
        val installer = source("java/name/caiyao/fakegps/hook/oracle/SystemServerOracleInstaller.java")

        assertTrue(binder.contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(binder.contains("UUID.randomUUID().toString()"))
        assertTrue(installer.contains("poisonCallback"))
        assertTrue(installer.contains("BUILD_UNATTESTED"))
        assertTrue(
            "a foreign covered mutation must suppress QWY receipt correlation",
            binder.contains("!aggregateForeignChanged"),
        )
    }

    @Test
    fun `release shrinker keeps private AIDL and system hook producer`() {
        val rules = File(moduleRoot, "proguard-rules.pro").readText()

        assertTrue(rules.contains("name.caiyao.fakegps.oracle.**"))
        assertTrue(rules.contains("name.caiyao.fakegps.hook.oracle.**"))
    }

    private fun source(relative: String): String = File(moduleRoot, "src/main/$relative").readText()
}
