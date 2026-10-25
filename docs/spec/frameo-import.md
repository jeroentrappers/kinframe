# Importing the photos of a converted Frameo frame

Interoperability note for the "keep your photos" step of converting a stock Frameo frame (issue #1, `tools/convert/`,
web `/convert`). Written from what can be seen on the device itself: the files the old app left on storage and the
database it keeps them in, read on a frame we own. No Frameo app code was decompiled or consulted, and nothing below
copies Frameo texts or assets. Field and class names are listed only because a reader has to know them to find the data.

## What a converted frame still has

Converting installs kinframe as the launcher and disables the Frameo app; its data stays on the device untouched (the
revert path needs it). On the reference frame (Denver PFF-1024, Android 6, root through `su 0`) we observed:

| Where | What |
|---|---|
| `/sdcard/frameo_files/media/<n>.webp` | Every received photo as WebP, already scaled down by the sender's app (about 1700 × 1280 or 2048 × 1152; larger than the 1024 × 600 screen). `<n>` is the time the frame received it, in milliseconds since 1970. |
| `/sdcard/frameo_files/media/<n>.mp4` | Received videos (H.264 MP4), same naming. |
| `/sdcard/frameo_files/{profilePictures,videoThumbnails,cache}` | Sender pictures, video posters and scratch files. Not imported. |
| `/data/data/net.frameo.frame/files/default.realm` | The app's database: a [Realm](https://github.com/realm/realm-core) file, not encrypted, private to the app (needs root to read). A second file, `calendar.realm`, holds calendar data and is not used here. |

`/sdcard` is `/storage/emulated/0`; the database stores absolute paths with the latter prefix.

### The database

The file header says Realm file format 23 (the format of Realm Core 13). It holds a schema (schema version 30 on the
reference frame) with, among others, these classes. Only the fields the import reads are listed.

| Class | Field | Type | Meaning, as observed |
|---|---|---|---|
| `Media` | `id` | int | Primary key. One object per received file. |
| | `filePath` | string | Absolute path of the file under `frameo_files/media/`. |
| | `receivedTime` | date | When the frame received it; equals the number in the file name. |
| | `captureTime` | date, optional | When the photo or video was taken. |
| | `caption` | string, optional | Caption as sent. |
| | `editedCaption`, `isCaptionEdited` | string, bool | A caption changed afterwards; when the flag is set, this one is shown. |
| | `isEnabled` | bool | False when the photo was hidden from the slideshow on the frame. |
| | `friend` | link to `Friend`, optional | Who sent it. |
| | `picture` / `video` | link, optional | Exactly one is set for a photo or a video. |
| | `greeting` | link, optional | A greeting-card decoration; the file underneath is still a normal photo. |
| `Friend` | `name` | string | The sender's display name as the frame knows it. |
| `Picture` | `scaleType` | int | 0 = fill the screen (crop), 1 = show the whole photo (see below). |
| | `centerPointX`, `centerPointY` | float | Point of the photo, as fractions (0..1) of its width and height, to keep in view when cropping. 0.5/0.5 is the default. |
| | `rotation` | int | Extra rotation in degrees chosen on the frame. |
| `Video` | `durationMs`, `hasAudio` | int, bool | Length and audio (also read from the file by the server, so informational). |

There is no "trashed" flag on `Media` (a trash flag exists only on schedule entries); the files in `media/` and the
`Media` objects match one to one on the reference frame, so a photo deleted on the frame appears to be gone from both.
Should a file ever have no database entry, it is still imported from the file alone. The `Reaction` class links reactions to `Media`, but its numeric reaction types have
no documented meaning, so reactions are not imported.

`scaleType`: on the reference frame 109 of 112 photos have 0 and most of them a moved centre point, while the 3 with 1
nearly all keep the default centre, which only makes sense if 0 crops around the centre point and 1 shows the whole
photo. This is an inference from data, not from code; if it turns out to be the other way round, only the mapping
table in `FrameoMapping` changes.

### Reference frame contents (counts only)

113 `Media` objects, matching the 113 files: 112 photos, 1 video, 0 greetings; capture time on all 113, sender on all
113 (2 senders), 0 captions, 0 hidden, 3 "whole photo" and 109 "fill" photos (75 with a moved centre point), no extra
rotation. Albums, unsupported files and archive entries: 0.

## How kinframe imports them

The import runs **on the frame**, in the kinframe frame app: it is the only place with root access to the private
database, and it keeps going without a browser.

1. **Find.** The frame looks for `frameo_files/media/` on its internal storage. If it holds photos or videos and the
   frame is claimed, the import is offered.
2. **Read the database (best effort).** With root, the frame copies `default.realm` into its own private folder and
   opens the **copy** with the official Realm library in dynamic (schema-less) mode. The original is never opened,
   locked or written. The copy is deleted afterwards. When there is no root, the library cannot load on the device, or
   the file cannot be read, the import still runs on the files alone: received time from the file name, no caption, no
   sender, server-side capture time from the file's own metadata.
3. **Plan.** Every media file becomes one candidate. Files with a database entry get its received time, capture
   time, caption (the edited one when set), sender name, crop/fit choice, centre point and hidden state. Files that are
   not photos or videos, or larger than the server accepts, are skipped. Entries whose file is missing are skipped;
   files without an entry are imported from the file alone.
4. **Upload.** Each file goes up through the frame's existing import endpoint (`POST /v1/device/imports`, the same
   path, processing, rendition and EXIF stripping as SD/USB imports), with `source=frameo` and a stable `source_key`:
   the Frameo file name (`<received ms>.<ext>`). The server keeps one item per frame and key, so running the import
   again, after a reboot, a network loss or on purpose, never creates duplicates; a key it has seen before (even if
   the photo was deleted in kinframe since) is answered with the existing placement, not re-imported. Before
   uploading, the frame asks which keys the server already has (`POST /v1/device/imports/check`), so a resumed import
   only sends what is missing.
5. **Store.** The server creates the item with the frame as sender (like any import on the frame), the frame owner as
   owner of the media, the original sender's name and the "imported from Frameo" marker on the item, the capture time
   as capture hint, the caption, and fit and focal point (fill → `fill` with the centre point as focal point; whole
   photo → `fit`). The placement's received time is the original received time, so the slideshow order and
   "received" dates stay as they were; a hidden photo stays hidden. Imported photos are not treated as new arrivals.
6. **Show.** Wherever a sender is shown (frame caption line, web library), an imported item shows the original sender
   as "Anna · via Frameo", or "via Frameo" when the sender is unknown.

### When it runs

- **Automatically, once**, after the frame is claimed by its owner, when Frameo files are present and the owner has
  not stopped it before. Progress shows as a small card on the slideshow with a Stop button; stopping counts as
  declining the automatic run.
- **From the frame settings** ("Photos from Frameo" → Import), at any time, also to finish a stopped run.
- It pauses while the frame is offline and continues where it left off when it is back online or after a reboot:
  progress is the set of keys the server already has, so nothing extra has to survive on the frame.
- At the end the frame shows a summary: imported, already there, skipped.

### What is not imported

Reactions (meaning of the stored values unknown), albums (none observed), sender pictures, greeting-card decorations,
calendar data, and the Frameo friend list (senders keep their name on each photo but do not become kinframe members;
they join with an invite like everyone else).

## Cost and limits

- **APK size.** Realm's native library for 32-bit ARM adds about 2.9 MB to the frame APK (6.8 MB instead of 3.8 MB;
  the library is compressed in the APK and left out for every other ABI). On a 64-bit or x86 device the library
  does not load and the import uses the files alone. The library is Apache-2.0 licensed (Realm Java 10.19, Realm
  Core 13, which reads file format 23 as is).
- **Time.** About 110 photos of 200 to 400 KB each upload in a few minutes on a normal connection; the server then
  renders them like any upload. The slideshow keeps playing meanwhile.
- **Testing.** `FrameoImportTest` (unit) covers the mapping, planning and upload loop with synthetic data;
  `FrameoDatabaseTest` (instrumented, emulator only) reads a synthetic Realm file with the real library and, given a
  file, a real one (counts only); the server's `TestFrameoImport` covers the endpoint.
