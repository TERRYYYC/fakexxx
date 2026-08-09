package name.caiyao.fakegps.mockprovider

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository

/** Shell-only debug acceptance seam. It never ships in release and only mutates .bench data. */
class MockProviderAcceptanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            COMMAND_PREPARE_KYIV -> prepareKyiv()
            COMMAND_STOP -> {
                MockProviderRuntime.useHookAndStopSystemMock(applicationContext)
                complete(COMMAND_STOP)
            }
            else -> complete("rejected")
        }
    }

    private fun prepareKyiv() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val repository = ProfileRepository(
                    AppDatabase.getInstance(applicationContext),
                    applicationContext,
                )
                repository.deleteAll()
                repository.save(
                    ProfileEntity(
                        addname = "Kyiv acceptance",
                        latitude = KYIV_LATITUDE,
                        longitude = KYIV_LONGITUDE,
                        altitude = KYIV_ALTITUDE,
                        accuracy = 3f,
                        tac = 27101,
                        wifiSsid = "Kyiv-Acceptance",
                    ),
                )
                val settings = SpoofSettings.getInstance(applicationContext)
                settings.setLocationDeliveryMode(LocationDeliveryMode.HOOK)
                settings.setMockProviderCleanupRequired(false)
                ConfigPrefsSync.sync(applicationContext)
            }
            complete(COMMAND_PREPARE_KYIV)
        }
    }

    private fun complete(command: String) {
        Log.i(TAG, "READY command=$command")
        finish()
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val COMMAND_PREPARE_KYIV = "prepare_kyiv"
        const val COMMAND_STOP = "stop"
        const val KYIV_LATITUDE = 50.4501
        const val KYIV_LONGITUDE = 30.5234
        const val KYIV_ALTITUDE = 179.0
        private const val TAG = "MockProviderAcceptance"
    }
}
