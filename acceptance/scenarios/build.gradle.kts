plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Matrix scenario runner (#6). The §10.1 ledger freezes the FILE PATHS of the
// `sol-blackbox` rows as `acceptance/scenarios/src/matrixTest/kotlin/matrix/*`;
// Gradle-wise those files are mounted into the JVM unit-test compilation, so
// `:scenarios:testDebugUnitTest` executes every matrix row deterministically.
android {
    namespace = "io.github.terryyyc.fakexxx.acceptance.scenarios"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets {
        // Ledger-frozen location for matrix rows (§10.1): the directory name is
        // part of each row's exact entry, so it is mounted verbatim.
        getByName("test") {
            kotlin.srcDir("src/matrixTest/kotlin")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

dependencies {
    implementation(project(":fake-qwy"))
    implementation(project(":environment-control-v1"))
    testImplementation("junit:junit:4.13.2")
}
