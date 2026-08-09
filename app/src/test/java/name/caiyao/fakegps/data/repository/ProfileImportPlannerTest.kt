package name.caiyao.fakegps.data.repository

import name.caiyao.fakegps.data.db.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportPlannerTest {
    @Test
    fun `planner recomputes exact duplicates against database and within pending batch`() {
        val existing = listOf(ProfileEntity(id = 7L, addname = "same", tac = 1))
        val candidates = listOf(
            ProfileEntity(id = 99L, addname = "same", tac = 1),
            ProfileEntity(id = 100L, addname = "new", tac = 2),
            ProfileEntity(id = 101L, addname = "new", tac = 2),
        )

        val plan = ProfileImportPlanner.plan(existing, candidates)

        assertEquals(2, plan.duplicates)
        assertEquals(listOf(ProfileEntity(addname = "new", tac = 2)), plan.toInsert)
        assertTrue(plan.toInsert.all { it.id == 0L })
    }

    @Test
    fun `same fields with a different archive name remain distinct`() {
        val plan = ProfileImportPlanner.plan(
            existing = listOf(ProfileEntity(id = 1L, addname = "home", tac = 1)),
            candidates = listOf(ProfileEntity(id = 2L, addname = "work", tac = 1)),
        )

        assertEquals(0, plan.duplicates)
        assertEquals("work", plan.toInsert.single().addname)
    }
}
