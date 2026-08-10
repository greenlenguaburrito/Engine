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

        // Without this, the APK bundles native (.so) libraries for every ABI
        // (arm64-v8a, armeabi-v7a, x86, x86_64), ballooning it past 250MB for a
        // debug build. Virtually every real Android phone since ~2017 is
        // arm64-v8a, so restrict to that -- if you're testing on an x86_64
        // emulator, add it back here.
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // Truck routing and destination search call TomTom's public REST APIs
    // directly (see routing/RouteRepository.kt and search/SearchRepository.kt)
    // rather than the native Routing/Search SDK modules. That REST schema is
    // TomTom's long-stable, well-documented v1/v2 API -- the same one the
    // original HTML prototype this app is based on used successfully -- while
    // the native Kotlin Routing/Search SDK's typed model (Vehicle, Instruction
    // sealed hierarchy, Quantity-based Distance/Duration, etc.) turned out to
    // be deep enough that guessing its exact surface caused repeated CI
    // failures. This keeps the dependency graph small too, avoiding the
    // duplicate-class conflicts that came from combining multiple TomTom SDK
    // modules pinned to different release trains.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- TomTom SDK (map display only) -----------------------------------
    // Versions confirmed directly against repositories.tomtom.com's
    // maven-metadata.xml. These are the concrete "-android-complete"
    // artifacts (the free/public flavor), not the older ambiguous
    // "map-display"/"init" artifact names, which avoids needing Gradle
    // flavor-attribute resolution for these specific dependencies.
    val tomtomSdkVersion = "2.4.2"

    implementation("com.tomtom.sdk:init-android-complete:$tomtomSdkVersion")
    implementation("com.tomtom.sdk.common:configuration:$tomtomSdkVersion")
    implementation("com.tomtom.sdk.maps:map-display-standard-android-complete:$tomtomSdkVersion")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
