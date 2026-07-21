import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Routing server defaults baked into the APK. Read from local.properties
// (local builds) or environment (CI, via GitHub secrets). All optional.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun routingCfg(propKey: String, envKey: String): String =
    localProps.getProperty(propKey) ?: System.getenv(envKey) ?: ""

android {
    namespace = "com.jellemax.maproulette"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jellemax.maproulette"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "1.30"

        buildConfigField("String", "ROUTING_URL",
            "\"${routingCfg("routing.url", "ROUTING_SERVER_URL")}\"")
        buildConfigField("String", "ROUTING_CF_ID",
            "\"${routingCfg("routing.cfId", "ROUTING_CF_ID")}\"")
        buildConfigField("String", "ROUTING_CF_SECRET",
            "\"${routingCfg("routing.cfSecret", "ROUTING_CF_SECRET")}\"")
        buildConfigField("String", "SYNC_URL",
            "\"${routingCfg("sync.url", "SYNC_SERVER_URL")}\"")
        buildConfigField("String", "GEOCODER_URL",
            "\"${routingCfg("geocoder.url", "GEOCODER_URL")}\"")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    wearApp(project(":wear"))
}
