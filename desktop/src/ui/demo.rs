//! Demo state for screenshots and visual checks: every screen with realistic, made-up data and procedurally
//! drawn "photos". Nothing here talks to a server.

use std::path::PathBuf;
use std::sync::Arc;

use chrono::{Duration, Utc};
use egui::{Color32, ColorImage};

use super::thumbs::Source;
use super::{App, AuthState, Draft, ServerCheck, Tab};
use crate::auth::MemoryStore;
use crate::discovery::Discovery;
use crate::model::{FrameSummary, Item, Permissions, Send, SendFrame, SendPlacement, User};
use crate::queue::{FrameRef, ItemState, Job, Queue};
use crate::scan::{DateSource, MediaKind, ScannedFile};
use crate::store::{Dirs, Settings};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Screen {
    Welcome,
    Frames,
    /// The Frames tab while files are dragged over it (the screenshot tool supplies the files and pointer).
    FramesDrop,
    Draft,
    Progress,
    History,
}

impl Screen {
    pub const ALL: [Screen; 6] = [
        Screen::Welcome,
        Screen::Frames,
        Screen::FramesDrop,
        Screen::Draft,
        Screen::Progress,
        Screen::History,
    ];
    pub fn name(self) -> &'static str {
        match self {
            Screen::Welcome => "welcome",
            Screen::Frames => "frames",
            Screen::FramesDrop => "frames-drop",
            Screen::Draft => "import-preview",
            Screen::Progress => "import-progress",
            Screen::History => "history",
        }
    }
}

fn discovery() -> Discovery {
    crate::discovery::parse(
        serde_json::json!({
            "name": "Trappers family", "version": "0.4.2", "api": "https://kinframe.appmire.be/v1",
            "auth": {"issuer": "https://auth.kinframe.appmire.be/oidc", "desktop_client_id": "desk",
                     "api_resource": "https://kinframe.appmire.be/v1"},
            "limits": {"max_items_per_send": 200, "max_video_seconds": 300}
        })
        .to_string()
        .as_bytes(),
    )
    .expect("demo discovery")
}

fn frame(id: &str, name: &str, loc: &str, owner: &str, role: &str, online: bool, seen_h: i64) -> FrameSummary {
    FrameSummary {
        id: id.into(),
        name: name.into(),
        location: Some(loc.into()),
        owner_name: owner.into(),
        role: role.into(),
        permissions: Permissions { send: true },
        online,
        last_seen_at: Some(Utc::now() - Duration::hours(seen_h)),
    }
}

fn files(n: usize) -> Vec<ScannedFile> {
    let start = Utc::now() - Duration::days(90);
    (0..n)
        .map(|i| {
            let video = i % 9 == 4;
            let heic = i % 11 == 7;
            let ext = if video {
                "mov"
            } else if heic {
                "heic"
            } else {
                "jpg"
            };
            ScannedFile {
                path: PathBuf::from(format!("/demo/Summer in Zeeland/IMG_{:04}.{ext}", 2100 + i)),
                folder: "Summer in Zeeland".into(),
                size: if video {
                    48_000_000
                } else {
                    3_400_000 + (i as u64 * 7919) % 2_000_000
                },
                kind: if video { MediaKind::Video } else { MediaKind::Photo },
                mime: if video {
                    "video/quicktime".into()
                } else {
                    "image/jpeg".into()
                },
                taken_at: start + Duration::minutes(i as i64 * 97),
                date_source: DateSource::Exif,
            }
        })
        .collect()
}

/// A soft, made-up landscape photo.
pub fn picture(seed: usize, w: usize, h: usize) -> ColorImage {
    type Rgb = [u8; 3];
    let palettes: [(Rgb, Rgb, Rgb, Rgb); 6] = [
        ([250, 214, 165], [244, 162, 97], [42, 157, 143], [38, 70, 83]),
        ([173, 216, 230], [255, 255, 255], [106, 153, 78], [56, 102, 65]),
        ([255, 200, 221], [255, 175, 204], [189, 224, 254], [162, 210, 255]),
        ([142, 202, 230], [33, 158, 188], [255, 183, 3], [251, 133, 0]),
        ([233, 196, 106], [244, 162, 97], [231, 111, 81], [38, 70, 83]),
        ([202, 240, 248], [144, 224, 239], [0, 150, 199], [2, 62, 138]),
    ];
    let (sky_top, sky_bottom, hill1, hill2) = palettes[seed % palettes.len()];
    let lerp = |a: [u8; 3], b: [u8; 3], t: f32| {
        Color32::from_rgb(
            (a[0] as f32 + (b[0] as f32 - a[0] as f32) * t) as u8,
            (a[1] as f32 + (b[1] as f32 - a[1] as f32) * t) as u8,
            (a[2] as f32 + (b[2] as f32 - a[2] as f32) * t) as u8,
        )
    };
    let phase = seed as f32 * 1.7;
    let sun = (0.25 + (seed % 5) as f32 * 0.12, 0.28 + (seed % 3) as f32 * 0.06);
    let mut px = Vec::with_capacity(w * h);
    for y in 0..h {
        for x in 0..w {
            let (fx, fy) = (x as f32 / w as f32, y as f32 / h as f32);
            let mut c = lerp(sky_top, sky_bottom, fy);
            let d = ((fx - sun.0).powi(2) + (fy - sun.1).powi(2)).sqrt();
            if d < 0.07 {
                c = Color32::from_rgb(255, 236, 179);
            }
            let ridge1 = 0.58 + 0.08 * (fx * 6.0 + phase).sin() + 0.04 * (fx * 13.0 + phase * 2.0).sin();
            let ridge2 = 0.72 + 0.06 * (fx * 4.0 - phase).sin();
            if fy > ridge1 {
                c = lerp(hill1, hill2, ((fy - ridge1) * 2.0).min(1.0) * 0.4);
            }
            if fy > ridge2 {
                c = lerp(hill2, hill1, ((fy - ridge2) * 3.0).min(1.0) * 0.3);
            }
            px.push(c);
        }
    }
    ColorImage::new([w, h], px)
}

fn me() -> User {
    User {
        id: "u1".into(),
        display_name: "Jeroen Trappers".into(),
        email: Some("jeroen@example.org".into()),
        locale: Some("en".into()),
    }
}

fn sends() -> Vec<Send> {
    let mk_items = |prefix: &str, n: usize, video: usize| -> Vec<Item> {
        (0..n)
            .map(|i| Item {
                id: format!("{prefix}{i}"),
                kind: if i < video { "video".into() } else { "photo".into() },
                status: "ready".into(),
                failure_code: None,
                caption: (i == 0).then(|| "Summer in Zeeland".to_string()),
                thumb_url: Some(format!("demo://{prefix}{i}")),
            })
            .collect()
    };
    let placements = |items: &[Item], delivered: usize, seen: usize, love: usize| -> Vec<SendPlacement> {
        items
            .iter()
            .enumerate()
            .map(|(i, it)| SendPlacement {
                placement_id: format!("p-{}", it.id),
                item_id: it.id.clone(),
                status: if i < seen {
                    "seen"
                } else if i < delivered {
                    "delivered"
                } else {
                    "pending"
                }
                .into(),
                reaction: (i < love).then(|| {
                    if i == 1 {
                        "laugh".to_string()
                    } else {
                        "love".to_string()
                    }
                }),
                replies: vec![],
            })
            .collect()
    };
    let a = mk_items("a", 12, 1);
    let b = mk_items("b", 3, 0);
    let c = mk_items("c", 40, 2);
    vec![
        Send {
            id: "s1".into(),
            created_at: Utc::now() - Duration::minutes(25),
            frames: vec![
                SendFrame {
                    frame_id: "f1".into(),
                    frame_name: "Oma's frame".into(),
                    online: Some(true),
                    placements: placements(&a, 12, 9, 3),
                },
                SendFrame {
                    frame_id: "f2".into(),
                    frame_name: "Opa in Gent".into(),
                    online: Some(false),
                    placements: placements(&a, 0, 0, 0),
                },
            ],
            items: a,
        },
        Send {
            id: "s2".into(),
            created_at: Utc::now() - Duration::hours(26),
            frames: vec![SendFrame {
                frame_id: "f1".into(),
                frame_name: "Oma's frame".into(),
                online: Some(true),
                placements: {
                    let mut p = placements(&b, 3, 3, 1);
                    p[2].replies = vec!["call_me".into()];
                    p
                },
            }],
            items: b,
        },
        Send {
            id: "s3".into(),
            created_at: Utc::now() - Duration::days(6),
            frames: vec![SendFrame {
                frame_id: "f3".into(),
                frame_name: "Living room".into(),
                online: Some(true),
                placements: placements(&c, 40, 22, 2),
            }],
            items: c,
        },
    ]
}

fn queue(server: &str) -> Queue {
    let fr = vec![
        FrameRef {
            id: "f1".into(),
            name: "Oma's frame".into(),
        },
        FrameRef {
            id: "f2".into(),
            name: "Opa in Gent".into(),
        },
    ];
    let mut big = Job::new(server, "Summer in Zeeland", fr.clone(), &files(342), "{folder}");
    big.created_at = Utc::now() - Duration::minutes(12);
    for (i, it) in big.items.iter_mut().enumerate() {
        it.state = match i {
            0..=118 => ItemState::Sent,
            119..=124 => ItemState::Duplicate,
            125 | 126 => ItemState::Failed,
            127..=129 => ItemState::Uploading,
            _ => ItemState::Queued,
        };
        if it.state == ItemState::Uploading {
            it.resume.offset = it.size / (i as u64 - 125);
        }
        if it.state == ItemState::Failed {
            it.error = Some(crate::i18n::t("error.videoTooLong").to_string());
        }
    }
    let mut done = Job::new(server, "Birthday Lotte", vec![fr[0].clone()], &files(24), "");
    done.created_at = Utc::now() - Duration::days(2);
    for it in &mut done.items {
        it.state = ItemState::Sent;
    }
    done.items[3].state = ItemState::Duplicate;
    Queue { jobs: vec![done, big] }
}

/// An `App` showing `screen` with demo data.
pub fn app(ctx: &egui::Context, screen: Screen, dir: &std::path::Path) -> App {
    super::theme::install(ctx);
    let mut app = App::with_parts(
        ctx.clone(),
        Dirs::at(dir),
        Settings::default(),
        None,
        Arc::new(MemoryStore::default()),
    );
    let d = discovery();
    app.discovery = Some(d.clone());
    app.settings.server = Some("https://kinframe.appmire.be".into());
    app.live_connected = true;
    if screen == Screen::Welcome {
        app.auth = AuthState::SignedOut;
        app.server_check = ServerCheck::Ok(Box::new(d));
        return app;
    }
    app.auth = AuthState::SignedIn;
    app.me = Some(me());
    app.frames = vec![
        frame("f1", "Oma's frame", "Living room", "Jeroen Trappers", "owner", true, 0),
        frame("f2", "Opa in Gent", "Kitchen", "Jeroen Trappers", "admin", false, 3),
        frame("f3", "Living room", "Antwerp", "Lies Peeters", "member", true, 0),
    ];
    app.frames_loaded = true;
    app.history = sends();
    app.history_loaded = true;
    app.history_cursor = Some("next".into());
    for s in &app.history {
        for (i, it) in s.items.iter().enumerate() {
            if let Some(u) = &it.thumb_url {
                let seed = i * 5 + s.id.bytes().map(usize::from).sum::<usize>();
                app.thumbs.insert(ctx, Source::Url(u.clone()), picture(seed, 96, 96));
            }
        }
    }
    let fs = files(342);
    for (i, f) in fs.iter().enumerate().take(80) {
        app.thumbs
            .insert(ctx, Source::File(f.path.clone()), picture(i, 160, 120));
    }
    match screen {
        Screen::Frames | Screen::FramesDrop => {
            app.tab = Tab::Frames;
            app.code_input = "4821 39".into();
        }
        Screen::Draft => {
            app.tab = Tab::Send;
            let n = fs.len();
            let mut selected = vec![true; n];
            selected[2] = false;
            selected[9] = false;
            app.draft = Some(Draft {
                title: "Summer in Zeeland".into(),
                files: fs,
                selected,
                skipped: 3,
                frames: ["f1".to_string(), "f2".to_string()].into(),
                caption_template: "{folder}".into(),
            });
        }
        Screen::Progress => {
            app.tab = Tab::Send;
            let q = queue(&discovery().server_key());
            app.expanded_job = Some(q.jobs[1].id);
            app.demo_queue = Some(q);
        }
        Screen::History => app.tab = Tab::History,
        Screen::Welcome => {}
    }
    app
}
