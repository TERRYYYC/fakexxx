plugins {
    id("com.android.application") version "9.1.0" apply false
    // Required by the shared contract library module (contract wiring only).
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.3.2" apply false
}
