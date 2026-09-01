import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Navigation 3 persists the back stack through kotlinx-serialization, so the
    // Destination keys need the serialization compiler plugin. The runtime
    // library was already a dependency below; only the plugin is new.
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Routing server defaults baked into the APK. Read from local.properties
// (local builds) or environment (CI, via GitHub secrets). All optional.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun routingCfg(propKey: String, envKey: String): String =
    localProps.getProperty(propKey) ?: System.getenv(envKey) ?: ""

// The four services can sit behind one hostname, path-routed by the tunnel:
// /route to GraphHopper, /api to Photon, /live to the convoy relay, anything
// else to the sync server. Those paths are exactly what each client already
// appends to its base URL, so a single `server.url` is enough to reach all
// four. The per-service keys below still win wherever they're set, so a
// split deployment (a hostname each, which is what this started as) keeps
// working unchanged.
val serverUrl = routingCfg("server.url", "SERVER_URL").trimEnd('/')

fun serviceUrl(propKey: String, envKey: String): String =
    routingCfg(propKey, envKey).ifBlank { serverUrl }

// The sync + social API serves everything under /api. Deliberately *not*
// defaulted to `serverUrl`: the path routing above already hands /api to
// Photon, so this service needs a hostname of its own (or an ingress rule ahead
// of that one), and quietly pointing it at the geocoder would surface as
// unparseable JSON rather than as "not configured".
val apiUrl = routingCfg("api.url", "API_URL").trimEnd('/')

// The realm that issues rider tokens, e.g.
// https://idp.example.com/realms/detour. Blank means signing in is impossible
// and every social feature behaves as it does when signed out — which is what a
// CI build with no secrets wants.
val idpIssuer = routingCfg("idp.issuer", "IDP_ISSUER").trimEnd('/')

// The relay is the one service that can't just take the base as-is: it's a
// WebSocket. It is an ordinary endpoint of the API now rather than a listener on
// its own port, so it derives from `api.url` and sits under the same /api prefix
// and the same bearer auth as every other call — not from `server.url`, which
// path-routes /api to the geocoder.
fun liveUrl(): String {
    val explicit = routingCfg("live.url", "LIVE_SERVER_URL")
    if (explicit.isNotBlank()) return explicit
    return when {
        apiUrl.startsWith("https://") -> "wss://" + apiUrl.removePrefix("https://") + "/api/live"
        apiUrl.startsWith("http://") -> "ws://" + apiUrl.removePrefix("http://") + "/api/live"
        else -> ""
    }
}

android {
    // The namespace stays on the original name: it's the Kotlin package and the
    // R class, invisible outside the build. Only applicationId is the identity
    // Play and the device see, and Play fixes it permanently at first upload.
    namespace = "com.jellemax.detour"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.maxke24.detour"
        minSdk = 26
        targetSdk = 36
        // Play rejects an upload whose code isn't higher than every previous
        // one, and the phone and watch artifacts share an applicationId, so
        // they also need codes distinct from each other. CI stamps both from
        // the run number (see .github/workflows/build.yml); a local build
        // keeps the literal.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 82
        versionName = "1.90.0"

        buildConfigField("String", "ROUTING_URL",
            "\"${serviceUrl("routing.url", "ROUTING_SERVER_URL")}\"")
        buildConfigField("String", "ROUTING_CF_ID",
            "\"${routingCfg("routing.cfId", "ROUTING_CF_ID")}\"")
        buildConfigField("String", "ROUTING_CF_SECRET",
            "\"${routingCfg("routing.cfSecret", "ROUTING_CF_SECRET")}\"")
        buildConfigField("String", "API_URL", "\"$apiUrl\"")
        buildConfigField("String", "IDP_ISSUER", "\"$idpIssuer\"")
        buildConfigField("String", "GEOCODER_URL",
            "\"${serviceUrl("geocoder.url", "GEOCODER_URL")}\"")
        // Convoy live-location/PTT relay: a WebSocket surface of its own, so it
        // needs its own scheme and path rather than the plain base URL. Nothing
        // serves it at the moment — see Features.liveRelay in shared/.
        buildConfigField("String", "LIVE_URL", "\"${liveUrl()}\"")
        // The repository whose releases this build updates itself from, passed
        // by CI as github.repository so a fork's build points at the fork.
        // Blank everywhere else, which makes the whole feature inert — a local
        // build is signed with a different key and could never install a CI
        // APK anyway.
        buildConfigField("String", "UPDATE_REPO",
            "\"${System.getenv("UPDATE_REPO") ?: ""}\"")
    }

    // Release signing reads from the environment rather than local.properties:
    // the keystore never touches disk in this repo or a contributor's checkout,
    // only CI's ephemeral runner (decoded from a GitHub secret) or a maintainer's
    // own shell. Signing is only configured when RELEASE_KEYSTORE is set, so an
    // unsigned/local release build (or plain assembleDebug) is unaffected.
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
        debug {
            // Distinct package so a debug build installs alongside the
            // release-signed app instead of forcing an uninstall (which would
            // take the trip history with it).
            applicationIdSuffix = ".debug"
        }
        release {
            // R8 shrinks and obfuscates; MapLibre and Play Services ship their
            // own consumer proguard rules so this is expected to be near
            // friction-free. mapping.txt from this build is published alongside
            // the release APK so stack traces from issues stay de-obfuscatable.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // The APK published to GitHub Releases, and the only build allowed to
        // update itself. Identical to release in every other way — same R8
        // config, same signing, same applicationId — so the artifact a rider
        // installs is the release build plus one permission.
        //
        // A separate build type because the permission has to be absent from
        // the Play bundle, and a manifest source set keys off a variant. Play
        // is built with bundleRelease, which stays on `release` and never sees
        // REQUEST_INSTALL_PACKAGES. A build type rather than a flavor for the
        // reason the automotive block below gives: a flavor dimension renames
        // every existing variant task.
        create("githubRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
        // A test harness for the car/ screens, never shipped. On a phone the
        // CarAppService is driven by the Android Auto host on the head unit, so
        // none of car/ can run without one — which is why #37's defects in
        // CarMapRenderer have gone unverified through two pull requests. On
        // Android Automotive OS the same CarAppService is hosted on-device by
        // CarAppActivity, from androidx.car.app:app-automotive, so an AAOS
        // emulator runs those screens with no head unit at all.
        //
        // A build type rather than a product flavor deliberately: a flavor
        // dimension renames every existing variant task (assembleDebug becomes
        // assemblePhoneDebug), which would break .github/workflows/build.yml and
        // a dozen references across .claude/skills. A build type only adds
        // assembleAutomotive and leaves the existing names alone.
        create("automotive") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".automotive"
            // Nothing depends on this build type's output, but AGP needs a
            // fallback for any dependency that publishes debug/release only.
            matchingFallbacks += listOf("debug")
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
    // Roulette/routing/trip logic, shared verbatim with the iOS app in iosApp/.
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Custom Tabs, for the sign-in leg. A native app must not put the identity
    // provider's login form in a WebView (RFC 8252): a tab keeps the address bar
    // and the browser's own session, so the user can see who they are typing a
    // password into and does not type it again on the next device.
    implementation("androidx.browser:browser:1.8.0")
    // Navigation 3: the app owns the back stack as a snapshot-state list and
    // NavDisplay observes it, so a transition knows whether the list grew or
    // shrank instead of inferring it from a hand-maintained depth. See
    // app/.../nav/Destination.kt and #68.
    implementation("androidx.navigation3:navigation3-runtime:1.1.7")
    implementation("androidx.navigation3:navigation3-ui:1.1.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    // Android Auto: projects a car-screen "Spin" flow onto the head unit.
    implementation("androidx.car.app:app:1.7.0")
    // Only for the `automotive` build type: app-automotive adds CarAppActivity,
    // the on-device host that renders the same CarAppService's templates on
    // Android Automotive OS. Same version as :app above, so this pulls no new
    // transitive graph — it is the host half of a library the app already uses.
    "automotiveImplementation"("androidx.car.app:app-automotive:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    // WebSocket client for the convoy live-location/PTT relay - Android has
    // no built-in WS client and hand-rolling RFC 6455 framing isn't worth it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // :shared's CircleEvents.placeEventFromRelayFrame takes a kotlinx
    // JsonObject, but shared/build.gradle.kts declares kotlinx-serialization
    // as `implementation`, not `api` - Gradle doesn't leak that onto a
    // consumer's compile classpath, so it has to be declared here too,
    // pinned to the same version shared uses to resolve to one identical
    // class rather than risk two.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    // Plain JUnit4, the default AGP test runner wires up for testDebugUnitTest
    // with no extra plugin - app/ had no unit tests before PlaceNotifications'
    // catch-up planning logic, which is pure Kotlin and worth covering.
    testImplementation("junit:junit:4.13.2")
    // No wearApp(project(":wear")) here on purpose. Embedding the watch APK
    // inside the phone one only ever auto-installed on Wear OS 1.x, and this
    // watch app is minSdk 30 (Wear OS 3) — so the embedded copy was 40 MB of
    // payload that never ran, and Play refuses a bundle that carries one. The
    // watch app ships as its own artifact instead: same applicationId, its own
    // versionCode, uploaded alongside the phone bundle in the same Play
    // release and attached to the GitHub release for sideloading.
}
