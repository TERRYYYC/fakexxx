package name.caiyao.fakegps.integration.v1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import kotlinx.coroutines.runBlocking
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run on a disposable emulator with fresh target app data, not a user's profile store. */
@RunWith(AndroidJUnit4::class)
class BinderIdentityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testPackage = instrumentation.context.packageName

    @Test
    fun approvedRemoteCallerCanPublishFromTheRealPrivateProvider() {
        assertEquals("Only run on a disposable emulator", "ranchu", Build.HARDWARE)
        val marker = "binder-identity-${UUID.randomUUID()}"
        // Deliberately use the DAO: repository writes would pre-publish and mask the bug.
        runBlocking {
            val dao = AppDatabase.getInstance(context).profileDao()
            assertTrue("Use fresh disposable app data", dao.getAll().isEmpty())
            dao.insert(ProfileEntity(addname = marker, latitude = 12.345, longitude = 23.456))
        }
        assertEquals(PayloadRead.Absent, ConfigPrefsSync.readPublished(context))
        val authority = "${context.packageName}.data.AppInfoProvider"
        assertFalse(context.packageManager.resolveContentProvider(authority, 0)!!.exported)

        withRelay { relay ->
            val remote = IEnvironmentControlV1.Stub.asInterface(serviceProxy(relay))
            assertAllEntriesRejectUnpaired(remote)
            val candidate = ProviderRuntime.pendingCallers(context).single {
                it.callerApplicationId == testPackage
            }
            val digest = AndroidPackageIdentityResolver(context)
                .signerLookup(testPackage)!!.currentSignerDigests.single()
            assertEquals(digest, candidate.currentSignerDigest)
            assertFalse(ProviderRuntime.approveCaller(context, testPackage, "wrong-signer"))
            assertEquals(ContractErrorCodeV1.NOT_PAIRED.wire, remote.discover().errorCodeWire)
            assertTrue(ProviderRuntime.approveCaller(context, testPackage, digest))
            val snapshot = remote.discover().capabilitySnapshot!!
            val scheduleId = checkNotNull(snapshot.currentScheduleId)
            val itemId = checkNotNull(snapshot.currentItemId)
            val now = System.currentTimeMillis()
            val intent = EnvironmentIntentV1(
                runId = UUID.randomUUID().toString(),
                attemptId = UUID.randomUUID().toString(),
                profileRef = itemId,
                scheduleRef = scheduleId,
                requiredVerificationWire = 1,
                notBeforeEpochMs = now - 1_000,
                deadlineEpochMs = now + 600_000,
            )
            assertEquals("No fixture or discover call may pre-publish", PayloadRead.Absent,
                ConfigPrefsSync.readPublished(context))
            assertNotNull("Approved preflight must retain the remote principal",
                remote.preflight(PreflightRequestV1(intent, "approved-preflight", ContractV1.PROTOCOL_VERSION)).preflightReport)
            val result = remote.apply(ApplyRequestV1(intent, UUID.randomUUID().toString(), ContractV1.PROTOCOL_VERSION))
            val receipt = result.applyReceipt
            assertNotNull("Expected apply receipt, got $result", receipt)
            try {
                val published = ConfigPrefsSync.readPublished(context)
                assertTrue("Remote apply must query QWY's private provider and commit a fresh payload; got $published",
                    published is PayloadRead.Raw)
                assertEquals(marker, JSONObject((published as PayloadRead.Raw).text)
                    .getJSONObject("fields").getString("addname"))
                // Payload construction is NOT proof of cross-process readability or real location.
                Log.i("BinderIdentityTest", "private-provider payload matched; verification=${receipt!!.verificationLevelWire}")
                assertNotNull("Approved observe must retain the remote lease principal",
                    remote.observe(ObserveRequestV1(receipt.leaseId, "approved-observe", receipt.acceptedIntentHash))
                        .environmentObservation)
                // Invalid digest is deliberately refused AFTER authorization and BEFORE advance.
                // This exercises the sixth entry without inventing a trusted completion ledger.
                assertEquals(ContractErrorCodeV1.REQUEST_INVALID.wire,
                    remote.completeAndAdvance(CompleteAndAdvanceRequestV1(receipt.leaseId,
                        "approved-invalid-advance", "deliberately-wrong-digest", scheduleId,
                        snapshot.scheduleVersion!!, itemId,
                        CompletionProofV1(itemId, 0, 1, "not-a-completion", SystemClock.elapsedRealtime()),
                        ContractV1.PROTOCOL_VERSION)).errorCodeWire)
                // A second real principal, QWY itself, must not adopt the remote caller's lease.
                withBoundService(ComponentName(context.packageName, EnvironmentControlService::class.java.name)) { binder ->
                    val local = IEnvironmentControlV1.Stub.asInterface(binder)
                    assertEquals(ContractErrorCodeV1.NOT_PAIRED.wire, local.discover().errorCodeWire)
                    val localDigest = AndroidPackageIdentityResolver(context)
                        .signerLookup(context.packageName)!!.currentSignerDigests.single()
                    assertTrue(ProviderRuntime.approveCaller(context, context.packageName, localDigest))
                    assertNotNull(local.discover().capabilitySnapshot)
                    assertEquals(ContractErrorCodeV1.STALE_LEASE.wire,
                        local.observe(ObserveRequestV1(receipt.leaseId, "foreign-observe", receipt.acceptedIntentHash)).errorCodeWire)
                    assertEquals(ContractErrorCodeV1.STALE_LEASE.wire,
                        local.release(ReleaseRequestV1(receipt.leaseId, "foreign-release", "foreign-key")).errorCodeWire)
                }
            } finally {
                val release = remote.release(ReleaseRequestV1(receipt!!.leaseId,
                    UUID.randomUUID().toString(), UUID.randomUUID().toString()))
                assertTrue("Lease cleanup must complete: $release", release.releaseReceipt?.releaseComplete == true)
            }
        }
    }

    private fun assertAllEntriesRejectUnpaired(remote: IEnvironmentControlV1) {
        val now = System.currentTimeMillis()
        val intent = EnvironmentIntentV1("unpaired-run", "unpaired-attempt", "unused-profile",
            "unused-schedule", 1, now - 1_000, now + 600_000)
        val results = linkedMapOf(
            "discover" to remote.discover(),
            "preflight" to remote.preflight(PreflightRequestV1(intent, "unpaired-preflight", ContractV1.PROTOCOL_VERSION)),
            "apply" to remote.apply(ApplyRequestV1(intent, "unpaired-apply", ContractV1.PROTOCOL_VERSION)),
            "observe" to remote.observe(ObserveRequestV1("unused-lease", "unpaired-observe", "unused-hash")),
            "release" to remote.release(ReleaseRequestV1("unused-lease", "unpaired-release", "unpaired-release-key")),
            "completeAndAdvance" to remote.completeAndAdvance(CompleteAndAdvanceRequestV1(
                "unused-lease", "unpaired-advance", "unused-digest", "unused-schedule", 1,
                "unused-item", CompletionProofV1("unused-item", 1, 1, "unused-ledger", SystemClock.elapsedRealtime()),
                ContractV1.PROTOCOL_VERSION)),
        )
        results.forEach { (operation, result) ->
            assertEquals("$operation must authorize the remote principal: $result",
                ContractErrorCodeV1.NOT_PAIRED.wire, result.errorCodeWire)
        }
    }

    @Test
    fun identityIsRestoredInsideTheSameIncomingTransactionOnEveryExit() {
        withRelay { relay ->
            val remoteUid = context.packageManager.getApplicationInfo(testPackage, 0).uid
            // Each case observes BEFORE/INSIDE/AFTER within one server-side transaction.
            // A later transaction cannot prove finally: Binder resets identity between calls.
            for (exit in listOf("return", "typed-rejection", "initialization-throw", "execution-throw")) {
                val probe = object : Binder() {
                    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                        val before = Binder.getCallingUid()
                        var captured = -1
                        var inside = -1
                        var nestedRestored = -1
                        var outcome = ""
                        val sentinel = IllegalStateException(exit)
                        try {
                            withProviderBinderIdentity { uid ->
                                captured = uid
                                inside = Binder.getCallingUid()
                                withProviderBinderIdentity { nestedUid ->
                                    check(nestedUid == Process.myUid())
                                }
                                nestedRestored = Binder.getCallingUid()
                                if (exit == "initialization-throw") throw sentinel
                                val result = toTypedResult {
                                    if (exit == "typed-rejection") throw ContractException(ContractErrorCodeV1.NOT_PAIRED)
                                    if (exit == "execution-throw") throw sentinel
                                    // An ordinary carrier return, without throwing or relying on framework exception conversion.
                                    EnvironmentControlResultV1.failure(ContractErrorCodeV1.REQUEST_INVALID.wire)
                                }
                                outcome = if (result.errorCodeWire == ContractErrorCodeV1.NOT_PAIRED.wire)
                                    "typed-rejection" else "return"
                            }
                        } catch (t: IllegalStateException) {
                            outcome = if (t === sentinel) exit else "wrong-exception"
                        }
                        val after = Binder.getCallingUid()
                        reply!!.writeNoException()
                        listOf(before, captured, inside, nestedRestored, after).forEach(reply::writeInt)
                        reply.writeString(outcome)
                        return true
                    }
                }
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    assertTrue(forward(relay, RemoteBinderRelayService.TRANSACTION_FORWARD_BINDER,
                        IBinder.FIRST_CALL_TRANSACTION, data, reply, probe))
                    reply.readException()
                    assertEquals("$exit before", remoteUid, reply.readInt())
                    assertEquals("$exit captured principal", remoteUid, reply.readInt())
                    assertEquals("$exit local work", Process.myUid(), reply.readInt())
                    assertEquals("$exit nested restore", Process.myUid(), reply.readInt())
                    assertEquals("$exit finally restore", remoteUid, reply.readInt())
                    assertEquals(exit, reply.readString())
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
        }
    }

    private fun serviceProxy(relay: IBinder): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            require(flags and IBinder.FLAG_ONEWAY == 0 && reply != null)
            return forward(relay, RemoteBinderRelayService.TRANSACTION_FORWARD_SERVICE, code, data, reply)
        }
    }

    private fun forward(relay: IBinder, operation: Int, code: Int, data: Parcel,
                        reply: Parcel, target: IBinder? = null): Boolean {
        val envelope = Parcel.obtain()
        val response = Parcel.obtain()
        try {
            envelope.writeInterfaceToken(RemoteBinderRelayService.DESCRIPTOR)
            if (target != null) envelope.writeStrongBinder(target)
            envelope.writeInt(code)
            envelope.writeByteArray(data.marshall())
            check(relay.transact(operation, envelope, response, 0))
            response.readException()
            val handled = response.readInt() != 0
            val bytes = response.createByteArray()!!
            reply.unmarshall(bytes, 0, bytes.size)
            reply.setDataPosition(0)
            return handled
        } finally {
            response.recycle()
            envelope.recycle()
        }
    }

    private fun withRelay(block: (IBinder) -> Unit) {
        withBoundService(ComponentName(testPackage, RemoteBinderRelayService::class.java.name)) { binder ->
            awaitRelayReady(binder)
            block(binder)
        }
    }

    private fun withBoundService(component: ComponentName, block: (IBinder) -> Unit) {
        val latch = CountDownLatch(1)
        var relay: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                relay = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val intent = Intent().setComponent(component)
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            check(latch.await(10, TimeUnit.SECONDS)) { "Service did not bind: $component" }
            val binder = checkNotNull(relay)
            block(binder)
        } finally {
            context.unbindService(connection)
        }
    }

    private fun awaitRelayReady(binder: IBinder) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            val ready: Boolean
            try {
                request.writeInterfaceToken(RemoteBinderRelayService.DESCRIPTOR)
                check(binder.transact(RemoteBinderRelayService.TRANSACTION_STATUS, request, response, 0))
                response.readException()
                val uid = response.readInt()
                val pid = response.readInt()
                ready = response.readInt() != 0
                assertEquals(context.packageName, response.readString())
                assertEquals(context.packageManager.getApplicationInfo(testPackage, 0).uid, uid)
                assertNotEquals("Must be a distinct APK UID", Process.myUid(), uid)
                assertNotEquals("Must be a real remote process", Process.myPid(), pid)
                if (ready) Log.i("BinderIdentityTest", "QWY uid=${Process.myUid()} remote uid=$uid pid=$pid")
            } finally {
                request.recycle()
                response.recycle()
            }
            if (ready) break
            check(SystemClock.elapsedRealtime() < deadline) { "Production service did not bind" }
            SystemClock.sleep(25)
        }
    }
}
