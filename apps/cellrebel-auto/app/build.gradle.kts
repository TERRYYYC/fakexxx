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
        // Non-empty only on a lane build type that must pair with a same-suffix provider
        // (e.g. glmbench -> name.caiyao.fakegps.glmbench). Empty = ProviderPrincipal
        // resolves by build debug/release semantics as before.
        buildConfigField("String", "PROVIDER_APPLICATION_ID_OVERRIDE", "\"\"")
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
        // glmbench lane: debuggable sibling of debug that installs as a SEPARATE app
        // (com.example.cellrebelauto.glmbench) alongside production/bench installs, pairs
        // ONLY with name.caiyao.fakegps.glmbench via the BuildConfig override, and reuses
        // the whole debug tooling source set (probes, ProviderPrincipalBuild).
        create("glmbench") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".glmbench"
            matchingFallbacks.add("debug")
            buildConfigField("String", "PROVIDER_APPLICATION_ID_OVERRIDE", "\"name.caiyao.fakegps.glmbench\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        // The glmbench build type compiles the debug lane's sources (probe surfaces +
        // debug ProviderPrincipalBuild); its own srcDir stays available for lane-only
        // additions. Manifest merge pulls the debug-only exported probe activities.
        getByName("glmbench") {
            java.srcDir("src/debug/java")
            manifest.srcFile("src/debug/AndroidManifest.xml")
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
        compose = true
        buildConfig = true
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
    // G2 §5A plan seeder parses the frozen fixture JSON on-device via org.json;
    // the real impl (not the android.jar stub) lets the parser be JVM-unit-tested
    // without booting Robolectric. Test-only; production unchanged.
    testImplementation("org.json:json:20240303")
}
