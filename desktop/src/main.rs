// No console window on Windows release builds.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

use kinframe_desktop::ui::App;

fn icon() -> Option<egui::IconData> {
    let img = image::load_from_memory(include_bytes!("../assets/icons/icon-256.png"))
        .ok()?
        .to_rgba8();
    let (width, height) = img.dimensions();
    Some(egui::IconData {
        rgba: img.into_raw(),
        width,
        height,
    })
}

fn main() -> eframe::Result {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("warn,kinframe_desktop=info")).init();
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("kinframe")
        .with_app_id("app.kinframe.desktop")
        .with_inner_size([1180.0, 800.0])
        .with_min_inner_size([820.0, 600.0])
        .with_drag_and_drop(true);
    if let Some(i) = icon() {
        viewport = viewport.with_icon(i);
    }
    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };
    eframe::run_native("kinframe", options, Box::new(|cc| Ok(Box::new(App::new(cc)))))
}
