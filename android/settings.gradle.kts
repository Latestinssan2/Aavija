// Root settings — tells Gradle where to find the app module and the plugins.
pluginManagement {
    repositories {
        google() // AndroidX + AGP
        mavenCentral() // Kotlin + other libs
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

rootProject.name = "Aavija"
include(":app")
