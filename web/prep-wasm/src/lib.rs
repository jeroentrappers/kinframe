//! Photo master preparation (`photo-master-v1`, docs/api/README.md §5.3) for browsers that can't do it with
//! `createImageBitmap` and `OffscreenCanvas`: decode (JPEG, PNG, WebP, GIF first frame, BMP, TIFF), scale so the
//! long edge is at most 2560 px (never up) with Lanczos3, apply the EXIF orientation, convert to sRGB when the
//! embedded ICC profile is a matrix/TRC RGB profile, and encode a baseline 4:2:0 JPEG. The output carries no metadata
//! at all: no EXIF, no XMP, no ICC profile (it is sRGB).
//!
//! HEIC/HEIF is decoded by libheif in the web app and arrives here as RGBA pixels ([`prepare_pixels`]).

mod icc;

use std::io::Cursor;

use fast_image_resize::images::Image as FirImage;
use fast_image_resize::{FilterType, PixelType, ResizeAlg, ResizeOptions, Resizer};
use image::metadata::Orientation;
use image::{DynamicImage, ImageDecoder, ImageReader, Limits, RgbaImage};
use jpeg_encoder::{ColorType, Encoder, SamplingFactor};
use wasm_bindgen::prelude::*;

pub use icc::ToSrgb;

/// Decoders refuse images that would need more memory than this (a 100 MP RGBA image is 400 MB).
const MAX_ALLOC: u64 = 1 << 30;

/// A prepared master: JPEG bytes and their size.
#[wasm_bindgen]
pub struct Prepared {
    width: u32,
    height: u32,
    jpeg: Vec<u8>,
}

#[wasm_bindgen]
impl Prepared {
    #[wasm_bindgen(getter)]
    pub fn width(&self) -> u32 {
        self.width
    }

    #[wasm_bindgen(getter)]
    pub fn height(&self) -> u32 {
        self.height
    }

    /// The JPEG (moved out; a second call returns an empty array).
    #[wasm_bindgen(js_name = takeJpeg)]
    pub fn take_jpeg(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.jpeg)
    }
}

/// Where the pixels' colours come from.
pub enum Colour<'a> {
    Srgb,
    Icc(&'a [u8]),
    DisplayP3,
}

/// Scale (never up) so the long edge is at most `max_edge`; the same rounding as `photoTarget` in plan.ts.
pub fn target(width: u32, height: u32, max_edge: u32) -> (u32, u32) {
    let scale = (max_edge as f64 / width.max(height) as f64).min(1.0);
    let r = |v: u32| ((v as f64 * scale).round() as u32).max(1);
    (r(width), r(height))
}

fn swaps_axes(o: Orientation) -> bool {
    matches!(
        o,
        Orientation::Rotate90 | Orientation::Rotate270 | Orientation::Rotate90FlipH | Orientation::Rotate270FlipH
    )
}

/// Composites any transparency onto black, as the browser path does (it draws onto an opaque canvas).
fn flatten(rgba: &mut [u8]) {
    for px in rgba.as_chunks_mut::<4>().0 {
        let a = px[3] as u32;
        if a != 255 {
            for c in px.iter_mut().take(3) {
                *c = ((*c as u32 * a + 127) / 255) as u8;
            }
            px[3] = 255;
        }
    }
}

fn resize(rgba: Vec<u8>, width: u32, height: u32, to: (u32, u32)) -> Result<Vec<u8>, String> {
    if (width, height) == to {
        return Ok(rgba);
    }
    let src = FirImage::from_vec_u8(width, height, rgba, PixelType::U8x4).map_err(|e| e.to_string())?;
    let mut dst = FirImage::new(to.0, to.1, PixelType::U8x4);
    let opts = ResizeOptions::new().resize_alg(ResizeAlg::Convolution(FilterType::Lanczos3));
    Resizer::new()
        .resize(&src, &mut dst, &opts)
        .map_err(|e| e.to_string())?;
    Ok(dst.into_vec())
}

fn encode_jpeg(rgba: &[u8], width: u32, height: u32, quality: u8) -> Result<Vec<u8>, String> {
    let (w, h) = (
        u16::try_from(width).map_err(|_| "too wide")?,
        u16::try_from(height).map_err(|_| "too tall")?,
    );
    let mut out = Vec::with_capacity(rgba.len() / 8);
    let mut enc = Encoder::new(&mut out, quality.clamp(1, 100));
    enc.set_sampling_factor(SamplingFactor::R_4_2_0);
    enc.encode(rgba, w, h, ColorType::Rgba).map_err(|e| e.to_string())?;
    Ok(out)
}

/// Scale, orient, convert to sRGB and encode. `orientation` is applied after scaling (cheaper), so the target is
/// computed on the displayed size.
fn finish(
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    orientation: Orientation,
    colour: Colour,
    max_edge: u32,
    quality: u8,
) -> Result<Prepared, String> {
    if width == 0 || height == 0 {
        return Err("empty image".into());
    }
    let swap = swaps_axes(orientation);
    let (dw, dh) = if swap { (height, width) } else { (width, height) };
    let (tw, th) = target(dw, dh, max_edge);
    let to = if swap { (th, tw) } else { (tw, th) };
    let mut pixels = resize(rgba, width, height, to)?;
    let transform = match colour {
        Colour::Srgb => None,
        Colour::Icc(p) => ToSrgb::from_icc(p),
        Colour::DisplayP3 => Some(ToSrgb::display_p3()),
    };
    if let Some(t) = transform {
        t.apply(&mut pixels);
    }
    let (mut w, mut h) = to;
    if orientation != Orientation::NoTransforms {
        let buf = RgbaImage::from_raw(w, h, pixels).ok_or("bad buffer")?;
        let mut img = DynamicImage::ImageRgba8(buf);
        img.apply_orientation(orientation);
        (w, h) = (img.width(), img.height());
        pixels = img.into_rgba8().into_raw();
    }
    let jpeg = encode_jpeg(&pixels, w, h, quality)?;
    Ok(Prepared {
        width: w,
        height: h,
        jpeg,
    })
}

/// Prepares an encoded image (JPEG, PNG, WebP, GIF, BMP or TIFF).
pub fn prepare_encoded(input: &[u8], max_edge: u32, quality: u8) -> Result<Prepared, String> {
    let mut reader = ImageReader::new(Cursor::new(input))
        .with_guessed_format()
        .map_err(|e| e.to_string())?;
    if reader.format().is_none() {
        return Err("unsupported image format".into());
    }
    let mut limits = Limits::default();
    limits.max_alloc = Some(MAX_ALLOC);
    reader.limits(limits);
    let mut decoder = reader.into_decoder().map_err(|e| e.to_string())?;
    let orientation = decoder.orientation().unwrap_or(Orientation::NoTransforms);
    let icc = decoder.icc_profile().ok().flatten();
    let img = DynamicImage::from_decoder(decoder).map_err(|e| e.to_string())?;
    let (w, h) = (img.width(), img.height());
    let has_alpha = img.color().has_alpha();
    let mut rgba = img.into_rgba8().into_raw();
    if has_alpha {
        flatten(&mut rgba);
    }
    let colour = icc.as_deref().map_or(Colour::Srgb, Colour::Icc);
    finish(rgba, w, h, orientation, colour, max_edge, quality)
}

/// Prepares decoded RGBA8 pixels (HEIC/HEIF from libheif, with its rotation and mirroring already applied).
pub fn prepare_rgba(
    mut rgba: Vec<u8>,
    width: u32,
    height: u32,
    colour: Colour,
    max_edge: u32,
    quality: u8,
) -> Result<Prepared, String> {
    if rgba.len() as u64 != width as u64 * height as u64 * 4 {
        return Err("pixel buffer does not match the size".into());
    }
    flatten(&mut rgba);
    finish(
        rgba,
        width,
        height,
        Orientation::NoTransforms,
        colour,
        max_edge,
        quality,
    )
}

/// JS: prepare an encoded image. Throws with a short reason when it can't.
#[wasm_bindgen(js_name = prepareImage)]
pub fn prepare_image_js(input: &[u8], max_edge: u32, quality: u8) -> Result<Prepared, JsError> {
    prepare_encoded(input, max_edge, quality).map_err(|e| JsError::new(&e))
}

/// JS: prepare RGBA pixels. `icc` is the image's ICC profile if any; `p3` marks Display P3 pixels without one
/// (HEIF `nclx` colour primaries 12).
#[wasm_bindgen(js_name = preparePixels)]
pub fn prepare_pixels(
    rgba: Vec<u8>,
    width: u32,
    height: u32,
    icc: Option<Vec<u8>>,
    p3: bool,
    max_edge: u32,
    quality: u8,
) -> Result<Prepared, JsError> {
    let colour = match (&icc, p3) {
        (Some(p), _) => Colour::Icc(p),
        (None, true) => Colour::DisplayP3,
        (None, false) => Colour::Srgb,
    };
    prepare_rgba(rgba, width, height, colour, max_edge, quality).map_err(|e| JsError::new(&e))
}

#[cfg(test)]
mod tests;
