//! History: recent sends with per-frame delivered/seen counts and reactions, kept live by the websocket.

use chrono::{DateTime, Local, Utc};
use egui::{Align, CornerRadius, Layout, RichText, Sense, Ui, vec2};

use super::frames::page;
use super::theme::{self, palette};
use super::thumbs::Source;
use super::widgets::*;
use super::{App, Tab};
use crate::i18n::{t, tf, tn};
use crate::model::Send;

impl App {
    pub(super) fn history_tab(&mut self, ui: &mut Ui) {
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            page(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        title(ui, t("history.title"));
                        muted(ui, t("history.lede"));
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if small_button(ui, t("common.refresh")).clicked() {
                            self.load_history(false);
                        }
                        if self.live_connected {
                            ui.label(
                                RichText::new(t("history.live"))
                                    .color(palette(ui.ctx()).accent_text)
                                    .size(13.5),
                            )
                            .on_hover_text(t("history.liveHint"));
                            status_dot(ui, true);
                        }
                    });
                });
                ui.add_space(12.0);
                if !self.history_loaded {
                    match self.history_error.clone() {
                        Some(e) => {
                            ui.label(RichText::new(e).color(palette(ui.ctx()).danger));
                        }
                        None => {
                            ui.spinner();
                        }
                    }
                    return;
                }
                if self.history.is_empty() {
                    card(ui).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        empty_state(ui, t("history.emptyTitle"), t("history.emptyBody"));
                        ui.vertical_centered(|ui| {
                            if primary_button(ui, t("nav.sendPhotos")).clicked() {
                                self.tab = Tab::Send;
                            }
                        });
                    });
                    return;
                }
                let sends = self.history.clone();
                for s in &sends {
                    self.send_card(ui, s);
                    ui.add_space(4.0);
                }
                if self.history_cursor.is_some() {
                    ui.vertical_centered(|ui| {
                        if self.history_loading {
                            ui.spinner();
                        } else if secondary_button(ui, t("common.loadMore")).clicked() {
                            self.load_history(true);
                        }
                    });
                }
            });
        });
    }

    fn send_card(&mut self, ui: &mut Ui, s: &Send) {
        let p = palette(ui.ctx());
        card(ui).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal_top(|ui| {
                // Thumbnails strip.
                let shown: Vec<_> = s.items.iter().take(4).collect();
                let size = 64.0;
                for item in &shown {
                    let (r, _) = ui.allocate_exact_size(vec2(size, size), Sense::hover());
                    let tex = item.thumb_url.clone().and_then(|u| self.thumb(&Source::Url(u)));
                    match tex {
                        Some(tex) => super::import::paint_cover(ui, r, &tex, 10),
                        None => {
                            ui.painter().rect_filled(r, CornerRadius::same(10), p.surface3);
                            if item.kind == "video" {
                                ui.painter().text(
                                    r.center(),
                                    egui::Align2::CENTER_CENTER,
                                    "▶",
                                    theme::strong_font(16.0),
                                    p.text_faint,
                                );
                            }
                        }
                    }
                }
                if s.items.len() > shown.len() {
                    let (r, _) = ui.allocate_exact_size(vec2(size, size), Sense::hover());
                    ui.painter().rect_filled(r, CornerRadius::same(10), p.surface2);
                    ui.painter().text(
                        r.center(),
                        egui::Align2::CENTER_CENTER,
                        format!("+{}", s.items.len() - shown.len()),
                        theme::strong_font(15.0),
                        p.text_muted,
                    );
                }
                ui.add_space(8.0);
                ui.vertical(|ui| {
                    ui.spacing_mut().item_spacing.y = 4.0;
                    ui.horizontal(|ui| {
                        let mut what = vec![];
                        if s.photos() > 0 {
                            what.push(tn("history.photos", s.photos() as u64, &[]));
                        }
                        if s.videos() > 0 {
                            what.push(tn("history.videos", s.videos() as u64, &[]));
                        }
                        label_strong(ui, what.join(" · "));
                        faint(ui, when(s.created_at));
                        let processing = s.items.iter().filter(|i| i.status == "processing").count();
                        let failed = s.items.iter().filter(|i| i.status == "failed").count();
                        if processing > 0 {
                            chip(ui, Tone::Primary, t("history.processing"));
                        }
                        if failed > 0 {
                            chip(ui, Tone::Bad, tn("history.failed", failed as u64, &[]));
                        }
                        ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                            for (r, n) in s.reactions() {
                                reaction_chip(ui, &r, n).on_hover_text(t(&format!("reaction.{r}")));
                            }
                        });
                    });
                    if let Some(c) = s.items.iter().find_map(|i| i.caption.clone()) {
                        ui.label(RichText::new(format!("“{c}”")).color(p.text_muted).italics());
                    }
                    for f in &s.frames {
                        ui.horizontal(|ui| {
                            status_dot(ui, f.online.unwrap_or(false));
                            ui.label(RichText::new(&f.frame_name).color(p.text).size(14.5));
                            let total = f.total();
                            let delivered = f.delivered();
                            let seen = f.seen();
                            let (tone, text) = if total > 0 && seen == total {
                                (Tone::Good, t("history.allSeen").to_string())
                            } else if total > 0 && delivered == total {
                                (Tone::Good, t("history.allDelivered").to_string())
                            } else if delivered > 0 {
                                (
                                    Tone::Primary,
                                    tf("history.deliveredOf", &[("done", &delivered), ("total", &total)]),
                                )
                            } else {
                                (Tone::Neutral, t("history.onTheWay").to_string())
                            };
                            chip(ui, tone, text);
                            if seen > 0 && seen < total {
                                faint(ui, tf("history.seenOf", &[("done", &seen), ("total", &total)]));
                            }
                            if f.failed() > 0 {
                                ui.label(
                                    RichText::new(tn("history.failed", f.failed() as u64, &[]))
                                        .color(p.danger)
                                        .size(13.0),
                                );
                            }
                            let replies: Vec<String> = f
                                .placements
                                .iter()
                                .flat_map(|pl| pl.replies.iter())
                                .map(|r| t(&format!("reply.{r}")).to_string())
                                .collect();
                            if let Some(last) = replies.last() {
                                chip(ui, Tone::Good, format!("“{last}”"));
                            }
                        });
                    }
                });
            });
        });
    }
}

/// A reaction with its count. The icons are painted: egui can't draw colour emoji fonts, and monochrome emoji
/// look out of place next to the warm palette.
fn reaction_chip(ui: &mut Ui, reaction: &str, n: usize) -> egui::Response {
    let p = palette(ui.ctx());
    egui::Frame::new()
        .fill(p.primary_soft)
        .corner_radius(CornerRadius::same(255))
        .inner_margin(egui::Margin::symmetric(10, 4))
        .show(ui, |ui| {
            ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                ui.spacing_mut().item_spacing.x = 6.0;
                let (r, _) = ui.allocate_exact_size(vec2(16.0, 16.0), Sense::hover());
                paint_reaction(ui, r, reaction);
                ui.label(
                    RichText::new(n.to_string())
                        .font(theme::strong_font(13.0))
                        .color(p.primary_soft_text),
                );
            });
        })
        .response
}

pub(super) fn paint_reaction(ui: &Ui, r: egui::Rect, reaction: &str) {
    use egui::{Color32, Shape, Stroke, pos2};
    let painter = ui.painter();
    let c = r.center();
    let s = r.width() / 16.0;
    if reaction == "love" {
        let red = Color32::from_rgb(0xe0, 0x4f, 0x5f);
        painter.circle_filled(c + vec2(-3.4, -2.0) * s, 4.0 * s, red);
        painter.circle_filled(c + vec2(3.4, -2.0) * s, 4.0 * s, red);
        painter.add(Shape::convex_polygon(
            vec![
                c + vec2(-7.2, -0.6) * s,
                c + vec2(7.2, -0.6) * s,
                c + vec2(0.0, 7.0) * s,
            ],
            red,
            Stroke::NONE,
        ));
        return;
    }
    let face = Color32::from_rgb(0xf6, 0xc3, 0x4a);
    let ink = Color32::from_rgb(0x5a, 0x3a, 0x12);
    painter.circle_filled(c, 7.5 * s, face);
    let eye = |dx: f32| c + vec2(dx, -2.0) * s;
    match reaction {
        "laugh" => {
            for dx in [-2.8, 2.8] {
                painter.add(Shape::line(
                    vec![
                        eye(dx) + vec2(-1.4, 0.8) * s,
                        eye(dx) + vec2(0.0, -0.6) * s,
                        eye(dx) + vec2(1.4, 0.8) * s,
                    ],
                    Stroke::new(1.2 * s, ink),
                ));
            }
            let mouth: Vec<_> = (0..=8)
                .map(|i| {
                    let a = std::f32::consts::PI * i as f32 / 8.0;
                    c + vec2(-a.cos() * 4.0, 1.5 + a.sin() * 3.6) * s
                })
                .collect();
            painter.add(Shape::convex_polygon(mouth, ink, Stroke::NONE));
        }
        "wow" => {
            painter.circle_filled(eye(-2.8), 1.1 * s, ink);
            painter.circle_filled(eye(2.8), 1.1 * s, ink);
            painter.circle_filled(c + vec2(0.0, 3.2) * s, 2.0 * s, ink);
        }
        _ => {
            painter.circle_filled(eye(-2.8), 1.1 * s, ink);
            painter.circle_filled(eye(2.8), 1.1 * s, ink);
            let mouth: Vec<_> = (0..=8)
                .map(|i| {
                    let a = std::f32::consts::PI * i as f32 / 8.0;
                    pos2(c.x - a.cos() * 3.2 * s, c.y + (4.6 - a.sin() * 2.2) * s)
                })
                .collect();
            painter.add(Shape::line(mouth, Stroke::new(1.3 * s, ink)));
            painter.circle_filled(c + vec2(4.6, 1.0) * s, 1.2 * s, Color32::from_rgb(0x6c, 0xb4, 0xee));
        }
    }
}

/// "Today 14:05", "Yesterday 09:12", or the date.
fn when(t_utc: DateTime<Utc>) -> String {
    let local = t_utc.with_timezone(&Local);
    let today = Local::now().date_naive();
    let time = local.format("%H:%M").to_string();
    if local.date_naive() == today {
        tf("history.todayAt", &[("time", &time)])
    } else if Some(local.date_naive()) == today.pred_opt() {
        tf("history.yesterdayAt", &[("time", &time)])
    } else {
        local.format("%Y-%m-%d %H:%M").to_string()
    }
}
