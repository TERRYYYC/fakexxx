package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QwyScheduleStoreDurabilityTest {

    @Test
    fun `pointer commit false fails loud and preserves the pre-mutation tuple`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        val before = scheduleTuple(store)
        backend.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.advancePointer("profile-1")
        }

        assertEquals(before, scheduleTuple(store))
    }

    @Test
    fun `pointer success acknowledgement is rejected when tuple readback did not move`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        val before = scheduleTuple(store)
        backend.dropNextCommitButReportSuccess = true

        assertThrows(IllegalStateException::class.java) {
            store.advancePointer("profile-1")
        }

        assertEquals(before, scheduleTuple(store))
    }

    @Test
    fun `projection anchor commit false fails loud and remains absent`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        backend.failNextCommit = true

        assertThrows(IllegalStateException::class.java) {
            store.recordLastApplied(
                latitude = 50.4501,
                longitude = 30.5234,
                publishNotBeforeElapsedRealtimeMs = 1_000L,
                transportPublished = true,
                scheduleItemId = "profile-1",
                scheduleVersion = 1L,
                purpose = ProjectionPurpose.POST_ADVANCE,
            )
        }

        assertNull(store.getLastApplied())
    }

    @Test
    fun `projection anchor exact readback rejects omitted equator coordinates`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        backend.dropKeysFromNextCommitButReportSuccess = setOf(
            "lastAppliedLat",
            "lastAppliedLng",
        )

        assertThrows(IllegalStateException::class.java) {
            store.recordLastApplied(
                latitude = 0.0,
                longitude = 0.0,
                publishNotBeforeElapsedRealtimeMs = 1_000L,
                transportPublished = true,
                scheduleItemId = "profile-1",
                scheduleVersion = 1L,
                purpose = ProjectionPurpose.POST_ADVANCE,
            )
        }

        assertNull(
            "a partial successful acknowledgement must not manufacture a restartable anchor",
            store.getLastApplied(),
        )
    }

    @Test
    fun `post advance projection contract survives rebuild and lease handoff replaces it`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        store.advancePointer("profile-1")
        store.recordLastApplied(
            latitude = 50.4501,
            longitude = 30.5234,
            publishNotBeforeElapsedRealtimeMs = 1_000L,
            transportPublished = true,
            scheduleItemId = "profile-2",
            scheduleVersion = 2L,
            purpose = ProjectionPurpose.POST_ADVANCE,
        )

        val rebuilt = QwyScheduleStore(backend, TestItemCodec)
        val schedule = checkNotNull(rebuilt.readScheduleState())
        assertEquals("profile-2", schedule.currentItemId)
        assertEquals(2L, schedule.scheduleVersion)
        assertEquals(
            ProjectionPurpose.POST_ADVANCE,
            rebuilt.postAdvanceProjectionFor(schedule)?.purpose,
        )

        rebuilt.recordLastApplied(
            latitude = 50.4501,
            longitude = 30.5234,
            publishNotBeforeElapsedRealtimeMs = 1_500L,
            transportPublished = true,
            scheduleItemId = "profile-2",
            scheduleVersion = 2L,
            purpose = ProjectionPurpose.POST_ADVANCE,
        )
        assertEquals(
            "a post-advance retry after process rebuild must keep the exact reconstruction tuple",
            1_500L,
            rebuilt.postAdvanceProjectionFor(schedule)?.publishNotBeforeElapsedRealtimeMs,
        )

        rebuilt.recordLastApplied(
            latitude = 50.4501,
            longitude = 30.5234,
            publishNotBeforeElapsedRealtimeMs = 2_000L,
            transportPublished = true,
            scheduleItemId = "profile-2",
            scheduleVersion = 2L,
            purpose = ProjectionPurpose.LEASE,
        )

        assertNull(
            "a successful next-lease handoff must retire the restartable post-advance marker",
            rebuilt.postAdvanceProjectionFor(schedule),
        )
    }

    @Test
    fun `post advance reconstruction refuses an anchor from a stale schedule generation`() {
        val backend = RecordingQwySchedulePreferences()
        val store = initializedStore(backend)
        store.advancePointer("profile-1")
        store.recordLastApplied(
            latitude = 50.4501,
            longitude = 30.5234,
            publishNotBeforeElapsedRealtimeMs = 1_000L,
            transportPublished = true,
            scheduleItemId = "profile-2",
            scheduleVersion = 2L,
            purpose = ProjectionPurpose.POST_ADVANCE,
        )

        store.advancePointer("profile-2")
        val newerSchedule = checkNotNull(store.readScheduleState())

        assertNull(
            "a stale POST_ADVANCE anchor must not rehydrate a changed schedule generation",
            store.postAdvanceProjectionFor(newerSchedule),
        )
    }

    private fun initializedStore(backend: RecordingQwySchedulePreferences): QwyScheduleStore =
        QwyScheduleStore(backend, TestItemCodec).also {
            it.initFromProfileIds(listOf(1L, 2L))
        }

    private fun scheduleTuple(store: QwyScheduleStore): List<Any?> = listOf(
        store.getCurrentItemId(),
        store.getScheduleVersion(),
        store.isExhausted(),
        store.getAdvanceCount(),
    )

    private class RecordingQwySchedulePreferences : QwySchedulePreferences {
        private val state = linkedMapOf<String, Any?>()
        var failNextCommit = false
        var dropNextCommitButReportSuccess = false
        var dropKeysFromNextCommitButReportSuccess: Set<String> = emptySet()

        override fun snapshot(): Map<String, Any?> = state.toMap()

        override fun commit(changes: Map<String, Any?>): Boolean {
            if (failNextCommit) {
                failNextCommit = false
                return false
            }
            if (dropNextCommitButReportSuccess) {
                dropNextCommitButReportSuccess = false
                return true
            }
            changes.forEach { (key, value) ->
                if (key !in dropKeysFromNextCommitButReportSuccess) {
                    if (value == null) state.remove(key) else state[key] = value
                }
            }
            dropKeysFromNextCommitButReportSuccess = emptySet()
            return true
        }
    }

    private object TestItemCodec : QwyScheduleItemCodec {
        override fun encode(ids: List<String>): String = ids.joinToString("|")
        override fun decode(raw: String): List<String> =
            raw.takeIf { it.isNotEmpty() }?.split("|").orEmpty()
    }
}
