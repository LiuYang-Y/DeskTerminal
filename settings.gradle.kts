pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DeskTerminal"

include(
    ":app",
    ":shell",
    ":connection",
    ":core:common",
    ":core:model",
    ":core:ui",
    ":core:platform",
    ":core:data",
    ":projection:api",
    ":projection:carplay",
    ":projection:auto",
    ":feature:home",
    ":feature:media",
    ":hosting"
)
