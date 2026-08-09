package name.caiyao.fakegps.verify

import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.TransportSchemaContract

/**
 * Whether the hook is actually applying the published payload right now.
 *
 * A verdict is only meaningful once this says [APPLYING]. In every other state the hook passes real
 * values through BY DESIGN, so each configured field reads back real — which, scored naively, looks
 * identical to a broken hook. The screen would then tell the user to go re-check their module scope
 * while the module behaves exactly as configured.
 *
 * Deliberately mirrors the gates in MainHook#loadSnapshot.
 */
enum class HookApplicability {
    /** Hook is live and applying this payload. Verdicts mean what they say. */
    APPLYING,

    /** 伪装模式 = 关闭. */
    MODE_OFF,

    /** time_based, and the current hour falls outside the configured window. */
    OUTSIDE_ACTIVE_HOURS,

    /** The hook refuses a payload version it cannot interpret and keeps last-known-good instead. */
    SCHEMA_REJECTED,

    /**
     * The payload carries no `fields` object. MainHook calls that structurally incomplete and keeps
     * its previous Snapshot, so the config actually in force is one the UI cannot see — any verdict
     * would describe a config the hook is not running.
     */
    PAYLOAD_INCOMPLETE,

    /**
     * Bytes exist on disk but cannot be parsed. MainHook keeps its last-known-good Snapshot, so it
     * is still spoofing — from a config the UI cannot read. Treating this as "no config" would make
     * the verdict card claim nothing is being spoofed while the payload card says the opposite.
     */
    PAYLOAD_MALFORMED,

    /** Nothing has ever been published; the hook has no config at all. */
    NEVER_PUBLISHED,

    /**
     * The payload could not be read at all (permissions, I/O). Unlike [NEVER_PUBLISHED] the hook is
     * almost certainly still spoofing from last-known-good — we simply cannot see what.
     */
    PAYLOAD_UNREADABLE,

    /** The database changed but the transport publish failed; the on-disk payload is stale. */
    PUBLICATION_FAILED;

    val verdictsMeaningful: Boolean get() = this == APPLYING

    companion object {

        /**
         * Applicability for a payload as actually read back from disk.
         *
         * Centralised here so the unreadable/absent cases cannot be papered over with defaults at
         * the call site — the previous code substituted a compatible schemaVersion and
         * `fieldsPresent = true` for an unparseable payload, which silently produced [APPLYING].
         */
        fun forPayload(
            read: PayloadRead,
            parsed: PublishedConfig?,
            currentHour: Int,
        ): HookApplicability = when {
            parsed != null -> of(
                mode = parsed.mode,
                schemaVersion = parsed.schemaVersion,
                currentHour = currentHour,
                activeStart = parsed.activeHourStart,
                activeEnd = parsed.activeHourEnd,
                fieldsPresent = parsed.fieldsPresent &&
                    (parsed.schemaVersion == ConfigPrefsSync.LEGACY_SCHEMA_VERSION || parsed.unavailablePresent),
            )
            read is PayloadRead.ReadError -> PAYLOAD_UNREADABLE
            read is PayloadRead.Raw -> PAYLOAD_MALFORMED
            else -> NEVER_PUBLISHED
        }
        fun of(
            mode: String,
            schemaVersion: Int,
            currentHour: Int,
            activeStart: Int? = null,
            activeEnd: Int? = null,
            fieldsPresent: Boolean = true,
        ): HookApplicability {
            // Checked first: a rejected payload never loads at all, so its mode and hours are moot.
            if (!TransportSchemaContract.supports(schemaVersion)) return SCHEMA_REJECTED
            if (mode == "off") return MODE_OFF
            if (mode == "time_based" && activeStart != null && activeEnd != null) {
                val inRange = if (activeStart <= activeEnd) {
                    currentHour >= activeStart && currentHour < activeEnd
                } else {
                    // A window wrapping past midnight, e.g. 22:00-07:00.
                    currentHour >= activeStart || currentHour < activeEnd
                }
                if (!inRange) return OUTSIDE_ACTIVE_HOURS
            }
            if (!fieldsPresent) return PAYLOAD_INCOMPLETE
            return APPLYING
        }
    }
}
