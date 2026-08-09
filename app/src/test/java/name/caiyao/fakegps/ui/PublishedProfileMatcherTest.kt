package name.caiyao.fakegps.ui

import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.ui.screen.collection.PublishedProfileMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublishedProfileMatcherTest {
    @Test
    fun `empty database import stays unpublished and has no effective badge`() {
        val imported = listOf(ProfileEntity(id = 1L, addname = "archive", tac = 7))

        assertNull(PublishedProfileMatcher.effectiveProfileId(imported, PayloadRead.Absent))
        assertNull(
            PublishedProfileMatcher.effectiveProfileId(
                imported,
                PayloadRead.Raw(
                    """{"schemaVersion":3,"mode":"always_on","fields":{},"unavailable":[]}""",
                ),
            ),
        )
    }

    @Test
    fun `imported rows cannot displace the profile represented by published bytes`() {
        val profiles = listOf(
            ProfileEntity(id = 4L, addname = "published", speed = 0.1f, tac = 7),
            ProfileEntity(id = 5L, addname = "imported", speed = 1.0f, tac = 8),
        )
        val read = PayloadRead.Raw(
            """{"schemaVersion":3,"mode":"always_on","fields":{"addname":"published","speed":0.10000000149011612,"tac":7},"unavailable":[]}""",
        )

        assertEquals(4L, PublishedProfileMatcher.effectiveProfileId(profiles, read))
    }

    @Test
    fun `unreadable invalid or unknown payload never produces a guessed effective badge`() {
        val profiles = listOf(ProfileEntity(id = 1L, addname = "archive", tac = 7))

        assertNull(PublishedProfileMatcher.effectiveProfileId(profiles, PayloadRead.ReadError("EACCES")))
        assertNull(PublishedProfileMatcher.effectiveProfileId(profiles, PayloadRead.Raw("{oops")))
        assertNull(
            PublishedProfileMatcher.effectiveProfileId(
                profiles,
                PayloadRead.Raw(
                    """{"schemaVersion":3,"mode":"always_on","fields":{"addname":"archive","tac":7}}""",
                ),
            ),
        )
        assertNull(
            PublishedProfileMatcher.effectiveProfileId(
                profiles,
                PayloadRead.Raw(
                    """{"schemaVersion":99,"mode":"always_on","fields":{"addname":"archive","tac":7},"unavailable":[]}""",
                ),
            ),
        )
    }
}
