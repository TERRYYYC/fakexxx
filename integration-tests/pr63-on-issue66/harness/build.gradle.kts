plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.terryyyc.fakexxx.integration.pr63issue66"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// The app sources below are mounted as two exact canonical support files, never as a production
// dependency or directory-wide test-source import.
val qwyCanonicalSupport = files(
    "../../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/Fakes.kt",
    "../../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/ProviderHarness.kt",
)
val qwyProductionArtifact = providers.provider {
    configurations.getByName("debugUnitTestCompileClasspath")
        .incoming.artifactView {
            attributes.attribute(
                org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
                "android-classes-jar",
            )
            componentFilter { it.displayName == "project :qianwangyou:app" }
        }
        .files.singleFile
}
val expectedQwyCanonicalSupport = setOf(
    rootProject.file("../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/Fakes.kt").canonicalFile,
    rootProject.file("../../apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/integration/v1/support/ProviderHarness.kt").canonicalFile,
)
val hostGuardExternalScripts = files(
    rootProject.file("run-host-gate.sh"),
    rootProject.file("host-gate-test-attestation.init.gradle"),
    rootProject.file("../../scripts/verify-a-plus.sh"),
    rootProject.file("../../scripts/validate-java17-runtime.py"),
    rootProject.file("../../scripts/stage-java17-runtime.py"),
    rootProject.file("../../scripts/fixtures/issue66-java17-runtime-profiles.json"),
    rootProject.file("../../scripts/test_validate_java17_runtime.py"),
    rootProject.file("../../scripts/test_stage_java17_runtime.py"),
    rootProject.file("../../scripts/validate-android-sdk-runtime.py"),
    rootProject.file("../../scripts/test_validate_android_sdk_runtime.py"),
    rootProject.file("../../scripts/collect-issue66-moto-readonly-preflight.sh"),
    rootProject.file("../../scripts/selftest-issue66-moto-readonly-collector.sh"),
    rootProject.file("../../scripts/fixtures/issue66-moto-readonly-collector/fake-adb.sh"),
    rootProject.file("../../scripts/fixtures/issue66-moto-readonly-collector/approved-adb-sha256.tsv"),
    rootProject.file("../../scripts/check-issue66-services-compatibility.sh"),
    rootProject.file("../../scripts/selftest-issue66-services-compatibility.sh"),
    rootProject.file("../../scripts/fixtures/issue66-services-compatibility/fake-dexdump.sh"),
    rootProject.file("../../apps/cellrebel-auto/gradle/wrapper/gradle-wrapper.properties"),
    rootProject.file("../../apps/qianwangyou/gradle/wrapper/gradle-wrapper.properties"),
    rootProject.file("../../acceptance/gradle/wrapper/gradle-wrapper.properties"),
)
val hostGuardExternalAppSources = files(
    rootProject.file("../../apps/cellrebel-auto/app/src/main"),
    rootProject.file("../../apps/qianwangyou/app/src/main"),
)
// HostReceiptModeGuardTest reads this script, so its own bytes are part of the test contract.
val hostGuardBuildScript = files(
    rootProject.file("harness/build.gradle.kts"),
)
val hostGuardExternalDocs = files(
    rootProject.file("../../feature-specs/2026-09-03-readback-and-config-isolation-plan.md"),
    rootProject.file("../../docs/acceptance/issue71-binder-identity-emulator.md"),
    rootProject.file("../../docs/acceptance/readback-isolation-combined-2026-09-03.md"),
    rootProject.file("../../docs/acceptance/issue66-moto-readonly-preflight-runbook.md"),
)

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name == "compileDebugUnitTestKotlin") {
        source(qwyCanonicalSupport)
        compilerOptions.freeCompilerArgs.add(
            qwyProductionArtifact.map { "-Xfriend-paths=${it.absolutePath}" },
        )
    }
}

dependencies {
    testImplementation(project(":environment-control-v1"))
    // The host owns the one frozen contract instance. Exclude only each included build's local
    // copy; every other production transitive remains resolved from the real app graph.
    testImplementation("local.integration:cellrebel-auto-app") {
        attributes {
            attribute(
                com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
                objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "release"),
            )
        }
        exclude(group = "CellRebelAuto", module = "environment-control-v1")
    }
    testImplementation("local.integration:qianwangyou-app") {
        attributes {
            attribute(
                com.android.build.api.attributes.BuildTypeAttr.ATTRIBUTE,
                objects.named(com.android.build.api.attributes.BuildTypeAttr::class.java, "debug"),
            )
        }
        exclude(group = "FakeGPS", module = "environment-control-v1")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.7.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

val verifyResolvedIntegrationBoundary = tasks.register("verifyResolvedIntegrationBoundary") {
    inputs.files(qwyCanonicalSupport)
    doLast {
        val resolvedSupport = qwyCanonicalSupport.files.map { it.canonicalFile }.toSet()
        check(resolvedSupport == expectedQwyCanonicalSupport) {
            "QWY canonical support source whitelist changed: $resolvedSupport"
        }

        val resolvedDirect = configurations.getByName("debugUnitTestRuntimeClasspath")
            .incoming.resolutionResult.root.dependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .map { it.selected.id.displayName }
            .toSet()
        // ResolutionResult includes the consumer self-edge and the Kotlin plugin's implicit
        // stdlib edge. Pin those alongside the eight explicitly declared direct dependencies.
        val expectedDirect = setOf(
            "project :harness",
            "project :environment-control-v1",
            "project :cellrebel-auto:app",
            "project :qianwangyou:app",
            "junit:junit:4.13.2",
            "org.robolectric:robolectric:4.14.1",
            "androidx.test:core:1.6.1",
            "androidx.room:room-testing:2.7.1",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.2.10",
        )
        check(resolvedDirect == expectedDirect) {
            "resolved direct dependency whitelist changed: $resolvedDirect"
        }
    }
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(verifyResolvedIntegrationBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(
        hostGuardExternalScripts,
        hostGuardExternalAppSources,
        hostGuardBuildScript,
        hostGuardExternalDocs,
    )
        .withPropertyName("hostGuardExternalInputs")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}
