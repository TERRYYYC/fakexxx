package name.caiyao.fakegps.integration.v1

import android.util.Log

internal enum class SystemMockDiagnosticOrigin {
    INTEGRATION,
    SERVICE,
}

internal object AndroidSystemMockDiagnosticLogger {
    const val TAG = "QwySystemMockRead"

    fun record(origin: SystemMockDiagnosticOrigin, diagnostics: SystemMockEvaluationDiagnostics) {
        SystemMockDiagnosticFormatter.lines(diagnostics).forEach { line ->
            Log.i(TAG, "origin=${origin.name} $line")
        }
    }
}
