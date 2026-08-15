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

// Shared Environment Control contract v1. Lives outside this Gradle root
// because Qianwangyou consumes the same module from its own independent root.
include(":environment-control-v1")
project(":environment-control-v1").projectDir =
    file("../../contracts/environment-control-v1")
