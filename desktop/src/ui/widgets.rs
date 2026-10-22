//! Small building blocks shared by the screens.

use egui::{
    Align2, Color32, CornerRadius, Frame, Margin, Pos2, Rect, Response, RichText, Sense, Shape, Stroke, StrokeKind, Ui,
    Vec2, Widget, epaint::Shadow, pos2, vec2,
};

use super::theme::{self, Palette, palette};

/// A raised surface like the web's `.card`.
pub fn card(ui: &Ui) -> Frame {
    let p = palette(ui.ctx());
    Frame::new()
        .fill(p.surface)
        .stroke(Stroke::new(1.0, p.border))
        .corner_radius(CornerRadius::same(18))
        .inner_margin(Margin::same(20))
        .shadow(Shadow {
            offset: [0, 2],
            blur: 10,
            spread: 0,
            color: p.shadow,
        })
}

/// A flat, tinted panel for secondary content.
pub fn soft_panel(ui: &Ui) -> Frame {
    let p = palette(ui.ctx());
    Frame::new()
        .fill(p.surface2)
        .corner_radius(CornerRadius::same(14))
        .inner_margin(Margin::same(14))
}

pub fn primary_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.on_primary).font(theme::strong_font(15.0)))
            .fill(p.primary)
            .stroke(Stroke::NONE)
            .corner_radius(CornerRadius::same(12))
            .min_size(vec2(0.0, 40.0)),
    )
}

pub fn primary_button_enabled(ui: &mut Ui, enabled: bool, text: &str) -> Response {
    ui.add_enabled_ui(enabled, |ui| primary_button(ui, text)).inner
}

pub fn secondary_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.text).font(theme::strong_font(14.5)))
            .fill(p.surface)
            .stroke(Stroke::new(1.0, p.border_strong))
            .corner_radius(CornerRadius::same(12))
            .min_size(vec2(0.0, 40.0)),
    )
}

/// A text-only button (like a link) for low-emphasis actions.
pub fn quiet_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.primary).font(theme::strong_font(14.0)))
            .frame(false)
            .min_size(vec2(0.0, 32.0)),
    )
}

pub fn small_button(ui: &mut Ui, text: &str) -> Response {
    let p = palette(ui.ctx());
    ui.add(
        egui::Button::new(RichText::new(text).color(p.text).font(theme::strong_font(13.0)))
            .fill(p.surface2)
            .stroke(Stroke::new(1.0, p.border))
            .corner_radius(CornerRadius::same(9))
            .min_size(vec2(0.0, 30.0)),
    )
}

pub fn title(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text).font(theme::heading(30.0)).color(p.text));
}

pub fn subtitle(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text).font(theme::heading(20.0)).color(p.text));
}

pub fn muted(ui: &mut Ui, text: impl Into<String>) -> Response {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text.into()).color(p.text_muted))
}

pub fn faint(ui: &mut Ui, text: impl Into<String>) -> Response {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text.into()).color(p.text_faint).size(13.0))
}

pub fn label_strong(ui: &mut Ui, text: impl Into<String>) -> Response {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text.into()).font(theme::strong_font(15.0)).color(p.text))
}

/// Section label above form fields.
pub fn field_label(ui: &mut Ui, text: &str) {
    let p = palette(ui.ctx());
    ui.label(RichText::new(text).font(theme::strong_font(13.5)).color(p.text_muted));
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tone {
    Neutral,
    Primary,
    Good,
    Warn,
    Bad,
}

impl Tone {
    pub fn colors(self, p: &Palette) -> (Color32, Color32) {
        match self {
            Tone::Neutral => (p.surface3, p.text_muted),
            Tone::Primary => (p.primary_soft, p.primary_soft_text),
            Tone::Good => (p.accent_soft, p.accent_text),
            Tone::Warn => (p.warn_soft, p.warn),
            Tone::Bad => (p.danger_soft, p.danger),
        }
    }
}

/// A rounded pill with a short status.
pub fn chip(ui: &mut Ui, tone: Tone, text: impl Into<String>) -> Response {
    let p = palette(ui.ctx());
    let (bg, fg) = tone.colors(p);
    Frame::new()
        .fill(bg)
        .corner_radius(CornerRadius::same(255))
        .inner_margin(Margin::symmetric(10, 3))
        .show(ui, |ui| {
            ui.label(RichText::new(text.into()).font(theme::strong_font(12.5)).color(fg));
        })
        .response
}

/// Online/offline dot.
pub fn status_dot(ui: &mut Ui, online: bool) -> Response {
    let p = palette(ui.ctx());
    let (rect, resp) = ui.allocate_exact_size(vec2(10.0, 10.0), Sense::hover());
    let c = rect.center();
    if online {
        ui.painter().circle_filled(c, 6.5, p.accent.gamma_multiply(0.22));
        ui.painter().circle_filled(c, 4.0, p.accent);
    } else {
        ui.painter().circle_filled(c, 4.0, p.text_faint);
    }
    resp
}

/// A slim, rounded progress bar in the primary colour.
pub fn progress(ui: &mut Ui, fraction: f32, width: f32, color: Option<Color32>) -> Response {
    let p = palette(ui.ctx());
    let (rect, resp) = ui.allocate_exact_size(vec2(width, 8.0), Sense::hover());
    ui.painter().rect_filled(rect, CornerRadius::same(4), p.surface3);
    let f = fraction.clamp(0.0, 1.0);
    if f > 0.0 {
        let mut r = rect;
        r.set_width((rect.width() * f).max(8.0));
        ui.painter()
            .rect_filled(r, CornerRadius::same(4), color.unwrap_or(p.primary));
    }
    resp
}

/// The kinframe logo (a framed landscape with a sun), painted so it stays crisp at any scale.
pub fn logo(ui: &mut Ui, size: f32) -> Response {
    let (rect, resp) = ui.allocate_exact_size(vec2(size, size), Sense::hover());
    paint_logo(ui, rect);
    resp
}

pub fn paint_logo(ui: &Ui, rect: Rect) {
    let p = palette(ui.ctx());
    let s = rect.width() / 64.0;
    let at = |x: f32, y: f32| pos2(rect.left() + x * s, rect.top() + y * s);
    let painter = ui.painter();
    painter.rect_filled(
        Rect::from_min_max(at(4.0, 8.0), at(60.0, 56.0)),
        CornerRadius::same((12.0 * s) as u8),
        // The frame keeps the light primary in both themes, like the app icon.
        theme::LIGHT.primary,
    );
    painter.rect_filled(
        Rect::from_min_max(at(11.0, 15.0), at(53.0, 49.0)),
        CornerRadius::same((6.0 * s) as u8),
        p.paper,
    );
    painter.circle_filled(at(41.0, 26.0), 5.0 * s, p.sun);
    painter.add(Shape::convex_polygon(
        vec![
            at(11.0, 44.0),
            at(23.0, 31.0),
            at(41.0, 49.0),
            at(15.0, 49.0),
            at(11.5, 46.5),
        ],
        p.hill,
        Stroke::NONE,
    ));
    painter.add(Shape::convex_polygon(
        vec![at(23.0, 49.0), at(38.0, 34.0), at(53.0, 49.0)],
        p.hill,
        Stroke::NONE,
    ));
}

pub fn wordmark(ui: &mut Ui, size: f32) {
    let p = palette(ui.ctx());
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = size * 0.3;
        logo(ui, size);
        ui.label(
            RichText::new("kinframe")
                .font(theme::heading(size * 0.72))
                .color(p.text),
        );
    });
}

/// An avatar circle with initials.
pub fn avatar(ui: &mut Ui, name: &str, size: f32) -> Response {
    let p = palette(ui.ctx());
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    let initials: String = name
        .split_whitespace()
        .filter_map(|w| w.chars().next())
        .take(2)
        .collect::<String>()
        .to_uppercase();
    let fill = if resp.hovered() { p.primary } else { p.primary_soft };
    let fg = if resp.hovered() {
        p.on_primary
    } else {
        p.primary_soft_text
    };
    ui.painter().circle_filled(rect.center(), size / 2.0, fill);
    ui.painter().text(
        rect.center(),
        Align2::CENTER_CENTER,
        if initials.is_empty() { "?".into() } else { initials },
        theme::strong_font(size * 0.38),
        fg,
    );
    resp
}

/// A centred empty state with a big, quiet illustration.
pub fn empty_state(ui: &mut Ui, title_text: &str, body: &str) {
    let p = palette(ui.ctx());
    ui.vertical_centered(|ui| {
        ui.add_space(24.0);
        let (rect, _) = ui.allocate_exact_size(vec2(72.0, 72.0), Sense::hover());
        ui.painter().circle_filled(rect.center(), 36.0, p.surface2);
        paint_logo(ui, rect.shrink(16.0));
        ui.add_space(8.0);
        ui.label(RichText::new(title_text).font(theme::heading(22.0)).color(p.text));
        ui.add(egui::Label::new(RichText::new(body).color(p.text_muted)).wrap());
        ui.add_space(16.0);
    });
}

/// A dashed rounded rectangle (drop zones).
pub fn dashed_rect(ui: &Ui, rect: Rect, color: Color32, radius: f32) {
    let painter = ui.painter();
    let stroke = Stroke::new(1.5, color);
    let r = radius;
    let (l, t, rr, b) = (rect.left(), rect.top(), rect.right(), rect.bottom());
    let lines = [
        [pos2(l + r, t), pos2(rr - r, t)],
        [pos2(rr, t + r), pos2(rr, b - r)],
        [pos2(rr - r, b), pos2(l + r, b)],
        [pos2(l, b - r), pos2(l, t + r)],
    ];
    for [a, c] in lines {
        painter.extend(Shape::dashed_line(&[a, c], stroke, 7.0, 5.0));
    }
    let corners = [
        (pos2(l + r, t + r), 180.0f32),
        (pos2(rr - r, t + r), 270.0),
        (pos2(rr - r, b - r), 0.0),
        (pos2(l + r, b - r), 90.0),
    ];
    for (c, start) in corners {
        let pts: Vec<Pos2> = (0..=8)
            .map(|i| {
                let a = (start + i as f32 * 90.0 / 8.0).to_radians();
                c + vec2(a.cos(), a.sin()) * r
            })
            .collect();
        painter.add(Shape::line(pts, stroke));
    }
}

/// Wraps a widget so keyboard focus shows a clear ring.
pub fn focus_ring(ui: &Ui, resp: &Response, radius: u8) {
    if resp.has_focus() {
        let p = palette(ui.ctx());
        ui.painter().rect_stroke(
            resp.rect.expand(3.0),
            CornerRadius::same(radius),
            Stroke::new(2.0, p.focus),
            StrokeKind::Outside,
        );
    }
}

/// A checkbox-like toggle used on thumbnails.
pub struct Check {
    pub checked: bool,
}

impl Widget for Check {
    fn ui(self, ui: &mut Ui) -> Response {
        let p = palette(ui.ctx());
        let (rect, resp) = ui.allocate_exact_size(vec2(22.0, 22.0), Sense::hover());
        paint_check(ui, rect, self.checked, p);
        resp
    }
}

pub fn paint_check(ui: &Ui, rect: Rect, checked: bool, p: &Palette) {
    let painter = ui.painter();
    if checked {
        painter.circle_filled(rect.center(), rect.width() / 2.0, p.primary);
        let c = rect.center();
        let s = rect.width() / 22.0;
        painter.add(Shape::line(
            vec![
                c + vec2(-5.0, 0.5) * s,
                c + vec2(-1.5, 4.0) * s,
                c + vec2(5.5, -3.5) * s,
            ],
            Stroke::new(2.2 * s, p.on_primary),
        ));
    } else {
        painter.circle_filled(rect.center(), rect.width() / 2.0, Color32::from_black_alpha(70));
        painter.circle_stroke(
            rect.center(),
            rect.width() / 2.0 - 1.0,
            Stroke::new(1.8, Color32::WHITE),
        );
    }
}
