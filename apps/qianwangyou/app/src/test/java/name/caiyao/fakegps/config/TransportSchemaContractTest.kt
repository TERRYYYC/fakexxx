package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSchemaContractTest {

    @Test
    fun `current writer is v5 and reader losslessly accepts v4 v3 and v2`() {
        assertEquals(5, ConfigPrefsSync.SCHEMA_VERSION)
        assertEquals(4, ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION)
        assertEquals(3, ConfigPrefsSync.LEGACY_SCHEMA_VERSION)
        assertEquals(2, ConfigPrefsSync.OLDEST_READABLE_SCHEMA_VERSION)
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.SCHEMA_VERSION))
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION))
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.LEGACY_SCHEMA_VERSION))
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.OLDEST_READABLE_SCHEMA_VERSION))
        assertFalse(TransportSchemaContract.supports(1))
        assertFalse(TransportSchemaContract.supports(6))
    }
}
