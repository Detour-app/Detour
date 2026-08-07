plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // expect/actual classes are still flagged Beta; Prefs is exactly the case
    // the feature exists for, and the warning is noise on every compile.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Device, Apple-silicon simulator, Intel simulator. All three are declared
    // unconditionally so the Gradle model is identical everywhere; the Native
    // compilations simply cannot be *invoked* off macOS, which is fine — Linux
    // CI and local Linux builds only ever touch the android + metadata tasks.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "DetourShared"
            // Static: the Xcode side then embeds one .framework with no
            // dylib-copy phase, and Kotlin/Native's own runtime comes along
            // inside it rather than needing to be shipped separately.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
            implementation("io.ktor:ktor-client-core:2.3.12")
            // ContentEncoding (transparent gzip on responses) ships separately
            // from core.
            implementation("io.ktor:ktor-client-encoding:2.3.12")
            implementation("com.squareup.okio:okio:3.9.0")
            // Only for the auto-theme's local clock hour; java.util.Calendar
            // has no common equivalent.
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:2.3.12")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:2.3.12")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.jellemax.detour.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
