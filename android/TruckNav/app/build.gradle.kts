import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// TomTom API key. Checked in three places, in order: local.properties (gitignored,
// for a personal override), a TOMTOM_API_KEY env var (for CI), then the hardcoded
// default below. The default is a real personal/free-tier key committed at the
// user's explicit request for a zero-setup build on this public repo — treat it as
// already public and rotate it at https://developer.tomtom.com if usage looks abused.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}
val tomtomApiKey: String = (localProperties.getProperty("tomtom.api.key")
    ?: System.getenv("TOMTOM_API_KEY")
    ?: "z14dyywuI1EXZvrXfpV90HXNlbz2eNQR").trim()

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

        // TomTom SDK artifacts publish "complete" and "extended" flavor variants;
        // Gradle can't pick one on its own. "extended" needs Artifactory credentials
        // we don't have, so pin to the free/public "complete" flavor.
        missingDimensionStrategy("tomtom-sdk-version", "complete")
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
    // Versions confirmed directly against repositories.tomtom.com's
    // maven-metadata.xml (its <latest> tag) rather than docs pages, which
    // lag. TomTom ships two version "families" that must each be pinned to
    // their own latest, not mixed: init/location-provider on the 2.x line,
    // map-display/search/routing on the 1.26.x line. Mixing older releases
    // across the two pulls in both the old "sensoris" telemetry artifact and
    // its renamed replacement "telemetry-protobuf-internal" at once, which
    // fails the build with duplicate-class errors -- excluded below as a
    // second layer of protection even when versions are aligned.
    val tomtomInitVersion = "2.4.2"
    val tomtomMapsVersion = "1.26.7"
    val tomtomRoutingVersion = "1.26.7"
    val tomtomSearchVersion = "1.26.7"

    implementation("com.tomtom.sdk:init:$tomtomInitVersion")
    implementation("com.tomtom.sdk.maps:map-display:$tomtomMapsVersion") {
        exclude(group = "com.tomtom.sdk.telemetry", module = "sensoris")
    }
    implementation("com.tomtom.sdk.location:provider-android:$tomtomInitVersion")
    implementation("com.tomtom.sdk.search:search-online:$tomtomSearchVersion") {
        exclude(group = "com.tomtom.sdk.telemetry", module = "sensoris")
    }
    implementation("com.tomtom.sdk.routing:route-planner-online:$tomtomRoutingVersion") {
        exclude(group = "com.tomtom.sdk.telemetry", module = "sensoris")
    }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
