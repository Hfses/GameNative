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
        // LibretroDroid (in-app console emulation)
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.Swordfish90") }
        }
    }
}

rootProject.name = "gamenative"
include(":app")
include(":ubuntufs")
