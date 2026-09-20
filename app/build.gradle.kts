plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ipixelclock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ipixelclock"
        // Android 5.0. The iPixel driver carries a legacy startLeScan() path
        // for exactly this, and nothing here uses java.time, so no core
        // library desugaring is needed.
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Deliberately minimal: the UI is self-drawn Views plus a WebView on the
    // app's own server, and the HTTP/WebSocket server is hand-rolled, so
    // there is no framework to pull in.
    implementation("androidx.core:core-ktx:1.13.1")
}
