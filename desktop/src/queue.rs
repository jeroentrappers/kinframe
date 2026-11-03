//! The bulk-import queue: jobs, their files and send batches. Pure state, persisted as JSON so an import
//! survives quitting the app; [`crate::engine`] does the work.

use std::path::{Path, PathBuf};
use std::time::Instant;

use chrono::{DateTime, Local, Utc};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::scan::{MediaKind, ScannedFile};
use crate::tus::ResumeState;

pub const MAX_CAPTION: usize = 280;

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Queue {
    #[serde(default)]
    pub jobs: Vec<Job>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FrameRef {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Job {
    pub id: Uuid,
    /// `Discovery::server_key` of the server this job sends to.
    pub server: String,
    pub created_at: DateTime<Utc>,
    pub title: String,
    pub frames: Vec<FrameRef>,
    #[serde(default)]
    pub paused: bool,
    pub items: Vec<JobItem>,
    #[serde(default)]
    pub batches: Vec<Batch>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ItemState {
    Queued,
    Hashing,
    Uploading,
    /// On the server, waiting to be part of a send.
    Uploaded,
    /// In a send batch that is not confirmed yet.
    Batched,
    Sent,
    /// Every target frame already has it.
    Duplicate,
    Failed,
}

impl ItemState {
    pub fn is_final(self) -> bool {
        matches!(self, ItemState::Sent | ItemState::Duplicate | ItemState::Failed)
    }
    pub fn is_upload_work(self) -> bool {
        matches!(self, ItemState::Queued | ItemState::Hashing | ItemState::Uploading)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct JobItem {
    pub id: u32,
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    pub kind: MediaKind,
    pub mime: String,
    pub taken_at: DateTime<Utc>,
    #[serde(default)]
    pub caption: Option<String>,
    #[serde(default)]
    pub sha256: Option<String>,
    /// Frames that still need this file; set after the duplicate check.
    #[serde(default)]
    pub targets: Option<Vec<String>>,
    pub state: ItemState,
    #[serde(default)]
    pub resume: ResumeState,
    /// tus upload id, or the existing item id the server reported.
    #[serde(default)]
    pub upload_ref: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
    #[serde(skip)]
    pub attempts: u32,
    #[serde(skip)]
    pub retry_at: Option<Instant>,
}

impl JobItem {
    /// Bytes of this file that are done (for progress bars).
    pub fn bytes_done(&self) -> u64 {
        match self.state {
            ItemState::Queued | ItemState::Hashing | ItemState::Uploading => self.resume.offset.min(self.size),
            ItemState::Failed => 0,
            _ => self.size,
        }
    }
    pub fn fraction(&self) -> f32 {
        if self.size == 0 {
            return 1.0;
        }
        self.bytes_done() as f32 / self.size as f32
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Batch {
    /// Also the `Idempotency-Key` of the send, so a retry after a crash can't send twice.
    pub id: Uuid,
    pub items: Vec<u32>,
    pub frames: Vec<String>,
    pub state: BatchState,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case", tag = "state")]
pub enum BatchState {
    Pending,
    Sent { send_id: String },
    Failed { error: String },
}

#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct Counts {
    pub total: usize,
    pub sent: usize,
    pub duplicate: usize,
    pub failed: usize,
    pub active: usize,
    pub bytes_total: u64,
    pub bytes_done: u64,
}

impl Counts {
    pub fn finished(&self) -> usize {
        self.sent + self.duplicate + self.failed
    }
    pub fn fraction(&self) -> f32 {
        if self.bytes_total == 0 {
            return if self.total == 0 {
                0.0
            } else {
                self.finished() as f32 / self.total as f32
            };
        }
        // Bytes drive the bar; a send confirmation is the last step of each file.
        let bytes = self.bytes_done as f64 / self.bytes_total as f64;
        let files = self.finished() as f64 / self.total.max(1) as f64;
        (bytes * 0.9 + files * 0.1) as f32
    }
}

/// Fills `{folder}`, `{filename}` (without extension) and `{date}` (the day it was taken, local time).
pub fn render_caption(template: &str, file: &ScannedFile) -> Option<String> {
    let t = template.trim();
    if t.is_empty() {
        return None;
    }
    let stem = file
        .path
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    let date = file.taken_at.with_timezone(&Local).format("%Y-%m-%d").to_string();
    let s = t
        .replace("{folder}", &file.folder)
        .replace("{filename}", &stem)
        .replace("{date}", &date);
    let s: String = s.trim().chars().take(MAX_CAPTION).collect();
    (!s.is_empty()).then_some(s)
}

impl Job {
    pub fn new(server: &str, title: &str, frames: Vec<FrameRef>, files: &[ScannedFile], caption_template: &str) -> Job {
        let items = files
            .iter()
            .enumerate()
            .map(|(i, f)| JobItem {
                id: i as u32,
                path: f.path.clone(),
                name: f.file_name(),
                size: f.size,
                kind: f.kind,
                mime: f.mime.clone(),
                taken_at: f.taken_at,
                caption: render_caption(caption_template, f),
                sha256: None,
                targets: None,
                state: ItemState::Queued,
                resume: ResumeState::default(),
                upload_ref: None,
                error: None,
                attempts: 0,
                retry_at: None,
            })
            .collect();
        Job {
            id: Uuid::new_v4(),
            server: server.to_string(),
            created_at: Utc::now(),
            title: title.to_string(),
            frames,
            paused: false,
            items,
            batches: vec![],
        }
    }

    pub fn frame_ids(&self) -> Vec<String> {
        self.frames.iter().map(|f| f.id.clone()).collect()
    }

    pub fn item(&self, id: u32) -> Option<&JobItem> {
        self.items.get(id as usize).filter(|i| i.id == id)
    }

    pub fn item_mut(&mut self, id: u32) -> Option<&mut JobItem> {
        self.items.get_mut(id as usize).filter(|i| i.id == id)
    }

    pub fn counts(&self) -> Counts {
        let mut c = Counts {
            total: self.items.len(),
            ..Default::default()
        };
        for i in &self.items {
            match i.state {
                ItemState::Sent => c.sent += 1,
                ItemState::Duplicate => c.duplicate += 1,
                ItemState::Failed => c.failed += 1,
                ItemState::Hashing | ItemState::Uploading => c.active += 1,
                _ => {}
            }
            if i.state != ItemState::Failed {
                c.bytes_total += i.size;
                c.bytes_done += i.bytes_done();
            }
        }
        c
    }

    pub fn is_done(&self) -> bool {
        self.items.iter().all(|i| i.state.is_final())
    }

    /// The next file to hash and upload.
    pub fn next_work(&self, now: Instant) -> Option<u32> {
        self.items
            .iter()
            .find(|i| i.state == ItemState::Queued && i.retry_at.is_none_or(|t| t <= now))
            .map(|i| i.id)
    }

    /// Earliest pending retry, to know when to wake up.
    pub fn next_retry(&self) -> Option<Instant> {
        self.items
            .iter()
            .filter(|i| i.state == ItemState::Queued)
            .filter_map(|i| i.retry_at)
            .min()
    }

    /// Groups uploaded files into send batches: by the set of frames that still need them, at most `max` per send.
    /// Full batches go out right away; the rest once no more uploads can join them.
    pub fn plan_batches(&mut self, max: usize) -> usize {
        let max = max.max(1);
        let uploads_pending = self.items.iter().any(|i| i.state.is_upload_work());
        let mut groups: Vec<(Vec<String>, Vec<u32>)> = vec![];
        for i in self.items.iter().filter(|i| i.state == ItemState::Uploaded) {
            let key = i.targets.clone().unwrap_or_else(|| self.frame_ids());
            match groups.iter_mut().find(|(k, _)| *k == key) {
                Some((_, ids)) => ids.push(i.id),
                None => groups.push((key, vec![i.id])),
            }
        }
        let mut made = 0;
        for (frames, ids) in groups {
            for chunk in ids.chunks(max) {
                if chunk.len() < max && uploads_pending {
                    break;
                }
                for id in chunk {
                    if let Some(i) = self.item_mut(*id) {
                        i.state = ItemState::Batched;
                    }
                }
                self.batches.push(Batch {
                    id: Uuid::new_v4(),
                    items: chunk.to_vec(),
                    frames: frames.clone(),
                    state: BatchState::Pending,
                });
                made += 1;
            }
        }
        made
    }

    pub fn pending_batch(&self) -> Option<&Batch> {
        self.batches.iter().find(|b| b.state == BatchState::Pending)
    }

    /// Marks a batch sent; returns `(sha256, frames)` for the sent index.
    pub fn batch_sent(&mut self, batch_id: Uuid, send_id: &str) -> Vec<(String, Vec<String>)> {
        let Some(b) = self.batches.iter_mut().find(|b| b.id == batch_id) else {
            return vec![];
        };
        b.state = BatchState::Sent {
            send_id: send_id.to_string(),
        };
        let (ids, frames) = (b.items.clone(), b.frames.clone());
        let mut out = vec![];
        for id in ids {
            if let Some(i) = self.item_mut(id) {
                i.state = ItemState::Sent;
                i.error = None;
                if let Some(h) = &i.sha256 {
                    out.push((h.clone(), frames.clone()));
                }
            }
        }
        out
    }

    /// A send was refused. With `reupload` (the uploads expired) the files go back to the upload queue and the
    /// batch is dropped; otherwise they fail with `error`.
    pub fn batch_failed(&mut self, batch_id: Uuid, error: &str, reupload: bool) {
        let Some(pos) = self.batches.iter().position(|b| b.id == batch_id) else {
            return;
        };
        let ids = self.batches[pos].items.clone();
        if reupload {
            self.batches.remove(pos);
        } else {
            self.batches[pos].state = BatchState::Failed {
                error: error.to_string(),
            };
        }
        for id in ids {
            if let Some(i) = self.item_mut(id) {
                if reupload {
                    i.state = ItemState::Queued;
                    i.resume = ResumeState::default();
                    i.upload_ref = None;
                } else {
                    i.state = ItemState::Failed;
                    i.error = Some(error.to_string());
                }
            }
        }
    }

    /// "Retry failed": failed sends are planned again, failed files are uploaded again.
    pub fn retry_failed(&mut self) -> usize {
        let failed_batches: Vec<Uuid> = self
            .batches
            .iter()
            .filter(|b| matches!(b.state, BatchState::Failed { .. }))
            .map(|b| b.id)
            .collect();
        self.batches.retain(|b| !failed_batches.contains(&b.id));
        let mut n = 0;
        for i in &mut self.items {
            if i.state == ItemState::Failed {
                n += 1;
                i.error = None;
                i.attempts = 0;
                i.retry_at = None;
                i.state = if i.upload_ref.is_some() {
                    ItemState::Uploaded
                } else {
                    ItemState::Queued
                };
            }
        }
        self.paused = false;
        n
    }
}

impl Queue {
    /// Loads the queue; files that were in flight when the app stopped are picked up again.
    pub fn load(path: &Path) -> Queue {
        let mut q: Queue = match std::fs::read(path) {
            Ok(b) => serde_json::from_slice(&b).unwrap_or_else(|e| {
                log::warn!("queue file unreadable ({e}); keeping a copy and starting empty");
                let _ = std::fs::copy(path, path.with_extension("corrupt.json"));
                Queue::default()
            }),
            Err(_) => Queue::default(),
        };
        for job in &mut q.jobs {
            for i in &mut job.items {
                if matches!(i.state, ItemState::Hashing | ItemState::Uploading) {
                    i.state = ItemState::Queued;
                }
            }
        }
        q
    }

    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        crate::store::write_atomic(path, &serde_json::to_vec(self)?)
    }

    pub fn job(&self, id: Uuid) -> Option<&Job> {
        self.jobs.iter().find(|j| j.id == id)
    }

    pub fn job_mut(&mut self, id: Uuid) -> Option<&mut Job> {
        self.jobs.iter_mut().find(|j| j.id == id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scan::DateSource;

    pub fn files(n: usize) -> Vec<ScannedFile> {
        (0..n)
            .map(|i| ScannedFile {
                path: PathBuf::from(format!("/photos/Summer 2024/IMG_{i:04}.jpg")),
                folder: "Summer 2024".into(),
                size: 1000,
                kind: MediaKind::Photo,
                mime: "image/jpeg".into(),
                taken_at: DateTime::from_timestamp(1_720_958_400 + i as i64, 0).unwrap(),
                date_source: DateSource::Exif,
            })
            .collect()
    }

    fn frames() -> Vec<FrameRef> {
        vec![
            FrameRef {
                id: "f1".into(),
                name: "Oma".into(),
            },
            FrameRef {
                id: "f2".into(),
                name: "Opa".into(),
            },
        ]
    }

    #[test]
    fn captions_from_template() {
        let f = &files(1)[0];
        assert_eq!(render_caption("", f), None);
        assert_eq!(render_caption("{folder}", f).as_deref(), Some("Summer 2024"));
        assert_eq!(
            render_caption("{folder} · {filename}", f).as_deref(),
            Some("Summer 2024 · IMG_0000")
        );
        assert!(render_caption("{date}", f).unwrap().starts_with("2024-07-1"));
        assert_eq!(
            render_caption(&"x".repeat(400), f).unwrap().chars().count(),
            MAX_CAPTION
        );
    }

    #[test]
    fn batches_respect_the_limit_and_target_sets() {
        let mut job = Job::new("https://a", "Summer", frames(), &files(7), "");
        let all = job.frame_ids();
        for i in &mut job.items {
            i.state = ItemState::Uploaded;
            i.targets = Some(all.clone());
        }
        // Item 6 only still needs frame f2.
        job.items[6].targets = Some(vec!["f2".into()]);
        // Item 5 is still uploading: only the full batch goes out.
        job.items[5].state = ItemState::Uploading;
        assert_eq!(job.plan_batches(3), 1);
        assert_eq!(job.batches[0].items, vec![0, 1, 2]);
        assert_eq!(job.items[0].state, ItemState::Batched);

        job.items[5].state = ItemState::Uploaded;
        assert_eq!(job.plan_batches(3), 2);
        assert_eq!(job.batches[1].items, vec![3, 4, 5]);
        assert_eq!(job.batches[2].items, vec![6]);
        assert_eq!(job.batches[2].frames, vec!["f2".to_string()]);
        assert_eq!(job.plan_batches(3), 0);
    }

    #[test]
    fn send_results_and_retry() {
        let mut job = Job::new("https://a", "Summer", frames(), &files(4), "");
        for i in &mut job.items {
            i.state = ItemState::Uploaded;
            i.sha256 = Some(format!("h{}", i.id));
            i.upload_ref = Some(format!("u{}", i.id));
        }
        job.plan_batches(2);
        let (b0, b1) = (job.batches[0].id, job.batches[1].id);
        let recorded = job.batch_sent(b0, "send-1");
        assert_eq!(recorded.len(), 2);
        assert_eq!(recorded[0].1, vec!["f1".to_string(), "f2".to_string()]);
        job.batch_failed(b1, "Video too long", false);
        assert_eq!(job.items[2].state, ItemState::Failed);
        let c = job.counts();
        assert_eq!((c.sent, c.failed), (2, 2));
        assert!(job.is_done());

        assert_eq!(job.retry_failed(), 2);
        assert_eq!(job.items[2].state, ItemState::Uploaded);
        assert!(job.batches.iter().all(|b| b.id != b1));
        job.plan_batches(2);
        assert_eq!(job.pending_batch().unwrap().items, vec![2, 3]);

        // Expired uploads go back to the upload queue.
        let b2 = job.pending_batch().unwrap().id;
        job.batch_failed(b2, "", true);
        assert_eq!(job.items[3].state, ItemState::Queued);
        assert_eq!(job.items[3].upload_ref, None);
        assert_eq!(job.next_work(Instant::now()), Some(2));
    }

    #[test]
    fn persists_and_resumes_in_flight_work() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("queue.json");
        let mut q = Queue::default();
        let mut job = Job::new("https://a", "Summer", frames(), &files(3), "{folder}");
        job.items[0].state = ItemState::Uploading;
        job.items[0].resume = ResumeState {
            url: Some("https://a/v1/uploads/x".into()),
            offset: 500,
        };
        job.items[1].state = ItemState::Hashing;
        job.items[2].state = ItemState::Uploaded;
        job.plan_batches(10);
        let id = job.id;
        q.jobs.push(job);
        q.save(&path).unwrap();

        let q = Queue::load(&path);
        let job = q.job(id).unwrap();
        assert_eq!(job.items[0].state, ItemState::Queued);
        assert_eq!(job.items[0].resume.offset, 500);
        assert_eq!(job.items[1].state, ItemState::Queued);
        assert_eq!(job.items[0].caption.as_deref(), Some("Summer 2024"));
        // Uploads were pending, so the single uploaded file waits instead of going out alone.
        assert!(job.batches.is_empty());
        assert_eq!(job.counts().bytes_done, 500 + 1000);

        std::fs::write(&path, b"garbage").unwrap();
        assert!(Queue::load(&path).jobs.is_empty());
        assert!(path.with_extension("corrupt.json").exists());
    }
}
