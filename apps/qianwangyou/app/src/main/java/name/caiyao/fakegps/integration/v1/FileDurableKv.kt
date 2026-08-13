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
 * WHY NOT Room/SQLite — corrected, and narrower than first claimed
 * ----------------------------------------------------------------
 * An earlier version of this comment said Room "cannot be exercised". A reviewer
 * pointed out the repo already uses Room 2.7.1 with room-testing,
 * AppDatabaseMigrationTest and ProfileImportTransactionTest, so that was wrong
 * as written and is corrected here rather than quietly softened.
 *
 * The accurate statement is narrower: both of those live in `src/androidTest`,
 * `room-testing` is an `androidTestImplementation` dependency, and this project's
 * CI runs only `testDebugUnitTest` and `assembleDebug` — no emulator job, no
 * `connectedAndroidTest`. They exist and are never executed by any gate. So on a
 * Room backend the atomicity guarantee would be verifiable in principle and
 * unverified in practice until someone stands up an instrumented CI lane.
 *
 * That is a real and reasonable option, and it is an infrastructure decision, not
 * this class's to make. What this store buys meanwhile is that
 * [DurableKvTransactionContractTest] runs the SAME cases against it and against
 * the fake, inside the gate that actually runs. If the schedule model lands in
 * Room — which is where an operator-maintained plan belongs — the thing to carry
 * over is the contract test: point it at the new backend and the guarantee
 * travels with it.
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
open class FileDurableKv(val directory: File) : DurableKv {

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
                // A bare write is its own one-key transaction — same commit path,
                // so it inherits the same durability-before-memory ordering
                // instead of quietly having weaker rules than a transaction.
                commit(mapOf((namespace to key) to value))
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
        txBuffer = null
        commit(buffer)
        result
    }

    /**
     * Durability first, then memory.
     *
     * The earlier order was: mutate [data], then persist. If persist threw — full
     * disk, revoked permission, a failed rename — the in-process truth had
     * already moved and the disk had not. The store would keep answering from
     * the newer state for the rest of the process lifetime and silently revert
     * on restart, which is worse than the crash it was trying to survive:
     * a rollback that only happens later, invisibly.
     *
     * So the candidate state is built off to the side, persisted, and only
     * adopted once the bytes are down. A failed commit leaves both memory and
     * disk on the previous state — same state, one truth.
     */
    private fun commit(buffer: Map<Pair<String, String>, String>) {
        val candidate = HashMap<String, HashMap<String, String>>(data.size)
        data.forEach { (ns, entries) -> candidate[ns] = HashMap(entries) }
        buffer.forEach { (nsKey, value) ->
            candidate.getOrPut(nsKey.first) { HashMap() }[nsKey.second] = value
        }

        persist(candidate)

        data.clear()
        data.putAll(candidate)
    }

    /**
     * Load, refusing to interpret a damaged file.
     *
     * The first version skipped malformed lines. That is the most dangerous
     * possible reaction: a half-written file would load as a SUBSET of the last
     * committed state and every later read would answer from it confidently.
     * Silently dropping records turns "the store is corrupt" into "the pointer
     * moved back and the receipt vanished" — precisely the torn state the whole
     * temp+rename design exists to make impossible, reintroduced at read time.
     *
     * Given rename-based commits, a malformed line means something outside this
     * class's model happened (a partial write that still got renamed, external
     * tampering, a truncating filesystem). None of those are safe to guess
     * through, so the store fails to open and the caller can decide.
     */
    private fun load() {
        data.clear()
        if (!file.isFile) return
        file.readLines().forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(FS)
            if (parts.size != 3) {
                throw IllegalStateException(
                    "corrupt durable store at $file line ${index + 1}: expected 3 " +
                        "unit-separated fields, found ${parts.size}. Refusing to load " +
                        "a partial state — a subset of the last commit is a torn state."
                )
            }
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
     *
     * There is deliberately NO fallback when rename fails. An earlier version
     * tried `file.delete()` then renamed again — which converts one atomic
     * replace into delete-then-create, and a crash in that window leaves NO live
     * file at all. It traded the single guarantee this class exists to provide
     * for a slightly better success rate on a path that should be failing loudly.
     * If rename fails, the previous file is still intact and the caller is told.
     */
    private fun persist(state: Map<String, Map<String, String>>) {
        val text = buildString {
            state.forEach { (ns, entries) ->
                entries.forEach { (key, value) ->
                    append(escape(ns)).append(FS)
                        .append(escape(key)).append(FS)
                        .append(escape(value)).append('\n')
                }
            }
        }

        writeTempFile(tempFile, text)
        RandomAccessFile(tempFile, "rws").use { it.fd.sync() }

        if (!tempFile.renameTo(file)) {
            throw IllegalStateException(
                "could not atomically replace $file; previous state is intact"
            )
        }

        syncDirectory()
    }

    /**
     * Overridable purely so the contract test can inject a failure at the exact
     * point where torn state would appear. Production behavior is [File.writeText].
     */
    internal open fun writeTempFile(target: File, text: String) {
        target.writeText(text)
    }

    /**
     * fsync the directory so the rename itself survives power loss.
     *
     * Renaming makes the switch atomic; it does not make the DIRECTORY ENTRY
     * durable. Without this, power loss can roll the directory back to the
     * previous entry — the old state, whole. That is a durability limit, not a
     * torn state, which is why this is best-effort rather than fatal.
     *
     * Best-effort is also forced: fsync-on-directory has no pre-API-26 Java
     * surface, and minSdk here is 24. On 24/25 the NoSuchMethodError is caught
     * and the store degrades to "atomic, but the last commit may not survive a
     * power cut" — stated rather than assumed.
     */
    @Suppress("NewApi")
    private fun syncDirectory() {
        try {
            java.nio.channels.FileChannel
                .open(directory.toPath(), java.nio.file.StandardOpenOption.READ)
                .use { it.force(true) }
        } catch (t: Throwable) {
            // API < 26, or a filesystem that refuses to open a directory.
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
