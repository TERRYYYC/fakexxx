package name.caiyao.fakegps.mockprovider

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationServices
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import name.caiyao.fakegps.R
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.integration.v1.AndroidSystemMockLocationReader
import name.caiyao.fakegps.integration.v1.AndroidSystemMockDiagnosticLogger
import name.caiyao.fakegps.integration.v1.SystemMockDiagnosticOrigin
import name.caiyao.fakegps.integration.v1.SystemMockTrustPolicy
import name.caiyao.fakegps.ui.ComposeActivity

class MockProviderService : Service() {
    private lateinit var controller: MockProviderSessionController
    private lateinit var orchestrator: LocationDeliveryOrchestrator
    private val handler = Handler(Looper.getMainLooper())
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private lateinit var sessionRunner: MockProviderSessionRunner
    private val tick = object : Runnable {
        override fun run() {
            runSession("refresh", orchestrator::refresh, continueWhileRunning = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gateway = CoordinatedMockProviderGateway(
            framework = AndroidMockProviderGateway(this),
            fused = GooglePlayServicesFusedMockProviderGateway(
                LocationServices.getFusedLocationProviderClient(this),
            ),
        )
        controller = MockProviderSessionController(
            gateway = gateway,
            ownership = ProcessMockProviderOwnership,
            onStateChanged = MockProviderStatusStore::publish,
        )
        sessionRunner = MockProviderSessionRunner(
            worker = commandExecutor,
            completion = Executor(handler::post),
        )
        val settings = SpoofSettings.getInstance(this)
        val trustPolicy = SystemMockTrustPolicy(AndroidSystemMockLocationReader(manager), diagnosticSink = {
            AndroidSystemMockDiagnosticLogger.record(SystemMockDiagnosticOrigin.SERVICE, it)
        })
        orchestrator = LocationDeliveryOrchestrator(
            controller = controller,
            readPublished = {
                PublishedConfig.parse(ConfigPrefsSync.readPublished(this).textOrNull)
            },
            readMode = settings::readLocationDeliveryMode,
            readCleanupRequired = settings::isMockProviderCleanupRequired,
            persistMode = settings::setLocationDeliveryMode,
            publishConfig = { ConfigPrefsSync.sync(this) },
            persistCleanupRequired = settings::setMockProviderCleanupRequired,
            ownership = ProcessMockProviderOwnership,
            projectionMatchesExactly = { config ->
                trustPolicy.evaluate(
                    targetLatitude = config.latitude,
                    targetLongitude = config.longitude,
                    publishNotBeforeElapsedRealtimeMs = 0L,
                ).matchesExactTargetProjection
            },
        )
    }

    @RequiresPermission(Manifest.permission.FOREGROUND_SERVICE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(tick)
        when (val command = MockProviderServiceContract.decode(intent?.action)) {
            MockProviderCommand.StartFromEffectiveProfile -> startFromEffectiveProfile()
            MockProviderCommand.StopAndUseHook -> {
                runSession("stop-and-use-hook", orchestrator::disable)
            }
            MockProviderCommand.CleanupRuntimeOnly -> {
                runSession("runtime-cleanup", orchestrator::cleanupRuntimeOnly)
            }
            is MockProviderCommand.Rejected -> {
                publishState("rejected", MockProviderState.Failed(command.message))
                finishService()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (::orchestrator.isInitialized) {
            // Best effort only. SIGKILL/force-stop can skip onDestroy; startup reconciliation is
            // the durable repair path and acceptance verifies system provider identity directly.
            commandExecutor.execute(orchestrator::cleanupRuntimeOnly)
        }
        commandExecutor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(Manifest.permission.FOREGROUND_SERVICE)
    private fun startFromEffectiveProfile() {
        val foregroundFailure = runCatching { beginForeground() }.exceptionOrNull()
        if (foregroundFailure != null) {
            publishState("foreground-failed", MockProviderState.Failed(describe(foregroundFailure)))
            finishService()
            return
        }

        runSession("start", orchestrator::enable, continueWhileRunning = true)
    }

    private fun runSession(
        event: String,
        operation: () -> MockProviderState,
        continueWhileRunning: Boolean = false,
    ) {
        sessionRunner.submit(operation) { result ->
            val state = result.getOrElse { MockProviderState.Failed(describe(it)) }
            publishState(event, state)
            if (continueWhileRunning && state is MockProviderState.Running) {
                handler.postDelayed(tick, TICK_MILLIS)
            } else {
                finishService()
            }
        }
    }

    @RequiresPermission(Manifest.permission.FOREGROUND_SERVICE)
    private fun beginForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    private fun publishState(event: String, state: MockProviderState) {
        MockProviderStatusStore.publish(state)
        Log.i(TAG, "event=$event pid=${Process.myPid()} state=$state")
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "System Mock 位置",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_lan)
        .setContentTitle("千网游 · System Mock 位置")
        .setContentText("使用生效中档案；点停止将切回 Hook")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ComposeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "停止并切回 Hook",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MockProviderService::class.java)
                    .setAction(MockProviderServiceContract.ACTION_STOP_AND_USE_HOOK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun describe(failure: Throwable): String =
        failure.message ?: failure.javaClass.simpleName

    companion object {
        private const val TAG = "MockProviderMain"
        private const val CHANNEL_ID = "system_mock_location"
        private const val NOTIFICATION_ID = 2401
        private const val TICK_MILLIS = 1_000L
    }
}
