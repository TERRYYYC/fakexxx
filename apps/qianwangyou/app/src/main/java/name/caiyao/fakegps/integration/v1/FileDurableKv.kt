package name.caiyao.fakegps.integration.v1

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Durable KV with real transaction atomicity, backed by a legacy snapshot plus
 * an append-only transaction journal.
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
 * Old installs may have a complete [STORE_FILE] snapshot. New commits leave it
 * intact and append one self-validating record to [JOURNAL_FILE]. Each record
 * carries every key changed by its transaction, so replay observes either the
 * entire transaction or none of it. A crash-truncated final record is ignored;
 * a complete malformed record is corruption and fails closed. This preserves
 * composite commits (pointer + receipt + audit) while avoiding a whole-map
 * rewrite for every audit append.
 *
 * Single-writer per §6.6 L3: one process owns this directory. Multi-process
 * access is not made safe by this class and is banned by the contract, not
 * guarded against here.
 */
open class FileDurableKv(val directory: File) : DurableKv {

    private val file = File(directory, STORE_FILE)
    private val journalFile = File(directory, JOURNAL_FILE)
    private val tempFile = File(directory, "$JOURNAL_FILE.tmp")
    private val lock = Any()

    /** Committed state reconstructed from the legacy snapshot and journal. */
    private val data = HashMap<String, HashMap<String, String>>()

    /** Non-null while a transaction is open; holds writes not yet committed. */
    private var txBuffer: HashMap<Pair<String, String>, String>? = null

    /** Byte boundary after the last complete journal record, if recovery saw a torn suffix. */
    private var incompleteJournalOffset: Long? = null

    /**
     * A journal append can fail after the filesystem accepted bytes. This owner
     * cannot know whether those bytes will survive, so it must never allocate
     * another sequence from its stale in-memory view. A new owner replays disk
     * and becomes the only safe recovery authority.
     */
    private var ownerFailure: Throwable? = null

    init {
        if (!directory.exists()) directory.mkdirs()
        load()
    }

    override fun read(namespace: String, key: String): String? = synchronized(lock) {
        requireHealthyOwner()
        // A transaction must observe its own writes or read-modify-write breaks.
        txBuffer?.let { buffer ->
            if (buffer.containsKey(namespace to key)) return buffer[namespace to key]
        }
        data[namespace]?.get(key)
    }

    override fun write(namespace: String, key: String, value: String) {
        synchronized(lock) {
            requireHealthyOwner()
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
        requireHealthyOwner()
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
        requireHealthyOwner()
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
     * So the candidate state is built off to the side and only adopted once the
     * bytes are down. A staging failure leaves the prior state usable. A failure
     * once journal I/O starts is different: disk may contain a complete record,
     * so this owner fail-stops and requires a fresh replay owner.
     */
    private fun commit(buffer: Map<Pair<String, String>, String>) {
        if (buffer.isEmpty()) return
        persist(buffer)
        apply(buffer)
    }

    private fun requireHealthyOwner() {
        check(ownerFailure == null) {
            "durable store owner is stopped after an uncertain journal commit; reopen before reuse"
        }
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
        if (file.isFile) applySnapshot(file.readLines(), file)
        loadJournal()
    }

    private fun applySnapshot(lines: List<String>, source: File) {
        lines.forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(FS)
            if (parts.size != 3) {
                throw IllegalStateException(
                    "corrupt durable store at $source line ${index + 1}: expected 3 " +
                    "unit-separated fields, found ${parts.size}. Refusing to load " +
                    "a partial state — a subset of the last commit is a torn state."
                )
            }
            val (ns, key, value) = parts
            data.getOrPut(unescape(ns)) { HashMap() }[unescape(key)] = unescape(value)
        }
    }

    /** Replays complete journal records. A partial final record never committed. */
    private fun loadJournal() {
        if (!journalFile.isFile) return
        incompleteJournalOffset = null
        val bytes = journalFile.readBytes()
        var cursor = 0
        while (cursor < bytes.size) {
            val headerEnd = bytes.indexOfByte('\n'.code.toByte(), cursor)
            if (headerEnd < 0) break // interrupted header at EOF
            val header = String(bytes, cursor, headerEnd - cursor, StandardCharsets.US_ASCII)
            val match = JOURNAL_HEADER.matchEntire(header)
                ?: throw IllegalStateException("corrupt durable journal at $journalFile offset $cursor: bad header")
            val length = match.groupValues[1].toIntOrNull()
                ?: throw IllegalStateException("corrupt durable journal at $journalFile offset $cursor: bad length")
            require(length >= 0) { "corrupt durable journal at $journalFile offset $cursor: negative length" }
            val bodyStart = headerEnd + 1
            val bodyEnd = bodyStart + length
            if (bodyEnd > bytes.size) break // interrupted body at EOF
            val body = bytes.copyOfRange(bodyStart, bodyEnd)
            val expectedDigest = match.groupValues[2]
            if (!sha256(body).equals(expectedDigest, ignoreCase = true)) {
                throw IllegalStateException("corrupt durable journal at $journalFile offset $cursor: digest mismatch")
            }
            applyRecord(String(body, StandardCharsets.UTF_8), cursor)
            cursor = bodyEnd
        }
        // Keep a read-only open observationally pure. The incomplete suffix is
        // removed only immediately before a future append, when retaining it
        // would make a malformed middle record. It never represented a commit.
        if (cursor < bytes.size) {
            incompleteJournalOffset = cursor.toLong()
        }
    }

    private fun applyRecord(record: String, offset: Int) {
        if (record.isEmpty()) {
            throw IllegalStateException("corrupt durable journal at $journalFile offset $offset: empty transaction")
        }
        val writes = HashMap<Pair<String, String>, String>()
        record.lineSequence().forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(FS)
            if (parts.size != 3) {
                throw IllegalStateException(
                    "corrupt durable journal at $journalFile offset $offset record line ${index + 1}: expected 3 fields"
                )
            }
            val key = unescape(parts[0]) to unescape(parts[1])
            if (writes.put(key, unescape(parts[2])) != null) {
                throw IllegalStateException("corrupt durable journal at $journalFile offset $offset: duplicate key")
            }
        }
        if (writes.isEmpty()) {
            throw IllegalStateException("corrupt durable journal at $journalFile offset $offset: empty transaction")
        }
        apply(writes)
    }

    private fun apply(writes: Map<Pair<String, String>, String>) {
        writes.forEach { (nsKey, value) ->
            data.getOrPut(nsKey.first) { HashMap() }[nsKey.second] = value
        }
    }

    /**
     * Stage and append one self-validating transaction record.
     *
     * A staged record lets the existing fault-injection seam prove that a failed
     * serialization never reaches the journal. The actual journal append is
     * followed by fsync before memory advances. If a power loss tears that final
     * append, [loadJournal] ignores the incomplete suffix; it can never expose a
     * subset of the transaction.
     */
    private fun persist(writes: Map<Pair<String, String>, String>) {
        val body = buildString {
            writes.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
                .forEach { (nsKey, value) ->
                    append(escape(nsKey.first)).append(FS)
                        .append(escape(nsKey.second)).append(FS)
                        .append(escape(value)).append('\n')
                }
        }.toByteArray(StandardCharsets.UTF_8)
        val record = "#${body.size}:${sha256(body)}\n".toByteArray(StandardCharsets.US_ASCII) + body
        writeTempFile(tempFile, String(record, StandardCharsets.UTF_8))
        RandomAccessFile(tempFile, "rws").use { it.fd.sync() }
        val staged = tempFile.readBytes()
        if (!staged.contentEquals(record)) {
            throw IllegalStateException("staged durable journal record is corrupt; previous state is intact")
        }
        try {
            discardIncompleteSuffixBeforeAppend()
            appendJournalRecord(journalFile, staged)
        } catch (failure: Throwable) {
            ownerFailure = failure
            throw failure
        }
        syncDirectory()
    }

    /**
     * Journal-I/O seam for fault injection. Unlike [writeTempFile], a failure
     * here may follow a partial or complete write to the live journal and must
     * stop this owner.
     */
    internal open fun appendJournalRecord(target: File, bytes: ByteArray) {
        FileOutputStream(target, true).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    private fun discardIncompleteSuffixBeforeAppend() {
        val offset = incompleteJournalOffset ?: return
        RandomAccessFile(journalFile, "rws").use {
            it.setLength(offset)
            it.fd.sync()
        }
        incompleteJournalOffset = null
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

    private fun ByteArray.indexOfByte(needle: Byte, start: Int): Int {
        for (index in start until size) if (this[index] == needle) return index
        return -1
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val STORE_FILE = "environment-control-v1.kv"
        const val JOURNAL_FILE = "environment-control-v1.journal"
        val JOURNAL_HEADER = Regex("#([0-9]+):([0-9a-fA-F]{64})")

        /** ASCII unit separator: escaped on write, so it can never occur in a field. */
        const val FS = '\u001F'
    }
}
