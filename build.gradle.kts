// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    apply(from = "versions.gradle")

    val kotlinVersion = "2.2.0" // Define Kotlin version
    val androidGradlePluginVersion = "8.6.1"
    val googlePlayServicesVersion = "4.4.2"
    val hiltVersion = "2.48"
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.6.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:kover:0.5.1")
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.21.0")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.9.20")
        classpath("com.android.tools.build:gradle:$androidGradlePluginVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("com.google.gms:google-services:$googlePlayServicesVersion")
        classpath("com.google.dagger:hilt-android-gradle-plugin:$hiltVersion")
    }
}

apply(plugin = "kover")

val githubProperties = java.util.Properties().apply {
    load(java.io.FileInputStream(rootProject.file("github.properties")))
}

allprojects {

    configurations.all {
        resolutionStrategy.force("org.objenesis:objenesis:3.3")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
