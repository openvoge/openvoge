plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vogy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vogy.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // MapLibre's native engine is ~15 MB per ABI. Published APKs (CI passes -PphoneAbisOnly)
        // ship only the ABIs phones use; local builds keep x86/x86_64 so emulators still work.
        if (providers.gradleProperty("phoneAbisOnly").isPresent) {
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    packaging {
        jniLibs {
            // Store native libs compressed: roughly 3x smaller download for the map engine
            useLegacyPackaging = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.0")
    // 13.0.x is the newest line built against kotlin-stdlib 2.0, which our Kotlin 1.9 compiler can still read.
    // Moving past it requires upgrading the Kotlin Gradle plugin to 2.2+.
    implementation("org.maplibre.gl:android-sdk:13.0.2")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests: android.jar only ships stubs that throw
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
