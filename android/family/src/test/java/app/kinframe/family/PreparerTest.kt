package app.kinframe.family

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import app.kinframe.core.api.MediaKind
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.prep.MediaPreparer
import app.kinframe.family.send.prep.PreparationFailedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The preparer itself: photos become a photo-master-v1 WebP, and a file it can't prepare never goes as an original.
 * (Video transcoding needs a real device; the video limits are covered in PrepTest.)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PreparerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val preparer get() = MediaPreparer(RuntimeEnvironment.getApplication())

    private fun photo(w: Int, h: Int, name: String = "p.png"): OutboxItem {
        val f = tmp.newFile(name)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return OutboxItem(name, Uri.fromFile(f).toString(), localPath = f.path, mime = "image/png", name = name, size = f.length())
    }

    private fun junk(name: String, mime: String, kind: String = MediaKind.PHOTO): OutboxItem {
        val f = tmp.newFile(name).apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        return OutboxItem(name, Uri.fromFile(f).toString(), localPath = f.path, mime = mime, name = name, size = f.length(), kind = kind)
    }

    @Test fun aLargePhotoBecomesAPreparedMasterWithinTheLongEdge() = runBlocking {
        val out = preparer.prepare(photo(3000, 2000), tmp.newFolder("out")) {}
        assertEquals(OutboxItem.PREP_PREPARED, out.prepState)
        assertEquals("photo-master-v1", out.prepared)
        assertEquals("image/webp", out.preparedMime)
        assertEquals(2560, out.preparedWidth)
        assertTrue(File(out.preparedPath!!).length() > 0)
    }

    @Test fun anUndecodablePhotoIsAnErrorNotAnOriginalUpload() = runBlocking {
        val e = runCatching { preparer.prepare(junk("broken.jpg", "image/jpeg"), tmp.newFolder("out")) {} }.exceptionOrNull()
        assertTrue("expected PreparationFailedException, got $e", e is PreparationFailedException)
    }

    @Test fun anUnsupportedTypeWithinTheLimitsGoesAsItIs() = runBlocking {
        // A small BMP: no encoder path for its type, but it is already within 2560 px.
        val f = tmp.newFile("small.bmp")
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } // PNG bytes; decodable header
        val item = OutboxItem("b", Uri.fromFile(f).toString(), localPath = f.path, mime = "image/bmp", name = "small.bmp", size = f.length())
        val out = preparer.prepare(item, tmp.newFolder("out")) {}
        assertEquals(OutboxItem.PREP_AS_IS, out.prepState)
        assertEquals(null, out.preparedPath)
    }
}
