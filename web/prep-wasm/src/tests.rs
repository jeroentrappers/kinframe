use super::*;
use image::{ExtendedColorType, ImageEncoder, ImageFormat, Rgba};

const EDGE: u32 = 2560;
const Q: u8 = 90;

/// Left half red, right half blue.
fn halves(w: u32, h: u32) -> RgbaImage {
    RgbaImage::from_fn(w, h, |x, _| {
        if x < w / 2 {
            Rgba([230, 20, 20, 255])
        } else {
            Rgba([20, 20, 230, 255])
        }
    })
}

fn gradient(w: u32, h: u32) -> RgbaImage {
    RgbaImage::from_fn(w, h, |x, y| Rgba([(x * 255 / w) as u8, (y * 255 / h) as u8, 128, 255]))
}

fn encode(img: &RgbaImage, format: ImageFormat) -> Vec<u8> {
    let mut out = Cursor::new(Vec::new());
    DynamicImage::ImageRgba8(img.clone())
        .write_to(&mut out, format)
        .expect("encode");
    out.into_inner()
}

/// A JPEG with an EXIF APP1 holding only an Orientation tag.
fn jpeg_with_orientation(img: &RgbaImage, orientation: u16) -> Vec<u8> {
    let mut tiff = b"Exif\0\0II*\0\x08\0\0\0\x01\0".to_vec();
    tiff.extend(0x0112u16.to_le_bytes()); // Orientation
    tiff.extend(3u16.to_le_bytes()); // SHORT
    tiff.extend(1u32.to_le_bytes());
    tiff.extend(orientation.to_le_bytes());
    tiff.extend([0, 0, 0, 0, 0, 0]);
    let mut out = Vec::new();
    let mut enc = Encoder::new(&mut out, 95);
    enc.add_app_segment(1, tiff).unwrap();
    enc.encode(img.as_raw(), img.width() as u16, img.height() as u16, ColorType::Rgba)
        .unwrap();
    out
}

fn contains(hay: &[u8], needle: &[u8]) -> bool {
    hay.windows(needle.len()).any(|w| w == needle)
}

/// Checks the output is a plain JPEG without metadata, and decodes it.
fn decode_output(p: &mut Prepared) -> RgbaImage {
    let jpeg = p.take_jpeg();
    assert_eq!(&jpeg[..3], &[0xff, 0xd8, 0xff], "JPEG magic");
    assert!(!contains(&jpeg, b"Exif"), "no EXIF");
    assert!(!contains(&jpeg, b"ICC_PROFILE"), "no ICC profile");
    assert!(!contains(&jpeg, b"http://ns.adobe.com/xap"), "no XMP");
    // Only SOI, APP0 (JFIF) and the image segments: no APP1..APP15, no COM.
    let mut i = 2;
    while i + 4 <= jpeg.len() && jpeg[i] == 0xff {
        let marker = jpeg[i + 1];
        if marker == 0xda {
            break;
        }
        assert!(
            !(0xe1..=0xef).contains(&marker) && marker != 0xfe,
            "unexpected marker {marker:02x}"
        );
        i += 2 + u16::from_be_bytes([jpeg[i + 2], jpeg[i + 3]]) as usize;
    }
    let img = image::load_from_memory_with_format(&jpeg, ImageFormat::Jpeg)
        .expect("decodes")
        .into_rgba8();
    assert_eq!((img.width(), img.height()), (p.width, p.height));
    img
}

fn near(px: &Rgba<u8>, want: [u8; 3]) -> bool {
    px.0.iter().zip(want).all(|(a, b)| (*a as i32 - b as i32).abs() <= 24)
}

#[test]
fn target_matches_plan_ts() {
    assert_eq!(target(4032, 3024, EDGE), (2560, 1920));
    assert_eq!(target(3024, 4032, EDGE), (1920, 2560));
    assert_eq!(target(3000, 2000, EDGE), (2560, 1707));
    assert_eq!(target(800, 600, EDGE), (800, 600));
    assert_eq!(target(100_000, 10, EDGE), (2560, 1));
}

#[test]
fn large_png_is_scaled_to_2560() {
    let mut p = prepare_encoded(&encode(&gradient(4000, 3000), ImageFormat::Png), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (2560, 1920));
    let img = decode_output(&mut p);
    assert!(near(img.get_pixel(1280, 960), [128, 128, 128]));
}

#[test]
fn small_images_are_never_upscaled() {
    let mut p = prepare_encoded(&encode(&gradient(800, 600), ImageFormat::Png), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (800, 600));
    decode_output(&mut p);
}

#[test]
fn exif_orientation_6_rotates_clockwise() {
    let mut p = prepare_encoded(&jpeg_with_orientation(&halves(400, 200), 6), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (200, 400));
    let img = decode_output(&mut p);
    // Rotated 90° clockwise: the left (red) half is now on top.
    assert!(
        near(img.get_pixel(100, 50), [230, 20, 20]),
        "{:?}",
        img.get_pixel(100, 50)
    );
    assert!(
        near(img.get_pixel(100, 350), [20, 20, 230]),
        "{:?}",
        img.get_pixel(100, 350)
    );
}

#[test]
fn exif_orientation_8_and_3() {
    let mut p = prepare_encoded(&jpeg_with_orientation(&halves(400, 200), 8), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (200, 400));
    let img = decode_output(&mut p);
    // 90° counter-clockwise: the left (red) half ends up at the bottom.
    assert!(near(img.get_pixel(100, 350), [230, 20, 20]));
    let mut p = prepare_encoded(&jpeg_with_orientation(&halves(400, 200), 3), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (400, 200));
    let img = decode_output(&mut p);
    assert!(near(img.get_pixel(50, 100), [20, 20, 230]));
    assert!(near(img.get_pixel(350, 100), [230, 20, 20]));
}

#[test]
fn rotated_large_photo_is_scaled_on_its_displayed_size() {
    let mut p = prepare_encoded(&jpeg_with_orientation(&halves(4000, 2000), 6), EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (1280, 2560));
    let img = decode_output(&mut p);
    assert!(near(img.get_pixel(640, 200), [230, 20, 20]));
    assert!(near(img.get_pixel(640, 2400), [20, 20, 230]));
}

#[test]
fn every_supported_format_decodes() {
    let src = gradient(3000, 1000);
    for format in [
        ImageFormat::Png,
        ImageFormat::WebP,
        ImageFormat::Gif,
        ImageFormat::Bmp,
        ImageFormat::Tiff,
        ImageFormat::Jpeg,
    ] {
        let bytes = if format == ImageFormat::Jpeg {
            jpeg_with_orientation(&src, 1)
        } else {
            encode(&src, format)
        };
        let mut p = prepare_encoded(&bytes, EDGE, Q).unwrap_or_else(|e| panic!("{format:?}: {e}"));
        assert_eq!((p.width, p.height), (2560, 853), "{format:?}");
        decode_output(&mut p);
    }
}

#[test]
fn transparency_becomes_black_like_the_canvas_path() {
    let img = RgbaImage::from_fn(10, 10, |x, _| {
        if x < 5 {
            Rgba([255, 255, 255, 0])
        } else {
            Rgba([255, 255, 255, 255])
        }
    });
    let mut p = prepare_encoded(&encode(&img, ImageFormat::Png), EDGE, Q).unwrap();
    let out = decode_output(&mut p);
    assert!(near(out.get_pixel(0, 5), [0, 0, 0]));
    assert!(near(out.get_pixel(9, 5), [255, 255, 255]));
}

#[test]
fn display_p3_profile_is_converted_and_dropped() {
    let img = RgbaImage::from_pixel(64, 64, Rgba([128, 64, 32, 255]));
    let mut png = Vec::new();
    let mut enc = image::codecs::png::PngEncoder::new(&mut png);
    enc.set_icc_profile(icc::tests::profile(icc::DISPLAY_P3_D50)).unwrap();
    enc.write_image(img.as_raw(), 64, 64, ExtendedColorType::Rgba8).unwrap();
    let mut p = prepare_encoded(&png, EDGE, Q).unwrap();
    let px = *decode_output(&mut p).get_pixel(32, 32);
    // The same numbers mean a more saturated colour in P3: in sRGB red goes up and blue down.
    assert!(px[0] > 136 && px[2] < 28, "{px:?}");
}

#[test]
fn pixels_from_libheif() {
    let rgba = gradient(3000, 4000).into_raw();
    let mut p = prepare_rgba(rgba, 3000, 4000, Colour::DisplayP3, EDGE, Q).unwrap();
    assert_eq!((p.width, p.height), (1920, 2560));
    decode_output(&mut p);
    assert!(prepare_rgba(vec![0; 12], 2, 2, Colour::Srgb, EDGE, Q).is_err());
}

#[test]
fn junk_is_an_error() {
    assert!(prepare_encoded(b"not an image at all", EDGE, Q).is_err());
    assert!(prepare_encoded(&[0xff, 0xd8, 0xff, 0xe0, 0, 16], EDGE, Q).is_err());
}
