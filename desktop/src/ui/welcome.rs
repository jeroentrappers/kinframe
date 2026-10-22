//! Welcome: pick a server, then sign in with the system browser (or dev auth on development servers).

use egui::{Align, Key, Layout, Margin, RichText, Ui, vec2};

use super::theme::{self, palette};
use super::widgets::*;
use super::{App, AuthState, ServerCheck};
use crate::discovery;
use crate::i18n::{t, tf};

impl App {
    pub(super) fn welcome(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            ui.vertical_centered(|ui| {
                let width = 460.0_f32.min(ui.available_width() - 32.0);
                ui.add_space((ui.available_height() * 0.08).clamp(24.0, 72.0));
                logo(ui, 72.0);
                ui.add_space(10.0);
                ui.allocate_ui(vec2(width + 240.0, 0.0), |ui| {
                    ui.vertical_centered(|ui| {
                        ui.label(
                            RichText::new(t("welcome.title"))
                                .font(theme::heading(34.0))
                                .color(p.text),
                        );
                        ui.add_space(2.0);
                        ui.add(
                            egui::Label::new(RichText::new(t("welcome.lede")).color(p.text_muted).size(16.0)).wrap(),
                        );
                    });
                });
                ui.add_space(20.0);
                ui.allocate_ui(vec2(width, 0.0), |ui| {
                    card(ui).show(ui, |ui| {
                        ui.set_width(width - 40.0);
                        ui.with_layout(Layout::top_down(Align::Min), |ui| match self.auth.clone() {
                            AuthState::Restoring => {
                                ui.horizontal(|ui| {
                                    ui.spinner();
                                    muted(ui, t("welcome.restoring"));
                                });
                            }
                            AuthState::Waiting { url } => self.waiting(ui, url),
                            _ => self.server_and_sign_in(ui),
                        });
                    });
                });
                ui.add_space(16.0);
                faint(ui, t("welcome.footer"));
                ui.add_space(24.0);
            });
        });
    }

    fn server_and_sign_in(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        field_label(ui, t("welcome.serverLabel"));
        let before = (self.custom_server, self.server_input.clone());
        ui.radio_value(
            &mut self.custom_server,
            false,
            RichText::new(t("welcome.defaultServer")).color(p.text),
        )
        .on_hover_text(discovery::DEFAULT_SERVER);
        if !self.custom_server {
            ui.indent("default-hint", |ui| faint(ui, t("welcome.defaultServerHint")));
        }
        ui.radio_value(
            &mut self.custom_server,
            true,
            RichText::new(t("welcome.ownServer")).color(p.text),
        );
        if self.custom_server {
            ui.indent("own", |ui| {
                ui.horizontal(|ui| {
                    let r = ui.add(
                        egui::TextEdit::singleline(&mut self.server_input)
                            .hint_text("frames.example.org")
                            .desired_width(ui.available_width() - 96.0)
                            .margin(Margin::symmetric(10, 8)),
                    );
                    if r.lost_focus() && ui.input(|i| i.key_pressed(Key::Enter)) {
                        self.check_server();
                    }
                    if small_button(ui, t("welcome.check")).clicked() {
                        self.check_server();
                    }
                });
            });
        }
        if before != (self.custom_server, self.server_input.clone()) {
            self.server_check = ServerCheck::Idle;
        }
        ui.add_space(4.0);
        match &self.server_check {
            ServerCheck::Idle => {}
            ServerCheck::Checking => {
                ui.horizontal(|ui| {
                    ui.spinner();
                    muted(ui, t("welcome.checking"));
                });
            }
            ServerCheck::Ok(d) => {
                ui.horizontal(|ui| {
                    chip(ui, Tone::Good, "✓");
                    label_strong(ui, &d.name);
                    faint(ui, tf("welcome.version", &[("version", &d.version)]));
                });
            }
            ServerCheck::Err(e) => {
                ui.horizontal_wrapped(|ui| {
                    chip(ui, Tone::Bad, t("welcome.serverError"));
                    ui.label(RichText::new(e).color(p.danger));
                });
            }
        }
        ui.add_space(10.0);

        let checked = match &self.server_check {
            ServerCheck::Ok(d) => Some((**d).clone()),
            _ => None,
        };
        let dev = checked.as_ref().or(self.discovery.as_ref()).is_some_and(|d| d.dev_auth);
        let no_desktop = checked.as_ref().is_some_and(|d| !d.dev_auth && !d.logto_available());
        if no_desktop {
            ui.add(egui::Label::new(RichText::new(t("welcome.noDesktopSupport")).color(p.warn)).wrap());
        }
        let busy = self.server_check == ServerCheck::Checking;
        let w = ui.available_width();
        if ui
            .add_enabled_ui(!busy && !no_desktop, |ui| {
                ui.add_sized(vec2(w, 44.0), |ui: &mut Ui| primary_button(ui, t("welcome.signIn")))
            })
            .inner
            .clicked()
        {
            self.sign_in();
        }
        ui.add_space(2.0);
        ui.vertical_centered(|ui| faint(ui, t("welcome.signInHint")));

        if dev {
            ui.add_space(8.0);
            soft_panel(ui).show(ui, |ui| {
                ui.set_width(ui.available_width());
                field_label(ui, t("welcome.devTitle"));
                faint(ui, t("welcome.devBody"));
                ui.add(
                    egui::TextEdit::singleline(&mut self.dev_email)
                        .hint_text(t("welcome.devEmail"))
                        .desired_width(f32::INFINITY),
                );
                ui.add(
                    egui::TextEdit::singleline(&mut self.dev_name)
                        .hint_text(t("welcome.devName"))
                        .desired_width(f32::INFINITY),
                );
                let ok = self.dev_email.contains('@');
                if ui
                    .add_enabled_ui(ok, |ui| secondary_button(ui, t("welcome.devSignIn")))
                    .inner
                    .clicked()
                {
                    if let Some(d) = checked.clone() {
                        self.adopt_server(d);
                    }
                    self.dev_sign_in();
                }
            });
        }
        if let Some(e) = &self.auth_error {
            ui.add_space(6.0);
            ui.add(egui::Label::new(RichText::new(e).color(p.danger)).wrap());
        }
    }

    fn waiting(&mut self, ui: &mut Ui, url: Option<url::Url>) {
        let p = palette(ui.ctx());
        ui.horizontal(|ui| {
            ui.spinner();
            ui.label(
                RichText::new(t("welcome.waitingTitle"))
                    .font(theme::heading(20.0))
                    .color(p.text),
            );
        });
        ui.add(egui::Label::new(RichText::new(t("welcome.waitingBody")).color(p.text_muted)).wrap());
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            if let Some(u) = url
                && secondary_button(ui, t("welcome.openAgain")).clicked()
            {
                let _ = webbrowser::open(u.as_str());
            }
            if quiet_button(ui, t("common.cancel")).clicked() {
                self.cancel_sign_in();
            }
        });
    }
}
