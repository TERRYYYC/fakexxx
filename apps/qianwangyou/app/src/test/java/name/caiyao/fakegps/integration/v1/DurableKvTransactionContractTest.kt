package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The transaction contract, asserted against EVERY DurableKv implementation.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The crash matrix proves "a crash between the pointer write and the receipt
 * write leaves exactly one legal state" by injecting a failure into
 * [InMemoryDurableKv], whose transaction buffers writes and discards the buffer
 * on throw. That is a real rollback.
 *
 * The first production KV on this branch did not do that. It ran
 * `transaction { block() }` as a bare monitor while every write committed
 * immediately to its own SharedPreferences file. Serialized, yes; atomic, no.
 * A crash after the pointer write and before the receipt write left precisely
 * the torn state the matrix says is impossible.
 *
 * So the fake was STRONGER than production, and the whole crash lane was green
 * for a guarantee the device never had. Fidelity inversion is worse than a
 * missing test: it manufactures confidence.
 *
 * The fix is structural rather than another one-off case — the contract is
 * asserted once and every implementation must satisfy it. A new backend that
 * cannot roll back now fails here instead of passing by never being asked.
 */
abstract class DurableKvTransactionContractTest {

    /** A fresh, empty store. */
    protected abstract fun newKv(): DurableKv

    /**
     * Another handle onto the SAME durable state — this is "the process after a
     * restart". For an in-memory fake that is the same instance; for a
     * file-backed store it is a new instance over the same directory.
     */
    protected abstract fun reopen(kv: DurableKv): DurableKv

    private class Boom : RuntimeException("injected mid-transaction failure")

    @Test
    fun `a transaction that throws leaves no partial writes`() {
        val kv = newKv()
        kv.write("ns", "settled", "before")

        try {
            kv.transaction {
                kv.write("ns", "pointer", "advanced")
                // Crash between the two writes the advance path must keep together.
                throw Boom()
            }
        } catch (e: Boom) {
            // expected
        }

        assertNull(
            "the pointer write must not survive a failed transaction — this is " +
                "exactly the torn state §6.7.5 forbids",
            reopen(kv).read("ns", "pointer")
        )
        assertEquals(
            "state written before the transaction must be untouched",
            "before",
            reopen(kv).read("ns", "settled")
        )
    }

    @Test
    fun `a transaction is all-or-nothing across namespaces`() {
        val kv = newKv()

        try {
            kv.transaction {
                kv.write("schedule", "currentItemId", "item-2")
                kv.write("schedule", "exhausted", "0")
                kv.write("receipts", "advance-1", "receipt-body")
                throw Boom()
            }
        } catch (e: Boom) {
            // expected
        }

        val after = reopen(kv)
        assertNull("pointer must not survive", after.read("schedule", "currentItemId"))
        assertNull("exhausted bit must not survive", after.read("schedule", "exhausted"))
        assertNull("receipt must not survive", after.read("receipts", "advance-1"))
    }

    @Test
    fun `a committed transaction is visible in full after restart`() {
        val kv = newKv()

        kv.transaction {
            kv.write("schedule", "currentItemId", "item-2")
            kv.write("receipts", "advance-1", "receipt-body")
        }

        val after = reopen(kv)
        assertEquals("item-2", after.read("schedule", "currentItemId"))
        assertEquals("receipt-body", after.read("receipts", "advance-1"))
        assertEquals(
            "keys() must see committed data after restart",
            setOf("currentItemId"),
            after.keys("schedule")
        )
    }

    @Test
    fun `reads inside a transaction see its own uncommitted writes`() {
        val kv = newKv()
        kv.transaction {
            kv.write("ns", "k", "v1")
            assertEquals(
                "a transaction must read its own writes, or read-modify-write is broken",
                "v1",
                kv.read("ns", "k")
            )
        }
        assertEquals("v1", reopen(kv).read("ns", "k"))
    }

    @Test
    fun `writes outside a transaction survive restart`() {
        val kv = newKv()
        kv.write("ns", "k", "v")
        assertEquals("v", reopen(kv).read("ns", "k"))
    }
}

/** The fake the whole behavior matrix runs on. It defines the contract. */
class InMemoryDurableKvContractTest : DurableKvTransactionContractTest() {
    override fun newKv(): DurableKv = InMemoryDurableKv()

    /** Memory-backed: the same instance IS the durable state. */
    override fun reopen(kv: DurableKv): DurableKv = kv
}

/**
 * The production store. It runs the SAME cases as the fake — that equality is
 * the point, and it is what a SharedPreferences-per-namespace backend could not
 * have satisfied.
 */
class FileDurableKvContractTest : DurableKvTransactionContractTest() {

    @get:Rule
    val folder = TemporaryFolder()

    override fun newKv(): DurableKv = FileDurableKv(folder.newFolder())

    override fun reopen(kv: DurableKv): DurableKv =
        FileDurableKv((kv as FileDurableKv).directory)
}
