package com.jellemax.detour.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdatePartFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun acceptsAConventionalAssetName() {
        assertTrue(UpdatePartFiles.isSafeAssetName("detour-2.14.0.apk"))
        assertTrue(UpdatePartFiles.isSafeAssetName("detour_2.14.0-arm64.apk"))
    }

    @Test fun rejectsAnythingThatCouldLeaveTheDirectory() {
        assertFalse(UpdatePartFiles.isSafeAssetName("../detour.apk"))
        assertFalse(UpdatePartFiles.isSafeAssetName("a/b.apk"))
        assertFalse(UpdatePartFiles.isSafeAssetName("a\\b.apk"))
        assertFalse(UpdatePartFiles.isSafeAssetName(".."))
        assertFalse(UpdatePartFiles.isSafeAssetName("."))
        assertFalse(UpdatePartFiles.isSafeAssetName(""))
    }

    @Test fun rejectsAnOverlongName() {
        assertFalse(UpdatePartFiles.isSafeAssetName("a".repeat(129)))
    }

    @Test fun resolvesTheThreePathsInsideTheDirectory() {
        val dir = tmp.newFolder("updates")
        val paths = UpdatePartFiles.resolve(dir, "detour-2.14.0.apk")!!
        assertEquals(dir, paths.target.parentFile)
        assertEquals("detour-2.14.0.apk", paths.target.name)
        assertEquals("detour-2.14.0.apk.part", paths.part.name)
        assertEquals("detour-2.14.0.apk.part.meta", paths.meta.name)
    }

    @Test fun resolveRefusesAnUnsafeName() {
        val dir = tmp.newFolder("updates")
        assertNull(UpdatePartFiles.resolve(dir, "../escape.apk"))
    }
}
