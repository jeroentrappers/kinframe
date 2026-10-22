//! Frames: the frames you're a member of, with online state, and "Add a frame" by code.

use egui::{Align, Key, Layout, Margin, RichText, Ui, vec2};

use super::theme::{self, palette};
use super::widgets::*;
use super::{App, RedeemState, Tab, relative_time};
use crate::i18n::{t, tf, tn};
use crate::model::FrameSummary;

impl App {
    pub(super) fn frames_tab(&mut self, ui: &mut Ui) {
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            page(ui, |ui| {
                let online = self.frames.iter().filter(|f| f.online).count();
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        title(ui, t("frames.title"));
                        if self.frames_loaded {
                            muted(
                                ui,
                                tn("frames.summary", self.frames.len() as u64, &[("online", &online)]),
                            );
                        }
                    });
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if small_button(ui, t("common.refresh")).clicked() {
                            self.reload_frames();
                        }
                    });
                });
                ui.add_space(12.0);
                let wide = ui.available_width() > 860.0;
                if wide {
                    ui.horizontal_top(|ui| {
                        let list_w = ui.available_width() - 360.0;
                        ui.allocate_ui_with_layout(vec2(list_w, 0.0), Layout::top_down(Align::Min), |ui| {
                            ui.set_width(list_w);
                            self.frame_list(ui);
                        });
                        ui.add_space(4.0);
                        ui.allocate_ui_with_layout(vec2(340.0, 0.0), Layout::top_down(Align::Min), |ui| {
                            ui.set_width(340.0);
                            self.add_frame_card(ui)
                        });
                    });
                } else {
                    self.add_frame_card(ui);
                    ui.add_space(12.0);
                    self.frame_list(ui);
                }
            });
        });
    }

    fn frame_list(&mut self, ui: &mut Ui) {
        if !self.frames_loaded {
            if let Some(e) = self.frames_error.clone() {
                ui.label(RichText::new(e).color(palette(ui.ctx()).danger));
            } else {
                ui.spinner();
            }
            return;
        }
        if self.frames.is_empty() {
            card(ui).show(ui, |ui| {
                ui.set_width(ui.available_width());
                empty_state(ui, t("frames.emptyTitle"), t("frames.emptyBody"));
            });
            return;
        }
        let cols = ((ui.available_width() + 12.0) / 300.0).floor().max(1.0) as usize;
        let w = (ui.available_width() - 12.0 * (cols as f32 - 1.0)) / cols as f32;
        let frames = self.frames.clone();
        for row in frames.chunks(cols) {
            ui.horizontal_top(|ui| {
                ui.spacing_mut().item_spacing.x = 12.0;
                for f in row {
                    ui.allocate_ui_with_layout(vec2(w, 0.0), Layout::top_down(Align::Min), |ui| {
                        self.frame_card(ui, f, w)
                    });
                }
            });
            ui.add_space(2.0);
        }
    }

    fn frame_card(&mut self, ui: &mut Ui, f: &FrameSummary, w: f32) {
        let p = palette(ui.ctx());
        card(ui).show(ui, |ui| {
            ui.set_width(w - 40.0);
            ui.set_min_height(118.0);
            ui.horizontal(|ui| {
                status_dot(ui, f.online);
                let state = if f.online {
                    t("frames.online").to_string()
                } else {
                    match f.last_seen_at {
                        Some(ts) => tf("frames.lastSeen", &[("when", &relative_time(ts))]),
                        None => t("frames.offline").to_string(),
                    }
                };
                ui.label(
                    RichText::new(state)
                        .size(13.0)
                        .color(if f.online { p.accent_text } else { p.text_faint }),
                );
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    let (tone, label) = match f.role.as_str() {
                        "owner" => (Tone::Primary, t("role.owner")),
                        "admin" => (Tone::Good, t("role.admin")),
                        _ => (Tone::Neutral, t("role.member")),
                    };
                    chip(ui, tone, label);
                });
            });
            ui.add_space(2.0);
            ui.label(RichText::new(&f.name).font(theme::heading(21.0)).color(p.text));
            let mut sub = vec![];
            if let Some(l) = f.location.as_ref().filter(|l| !l.is_empty()) {
                sub.push(l.clone());
            }
            if f.role != "owner" && !f.owner_name.is_empty() {
                sub.push(tf("frames.ownedBy", &[("name", &f.owner_name)]));
            }
            if !sub.is_empty() {
                muted(ui, sub.join(" · "));
            }
            ui.add_space(4.0);
            if f.can_send() {
                if quiet_button(ui, t("frames.sendHere")).clicked() {
                    self.settings.last_frames = vec![f.id.clone()];
                    self.tab = Tab::Send;
                    if let Some(d) = &mut self.draft {
                        d.frames = [f.id.clone()].into();
                    }
                }
            } else {
                faint(ui, t("frames.cannotSend"));
            }
        });
    }

    fn add_frame_card(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        card(ui).show(ui, |ui| {
            ui.set_width(ui.available_width());
            subtitle(ui, t("frames.addTitle"));
            ui.add(egui::Label::new(RichText::new(t("frames.addBody")).color(p.text_muted)).wrap());
            ui.add_space(6.0);
            field_label(ui, t("frames.codeLabel"));
            let before = self.code_input.clone();
            let r = ui.add(
                egui::TextEdit::singleline(&mut self.code_input)
                    .hint_text("1234 5678")
                    .font(egui::FontId::monospace(24.0))
                    .align(egui::Align2::CENTER_CENTER)
                    .desired_width(f32::INFINITY)
                    .margin(Margin::symmetric(12, 10)),
            );
            if self.code_input != before {
                // Format as the frame shows it; accept pasted join links.
                self.code_input = crate::code::format_input(&self.code_input);
                if !matches!(self.redeem, RedeemState::Busy) {
                    self.redeem = RedeemState::Idle;
                }
            }
            faint(ui, t("frames.codeHint"));
            let complete = crate::code::normalize(&self.code_input).is_some();
            let enter = r.lost_focus() && ui.input(|i| i.key_pressed(Key::Enter));
            ui.add_space(4.0);
            let busy = matches!(self.redeem, RedeemState::Busy);
            let w = ui.available_width();
            let clicked = ui
                .add_enabled_ui(complete && !busy, |ui| {
                    ui.add_sized(vec2(w, 42.0), |ui: &mut Ui| primary_button(ui, t("frames.addSubmit")))
                })
                .inner
                .clicked();
            if (clicked || (enter && complete)) && !busy {
                self.redeem_code();
            }
            match self.redeem.clone() {
                RedeemState::Idle => {}
                RedeemState::Busy => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        muted(ui, t("frames.checking"));
                    });
                }
                RedeemState::Err(e) => {
                    ui.add(egui::Label::new(RichText::new(e).color(p.danger)).wrap());
                }
                RedeemState::Done(res) => {
                    let name = res.frame.name.clone();
                    let (title_text, body) = match res.status.as_str() {
                        "claimed" => (
                            t("frames.claimedTitle").to_string(),
                            t("frames.claimedBody").to_string(),
                        ),
                        "pending" => (
                            t("frames.pendingTitle").to_string(),
                            tf("frames.pendingBody", &[("name", &name)]),
                        ),
                        "already_member" => (
                            t("frames.alreadyTitle").to_string(),
                            tf("frames.alreadyBody", &[("name", &name)]),
                        ),
                        _ => (
                            tf("frames.joinedTitle", &[("name", &name)]),
                            tf("frames.joinedBody", &[("owner", &res.frame.owner_name)]),
                        ),
                    };
                    soft_panel(ui).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        label_strong(ui, title_text);
                        ui.add(egui::Label::new(RichText::new(body).color(p.text_muted)).wrap());
                    });
                }
            }
        });
    }
}

/// Centres page content with a comfortable maximum width.
pub(super) fn page(ui: &mut Ui, add: impl FnOnce(&mut Ui)) {
    let avail = ui.available_width();
    let w = (avail - 56.0).min(1180.0);
    let side = ((avail - w) / 2.0).max(28.0);
    ui.add_space(8.0);
    ui.horizontal_top(|ui| {
        ui.spacing_mut().item_spacing.x = 0.0;
        ui.add_space(side);
        ui.vertical(|ui| {
            ui.set_width(w);
            ui.spacing_mut().item_spacing.x = 10.0;
            add(ui);
        });
    });
    ui.add_space(32.0);
}
