package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class QwySemanticDigestV1ContractTest {

    @Test
    fun `repeated refresh with identical semantic inputs keeps one digest`() {
        val inputs = baselineInputs()

        val first = digest(inputs)
        val second = digest(inputs.copy())

        assertEquals(
            "refresh cadence and sample time are not inputs, so an unchanged refresh is stable",
            first,
            second,
        )
        assertEquals(64, first.length)
        assertEquals(first.lowercase(), first)
    }

    @Test
    fun `every canonical semantic field changes the digest independently`() {
        val baseline = baselineInputs()
        val baselineDigest = digest(baseline)
        val mutations = linkedMapOf(
            "owner generation" to baseline.copy(ownerGeneration = baseline.ownerGeneration + 1L),
            "active mode" to baseline.copy(activeMode = "route"),
            "active profile" to baseline.copy(activeProfileRef = "profile-2"),
            "published profile content" to baseline.copy(
                publishedConfigDigest = "published-profile-b",
            ),
            "schedule id" to baseline.copy(
                schedule = baseline.schedule.copy(scheduleId = "schedule-2"),
            ),
            "schedule version" to baseline.copy(
                schedule = baseline.schedule.copy(
                    scheduleVersion = baseline.schedule.scheduleVersion + 1L,
                ),
            ),
            "schedule item" to baseline.copy(
                schedule = baseline.schedule.copy(currentItemId = "item-2"),
            ),
            "schedule exhausted" to baseline.copy(
                schedule = baseline.schedule.copy(exhausted = true),
            ),
            "latitude bits" to baseline.copy(
                effectiveLatitude = Double.fromBits(
                    baseline.effectiveLatitude.toBits() + 1L,
                ),
            ),
            "longitude bits" to baseline.copy(
                effectiveLongitude = Double.fromBits(
                    baseline.effectiveLongitude.toBits() + 1L,
                ),
            ),
            "projection active" to baseline.copy(projectionActive = false),
            "effective provider projection" to baseline.copy(
                effectiveProjectionFingerprint = "system-mock:gps=B|network=B",
            ),
        )

        mutations.forEach { (field, changed) ->
            assertNotEquals(
                "$field must be part of the canonical semantic digest",
                baselineDigest,
                digest(changed),
            )
        }
    }

    @Test
    fun `identical published payloads under different active profile identities do not alias`() {
        val baseline = baselineInputs()
        val otherIdentity = baseline.copy(activeProfileRef = "profile-2")

        assertEquals(baseline.publishedConfigDigest, otherIdentity.publishedConfigDigest)
        assertEquals(baseline.schedule, otherIdentity.schedule)
        assertNotEquals(
            "the durable active-profile identity is semantic even when its payload is identical",
            digest(baseline),
            digest(otherIdentity),
        )
    }

    @Test
    fun `coordinate framing preserves the sign bit of zero`() {
        val positiveZero = baselineInputs().copy(
            effectiveLatitude = 0.0,
            effectiveLongitude = 0.0,
        )

        assertNotEquals(
            "negative zero has a distinct IEEE-754 representation and must not alias positive zero",
            digest(positiveZero),
            digest(positiveZero.copy(effectiveLatitude = -0.0)),
        )
        assertNotEquals(
            "longitude uses the same bit-preserving canonical framing",
            digest(positiveZero),
            digest(positiveZero.copy(effectiveLongitude = -0.0)),
        )
    }

    @Test
    fun `published payload digest excludes refresh cadence but covers effective fields`() {
        val baseline = """{"schemaVersion":4,"refreshIntervalSec":3,"mode":"always_on","fields":{"id":1,"model":"A"}}"""
        val cadenceOnly = baseline.replace("\"refreshIntervalSec\":3", "\"refreshIntervalSec\":30")
        val profileChanged = baseline.replace("\"model\":\"A\"", "\"model\":\"B\"")
        val modeChanged = baseline.replace("\"always_on\"", "\"off\"")
        val reordered = """{"fields":{"model":"A","id":1},"mode":"always_on","refreshIntervalSec":30,"schemaVersion":4}"""

        assertEquals(
            QwyPublishedConfigSemanticV1.digest(baseline),
            QwyPublishedConfigSemanticV1.digest(cadenceOnly),
        )
        assertEquals(
            "JSON member order and cadence are not semantic inputs",
            QwyPublishedConfigSemanticV1.digest(baseline),
            QwyPublishedConfigSemanticV1.digest(reordered),
        )
        assertNotEquals(
            QwyPublishedConfigSemanticV1.digest(baseline),
            QwyPublishedConfigSemanticV1.digest(profileChanged),
        )
        assertNotEquals(
            QwyPublishedConfigSemanticV1.digest(baseline),
            QwyPublishedConfigSemanticV1.digest(modeChanged),
        )
    }

    @Test
    fun `published payload canonicalization preserves cadence-like text inside values`() {
        val baseline = """{"schemaVersion":4,"refreshIntervalSec":3,"fields":{"note":"literal refreshIntervalSec\\\":3, text"}}"""
        val changedValue = baseline.replace("literal", "changed")

        assertNotEquals(
            "only the root cadence field may be excluded",
            QwyPublishedConfigSemanticV1.digest(baseline),
            QwyPublishedConfigSemanticV1.digest(changedValue),
        )
    }

    private fun digest(inputs: SemanticInputs): String = QwySemanticDigestV1.compute(
        ownerGeneration = inputs.ownerGeneration,
        activeMode = inputs.activeMode,
        activeProfileRef = inputs.activeProfileRef,
        schedule = inputs.schedule,
        effectiveLatitude = inputs.effectiveLatitude,
        effectiveLongitude = inputs.effectiveLongitude,
        projectionActive = inputs.projectionActive,
        effectiveProjectionFingerprint = inputs.effectiveProjectionFingerprint,
        publishedConfigDigest = inputs.publishedConfigDigest,
    )

    private fun baselineInputs() = SemanticInputs(
        ownerGeneration = 17L,
        activeMode = "always_on",
        activeProfileRef = "profile-1",
        schedule = ScheduleSnapshot(
            scheduleId = "schedule-1",
            scheduleVersion = 23L,
            currentItemId = "item-1",
            itemIds = listOf("item-1", "item-2"),
            exhausted = false,
        ),
        effectiveLatitude = 50.450001,
        effectiveLongitude = 30.523333,
        projectionActive = true,
        effectiveProjectionFingerprint = "system-mock:gps=A|network=A",
        publishedConfigDigest = "published-profile-a",
    )

    private data class SemanticInputs(
        val ownerGeneration: Long,
        val activeMode: String?,
        val activeProfileRef: String?,
        val schedule: ScheduleSnapshot,
        val effectiveLatitude: Double,
        val effectiveLongitude: Double,
        val projectionActive: Boolean,
        val effectiveProjectionFingerprint: String?,
        val publishedConfigDigest: String?,
    )
}
