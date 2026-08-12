package name.caiyao.fakegps.integration.v1

import java.io.File
import java.io.RandomAccessFile

/**
 * Durable KV with real transaction atomicity, backed by one file replaced
 * atomically.
 *
 * WHY NOT SharedPreferences
 * -------------------------
 * The first cut used one SharedPreferences file per namespace and ran
 * `transaction { }` as a bare monitor. That serializes concurrent callers but
 * gives no atomicity: each write commits on its own, so a crash between the
 * schedule pointer write and the advance receipt write leaves the torn state
 * §6.7.5 forbids. The fake the crash matrix runs on DOES roll back, so the lane
 * was green for a guarantee the device never had.
 *
 * WHY NOT Room/SQLite
 * -------------------
 * SQLite would give the same atomicity. It was not chosen because it cannot be
 * exercised where the guarantee actually needs proving: a Room transaction needs
 * an Android runtime, so the production implementation would go back to having
 * no unit coverage — the precise hole that let the untested seam ship in the
 * first place. This store is plain JVM, so
 * [DurableKvTransactionContractTest] runs the SAME cases against it and against
 * the fake, and a backend that cannot roll back fails instead of never being
 * asked. Schema/migration cost is also avoided for what is a flat key-value map.
 *
 * ATOMICITY MODEL
 * ---------------
 * All state lives in one file. A commit serializes the whole map to a temp file,
 * fsyncs it, then rename(2)s it over the live file. rename within a directory is
 * atomic on the filesystems Android uses, so a crash leaves either the entire
 * previous state or the entire next one — never a mix. This is the same
 * write-temp-then-rename discipline SharedPreferences uses internally; the
 * difference is that here ONE rename covers every key the transaction touched,
 * which is what makes it a transaction rather than a sequence of writes.
 *
 * Single-writer per §6.6 L3: one process owns this directory. Multi-process
 * access is not made safe by this class and is banned by the contract, not
 * guarded against here.
 */
class FileDurableKv(val directory: File) : DurableKv {

    private val file = File(directory, STORE_FILE)
    private val tempFile = File(directory, "$STORE_FILE.tmp")
    private val lock = Any()

    /** Committed state. Loaded once; every commit rewrites it wholesale. */
    private val data = HashMap<String, HashMap<String, String>>()

    /** Non-null while a transaction is open; holds writes not yet committed. */
    private var txBuffer: HashMap<Pair<String, String>, String>? = null

    init {
        if (!directory.exists()) directory.mkdirs()
        load()
    }

    override fun read(namespace: String, key: String): String? = synchronized(lock) {
        // A transaction must observe its own writes or read-modify-write breaks.
        txBuffer?.let { buffer ->
            if (buffer.containsKey(namespace to key)) return buffer[namespace to key]
        }
        data[namespace]?.get(key)
    }

    override fun write(namespace: String, key: String, value: String) {
        synchronized(lock) {
            val buffer = txBuffer
            if (buffer != null) {
                buffer[namespace to key] = value
            } else {
                // A bare write is its own one-key transaction.
                data.getOrPut(namespace) { HashMap() }[key] = value
                persist()
            }
        }
    }

    override fun keys(namespace: String): Set<String> = synchronized(lock) {
        val committed = data[namespace]?.keys?.toSet() ?: emptySet()
        val buffered = txBuffer?.keys?.filter { it.first == namespace }?.map { it.second }.orEmpty()
        committed + buffered
    }

    /**
     * Buffered read-modify-write. The buffer is applied and persisted only on
     * normal completion; any throw discards it, so a failed advance leaves no
     * pointer move and no receipt.
     *
     * A nested transaction joins the outer one: the outer commit is the only
     * durability point, matching the fake and keeping "one advance = one
     * atomic step" true even when helpers wrap their own transaction.
     */
    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (txBuffer != null) return@synchronized block()

        val buffer = HashMap<Pair<String, String>, String>()
        txBuffer = buffer
        val result = try {
            block()
        } catch (t: Throwable) {
            txBuffer = null
            throw t
        }
        // Success: apply, then make it durable in one rename.
        txBuffer = null
        buffer.forEach { (nsKey, value) ->
            data.getOrPut(nsKey.first) { HashMap() }[nsKey.second] = value
        }
        persist()
        result
    }

    private fun load() {
        data.clear()
        if (!file.isFile) return
        file.forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val parts = line.split(FS)
            if (parts.size != 3) return@forEachLine
            val (ns, key, value) = parts
            data.getOrPut(unescape(ns)) { HashMap() }[unescape(key)] = unescape(value)
        }
    }

    /**
     * Serialize everything, fsync, then rename over the live file.
     *
     * The fsync is not optional: rename only guarantees that the directory entry
     * flips atomically, not that the bytes it points at reached the disk. Without
     * it a power loss can leave the new name pointing at a truncated file, which
     * would be a torn state wearing a committed state's name.
     */
    private fun persist() {
        val text = buildString {
            data.forEach { (ns, entries) ->
                entries.forEach { (key, value) ->
                    append(escape(ns)).append(FS)
                        .append(escape(key)).append(FS)
                        .append(escape(value)).append('\n')
                }
            }
        }

        tempFile.writeText(text)
        RandomAccessFile(tempFile, "rws").use { it.fd.sync() }

        if (!tempFile.renameTo(file)) {
            // Fall back only after the atomic path failed; a store that cannot
            // persist must say so rather than pretend the write landed.
            if (!(file.delete() && tempFile.renameTo(file))) {
                throw IllegalStateException("could not atomically replace $file")
            }
        }
    }

    /**
     * Percent-encoding over the characters the line format reserves.
     *
     * Backslash-escaping was the obvious first choice and is ambiguous: a value
     * ending in a literal backslash followed by an "n" decodes as a newline. By
     * encoding '%' itself first, every '%' in the output starts an escape and
     * decoding has exactly one reading. Nothing else is touched, so the file
     * stays greppable.
     */
    private fun escape(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '%' -> append("%25")
                '\n' -> append("%0A")
                '\r' -> append("%0D")
                FS -> append("%1F")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String = buildString(s.length) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    append(hex.toChar())
                    i += 3
                    continue
                }
            }
            append(c)
            i++
        }
    }

    private companion object {
        const val STORE_FILE = "environment-control-v1.kv"

        /** ASCII unit separator: escaped on write, so it can never occur in a field. */
        const val FS = '\u001F'
    }
}
