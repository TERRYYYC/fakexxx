package name.caiyao.fakegps.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.motion.RoutePayload
import name.caiyao.fakegps.motion.RouteWaypoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P3.1 来源 a 的落库 seam：attachRoute 把独立路线 CSV 的解析结果挂到档案上（null = detach），
 * 并触发 republish。跑真实的 Room（Robolectric 内存库）。
 */
@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryRouteTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ProfileRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppDatabase.closeInstanceForTests()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ProfileRepository(db, context = null, publishOverride = { true })
    }

    @After
    fun tearDown() {
        db.close()
        AppDatabase.closeInstanceForTests()
    }

    @Test
    fun `attachRoute stores the parsed waypoints and detach clears them`() = runBlocking {
        val saved = repo.save(ProfileEntity(latitude = 50.4501, longitude = 30.5234))

        val waypoints = listOf(
            RouteWaypoint(50.4501, 30.5234),
            RouteWaypoint(50.4600, 30.5400, speedMps = 8.0),
        )
        assertTrue(repo.attachRoute(saved.id, RoutePayload.encodeWaypoints(waypoints)))
        assertEquals(
            waypoints,
            RoutePayload.parseWaypoints(repo.getById(saved.id)!!.routeWaypointsJson),
        )

        assertTrue(repo.attachRoute(saved.id, null))
        assertNull(repo.getById(saved.id)!!.routeWaypointsJson)
    }

    @Test
    fun `attachRoute on a missing profile reports failure without publishing`() = runBlocking {
        assertFalse(repo.attachRoute(1234L, "[]"))
    }
}
