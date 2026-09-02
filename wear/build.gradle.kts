plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.jellemax.detour.wear"
    compileSdk = 36

    defaultConfig {
        // MessageClient.sendMessage() routes by matching applicationId across
        // the phone/watch node pair — must equal the phone app's id or the
        // system silently drops every message ("Failed to deliver to AppKey").
        applicationId = "io.github.maxke24.detour"
        minSdk = 30
        targetSdk = 35
        // Shares an applicationId with the phone app, so Play needs this to
        // differ from the phone artifact's code in every upload. CI stamps it
        // (see .github/workflows/build.yml); a local build keeps the literal.
        versionCode = System.getenv("WEAR_VERSION_CODE")?.toInt() ?: 21
        versionName = "1.15"
    }

    // Same upload key as the phone app: Play treats both artifacts as one
    // application and rejects a release where they're signed differently.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.wear.compose:compose-material:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
}
