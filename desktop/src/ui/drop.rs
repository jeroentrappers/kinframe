//! Dropping files onto a frame. While files hover over the window, every frame you can send to (cards on the
//! Frames tab, rows in the Send picker) is a drop target; dropping on one scans as usual, with only that frame
//! picked. Anywhere else, a drop starts the ordinary import.
//!
//! egui has no per-widget drop events: screens record their targets' rects while laying out, and the next frame
//! hit-tests the pointer against them. winit doesn't move the pointer during a drag on most platforms, so the
//! position at the drop can be missing; a drop that misses every target waits briefly for the first pointer move
//! after it (the OS usually sends one straight away) before falling back to the ordinary import.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use egui::{CornerRadius, Pos2, Rect, Stroke, StrokeKind, Ui, vec2};

use super::theme::{self, palette};
use super::widgets::dashed_rect;
use super::{App, AuthState};
use crate::i18n::tf;

/// How long a drop that missed every target waits for a fresh pointer position.
const SETTLE: Duration = Duration::from_millis(300);

/// A frame's area on screen, recorded during layout.
#[derive(Debug, Clone, PartialEq)]
pub struct DropTarget {
    pub frame: String,
    pub rect: Rect,
}

/// Dropped paths whose target isn't known yet.
pub struct PendingDrop {
    paths: Vec<PathBuf>,
    at: Instant,
}

/// What to do with a pending drop this frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Settle<'a> {
    Wait,
    Frame(&'a str),
    Anywhere,
}

/// The frame under `pos`. Later targets were drawn on top, so they win.
pub fn target_at(targets: &[DropTarget], pos: Option<Pos2>) -> Option<&str> {
    let pos = pos?;
    targets
        .iter()
        .rev()
        .find(|t| t.rect.contains(pos))
        .map(|t| t.frame.as_str())
}

/// A hit sends to that frame. A miss counts once the pointer position is known to be current (it has moved since
/// the drop) or once we've waited long enough.
pub fn settle(hit: Option<&str>, pointer_known: bool, waited: Duration) -> Settle<'_> {
    match hit {
        Some(f) => Settle::Frame(f),
        None if pointer_known || waited >= SETTLE => Settle::Anywhere,
        None => Settle::Wait,
    }
}

impl App {
    /// Start of a frame: works out the hovered target from last frame's rects and handles dropped files.
    pub(super) fn handle_drops(&mut self, ctx: &egui::Context) {
        let targets = std::mem::take(&mut self.drop_targets);
        let (hovering, hover, moved, dropped) = ctx.input(|i| {
            let moved = i.events.iter().rev().find_map(|e| match e {
                egui::Event::PointerMoved(p) => Some(*p),
                _ => None,
            });
            let dropped: Vec<PathBuf> = i
                .raw
                .dropped_files
                .iter()
                .map(|f| f.path().to_path_buf())
                .filter(|p| !p.as_os_str().is_empty())
                .collect();
            (!i.raw.hovered_files.is_empty(), i.pointer.hover_pos(), moved, dropped)
        });
        self.files_hovered = hovering && self.auth == AuthState::SignedIn;
        self.drop_hot = target_at(&targets, hover.filter(|_| self.files_hovered)).map(String::from);
        if self.auth != AuthState::SignedIn {
            self.pending_drop = None;
            return;
        }
        // At the drop itself, trust the position the highlight was drawn with.
        let pos = if dropped.is_empty() {
            moved
        } else {
            self.pending_drop = Some(PendingDrop {
                paths: dropped,
                at: Instant::now(),
            });
            moved.or(hover)
        };
        let Some(pending) = &self.pending_drop else { return };
        // With no frame on screen (the Send tab), where the pointer is doesn't matter: no need to wait for it.
        let known = moved.is_some() || targets.is_empty();
        match settle(target_at(&targets, pos), known, pending.at.elapsed()) {
            Settle::Wait => ctx.request_repaint_after(Duration::from_millis(30)),
            Settle::Frame(f) => {
                let frame = f.to_string();
                if let Some(p) = self.pending_drop.take() {
                    self.scan_to(p.paths, Some(frame));
                }
            }
            Settle::Anywhere => {
                if let Some(p) = self.pending_drop.take() {
                    self.scan(p.paths);
                }
            }
        }
    }

    /// Records the visible part of `rect` (inside `clip`) as the drop area of `frame` for the next frame.
    pub(super) fn add_drop_target(&mut self, frame: &str, rect: Rect, clip: Rect) {
        let rect = rect.intersect(clip);
        if rect.is_positive() {
            self.drop_targets.push(DropTarget {
                frame: frame.to_string(),
                rect,
            });
        }
    }
}

/// Paints a frame card as a drop target: a dashed outline on every card while files hover, filled with "Drop to
/// send to …" on the one under the pointer.
pub(super) fn paint_card_target(ui: &Ui, rect: Rect, name: &str, hot: bool) {
    let p = palette(ui.ctx());
    let painter = ui.painter();
    let radius = CornerRadius::same(18);
    let (font, color) = if hot {
        painter.rect_filled(rect, radius, p.primary_soft);
        painter.rect_stroke(rect, radius, Stroke::new(2.5, p.primary), StrokeKind::Inside);
        (theme::heading(21.0), p.primary_soft_text)
    } else {
        painter.rect_filled(rect, radius, p.surface.gamma_multiply(0.93));
        dashed_rect(ui, rect.shrink(1.0), p.primary, 18.0);
        (theme::strong_font(15.0), p.primary)
    };
    let text = tf("frames.dropToSend", &[("name", &name)]);
    let mut job = egui::text::LayoutJob::simple(text, font, color, (rect.width() - 40.0).max(40.0));
    // Centred lines: the galley is then laid out around x = 0.
    job.halign = egui::Align::Center;
    let galley = painter.layout_job(job);
    painter.galley(rect.center() - vec2(0.0, galley.size().y / 2.0), galley, color);
}

/// Dims a card you can't send to, so the targets stand out.
pub(super) fn paint_card_disabled(ui: &Ui, rect: Rect) {
    let p = palette(ui.ctx());
    ui.painter()
        .rect_filled(rect, CornerRadius::same(18), p.bg.gamma_multiply(0.6));
}

/// Paints the background of a frame row in the Send picker as a drop target (call before the row's content is
/// visible, via a reserved shape).
pub(super) fn row_target_shape(ui: &Ui, rect: Rect, hot: bool) -> egui::Shape {
    let p = palette(ui.ctx());
    let rect = rect.expand2(vec2(6.0, 3.0));
    let radius = CornerRadius::same(8);
    if hot {
        egui::epaint::RectShape::new(
            rect,
            radius,
            p.primary_soft,
            Stroke::new(2.0, p.primary),
            StrokeKind::Inside,
        )
        .into()
    } else {
        egui::epaint::RectShape::new(
            rect,
            radius,
            p.surface2,
            Stroke::new(1.0, p.border_strong),
            StrokeKind::Inside,
        )
        .into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use egui::pos2;

    fn target(frame: &str, min: (f32, f32), max: (f32, f32)) -> DropTarget {
        DropTarget {
            frame: frame.into(),
            rect: Rect::from_min_max(pos2(min.0, min.1), pos2(max.0, max.1)),
        }
    }

    #[test]
    fn hit_testing() {
        let targets = [
            target("a", (0.0, 0.0), (100.0, 100.0)),
            target("b", (120.0, 0.0), (220.0, 100.0)),
            // Overlaps "a" and was drawn later (a picker on top of the page): it wins.
            target("c", (50.0, 50.0), (80.0, 80.0)),
        ];
        assert_eq!(target_at(&targets, Some(pos2(10.0, 10.0))), Some("a"));
        assert_eq!(target_at(&targets, Some(pos2(150.0, 99.0))), Some("b"));
        assert_eq!(target_at(&targets, Some(pos2(60.0, 60.0))), Some("c"));
        assert_eq!(
            target_at(&targets, Some(pos2(110.0, 50.0))),
            None,
            "the gap between cards"
        );
        assert_eq!(target_at(&targets, Some(pos2(10.0, 300.0))), None);
        assert_eq!(target_at(&targets, None), None, "no pointer");
        assert_eq!(target_at(&[], Some(pos2(10.0, 10.0))), None);
    }

    #[test]
    fn settling_a_drop() {
        let now = Duration::ZERO;
        assert_eq!(settle(Some("a"), false, now), Settle::Frame("a"));
        assert_eq!(settle(Some("a"), true, now), Settle::Frame("a"));
        // A miss with a stale or missing pointer waits for the pointer to catch up...
        assert_eq!(settle(None, false, now), Settle::Wait);
        assert_eq!(settle(None, false, SETTLE / 2), Settle::Wait);
        // ...but not forever, and not once the pointer is known to be elsewhere.
        assert_eq!(settle(None, false, SETTLE), Settle::Anywhere);
        assert_eq!(settle(None, true, now), Settle::Anywhere);
    }
}
