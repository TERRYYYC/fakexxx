package com.example.cellrebelauto.util

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * T7 P1.1 — the diagnostic bundle (pure zip assembly).
 *
 * One zip replaces "scroll to the bottom and screenshot":
 *  - [ENTRY_LOGS]      the PERSISTED rolling log (disk ring, not the 200-line RAM ring);
 *  - [ENTRY_ENGINE]    the engine-state JSON (state / progress / self-heal flags);
 *  - [ENTRY_CONTRACT]  the contract readback (last discover handshake projection);
 *  - [ENTRY_DB]        the DB snapshot (plan / tasks / attempts / sessions CSV);
 *  - [ENTRY_MANIFEST]  enumerates the required entries.
 *
 * The builder REFUSES to emit a bundle missing or blanking any required
 * section — an incomplete diagnostic zip is worse than none, and the failure
 * surfaces at the call site instead of in the issue report.
 *
 * # 诊断包：清单枚举每项必在包内；缺项/空项构造时即拒绝
 */
object DiagnosticBundleBuilder {

    const val ENTRY_MANIFEST = "MANIFEST.txt"
    const val ENTRY_LOGS = "logs/rolling-log.txt"
    const val ENTRY_ENGINE = "engine-state.json"
    const val ENTRY_CONTRACT = "contract-readback.txt"
    const val ENTRY_DB = "db-snapshot.txt"

    /** The completeness contract — every entry MUST be present and non-blank. */
    val REQUIRED_ENTRIES = listOf(
        ENTRY_MANIFEST, ENTRY_LOGS, ENTRY_ENGINE, ENTRY_CONTRACT, ENTRY_DB,
    )

    /**
     * Engine snapshot rendered into [ENTRY_ENGINE]. Optionals are nullable —
     * the JSON keeps the keys with explicit nulls so field diffs stay stable.
     */
    data class EngineStateDump(
        val stateName: String,
        val isRunning: Boolean,
        val cycleCount: Int,
        val currentTaskCsvRow: Int?,
        val lastFailureOrdinal: Int?,
        val lastFailureReason: String?,
        val serviceConnected: Boolean,
        val trustedDone: Int,
        val trustedTotal: Int,
        val startStatus: String,
        // P1.3 self-heal trio — part of every export.
        val attemptWatchdogEnabled: Boolean,
        val coordinateGuardEnabled: Boolean,
        val serviceReconnectAutoResumeEnabled: Boolean,
        val generatedAtMs: Long,
    )

    /** Builds the zip. Throws [IllegalArgumentException] on a missing/blank required section. */
    fun build(sections: Map<String, String>): ByteArray {
        for (required in REQUIRED_ENTRIES) {
            val content = sections[required]
            if (content == null) {
                throw IllegalArgumentException("diagnostic bundle missing required section: $required")
            }
            if (content.isBlank()) {
                throw IllegalArgumentException("diagnostic bundle section is blank: $required")
            }
        }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for (required in REQUIRED_ENTRIES) {
                zip.putNextEntry(ZipEntry(required))
                zip.write(sections.getValue(required).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    /** Reads one entry back out of a built bundle (the completeness oracle's reader). */
    fun readEntry(zipBytes: ByteArray, entryName: String): String? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == entryName) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
    }

    fun engineStateJson(dump: EngineStateDump): String = JSONObject().apply {
        put("stateName", dump.stateName)
        put("isRunning", dump.isRunning)
        put("cycleCount", dump.cycleCount)
        put("currentTaskCsvRow", dump.currentTaskCsvRow)
        put("lastFailureOrdinal", dump.lastFailureOrdinal)
        put("lastFailureReason", dump.lastFailureReason)
        put("serviceConnected", dump.serviceConnected)
        put("trustedDone", dump.trustedDone)
        put("trustedTotal", dump.trustedTotal)
        put("startStatus", dump.startStatus)
        put("attemptWatchdogEnabled", dump.attemptWatchdogEnabled)
        put("coordinateGuardEnabled", dump.coordinateGuardEnabled)
        put("serviceReconnectAutoResumeEnabled", dump.serviceReconnectAutoResumeEnabled)
        put("generatedAtMs", dump.generatedAtMs)
    }.toString()
}
