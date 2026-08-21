pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// Independent acceptance harness root (#6). Deliberately its OWN Gradle root,
// exactly like the two apps: the harness must consume the contract the way a
// third party would — through the shared module's public surface, never
// through app internals.
rootProject.name = "AcceptanceMatrix"

include(":fake-qwy")
include(":scenarios")

// The frozen Environment Control contract v1 (§6). Same projectDir-redirect
// pattern both apps use; single source of truth outside this root.
include(":environment-control-v1")
project(":environment-control-v1").projectDir =
    file("../contracts/environment-control-v1")
