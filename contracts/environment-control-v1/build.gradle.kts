plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "io.github.terryyyc.fakexxx.contract.v1"
    compileSdk = 35

    defaultConfig {
        // Frozen by spec §6.1. The shared library must take the LOWER bound of
        // its two consumers: Auto is minSdk 26, Qianwangyou is minSdk 24. A
        // library declaring 26 cannot be depended on by a minSdk 24 app — AGP
        // fails the build outright.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// This module is consumed by two independent Gradle roots (apps/cellrebel-auto
// and apps/qianwangyou) via `include` + a projectDir override. Without this,
// both roots would write into the same contracts/environment-control-v1/build
// directory and step on each other. Redirecting the output under each
// consuming root keeps the two build lanes independent (INV-19) and keeps
// contracts/ free of build artifacts.
layout.buildDirectory.set(
    rootProject.layout.buildDirectory.dir("contract-environment-control-v1"),
)

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Parcel round-trip assertions need a real android.os.Parcel. Robolectric
    // gives that on the JVM, so version-skew and unparcel behaviour are covered
    // by unit tests rather than deferred to instrumentation on a device.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
