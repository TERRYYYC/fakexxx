plugins {
    // Contract module + harness modules are Android libraries (the contract
    // uses AIDL + parcelize, so its consumers must be Android-Gradle builds).
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10" apply false
}
