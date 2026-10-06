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
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // JavaSteam
        // LibretroDroid (in-app console emulation) is published through JitPack
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroupByRegex("(?i)com\\.github\\.swordfish90")
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}

rootProject.name = "gamenative"
include(":app")
include(":ubuntufs")
