//! kinframe's warm, calm look: the web's design tokens (web/src/app.css) as egui styles, light and dark.

use std::sync::Arc;

use egui::{
    Color32, Context, CornerRadius, FontData, FontDefinitions, FontFamily, FontId, Margin, Stroke, TextStyle, Theme,
    Visuals, epaint::Shadow, style::Selection,
};

#[derive(Debug, Clone, Copy)]
pub struct Palette {
    pub bg: Color32,
    pub surface: Color32,
    pub surface2: Color32,
    pub surface3: Color32,
    pub border: Color32,
    pub border_strong: Color32,
    pub text: Color32,
    pub text_muted: Color32,
    pub text_faint: Color32,
    pub primary: Color32,
    pub primary_hover: Color32,
    pub primary_soft: Color32,
    pub primary_soft_text: Color32,
    pub on_primary: Color32,
    pub accent: Color32,
    pub accent_soft: Color32,
    pub accent_text: Color32,
    pub warn: Color32,
    pub warn_soft: Color32,
    pub danger: Color32,
    pub danger_soft: Color32,
    pub sun: Color32,
    pub focus: Color32,
    pub shadow: Color32,
    /// The paper inside the logo frame.
    pub paper: Color32,
    pub hill: Color32,
}

const fn hex(rgb: u32) -> Color32 {
    Color32::from_rgb((rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8)
}

pub const LIGHT: Palette = Palette {
    bg: hex(0xf8f3ec),
    surface: hex(0xfffdf9),
    surface2: hex(0xf4ede4),
    surface3: hex(0xebe2d6),
    border: hex(0xe4d8ca),
    border_strong: hex(0xcdbdab),
    text: hex(0x2a221d),
    text_muted: hex(0x675a50),
    text_faint: hex(0x8b7d71),
    primary: hex(0xa7471e),
    primary_hover: hex(0x8d3b18),
    primary_soft: hex(0xf7e4d8),
    primary_soft_text: hex(0x7c3412),
    on_primary: hex(0xffffff),
    accent: hex(0x3c6a49),
    accent_soft: hex(0xe2eee3),
    accent_text: hex(0x2f5a3c),
    warn: hex(0x8a5a00),
    warn_soft: hex(0xfbefd3),
    danger: hex(0xb3261e),
    danger_soft: hex(0xfbe4e1),
    sun: hex(0xe7a23a),
    focus: hex(0x1f5fbf),
    shadow: Color32::from_rgba_premultiplied(15, 10, 5, 18),
    paper: hex(0xfbf1e6),
    hill: hex(0x4f7a5a),
};

pub const DARK: Palette = Palette {
    bg: hex(0x191512),
    surface: hex(0x231e1a),
    surface2: hex(0x2c2621),
    surface3: hex(0x373029),
    border: hex(0x3a322b),
    border_strong: hex(0x54493f),
    text: hex(0xf3ebe3),
    text_muted: hex(0xbdaea1),
    text_faint: hex(0x94867a),
    primary: hex(0xf09a6d),
    primary_hover: hex(0xf6b08b),
    primary_soft: hex(0x3d2519),
    primary_soft_text: hex(0xf6c1a3),
    on_primary: hex(0x2a1206),
    accent: hex(0x8cc39b),
    accent_soft: hex(0x1f3226),
    accent_text: hex(0xa6d6b2),
    warn: hex(0xf0c066),
    warn_soft: hex(0x3a2d12),
    danger: hex(0xf28b82),
    danger_soft: hex(0x3d1d1a),
    sun: hex(0xe7a23a),
    focus: hex(0x8ab4ff),
    shadow: Color32::from_rgba_premultiplied(0, 0, 0, 90),
    paper: hex(0xfbf1e6),
    hill: hex(0x4f7a5a),
};

pub fn palette(ctx: &Context) -> &'static Palette {
    match ctx.theme() {
        Theme::Dark => &DARK,
        Theme::Light => &LIGHT,
    }
}

/// Fraunces for headings (like the web), Inter for everything else. Both under the SIL OFL 1.1.
pub fn display() -> FontFamily {
    FontFamily::Name("display".into())
}
pub fn strong() -> FontFamily {
    FontFamily::Name("strong".into())
}

pub fn heading(size: f32) -> FontId {
    FontId::new(size, display())
}
pub fn strong_font(size: f32) -> FontId {
    FontId::new(size, strong())
}
pub fn body(size: f32) -> FontId {
    FontId::new(size, FontFamily::Proportional)
}

pub fn install(ctx: &Context) {
    let mut fonts = FontDefinitions::default();
    let add = |fonts: &mut FontDefinitions, name: &str, bytes: &'static [u8]| {
        fonts
            .font_data
            .insert(name.into(), Arc::new(FontData::from_static(bytes)));
    };
    add(
        &mut fonts,
        "Inter",
        include_bytes!("../../assets/fonts/Inter-Regular.ttf"),
    );
    add(
        &mut fonts,
        "Inter-SemiBold",
        include_bytes!("../../assets/fonts/Inter-SemiBold.ttf"),
    );
    add(
        &mut fonts,
        "Fraunces",
        include_bytes!("../../assets/fonts/Fraunces-SemiBold.ttf"),
    );
    let fallback: Vec<String> = fonts
        .families
        .get(&FontFamily::Proportional)
        .cloned()
        .unwrap_or_default();
    let with = |first: &[&str]| -> Vec<String> {
        first
            .iter()
            .map(|s| s.to_string())
            .chain(fallback.iter().cloned())
            .collect()
    };
    fonts.families.insert(FontFamily::Proportional, with(&["Inter"]));
    fonts.families.insert(strong(), with(&["Inter-SemiBold", "Inter"]));
    fonts.families.insert(display(), with(&["Fraunces", "Inter-SemiBold"]));
    ctx.set_fonts(fonts);

    ctx.set_visuals_of(Theme::Light, visuals(&LIGHT, false));
    ctx.set_visuals_of(Theme::Dark, visuals(&DARK, true));
    ctx.all_styles_mut(|s| {
        s.text_styles = [
            (TextStyle::Small, body(12.5)),
            (TextStyle::Body, body(15.0)),
            (TextStyle::Button, strong_font(14.5)),
            (TextStyle::Heading, heading(28.0)),
            (TextStyle::Monospace, FontId::new(14.0, FontFamily::Monospace)),
        ]
        .into();
        s.spacing.item_spacing = egui::vec2(10.0, 10.0);
        s.spacing.button_padding = egui::vec2(16.0, 9.0);
        s.spacing.interact_size.y = 36.0;
        s.spacing.icon_width = 18.0;
        s.spacing.icon_width_inner = 10.0;
        s.spacing.icon_spacing = 8.0;
        s.spacing.text_edit_width = 280.0;
        s.spacing.window_margin = Margin::same(20);
        s.spacing.menu_margin = Margin::same(8);
        s.spacing.scroll.bar_width = 8.0;
        s.spacing.scroll.floating = true;
        s.interaction.selectable_labels = false;
        s.animation_time = 0.15;
    });
    ctx.options_mut(|o| o.theme_preference = egui::ThemePreference::System);
}

fn visuals(p: &Palette, dark: bool) -> Visuals {
    let mut v = if dark { Visuals::dark() } else { Visuals::light() };
    let r = CornerRadius::same(10);
    v.panel_fill = p.bg;
    v.window_fill = p.surface;
    v.window_stroke = Stroke::new(1.0, p.border);
    v.window_corner_radius = CornerRadius::same(18);
    v.window_shadow = Shadow {
        offset: [0, 12],
        blur: 36,
        spread: 0,
        color: p.shadow,
    };
    v.popup_shadow = Shadow {
        offset: [0, 6],
        blur: 18,
        spread: 0,
        color: p.shadow,
    };
    v.menu_corner_radius = CornerRadius::same(12);
    v.faint_bg_color = p.surface2;
    v.extreme_bg_color = p.surface;
    v.text_edit_bg_color = Some(p.surface);
    v.code_bg_color = p.surface2;
    v.hyperlink_color = p.primary;
    v.warn_fg_color = p.warn;
    v.error_fg_color = p.danger;
    v.override_text_color = None;
    v.indent_has_left_vline = false;
    v.selection = Selection {
        bg_fill: p.primary_soft,
        stroke: Stroke::new(1.5, p.primary),
    };
    v.text_cursor.stroke = Stroke::new(2.0, p.primary);

    let w = &mut v.widgets;
    w.noninteractive.bg_fill = p.surface;
    w.noninteractive.weak_bg_fill = p.surface;
    w.noninteractive.bg_stroke = Stroke::new(1.0, p.border);
    w.noninteractive.fg_stroke = Stroke::new(1.0, p.text);
    w.noninteractive.corner_radius = r;

    w.inactive.bg_fill = p.surface2;
    w.inactive.weak_bg_fill = p.surface2;
    w.inactive.bg_stroke = Stroke::new(1.0, p.border);
    w.inactive.fg_stroke = Stroke::new(1.0, p.text);
    w.inactive.corner_radius = r;

    w.hovered.bg_fill = p.surface3;
    w.hovered.weak_bg_fill = p.surface3;
    w.hovered.bg_stroke = Stroke::new(1.0, p.border_strong);
    w.hovered.fg_stroke = Stroke::new(1.5, p.text);
    w.hovered.corner_radius = r;
    w.hovered.expansion = 0.0;

    w.active.bg_fill = p.surface3;
    w.active.weak_bg_fill = p.surface3;
    w.active.bg_stroke = Stroke::new(1.5, p.focus);
    w.active.fg_stroke = Stroke::new(1.5, p.text);
    w.active.corner_radius = r;
    w.active.expansion = 0.0;

    w.open.bg_fill = p.surface3;
    w.open.weak_bg_fill = p.surface3;
    w.open.bg_stroke = Stroke::new(1.0, p.border_strong);
    w.open.fg_stroke = Stroke::new(1.0, p.text);
    w.open.corner_radius = r;
    v
}
