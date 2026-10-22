package com.jellemax.detour.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Light modules the spec asks for around the code (ISO/IEC 18004 §5.3.8). Scanners find the finder
 * patterns by this margin, so it is drawn even on a light dialog.
 */
private const val QUIET_ZONE_MODULES = 4

/**
 * The QR modules for [text], one matrix cell per module and no quiet zone ([QrCode] draws that).
 * Lives in app/, not commonMain: zxing is a JVM library, and iOS has CoreImage's own generator.
 */
internal fun qrMatrix(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    ),
)

/**
 * [text] as a QR code (#762), drawn straight from the matrix with no bitmap. Always black on white,
 * whatever the theme: scanners expect dark modules on a light ground, and many read an inverted
 * code badly or not at all.
 */
@Composable
internal fun QrCode(text: String, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val matrix = remember(text) { qrMatrix(text) }
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        val modules = matrix.width + 2 * QUIET_ZONE_MODULES
        val cell = this.size.minDimension / modules
        drawRect(Color.White)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (!matrix[x, y]) continue
                drawRect(
                    Color.Black,
                    topLeft = Offset((x + QUIET_ZONE_MODULES) * cell, (y + QUIET_ZONE_MODULES) * cell),
                    // A hair over one cell, so anti-aliasing leaves no seams between adjacent modules.
                    size = Size(cell + 0.5f, cell + 0.5f),
                )
            }
        }
    }
}
