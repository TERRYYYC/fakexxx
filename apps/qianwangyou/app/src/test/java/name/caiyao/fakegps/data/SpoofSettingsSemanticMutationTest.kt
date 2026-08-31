package name.caiyao.fakegps.data

import android.content.SharedPreferences
import name.caiyao.fakegps.integration.v1.QwySemanticClientDeathToken
import name.caiyao.fakegps.integration.v1.QwySemanticClientDeathTokenFactory
import name.caiyao.fakegps.integration.v1.QwySemanticDigestProvider
import name.caiyao.fakegps.integration.v1.QwySemanticMutationCoordinator
import name.caiyao.fakegps.integration.v1.QwySemanticMutationEndpoint
import name.caiyao.fakegps.integration.v1.QwySemanticMutationEndpointProvider
import name.caiyao.fakegps.integration.v1.QwySemanticSessionHealth
import name.caiyao.fakegps.integration.v1.QwySemanticSessionRegistration
import name.caiyao.fakegps.integration.v1.QwySemanticWriterAmbiguityException
import name.caiyao.fakegps.integration.v1.QwySemanticWriterRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoofSettingsSemanticMutationTest {
    @Test
    fun `mode active hours and delivery mode use one authoritative bracket each`() {
        val prefs = FakeSharedPreferences()
        var publishes = 0
        val settings = SpoofSettings(
            prefs,
            authoritativePublisher = { publishes += 1; true },
        )
        val endpoint = FakeEndpoint()
        var nextId = 0
        val coordinator = coordinator(endpoint)
        val initialDigest = digest(settings)
        assertEquals(
            QwySemanticSessionRegistration.Registered(initialDigest),
            coordinator.registerCurrentSession(initialDigest),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { digest(settings) },
            sessionHealth = QwySemanticSessionHealth { true },
            mutationIdFactory = { kind -> "settings-$kind-${++nextId}" },
        )
        try {
            settings.setSpoofMode(SpoofSettings.MODE_TIME_BASED)
            settings.setActiveHourStart(5)
            settings.setActiveHourEnd(19)
            assertTrue(settings.setLocationDeliveryMode(LocationDeliveryMode.SYSTEM_MOCK))

            assertEquals(SpoofSettings.MODE_TIME_BASED, settings.spoofMode.value)
            assertEquals(5, settings.activeHourStart.value)
            assertEquals(19, settings.activeHourEnd.value)
            assertEquals(LocationDeliveryMode.SYSTEM_MOCK, settings.locationDeliveryMode.value)
            assertEquals(4, publishes)
            assertEquals(4, endpoint.calls.count { it.startsWith("begin:") })
            assertEquals(4, endpoint.calls.count { it.contains(":true:false:") })
            assertTrue(endpoint.calls.any { it.startsWith("begin:settings-spoof-mode-") })
            assertTrue(endpoint.calls.any { it.startsWith("begin:settings-active-hour-start-") })
            assertTrue(endpoint.calls.any { it.startsWith("begin:settings-active-hour-end-") })
            assertTrue(endpoint.calls.any { it.startsWith("begin:settings-location-delivery-mode-") })
        } finally {
            installation.close()
        }
    }

    @Test
    fun `identical setting is an explicit no-op and unavailable runtime keeps legacy writes`() {
        val prefs = FakeSharedPreferences()
        var publishes = 0
        val settings = SpoofSettings(
            prefs,
            authoritativePublisher = { publishes += 1; true },
        )

        settings.setSpoofMode(SpoofSettings.MODE_OFF)
        assertEquals(SpoofSettings.MODE_OFF, settings.getRawMode())
        assertEquals(0, publishes)

        val endpoint = FakeEndpoint()
        val coordinator = coordinator(endpoint)
        val current = digest(settings)
        assertEquals(
            QwySemanticSessionRegistration.Registered(current),
            coordinator.registerCurrentSession(current),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator,
            QwySemanticDigestProvider { digest(settings) },
            QwySemanticSessionHealth { true },
            mutationIdFactory = { "settings-no-op" },
        )
        try {
            settings.setSpoofMode(SpoofSettings.MODE_OFF)
            assertTrue(endpoint.calls.last().contains(":false:false:$current"))
            assertEquals(1, publishes)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `authoritative delivery commit failure becomes uncertainty while fallback returns false`() {
        val fallbackPrefs = FakeSharedPreferences().apply { failCommit = true }
        val fallbackSettings = SpoofSettings(fallbackPrefs)
        assertEquals(false, fallbackSettings.setLocationDeliveryMode(LocationDeliveryMode.SYSTEM_MOCK))

        val prefs = FakeSharedPreferences()
        val settings = SpoofSettings(prefs, authoritativePublisher = { true })
        val endpoint = FakeEndpoint()
        val coordinator = coordinator(endpoint)
        val current = digest(settings)
        assertEquals(
            QwySemanticSessionRegistration.Registered(current),
            coordinator.registerCurrentSession(current),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator,
            QwySemanticDigestProvider { digest(settings) },
            QwySemanticSessionHealth { true },
            mutationIdFactory = { "settings-delivery-failure" },
        )
        try {
            prefs.failCommit = true
            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                settings.setLocationDeliveryMode(LocationDeliveryMode.SYSTEM_MOCK)
            }
            assertTrue(endpoint.calls.last().contains(":false:true:"))
        } finally {
            installation.close()
        }
    }

    @Test
    fun `authoritative publication failure poisons the bracket after preference commit`() {
        val prefs = FakeSharedPreferences()
        val settings = SpoofSettings(prefs, authoritativePublisher = { false })
        val endpoint = FakeEndpoint()
        val coordinator = coordinator(endpoint)
        val current = digest(settings)
        assertEquals(
            QwySemanticSessionRegistration.Registered(current),
            coordinator.registerCurrentSession(current),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator,
            QwySemanticDigestProvider { digest(settings) },
            QwySemanticSessionHealth { true },
            mutationIdFactory = { "settings-publish-failure" },
        )
        try {
            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                settings.setSpoofMode(SpoofSettings.MODE_OFF)
            }

            assertEquals(SpoofSettings.MODE_OFF, settings.getRawMode())
            assertTrue(endpoint.calls.last().contains(":false:true:"))
        } finally {
            installation.close()
        }
    }

    private fun coordinator(endpoint: FakeEndpoint) = QwySemanticMutationCoordinator(
        endpointProvider = QwySemanticMutationEndpointProvider { endpoint },
        clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
            QwySemanticClientDeathToken { true }
        },
    )

    private fun digest(settings: SpoofSettings): String = listOf(
        settings.getRawMode(),
        settings.getRawHourStart(),
        settings.getRawHourEnd(),
        settings.readLocationDeliveryMode().wireValue,
    ).joinToString("|")

    private class FakeEndpoint : QwySemanticMutationEndpoint {
        val calls = mutableListOf<String>()
        private var nextToken = 0L

        override fun registerCurrentSession(
            semanticDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ) {
            calls += "register:$semanticDigest"
        }

        override fun beginMutation(
            mutationId: String,
            beforeDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ): Long {
            calls += "begin:$mutationId:$beforeDigest"
            return ++nextToken
        }

        override fun finishMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) {
            calls += "finish:$token:$changed:$uncertain:${afterDigest.orEmpty()}"
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()
        var failCommit = false

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = value }
            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply {
                updates[requireNotNull(key)] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = value }
            override fun remove(key: String?): SharedPreferences.Editor =
                apply { updates[requireNotNull(key)] = Removed }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean {
                applyUpdates()
                return !failCommit
            }
            override fun apply() = applyUpdates()

            private fun applyUpdates() {
                if (clear) values.clear()
                updates.forEach { (key, value) ->
                    if (value === Removed) values.remove(key) else values[key] = value
                }
            }
        }

        private object Removed
    }
}
