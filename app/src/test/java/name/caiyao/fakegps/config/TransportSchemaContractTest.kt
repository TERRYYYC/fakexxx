package name.caiyao.fakegps.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSchemaContractTest {

    @Test
    fun `current writer is v4 and reader losslessly accepts v2 and v3`() {
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.SCHEMA_VERSION))
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.PREVIOUS_SCHEMA_VERSION))
        assertTrue(TransportSchemaContract.supports(ConfigPrefsSync.LEGACY_SCHEMA_VERSION))
        assertFalse(TransportSchemaContract.supports(1))
        assertFalse(TransportSchemaContract.supports(5))
    }
}
