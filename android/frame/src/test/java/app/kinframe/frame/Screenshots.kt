package app.kinframe.frame

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.kinframe.core.design.KinframeTheme
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Renders a composable in a Robolectric activity (native graphics) and writes it to `frame/build/screenshots/<name>.png`
 * for design review. Records, not golden comparisons. The screen size comes from the test's `@Config(qualifiers)`.
 */
object Screenshots {
    fun shot(name: String, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val activity = controller.setup().get()
        activity.setContent { KinframeTheme { Box(Modifier.fillMaxSize()) { content() } } }
        shadowOf(Looper.getMainLooper()).idle()
        // Let entry animations settle.
        repeat(20) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        val view = activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val out = File("build/screenshots/$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.length() > 0)
    }
}

/** A warm garden photo stand-in. */
@Composable
fun FakePhoto(seed: Int = 0) {
    val palettes = listOf(
        listOf(Color(0xFF9CCBF0), Color(0xFFE9F4FB), Color(0xFF8DB580), Color(0xFF55804A)),
        listOf(Color(0xFF2B2A44), Color(0xFF6E4A62), Color(0xFFE39A6B), Color(0xFF3A2A2A)),
        listOf(Color(0xFFF3D9B1), Color(0xFFE7B07A), Color(0xFFB86B4B), Color(0xFF6B3F2F)),
        listOf(Color(0xFFBFD8D2), Color(0xFF7FA99B), Color(0xFF3F6E63), Color(0xFF1F3B36)),
    )
    val p = palettes[seed % palettes.size]
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(0f to p[0], 0.5f to p[1], 0.62f to p[2], 1f to p[3]))
        drawCircle(Color.White.copy(alpha = 0.5f), size.minDimension * 0.12f, Offset(size.width * 0.72f, size.height * 0.25f))
        drawRect(p[3].copy(alpha = 0.5f), Offset(0f, size.height * 0.78f), Size(size.width, size.height * 0.22f))
    }
}

/** The frame's paper background (setup and settings screens draw on it). */
@Composable
fun Paper() {
    Box(Modifier.fillMaxSize().background(app.kinframe.core.design.Kin.colors.paper))
}
