package com.jellemax.detour.arch

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parts of docs/guidelines/checklist.md a machine can check, so a review —
 * human or agent — can't wave them through. Each test cites the section it
 * holds; read that section before changing a test or an allowlist.
 *
 * Allowlists are ratchets: they record what was already there when the rule
 * landed. They may shrink, never grow, and an entry that no longer applies
 * fails the build until it is deleted — so a fix gets locked in.
 *
 * Runs in :app because :app's unit tests already run in CI; it reads
 * :shared's sources as text, it doesn't link them.
 */
class ArchitectureTest {

    /** Main source sets only — tests may do what they like. */
    private val files: List<KoFileDeclaration> = run {
        val main = Regex("^(app/src/main|shared/src/\\w+Main)/")
        (Konsist.scopeFromDirectory("app/src").files + Konsist.scopeFromDirectory("shared/src").files)
            .filter { main.containsMatchIn(it.rel) }
    }
    private val commonMain = files.filter { it.rel.startsWith("shared/src/commonMain/") }

    @Test
    fun `no MutableStateFlow is visible outside its owner - section 4`() {
        val offenders = files.flatMap { f ->
            f.properties(includeNested = true)
                .filter { "MutableStateFlow" in it.text.substringBefore("{") }
                .filterNot { it.hasPrivateModifier || it.hasInternalModifier }
                .map { "${f.rel}: ${it.name}" }
        }
        assertTrue(
            "Expose a StateFlow; keep the MutableStateFlow private, or internal for a test (state.md §4):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `commonMain has no Dispatchers or withContext - section 2_1`() {
        val banned = setOf("kotlinx.coroutines.Dispatchers", "kotlinx.coroutines.withContext", "kotlinx.coroutines.*")
        val offenders = commonMain.filter { f -> f.imports.any { it.name in banned } }.map { it.rel }
        assertTrue(
            "commonMain APIs are `suspend`; the caller picks the dispatcher (architecture.md §2.1):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `data and drive do not depend on presentation - section 3`() {
        val offenders = commonMain
            .filter { "/detour/data/" in it.rel || "/detour/drive/" in it.rel }
            .filter { f -> f.imports.any { it.name.startsWith("com.jellemax.detour.presentation") } }
            .map { it.rel }
        assertTrue(
            "presentation/ reads data/ and drive/, never the reverse (architecture.md §3):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * An interface in commonMain is a port, and a port needs more than one
     * implementation (multiplatform.md §5.2). Implementations live partly in
     * Swift, where this test can't see, so the check is a registry: a new
     * interface fails here until someone names its implementations below.
     * Sealed interfaces are sum types, not ports, and are exempt.
     */
    @Test
    fun `every commonMain interface is a registered port - section 5_2`() {
        val ports = mapOf(
            "Prefs" to "SharedPrefsStore, KeystorePrefs, UserDefaultsPrefs, KeychainPrefs",
            "RelaySocket" to "OkHttpRelaySocket (app), UrlSessionRelaySocket (iOS)",
            "BearerSource" to "Auth::bearer (app), AuthBearerSource (iOS)",
        )
        val found = commonMain.flatMap { f ->
            f.interfaces(includeNested = true).filterNot { it.hasSealedModifier }.map { it.name }
        }.toSet()
        assertTrue(
            "Unregistered commonMain interface(s) ${found - ports.keys}: one implementation should be an " +
                "`object` (multiplatform.md §5.2); with two or more, add it to `ports` naming them.",
            (found - ports.keys).isEmpty(),
        )
        assertTrue("Stale `ports` entries, delete them: ${ports.keys - found}", (ports.keys - found).isEmpty())
    }

    /**
     * boundaries.md §8: 1,000 lines per file is the hard limit. Files already
     * over it are pinned at their size when this landed — they may shrink, not
     * grow. Raising a number here is a visible decision in the diff; §8.4 says
     * why splitting just to get under it is sometimes the wrong move.
     */
    @Test
    fun `files stay under 1000 lines - section 8`() {
        val pinned = mapOf(
            "app/src/main/java/com/jellemax/detour/tracking/TripTrackingService.kt" to 1806,
            "app/src/main/java/com/jellemax/detour/ui/MapScreen.kt" to 1431,
            "app/src/main/java/com/jellemax/detour/ui/SettingsScreen.kt" to 1246,
            "shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt" to 1211,
            "app/src/main/java/com/jellemax/detour/car/CarMapRenderer.kt" to 1006,
        )
        assertUnderLimit(1000, pinned, files.associate { it.rel to it.text.count { c -> c == '\n' } })
    }

    /**
     * boundaries.md §8: 100 lines per function. detekt's LongMethod already
     * holds this for :app, so this covers :shared, which detekt doesn't scan.
     */
    @Test
    fun `shared functions stay under 100 lines - section 8`() {
        val pinned = mapOf(
            "shared/src/commonMain/kotlin/com/jellemax/detour/data/SyncClient.kt#sync" to 159,
            "shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt#run" to 152,
            "shared/src/commonMain/kotlin/com/jellemax/detour/data/CirclePresence.kt#tick" to 108,
            "shared/src/commonMain/kotlin/com/jellemax/detour/data/Settings.kt#init" to 105,
            "shared/src/commonMain/kotlin/com/jellemax/detour/drive/ConvoyRelay.kt#attempt" to 105,
        )
        val sizes = files.filter { it.rel.startsWith("shared/") }
            .flatMap { f ->
                f.functions(includeNested = true, includeLocal = true)
                    .map { "${f.rel}#${it.name}" to it.text.lines().size }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, v) -> v.max() }
        assertUnderLimit(100, pinned, sizes)
    }

    private fun assertUnderLimit(limit: Int, pinned: Map<String, Int>, sizes: Map<String, Int>) {
        val errors = sizes.mapNotNull { (key, size) ->
            val ceiling = pinned[key]
            when {
                ceiling == null && size > limit -> "$key: $size lines, limit $limit"
                ceiling != null && size > ceiling -> "$key: grew to $size lines, pinned at $ceiling"
                else -> null
            }
        } + pinned.mapNotNull { (key, ceiling) ->
            val size = sizes[key]
            when {
                size == null || size <= limit -> "$key: now ${size ?: "gone"}, delete its pinned entry"
                size < ceiling -> "$key: shrank to $size, lower its pinned entry from $ceiling"
                else -> null
            }
        }
        assertTrue("boundaries.md §8:\n" + errors.joinToString("\n"), errors.isEmpty())
    }

    private val KoFileDeclaration.rel: String get() = path.removePrefix(root)

    private companion object {
        // Unit tests run with the module directory as the working directory.
        val root = File("..").canonicalPath + "/"
    }
}
