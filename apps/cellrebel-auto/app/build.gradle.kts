plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.cellrebelauto"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.cellrebelauto"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("boolean", "CODEX_BENCH", "false")
        manifestPlaceholders["appLabel"] = "CellRebel Auto"
        manifestPlaceholders["benchProviderPackage"] = "name.caiyao.fakegps.bench"
    }

    signingConfigs {
        // F-18 (2026-08-25): pin the debug signer to the repo-committed keystore.
        // The machine-local ~/.android/debug.keystore made the signer ENVIRONMENT state:
        // CI runners roll a fresh random debug.keystore per run, and one random-signed
        // artifact reached the bench device, after which `adb install -r` failed with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE while version fields stayed identical
        // (false-green acceptance; see apps/qianwangyou/app/build.gradle F-18 note and
        // c5-evidence/f18-signer-divergence/). The committed bytes are the machine key
        // (cert sha256 7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41)
        // the device already trusts, so install -r continuity holds.
        // scripts/check-debug-signer.sh guards built artifacts against signer regressions.
        create("bench") {
            storeFile = rootProject.file("keystores/bench.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("bench")
        }
        create("codexBench") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".codexbench"
            buildConfigField("boolean", "CODEX_BENCH", "true")
            manifestPlaceholders["appLabel"] = "CellRebel Auto · codex-bench"
            manifestPlaceholders["benchProviderPackage"] = "name.caiyao.fakegps.codexbench"
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Reuse debug-only adapters and probes while keeping ordinary variant paths unchanged.
    sourceSets.getByName("codexBench") { setRoot("src/debug") }
}

configurations.named("codexBenchImplementation") {
    extendsFrom(configurations.getByName("debugImplementation"))
}

androidComponents {
    // AGP 9 defaults unit tests to the tested build type (debug) only. Exercise
    // the actual codexBench BuildConfig and adapter, without changing other variants.
    beforeVariants(selector().withBuildType("codexBench")) {
        (it as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

// Issue #5 Task 4 (INV-24): export Room schema JSON for version control + migration validation.
// # 导出 Room schema JSON 纳入版本控制，配合 MIGRATION_4_5 校验
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // R43: the frozen environment-control contract v1 (interface freeze 635a73a8).
    implementation(project(":environment-control-v1"))

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Room
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coroutines
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore for config persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (F001 baseline)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
