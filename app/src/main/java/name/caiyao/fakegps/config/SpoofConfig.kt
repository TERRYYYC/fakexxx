package name.caiyao.fakegps.config

import kotlinx.serialization.Serializable

/**
 * Canonical spoofing configuration — the single source of truth for what the hook
 * layer publishes into MainHook.CURRENT.
 *
 * Semantics (mirrors Snapshot's `NULL = passthrough` contract):
 *   - a field that is null (or absent from JSON) = PASSTHROUGH (use the real device value)
 *   - a non-null field                          = spoof with this value
 *
 * This type is @Serializable so config can cross the process boundary as a
 * fingerprinted JSON snapshot (see [ConfigCodec]) instead of the exported ContentProvider
 * that any app — including the app under test — could query.
 *
 * SCOPE NOTE: fields are migrated group-by-group from Snapshot (A–Q). This commit
 * establishes the transport CONTRACT + core fields (location / LTE cell / WiFi);
 * remaining cellular/wifi/ip fields follow in later commits. SpoofConfig is meant to
 * become the canonical schema the other field definitions converge onto (Snapshot,
 * ProfileEntity, FieldSpec×2) — NOT a 6th source of truth.
 */
@Serializable
data class SpoofConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val mode: Mode = Mode.always_on,
    val activeHours: ActiveHours? = null,
    val location: Location? = null,
    val lteCell: LteCell? = null,
    val wifi: Wifi? = null,
) {
    @Serializable
    enum class Mode { always_on, time_based, off }

    @Serializable
    data class ActiveHours(val start: Int, val end: Int)

    // A. LOCATION
    @Serializable
    data class Location(
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitude: Double? = null,   // meters
        val speed: Float? = null,       // m/s
        val bearing: Float? = null,     // degrees
        val accuracy: Float? = null,    // meters
    )

    // D+H. LTE CELL IDENTITY + SIGNAL (core subset; full A–Q to follow)
    @Serializable
    data class LteCell(
        val tac: Int? = null,
        val ci: Int? = null,            // 28-bit Cell Identity
        val pci: Int? = null,           // Physical Cell ID (0-503)
        val earfcn: Int? = null,        // E-UTRA ARFCN
        val rsrp: Int? = null,          // -140..-44 dBm
        val rsrq: Int? = null,          // -20..-3 dB
        val sinr: Int? = null,          // -20..+30 dB
    )

    // O. WIFI (core subset)
    @Serializable
    data class Wifi(
        val ssid: String? = null,
        val bssid: String? = null,
        val rssi: Int? = null,          // -90..-40 dBm
        val frequency: Int? = null,     // MHz
    )

    companion object {
        const val SCHEMA_VERSION = 1

        /** Everything null = passthrough all real values. */
        val PASSTHROUGH = SpoofConfig()
    }
}
