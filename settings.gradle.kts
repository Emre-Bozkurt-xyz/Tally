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

rootProject.name = "tally"

include(":core")
include(":server")

// The server's Docker build has no Android SDK; it sets TALLY_SKIP_ANDROID=true.
if (providers.environmentVariable("TALLY_SKIP_ANDROID").orNull != "true") {
    include(":app")
}
