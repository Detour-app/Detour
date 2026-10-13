package com.jellemax.detour.data

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

/**
 * The handful of whole-file operations the stores actually perform, over okio.
 *
 * java.io.File carried these as methods; okio splits them across FileSystem,
 * so they are gathered here rather than at ~40 call sites. Nothing streams:
 * every store here is read and written whole already, and the largest of them
 * (traces.jsonl) is a few MB after a year of riding.
 */

/**
 * A file belonging to the device rather than to any rider — one copy, shared
 * by everyone who signs in here.
 *
 * This used to be `appFile`. The rename is deliberate: an unqualified
 * `appFile` that silently means device-scoped is the exact shape of #73, and
 * a name that reads as "the normal one" is how the next store inherits the
 * bug. What the rename bought is the 28 existing call sites: every one of
 * them had to be read and assigned a scope by hand, because the old name no
 * longer compiled.
 *
 * It buys nothing going forward. A new store can call this and silently share
 * one file across every rider on the device with no diagnostic of any kind —
 * the name is a signpost, not a check. `detour-trip-data`'s precondition
 * script is what actually holds the line: it asserts both directions across
 * AccountFiles.SCOPED_NAMES on every run.
 */
internal fun deviceFile(name: String): Path = appFilesDir() / name

/** The layout, as a pure function of the root — [accountDir]'s testable half.
 *  Ambient `appFilesDir()` needs a platform Context, so the seam is what makes
 *  the component order assertable at all. */
internal fun accountDirIn(root: Path, bucket: String): Path =
    root / AccountScope.ACCOUNTS_DIR / bucket

/** The directory holding the current rider's files. */
internal fun accountDir(): Path {
    // A path resolved before the migration has run points at an empty bucket
    // while the rider's real files are still at the root — and a *write*
    // through it makes that permanent, because migrate skips a name the bucket
    // already holds. Failing loudly at the offending call site is the whole
    // reason this design has no read-path fallback. Same shape as
    // Settings.prefs, which errors rather than returning an empty bag.
    check(AccountFiles.migrated) { "AccountFiles.migrate has not run; call Settings.init() first" }
    return accountDirIn(appFilesDir(), AccountScope.current())
}

/**
 * A file belonging to whoever is signed in, or to the anonymous bucket when
 * nobody is. Resolved per call rather than cached, because the answer changes
 * the moment [Auth.store] or [Auth.clear] moves the session.
 */
internal fun accountFile(name: String): Path = accountDir() / name

internal fun Path.exists(): Boolean = fileSystem.exists(this)

internal fun Path.readText(): String = fileSystem.read(this) { readUtf8() }

/** Blank-line-free, matching java.io.File.readLines on these stores. */
internal fun Path.readLines(): List<String> =
    if (!exists()) emptyList() else readText().lineSequence().toList()

internal fun Path.writeText(text: String) = fileSystem.writeAtomically(this, text)

/**
 * Replace [target] with [text] so a reader only ever sees the old file or the
 * new one, never a prefix of the new one (#694).
 *
 * A plain `write` truncates first, so a process kill mid-write left a cut-off
 * trips.json that `load()` read as no trips at all. Here the text goes to a
 * sibling temp file, is flushed (an fsync on the JVM), and only then renamed
 * over the target. The temp name is unique per call, so two writers racing on
 * one store each rename a whole file and the last one wins; a kill between
 * write and rename leaves a stray `.tmp` behind, never a broken target.
 */
internal fun FileSystem.writeAtomically(target: Path, text: String) {
    val dir = target.parent
    dir?.let { createDirectories(it) }
    val tmpName = "${target.name}.${Random.nextLong().toULong()}.tmp"
    val tmp = dir?.div(tmpName) ?: tmpName.toPath()
    try {
        openReadWrite(tmp, mustCreate = true).use { handle ->
            handle.sink().buffer().use { it.writeUtf8(text) }
            handle.flush()
        }
        atomicMove(tmp, target)
    } catch (e: IOException) {
        delete(tmp, mustExist = false)
        throw e
    }
}

internal fun Path.appendText(text: String) {
    parent?.let { fileSystem.createDirectories(it) }
    fileSystem.appendingSink(this).buffer().use { it.writeUtf8(text) }
}

/** Missing file is not an error, matching java.io.File.delete()'s false. */
internal fun Path.deleteIfExists() {
    if (exists()) fileSystem.delete(this)
}
