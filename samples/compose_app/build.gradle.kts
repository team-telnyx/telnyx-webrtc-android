import java.io.FileInputStream
import java.util.*

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
    kotlin("plugin.serialization") version "1.9.0"
}

android {
    namespace = "org.telnyx.webrtc.compose_app"
    compileSdk = 35

    buildFeatures.buildConfig  = true

    defaultConfig {
        applicationId = "org.telnyx.webrtc.compose_app"
        minSdk = 27
        targetSdk = 35
        versionCode = 14
        versionName = "14"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val properties = Properties()
        val localPropertiesFile = file("${rootDir}/local.properties")

        if (localPropertiesFile.exists()) {
            FileInputStream(localPropertiesFile).use { properties.load(it) }
        }

        buildConfigField("String", "TEST_SIP_USERNAME", "\"${properties.getProperty("TEST_SIP_USERNAME", "default_username")}\"")
        buildConfigField("String", "TEST_SIP_PASSWORD", "\"${properties.getProperty("TEST_SIP_PASSWORD", "default_password")}\"")
        buildConfigField("String", "TEST_SIP_CALLER_NAME", "\"${properties.getProperty("TEST_SIP_CALLER_NAME", "default_callername")}\"")
        buildConfigField("String", "TEST_SIP_CALLER_NUMBER", "\"${properties.getProperty("TEST_SIP_CALLER_NUMBER", "default_callernumber")}\"")
        buildConfigField("String", "TEST_SIP_DEST_NUMBER", "\"${properties.getProperty("TEST_SIP_DEST_NUMBER", "default_dest_number")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13" // Use the latest version compatible with your Kotlin version
    }
    buildFeatures {
        compose = true
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(project(":telnyx_common"))
    implementation("com.jakewharton.timber:timber:4.5.1")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.0")

    implementation("androidx.compose.runtime:runtime-livedata:1.7.7")
    androidTestImplementation("androidx.navigation:navigation-testing:2.8.7")

    //permissions
    implementation("com.karumi:dexter:6.2.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    val nav_version = "2.9.0"
    implementation("androidx.navigation:navigation-compose:$nav_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.1")

}