pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://plugins.gradle.org/m2/") }
        maven { url = uri("https://jitpack.io") }
    }
    plugins {
        id("com.android.application") version "8.9.0"
        id("org.jetbrains.kotlin.android") version "1.9.23"
        id("org.jetbrains.kotlin.plugin.compose") version "1.9.23"
        id("io.gitlab.arturbosch.detekt") version "1.23.7"
        id("com.google.gms.google-services") version "4.4.2" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://plugins.gradle.org/m2/") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Telnyx Android WebRTC SDK"

include(":telnyx_rtc")
include(":telnyx_common")
include(":samples:connection_service_app")
include(":samples:xml_app")
include(":samples:compose_app")
