plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jellemax.detour.wear"
    compileSdk = 35

    defaultConfig {
        // MessageClient.sendMessage() routes by matching applicationId across
        // the phone/watch node pair — must equal the phone app's id or the
        // system silently drops every message ("Failed to deliver to AppKey").
        applicationId = "com.jellemax.detour"
        minSdk = 30
        targetSdk = 35
        versionCode = 21
        versionName = "1.14"
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.4")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
