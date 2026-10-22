//! Send: bulk import. Drop or pick folders, preview and select, choose frames and a caption, then follow the
//! queue's progress (pause, resume, retry).

use egui::{
    Align, Align2, Color32, CornerRadius, Frame, Layout, Margin, Rect, RichText, Sense, Stroke, StrokeKind, Ui, vec2,
};
use uuid::Uuid;

use super::drop;
use super::frames::page;
use super::theme::{self, Palette, palette};
use super::thumbs::{self, Source};
use super::widgets::*;
use super::{App, Tab, human_bytes, relative_time};
use crate::i18n::{t, tf, tn};
use crate::queue::{BatchState, Counts, ItemState, JobItem};
use crate::scan::MediaKind;

const TILE: f32 = 148.0;
const GAP: f32 = 10.0;

/// What the UI needs from a job, copied out of the shared queue.
struct JobView {
    id: Uuid,
    title: String,
    frames: Vec<String>,
    created_at: chrono::DateTime<chrono::Utc>,
    paused: bool,
    done: bool,
    counts: Counts,
    failed_sends: usize,
    items: Option<Vec<JobItem>>,
}

enum JobAction {
    Pause(Uuid, bool),
    Retry(Uuid),
    Remove(Uuid),
    Toggle(Uuid),
}

impl App {
    pub(super) fn send_tab(&mut self, ui: &mut Ui) {
        if self.draft.is_some() {
            self.draft_view(ui);
            return;
        }
        egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
            page(ui, |ui| {
                title(ui, t("import.title"));
                muted(ui, t("import.lede"));
                ui.add_space(12.0);
                if self.frames_loaded && !self.frames.iter().any(|f| f.can_send()) {
                    card(ui).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        empty_state(ui, t("import.noFramesTitle"), t("import.noFramesBody"));
                        ui.vertical_centered(|ui| {
                            if primary_button(ui, t("frames.addTitle")).clicked() {
                                self.tab = Tab::Frames;
                            }
                        });
                    });
                    ui.add_space(16.0);
                } else if let Some(s) = &self.scanning {
                    let n = s.found.load(std::sync::atomic::Ordering::Relaxed);
                    let cancel = s.cancel.clone();
                    card(ui).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.horizontal(|ui| {
                            ui.spinner();
                            label_strong(ui, t("import.scanning"));
                            muted(ui, tn("import.found", n as u64, &[]));
                            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                if quiet_button(ui, t("common.cancel")).clicked() {
                                    cancel.store(true, std::sync::atomic::Ordering::Relaxed);
                                    self.scanning = None;
                                }
                            });
                        });
                    });
                } else {
                    self.drop_zone(ui);
                }
                ui.add_space(20.0);
                self.jobs_list(ui);
            });
        });
    }

    fn drop_zone(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        let hovering = ui.ctx().input(|i| !i.raw.hovered_files.is_empty());
        let fill = if hovering { p.primary_soft } else { p.surface };
        // Smaller once there are imports below, so the queue stays in view.
        let compact = self.with_queue(|q| !q.jobs.is_empty());
        let resp = Frame::new()
            .fill(fill)
            .corner_radius(CornerRadius::same(22))
            .inner_margin(Margin::symmetric(24, if compact { 20 } else { 34 }))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.vertical_centered(|ui| {
                    if !compact {
                        let (r, _) = ui.allocate_exact_size(vec2(64.0, 64.0), Sense::hover());
                        ui.painter()
                            .circle_filled(r.center(), 32.0, if hovering { p.surface } else { p.primary_soft });
                        paint_logo(ui, r.shrink(14.0));
                        ui.add_space(6.0);
                    }
                    let headline = if hovering {
                        t("import.dropNow")
                    } else {
                        t("import.dropTitle")
                    };
                    ui.label(RichText::new(headline).font(theme::heading(24.0)).color(p.text));
                    ui.add(egui::Label::new(RichText::new(t("import.dropBody")).color(p.text_muted)).wrap());
                    ui.add_space(10.0);
                    ui.horizontal(|ui| {
                        let total = 2.0 * 170.0 + 10.0;
                        ui.add_space(((ui.available_width() - total) / 2.0).max(0.0));
                        if ui
                            .add_sized(vec2(170.0, 42.0), |ui: &mut Ui| {
                                primary_button(ui, t("import.chooseFolder"))
                            })
                            .on_hover_text(if cfg!(target_os = "macos") { "⌘O" } else { "Ctrl+O" })
                            .clicked()
                        {
                            self.pick_folder();
                        }
                        if ui
                            .add_sized(vec2(170.0, 42.0), |ui: &mut Ui| {
                                secondary_button(ui, t("import.chooseFiles"))
                            })
                            .clicked()
                        {
                            self.pick_files();
                        }
                    });
                    ui.add_space(4.0);
                    faint(ui, t("import.dropFoot"));
                });
            })
            .response;
        dashed_rect(
            ui,
            resp.rect.shrink(1.0),
            if hovering { p.primary } else { p.border_strong },
            22.0,
        );
    }

    fn jobs_list(&mut self, ui: &mut Ui) {
        let expanded = self.expanded_job;
        let jobs: Vec<JobView> = self.with_queue(|q| {
            q.jobs
                .iter()
                .rev()
                .map(|j| JobView {
                    id: j.id,
                    title: j.title.clone(),
                    frames: j.frames.iter().map(|f| f.name.clone()).collect(),
                    created_at: j.created_at,
                    paused: j.paused,
                    done: j.is_done(),
                    counts: j.counts(),
                    failed_sends: j
                        .batches
                        .iter()
                        .filter(|b| matches!(b.state, BatchState::Failed { .. }))
                        .count(),
                    items: (expanded == Some(j.id)).then(|| j.items.clone()),
                })
                .collect()
        });
        if jobs.is_empty() {
            return;
        }
        ui.horizontal(|ui| {
            subtitle(ui, t("import.queueTitle"));
            let active = jobs.iter().any(|j| !j.done && !j.paused);
            let paused = jobs.iter().any(|j| !j.done && j.paused);
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                let toggle = if active {
                    small_button(ui, t("import.pauseAll")).clicked().then_some(true)
                } else if paused {
                    small_button(ui, t("import.resumeAll")).clicked().then_some(false)
                } else {
                    None
                };
                if let (Some(pause), Some(e)) = (toggle, &self.engine) {
                    e.set_all_paused(pause);
                }
            });
        });
        ui.add_space(4.0);
        let mut actions = vec![];
        for job in &jobs {
            self.job_card(ui, job, &mut actions);
            ui.add_space(6.0);
        }
        for a in actions {
            match a {
                JobAction::Pause(id, p) => {
                    if let Some(e) = &self.engine {
                        e.set_paused(id, p);
                    }
                }
                JobAction::Retry(id) => {
                    if let Some(e) = &self.engine {
                        e.retry_failed(id);
                    }
                }
                JobAction::Remove(id) => {
                    if let Some(e) = &self.engine {
                        e.remove_job(id);
                    }
                    if self.expanded_job == Some(id) {
                        self.expanded_job = None;
                    }
                }
                JobAction::Toggle(id) => {
                    self.expanded_job = if self.expanded_job == Some(id) { None } else { Some(id) };
                }
            }
        }
    }

    fn job_card(&mut self, ui: &mut Ui, job: &JobView, actions: &mut Vec<JobAction>) {
        let p = palette(ui.ctx());
        let c = job.counts;
        let signed_in = self.engine.is_some() || self.demo_queue.is_some();
        card(ui).show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(if job.title.is_empty() {
                        t("import.untitled")
                    } else {
                        &job.title
                    })
                    .font(theme::heading(20.0))
                    .color(p.text),
                );
                let (tone, label) = if job.done && c.failed > 0 {
                    (Tone::Warn, t("import.stateDoneWithErrors"))
                } else if job.done {
                    (Tone::Good, t("import.stateDone"))
                } else if job.paused {
                    (Tone::Neutral, t("import.statePaused"))
                } else if !signed_in {
                    (Tone::Warn, t("import.stateWaiting"))
                } else {
                    (Tone::Primary, t("import.stateSending"))
                };
                chip(ui, tone, label);
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    let details = if job.items.is_some() {
                        t("import.hideFiles")
                    } else {
                        t("import.showFiles")
                    };
                    if quiet_button(ui, details).clicked() {
                        actions.push(JobAction::Toggle(job.id));
                    }
                    if (job.done || job.paused)
                        && small_button(ui, t("import.remove"))
                            .on_hover_text(t("import.removeHint"))
                            .clicked()
                    {
                        actions.push(JobAction::Remove(job.id));
                    }
                    if c.failed > 0 && small_button(ui, t("import.retryFailed")).clicked() {
                        actions.push(JobAction::Retry(job.id));
                    }
                    if !job.done {
                        let label = if job.paused {
                            t("import.resume")
                        } else {
                            t("import.pause")
                        };
                        if small_button(ui, label).clicked() {
                            actions.push(JobAction::Pause(job.id, !job.paused));
                        }
                    }
                });
            });
            let to = tf("import.to", &[("frames", &job.frames.join(", "))]);
            faint(
                ui,
                format!(
                    "{to} · {} · {} · {}",
                    tn("import.files", c.total as u64, &[]),
                    human_bytes(c.bytes_total),
                    relative_time(job.created_at)
                ),
            );
            ui.add_space(4.0);
            let w = ui.available_width();
            let color = if job.done && c.failed == 0 {
                Some(p.accent)
            } else if job.paused {
                Some(p.text_faint)
            } else {
                None
            };
            progress(ui, if job.done { 1.0 } else { c.fraction() }, w, color);
            ui.horizontal_wrapped(|ui| {
                ui.spacing_mut().item_spacing.x = 14.0;
                label_strong(ui, tf("import.sentOf", &[("sent", &c.sent), ("total", &c.total)]));
                if c.duplicate > 0 {
                    muted(ui, tn("import.skippedDuplicates", c.duplicate as u64, &[]));
                }
                if c.failed > 0 {
                    ui.label(RichText::new(tn("import.failedCount", c.failed as u64, &[])).color(p.danger));
                }
                if !job.done {
                    muted(
                        ui,
                        tf(
                            "import.bytesOf",
                            &[
                                ("done", &human_bytes(c.bytes_done)),
                                ("total", &human_bytes(c.bytes_total)),
                            ],
                        ),
                    );
                }
                if job.failed_sends > 0 {
                    ui.label(RichText::new(t("import.sendFailed")).color(p.danger));
                }
            });
            if let Some(items) = &job.items {
                ui.add_space(6.0);
                self.job_files(ui, items);
            }
        });
    }

    fn job_files(&mut self, ui: &mut Ui, items: &[JobItem]) {
        let p = palette(ui.ctx());
        // Problems first, then work in progress, then the rest in order.
        let mut order: Vec<usize> = (0..items.len()).collect();
        order.sort_by_key(|&i| match items[i].state {
            ItemState::Failed => 0,
            ItemState::Hashing | ItemState::Uploading => 1,
            _ => 2,
        });
        let row_h = 46.0;
        Frame::new()
            .fill(p.surface2)
            .corner_radius(CornerRadius::same(12))
            .inner_margin(Margin::symmetric(10, 6))
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .id_salt("job-files")
                    .max_height(360.0)
                    .auto_shrink([false, true])
                    .show_rows(ui, row_h, order.len(), |ui, range| {
                        for &i in &order[range] {
                            let it = &items[i];
                            let size = vec2(ui.available_width(), row_h);
                            ui.allocate_ui_with_layout(size, Layout::left_to_right(Align::Center), |ui| {
                                ui.set_height(row_h);
                                let (r, _) = ui.allocate_exact_size(vec2(36.0, 36.0), Sense::hover());
                                self.paint_thumb(ui, r, &it.path, it.kind, 7);
                                ui.vertical(|ui| {
                                    ui.spacing_mut().item_spacing.y = 2.0;
                                    ui.label(RichText::new(&it.name).color(p.text).size(14.0));
                                    match (&it.error, it.state) {
                                        (Some(e), ItemState::Failed) => {
                                            ui.label(RichText::new(e).color(p.danger).size(12.5));
                                        }
                                        (_, ItemState::Uploading) => {
                                            progress(ui, it.fraction(), 160.0, None);
                                        }
                                        _ => {
                                            if let Some(c) = &it.caption {
                                                faint(ui, format!("“{c}”"));
                                            } else {
                                                faint(ui, human_bytes(it.size));
                                            }
                                        }
                                    }
                                });
                                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                    let (tone, label) = item_state(it);
                                    ui.allocate_ui_with_layout(
                                        vec2(180.0, 28.0),
                                        Layout::right_to_left(Align::Center),
                                        |ui| chip(ui, tone, label),
                                    );
                                });
                            });
                        }
                    });
            });
    }

    /// Paints a square, cropped thumbnail (or a placeholder for videos and HEIC).
    pub(super) fn paint_thumb(&mut self, ui: &Ui, rect: Rect, path: &std::path::Path, kind: MediaKind, radius: u8) {
        let p = palette(ui.ctx());
        let tex = if thumbs::previewable(path) {
            self.thumb(&Source::File(path.to_path_buf()))
        } else {
            None
        };
        match tex {
            Some(tex) => paint_cover(ui, rect, &tex, radius),
            None => {
                ui.painter().rect_filled(rect, CornerRadius::same(radius), p.surface3);
                let label = match kind {
                    MediaKind::Video => "▶",
                    MediaKind::Photo => path
                        .extension()
                        .and_then(|e| e.to_str())
                        .map(|e| {
                            if e.eq_ignore_ascii_case("heic") || e.eq_ignore_ascii_case("heif") {
                                "HEIC"
                            } else {
                                ""
                            }
                        })
                        .unwrap_or(""),
                };
                ui.painter().text(
                    rect.center(),
                    Align2::CENTER_CENTER,
                    label,
                    theme::strong_font((rect.width() * 0.22).clamp(10.0, 26.0)),
                    p.text_faint,
                );
            }
        }
    }

    fn draft_view(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        let sendable: Vec<crate::model::FrameSummary> = self.frames.iter().filter(|f| f.can_send()).cloned().collect();
        let max_items = self
            .discovery
            .as_ref()
            .map_or(200, |d| d.limits.max_items_per_send as usize);
        let mut start = false;
        let mut cancel = false;
        let (hovering, hot) = (self.files_hovered, self.drop_hot.clone());
        let mut rows = vec![];

        egui::Panel::right("draft-side")
            .exact_size(340.0)
            .resizable(false)
            .show_separator_line(false)
            .frame(Frame::new().fill(p.bg).inner_margin(Margin {
                left: 8,
                right: 28,
                top: 8,
                bottom: 20,
            }))
            .show(ui, |ui| {
                let Some(draft) = self.draft.as_mut() else { return };
                egui::ScrollArea::vertical().show(ui, |ui| {
                    card(ui).show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        subtitle(ui, t("import.sendTo"));
                        if hovering {
                            let name = sendable.iter().find(|f| hot.as_deref() == Some(&f.id)).map(|f| &f.name);
                            let hint = match name {
                                Some(name) => tf("frames.dropToSend", &[("name", name)]),
                                None => t("frames.dropHint").into(),
                            };
                            ui.label(RichText::new(hint).color(p.primary).size(13.0));
                        }
                        ui.add_space(2.0);
                        for f in &sendable {
                            let mut on = draft.frames.contains(&f.id);
                            // Reserved now, painted behind the row once its size is known.
                            let bg = ui.painter().add(egui::Shape::Noop);
                            let row = ui.horizontal(|ui| {
                                if ui.checkbox(&mut on, RichText::new(&f.name).color(p.text)).changed() {
                                    if on {
                                        draft.frames.insert(f.id.clone());
                                    } else {
                                        draft.frames.remove(&f.id);
                                    }
                                }
                                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                                    status_dot(ui, f.online);
                                });
                            });
                            if hovering {
                                let shape =
                                    drop::row_target_shape(ui, row.response.rect, hot.as_deref() == Some(&f.id));
                                ui.painter().set(bg, shape);
                            }
                            rows.push((f.id.clone(), row.response.rect, ui.clip_rect()));
                        }
                        if sendable.iter().any(|f| !f.online && draft.frames.contains(&f.id)) {
                            faint(ui, t("import.offlineNote"));
                        }
                        ui.add_space(8.0);
                        subtitle(ui, t("import.caption"));
                        ui.add(
                            egui::TextEdit::singleline(&mut draft.caption_template)
                                .hint_text(t("import.captionHint"))
                                .desired_width(f32::INFINITY)
                                .char_limit(crate::queue::MAX_CAPTION)
                                .margin(Margin::symmetric(10, 8)),
                        );
                        ui.horizontal_wrapped(|ui| {
                            ui.spacing_mut().item_spacing = vec2(6.0, 6.0);
                            for (label, token) in [
                                (t("import.tokenFolder"), "{folder}"),
                                (t("import.tokenDate"), "{date}"),
                                (t("import.tokenFile"), "{filename}"),
                            ] {
                                if small_button(ui, &format!("+ {label}")).clicked() {
                                    if !draft.caption_template.is_empty() && !draft.caption_template.ends_with(' ') {
                                        draft.caption_template.push(' ');
                                    }
                                    draft.caption_template.push_str(token);
                                }
                            }
                        });
                        if let Some(first) = draft
                            .files
                            .iter()
                            .zip(&draft.selected)
                            .find(|(_, s)| **s)
                            .map(|(f, _)| f)
                            && let Some(c) = crate::queue::render_caption(&draft.caption_template, first)
                        {
                            faint(ui, tf("import.captionExample", &[("caption", &c)]));
                        }
                        ui.add_space(10.0);
                        let n = draft.selected_count();
                        let bytes: u64 = draft
                            .files
                            .iter()
                            .zip(&draft.selected)
                            .filter(|(_, s)| **s)
                            .map(|(f, _)| f.size)
                            .sum();
                        soft_panel(ui).show(ui, |ui| {
                            ui.set_width(ui.available_width());
                            label_strong(
                                ui,
                                format!("{} · {}", tn("import.items", n as u64, &[]), human_bytes(bytes)),
                            );
                            let sends = n.div_ceil(max_items.max(1));
                            if sends > 1 {
                                faint(ui, tf("import.batches", &[("sends", &sends), ("max", &max_items)]));
                            }
                            faint(ui, t("import.dedupeNote"));
                        });
                        ui.add_space(10.0);
                        let ok = n > 0 && !draft.frames.is_empty();
                        let w = ui.available_width();
                        if ui
                            .add_enabled_ui(ok, |ui| {
                                ui.add_sized(vec2(w, 44.0), |ui: &mut Ui| {
                                    primary_button(ui, &tn("import.sendN", n as u64, &[]))
                                })
                            })
                            .inner
                            .clicked()
                        {
                            start = true;
                        }
                        if draft.frames.is_empty() {
                            faint(ui, t("import.pickFrame"));
                        }
                        ui.vertical_centered(|ui| {
                            if quiet_button(ui, t("common.cancel")).on_hover_text("Esc").clicked() {
                                cancel = true;
                            }
                        });
                    });
                });
            });

        for (frame, rect, clip) in rows {
            self.add_drop_target(&frame, rect, clip);
        }

        egui::CentralPanel::no_frame().show(ui, |ui| {
            Frame::new()
                .inner_margin(Margin {
                    left: 28,
                    right: 8,
                    top: 8,
                    bottom: 0,
                })
                .show(ui, |ui| self.draft_grid(ui));
        });
        if start {
            self.start_import();
        } else if cancel {
            self.draft = None;
        }
    }

    fn draft_grid(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        let Some(draft) = self.draft.as_mut() else { return };
        let n = draft.files.len();
        let selected = draft.selected_count();
        let photos = draft.files.iter().filter(|f| f.kind == MediaKind::Photo).count();
        let videos = n - photos;
        ui.add(
            egui::TextEdit::singleline(&mut draft.title)
                .font(theme::heading(28.0))
                .frame(Frame::NONE)
                .hint_text(t("import.untitled"))
                .desired_width(ui.available_width()),
        );
        let mut parts = vec![];
        if photos > 0 {
            parts.push(tn("import.photos", photos as u64, &[]));
        }
        if videos > 0 {
            parts.push(tn("import.videos", videos as u64, &[]));
        }
        if let (Some(first), Some(last)) = (draft.files.first(), draft.files.last()) {
            let fmt = |d: chrono::DateTime<chrono::Utc>| d.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string();
            let (a, b) = (fmt(first.taken_at), fmt(last.taken_at));
            parts.push(if a == b { a } else { format!("{a} – {b}") });
        }
        if draft.skipped > 0 {
            parts.push(tn("import.notMedia", draft.skipped as u64, &[]));
        }
        muted(ui, parts.join(" · "));
        ui.add_space(4.0);
        ui.horizontal(|ui| {
            if small_button(ui, t("import.selectAll")).clicked() {
                draft.selected.iter_mut().for_each(|s| *s = true);
            }
            if small_button(ui, t("import.selectNone")).clicked() {
                draft.selected.iter_mut().for_each(|s| *s = false);
            }
            faint(ui, tf("import.selected", &[("n", &selected), ("total", &n)]));
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                faint(ui, t("import.sortNote"))
            });
        });
        ui.add_space(6.0);

        let width = ui.available_width() - 8.0;
        let cols = ((width + GAP) / (TILE + GAP)).floor().max(1.0) as usize;
        let tile = ((width + GAP) / cols as f32 - GAP).min(TILE * 1.3);
        let row_h = tile + 26.0 + GAP;
        let rows = n.div_ceil(cols);
        let files: Vec<_> = draft
            .files
            .iter()
            .map(|f| (f.path.clone(), f.kind, f.taken_at, f.file_name()))
            .collect();
        let mut toggles = vec![];
        let sel = draft.selected.clone();
        egui::ScrollArea::vertical()
            .id_salt("draft-grid")
            .auto_shrink([false, false])
            .show_rows(ui, row_h, rows, |ui, range| {
                for row in range {
                    ui.horizontal(|ui| {
                        ui.spacing_mut().item_spacing.x = GAP;
                        for col in 0..cols {
                            let i = row * cols + col;
                            if i >= n {
                                break;
                            }
                            let (path, kind, taken, name) = &files[i];
                            let (rect, resp) = ui.allocate_exact_size(vec2(tile, tile + 26.0), Sense::click());
                            let img = Rect::from_min_size(rect.min, vec2(tile, tile));
                            self.paint_thumb(ui, img, path, *kind, 12);
                            paint_tile_chrome(ui, img, sel[i], resp.hovered(), *kind, palette(ui.ctx()));
                            focus_ring(ui, &resp, 12);
                            ui.painter().text(
                                img.left_bottom() + vec2(2.0, 6.0),
                                Align2::LEFT_TOP,
                                taken.with_timezone(&chrono::Local).format("%Y-%m-%d").to_string(),
                                theme::body(12.0),
                                p.text_faint,
                            );
                            let resp = resp.on_hover_text(name);
                            if resp.clicked() {
                                toggles.push(i);
                            }
                        }
                    });
                    ui.add_space(GAP);
                }
            });
        if let Some(draft) = self.draft.as_mut() {
            for i in toggles {
                draft.selected[i] = !draft.selected[i];
            }
        }
    }
}

fn item_state(it: &JobItem) -> (Tone, String) {
    match it.state {
        ItemState::Queued if it.retry_at.is_some() => (Tone::Warn, t("item.retrying").into()),
        ItemState::Queued if it.resume.offset > 0 => (Tone::Neutral, t("item.paused").into()),
        ItemState::Queued => (Tone::Neutral, t("item.queued").into()),
        ItemState::Hashing => (Tone::Primary, t("item.checking").into()),
        ItemState::Uploading => (
            Tone::Primary,
            format!("{} {:.0}%", t("item.uploading"), it.fraction() * 100.0),
        ),
        ItemState::Uploaded | ItemState::Batched => (Tone::Primary, t("item.uploaded").into()),
        ItemState::Sent => (Tone::Good, t("item.sent").into()),
        ItemState::Duplicate => (Tone::Neutral, t("item.duplicate").into()),
        ItemState::Failed => (Tone::Bad, t("item.failed").into()),
    }
}

/// Crops a texture to fill `rect` (like CSS `object-fit: cover`).
pub(super) fn paint_cover(ui: &Ui, rect: Rect, tex: &egui::TextureHandle, radius: u8) {
    let [w, h] = tex.size();
    let (w, h) = (w as f32, h as f32);
    let target = rect.width() / rect.height();
    let src = w / h;
    let uv = if src > target {
        let f = target / src;
        Rect::from_min_max(egui::pos2((1.0 - f) / 2.0, 0.0), egui::pos2((1.0 + f) / 2.0, 1.0))
    } else {
        let f = src / target;
        Rect::from_min_max(egui::pos2(0.0, (1.0 - f) / 2.0), egui::pos2(1.0, (1.0 + f) / 2.0))
    };
    egui::Image::from_texture(tex)
        .uv(uv)
        .corner_radius(CornerRadius::same(radius))
        .paint_at(ui, rect);
}

fn paint_tile_chrome(ui: &Ui, rect: Rect, selected: bool, hovered: bool, kind: MediaKind, p: &Palette) {
    let painter = ui.painter();
    if !selected {
        painter.rect_filled(rect, CornerRadius::same(12), p.bg.gamma_multiply(0.55));
    }
    if hovered {
        painter.rect_stroke(
            rect,
            CornerRadius::same(12),
            Stroke::new(2.0, p.border_strong),
            StrokeKind::Inside,
        );
    }
    if selected {
        painter.rect_stroke(
            rect,
            CornerRadius::same(12),
            Stroke::new(2.0, p.primary),
            StrokeKind::Inside,
        );
    }
    let check = Rect::from_min_size(rect.min + vec2(8.0, 8.0), vec2(22.0, 22.0));
    paint_check(ui, check, selected, p);
    if kind == MediaKind::Video {
        let badge = Rect::from_min_size(rect.left_bottom() + vec2(8.0, -28.0), vec2(30.0, 20.0));
        painter.rect_filled(badge, CornerRadius::same(6), Color32::from_black_alpha(140));
        painter.text(
            badge.center(),
            Align2::CENTER_CENTER,
            "▶",
            theme::strong_font(11.0),
            Color32::WHITE,
        );
    }
}
