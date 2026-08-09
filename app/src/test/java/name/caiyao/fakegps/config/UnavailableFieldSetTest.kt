package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UnavailableFieldSetTest {

    @Test
    fun `storage is canonical sorted json and empty is null`() {
        assertEquals("""["lac","tac"]""", UnavailableFieldSet.encode(setOf("tac", "lac")))
        assertNull(UnavailableFieldSet.encode(emptySet()))
    }

    @Test
    fun `stored value round trips`() {
        assertEquals(linkedSetOf("lac", "tac"), UnavailableFieldSet.decode("""["tac","lac"]"""))
        assertEquals(emptySet<String>(), UnavailableFieldSet.decode(null))
        assertEquals(emptySet<String>(), UnavailableFieldSet.decode(""))
    }

    @Test
    fun `corrupt duplicate unknown and unsupported storage is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UnavailableFieldSet.decode("not-json")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnavailableFieldSet.decode("""["tac","tac"]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnavailableFieldSet.decode("""["tacc"]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            UnavailableFieldSet.decode("""["is_roaming"]""")
        }
    }
}
