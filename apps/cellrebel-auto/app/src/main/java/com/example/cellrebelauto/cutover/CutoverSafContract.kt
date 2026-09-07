package com.example.cellrebelauto.cutover

/** Neutral carrier constants shared by the two compile-time-isolated SAF directions. */
object CutoverSafContract {
    const val MEDIA_TYPE = "application/vnd.fakexxx.auto-cutover-v2"
    const val FILE_EXTENSION = ".fakexxx-auto-cutover-v2"

    fun suggestedFileName(captureId: String): String {
        require(captureId.isNotBlank()) { "capture id cannot be blank" }
        return "cellrebel-auto-$captureId$FILE_EXTENSION"
    }
}
