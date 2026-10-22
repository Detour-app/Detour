package com.jellemax.detour.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.jellemax.detour.data.convoyInviteLink
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodeTest {

    @Test
    fun `the matrix decodes back to the invite link`() {
        val link = convoyInviteLink("https://detour.example.org/", "Zq3_kX-9aB7cD1eF2gH3iJ4kL5mN6oP")
        assertEquals(link, decode(qrMatrix(link)))
    }

    @Test
    fun `a non-ASCII server address survives the round trip`() {
        val link = convoyInviteLink("https://rïders.example", "abc")
        assertEquals(link, decode(qrMatrix(link)))
    }

    /** Renders the matrix the way QrCode draws it, quiet zone included, and reads it with zxing. */
    private fun decode(matrix: BitMatrix): String {
        val scale = 4
        val quiet = 4
        val side = (matrix.width + 2 * quiet) * scale
        val pixels = IntArray(side * side) { i ->
            val x = i % side / scale - quiet
            val y = i / side / scale - quiet
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }
}
