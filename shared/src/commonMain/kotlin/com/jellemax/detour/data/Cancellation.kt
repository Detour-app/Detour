package com.jellemax.detour.data

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that lets cancellation through.
 *
 * Plain `runCatching` around a suspend call also catches the
 * [CancellationException] its coroutine is cancelled with, so a cancelled
 * `while (true)` poll loop carries on until some later suspension point throws
 * again (#509). Everything else lands in the [Result] as with `runCatching`.
 *
 * `inline` rather than `suspend`: the block is inlined into the caller, so it
 * may suspend wherever the caller may.
 */
inline fun <T> catchingCancellable(block: () -> T): Result<T> =
    runCatching(block).onFailure { if (it is CancellationException) throw it }
