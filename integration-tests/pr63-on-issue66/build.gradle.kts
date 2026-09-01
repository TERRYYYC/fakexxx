// Host-only handler/consumer integration gate; this root never enters either APK.
plugins {
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
