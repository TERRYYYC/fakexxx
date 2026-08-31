package name.caiyao.fakegps.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QwySemanticMutationCoordinatorTest {

    @Test
    fun `register current session binds exact digest and client death token`() {
        val fixture = fixture()

        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            fixture.coordinator.registerCurrentSession("digest-a"),
        )
        assertEquals(listOf("register:digest-a"), fixture.endpoint.calls)
        assertSame(fixture.deathToken, fixture.endpoint.registeredDeathToken)
    }

    @Test
    fun `missing throwing or rejecting registration endpoint fails closed`() {
        val missing = fixture(endpointAvailable = false)
        assertEquals(
            QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            ),
            missing.coordinator.registerCurrentSession("digest-a"),
        )

        val throwingProvider = fixture().apply { providerThrows = true }
        assertEquals(
            QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            ),
            throwingProvider.coordinator.registerCurrentSession("digest-a"),
        )

        val rejectingEndpoint = fixture().apply { endpoint.failRegister = true }
        assertEquals(
            QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.REGISTRATION_FAILED,
            ),
            rejectingEndpoint.coordinator.registerCurrentSession("digest-a"),
        )
    }

    @Test
    fun `dead or failing client death token cannot establish a session`() {
        val dead = fixture().apply { deathToken.alive = false }
        assertEquals(
            QwySemanticSessionRegistration.Failed(QwySemanticMutationFailure.CLIENT_DIED),
            dead.coordinator.registerCurrentSession("digest-a"),
        )
        assertTrue(dead.endpoint.calls.isEmpty())

        val failingCheck = fixture().apply { deathToken.failAliveCheck = true }
        assertEquals(
            QwySemanticSessionRegistration.Failed(QwySemanticMutationFailure.CLIENT_DIED),
            failingCheck.coordinator.registerCurrentSession("digest-a"),
        )
        assertTrue(failingCheck.endpoint.calls.isEmpty())

        val failingFactory = fixture().apply { deathTokenFactoryThrows = true }
        assertEquals(
            QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.CLIENT_DEATH_TOKEN_FAILED,
            ),
            failingFactory.coordinator.registerCurrentSession("digest-a"),
        )
    }

    @Test
    fun `normal changed mutation brackets work and advances registered digest`() {
        val fixture = registeredFixture()

        val result = fixture.coordinator.runMutation(
            mutationId = "mutation-1",
            beforeDigest = "digest-a",
        ) {
            fixture.endpoint.calls += "work"
            QwySemanticMutationWork.Changed(value = "value", afterDigest = "digest-b")
        }

        assertEquals(
            QwySemanticMutationResult.Changed(value = "value", afterDigest = "digest-b"),
            result,
        )
        assertEquals(
            listOf(
                "register:digest-a",
                "begin:mutation-1:digest-a",
                "work",
                "finish:41:true:false:digest-b",
            ),
            fixture.endpoint.calls,
        )

        val next = fixture.coordinator.runMutation("mutation-2", "digest-b") {
            QwySemanticMutationWork.ProvedNoOp("next", "digest-b")
        }
        assertEquals(
            QwySemanticMutationResult.ProvedNoOp("next", "digest-b"),
            next,
        )
    }

    @Test
    fun `proved no-op reports neither changed nor uncertain`() {
        val fixture = registeredFixture()

        val result = fixture.coordinator.runMutation("mutation-noop", "digest-a") {
            QwySemanticMutationWork.ProvedNoOp(value = 7, afterDigest = "digest-a")
        }

        assertEquals(QwySemanticMutationResult.ProvedNoOp(7, "digest-a"), result)
        assertEquals(
            "finish:41:false:false:digest-a",
            fixture.endpoint.calls.last(),
        )
    }

    @Test
    fun `explicit uncertain outcome is sent remotely and invalidates local session`() {
        val fixture = registeredFixture()

        val result = fixture.coordinator.runMutation<String>("mutation-uncertain", "digest-a") {
            QwySemanticMutationWork.Uncertain(afterDigest = null)
        }

        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.EXPLICIT_UNCERTAIN,
            ),
            result,
        )
        assertEquals("finish:41:false:true:null", fixture.endpoint.calls.last())
        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.SESSION_UNAVAILABLE,
            ),
            fixture.coordinator.runMutation("mutation-after", "digest-a") {
                QwySemanticMutationWork.Changed(Unit, "digest-b")
            },
        )
    }

    @Test
    fun `begin failure never executes local mutation`() {
        val fixture = registeredFixture().apply { endpoint.failBegin = true }
        var executed = false

        val result = fixture.coordinator.runMutation("mutation-begin", "digest-a") {
            executed = true
            QwySemanticMutationWork.Changed(Unit, "digest-b")
        }

        assertEquals(
            QwySemanticMutationResult.Uncertain(QwySemanticMutationFailure.BEGIN_FAILED),
            result,
        )
        assertFalse(executed)
    }

    @Test
    fun `local exception attempts uncertain finish and returns no trusted value`() {
        val fixture = registeredFixture()

        val result = fixture.coordinator.runMutation<String>("mutation-throw", "digest-a") {
            error("local durable write failed")
        }

        assertEquals(
            QwySemanticMutationResult.Uncertain(QwySemanticMutationFailure.OPERATION_FAILED),
            result,
        )
        assertEquals("finish:41:false:true:null", fixture.endpoint.calls.last())
    }

    @Test
    fun `finish exception is uncertain and invalidates the session`() {
        val fixture = registeredFixture().apply { endpoint.failFinish = true }

        val result = fixture.coordinator.runMutation("mutation-finish", "digest-a") {
            QwySemanticMutationWork.Changed("value", "digest-b")
        }

        assertEquals(
            QwySemanticMutationResult.Uncertain(QwySemanticMutationFailure.FINISH_FAILED),
            result,
        )
        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.SESSION_UNAVAILABLE,
            ),
            fixture.coordinator.runMutation("later", "digest-a") {
                QwySemanticMutationWork.Changed(Unit, "digest-b")
            },
        )
    }

    @Test
    fun `client death or endpoint loss after begin attempts uncertain finish`() {
        val died = registeredFixture()
        val diedResult = died.coordinator.runMutation("mutation-death", "digest-a") {
            died.deathToken.alive = false
            QwySemanticMutationWork.Changed("value", "digest-b")
        }
        assertEquals(
            QwySemanticMutationResult.Uncertain(QwySemanticMutationFailure.CLIENT_DIED),
            diedResult,
        )
        assertEquals("finish:41:false:true:null", died.endpoint.calls.last())

        val disappeared = registeredFixture()
        val disappearedResult = disappeared.coordinator.runMutation(
            "mutation-endpoint",
            "digest-a",
        ) {
            disappeared.endpointAvailable = false
            QwySemanticMutationWork.Changed("value", "digest-b")
        }
        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            ),
            disappearedResult,
        )
        assertEquals("finish:41:false:true:null", disappeared.endpoint.calls.last())
    }

    @Test
    fun `digest mismatch and unproved no-op are uncertain`() {
        val mismatch = registeredFixture()
        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.DIGEST_MISMATCH,
            ),
            mismatch.coordinator.runMutation("mutation-mismatch", "other-digest") {
                QwySemanticMutationWork.Changed(Unit, "digest-b")
            },
        )
        assertEquals(listOf("register:digest-a"), mismatch.endpoint.calls)

        val lyingNoOp = registeredFixture()
        val result = lyingNoOp.coordinator.runMutation("mutation-lie", "digest-a") {
            QwySemanticMutationWork.ProvedNoOp("value", "digest-b")
        }
        assertEquals(
            QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.OUTCOME_UNPROVEN,
            ),
            result,
        )
        assertEquals("finish:41:false:true:digest-b", lyingNoOp.endpoint.calls.last())
    }

    private fun registeredFixture(): Fixture = fixture().also {
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            it.coordinator.registerCurrentSession("digest-a"),
        )
    }

    private fun fixture(endpointAvailable: Boolean = true): Fixture = Fixture(endpointAvailable)

    private class Fixture(initialEndpointAvailable: Boolean) {
        val endpoint = FakeEndpoint()
        val deathToken = FakeDeathToken()
        var endpointAvailable = initialEndpointAvailable
        var providerThrows = false
        var deathTokenFactoryThrows = false

        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider {
                if (providerThrows) error("endpoint registry failed")
                endpoint.takeIf { endpointAvailable }
            },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                if (deathTokenFactoryThrows) error("death token allocation failed")
                deathToken
            },
        )
    }

    private class FakeEndpoint : QwySemanticMutationEndpoint {
        val calls = mutableListOf<String>()
        var registeredDeathToken: QwySemanticClientDeathToken? = null
        var failRegister = false
        var failBegin = false
        var failFinish = false

        override fun registerCurrentSession(
            semanticDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ) {
            if (failRegister) error("register failed")
            registeredDeathToken = clientDeathToken
            calls += "register:$semanticDigest"
        }

        override fun beginMutation(
            mutationId: String,
            beforeDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ): Long {
            if (failBegin) error("begin failed")
            assertSame(registeredDeathToken, clientDeathToken)
            calls += "begin:$mutationId:$beforeDigest"
            return 41L
        }

        override fun finishMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) {
            if (failFinish) error("finish failed")
            calls += "finish:$token:$changed:$uncertain:$afterDigest"
        }
    }

    private class FakeDeathToken : QwySemanticClientDeathToken {
        var alive = true
        var failAliveCheck = false

        override fun isAlive(): Boolean {
            if (failAliveCheck) error("death check failed")
            return alive
        }
    }
}
