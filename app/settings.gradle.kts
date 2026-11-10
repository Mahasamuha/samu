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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only for the WebRTC voice activity detector (endpointing in piped STT); nothing else may come from here.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.gkonovalov.android-vad")
            }
        }
    }
}

rootProject.name = "Mahasamuha Assistant"
