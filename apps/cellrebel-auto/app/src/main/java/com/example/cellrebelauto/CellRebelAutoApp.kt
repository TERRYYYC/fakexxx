package com.example.cellrebelauto

import android.app.Application
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverControlStore
import com.example.cellrebelauto.cutover.CutoverRunQuiescencePort
import com.example.cellrebelauto.data.PlanConfigStore
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Application class — initializes database singleton.
 * # Application 类：初始化数据库单例
 */
class CellRebelAutoApp : Application() {
    lateinit var cutoverAccessGate: CutoverAccessGate
        private set

    val cutoverControlStore: CutoverControlStore by lazy { CutoverControlStore(this) }

    val database: AppDatabase by lazy {
        check(::cutoverAccessGate.isInitialized) { "cutover gate must initialize before Room" }
        AppDatabase.getInstance(this, cutoverAccessGate)
    }

    val planConfigStore: PlanConfigStore by lazy {
        check(::cutoverAccessGate.isInitialized) { "cutover gate must initialize before PlanConfig" }
        PlanConfigStore(this, cutoverAccessGate)
    }

    val cutoverRunQuiescence: CutoverRunQuiescencePort = CutoverRunQuiescencePort {
        AutomationService.quiesceForCutover()
    }

    override fun onCreate() {
        super.onCreate()
        cutoverAccessGate = try {
            val journal = runBlocking(Dispatchers.IO) { cutoverControlStore.read() }
            CutoverAccessGate.fromJournal(journal)
        } catch (_: Exception) {
            CutoverAccessGate.recoveryRequired()
        }
    }

    companion object {
        /** Non-manifest Application instances exist only in host tests and remain fail-closed. */
        internal fun accessGateFor(application: Application): CutoverAccessGate =
            (application as? CellRebelAutoApp)?.cutoverAccessGate ?: CutoverAccessGate.recoveryRequired()

        internal fun databaseFor(application: Application, accessGate: CutoverAccessGate): AppDatabase =
            (application as? CellRebelAutoApp)?.database
                ?: AppDatabase.getInstance(application, accessGate)

        internal fun planConfigStoreFor(
            application: Application,
            accessGate: CutoverAccessGate
        ): PlanConfigStore = (application as? CellRebelAutoApp)?.planConfigStore
            ?: PlanConfigStore(application, accessGate)
    }
}
