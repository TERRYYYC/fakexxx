package name.caiyao.fakegps.probe

import android.content.Context

/** Release variant: the public-API read-back probe is a debug-only diagnostic. */
internal class DebugHookProbeController {
    fun schedule(context: Context) = Unit

    fun close() = Unit
}
