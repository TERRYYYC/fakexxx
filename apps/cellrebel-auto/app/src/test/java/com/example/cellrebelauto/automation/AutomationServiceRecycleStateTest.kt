package com.example.cellrebelauto.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AutomationService.Companion as SvcCompanion
import com.example.cellrebelauto.model.AutomationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #15a — the service-recycle state-machine oracle.
 *
 * Device truth: the OEM recycles the accessibility service mid-run (onDestroy). The engine
 * coroutine is cancelled inside serviceScope; the state forwarders die with the SAME scope, so
 * whatever the engine's cancellation handler publishes never reaches the companion StateFlows —
 * the Run page keeps a Running-like state and History keeps the zombie attempt.
 *
 * The destroy path must therefore publish a TYPED terminal SYNCHRONOUSLY (no IO, no suspension)
 * BEFORE any coroutine cancellation:
 *   - currentState = SERVICE_RECYCLED (typed terminal, not a stale mid-run state);
 *   - currentTask / cooldown cleared (no stale run cards);
 *   - isRunning = false.
 *
 * Drives the REAL AutomationService lifecycle (attach → onServiceConnected → simulated mid-run
 * companion projections → onDestroy) using the same reflection seam as ProviderBindLifecycleTest
 * (Robolectric does not drive accessibility-service callbacks).
 *
 * # #15a 服务回收状态机 oracle：destroy 必须同步发布类型化终态并清空运行投影
 */
@RunWith(RobolectricTestRunner::class)
class AutomationServiceRecycleStateTest {

    @Test
    fun `a cancelled run cannot be replaced until its non-cancellable retirement completes`() = runBlocking {
        val retirementRelease = CompletableDeferred<Unit>()
        val enteredRetirement = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    enteredRetirement.complete(Unit)
                    retirementRelease.await()
                }
            }
        }

        job.cancel()
        enteredRetirement.await()
        assertFalse("cancelled is not the same as durably retired", mayStartAutomation(job))
        retirementRelease.complete(Unit)
        job.join()
        assertTrue(mayStartAutomation(job))
    }

    @Test
    fun `closed lifecycle fence rejects a stale forwarder even after a new run begins`() {
        val fence = ServiceProjectionFence()
        val staleRun = fence.beginRun()
        var projection = AutomationState.WAITING_INTERVAL
        val publisherEntered = CountDownLatch(1)
        val releasePublisher = CountDownLatch(1)

        val publisher = Thread {
            publisherEntered.countDown()
            releasePublisher.await(2, TimeUnit.SECONDS)
            fence.publish(staleRun) { projection = AutomationState.IDLE }
        }
        publisher.start()
        assertTrue(publisherEntered.await(2, TimeUnit.SECONDS))

        fence.close { projection = AutomationState.SERVICE_RECYCLED }
        fence.beginRun()
        releasePublisher.countDown()
        publisher.join(2_000)

        assertEquals(AutomationState.SERVICE_RECYCLED, projection)
    }

    private fun newConnectedService(): AutomationService {
        val service = AutomationService()
        val context: Context = ApplicationProvider.getApplicationContext()
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
        val connect = AutomationService::class.java.getDeclaredMethod("onServiceConnected")
        connect.isAccessible = true
        connect.invoke(service)
        return service
    }

    /**
     * Reflection seam onto the companion's private StateFlows (the forwarders' targets).
     * Kotlin compiles private companion properties to STATIC fields on the OUTER class
     * (no companion instance needed); the companion-instance branch is the fallback.
     */
    @Suppress("UNCHECKED_CAST")
    private fun companionFlow(name: String): MutableStateFlow<Any?> {
        val outer = AutomationService::class.java
        val staticField = outer.declaredFields.firstOrNull { it.name == name }
        if (staticField != null) {
            staticField.isAccessible = true
            return staticField.get(null) as MutableStateFlow<Any?>
        }
        val companionClass = outer.declaredClasses.first { it.simpleName == "Companion" }
        val holder = outer.declaredFields.first { it.type == companionClass }
        holder.isAccessible = true
        val companionInstance = holder.get(null)
        val field = companionClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(companionInstance) as MutableStateFlow<Any?>
    }

    @Test
    fun `onDestroy publishes the typed SERVICE_RECYCLED terminal and clears the run projections synchronously`() {
        val service = newConnectedService()
        // Simulate the mid-run projections the forwarders would have published (engine parked
        // inside the GPS settle stage — the device's silent-death shape).
        companionFlow("_isRunning").value = true
        companionFlow("_currentState").value = AutomationState.WAITING_INTERVAL
        companionFlow("_currentTask").value = EngineTaskSnapshot(
            csvRow = 1, priority = 1, latitude = 39.9, longitude = 116.4,
            completedSuccesses = 0, requiredSuccesses = 1, attemptOrdinal = 1
        )
        companionFlow("_cooldown").value = CooldownInfo(0L, 5_000L, 60_000L, "retry same location")

        val destroy = AutomationService::class.java.getDeclaredMethod("onDestroy")
        destroy.isAccessible = true
        destroy.invoke(service)

        // The typed terminal — RED today (stays WAITING_INTERVAL, the running illusion).
        assertEquals(
            "destroy must publish the typed SERVICE_RECYCLED terminal (issue #15)",
            AutomationState.SERVICE_RECYCLED,
            SvcCompanion.currentState.value
        )
        // Run-page cards must be retracted.
        assertNull("currentTask must be cleared on destroy", SvcCompanion.currentTask.value)
        assertNull("cooldown must be cleared on destroy", SvcCompanion.cooldown.value)
        assertFalse("isRunning must be false after destroy", SvcCompanion.isRunning.value)
        assertEquals(false, SvcCompanion.isServiceConnected.value)
    }
}
