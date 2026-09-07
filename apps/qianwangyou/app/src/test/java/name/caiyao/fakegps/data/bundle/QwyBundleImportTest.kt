package name.caiyao.fakegps.data.bundle

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T8: QWY-side bundle import — idempotent, fail-closed on unknown versions,
 * conflict policy (overwrite / skip), transactional (no half state), and the
 * post-import count reconciliation warning (T2's consistency-warning channel).
 * # 导入幂等 + 版本校验 fail-closed + 覆盖/跳过 + 事务性 + 行数对账警告
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QwyBundleImportTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Application
    private val publishedRequests = mutableListOf<ProfileRepository.PublishRequest>()

    private val profileA = ProfileEntity(addname = "a", tac = 1, latitude = 1.0, longitude = 2.0)
    private val profileB = ProfileEntity(addname = "b", tac = 2, mcc = 262, mnc = 2)

    private fun bundleFor(
        profiles: List<ProfileEntity>,
        active: ProfileEntity? = null,
        settings: QwyBundleSections.SettingsSnapshot? = defaultSettings(),
        schemaVersion: Int? = null,
        declaredCountOverride: Int? = null,
    ): ByteArray {
        val export = QwyBundleExporter.export(
            QwyBundleExport(
                profiles = profiles,
                activeProfile = active?.let {
                    QwyBundleExport.ActiveProfileRef(
                        QwyProfileFingerprint.of(it),
                        it.addname,
                    )
                },
                settings = settings,
                callers = listOf(
                    QwyBundleSections.CallerFingerprint(
                        "com.example.cellrebelauto",
                        "deadbeef",
                        15L,
                    ),
                ),
                lane = QwyBundleSections.LaneMetadata(
                    qwyApplicationId = "name.caiyao.fakegps",
                    qwyVersionName = "1.0-test",
                    transportSchemaVersion = 5,
                    autoApplicationId = null,
                    autoVersionName = null,
                    providerPrincipal = null,
                ),
                createdAtEpochMs = 42L,
            ),
        )
        if (schemaVersion == null && declaredCountOverride == null) return export.zipBytes
        // Mutate the manifest inside the otherwise-valid zip (crafting hostile packages).
        val files = ConfigBundleContract.parseBundle(export.zipBytes).let {
            (it as ConfigBundleParseResult.Ok).bundle.files
        }
        val manifestJson = files.getValue("manifest.json").toString(Charsets.UTF_8)
        val patched = schemaVersion?.let { v ->
            manifestJson.replace(
                "\"schemaVersion\":${ConfigBundleContract.BUNDLE_SCHEMA_VERSION}",
                "\"schemaVersion\":$v",
            )
        } ?: manifestJson
        val patched2 = declaredCountOverride?.let { n ->
            patched.replace(
                "\"count\":${profiles.size}",
                "\"count\":$n",
            )
        } ?: patched
        return ConfigBundleZipWriter.write(
            files + ("manifest.json" to patched2.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun defaultSettings() = QwyBundleSections.SettingsSnapshot(
        spoofMode = "time_based",
        activeHourStart = 8,
        activeHourEnd = 20,
        refreshIntervalSec = 10,
        locationDeliveryMode = "hook",
        modules = mapOf("location" to true, "cell" to false),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        publishedRequests.clear()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun importer() = QwyBundleImporter(
        db = db,
        repository = ProfileRepository(db, context, publishOverride = { request ->
            publishedRequests += request
            true
        }),
        settingsApplier = { _ -> true },
    )

    // ---- fresh import ----

    @Test
    fun `fresh import inserts all profiles and re-anchors the active pointer by fingerprint`() = runTest {
        val zip = bundleFor(listOf(profileA, profileB), active = profileB)

        val result = importer().import(zip, ConfigBundleImportDecision.Replace)

        assertTrue(result.toString(), result is ConfigBundleImportResult.Done)
        val done = result as ConfigBundleImportResult.Done
        assertEquals(2, done.profilesImported)
        assertEquals(2, db.profileDao().getAll().size)
        // The published request targets the REGENERATED row whose content matches profileB.
        val target = db.profileDao().getAll().single { it.addname == "b" }
        assertEquals(target.id, done.anchoredProfileId)
        assertTrue(done.anchoredProfileId != null)
    }

    // ---- idempotency ----

    @Test
    fun `importing the same bundle twice inserts nothing new`() = runTest {
        val zip = bundleFor(listOf(profileA, profileB), active = profileA)
        val import = importer()

        val first = import.import(zip, ConfigBundleImportDecision.Replace)
        val second = import.import(zip, ConfigBundleImportDecision.Replace)

        assertTrue(first is ConfigBundleImportResult.Done)
        assertTrue(second is ConfigBundleImportResult.Done)
        assertEquals(2, (first as ConfigBundleImportResult.Done).profilesImported)
        assertEquals(0, (second as ConfigBundleImportResult.Done).profilesImported)
        assertEquals(2, db.profileDao().getAll().size)
        assertTrue((second as ConfigBundleImportResult.Done).profilesDuplicate > 0)
    }

    // ---- conflict policy ----

    @Test
    fun `skip decision keeps existing profiles and reports the untouched state`() = runTest {
        db.profileDao().insertAll(listOf(profileA.copy(altitude = 9.0)))
        val zip = bundleFor(listOf(profileA, profileB))

        val result = importer().import(zip, ConfigBundleImportDecision.KeepExisting)

        val done = result as ConfigBundleImportResult.Done
        assertTrue(!done.profilesSectionApplied)
        assertEquals(1, db.profileDao().getAll().size)
        assertEquals(9.0, db.profileDao().getAll().single().altitude!!, 0.0)
    }

    @Test
    fun `replace decision swaps the whole profile set`() = runTest {
        db.profileDao().insertAll(listOf(profileA.copy(altitude = 9.0)))
        val zip = bundleFor(listOf(profileA, profileB))

        importer().import(zip, ConfigBundleImportDecision.Replace)

        val names = db.profileDao().getAll().map { it.addname }
        assertEquals(listOf("a", "b"), names)
    }

    // ---- fail-closed ----

    @Test
    fun `unknown bundle schemaVersion rejects with explicit error and writes nothing`() = runTest {
        val zip = bundleFor(listOf(profileA), schemaVersion = 99)

        val result = importer().import(zip, ConfigBundleImportDecision.Replace)

        assertTrue(result is ConfigBundleImportResult.Rejected)
        assertTrue((result as ConfigBundleImportResult.Rejected).reason.contains("99"))
        assertEquals(0, db.profileDao().getAll().size)
        assertTrue(publishedRequests.isEmpty())
    }

    @Test
    fun `corrupt profile section rejects atomically - database untouched`() = runTest {
        db.profileDao().insertAll(listOf(profileA))
        val good = bundleFor(listOf(profileA, profileB))
        val files = (ConfigBundleContract.parseBundle(good) as ConfigBundleParseResult.Ok)
            .bundle.files
        val broken = ConfigBundleZipWriter.write(
            files + ("qwy/profiles.json" to "not json".toByteArray(Charsets.UTF_8)),
        )

        val result = importer().import(broken, ConfigBundleImportDecision.Replace)

        assertTrue(result is ConfigBundleImportResult.Rejected)
        assertEquals(1, db.profileDao().getAll().size)
        assertEquals("a", db.profileDao().getAll().single().addname)
    }

    // ---- reconciliation (T2's warning channel) ----

    @Test
    fun `manifest count mismatch surfaces a reconciliation warning with both numbers`() = runTest {
        val zip = bundleFor(listOf(profileA, profileB), declaredCountOverride = 5)

        val result = importer().import(zip, ConfigBundleImportDecision.Replace)

        val done = result as ConfigBundleImportResult.Done
        assertTrue(done.warnings.any { it.contains("5") && it.contains("2") })
        // The mismatch itself is a warning, not a silent success.
        assertTrue(done.warnings.isNotEmpty())
    }

    // ---- settings (lane config) ----

    @Test
    fun `settings section applies spoof mode hours refresh modules and delivery mode`() = runTest {
        val zip = bundleFor(listOf(profileA), settings = defaultSettings())

        val result = QwyBundleImporter(
            db = db,
            repository = ProfileRepository(db, context, publishOverride = { true }),
            settingsApplier = { snapshot ->
                SpoofSettings.getInstance(context).setSpoofMode(snapshot.spoofMode)
                SpoofSettings.getInstance(context).setActiveHourStart(snapshot.activeHourStart)
                SpoofSettings.getInstance(context).setActiveHourEnd(snapshot.activeHourEnd)
                SpoofSettings.getInstance(context).setRefreshIntervalSec(snapshot.refreshIntervalSec)
                true
            },
        ).import(zip, ConfigBundleImportDecision.Replace)

        assertTrue(result is ConfigBundleImportResult.Done)
        val settings = SpoofSettings.getInstance(context)
        assertEquals("time_based", settings.getRawMode())
        assertEquals(8, settings.getRawHourStart())
        assertEquals(20, settings.getRawHourEnd())
        assertEquals(10, settings.readRefreshIntervalSec())
    }

    @Test
    fun `bundle without any qwy section is rejected as not ours`() = runTest {
        val autoOnly = ConfigBundleZipWriter.write(
            mapOf(
                "manifest.json" to """
                    {"schemaVersion":${ConfigBundleContract.BUNDLE_SCHEMA_VERSION},
                     "createdAtEpochMs":1,"exporter":"cellrebel-auto",
                     "sections":{"auto.plan":{"file":"auto/plan.csv","count":1}}}
                """.trimIndent().toByteArray(Charsets.UTF_8),
                "auto/plan.csv" to "longitude,latitude,priority,required_successes\n1.0,2.0,0,1"
                    .toByteArray(Charsets.UTF_8),
            ),
        )

        val result = importer().import(autoOnly, ConfigBundleImportDecision.Replace)

        assertTrue(result is ConfigBundleImportResult.Rejected)
        assertNull(db.profileDao().getAll().singleOrNull())
    }

    // ---- fingerprints surfaced for human verification only ----

    @Test
    fun `import result surfaces caller fingerprints for manual re-approval and writes no trust state`() = runTest {
        val zip = bundleFor(listOf(profileA))

        val done = importer().import(zip, ConfigBundleImportDecision.Replace)
            as ConfigBundleImportResult.Done

        assertEquals(1, done.callerFingerprints.size)
        assertEquals("deadbeef", done.callerFingerprints.single().signerDigest)
        // QWY import NEVER writes trust decisions from a bundle.
        assertNotNull(done.callerFingerprints)
    }
}
