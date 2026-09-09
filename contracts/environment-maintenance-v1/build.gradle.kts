plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    // Same namespace as environment-control-v1 BY DESIGN: the maintenance
    // surface is part of the same cross-app contract family and its AIDL
    // descriptors must live in the frozen contract package so both peers
    // resolve identical Binder descriptors.
    namespace = "io.github.terryyyc.fakexxx.contract.v1"
    compileSdk = 35

    defaultConfig {
        // Lower bound of the two consumers: Auto 26, Qianwangyou 24.
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

// Consumed by two independent Gradle roots (apps/cellrebel-auto and
// apps/qianwangyou) via `include` + a projectDir override, exactly like
// environment-control-v1. Redirecting the output under each consuming root
// keeps the two build lanes independent and keeps contracts/ free of build
// artifacts (INV-19).
layout.buildDirectory.set(
    rootProject.layout.buildDirectory.dir("contract-environment-maintenance-v1"),
)

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
