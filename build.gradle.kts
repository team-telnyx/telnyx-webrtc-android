// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    apply(from = "versions.gradle")

    val kotlinVersion = "1.7.10" // Define Kotlin version
    val androidGradlePluginVersion = "7.3.1"
    val googlePlayServicesVersion = "4.3.15"
    val hiltVersion = "2.44"
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://plugins.gradle.org/m2/") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:$androidGradlePluginVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlinx:kover:0.5.1")
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.21.0")
        classpath("org.jetbrains.dokka:dokka-gradle-plugin:1.7.10")
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
        resolutionStrategy {
            force("org.objenesis:objenesis:3.3")
            force("androidx.core:core:1.9.0")
            force("androidx.core:core-ktx:1.9.0")
            force("androidx.activity:activity:1.6.1")
            force("androidx.activity:activity-ktx:1.6.1")
            force("androidx.fragment:fragment:1.5.5")
            force("androidx.fragment:fragment-ktx:1.5.5")
            force("androidx.transition:transition:1.4.1")
            force("androidx.appcompat:appcompat:1.5.1")
            force("androidx.appcompat:appcompat-resources:1.5.1")
            force("com.google.android.material:material:1.7.0")
            force("androidx.lifecycle:lifecycle-common:2.5.1")
            force("androidx.lifecycle:lifecycle-common-java8:2.5.1")
            force("androidx.lifecycle:lifecycle-livedata:2.5.1")
            force("androidx.lifecycle:lifecycle-livedata-core:2.5.1")
            force("androidx.lifecycle:lifecycle-livedata-core-ktx:2.5.1")
            force("androidx.lifecycle:lifecycle-runtime:2.5.1")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")
            force("androidx.lifecycle:lifecycle-viewmodel:2.5.1")
            force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
            force("androidx.lifecycle:lifecycle-livedata-ktx:2.5.1")
            force("androidx.lifecycle:lifecycle-service:2.5.1")
            force("androidx.test:core:1.5.0")
            force("androidx.test:core-ktx:1.5.0")
            force("androidx.test:runner:1.5.2")
            force("androidx.test:rules:1.5.0")
            force("androidx.test.espresso:espresso-core:3.5.1")
            force("androidx.test.ext:junit:1.1.5")
            force("androidx.test.ext:junit-ktx:1.1.5")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
