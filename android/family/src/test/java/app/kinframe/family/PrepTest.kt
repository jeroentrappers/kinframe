package app.kinframe.family

import app.kinframe.core.api.KinJson
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.PreparedProfile
import app.kinframe.core.api.SendItemCreate
import app.kinframe.family.send.OutboxItem
import app.kinframe.family.send.prep.ArrayReader
import app.kinframe.family.send.prep.MotionPhotos
import app.kinframe.family.send.prep.PrepPolicy
import app.kinframe.family.send.prep.PrepResult
import app.kinframe.family.send.prep.PxSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Client-side preparation decisions (README §5.3 "Prepared uploads") and Motion Photo parsing. */
class PrepTest {
    @Test fun photoTargetCapsTheLongEdgeAndNeverUpscales() {
        assertEquals(PxSize(2560, 1920), PrepPolicy.photoTarget(PxSize(4032, 3024)))
        assertEquals(PxSize(1440, 2560), PrepPolicy.photoTarget(PxSize(3024, 5376)))
        assertEquals(PxSize(800, 600), PrepPolicy.photoTarget(PxSize(800, 600)))
        assertEquals(4, PrepPolicy.inSampleSize(PxSize(12000, 9000), PrepPolicy.photoTarget(PxSize(12000, 9000))))
        assertEquals(1, PrepPolicy.inSampleSize(PxSize(4032, 3024), PxSize(2560, 1920)))
    }

    @Test fun videoTargetFitsWithin1080pInEitherOrientation() {
        assertEquals(PxSize(1920, 1080), PrepPolicy.videoTarget(PxSize(3840, 2160)))
        assertEquals(PxSize(1080, 1920), PrepPolicy.videoTarget(PxSize(2160, 3840)))
        assertEquals(PxSize(1280, 720), PrepPolicy.videoTarget(PxSize(1280, 720)))
        // Odd sizes become even (H.264 4:2:0).
        assertEquals(PxSize(1440, 1080), PrepPolicy.videoTarget(PxSize(1441, 1081)))
        // Under the frame rendition budget (w·h·4, max 8 Mbps) so frames get the master as is.
        assertEquals(3_225_600, PrepPolicy.videoBitrate(PxSize(1280, 720)))
        assertEquals(7_257_600, PrepPolicy.videoBitrate(PxSize(1920, 1080)))
        assertEquals(PrepPolicy.VIDEO_MAX_BITRATE, PrepPolicy.videoBitrate(PxSize(2560, 1440)))
    }

    @Test fun whatCanBePrepared() {
        assertTrue(PrepPolicy.canPreparePhoto("image/jpeg", "a.jpg", 26))
        assertTrue(PrepPolicy.canPreparePhoto("application/octet-stream", "a.PNG", 26))
        assertFalse(PrepPolicy.canPreparePhoto("image/heic", "a.heic", 27)) // no ImageDecoder: upload as is
        assertTrue(PrepPolicy.canPreparePhoto("image/heic", "a.heic", 28))
        assertFalse(PrepPolicy.canPreparePhoto("image/avif", "a.avif", 30))
        assertFalse(PrepPolicy.canPreparePhoto("image/gif", "a.gif", 34))
        assertFalse(PrepPolicy.canPrepareVideo(25))
    }

    @Test fun clipAppliesTrimAndTheMotionLimit() {
        assertEquals(1000L to 4000L, PrepPolicy.clip(10_000, 1000, 4000))
        assertEquals(0L to null, PrepPolicy.clip(null, null, null))
        assertEquals(0L to 10_000L, PrepPolicy.clip(15_000, null, null, PrepPolicy.MOTION_MAX_MS))
        assertEquals(0L to 3000L, PrepPolicy.clip(3000, null, null, PrepPolicy.MOTION_MAX_MS))
    }

    @Test fun captureTimes() {
        assertEquals("2023-05-01T10:00:00Z", PrepPolicy.exifCaptureTime("2023:05:01 12:00:00", "+02:00"))
        // No offset: the wall-clock time as if UTC, like the server does with EXIF.
        assertEquals("2021-06-15T10:30:00Z", PrepPolicy.exifCaptureTime("2021:06:15 10:30:00", null))
        assertNull(PrepPolicy.exifCaptureTime("0000:00:00 00:00:00", null))
        assertNull(PrepPolicy.exifCaptureTime("garbage", null))
        assertEquals(1_682_942_400_000L, PrepPolicy.videoDate("20230501T120000.000Z"))
        assertNull(PrepPolicy.videoDate("19040101T000000.000Z"))
        assertEquals("2023-05-01T12:00:00Z", PrepPolicy.epochCaptureTime(1_682_942_400_123L))
    }

    @Test fun preparedItemsSendTheirFactsAndNoTrim() {
        val item = OutboxItem(
            "i1", "content://v", mime = "video/quicktime", name = "IMG_1.MOV", size = 50_000_000, kind = MediaKind.VIDEO,
            trimStartMs = 1000, trimEndMs = 5000, includeAudio = false, uploadId = "up1", capturedAt = "2023-05-01T10:00:00Z",
        )
        val prepared = item.withPrep(PrepResult.Prepared("/x/prep-i1.mp4", 2_000_000, PreparedProfile.VIDEO, "video/mp4", 1920, 1080, 4000, trimmed = true))
        assertEquals(2_000_000L, prepared.uploadSize)
        assertEquals("IMG_1.mp4", prepared.uploadName)
        assertTrue(prepared.dedupable)
        assertEquals(
            """{"upload_id":"up1","include_audio":false,"captured_at":"2023-05-01T10:00:00Z","width":1920,"height":1080,"duration_ms":4000}""",
            KinJson.encodeToString(SendItemCreate.serializer(), prepared.toSendItem()),
        )
        // Falling back to the original keeps the server-side trim.
        val original = item.withPrep(PrepResult.Original("Transformer"))
        assertEquals(1000L, original.toSendItem().trim?.startMs)
        assertNull(original.toSendItem().width)
    }

    @Test fun liveAndStillOnlyMotionPhotos() {
        val clip = OutboxItem("i1-motion", "file:///m.mp4", mime = "video/mp4", name = "m.mp4", size = 9, kind = MediaKind.VIDEO, uploadId = "up2")
        val live = OutboxItem("i1", "content://p", mime = "image/jpeg", name = "PXL.jpg", size = 10, uploadId = "up1", motionPhoto = true, motion = clip)
        assertEquals("up2", live.toSendItem().liveVideoUploadId)
        assertNull(live.toSendItem().live)
        val still = live.copy(live = false, motion = null)
        assertEquals(false, still.toSendItem().live)
        assertNull(still.toSendItem().liveVideoUploadId)
    }

    // ---------------------------------------------------------------- Motion Photos

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val n = 8 + payload.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private val mp4: ByteArray = box("ftyp", "isom\u0000\u0000\u0002\u0000isommp42".toByteArray(Charsets.ISO_8859_1)) +
        box("moov", ByteArray(20) { 1 }) + box("mdat", ByteArray(64) { 2 })

    private fun motionPhoto(xmp: String, trailer: ByteArray = ByteArray(0)): ByteArray {
        val head = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0, 0) +
            "http://ns.adobe.com/xap/1.0/\u0000$xmp".toByteArray(Charsets.ISO_8859_1)
        val body = ByteArray(500) { 0x55 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return head + body + mp4 + trailer
    }

    private fun googleXmp(length: Int) = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description
        GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="1500000">
        <Container:Directory><rdf:Seq>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$length" Item:Padding="0"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    @Test fun findsTheClipOfAGoogleMotionPhoto() {
        val file = motionPhoto(googleXmp(mp4.size))
        val range = MotionPhotos.find(ArrayReader(file))!!
        assertEquals((file.size - mp4.size).toLong(), range.first)
        assertEquals((file.size - 1).toLong(), range.last)
        assertTrue(MotionPhotos.isFlagged(MotionPhotos.xmpOf(file)!!))
    }

    @Test fun findsTheClipOfALegacyMicroVideo() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="${mp4.size}"/></x:xmpmeta>"""
        val file = motionPhoto(xmp)
        assertEquals((file.size - mp4.size).toLong(), MotionPhotos.find(ArrayReader(file))!!.first)
    }

    @Test fun wrongOffsetsFallBackToScanningAndTrailersAreLeftOut() {
        // Item:Length counts 4 bytes too many and the file has a trailer after the MP4.
        val trailer = "SEFT-trailer".toByteArray()
        val file = motionPhoto(googleXmp(mp4.size + 4), trailer)
        val range = MotionPhotos.find(ArrayReader(file))!!
        assertEquals((file.size - trailer.size - mp4.size).toLong(), range.first)
        assertEquals((file.size - trailer.size - 1).toLong(), range.last)
    }

    @Test fun ordinaryPhotosHaveNoClip() {
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(300) + mp4
        assertNull(MotionPhotos.find(ArrayReader(plain)))
        // Flag set but nothing that looks like an MP4: no clip.
        val broken = motionPhoto(googleXmp(mp4.size)).copyOf(600)
        assertNull(MotionPhotos.find(ArrayReader(broken)))
        assertEquals(emptyList<Long>(), MotionPhotos.candidateOffsets("<x:xmpmeta></x:xmpmeta>", 1000))
    }
}
