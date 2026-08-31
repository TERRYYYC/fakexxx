package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSemanticChangePolicyTest {

    @Test
    fun `exact coordinate bits are a semantic no-op`() {
        assertFalse(
            LocationSemanticChangePolicy.hasChanged(
                37.4219999,
                -122.0840575,
                37.4219999,
                -122.0840575,
            ),
        )
    }

    @Test
    fun `either coordinate changing is semantic history`() {
        assertTrue(LocationSemanticChangePolicy.hasChanged(1.0, 2.0, 1.0, 3.0))
        assertTrue(LocationSemanticChangePolicy.hasChanged(1.0, 2.0, 3.0, 2.0))
    }

    @Test
    fun `signed zero follows the exact digest bit contract`() {
        assertTrue(LocationSemanticChangePolicy.hasChanged(0.0, 1.0, -0.0, 1.0))
        assertTrue(LocationSemanticChangePolicy.hasChanged(1.0, 0.0, 1.0, -0.0))
    }

    @Test
    fun `missing invalid and out of range coordinates are never a proved no-op`() {
        assertTrue(LocationSemanticChangePolicy.hasChanged(null, null, 1.0, 2.0))
        assertTrue(LocationSemanticChangePolicy.hasChanged(1.0, 2.0, null, null))
        assertTrue(LocationSemanticChangePolicy.hasChanged(Double.NaN, 2.0, Double.NaN, 2.0))
        assertTrue(LocationSemanticChangePolicy.hasChanged(91.0, 2.0, 91.0, 2.0))
        assertTrue(LocationSemanticChangePolicy.hasChanged(1.0, 181.0, 1.0, 181.0))
    }
}
