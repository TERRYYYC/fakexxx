package name.caiyao.fakegps.probe

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog

/**
 * #194 direct-boot 回归。QWY 进程会在用户解锁前被 system_server 的 boot phase-600 bind 拉起
 * （directBootAware 的 OracleBridgeService 随进程启动 HookAcceptanceApplication），此时
 * credential-encrypted 存储不可读。这里在 JVM 上模拟两种 CE 不可用形态：
 * 1. CE prefs 读取直接抛（真机 direct-boot 的实际症状）——hasPending/pendingFingerprint 必须
 *    返回默认值而不是把进程打死；
 * 2. UserManager.isUserUnlocked=false（CE 明确未解锁）——显式走延后路径：durable record 原样
 *    保留，等下一次解锁后的进程启动再恢复（验收 harness 本身只在解锁态运行，延后不丢事务）。
 */
@RunWith(RobolectricTestRunner::class)
class HookAcceptanceRecoveryDirectBootTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(HookAcceptanceRecovery.RECORD_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowLog.setupLogging()
        setUserUnlocked(true)
    }

    /** 真机 direct-boot 的症状形态：CE 存储读取抛 IllegalStateException。 */
    private fun lockedStorageContext(): Context = object : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            throw IllegalStateException(
                "device is locked: credential-encrypted storage unavailable",
            )
        }
    }

    private fun seedPending(previousJson: String, target: Context = context) {
        target.getSharedPreferences(HookAcceptanceRecovery.RECORD_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(HookAcceptanceRecovery.KEY_PENDING, true)
            .putString("previous_json", previousJson)
            .commit()
    }

    private fun setUserUnlocked(unlocked: Boolean) {
        shadowOf(context.getSystemService(Context.USER_SERVICE) as UserManager)
            .setUserUnlocked(unlocked)
    }

    @Test
    fun `locked CE storage reports no pending instead of crashing the process start`() {
        val locked = lockedStorageContext()

        assertFalse(HookAcceptanceRecovery.hasPending(locked))
        assertNull(HookAcceptanceRecovery.pendingFingerprint(locked))
        assertTrue(
            "deferral must be observable in the log",
            ShadowLog.getLogsForTag(HookAcceptanceRecovery.TAG).any { it.msg == "pending_check_deferred" },
        )
    }

    @Test
    fun `unlocked device gate defers even when the storage read itself would succeed`() {
        seedPending("""{"fields":{"tac":7}}""")
        setUserUnlocked(false)

        assertFalse(HookAcceptanceRecovery.hasPending(context))
        assertNull(HookAcceptanceRecovery.pendingFingerprint(context))
    }

    @Test
    fun `durable record survives the direct-boot deferral for the next unlocked start`() {
        seedPending("""{"fields":{"tac":7}}""")

        setUserUnlocked(false)
        assertFalse("direct-boot start must defer, not consume", HookAcceptanceRecovery.hasPending(context))

        setUserUnlocked(true)
        assertTrue("next unlocked start sees the intact record", HookAcceptanceRecovery.hasPending(context))
        assertNotNull(HookAcceptanceRecovery.pendingFingerprint(context))
    }

    @Test
    fun `application start in direct-boot window defers recovery without crashing`() {
        val app = ApplicationProvider.getApplicationContext<HookAcceptanceApplication>()
        fun pendingFlag() =
            app.getSharedPreferences(HookAcceptanceRecovery.RECORD_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(HookAcceptanceRecovery.KEY_PENDING, false)

        fun recoveryDecisions() = ShadowLog.getLogsForTag(HookAcceptanceRecovery.TAG)
            .count { it.msg == "recovered_pending" || it.msg == "recovery_failed" }

        setUserUnlocked(false)
        seedPending("""{"fields":{"tac":7}}""")
        app.onCreate()

        assertTrue(
            "deferred start must leave the pending record untouched",
            pendingFlag(),
        )
        assertEquals(
            "locked start must not even reach the recovery decision",
            0,
            recoveryDecisions(),
        )

        setUserUnlocked(true)
        app.onCreate()

        assertEquals(
            "unlocked start reaches the recovery decision (publish outcome is device-only: " +
                "Robolectric rejects the MODE_WORLD_READABLE transport with SecurityException; " +
                "object and caller both log, hence >=1)",
            true,
            recoveryDecisions() >= 1,
        )
    }
}
