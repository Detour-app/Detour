package com.jellemax.detour.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdatePruneTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: java.io.File, name: String) =
        java.io.File(dir, name).apply { writeText("x") }

    @Test fun keepsTheAssetItsPartialAndTheSidecar() {
        val dir = tmp.newFolder("updates")
        file(dir, "detour-2.14.0.apk")
        file(dir, "detour-2.14.0.apk.part")
        file(dir, "detour-2.14.0.apk.part.meta")
        file(dir, "detour-2.13.0.apk")
        file(dir, "detour-2.13.0.apk.part")

        UpdateDownloader.prune(dir, keep = "detour-2.14.0.apk")

        assertTrue(java.io.File(dir, "detour-2.14.0.apk").exists())
        assertTrue(java.io.File(dir, "detour-2.14.0.apk.part").exists())
        assertTrue(java.io.File(dir, "detour-2.14.0.apk.part.meta").exists())
        assertFalse(java.io.File(dir, "detour-2.13.0.apk").exists())
        assertFalse(java.io.File(dir, "detour-2.13.0.apk.part").exists())
    }

    @Test fun aNullKeepClearsEverything() {
        val dir = tmp.newFolder("updates")
        file(dir, "detour-2.14.0.apk")
        file(dir, "detour-2.14.0.apk.part")
        file(dir, "detour-2.14.0.apk.part.meta")

        UpdateDownloader.prune(dir, keep = null)

        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
