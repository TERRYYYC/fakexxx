package name.caiyao.fakegps.probe

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhysicalChannelConfig
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Read-back probe for the public Android APIs intercepted by the module.
 *
 * The acceptance activity supplies a session ID and persists the returned JSON in its private
 * cache so the host harness is not limited by logcat's message size. The normal debug UI keeps the
 * legacy one-line log for quick diagnostics.
 */
object HookProbe {
    const val TAG = "FakeGPSProbe"
    private const val CALLBACK_TIMEOUT_SECONDS = 5L
    private const val ACCEPTANCE_SIGNAL_SAMPLE_COUNT = 256

    @SuppressLint("MissingPermission", "NewApi")
    fun run(context: Context, sessionId: String? = null): JSONObject {
        val errors = JSONArray()
        val out = JSONObject()
        out.put("contractVersion", ProbeFieldContract.VERSION)
        out.put("apiLevel", Build.VERSION.SDK_INT)
        sessionId?.let { out.put("sessionId", it) }
        val signalSampleCount = if (sessionId == null) {
            0
        } else {
            ACCEPTANCE_SIGNAL_SAMPLE_COUNT
        }

        collectLocation(context, out, errors)

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val cellInfo = JSONObject()
        val synchronous = try {
            collectCells(
                tm.allCellInfo.orEmpty(),
                errors,
                "cellInfo.sync",
                signalSampleCount,
            )
        } catch (failure: Throwable) {
            recordError(errors, "cellInfo.sync", failure)
            JSONObject()
        }
        cellInfo.put("sync", synchronous)

        if (Build.VERSION.SDK_INT >= 29) {
            val request = requestCellInfo(tm, errors, signalSampleCount)
            cellInfo.put("request", request.cells)
            cellInfo.put("requestCompleted", request.completed)
        }
        out.put("cellInfo", cellInfo)
        out.put("telephony", collectTelephony(tm, errors))

        if (Build.VERSION.SDK_INT >= 31) {
            out.put(
                "callback",
                collectTelephonyCallbacks(
                    tm,
                    errors,
                    requirePhysicalChannel = sessionId != null,
                    signalSampleCount = signalSampleCount,
                ),
            )
        }

        collectWifi(context, out, errors)
        out.put("errors", errors)
        Log.w(TAG, out.toString())
        return out
    }

    @SuppressLint("MissingPermission")
    private fun collectLocation(context: Context, out: JSONObject, errors: JSONArray) {
        val location = JSONObject()
        try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val best = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            putValue(location, "latitude", best?.latitude)
            putValue(location, "longitude", best?.longitude)
            putValue(location, "altitude", best?.altitude)
            putValue(location, "accuracy", best?.accuracy)
            putValue(location, "isMock", best?.isMock)
            putValue(location, "provider", best?.provider)
        } catch (failure: Throwable) {
            recordError(errors, "location", failure)
        }
        out.put("location", location)
    }

    private fun collectCells(
        cells: List<CellInfo>,
        errors: JSONArray,
        stage: String,
        signalSampleCount: Int,
    ): JSONObject {
        val observed = JSONObject()
        val neighbors = JSONObject()
        for (cell in cells) {
            val isNeighbor = try {
                !cell.isRegistered
            } catch (failure: Throwable) {
                recordError(errors, "$stage.registration", failure)
                false
            }
            if (isNeighbor) {
                when {
                    cell is CellInfoLte && !neighbors.has("lte") ->
                        neighbors.put(
                            "lte",
                            observeLte(
                                cell,
                                errors,
                                "$stage.neighbors.lte",
                                signalSampleCount,
                            ),
                        )
                    cell is CellInfoGsm && !neighbors.has("gsm") ->
                        neighbors.put("gsm", observeGsm(cell, errors, "$stage.neighbors.gsm"))
                    cell is CellInfoWcdma && !neighbors.has("wcdma") ->
                        neighbors.put(
                            "wcdma",
                            observeWcdma(cell, errors, "$stage.neighbors.wcdma"),
                        )
                }
                continue
            }
            when {
                cell is CellInfoLte && !observed.has("lte") ->
                    observed.put(
                        "lte",
                        observeLte(
                            cell,
                            errors,
                            "$stage.lte",
                            signalSampleCount,
                        ),
                    )
                cell is CellInfoGsm && !observed.has("gsm") ->
                    observed.put("gsm", observeGsm(cell, errors, "$stage.gsm"))
                cell is CellInfoWcdma && !observed.has("wcdma") ->
                    observed.put("wcdma", observeWcdma(cell, errors, "$stage.wcdma"))
                Build.VERSION.SDK_INT >= 29 && !observed.has("nr") ->
                    observeNrIfPresent(cell, errors, "$stage.nr")
                        ?.let { observed.put("nr", it) }
            }
        }
        if (neighbors.length() > 0) {
            observed.put("neighbors", neighbors)
        }
        return observed
    }

    @Suppress("DEPRECATION")
    private fun observeLte(
        cell: CellInfoLte,
        errors: JSONArray,
        stage: String,
        signalSampleCount: Int,
    ): JSONObject {
        val out = JSONObject()
        val id: CellIdentityLte = cell.cellIdentity
        val signal: CellSignalStrengthLte = cell.cellSignalStrength
        observe(out, "registered", errors, stage) { cell.isRegistered }
        if (Build.VERSION.SDK_INT >= 28) {
            observe(out, "mccString", errors, stage) { id.mccString }
            observe(out, "mncString", errors, stage) { id.mncString }
        } else {
            observe(out, "mccString", errors, stage) { id.mcc.toString() }
            observe(out, "mncString", errors, stage) { id.mnc.toString() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            observeIdentityCarrier(out, id, errors, stage)
        }
        observe(out, "tac", errors, stage) { id.tac }
        observe(out, "ci", errors, stage) { id.ci }
        observe(out, "pci", errors, stage) { id.pci }
        observe(out, "earfcn", errors, stage) { id.earfcn }
        observe(out, "bandwidth", errors, stage) { id.bandwidth }
        observe(out, "dbm", errors, stage) { signal.dbm }
        observe(out, "rsrp", errors, stage) { signal.rsrp }
        if (signalSampleCount > 0) {
            val samples = JSONArray()
            repeat(signalSampleCount) {
                samples.put(signal.rsrp)
            }
            out.put("rsrpSamples", samples)
        }
        observe(out, "rsrq", errors, stage) { signal.rsrq }
        observe(out, "rssnr", errors, stage) { signal.rssnr }
        observe(out, "cqi", errors, stage) { signal.cqi }
        observe(out, "timingAdvance", errors, stage) { signal.timingAdvance }
        if (Build.VERSION.SDK_INT >= 29) {
            observe(out, "rssi", errors, stage) { signal.rssi }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun observeGsm(
        cell: CellInfoGsm,
        errors: JSONArray,
        stage: String,
    ): JSONObject {
        val out = JSONObject()
        val id: CellIdentityGsm = cell.cellIdentity
        val signal: CellSignalStrengthGsm = cell.cellSignalStrength
        observe(out, "registered", errors, stage) { cell.isRegistered }
        if (Build.VERSION.SDK_INT >= 28) {
            observe(out, "mccString", errors, stage) { id.mccString }
            observe(out, "mncString", errors, stage) { id.mncString }
        } else {
            observe(out, "mccString", errors, stage) { id.mcc.toString() }
            observe(out, "mncString", errors, stage) { id.mnc.toString() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            observeIdentityCarrier(out, id, errors, stage)
        }
        observe(out, "lac", errors, stage) { id.lac }
        observe(out, "cid", errors, stage) { id.cid }
        observe(out, "arfcn", errors, stage) { id.arfcn }
        observe(out, "bsic", errors, stage) { id.bsic }
        observe(out, "dbm", errors, stage) { signal.dbm }
        observe(out, "bitErrorRate", errors, stage) { signal.bitErrorRate }
        if (Build.VERSION.SDK_INT >= 26) {
            observe(out, "timingAdvance", errors, stage) { signal.timingAdvance }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun observeWcdma(
        cell: CellInfoWcdma,
        errors: JSONArray,
        stage: String,
    ): JSONObject {
        val out = JSONObject()
        val id: CellIdentityWcdma = cell.cellIdentity
        val signal: CellSignalStrengthWcdma = cell.cellSignalStrength
        observe(out, "registered", errors, stage) { cell.isRegistered }
        if (Build.VERSION.SDK_INT >= 28) {
            observe(out, "mccString", errors, stage) { id.mccString }
            observe(out, "mncString", errors, stage) { id.mncString }
        } else {
            observe(out, "mccString", errors, stage) { id.mcc.toString() }
            observe(out, "mncString", errors, stage) { id.mnc.toString() }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            observeIdentityCarrier(out, id, errors, stage)
        }
        observe(out, "lac", errors, stage) { id.lac }
        observe(out, "cid", errors, stage) { id.cid }
        observe(out, "psc", errors, stage) { id.psc }
        observe(out, "uarfcn", errors, stage) { id.uarfcn }
        observe(out, "dbm", errors, stage) { signal.dbm }
        if (Build.VERSION.SDK_INT >= 29) {
            observe(out, "ecNo", errors, stage) { signal.ecNo }
        }
        return out
    }

    @RequiresApi(29)
    private fun observeNrIfPresent(
        cell: CellInfo,
        errors: JSONArray,
        stage: String,
    ): JSONObject? {
        if (cell !is CellInfoNr) return null
        val out = JSONObject()
        val id = cell.cellIdentity as CellIdentityNr
        val signal = cell.cellSignalStrength as CellSignalStrengthNr
        observe(out, "registered", errors, stage) { cell.isRegistered }
        observe(out, "mccString", errors, stage) { id.mccString }
        observe(out, "mncString", errors, stage) { id.mncString }
        observeIdentityCarrier(out, id, errors, stage)
        observe(out, "nci", errors, stage) { id.nci }
        observe(out, "nrarfcn", errors, stage) { id.nrarfcn }
        observe(out, "pci", errors, stage) { id.pci }
        observe(out, "tac", errors, stage) { id.tac }
        observe(out, "dbm", errors, stage) { signal.dbm }
        observe(out, "ssRsrp", errors, stage) { signal.ssRsrp }
        observe(out, "ssRsrq", errors, stage) { signal.ssRsrq }
        observe(out, "ssSinr", errors, stage) { signal.ssSinr }
        observe(out, "csiRsrp", errors, stage) { signal.csiRsrp }
        observe(out, "csiRsrq", errors, stage) { signal.csiRsrq }
        observe(out, "csiSinr", errors, stage) { signal.csiSinr }
        return out
    }

    @RequiresApi(28)
    private fun observeIdentityCarrier(
        out: JSONObject,
        identity: CellIdentity,
        errors: JSONArray,
        stage: String,
    ) {
        observe(out, "operatorAlphaLong", errors, stage) {
            identity.operatorAlphaLong?.toString()
        }
        observe(out, "operatorAlphaShort", errors, stage) {
            identity.operatorAlphaShort?.toString()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun observeServiceState(
        serviceState: ServiceState,
        errors: JSONArray,
        stage: String,
    ): JSONObject {
        val out = JSONObject()
        observe(out, "state", errors, stage) { serviceState.state }
        observe(out, "operatorAlphaLong", errors, stage) {
            serviceState.operatorAlphaLong
        }
        observe(out, "operatorAlphaShort", errors, stage) {
            serviceState.operatorAlphaShort
        }
        observe(out, "operatorNumeric", errors, stage) {
            serviceState.operatorNumeric
        }
        observe(out, "roaming", errors, stage) { serviceState.roaming }

        if (Build.VERSION.SDK_INT >= 30) {
            val registration = try {
                val registrations = serviceState.networkRegistrationInfoList
                registrations.firstOrNull { it.cellIdentity != null }
                    ?: registrations.firstOrNull()
            } catch (failure: Throwable) {
                recordError(errors, "$stage.networkRegistrationInfo", failure)
                null
            }
            observe(out, "registrationPlmn", errors, stage) {
                registration?.registeredPlmn
            }
            observe(out, "registrationRoaming", errors, stage) {
                if (Build.VERSION.SDK_INT >= 34) {
                    registration?.isNetworkRoaming
                } else {
                    registration?.isRoaming
                }
            }
            observe(out, "registrationOperatorAlphaLong", errors, stage) {
                registration?.cellIdentity?.operatorAlphaLong?.toString()
            }
            observe(out, "registrationOperatorAlphaShort", errors, stage) {
                registration?.cellIdentity?.operatorAlphaShort?.toString()
            }
        }
        return out
    }

    @RequiresApi(29)
    @SuppressLint("MissingPermission")
    private fun requestCellInfo(
        manager: TelephonyManager,
        errors: JSONArray,
        signalSampleCount: Int,
    ): CellInfoRequestResult {
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val result = AtomicReference<List<CellInfo>>(emptyList())
        var completed = false
        try {
            manager.requestCellInfoUpdate(
                executor,
                object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        result.set(cellInfo.toList())
                        latch.countDown()
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        recordError(
                            errors,
                            "cellInfo.request",
                            IllegalStateException("errorCode=$errorCode", detail),
                        )
                        latch.countDown()
                    }
                },
            )
            completed = latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Throwable) {
            recordError(errors, "cellInfo.request", failure)
        } finally {
            executor.shutdownNow()
        }
        return CellInfoRequestResult(
            cells = collectCells(
                result.get(),
                errors,
                "cellInfo.request",
                signalSampleCount,
            ),
            completed = completed,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun collectTelephony(
        manager: TelephonyManager,
        errors: JSONArray,
    ): JSONObject {
        val out = JSONObject()
        observe(out, "networkOperatorName", errors, "telephony") {
            manager.networkOperatorName
        }
        observe(out, "networkOperator", errors, "telephony") { manager.networkOperator }
        observe(out, "simOperator", errors, "telephony") { manager.simOperator }
        observe(out, "simOperatorName", errors, "telephony") { manager.simOperatorName }
        observe(out, "simCountryIso", errors, "telephony") { manager.simCountryIso }
        observe(out, "networkCountryIso", errors, "telephony") { manager.networkCountryIso }
        observe(out, "isNetworkRoaming", errors, "telephony") { manager.isNetworkRoaming }
        observe(out, "phoneType", errors, "telephony") { manager.phoneType }
        observe(out, "networkType", errors, "telephony") { manager.networkType }
        observe(out, "dataNetworkType", errors, "telephony") { manager.dataNetworkType }
        observe(out, "voiceNetworkType", errors, "telephony") { manager.voiceNetworkType }
        observe(out, "dataState", errors, "telephony") { manager.dataState }
        observe(out, "dataActivity", errors, "telephony") { manager.dataActivity }
        if (Build.VERSION.SDK_INT >= 26) {
            val serviceState = try {
                manager.serviceState
            } catch (failure: Throwable) {
                recordError(errors, "telephony.serviceState", failure)
                null
            }
            observe(out, "serviceState", errors, "telephony") { serviceState?.state }
            if (serviceState != null) {
                out.put(
                    "serviceStateDetails",
                    observeServiceState(serviceState, errors, "telephony.serviceStateDetails"),
                )
            }
        }
        return out
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun collectTelephonyCallbacks(
        manager: TelephonyManager,
        errors: JSONArray,
        requirePhysicalChannel: Boolean,
        signalSampleCount: Int,
    ): JSONObject {
        val ordinary = collectOrdinaryTelephonyCallbacks(
            manager,
            errors,
            signalSampleCount,
        )
        val physical = if (requirePhysicalChannel) {
            collectPhysicalChannelCallback(manager, errors)
        } else {
            PhysicalChannelResult(
                value = JSONObject(),
                completed = true,
                delivery = "not_requested",
            )
        }
        val missing = JSONArray()
        val ordinaryMissing = ordinary.getJSONArray("missing")
        for (index in 0 until ordinaryMissing.length()) {
            missing.put(ordinaryMissing.getString(index))
        }
        if (!physical.completed) {
            missing.put("physicalChannel")
        }
        return JSONObject()
            .put(
                "completed",
                ordinary.getBoolean("completed") && physical.completed,
            )
            .put("missing", missing)
            .put("cellInfo", ordinary.getJSONObject("cellInfo"))
            .put("serviceState", ordinary.getJSONObject("serviceState"))
            .put("displayInfo", ordinary.getJSONObject("displayInfo"))
            .put("physicalChannel", physical.value)
            .put("physicalChannelDelivery", physical.delivery)
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun collectOrdinaryTelephonyCallbacks(
        manager: TelephonyManager,
        errors: JSONArray,
        signalSampleCount: Int,
    ): JSONObject {
        val executor = Executors.newSingleThreadExecutor()
        val callback = AcceptanceTelephonyCallback(errors, signalSampleCount)
        var registered = false
        try {
            manager.registerTelephonyCallback(executor, callback)
            registered = true
            callback.await()
        } catch (failure: Throwable) {
            recordError(errors, "callback.registration", failure)
        } finally {
            if (registered) {
                try {
                    manager.unregisterTelephonyCallback(callback)
                } catch (failure: Throwable) {
                    recordError(errors, "callback.unregister", failure)
                }
            }
            executor.shutdownNow()
        }
        return callback.toJson()
    }

    /**
     * Physical-channel callbacks require the signature|privileged
     * READ_PRECISE_PHONE_STATE permission. A normal debug APK cannot obtain it, even on a rooted
     * test device. Registration still crosses HookUtils' in-process before-hook first, so after
     * the framework rejects the listener we replay one empty callback locally. The production
     * hook must replace that empty list with its configured PhysicalChannelConfig; otherwise this
     * probe records an error and the acceptance verdict fails.
     */
    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun collectPhysicalChannelCallback(
        manager: TelephonyManager,
        errors: JSONArray,
    ): PhysicalChannelResult {
        val executor = Executors.newSingleThreadExecutor()
        val callback = AcceptancePhysicalChannelCallback(errors)
        var registered = false
        var delivery = "framework"
        try {
            manager.registerTelephonyCallback(executor, callback)
            registered = true
            callback.await()
        } catch (_: SecurityException) {
            delivery = "hook_replay_after_permission_denied"
            callback.onPhysicalChannelConfigChanged(mutableListOf())
        } catch (failure: Throwable) {
            recordError(errors, "callback.physicalChannel.registration", failure)
        } finally {
            if (registered) {
                try {
                    manager.unregisterTelephonyCallback(callback)
                } catch (failure: Throwable) {
                    recordError(errors, "callback.physicalChannel.unregister", failure)
                }
            }
            executor.shutdownNow()
        }
        return PhysicalChannelResult(
            value = callback.value(),
            completed = callback.wasSeen(),
            delivery = delivery,
        )
    }

    @RequiresApi(31)
    private class AcceptanceTelephonyCallback(
        private val errors: JSONArray,
        private val signalSampleCount: Int,
    ) : TelephonyCallback(),
        TelephonyCallback.CellInfoListener,
        TelephonyCallback.ServiceStateListener,
        TelephonyCallback.DisplayInfoListener {

        private val latch = CountDownLatch(3)
        private val cellSeen = AtomicBoolean(false)
        private val serviceSeen = AtomicBoolean(false)
        private val displaySeen = AtomicBoolean(false)
        private val cells = AtomicReference(JSONObject())
        private val service = AtomicReference(JSONObject())
        private val display = AtomicReference(JSONObject())

        override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
            cells.set(
                collectCells(
                    cellInfo,
                    errors,
                    "callback.cellInfo",
                    signalSampleCount,
                ),
            )
            mark(cellSeen)
        }

        @Suppress("DEPRECATION")
        override fun onServiceStateChanged(serviceState: ServiceState) {
            service.set(observeServiceState(serviceState, errors, "callback.serviceState"))
            mark(serviceSeen)
        }

        override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
            val out = JSONObject()
            observe(out, "networkType", errors, "callback.displayInfo") {
                telephonyDisplayInfo.networkType
            }
            observe(out, "overrideNetworkType", errors, "callback.displayInfo") {
                telephonyDisplayInfo.overrideNetworkType
            }
            display.set(out)
            mark(displaySeen)
        }

        fun await(): Boolean = latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun missingCallbacks(): List<String> = buildList {
            if (!cellSeen.get()) add("cellInfo")
            if (!serviceSeen.get()) add("serviceState")
            if (!displaySeen.get()) add("displayInfo")
        }

        fun toJson(): JSONObject = JSONObject()
            .put("completed", missingCallbacks().isEmpty())
            .put("missing", JSONArray(missingCallbacks()))
            .put("cellInfo", cells.get())
            .put("serviceState", service.get())
            .put("displayInfo", display.get())

        private fun mark(flag: AtomicBoolean) {
            if (flag.compareAndSet(false, true)) {
                latch.countDown()
            }
        }
    }

    @RequiresApi(31)
    private class AcceptancePhysicalChannelCallback(
        private val errors: JSONArray,
    ) : TelephonyCallback(),
        TelephonyCallback.PhysicalChannelConfigListener {

        private val latch = CountDownLatch(1)
        private val seen = AtomicBoolean(false)
        private val physical = AtomicReference(JSONObject())

        override fun onPhysicalChannelConfigChanged(configs: MutableList<PhysicalChannelConfig>) {
            val out = JSONObject()
            val config = configs.firstOrNull()
            if (config == null) {
                recordError(
                    errors,
                    "callback.physicalChannel",
                    IllegalStateException("empty PhysicalChannelConfig list"),
                )
            } else {
                observe(out, "cellBandwidthDownlinkKhz", errors, "callback.physicalChannel") {
                    config.cellBandwidthDownlinkKhz
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    observe(out, "cellBandwidthUplinkKhz", errors, "callback.physicalChannel") {
                        config.cellBandwidthUplinkKhz
                    }
                }
                observe(out, "physicalCellId", errors, "callback.physicalChannel") {
                    config.physicalCellId
                }
                observe(out, "connectionStatus", errors, "callback.physicalChannel") {
                    config.connectionStatus
                }
                observe(out, "networkType", errors, "callback.physicalChannel") {
                    config.networkType
                }
                observe(out, "band", errors, "callback.physicalChannel") { config.band }
                observe(out, "downlinkChannelNumber", errors, "callback.physicalChannel") {
                    config.downlinkChannelNumber
                }
                observe(out, "uplinkChannelNumber", errors, "callback.physicalChannel") {
                    config.uplinkChannelNumber
                }
            }
            physical.set(out)
            if (seen.compareAndSet(false, true)) {
                latch.countDown()
            }
        }

        fun await(): Boolean = latch.await(CALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        fun wasSeen(): Boolean = seen.get()

        fun value(): JSONObject = physical.get()
    }

    private fun collectWifi(context: Context, out: JSONObject, errors: JSONArray) {
        val wifi = JSONObject()
        try {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = manager.connectionInfo
            @Suppress("DEPRECATION")
            putValue(wifi, "ssid", info?.ssid)
            @Suppress("DEPRECATION")
            putValue(wifi, "bssid", info?.bssid)
            @Suppress("DEPRECATION")
            putValue(wifi, "rssi", info?.rssi)
            @Suppress("DEPRECATION")
            putValue(wifi, "frequency", info?.frequency)
        } catch (failure: Throwable) {
            recordError(errors, "wifi", failure)
        }
        out.put("wifi", wifi)
    }

    private inline fun observe(
        target: JSONObject,
        key: String,
        errors: JSONArray,
        stage: String,
        value: () -> Any?,
    ) {
        try {
            putValue(target, key, value())
        } catch (failure: Throwable) {
            recordError(errors, "$stage.$key", failure)
        }
    }

    private fun putValue(target: JSONObject, key: String, value: Any?) {
        target.put(key, value ?: JSONObject.NULL)
    }

    private fun recordError(errors: JSONArray, stage: String, failure: Throwable) {
        synchronized(errors) {
            errors.put(
                JSONObject()
                    .put("stage", stage)
                    .put("error", failure.toString()),
            )
        }
    }

    private data class CellInfoRequestResult(
        val cells: JSONObject,
        val completed: Boolean,
    )

    private data class PhysicalChannelResult(
        val value: JSONObject,
        val completed: Boolean,
        val delivery: String,
    )
}
