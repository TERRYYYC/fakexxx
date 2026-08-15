pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CellRebelAuto"
include(":app")

// R43 (Sol GREEN-review P1-1): the frozen environment-control contract, consumed via include +
// projectDir override exactly as the contract's own build.gradle.kts documents (two independent
// Gradle roots share contracts/ without stepping on each other's build dirs — INV-19).
include(":environment-control-v1")
project(":environment-control-v1").projectDir =
    file("${rootProject.projectDir}/../../contracts/environment-control-v1")
