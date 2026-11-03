//! Dropping files onto a frame card, driven through the real UI with demo data (no server, no window).

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

use egui::{HoveredFile, Pos2, pos2, vec2};
use egui_kittest::Harness;
use egui_kittest::kittest::Queryable;
use kinframe_desktop::ui::{App, demo};

#[derive(Debug)]
struct File(PathBuf);

impl egui::DroppedFile for File {
    fn path(&self) -> &Path {
        &self.0
    }
    fn bytes(&self) -> Result<Vec<u8>, String> {
        std::fs::read(&self.0).map_err(|e| e.to_string())
    }
}

fn harness(dir: &Path) -> Harness<'static, Option<App>> {
    let dir = dir.to_path_buf();
    Harness::builder().with_size(vec2(1280.0, 820.0)).build_ui_state(
        move |ui, state: &mut Option<App>| {
            state
                .get_or_insert_with(|| demo::app(ui.ctx(), demo::Screen::Frames, &dir))
                .ui_root(ui);
        },
        None,
    )
}

/// A folder with one photo and one file the import ignores.
fn folder(dir: &Path) -> PathBuf {
    let root = dir.join("Zeeland");
    std::fs::create_dir_all(&root).unwrap();
    std::fs::write(root.join("beach.jpg"), b"jpeg-ish").unwrap();
    std::fs::write(root.join("notes.txt"), b"x").unwrap();
    root
}

/// Drags `path` over the window to `pos` (`None`: the platform reports no pointer).
fn drag(h: &mut Harness<'_, Option<App>>, path: &Path, pos: Option<Pos2>) {
    h.input_mut().hovered_files = vec![HoveredFile {
        path: Some(path.to_path_buf()),
        mime: String::new(),
    }];
    match pos {
        Some(pos) => h.hover_at(pos),
        None => h.remove_cursor(),
    }
    h.run_steps(2);
}

/// `drag`, then let go: the drop is handled on the next step.
fn drag_and_drop(h: &mut Harness<'_, Option<App>>, path: &Path, pos: Option<Pos2>) {
    drag(h, path, pos);
    h.input_mut().hovered_files.clear();
    h.input_mut().dropped_files = vec![Arc::new(File(path.to_path_buf()))];
}

/// Steps until the scan has produced a draft, returning its frames.
fn draft_frames(h: &mut Harness<'_, Option<App>>) -> Vec<String> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        h.step();
        if let Some(d) = h.state().as_ref().and_then(|a| a.draft.as_ref()) {
            assert_eq!(d.files.len(), 1, "only the photo is imported");
            assert_eq!(d.skipped, 1);
            return d.frames.iter().cloned().collect();
        }
        assert!(Instant::now() < deadline, "no draft");
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn dropping_on_a_frame_card_picks_that_frame() {
    let tmp = tempfile::tempdir().unwrap();
    let mut h = harness(tmp.path());
    h.run_steps(3);
    let card = h.get_by_label("Opa in Gent").rect().center();

    let dropped = folder(tmp.path());
    drag(&mut h, &dropped, None);
    // Hovering without a pointer: every card is a target, none is highlighted.
    let app = h.state().as_ref().unwrap();
    assert!(app.files_hovered);
    assert_eq!(app.drop_hot, None);

    h.get_by_label("Drop on a frame to send only there");
    drag_and_drop(&mut h, &dropped, Some(card));
    assert_eq!(h.state().as_ref().unwrap().drop_hot.as_deref(), Some("f2"));
    assert_eq!(draft_frames(&mut h), vec!["f2".to_string()]);
}

#[test]
fn dropping_elsewhere_keeps_the_usual_frames() {
    let tmp = tempfile::tempdir().unwrap();
    let mut h = harness(tmp.path());
    h.run_steps(3);
    let dropped = folder(tmp.path());
    drag_and_drop(&mut h, &dropped, Some(pos2(640.0, 40.0)));
    assert_eq!(h.state().as_ref().unwrap().drop_hot, None);
    // Three frames and none sent to before: nothing is preselected.
    assert!(draft_frames(&mut h).is_empty());
}

#[test]
fn a_drop_without_a_pointer_waits_for_it() {
    let tmp = tempfile::tempdir().unwrap();
    let mut h = harness(tmp.path());
    h.run_steps(3);
    let card = h.get_by_label("Oma's frame").rect().center();
    let dropped = folder(tmp.path());

    // The pointer only turns up after the drop, as on most platforms.
    drag_and_drop(&mut h, &dropped, None);
    h.step();
    assert!(
        h.state().as_ref().unwrap().scanning.is_none(),
        "still waiting for the pointer"
    );
    h.hover_at(card);
    assert_eq!(draft_frames(&mut h), vec!["f1".to_string()]);

    // No pointer at all: the ordinary import, after a moment.
    drag_and_drop(&mut h, &dropped, None);
    h.step();
    std::thread::sleep(Duration::from_millis(350));
    h.step();
    assert!(h.state().as_ref().unwrap().scanning.is_some());
}
