plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

android {
    namespace = "com.jellemax.mocklocation"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jellemax.mocklocation"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Debug-signed only. This never ships anywhere.
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
