package name.caiyao.fakegps.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/** Debug implementation that schedules the normal UI's read-back probe off the main thread. */
internal class DebugHookProbeController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeRunner = HookProbeRunner(
        callbackExecutor = Executor(mainHandler::post),
    )
    private val delayedProbe = Runnable {
        probeRunner.submit(
            task = { HookProbe.run(applicationContext) },
            completion = { result ->
                result.exceptionOrNull()?.let { Log.e(HookProbe.TAG, "probe failed", it) }
            },
        )
    }
    private lateinit var applicationContext: Context

    fun schedule(context: Context) {
        applicationContext = context.applicationContext
        mainHandler.postDelayed(delayedProbe, PROBE_DELAY_MS)
    }

    fun close() {
        mainHandler.removeCallbacks(delayedProbe)
        probeRunner.close()
    }

    private companion object {
        const val PROBE_DELAY_MS = 5_000L
    }
}
