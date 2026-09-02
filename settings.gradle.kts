pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Detour"
include(":app")
// Platform-free core (roulette/routing/trip logic), shared by the Android app
// and the iOS app in iosApp/. See docs/IOS_PORT.md.
include(":shared")
