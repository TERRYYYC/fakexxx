package com.example.cellrebelauto.ui

import android.net.Uri
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T11c: the A2 Provider-page todo bar's pure projection — "对方（QWY）还未批准我方"
 * is derivable from the EXISTING discover channel and must stop being a blind spot.
 *
 * The refusal message shape below is the EXACT one
 * [com.example.cellrebelauto.integration.v1.EnvironmentControlClient] produces when
 * QWY answers discover with the frozen NOT_PAIRED error code
 * ("discover validation failed: PROVIDER_ERROR_<wire> (kind=… err=… diag=…)") —
 * the test pins that coupling so a client-side wording change cannot silently
 * degrade the todo into an unreachable-looking grey.
 *
 * # 对方批准状态投影：纯函数 + 精确钉住 NOT_PAIRED 拒答消息形状
 */
@RunWith(RobolectricTestRunner::class)
class PeerPairingStatusTest {

    private fun notPairedRefusal() = EnvironmentControlClient.HandshakeResult.Refused(
        providerPackage = "name.caiyao.fakegps.fakexxx",
        cause = IllegalStateException(
            "discover validation failed: " +
                "PROVIDER_ERROR_${ContractErrorCodeV1.NOT_PAIRED.wire}" +
                " (kind=ERROR err=${ContractErrorCodeV1.NOT_PAIRED.wire} diag=NOT_PAIRED)"
        ),
    )

    private fun otherRefusal() = EnvironmentControlClient.HandshakeResult.Refused(
        providerPackage = "name.caiyao.fakegps.fakexxx",
        cause = IllegalStateException(
            "discover validation failed: " +
                "PROVIDER_ERROR_${ContractErrorCodeV1.CALLER_NOT_ALLOWED.wire} (kind=ERROR)"
        ),
    )

    private fun connected() = EnvironmentControlClient.HandshakeResult.Connected(
        providerPackage = "name.caiyao.fakegps.fakexxx",
        snapshot = io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1(
            serviceVersion = "test-provider",
            supportedModeWires = listOf(0),
            supportedVerificationLevelWires = listOf(0),
            continuityCoverageWire = 0,
            environmentRevision = 1L,
            profileRefs = listOf("p1"),
            scheduleRefs = listOf("s1"),
            currentScheduleId = "s1",
            currentItemId = "i1",
            scheduleVersion = 1L,
            exhausted = false,
        ),
    )

    // ---- fromHandshake ----

    @Test
    fun `NOT_PAIRED refusal maps to WAITING_PEER_APPROVAL`() {
        assertEquals(
            PeerPairingStatus.WAITING_PEER_APPROVAL,
            PeerPairingStatus.fromHandshake(notPairedRefusal())
        )
    }

    @Test
    fun `connected handshake maps to PEER_LINK_UP`() {
        assertEquals(
            PeerPairingStatus.PEER_LINK_UP,
            PeerPairingStatus.fromHandshake(connected())
        )
    }

    // R1 (fresh-context review): wire codes 10–17 all share NOT_PAIRED's leading digit,
    // so a refusal projected by a raw startsWith on "PROVIDER_ERROR_1" would misread
    // e.g. INTERNAL_FAILURE(11) — "provider blew up" — as "QWY has not approved us".
    // Only the EXACT typed outcome token may become the todo.
    @Test
    fun `refusals whose wire code merely shares NOT_PAIRED's digit prefix never become the todo`() {
        listOf(
            ContractErrorCodeV1.INTERNAL_FAILURE,
            ContractErrorCodeV1.RELEASE_INCOMPLETE,
            ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
            ContractErrorCodeV1.SCHEDULE_IDENTITY_MISMATCH,
        ).forEach { code ->
            val refused = EnvironmentControlClient.HandshakeResult.Refused(
                providerPackage = "name.caiyao.fakegps.fakexxx",
                cause = IllegalStateException(
                    "discover validation failed: " +
                        "PROVIDER_ERROR_${code.wire}" +
                        " (kind=ERROR err=${code.wire} diag=pin)"
                ),
            )
            assertEquals(
                "$code must project UNREACHABLE, never WAITING_PEER_APPROVAL",
                PeerPairingStatus.UNREACHABLE,
                PeerPairingStatus.fromHandshake(refused),
            )
        }
    }

    @Test
    fun `not bindable and other refusals map to UNREACHABLE - never a guessed todo`() {
        assertEquals(
            PeerPairingStatus.UNREACHABLE,
            PeerPairingStatus.fromHandshake(
                EnvironmentControlClient.HandshakeResult.NotBindable(listOf("pkg"))
            )
        )
        assertEquals(
            PeerPairingStatus.UNREACHABLE,
            PeerPairingStatus.fromHandshake(otherRefusal())
        )
        assertNull("probe failure is UNKNOWN, not unreachable", PeerPairingStatus.fromHandshake(null))
    }

    // ---- todo bar projection ----

    @Test
    fun `waiting for peer approval renders the todo with the map pending deep link`() {
        val bar = PeerApprovalTodoBar.project(PeerPairingStatus.WAITING_PEER_APPROVAL)
        assertTrue("this IS the todo", bar.isTodo)
        assertTrue(bar.statusLine.contains("还未批准"))
        assertEquals(Uri.parse("fakexxx-map://pending"), bar.peerDeepLink)
        assertEquals("去 QWY 批准", bar.actionLabel)
    }

    @Test
    fun `link up never shows a todo`() {
        val bar = PeerApprovalTodoBar.project(PeerPairingStatus.PEER_LINK_UP)
        assertFalse(bar.isTodo)
        assertNull(bar.peerDeepLink)
    }

    @Test
    fun `unknown and unreachable explain themselves without a jump button`() {
        val unknown = PeerApprovalTodoBar.project(null)
        assertFalse(unknown.isTodo)
        assertNull(unknown.peerDeepLink)
        assertTrue(unknown.statusLine.contains("未知"))

        val unreachable = PeerApprovalTodoBar.project(PeerPairingStatus.UNREACHABLE)
        assertFalse(unreachable.isTodo)
        assertNull(unreachable.peerDeepLink)
    }
}
