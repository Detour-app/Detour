package com.jellemax.detour.data

import okio.Path
import okio.buffer
import okio.use

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
 * This is what [accountFile]'s counterpart used to be called, back when it
 * was the only one and every store used it by default. The rename is
 * deliberate: an unqualified `appFile` that silently means device-scoped is
 * the exact shape of #73, and a name that reads as "the normal one" is how
 * the next store inherits the bug. Picking a scope is now a decision the
 * compiler makes you make.
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

internal fun Path.writeText(text: String) {
    parent?.let { fileSystem.createDirectories(it) }
    fileSystem.write(this) { writeUtf8(text) }
}

internal fun Path.appendText(text: String) {
    parent?.let { fileSystem.createDirectories(it) }
    fileSystem.appendingSink(this).buffer().use { it.writeUtf8(text) }
}

/** Missing file is not an error, matching java.io.File.delete()'s false. */
internal fun Path.deleteIfExists() {
    if (exists()) fileSystem.delete(this)
}
