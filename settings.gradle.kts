pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://plugins.gradle.org/m2/") }
        maven { url = uri("https://jitpack.io") }
    }
    plugins {
        id("com.android.application") version "8.6.1"
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

// Review the unpublished V2 stack locally without changing the SDK's published dependencies.
providers.gradleProperty("callReportV2SharedDir").orNull?.let { sharedDir ->
    includeBuild(sharedDir) {
        dependencySubstitution {
            substitute(module("com.telnyx.webrtc.mobile.shared:core")).using(project(":modules:observability:core"))
            substitute(module("com.telnyx.webrtc.mobile.shared:contract")).using(project(":modules:observability:contract"))
        }
    }
}
