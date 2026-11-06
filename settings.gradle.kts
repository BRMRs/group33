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
    }
}

rootProject.name = "group33"

include(":server")
include(":shared")

// Server-only container builds do not need an Android SDK. Normal local and CI
// builds keep the Android application included.
if (!providers.gradleProperty("serverOnly").isPresent) {
    include(":android-app")
}

