// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    apply(from = "versions.gradle")

    val kotlinVersion = "1.9.23" // Define Kotlin version
    val androidGradlePluginVersion = "8.7.3"
    val googlePlayServicesVersion = "4.4.2"
    val hiltVersion = "2.48"
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:kover:0.6.1")
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

// Copy google-services.json from templates if missing (e.g. JitPack build).
// The file is untracked to prevent leaking Firebase credentials, but the
// google-services Gradle plugin requires it at build time.
tasks.register("restoreGoogleServicesTemplates") {
    description = "Restores google-services.json from templates if the file is missing."
    doLast {
        val sampleApps = listOf("compose_app", "xml_app", "connection_service_app")
        sampleApps.forEach { app ->
            val target = file("samples/$app/google-services.json")
            val template = file("samples/$app/google-services.json.template")
            if (!target.exists() && template.exists()) {
                template.copyTo(target)
                logger.lifecycle("Restored samples/$app/google-services.json from template")
            }
        }
    }
}

// Run before any build task that needs google-services.json
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("build") || it.name == "clean" }.configureEach {
    dependsOn("restoreGoogleServicesTemplates")
}
