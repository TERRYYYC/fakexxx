package name.caiyao.fakegps.oracle

import android.os.Bundle
import java.util.UUID

/**
 * Strict private Bundle schema used only between QWY and its injected system-server producer.
 * Unknown/missing values are rejected so a future producer cannot be interpreted as v1 by
 * accident. The pure field-map seam keeps all validation executable in host JVM tests.
 */
data class OracleWireSnapshot(
    val protocolVersion: Int,
    val bootId: String,
    val oracleInstanceId: String,
    val sequence: Long,
    val ownerUid: Int?,
    val ownerPackage: String?,
    val gpsProviderEnabled: Boolean,
    val networkProviderEnabled: Boolean,
    val requiredCoverageMask: Long,
    val installedCoverageMask: Long,
    val health: OracleWireHealth,
    val qwySemanticDigest: String?,
    val lastCompletedQwyMutationId: String?,
)

enum class OracleWireHealth {
    HEALTHY,
    /** Hooks may be observed on one exact evidence build, but authority stays unattested. */
    EVIDENCE_ONLY_READY,
    BUILD_UNATTESTED,
    UNSUPPORTED_PLATFORM,
    BOOT_ID_UNAVAILABLE,
    HOOKS_INCOMPLETE,
    BRIDGE_UNAVAILABLE,
    SESSION_UNAVAILABLE,
    ENDPOINT_UNAVAILABLE,
    CALLBACK_POISONED,
    INVARIANT_FAILURE,
}

object OracleBundleCodec {
    const val PROTOCOL_VERSION: Int = 1
    const val NO_OWNER_UID: Int = -1

    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_BOOT_ID = "bootId"
    const val KEY_ORACLE_INSTANCE_ID = "oracleInstanceId"
    const val KEY_SEQUENCE = "sequence"
    const val KEY_OWNER_UID = "ownerUid"
    const val KEY_OWNER_PACKAGE = "ownerPackage"
    const val KEY_GPS_PROVIDER_ENABLED = "gpsProviderEnabled"
    const val KEY_NETWORK_PROVIDER_ENABLED = "networkProviderEnabled"
    const val KEY_REQUIRED_COVERAGE_MASK = "requiredCoverageMask"
    const val KEY_INSTALLED_COVERAGE_MASK = "installedCoverageMask"
    const val KEY_HEALTH = "health"
    const val KEY_QWY_SEMANTIC_DIGEST = "qwySemanticDigest"
    const val KEY_LAST_COMPLETED_QWY_MUTATION_ID = "lastCompletedQwyMutationId"

    private val exactKeys = setOf(
        KEY_PROTOCOL_VERSION,
        KEY_BOOT_ID,
        KEY_ORACLE_INSTANCE_ID,
        KEY_SEQUENCE,
        KEY_OWNER_UID,
        KEY_OWNER_PACKAGE,
        KEY_GPS_PROVIDER_ENABLED,
        KEY_NETWORK_PROVIDER_ENABLED,
        KEY_REQUIRED_COVERAGE_MASK,
        KEY_INSTALLED_COVERAGE_MASK,
        KEY_HEALTH,
        KEY_QWY_SEMANTIC_DIGEST,
        KEY_LAST_COMPLETED_QWY_MUTATION_ID,
    )

    @JvmStatic
    fun encode(snapshot: OracleWireSnapshot): Bundle = Bundle().apply {
        encodeFields(snapshot).forEach { (key, value) ->
            when (value) {
                null -> putString(key, null)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                else -> error("unsupported oracle field type for $key")
            }
        }
    }

    @JvmStatic
    fun decode(bundle: Bundle?): OracleWireSnapshot? {
        if (bundle == null) return null
        return try {
            val fields = bundle.keySet().associateWith { key -> bundle.get(key) }
            decodeFields(fields)
        } catch (_: RuntimeException) {
            null
        }
    }

    @JvmStatic
    fun encodeFields(snapshot: OracleWireSnapshot): Map<String, Any?> = linkedMapOf(
        KEY_PROTOCOL_VERSION to snapshot.protocolVersion,
        KEY_BOOT_ID to snapshot.bootId,
        KEY_ORACLE_INSTANCE_ID to snapshot.oracleInstanceId,
        KEY_SEQUENCE to snapshot.sequence,
        KEY_OWNER_UID to (snapshot.ownerUid ?: NO_OWNER_UID),
        KEY_OWNER_PACKAGE to snapshot.ownerPackage,
        KEY_GPS_PROVIDER_ENABLED to snapshot.gpsProviderEnabled,
        KEY_NETWORK_PROVIDER_ENABLED to snapshot.networkProviderEnabled,
        KEY_REQUIRED_COVERAGE_MASK to snapshot.requiredCoverageMask,
        KEY_INSTALLED_COVERAGE_MASK to snapshot.installedCoverageMask,
        KEY_HEALTH to snapshot.health.name,
        KEY_QWY_SEMANTIC_DIGEST to snapshot.qwySemanticDigest,
        KEY_LAST_COMPLETED_QWY_MUTATION_ID to snapshot.lastCompletedQwyMutationId,
    )

    @JvmStatic
    fun decodeFields(fields: Map<String, Any?>): OracleWireSnapshot? {
        if (fields.keys != exactKeys) return null
        val protocolVersion = fields[KEY_PROTOCOL_VERSION] as? Int ?: return null
        if (protocolVersion != PROTOCOL_VERSION) return null
        val bootId = (fields[KEY_BOOT_ID] as? String)?.takeIf(::isKernelBootId) ?: return null
        val oracleInstanceId = (fields[KEY_ORACLE_INSTANCE_ID] as? String)
            ?.takeIf { it.isNotBlank() } ?: return null
        val sequence = (fields[KEY_SEQUENCE] as? Long)?.takeIf { it >= 0L } ?: return null
        val rawOwnerUid = fields[KEY_OWNER_UID] as? Int ?: return null
        val ownerUid = rawOwnerUid.takeUnless { it == NO_OWNER_UID }
        if (ownerUid != null && ownerUid < 0) return null
        val ownerPackage = fields[KEY_OWNER_PACKAGE] as? String
        if ((ownerUid == null) != (ownerPackage == null)) return null
        if (ownerPackage != null && ownerPackage.isBlank()) return null
        val gpsEnabled = fields[KEY_GPS_PROVIDER_ENABLED] as? Boolean ?: return null
        val networkEnabled = fields[KEY_NETWORK_PROVIDER_ENABLED] as? Boolean ?: return null
        val requiredMask = (fields[KEY_REQUIRED_COVERAGE_MASK] as? Long)
            ?.takeIf { it >= 0L } ?: return null
        val installedMask = (fields[KEY_INSTALLED_COVERAGE_MASK] as? Long)
            ?.takeIf { it >= 0L } ?: return null
        val healthName = fields[KEY_HEALTH] as? String ?: return null
        val health = OracleWireHealth.entries.firstOrNull { it.name == healthName } ?: return null
        val semanticDigest = fields[KEY_QWY_SEMANTIC_DIGEST] as? String
        val mutationId = fields[KEY_LAST_COMPLETED_QWY_MUTATION_ID] as? String
        if (semanticDigest != null && semanticDigest.isBlank()) return null
        if (mutationId != null && mutationId.isBlank()) return null

        return OracleWireSnapshot(
            protocolVersion = protocolVersion,
            bootId = bootId,
            oracleInstanceId = oracleInstanceId,
            sequence = sequence,
            ownerUid = ownerUid,
            ownerPackage = ownerPackage,
            gpsProviderEnabled = gpsEnabled,
            networkProviderEnabled = networkEnabled,
            requiredCoverageMask = requiredMask,
            installedCoverageMask = installedMask,
            health = health,
            qwySemanticDigest = semanticDigest,
            lastCompletedQwyMutationId = mutationId,
        )
    }

    @JvmStatic
    fun isKernelBootId(value: String): Boolean = try {
        value.isNotBlank() && UUID.fromString(value).toString().equals(value, ignoreCase = true)
    } catch (_: IllegalArgumentException) {
        false
    }
}
