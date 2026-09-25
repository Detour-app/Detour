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

// Static analysis for both modules. The Compose rules (KOTLIN_GUIDE.md §14 —
// state holders over parameter drilling) only find anything in :app; the rest
// applies to both. detekt 1.23 doesn't discover KMP source sets on its own, so
// :shared's are listed explicitly — a new source set has to be added here.
//
// Not in the module build files because the config and baseline paths are
// rootProject-relative and this keeps them next to the plugin version.
listOf(":app", ":shared").forEach { path -> project(path) {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // Only overrides live in our config; everything else is detekt's default.
        buildUponDefaultConfig = true
        // Pre-existing violations are recorded, not fixed. Deleting an entry is
        // how a fix gets locked in; adding one by hand defeats the point.
        baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
        if (path == ":shared") {
            source.setFrom(listOf("commonMain", "androidMain", "iosMain").map { "src/$it/kotlin" })
        }
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
} }
