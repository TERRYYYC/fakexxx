package name.caiyao.fakegps.data.bundle

import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8: section codecs for the QWY half of the configuration bundle.
 *
 * Profiles travel as the ProfileEntityCodec draft map (the T2 canonical identity):
 * lossless for every Room column including the orthogonal unavailable set, without
 * re-running the CSV validator's per-field limits — device-collected values must
 * survive a migration untouched.
 * # 档案区段用 codec 的 draft 语义全列无损往返；不走 CSV 校验，避免采集值被复检拒收
 */
class QwyBundleSectionsCodecTest {

    private val fullProfile = ProfileEntity(
        id = 7L,
        latitude = 50.113339,
        longitude = 8.674141,
        altitude = 112.5,
        speed = 3.5f,
        bearing = 270.0f,
        accuracy = 8.0f,
        lac = 21000,
        cid = 21001123,
        addname = "hq-lte",
        // mcc/mnc are marked explicitly unavailable below — canonical entities never carry a
        // typed value AND the unavailable marker for the same column (the import chain zeroes it).
        mcc = null,
        mnc = null,
        arfcn = 41,
        bsic = 7,
        psc = 312,
        uarfcn = 10713,
        tac = 21872,
        ci = 210011234,
        pci = 401,
        earfcn = 1301,
        lteBandwidth = 20,
        nci = 210011234567L,
        nrarfcn = 633984,
        nrPci = 12,
        nrTac = 34011,
        gsmRssi = -71,
        gsmBer = 0,
        gsmTa = 3,
        wcdmaRssi = -85,
        wcdmaRscp = -95,
        wcdmaEcno = -6,
        lteRssi = -79,
        lteRsrp = -102,
        lteRsrq = -13,
        lteSinr = 3,
        lteCqi = 9,
        lteTa = 11,
        nrSsRsrp = -94,
        nrSsRsrq = -12,
        nrSsSinr = 8,
        nrCsiRsrp = -97,
        nrCsiRsrq = -15,
        nrCsiSinr = 6,
        signalFluctuationEnabled = 1,
        signalFluctuationRangeDb = 4,
        networkType = 13,
        dataNetworkType = 13,
        voiceNetworkType = 13,
        operatorName = "vodafone.de",
        operatorNumeric = "26202",
        simOperator = "26202",
        simOperatorName = "Vodafone",
        simCountryIso = "de",
        networkCountryIso = "de",
        isRoaming = 0,
        phoneType = 2,
        serviceState = 0,
        dataState = 2,
        dataActivity = 0,
        overrideNetworkType = 0,
        band = 3,
        channelBandwidth = 20000,
        cellBandwidthDownlink = 20000,
        physicalCellId = 401,
        wifiSsid = "\"lab\"",
        wifiBssid = "aa:bb:cc:dd:ee:ff",
        wifiRssi = -42,
        wifiFrequency = 5180,
        wifiLinkSpeed = 866,
        wifiTxLinkSpeed = 866,
        wifiRxLinkSpeed = 780,
        wifiChannel = 36,
        wifiStandard = 5,
        wifiSecurityType = 3,
        wifiMac = "11:22:33:44:55:66",
        wifiIp = "192.168.1.23",
        wifiHidden = 0,
        wifiEnabled = 1,
        localIpv4 = "10.0.0.7",
        localIpv6 = "fd00::7",
        dnsPrimary = "10.0.0.1",
        dnsSecondary = "10.0.0.2",
        gateway = "10.0.0.1",
        subnetMask = "255.255.255.0",
        connectionType = "wifi",
        interfaceName = "wlan0",
        neighborCellsJson = """[{"pci":402,"rsrp":-108}]""",
        unavailableFields = """["mcc","mnc"]""",
    )

    // ---- profiles section: lossless round trip ----

    @Test
    fun `profile with every column set round trips byte-for-byte through the section codec`() {
        val encoded = QwyBundleSections.encodeProfiles(listOf(fullProfile))
        val decoded = QwyBundleSections.decodeProfiles(
            encoded.toByteArray(Charsets.UTF_8),
        ).let { it as QwyBundleSections.ProfilesResult.Ok }

        assertEquals(1, decoded.profiles.size)
        // Room id is regenerated on import — equality is the canonical (id-less) identity.
        assertEquals(
            ProfileEntityCodec.canonical(fullProfile),
            ProfileEntityCodec.canonical(decoded.profiles.single()),
        )
        assertEquals("hq-lte", decoded.profiles.single().addname)
    }

    @Test
    fun `unavailable marker columns survive as the orthogonal set`() {
        val encoded = QwyBundleSections.encodeProfiles(listOf(fullProfile))
        val decoded = QwyBundleSections.decodeProfiles(
            encoded.toByteArray(Charsets.UTF_8),
        ) as QwyBundleSections.ProfilesResult.Ok

        assertEquals(
            setOf("mcc", "mnc"),
            name.caiyao.fakegps.config.UnavailableFieldSet.decode(
                decoded.profiles.single().unavailableFields,
            ),
        )
    }

    @Test
    fun `profile with only null columns round trips`() {
        val empty = ProfileEntity(id = 3L, addname = null)
        val decoded = QwyBundleSections.decodeProfiles(
            QwyBundleSections.encodeProfiles(listOf(empty)).toByteArray(Charsets.UTF_8),
        ) as QwyBundleSections.ProfilesResult.Ok

        assertEquals(empty.copy(id = 0), ProfileEntityCodec.canonical(decoded.profiles.single()))
    }

    // ---- active profile pointer ----

    @Test
    fun `active pointer resolves by content fingerprint after id regeneration`() {
        val fingerprint = QwyProfileFingerprint.of(fullProfile)
        val pointerJson = QwyBundleSections.encodeActiveProfile(
            fingerprint = fingerprint,
            addname = fullProfile.addname,
        )
        val decoded = QwyBundleSections.decodeActiveProfile(
            pointerJson.toByteArray(Charsets.UTF_8),
        ) as QwyBundleSections.PointerResult.Ok

        assertEquals(fingerprint, decoded.fingerprint)
        assertEquals("hq-lte", decoded.addname)

        // The imported row (new id) carries the same canonical content -> same fingerprint.
        val reimported = fullProfile.copy(id = 999L)
        assertEquals(fingerprint, QwyProfileFingerprint.of(reimported))
    }

    @Test
    fun `fingerprints differ when any profile field differs`() {
        val other = fullProfile.copy(lteRsrp = -101)
        assertNotEquals(QwyProfileFingerprint.of(fullProfile), QwyProfileFingerprint.of(other))
    }

    // ---- callers (pairing fingerprints): the privacy red line ----

    @Test
    fun `callers section carries only the fingerprint triple - no trust state`() {
        val json = QwyBundleSections.encodeCallers(
            listOf(
                QwyBundleSections.CallerFingerprint(
                    applicationId = "com.example.cellrebelauto",
                    signerDigest = "ab12cd34",
                    observedVersionCode = 15L,
                ),
            ),
        )

        val root = JSONObject(json)
        val caller = root.getJSONArray("callers").getJSONObject(0)
        val keys = mutableListOf<String>()
        val it = caller.keys()
        while (it.hasNext()) keys.add(it.next())
        // Only identity fields for human matching; approval/timestamps/trust never migrate.
        assertEquals(listOf("applicationId", "signerDigest", "observedVersionCode").sorted(), keys.sorted())

        val decoded = QwyBundleSections.decodeCallers(
            json.toByteArray(Charsets.UTF_8),
        ) as QwyBundleSections.CallersResult.Ok
        assertEquals("ab12cd34", decoded.callers.single().signerDigest)
    }
}
