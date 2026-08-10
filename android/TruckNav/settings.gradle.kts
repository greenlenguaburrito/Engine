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

        // TomTom SDK artifacts (Maps display, Search, Routing, Traffic, Location provider).
        // See: https://docs.tomtom.com/maps/android/getting-started/project-setup
        maven {
            url = uri("https://repositories.tomtom.com/artifactory/maven")
        }
    }
}

rootProject.name = "TruckNav"
include(":app")
