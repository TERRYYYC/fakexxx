package name.caiyao.fakegps.integration.v1

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QwySemanticWriterRuntimeTest {
    @Test
    fun `uninstalled runtime preserves sync and suspend writes without claiming authority`() =
        runBlocking {
            var syncSelected = true
            var suspendSelected = true

            val syncValue = QwySemanticWriterRuntime.mutate("mode") { selected ->
                syncSelected = selected
                "sync-value"
            }
            val suspendValue = QwySemanticWriterRuntime.mutateSuspend("profile-save") { selected ->
                suspendSelected = selected
                "suspend-value"
            }

            assertEquals("sync-value", syncValue)
            assertEquals("suspend-value", suspendValue)
            assertFalse(syncSelected)
            assertFalse(suspendSelected)
        }

    @Test
    fun `installed healthy lane brackets sync and suspend writes and advances digest`() =
        runBlocking {
            val fixture = Fixture()
            fixture.registerAndInstall()
            try {
                val sync = QwySemanticWriterRuntime.mutate("mode") { selected ->
                    assertTrue(selected)
                    fixture.digest = "digest-b"
                    "sync"
                }
                val suspended = QwySemanticWriterRuntime.mutateSuspend("profile-save") { selected ->
                    assertTrue(selected)
                    fixture.digest = "digest-c"
                    "suspend"
                }

                assertEquals("sync", sync)
                assertEquals("suspend", suspended)
                assertEquals(
                    listOf(
                        "register:digest-a",
                        "begin:writer-mode-1:digest-a",
                        "finish:1:true:false:digest-b",
                        "begin:writer-profile-save-2:digest-b",
                        "finish:2:true:false:digest-c",
                    ),
                    fixture.endpoint.calls,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `same semantic digest is explicitly finished as a proved no-op`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            val value = QwySemanticWriterRuntime.mutate("active-hours") { "unchanged" }

            assertEquals("unchanged", value)
            assertEquals("finish:1:false:false:digest-a", fixture.endpoint.calls.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `external projection repair executes legacy refresh when no lane is installed`() {
        assertFalse(QwySemanticWriterRuntime.hasInstalledLane())
        var published = false

        val repaired = QwySemanticWriterRuntime.repairExternalProjection(
            "legacy-coordinate-repair",
        ) {
            published = true
        }

        assertTrue(repaired)
        assertTrue(published)
        assertFalse(QwySemanticWriterRuntime.hasInstalledLane())
    }

    @Test
    fun `external projection repair defers before work when installed lane is unhealthy`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            fixture.acceptedHealthDigest = "digest-a"
            fixture.digest = "digest-b"
            fixture.healthy = false
            var published = false

            val repaired = QwySemanticWriterRuntime.repairExternalProjection(
                "unhealthy-coordinate-repair",
            ) {
                published = true
            }

            assertFalse(repaired)
            assertFalse(published)
            assertEquals(listOf("register:digest-a"), fixture.endpoint.calls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `external projection drift is fenced as changed while restoring registered digest`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            fixture.acceptedHealthDigest = "digest-a"
            fixture.digest = "digest-b"

            val repaired = QwySemanticWriterRuntime.repairExternalProjection(
                "coordinate-repair",
            ) {
                fixture.digest = "digest-a"
            }

            assertTrue(repaired)
            assertEquals(
                listOf(
                    "register:digest-a",
                    "begin:writer-coordinate-repair-1:digest-a",
                    "finish:1:true:false:digest-a",
                ),
                fixture.endpoint.calls,
            )
            assertTrue(QwySemanticWriterRuntime.isInstalledAndHealthyFor("digest-a"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `missing projection readback is fenced and republished from registered digest`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            fixture.acceptedHealthDigest = "digest-a"
            fixture.digest = null

            val repaired = QwySemanticWriterRuntime.repairExternalProjection(
                "missing-coordinate-repair",
            ) {
                fixture.digest = "digest-a"
            }

            assertTrue(repaired)
            assertEquals(
                "finish:1:true:false:digest-a",
                fixture.endpoint.calls.last(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `central publication joins an existing coordinator bracket without a second mutation id`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            val outer = fixture.coordinator.runMutation("handler-apply", "digest-a") {
                val published = QwySemanticWriterRuntime.mutate("config-publish") { selected ->
                    assertTrue(selected)
                    fixture.digest = "digest-b"
                    "published"
                }
                QwySemanticMutationWork.Changed(published, "digest-b")
            }

            assertEquals(
                QwySemanticMutationResult.Changed("published", "digest-b"),
                outer,
            )
            assertEquals(
                listOf(
                    "register:digest-a",
                    "begin:handler-apply:digest-a",
                    "finish:1:true:false:digest-b",
                ),
                fixture.endpoint.calls,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `external writer exposes its in-flight bracket to callback suppression`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            assertFalse(QwySemanticWriterRuntime.isAuthoritativeMutationInFlight())
            QwySemanticWriterRuntime.mutate("mode") {
                assertTrue(QwySemanticWriterRuntime.isAuthoritativeMutationInFlight())
                fixture.digest = "digest-b"
            }
            assertFalse(QwySemanticWriterRuntime.isAuthoritativeMutationInFlight())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `selected lane with missing digest or lost health fails before local write`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            fixture.digest = null
            var ran = false

            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                QwySemanticWriterRuntime.mutate("mode") {
                    ran = true
                }
            }

            assertFalse(ran)
            assertEquals(listOf("register:digest-a"), fixture.endpoint.calls)
        } finally {
            fixture.close()
        }

        val unhealthy = Fixture()
        unhealthy.registerAndInstall()
        try {
            unhealthy.healthy = false
            var ran = false

            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                QwySemanticWriterRuntime.mutate("mode") {
                    ran = true
                }
            }

            assertFalse(ran)
        } finally {
            unhealthy.close()
        }
    }

    @Test
    fun `ambiguity after local commit poisons the selected session and never returns success`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        try {
            var committed = false

            val thrown = assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                QwySemanticWriterRuntime.mutate("delivery-mode") {
                    committed = true
                    fixture.digest = "digest-b"
                    fixture.endpointAlive = false
                    "must-not-escape"
                }
            }

            assertTrue(committed)
            assertTrue(thrown.message.orEmpty().contains("CLIENT_DIED"))
            assertEquals("finish:1:false:true:", fixture.endpoint.calls.last())
            assertFalse(fixture.coordinator.isReadyFor("digest-b"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `second process lane cannot replace a live installation`() {
        val first = Fixture()
        first.registerAndInstall()
        val second = Fixture()
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            second.coordinator.registerCurrentSession("digest-a"),
        )
        try {
            assertThrows(IllegalStateException::class.java) {
                second.install()
            }

            QwySemanticWriterRuntime.mutate("mode") {
                first.digest = "digest-b"
            }
            assertTrue(first.endpoint.calls.any { it.startsWith("begin:writer-mode") })
            assertFalse(second.endpoint.calls.any { it.startsWith("begin:") })
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `concurrent process writers serialize digest read through remote finish`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        val executor = Executors.newFixedThreadPool(2)
        val firstInsideWork = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondInvoked = CountDownLatch(1)
        try {
            val first = executor.submit<String> {
                QwySemanticWriterRuntime.mutate("mode") {
                    firstInsideWork.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    fixture.digest = "digest-b"
                    "first"
                }
            }
            assertTrue(firstInsideWork.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String> {
                secondInvoked.countDown()
                QwySemanticWriterRuntime.mutate("profile-save") {
                    fixture.digest = "digest-c"
                    "second"
                }
            }
            assertTrue(secondInvoked.await(5, TimeUnit.SECONDS))
            // Give the second writer a chance to reach the pre-digest read
            // while the first coordinator bracket is deliberately held open.
            Thread.sleep(100)
            releaseFirst.countDown()

            assertEquals("first", first.get(5, TimeUnit.SECONDS))
            assertEquals("second", second.get(5, TimeUnit.SECONDS))
            assertEquals(2, fixture.endpoint.calls.count { it.startsWith("begin:") })
            assertEquals(2, fixture.endpoint.calls.count { it.contains(":true:false:") })
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `handler coordinator reentry cannot deadlock with an external writer`() {
        val fixture = Fixture()
        fixture.registerAndInstall()
        val executor = Executors.newFixedThreadPool(2)
        val handlerInsideCoordinator = CountDownLatch(1)
        val externalWriterOwnsSelection = CountDownLatch(1)
        try {
            fixture.onDigestRead = { externalWriterOwnsSelection.countDown() }
            val handler = executor.submit<QwySemanticMutationResult<String>> {
                fixture.coordinator.runMutation("handler-apply", "digest-a") {
                    handlerInsideCoordinator.countDown()
                    check(externalWriterOwnsSelection.await(5, TimeUnit.SECONDS))
                    val joined = QwySemanticWriterRuntime.mutate("nested-publication") {
                        selected ->
                        assertTrue(selected)
                        fixture.digest = "digest-b"
                        "joined"
                    }
                    QwySemanticMutationWork.Changed(joined, "digest-b")
                }
            }
            assertTrue(handlerInsideCoordinator.await(5, TimeUnit.SECONDS))
            val external = executor.submit<Throwable?> {
                runCatching {
                    QwySemanticWriterRuntime.mutate("external-writer") { "external" }
                }.exceptionOrNull()
            }

            assertEquals(
                QwySemanticMutationResult.Changed("joined", "digest-b"),
                handler.get(5, TimeUnit.SECONDS),
            )
            assertTrue(
                "the external writer selected the old digest and must fail closed after the join",
                external.get(5, TimeUnit.SECONDS) is QwySemanticWriterAmbiguityException,
            )
        } finally {
            externalWriterOwnsSelection.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    @Test
    fun `installation cannot overtake an unbracketed writer selected before publication`() {
        val fixture = Fixture()
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            fixture.coordinator.registerCurrentSession("digest-a"),
        )
        val executor = Executors.newFixedThreadPool(2)
        val writerInsideFallback = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val installFinished = CountDownLatch(1)
        try {
            val writer = executor.submit<String> {
                QwySemanticWriterRuntime.mutate("pre-install-writer") { selected ->
                    assertFalse(selected)
                    writerInsideFallback.countDown()
                    check(releaseWriter.await(5, TimeUnit.SECONDS))
                    fixture.digest = "digest-b"
                    "written"
                }
            }
            assertTrue(writerInsideFallback.await(5, TimeUnit.SECONDS))
            val installation = executor.submit<Throwable?> {
                try {
                    runCatching { fixture.install() }.exceptionOrNull()
                } finally {
                    installFinished.countDown()
                }
            }

            assertFalse(
                "installation must wait until the already-selected fallback writer finishes",
                installFinished.await(150, TimeUnit.MILLISECONDS),
            )
            releaseWriter.countDown()
            assertEquals("written", writer.get(5, TimeUnit.SECONDS))
            val failure = installation.get(5, TimeUnit.SECONDS)
            assertTrue(failure is IllegalStateException)

            var selectedAfterFailedInstall = true
            QwySemanticWriterRuntime.mutate("after-failed-install") { selected ->
                selectedAfterFailedInstall = selected
            }
            assertFalse(selectedAfterFailedInstall)
        } finally {
            releaseWriter.countDown()
            executor.shutdownNow()
            fixture.close()
        }
    }

    private class Fixture {
        val endpoint = FakeEndpoint()
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider {
                endpoint.takeIf { endpointAvailable }
            },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { endpointAlive }
            },
        )
        @Volatile
        var digest: String? = "digest-a"
        var healthy = true
        var acceptedHealthDigest: String? = null
        var endpointAvailable = true
        var endpointAlive = true
        var onDigestRead: (() -> Unit)? = null
        private var installation: AutoCloseable? = null
        private var nextId = 0

        fun registerAndInstall() {
            assertEquals(
                QwySemanticSessionRegistration.Registered("digest-a"),
                coordinator.registerCurrentSession("digest-a"),
            )
            install()
        }

        fun install() {
            installation = QwySemanticWriterRuntime.install(
                coordinator = coordinator,
                semanticDigestProvider = QwySemanticDigestProvider {
                    onDigestRead?.invoke()
                    digest
                },
                sessionHealth = QwySemanticSessionHealth { expected ->
                    healthy && expected == (acceptedHealthDigest ?: digest)
                },
                mutationIdFactory = { kind -> "writer-$kind-${++nextId}" },
            )
        }

        fun close() {
            installation?.close()
            installation = null
        }
    }

    private class FakeEndpoint : QwySemanticMutationEndpoint {
        val calls = mutableListOf<String>()
        private var nextToken = 0L

        override fun registerCurrentSession(
            semanticDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ) {
            calls += "register:$semanticDigest"
        }

        override fun beginMutation(
            mutationId: String,
            beforeDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ): Long {
            calls += "begin:$mutationId:$beforeDigest"
            return ++nextToken
        }

        override fun finishMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) {
            calls += "finish:$token:$changed:$uncertain:${afterDigest.orEmpty()}"
        }
    }
}
