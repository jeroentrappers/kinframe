package app.kinframe.frame.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code drawn directly from the ZXing bit matrix (crisp at any size, no bitmap). */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, color: Color = Color(0xFF2E2621)) {
    val matrix = remember(content) {
        runCatching {
            QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        }.getOrNull()
    }
    Canvas(modifier) {
        val m = matrix ?: return@Canvas
        val cell = minOf(size.width / m.width, size.height / m.height)
        val ox = (size.width - cell * m.width) / 2
        val oy = (size.height - cell * m.height) / 2
        for (y in 0 until m.height) {
            var x = 0
            while (x < m.width) {
                if (m.get(x, y)) {
                    var run = 1
                    while (x + run < m.width && m.get(x + run, y)) run++
                    // Slight overlap avoids hairline gaps between modules.
                    drawRect(color, Offset(ox + x * cell, oy + y * cell), Size(run * cell + 0.5f, cell + 0.5f))
                    x += run
                } else x++
            }
        }
    }
}
