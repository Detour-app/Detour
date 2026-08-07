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

/** A file in the app-private directory. */
internal fun appFile(name: String): Path = appFilesDir() / name

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
