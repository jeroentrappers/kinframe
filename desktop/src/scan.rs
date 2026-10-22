//! Finding photos and videos in folders and dating them (EXIF, QuickTime `mvhd`, else the file time).

use std::collections::HashSet;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use chrono::{DateTime, Local, NaiveDateTime, TimeZone, Utc};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaKind {
    Photo,
    Video,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DateSource {
    Exif,
    QuickTime,
    FileTime,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ScannedFile {
    pub path: PathBuf,
    /// Name of the folder the file is in, for caption templates.
    pub folder: String,
    pub size: u64,
    pub kind: MediaKind,
    pub mime: String,
    /// When it was taken, or the file's modification time.
    pub taken_at: DateTime<Utc>,
    pub date_source: DateSource,
}

impl ScannedFile {
    pub fn file_name(&self) -> String {
        self.path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default()
    }
}

/// Supported inputs (docs/api/README.md §5.3): extension → kind and MIME type.
pub fn media_type(path: &Path) -> Option<(MediaKind, &'static str)> {
    let ext = path.extension()?.to_str()?.to_ascii_lowercase();
    Some(match ext.as_str() {
        "jpg" | "jpeg" => (MediaKind::Photo, "image/jpeg"),
        "png" => (MediaKind::Photo, "image/png"),
        "webp" => (MediaKind::Photo, "image/webp"),
        "heic" => (MediaKind::Photo, "image/heic"),
        "heif" => (MediaKind::Photo, "image/heif"),
        "gif" => (MediaKind::Photo, "image/gif"),
        "mp4" => (MediaKind::Video, "video/mp4"),
        "mov" => (MediaKind::Video, "video/quicktime"),
        "m4v" => (MediaKind::Video, "video/x-m4v"),
        "3gp" => (MediaKind::Video, "video/3gpp"),
        "mkv" => (MediaKind::Video, "video/x-matroska"),
        "webm" => (MediaKind::Video, "video/webm"),
        _ => return None,
    })
}

/// Folders that only hold caches or OS metadata.
fn skip_dir(name: &str) -> bool {
    name.starts_with('.')
        || matches!(
            name,
            "@eaDir" | "__MACOSX" | "$RECYCLE.BIN" | "System Volume Information"
        )
}

#[derive(Debug, Default)]
pub struct ScanResult {
    pub files: Vec<ScannedFile>,
    /// Files that are not photos or videos.
    pub skipped: usize,
}

/// Scans files and folders (recursively), oldest first. `progress` gets the number of media files found so far.
pub fn scan(paths: &[PathBuf], cancel: &AtomicBool, mut progress: impl FnMut(usize)) -> ScanResult {
    let mut out = ScanResult::default();
    let mut seen = HashSet::new();
    let mut consider = |path: &Path, out: &mut ScanResult| {
        let Some((kind, mime)) = media_type(path) else {
            out.skipped += 1;
            return;
        };
        let canon = path.canonicalize().unwrap_or_else(|_| path.to_path_buf());
        if !seen.insert(canon) {
            return;
        }
        if let Some(f) = describe(path, kind, mime) {
            out.files.push(f);
        }
    };
    for root in paths {
        if cancel.load(Ordering::Relaxed) {
            break;
        }
        if root.is_dir() {
            let walker = walkdir::WalkDir::new(root)
                .follow_links(false)
                .into_iter()
                .filter_entry(|e| {
                    e.depth() == 0 || !(e.file_type().is_dir() && skip_dir(&e.file_name().to_string_lossy()))
                });
            for entry in walker.flatten() {
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let name = entry.file_name().to_string_lossy();
                if !entry.file_type().is_file() || name.starts_with('.') {
                    continue;
                }
                consider(entry.path(), &mut out);
                if out.files.len() % 25 == 0 {
                    progress(out.files.len());
                }
            }
        } else if root.is_file() {
            consider(root, &mut out);
        }
    }
    sort_files(&mut out.files);
    progress(out.files.len());
    out
}

/// Oldest first, so the frame receives a trip in the order it happened.
pub fn sort_files(files: &mut [ScannedFile]) {
    files.sort_by(|a, b| a.taken_at.cmp(&b.taken_at).then_with(|| a.path.cmp(&b.path)));
}

pub fn describe(path: &Path, kind: MediaKind, mime: &str) -> Option<ScannedFile> {
    let meta = std::fs::metadata(path).ok()?;
    if meta.len() == 0 {
        return None;
    }
    let file_time: DateTime<Utc> = meta
        .modified()
        .map(DateTime::<Utc>::from)
        .unwrap_or_else(|_| Utc::now());
    let (taken_at, date_source) = match capture_time(path, kind) {
        Some((t, src)) => (t, src),
        None => (file_time, DateSource::FileTime),
    };
    let folder = path
        .parent()
        .and_then(|p| p.file_name())
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    Some(ScannedFile {
        path: path.to_path_buf(),
        folder,
        size: meta.len(),
        kind,
        mime: mime.to_string(),
        taken_at,
        date_source,
    })
}

pub fn capture_time(path: &Path, kind: MediaKind) -> Option<(DateTime<Utc>, DateSource)> {
    match kind {
        MediaKind::Photo => exif_time(path).map(|t| (t, DateSource::Exif)),
        MediaKind::Video => {
            let ext = path
                .extension()
                .and_then(|e| e.to_str())
                .unwrap_or_default()
                .to_ascii_lowercase();
            if matches!(ext.as_str(), "mp4" | "mov" | "m4v" | "3gp") {
                let mut f = BufReader::new(File::open(path).ok()?);
                quicktime_creation_time(&mut f).map(|t| (t, DateSource::QuickTime))
            } else {
                None
            }
        }
    }
}

/// `DateTimeOriginal` (or `DateTimeDigitized`, `DateTime`) with `OffsetTimeOriginal` when present; without an
/// offset the camera's clock is taken as local time.
pub fn exif_time(path: &Path) -> Option<DateTime<Utc>> {
    use exif::{In, Tag};
    let mut r = BufReader::new(File::open(path).ok()?);
    let ex = exif::Reader::new().read_from_container(&mut r).ok()?;
    let ascii = |tag: Tag| -> Option<String> {
        let f = ex.get_field(tag, In::PRIMARY)?;
        match &f.value {
            exif::Value::Ascii(v) => v.first().map(|b| String::from_utf8_lossy(b).trim().to_string()),
            _ => None,
        }
    };
    let (dt, offset) = [
        (Tag::DateTimeOriginal, Tag::OffsetTimeOriginal),
        (Tag::DateTimeDigitized, Tag::OffsetTimeDigitized),
        (Tag::DateTime, Tag::OffsetTime),
    ]
    .into_iter()
    .find_map(|(t, o)| ascii(t).map(|d| (d, ascii(o))))?;
    parse_exif_datetime(&dt, offset.as_deref())
}

pub fn parse_exif_datetime(s: &str, offset: Option<&str>) -> Option<DateTime<Utc>> {
    let naive = NaiveDateTime::parse_from_str(s.get(..19)?, "%Y:%m:%d %H:%M:%S").ok()?;
    if naive.and_utc().timestamp() <= 0 {
        return None;
    }
    if let Some(off) = offset
        && let Ok(fixed) = DateTime::parse_from_str(&format!("{} {off}", &s[..19]), "%Y:%m:%d %H:%M:%S %:z")
    {
        return Some(fixed.with_timezone(&Utc));
    }
    Local
        .from_local_datetime(&naive)
        .earliest()
        .map(|t| t.with_timezone(&Utc))
}

/// Seconds between 1904-01-01 (the QuickTime epoch) and 1970-01-01.
const QT_EPOCH_OFFSET: i64 = 2_082_844_800;

/// `moov/mvhd` creation time of an MP4/MOV/3GP file (UTC per the spec). Walks top-level boxes without reading
/// `mdat`, so it is fast even for large videos with the `moov` box at the end.
pub fn quicktime_creation_time<R: Read + Seek>(r: &mut R) -> Option<DateTime<Utc>> {
    let end = r.seek(SeekFrom::End(0)).ok()?;
    r.seek(SeekFrom::Start(0)).ok()?;
    let moov = find_box(r, 0, end, b"moov")?;
    let mvhd = find_box(r, moov.0, moov.1, b"mvhd")?;
    r.seek(SeekFrom::Start(mvhd.0)).ok()?;
    let mut vf = [0u8; 4];
    r.read_exact(&mut vf).ok()?;
    let secs = if vf[0] == 1 {
        let mut b = [0u8; 8];
        r.read_exact(&mut b).ok()?;
        u64::from_be_bytes(b) as i64
    } else {
        let mut b = [0u8; 4];
        r.read_exact(&mut b).ok()?;
        u32::from_be_bytes(b) as i64
    };
    let unix = secs - QT_EPOCH_OFFSET;
    // 0 (1904) or anything before digital video means "not set".
    if unix < 631_152_000 {
        return None;
    }
    DateTime::from_timestamp(unix, 0)
}

/// Finds a child box in `[start, end)`; returns its payload range.
fn find_box<R: Read + Seek>(r: &mut R, start: u64, end: u64, kind: &[u8; 4]) -> Option<(u64, u64)> {
    let mut pos = start;
    while pos + 8 <= end {
        r.seek(SeekFrom::Start(pos)).ok()?;
        let mut h = [0u8; 8];
        r.read_exact(&mut h).ok()?;
        let mut size = u32::from_be_bytes(h[..4].try_into().ok()?) as u64;
        let mut header = 8;
        if size == 1 {
            let mut b = [0u8; 8];
            r.read_exact(&mut b).ok()?;
            size = u64::from_be_bytes(b);
            header = 16;
        } else if size == 0 {
            size = end - pos;
        }
        if size < header {
            return None;
        }
        if &h[4..8] == kind {
            return Some((pos + header, (pos + size).min(end)));
        }
        pos = pos.checked_add(size)?;
    }
    None
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::io::Cursor;

    pub fn mp4_with_creation(secs_since_1904: u32, moov_last: bool) -> Vec<u8> {
        let bx = |kind: &[u8; 4], payload: &[u8]| {
            let mut v = ((payload.len() + 8) as u32).to_be_bytes().to_vec();
            v.extend_from_slice(kind);
            v.extend_from_slice(payload);
            v
        };
        let mut mvhd = vec![0u8, 0, 0, 0];
        mvhd.extend_from_slice(&secs_since_1904.to_be_bytes());
        mvhd.extend_from_slice(&[0u8; 92]);
        let moov = bx(b"moov", &bx(b"mvhd", &mvhd));
        let ftyp = bx(b"ftyp", b"isom\0\0\x02\0");
        let mdat = bx(b"mdat", &[7u8; 1000]);
        if moov_last {
            [ftyp, mdat, moov].concat()
        } else {
            [ftyp, moov, mdat].concat()
        }
    }

    #[test]
    fn quicktime_dates() {
        // 2024-07-14 12:00:00 UTC
        let unix = 1_720_958_400i64;
        let qt = (unix + QT_EPOCH_OFFSET) as u32;
        for moov_last in [false, true] {
            let t = quicktime_creation_time(&mut Cursor::new(mp4_with_creation(qt, moov_last))).unwrap();
            assert_eq!(t.timestamp(), unix);
        }
        assert_eq!(
            quicktime_creation_time(&mut Cursor::new(mp4_with_creation(0, false))),
            None
        );
        assert_eq!(quicktime_creation_time(&mut Cursor::new(b"not a video".to_vec())), None);
    }

    #[test]
    fn exif_dates() {
        let t = parse_exif_datetime("2024:07:14 14:00:00", Some("+02:00")).unwrap();
        assert_eq!(t.to_rfc3339(), "2024-07-14T12:00:00+00:00");
        assert!(parse_exif_datetime("2024:07:14 14:00:00", None).is_some());
        assert_eq!(parse_exif_datetime("0000:00:00 00:00:00", None), None);
        assert_eq!(parse_exif_datetime("garbage", None), None);
    }

    #[test]
    fn media_types() {
        assert_eq!(
            media_type(Path::new("a/IMG_1.JPG")),
            Some((MediaKind::Photo, "image/jpeg"))
        );
        assert_eq!(
            media_type(Path::new("clip.MOV")),
            Some((MediaKind::Video, "video/quicktime"))
        );
        assert_eq!(media_type(Path::new("x.heic")).unwrap().0, MediaKind::Photo);
        assert_eq!(media_type(Path::new("notes.txt")), None);
        assert_eq!(media_type(Path::new("noext")), None);
    }

    #[test]
    fn scans_folders_recursively_sorted_by_date() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("Summer 2024");
        std::fs::create_dir_all(root.join("day 2")).unwrap();
        std::fs::create_dir_all(root.join(".thumbnails")).unwrap();
        std::fs::create_dir_all(root.join("@eaDir")).unwrap();
        // Video with an embedded date in 2020: should sort first.
        let qt = (1_590_000_000i64 + QT_EPOCH_OFFSET) as u32;
        std::fs::write(root.join("day 2/clip.mp4"), mp4_with_creation(qt, true)).unwrap();
        std::fs::write(root.join("b.jpg"), b"jpeg-ish").unwrap();
        std::fs::write(root.join("a.png"), b"png-ish").unwrap();
        std::fs::write(root.join("notes.txt"), b"x").unwrap();
        std::fs::write(root.join(".hidden.jpg"), b"x").unwrap();
        std::fs::write(root.join("empty.jpg"), b"").unwrap();
        std::fs::write(root.join(".thumbnails/t.jpg"), b"x").unwrap();
        std::fs::write(root.join("@eaDir/t.jpg"), b"x").unwrap();

        let cancel = AtomicBool::new(false);
        // The same folder twice and a file inside it: no duplicates.
        let res = scan(&[root.clone(), root.clone(), root.join("b.jpg")], &cancel, |_| {});
        let names: Vec<_> = res.files.iter().map(|f| f.file_name()).collect();
        assert_eq!(names.len(), 3, "{names:?}");
        assert_eq!(names[0], "clip.mp4");
        assert_eq!(res.files[0].date_source, DateSource::QuickTime);
        assert_eq!(res.files[0].folder, "day 2");
        assert_eq!(res.files[1].folder, "Summer 2024");
        assert!(res.skipped >= 1);
    }
}
