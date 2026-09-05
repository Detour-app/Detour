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
            // The auto-theme's local clock hour and the nav ETA's zone;
            // java.util.Calendar has no common equivalent. `api`, not
            // `implementation`: TimeZone is a parameter type on navStateFrom,
            // and Kotlin must resolve every parameter type of a function to
            // type-check a call to it — so :app fails to compile the call
            // without this on its compile classpath, even though it names
            // TimeZone nowhere and leaves `zone` defaulted.
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:2.3.12")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:2.3.12")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // okio's own in-memory FileSystem, so the migration in
            // AccountFiles is testable without touching a real disk. Test-only,
            // and pinned to the version commonMain already uses for okio
            // itself. This is what finally makes Platform.kt's "a fake in
            // tests" true — nothing had ever supplied one.
            implementation("com.squareup.okio:okio-fakefilesystem:3.9.0")
        }
    }
}

/**
 * Builds the framework for whatever Xcode is currently targeting and drops it
 * somewhere with a stable name, so the Xcode project can hardcode one search
 * path instead of one per architecture/configuration.
 *
 * Xcode passes CONFIGURATION and SDK_NAME through as Gradle properties (see
 * iosApp/project.yml); the defaults are what a plain `./gradlew packForXcode`
 * from the command line should do, which is the simulator debug build.
 */
tasks.register<Sync>("packForXcode") {
    val configuration = (findProperty("xcode.configuration") as? String) ?: "Debug"
    val sdk = (findProperty("xcode.sdk") as? String) ?: "iphonesimulator"
    val targetName = when {
        sdk.startsWith("iphoneos") -> "iosArm64"
        // Simulator slice has to match the *host*, not the phone: an Apple
        // silicon Mac runs an arm64 simulator, an Intel one x86_64.
        System.getProperty("os.arch") == "aarch64" -> "iosSimulatorArm64"
        else -> "iosX64"
    }
    // Xcode spells these "Debug"/"Release"; Kotlin wants the enum. Anything
    // else (a custom Xcode configuration) links release, which is the safer
    // guess for a build that isn't plainly a debug one.
    val buildType = if (configuration.equals("debug", ignoreCase = true)) {
        org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG
    } else {
        org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE
    }
    val framework = kotlin.targets.getByName<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>(targetName)
        .binaries.getFramework(buildType)

    dependsOn(framework.linkTaskName)
    from(framework.outputDirectory)
    into(layout.buildDirectory.dir("xcode-frameworks"))
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
