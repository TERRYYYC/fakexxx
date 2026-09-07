package com.example.cellrebelauto.ui.dashboard.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/**
 * T7v2 §A1-v2 #2 — the CI hero's data source: a THIN TelephonyManager reader.
 *
 * Every value in [ServingCellReading] is the framework's raw answer — this
 * class computes nothing, chooses nothing (selection is the pure
 * [ServingCellSelector]) and never falls back to a configured value. The only
 * intelligence here is API-level degradation:
 *  - LTE: [CellInfoLte] exposes an int CI across the whole supported range
 *    (26..35) — no widening API exists;
 *  - NR: [CellInfoNr] only exists since API 29 — the SDK guard sits BEFORE the
 *    instanceof so the class is never touched on older builds;
 *  - unknown-valued fields (framework MAX_VALUE markers) are dropped to null
 *    instead of being rendered as absurd numbers.
 *
 * The COARSE location permission is declared and runtime-granted by the
 * onboarding (T2); a revoked permission or a failing radio read is a null
 * reading (hero shows "--"), never a crash.
 *
 * # 蜂窝读数器（薄）：值全部来自 TelephonyManager 原样；API 分级降级；失败=null
 */
class TelephonyServingCellPoller(private val context: Context) {

    fun probe(): ServingCellReading? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val all = try {
            telephony.allCellInfo
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        } ?: return null
        return ServingCellSelector.select(all.mapNotNull { map(it) })
    }

    /** Framework → reading; LTE/NR only (the RATs the worklist lane targets). */
    internal fun map(info: CellInfo): ServingCellReading? = when {
        info is CellInfoLte -> {
            val id = info.cellIdentity
            val strength = info.cellSignalStrength as? CellSignalStrengthLte
            ServingCellReading(
                rat = "LTE",
                // CellIdentityLte offers int ci across the whole supported API
                // range (26..35); MAX_VALUE is the framework's "unknown" marker.
                ci = id.ci.takeUnless { it == Int.MAX_VALUE || it < 0 }?.toLong(),
                tac = id.tac.takeUnless { it == Int.MAX_VALUE || it < 0 },
                pci = id.pci.takeUnless { it == Int.MAX_VALUE || it < 0 },
                // getMccString/getMncString are API 28+; below that the int
                // mcc/mnc getters (deprecated AT 28, functional before) carry
                // the same value — MAX_VALUE is the framework's "unknown".
                mcc = if (Build.VERSION.SDK_INT >= 28) id.mccString
                else @Suppress("DEPRECATION") id.mcc.takeUnless { it == Int.MAX_VALUE }?.toString(),
                mnc = if (Build.VERSION.SDK_INT >= 28) id.mncString
                else @Suppress("DEPRECATION") id.mnc.takeUnless { it == Int.MAX_VALUE }?.toString(),
                rsrpDbm = strength?.rsrp?.takeUnless { it == CellInfo.UNAVAILABLE || it == 0 },
                registered = info.isRegistered,
                readAtMs = System.currentTimeMillis(),
            )
        }
        Build.VERSION.SDK_INT >= 29 && info is CellInfoNr -> {
            // CellInfoNr.getCellIdentity() returns the BASE CellIdentity (no
            // covariant override) — an explicit cast to CellIdentityNr is required.
            val id = info.cellIdentity as? CellIdentityNr
            val strength = info.cellSignalStrength as? CellSignalStrengthNr
            ServingCellReading(
                rat = "NR",
                ci = id?.nci.takeUnless { it == null || it == Long.MAX_VALUE || it < 0 },
                tac = id?.tac.takeUnless { it == null || it == Int.MAX_VALUE || it < 0 },
                pci = id?.pci.takeUnless { it == null || it == Int.MAX_VALUE || it < 0 },
                mcc = id?.mccString,
                mnc = id?.mncString,
                rsrpDbm = strength?.ssRsrp?.takeUnless { it == CellInfo.UNAVAILABLE || it == 0 },
                registered = info.isRegistered,
                readAtMs = System.currentTimeMillis(),
            )
        }
        else -> null
    }
}
