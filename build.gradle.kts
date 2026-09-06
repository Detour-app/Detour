plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    // Applied by :app only when a google-services.json is present (see app/build.gradle.kts).
    id("com.google.gms.google-services") version "4.4.2" apply false
    // 1.23.7 rather than the 2.0 alpha because io.nlopez.compose.rules:detekt
    // 0.4.22 is compiled against detekt-core 1.23.7 (checked in its POM), and a
    // ruleset built against a different detekt major does not load.
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

// Static analysis, applied to :app only. The rules that matter here are the
// Compose ones (KOTLIN_GUIDE.md §14 — state holders over parameter drilling),
// and Compose lives entirely in :app. :shared can be added later, but detekt
// 1.23 needs its KMP source sets pointed at explicitly, which is its own change.
//
// Not in app/build.gradle.kts because the config and baseline paths are
// rootProject-relative and this keeps them next to the plugin version.
project(":app") {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Only overrides live in our config; everything else is detekt's default.
        buildUponDefaultConfig = true
        // Pre-existing violations are recorded, not fixed. Deleting an entry is
        // how a fix gets locked in; adding one by hand defeats the point.
        baseline = rootProject.file("config/detekt/baseline-app.xml")
    }

    dependencies {
        add("detektPlugins", "io.nlopez.compose.rules:detekt:0.4.22")
    }

    // The default `detekt` task runs without type resolution, which is enough
    // for every rule enabled here and avoids a full compile in CI.
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}
