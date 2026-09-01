pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
    }
}

includeBuild("../../apps/cellrebel-auto") {
    dependencySubstitution {
        substitute(module("local.integration:cellrebel-auto-app"))
            .using(project(":app"))
    }
}

includeBuild("../../apps/qianwangyou") {
    dependencySubstitution {
        substitute(module("local.integration:qianwangyou-app"))
            .using(project(":app"))
    }
}

rootProject.name = "Pr63OnIssue66Integration"
include(":harness")
include(":environment-control-v1")
project(":environment-control-v1").projectDir =
    file("../../contracts/environment-control-v1")
