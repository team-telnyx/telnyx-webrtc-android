import java.io.FileInputStream
import java.util.*

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "org.telnyx.webrtc.xmlapp"
    compileSdk = 35

    buildFeatures.buildConfig  = true

    defaultConfig {
        applicationId = "org.telnyx.webrtc.xml_app"
        minSdk = 27
        targetSdk = 35
        versionCode = 12
        versionName = "12"

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
        buildConfigField("String", "PRECALL_DIAGNOSIS_NUMBER", "\"${properties.getProperty("PRECALL_DIAGNOSIS_NUMBER", "default_precall_number")}\"")
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
    buildFeatures {
        viewBinding = true
        dataBinding = true
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation(project(":telnyx_common"))
    implementation("androidx.test.espresso:espresso-contrib:3.6.1")
    implementation("androidx.test:rules:1.6.1")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.9.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.9.6")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    //permissions
    implementation("com.karumi:dexter:6.2.2")

    implementation("com.github.davidmigloz:number-keyboard:3.1.0")

    implementation("com.google.code.gson:gson:2.12.0")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
