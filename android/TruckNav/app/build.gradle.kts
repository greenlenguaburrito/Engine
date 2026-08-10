import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load the TomTom API key from local.properties (gitignored) so it never lands in
// version control. Falls back to a TOMTOM_API_KEY env var for CI use.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}
val tomtomApiKey: String = (localProperties.getProperty("tomtom.api.key")
    ?: System.getenv("TOMTOM_API_KEY")
    ?: "").trim()

android {
    namespace = "com.trucknav.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trucknav.pro"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX / Material
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")

    // Location updates used to drive turn-by-turn voice guidance.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // --- TomTom SDK -----------------------------------------------------
    // NOTE: TomTom revs these frequently. If Gradle sync reports a version
    // that no longer resolves, check the current numbers at
    // https://docs.tomtom.com/maps/android/getting-started/project-setup
    // and https://docs.tomtom.com/navigation/android/getting-started/project-setup
    // and bump them here — the artifact/group names themselves are stable.
    val tomtomInitVersion = "2.4.2"
    val tomtomMapsVersion = "1.26.3"
    val tomtomRoutingVersion = "1.25.6"
    val tomtomSearchVersion = "1.26.3"

    implementation("com.tomtom.sdk:init:$tomtomInitVersion")
    implementation("com.tomtom.sdk.maps:map-display:$tomtomMapsVersion")
    implementation("com.tomtom.sdk.location:provider-android:$tomtomMapsVersion")
    implementation("com.tomtom.sdk.search:search-online:$tomtomSearchVersion")
    implementation("com.tomtom.sdk.routing:route-planner-online:$tomtomRoutingVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
