//! Thumbnail cache: decodes local photos (with EXIF orientation) and signed thumbnail URLs in the background,
//! keeps a bounded number of textures, and only loads what is on screen.

use std::collections::HashMap;
use std::path::PathBuf;

use egui::{ColorImage, Context, TextureHandle, TextureOptions};

pub const EDGE: u32 = 320;
const MAX_TEXTURES: usize = 600;
const MAX_IN_FLIGHT: usize = 6;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub enum Source {
    File(PathBuf),
    Url(String),
}

enum Slot {
    Loading,
    Ready(TextureHandle, u64),
    Failed,
}

#[derive(Default)]
pub struct Thumbs {
    slots: HashMap<Source, Slot>,
    frame: u64,
    in_flight: usize,
}

/// What the background decoder hands back.
pub struct Decoded {
    pub source: Source,
    pub image: Option<ColorImage>,
}

pub enum Request {
    Load(Source),
}

impl Thumbs {
    pub fn begin_frame(&mut self) {
        self.frame += 1;
    }

    /// The texture if ready; otherwise returns a load request (at most a few at a time).
    pub fn get(&mut self, source: &Source) -> (Option<TextureHandle>, Option<Request>) {
        match self.slots.get_mut(source) {
            Some(Slot::Ready(t, used)) => {
                *used = self.frame;
                (Some(t.clone()), None)
            }
            Some(Slot::Loading) | Some(Slot::Failed) => (None, None),
            None if self.in_flight < MAX_IN_FLIGHT => {
                self.in_flight += 1;
                self.slots.insert(source.clone(), Slot::Loading);
                (None, Some(Request::Load(source.clone())))
            }
            None => (None, None),
        }
    }

    pub fn failed(&self, source: &Source) -> bool {
        matches!(self.slots.get(source), Some(Slot::Failed))
    }

    pub fn finish(&mut self, ctx: &Context, d: Decoded) {
        self.in_flight = self.in_flight.saturating_sub(1);
        let slot = match d.image {
            Some(img) => {
                let name = match &d.source {
                    Source::File(p) => p.to_string_lossy().into_owned(),
                    Source::Url(u) => u.clone(),
                };
                Slot::Ready(ctx.load_texture(name, img, TextureOptions::LINEAR), self.frame)
            }
            None => Slot::Failed,
        };
        self.slots.insert(d.source, slot);
        self.evict();
    }

    /// Drops the least recently shown textures above the cap.
    fn evict(&mut self) {
        let ready = self.slots.values().filter(|s| matches!(s, Slot::Ready(..))).count();
        if ready <= MAX_TEXTURES {
            return;
        }
        let mut by_age: Vec<(Source, u64)> = self
            .slots
            .iter()
            .filter_map(|(k, s)| match s {
                Slot::Ready(_, used) => Some((k.clone(), *used)),
                _ => None,
            })
            .collect();
        by_age.sort_by_key(|(_, used)| *used);
        for (k, _) in by_age.into_iter().take(ready - MAX_TEXTURES) {
            self.slots.remove(&k);
        }
    }

    /// Insert a ready image directly (demo screenshots).
    pub fn insert(&mut self, ctx: &Context, source: Source, img: ColorImage) {
        self.in_flight += 1;
        self.finish(
            ctx,
            Decoded {
                source,
                image: Some(img),
            },
        );
    }
}

/// Decodes and downsizes an image file or byte buffer, applying EXIF orientation.
pub fn decode(bytes_or_path: Result<Vec<u8>, PathBuf>) -> Option<ColorImage> {
    use image::ImageDecoder;
    let reader = match bytes_or_path {
        Ok(bytes) => image::ImageReader::new(std::io::Cursor::new(bytes))
            .with_guessed_format()
            .ok()?
            .into_decoder()
            .ok()
            .map(|d| Box::new(d) as Box<dyn ImageDecoder>),
        Err(path) => image::ImageReader::open(path)
            .ok()?
            .with_guessed_format()
            .ok()?
            .into_decoder()
            .ok()
            .map(|d| Box::new(d) as Box<dyn ImageDecoder>),
    };
    let mut decoder = reader?;
    let orientation = decoder.orientation().ok();
    let mut img = image::DynamicImage::from_decoder(decoder).ok()?;
    if let Some(o) = orientation {
        img.apply_orientation(o);
    }
    let thumb = img.thumbnail(EDGE, EDGE).to_rgba8();
    let size = [thumb.width() as usize, thumb.height() as usize];
    Some(ColorImage::from_rgba_unmultiplied(size, thumb.as_raw()))
}

/// Whether we can draw a preview for this file locally (HEIC and videos get a placeholder).
pub fn previewable(path: &std::path::Path) -> bool {
    matches!(
        path.extension()
            .and_then(|e| e.to_str())
            .map(|e| e.to_ascii_lowercase())
            .as_deref(),
        Some("jpg" | "jpeg" | "png" | "webp" | "gif")
    )
}
