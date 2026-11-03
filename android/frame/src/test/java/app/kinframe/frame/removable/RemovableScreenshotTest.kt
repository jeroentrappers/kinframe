package app.kinframe.frame.removable

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots.shot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Design review shots for the SD/USB features (written to `frame/build/screenshots/`): the cards on the slideshow,
 * the storage panel, export progress and result, the import picker, and a problem, in landscape (English) and
 * portrait (Dutch).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class RemovableScreenshotTest {
    private val usb = Volume("public:8,1", "1A2B-3C4D", File("/storage/1A2B-3C4D"), null, Volume.Kind.Usb, "KINFRAME", "vfat", "USB drive")
    private val gb = 1024L * 1024 * 1024

    private fun state(task: StorageTask) = PanelState(usb, task, autoBackup = true, lastBackupText = "yesterday", canEject = true, freeBytes = 23 * gb, totalBytes = 29 * gb)

    private fun cards(suffix: String, seed: Int) = shot("removable_cards_$suffix") {
        FakePhoto(seed)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RemovableCard(Volume.Kind.Usb, {}, {})
            RemovableCard(Volume.Kind.SdCard, {}, {})
            RestoreCard(312, "Oma's kitchen frame", {}, {})
            BackupPill(42)
        }
    }

    private fun panel(suffix: String) = shot("removable_panel_$suffix") { RemovablePanel(state(StorageTask.Idle), PanelActions()) }

    private fun progress(suffix: String) = shot("removable_export_progress_$suffix") {
        RemovablePanel(
            state(StorageTask.Exporting(usb, backup = false, Progress(142, 312, 1_300_000_000, 3_400_000_000, "2026-07-05_Oma_Zondag-aan-zee.webp"))),
            PanelActions(),
        )
    }

    private fun done(suffix: String) = shot("removable_export_done_$suffix") {
        RemovablePanel(
            state(
                StorageTask.Exported(
                    usb, backup = false,
                    ExportEngine.Outcome.Done(
                        copied = 309, kept = 0,
                        skipped = listOf(ExportManifest.Skipped("a", SkipReason.NOT_ON_FRAME), ExportManifest.Skipped("b", SkipReason.TOO_LARGE_FOR_FAT32)),
                        dir = "/storage/1A2B-3C4D/kinframe-export", method = VolumeWriter.Method.Root,
                    ),
                ),
            ),
            PanelActions(),
        )
    }

    private fun problem(suffix: String) = shot("removable_problem_$suffix") {
        RemovablePanel(state(StorageTask.Problem(usb, StorageTask.Problem.Kind.NoSpace, needBytes = 3_400_000_000, freeBytes = 1_100_000_000)), PanelActions())
    }

    private fun candidates() = (1..23).map { i ->
        val video = i % 5 == 0
        ImportCandidate(
            File("/storage/1A2B-3C4D/DCIM/Camera/IMG_20260${i % 9 + 1}0${i % 9}_$i.${if (video) "mp4" else "jpg"}"),
            "DCIM/Camera/IMG_$i.${if (video) "mp4" else "jpg"}", if (video) "video" else "photo", if (video) "video/mp4" else "image/jpeg",
            2_000_000L * i, 1_700_000_000_000L - i * 86_400_000L,
        )
    }

    private fun picker(suffix: String) = shot("removable_import_picker_$suffix") {
        val items = candidates()
        ImportPicker(
            items, restore = false, onCancel = {}, onImport = {},
            thumb = { FakePhoto(it.relativePath.length) },
            initiallySelected = items.filterIndexed { i, _ -> i % 3 != 1 }.mapTo(HashSet()) { it.file },
        )
    }

    @Test fun cardsLandscape() = cards("landscape", 0)
    @Test fun panelLandscape() = panel("landscape")
    @Test fun exportProgressLandscape() = progress("landscape")
    @Test fun exportDoneLandscape() = done("landscape")
    @Test fun problemLandscape() = problem("landscape")
    @Test fun importPickerLandscape() = picker("landscape")

    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi") fun cardsPortraitDutch() = cards("portrait_nl", 2)
    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi") fun panelPortraitDutch() = panel("portrait_nl")
    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi") fun exportProgressPortraitDutch() = progress("portrait_nl")
    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi") fun exportDonePortraitDutch() = done("portrait_nl")
    @Test @Config(qualifiers = "nl-w800dp-h1280dp-port-mdpi") fun importPickerPortraitDutch() = picker("portrait_nl")
}
