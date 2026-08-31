package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PendingAdvanceTicketTest {
    @Test
    fun `authoritative ticket round trips every reservation field`() {
        val expected = PendingAdvanceTicket.Authoritative(
            fromItemId = "item\t1",
            toItemId = "oracle-v1",
            versionAfter = 9L,
            reservation = AuthoritativeRevisionReservation(
                mutationId = "mutation-1",
                baseRevision = 11L,
                reservedRevision = 12L,
                startingBootId = "boot-a",
                startingOracleInstanceId = "instance-a",
                startingSequence = 8L,
                startingSemanticDigest = "semantic-before",
                ownerGenerationAtReservation = 17L,
            ),
        )

        assertEquals(expected, PendingAdvanceTicket.decode(PendingAdvanceTicket.encode(expected)))
    }

    @Test
    fun `legacy two and three field markers remain readable`() {
        val two = PendingAdvanceTicket.decode(
            DurableFieldCodec.encode(listOf("item-1", null)),
        ) as PendingAdvanceTicket.Legacy
        assertEquals("item-1", two.fromItemId)
        assertNull(two.toItemId)
        assertNull(two.versionAfter)

        val three = PendingAdvanceTicket.decode(
            DurableFieldCodec.encode(listOf("item-1", "item-2", "8")),
        ) as PendingAdvanceTicket.Legacy
        assertEquals(8L, three.versionAfter)
    }

    @Test
    fun `legacy free string equal to oracle tag is not misclassified`() {
        val decoded = PendingAdvanceTicket.decode(
            DurableFieldCodec.encode(listOf("oracle-v1", "item-2", "8")),
        )
        assertEquals(PendingAdvanceTicket.Legacy::class, decoded::class)
        assertEquals("oracle-v1", decoded.fromItemId)
    }

    @Test
    fun `obsolete oracle v1 ticket is rejected instead of becoming unrecoverable`() {
        val obsolete = DurableFieldCodec.encode(
            listOf(
                "oracle-v1",
                "item-1",
                "item-2",
                "9",
                "mutation-1",
                "11",
                "12",
                "boot-a",
                "instance-a",
                "8",
                "semantic-before",
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            PendingAdvanceTicket.decode(obsolete)
        }
    }
}
