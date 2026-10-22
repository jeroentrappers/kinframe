//! Renders every main screen with demo data, offscreen (wgpu), in light and dark, to PNG files:
//!
//!     cargo run --example screenshots -- <out-dir> [scale]
//!
//! No window, server or display needed (a Vulkan, Metal, DX12 or GL adapter is enough).

use std::sync::Arc;

use egui::{Theme, vec2};
use egui_kittest::Harness;
use kinframe_desktop::i18n::{self, Lang};
use kinframe_desktop::ui::{App, demo};

fn main() {
    let mut args = std::env::args().skip(1);
    let out = std::path::PathBuf::from(args.next().unwrap_or_else(|| "screenshots".into()));
    let scale: f32 = args.next().and_then(|s| s.parse().ok()).unwrap_or(1.5);
    std::fs::create_dir_all(&out).expect("output dir");
    let tmp = tempfile::tempdir().expect("temp dir");

    let mut shots = vec![];
    for theme in [Theme::Light, Theme::Dark] {
        for screen in demo::Screen::ALL {
            shots.push((theme, screen, Lang::En));
        }
    }
    shots.push((Theme::Light, demo::Screen::Draft, Lang::Nl));
    shots.push((Theme::Dark, demo::Screen::History, Lang::Nl));

    for (theme, screen, lang) in shots {
        i18n::set_lang(lang);
        let dir = tmp.path().to_path_buf();
        let mut harness: Harness<'_, Option<App>> = Harness::builder()
            .with_size(vec2(1280.0, 820.0))
            .with_pixels_per_point(scale)
            .with_theme(theme)
            .with_context_setup(|ctx| {
                ctx.add_font_provider(Arc::new(egui_system_font_provider::SystemFontProvider::new()));
            })
            .allow_missing_glyphs()
            .wgpu()
            .build_ui_state(
                move |ui, state: &mut Option<App>| {
                    let app = state.get_or_insert_with(|| {
                        let a = demo::app(ui.ctx(), screen, &dir);
                        // The app follows the OS theme; pin the one we're rendering.
                        ui.ctx().set_theme(theme);
                        i18n::set_lang(lang);
                        a
                    });
                    app.ui_root(ui);
                },
                None,
            );
        harness.run_steps(6);
        let full = harness.render().expect("render");
        // The harness wraps the UI in a panel with an 8 pt margin; the real window has none.
        let m = (8.0 * scale).round() as u32;
        let img = image::imageops::crop_imm(&full, m, m, full.width() - 2 * m, full.height() - 2 * m).to_image();
        let theme_name = if theme == Theme::Dark { "dark" } else { "light" };
        let path = out.join(format!("{}-{}-{}.png", screen.name(), theme_name, lang.code()));
        img.save(&path).expect("save png");
        println!("{}", path.display());
    }
}
