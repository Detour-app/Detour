package com.jellemax.detour.data

import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

/**
 * Covers [accountDirIn] — the pure half of [accountDir]'s layout.
 *
 * `accountDir()` itself calls the ambient `appFilesDir()`, which needs a
 * platform Context and so cannot be driven from commonTest. This is the seam
 * that was split out for exactly that reason: it pins the component order
 * (`root / accounts / bucket`, not some other arrangement) with nothing
 * platform-specific left to fake.
 *
 * What this does NOT cover: which store resolves to [accountFile] versus
 * [deviceFile]. That is decided at 28 call sites against `expect`-backed
 * ambient functions with no injectable `FileSystem`, so it is not testable
 * here — only verifiable by grepping the call sites and reading them.
 */
class FilesLayoutTest {

    @Test
    fun accountDirIsRootThenAccountsThenBucket() {
        assertEquals(
            "/data/files/accounts/_local".toPath(),
            accountDirIn("/data/files".toPath(), "_local"),
        )
    }

    @Test
    fun accountDirWorksTheSameForAHashedBucketName() {
        assertEquals(
            "/data/files/accounts/a3f1c8e29b4d7061".toPath(),
            accountDirIn("/data/files".toPath(), "a3f1c8e29b4d7061"),
        )
    }
}
