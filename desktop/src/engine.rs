//! Runs the bulk-import queue: hashes, de-duplicates, uploads (tus, 3 at a time) and sends in batches.
//!
//! The queue is shared state behind a mutex: workers update it, the UI reads it every frame, and a saver task
//! writes it to disk once a second when something changed.

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tokio::sync::Notify;
use uuid::Uuid;

use crate::api::{Api, ApiError};
use crate::auth::AuthError;
use crate::dedupe::{SentIndex, hash_file};
use crate::model::{SendCreate, SendItemCreate};
use crate::prepare::{Originals, Preparer};
use crate::queue::{ItemState, Job, Queue};
use crate::tus::{Metadata, Outcome, Tus, TusError};

pub const CONCURRENCY: usize = 3;
const MAX_ATTEMPTS: u32 = 6;

#[derive(Debug, Clone, PartialEq)]
pub enum EngineEvent {
    /// Something changed; repaint.
    Changed,
    /// A job has nothing left to do.
    JobFinished {
        job: Uuid,
        title: String,
        sent: usize,
        duplicates: usize,
        failed: usize,
    },
    /// The server rejected our sign-in; work stops until the person signs in again.
    SignInExpired,
}

type Listener = Box<dyn Fn(EngineEvent) + Send + Sync>;

struct Inner {
    queue: Mutex<Queue>,
    index: Mutex<SentIndex>,
    queue_path: PathBuf,
    api: Mutex<Option<Api>>,
    preparer: Box<dyn Preparer>,
    wake: Notify,
    dirty: AtomicBool,
    running: Mutex<HashSet<(Uuid, u32)>>,
    sending: AtomicBool,
    send_retry_at: Mutex<Option<Instant>>,
    finished_reported: Mutex<HashSet<Uuid>>,
    listener: Listener,
    chunk_size: usize,
}

#[derive(Clone)]
pub struct Engine {
    inner: Arc<Inner>,
}

pub struct EngineConfig {
    pub queue_path: PathBuf,
    pub index_path: PathBuf,
    pub preparer: Box<dyn Preparer>,
    pub chunk_size: usize,
}

impl EngineConfig {
    pub fn new(queue_path: PathBuf, index_path: PathBuf) -> Self {
        Self {
            queue_path,
            index_path,
            preparer: Box::new(Originals),
            chunk_size: crate::tus::CHUNK_SIZE,
        }
    }
}

impl Engine {
    /// Loads the queue and starts the scheduler and saver on `rt`.
    pub fn start(
        rt: &tokio::runtime::Handle,
        cfg: EngineConfig,
        listener: impl Fn(EngineEvent) + Send + Sync + 'static,
    ) -> Engine {
        let queue = Queue::load(&cfg.queue_path);
        // Jobs that were already done before the restart don't announce themselves again.
        let done: HashSet<Uuid> = queue.jobs.iter().filter(|j| j.is_done()).map(|j| j.id).collect();
        let inner = Arc::new(Inner {
            queue: Mutex::new(queue),
            index: Mutex::new(SentIndex::load(&cfg.index_path)),
            queue_path: cfg.queue_path,
            api: Mutex::new(None),
            preparer: cfg.preparer,
            wake: Notify::new(),
            dirty: AtomicBool::new(false),
            running: Mutex::new(HashSet::new()),
            sending: AtomicBool::new(false),
            send_retry_at: Mutex::new(None),
            finished_reported: Mutex::new(done),
            listener: Box::new(listener),
            chunk_size: cfg.chunk_size,
        });
        let e = Engine { inner };
        let s = e.clone();
        rt.spawn(async move { s.scheduler().await });
        let s = e.clone();
        rt.spawn(async move { s.saver().await });
        e
    }

    /// Work only happens while signed in, and only for jobs of that server.
    pub fn set_api(&self, api: Option<Api>) {
        *self.inner.api.lock().unwrap() = api;
        self.inner.wake.notify_one();
    }

    pub fn add_job(&self, job: Job) -> Uuid {
        let id = job.id;
        self.inner.queue.lock().unwrap().jobs.push(job);
        self.changed();
        id
    }

    pub fn set_paused(&self, job: Uuid, paused: bool) {
        if let Some(j) = self.inner.queue.lock().unwrap().job_mut(job) {
            j.paused = paused;
        }
        self.changed();
    }

    pub fn set_all_paused(&self, paused: bool) {
        for j in &mut self.inner.queue.lock().unwrap().jobs {
            if !j.is_done() {
                j.paused = paused;
            }
        }
        self.changed();
    }

    pub fn retry_failed(&self, job: Uuid) {
        if let Some(j) = self.inner.queue.lock().unwrap().job_mut(job) {
            j.retry_failed();
        }
        self.inner.finished_reported.lock().unwrap().remove(&job);
        self.changed();
    }

    pub fn remove_job(&self, job: Uuid) {
        self.inner.queue.lock().unwrap().jobs.retain(|j| j.id != job);
        self.changed();
    }

    /// Reads the queue (the UI calls this every frame; keep `f` cheap).
    pub fn with_queue<R>(&self, f: impl FnOnce(&Queue) -> R) -> R {
        f(&self.inner.queue.lock().unwrap())
    }

    /// Frames a file was already sent to, by hash (for the import preview).
    pub fn sent_index_len(&self, server: &str) -> usize {
        self.inner.index.lock().unwrap().len(server)
    }

    /// Writes the queue now (on quit).
    pub fn flush(&self) {
        let q = self.inner.queue.lock().unwrap().clone();
        if let Err(e) = q.save(&self.inner.queue_path) {
            log::warn!("could not save the queue: {e}");
        }
    }

    fn changed(&self) {
        self.inner.dirty.store(true, Ordering::Relaxed);
        self.inner.wake.notify_one();
        (self.inner.listener)(EngineEvent::Changed);
    }

    fn update_item(&self, job: Uuid, item: u32, f: impl FnOnce(&mut crate::queue::JobItem)) {
        if let Some(i) = self
            .inner
            .queue
            .lock()
            .unwrap()
            .job_mut(job)
            .and_then(|j| j.item_mut(item))
        {
            f(i);
        }
        self.inner.dirty.store(true, Ordering::Relaxed);
        (self.inner.listener)(EngineEvent::Changed);
    }

    fn is_paused(&self, job: Uuid) -> bool {
        self.inner.queue.lock().unwrap().job(job).is_none_or(|j| j.paused)
    }

    async fn saver(self) {
        let mut tick = tokio::time::interval(Duration::from_secs(1));
        loop {
            tick.tick().await;
            if self.inner.dirty.swap(false, Ordering::Relaxed) {
                let q = self.inner.queue.lock().unwrap().clone();
                let path = self.inner.queue_path.clone();
                let _ = tokio::task::spawn_blocking(move || {
                    if let Err(e) = q.save(&path) {
                        log::warn!("could not save the queue: {e}");
                    }
                })
                .await;
            }
        }
    }

    async fn scheduler(self) {
        loop {
            let wait = self.schedule();
            tokio::select! {
                _ = self.inner.wake.notified() => {}
                _ = tokio::time::sleep(wait) => {}
            }
        }
    }

    /// One scheduling round: start uploads, plan and start sends, report finished jobs. Returns how long to
    /// sleep at most before the next round.
    fn schedule(&self) -> Duration {
        let mut wait = Duration::from_secs(30);
        let Some(api) = self.inner.api.lock().unwrap().clone() else {
            return wait;
        };
        let server = api.discovery().server_key();
        let max_items = api.discovery().limits.max_items_per_send as usize;
        let now = Instant::now();
        let mut start = vec![];
        let mut finished = vec![];
        {
            let mut q = self.inner.queue.lock().unwrap();
            let mut running = self.inner.running.lock().unwrap();
            for job in q.jobs.iter_mut().filter(|j| j.server == server && !j.paused) {
                while running.len() < CONCURRENCY {
                    let Some(id) = job.next_work(now) else { break };
                    let item = job.item_mut(id).expect("next_work returns real items");
                    item.state = if item.sha256.is_some() {
                        ItemState::Uploading
                    } else {
                        ItemState::Hashing
                    };
                    item.retry_at = None;
                    running.insert((job.id, id));
                    start.push((job.id, id));
                }
                if let Some(t) = job.next_retry() {
                    wait = wait.min(t.saturating_duration_since(now));
                }
                job.plan_batches(max_items);
            }
            let mut reported = self.inner.finished_reported.lock().unwrap();
            for job in q.jobs.iter().filter(|j| j.server == server) {
                if job.is_done()
                    && !job.batches.iter().any(|b| b.state == crate::queue::BatchState::Pending)
                    && reported.insert(job.id)
                {
                    let c = job.counts();
                    finished.push(EngineEvent::JobFinished {
                        job: job.id,
                        title: job.title.clone(),
                        sent: c.sent,
                        duplicates: c.duplicate,
                        failed: c.failed,
                    });
                }
            }
        }
        if !start.is_empty() {
            self.inner.dirty.store(true, Ordering::Relaxed);
        }
        for (job, item) in start {
            let me = self.clone();
            let api = api.clone();
            tokio::spawn(async move {
                me.work(api, job, item).await;
                me.inner.running.lock().unwrap().remove(&(job, item));
                me.inner.wake.notify_one();
                (me.inner.listener)(EngineEvent::Changed);
            });
        }
        let retry_ok = self.inner.send_retry_at.lock().unwrap().is_none_or(|t| t <= now);
        if !retry_ok && let Some(t) = *self.inner.send_retry_at.lock().unwrap() {
            wait = wait.min(t.saturating_duration_since(now));
        }
        if retry_ok && !self.inner.sending.swap(true, Ordering::AcqRel) {
            let me = self.clone();
            tokio::spawn(async move {
                me.send_pending(api, &server).await;
                me.inner.sending.store(false, Ordering::Release);
                me.inner.wake.notify_one();
            });
        }
        for e in finished {
            (self.inner.listener)(e);
        }
        wait
    }

    /// Hash → duplicate check → prepare → upload one file.
    async fn work(&self, api: Api, job_id: Uuid, item_id: u32) {
        let snapshot = self.with_queue(|q| {
            q.job(job_id)
                .and_then(|j| j.item(item_id).map(|i| (i.clone(), j.frame_ids(), j.server.clone())))
        });
        let Some((mut item, frames, server)) = snapshot else {
            return;
        };

        // 1. Hash the original (duplicate detection, and the server's existing-item hint).
        if item.sha256.is_none() {
            let path = item.path.clone();
            match tokio::task::spawn_blocking(move || hash_file(&path, |_| {})).await {
                Ok(Ok(h)) => item.sha256 = Some(h),
                Ok(Err(e)) => {
                    return self.fail(job_id, item_id, &crate::i18n::tf("error.unreadable", &[("error", &e)]));
                }
                Err(_) => return self.fail(job_id, item_id, "internal error"),
            }
        }
        let sha = item.sha256.clone().expect("hashed above");

        // 2. Which target frames still need it?
        if item.targets.is_none() {
            let missing = self.inner.index.lock().unwrap().missing(&server, &sha, &frames);
            item.targets = Some(missing);
        }
        let targets = item.targets.clone().unwrap_or_default();
        if targets.is_empty() {
            self.update_item(job_id, item_id, |i| {
                i.sha256 = Some(sha.clone());
                i.targets = Some(vec![]);
                i.state = ItemState::Duplicate;
            });
            return;
        }
        self.update_item(job_id, item_id, |i| {
            i.sha256 = Some(sha.clone());
            i.targets = Some(targets.clone());
            i.state = ItemState::Uploading;
        });

        // 3. Prepare (originals for now).
        let prepared = match self.inner.preparer.prepare(&item) {
            Ok(p) => p,
            Err(e) => {
                return self.fail(job_id, item_id, &crate::i18n::tf("error.unreadable", &[("error", &e)]));
            }
        };

        // 4. Upload, resuming where we left off.
        let tus = Tus::new(api.http.clone(), api.discovery().api_url("uploads")).with_chunk_size(self.inner.chunk_size);
        let mut resume = item.resume.clone();
        let meta = Metadata {
            filename: &prepared.filename,
            filetype: &prepared.mime,
            sha256: Some(&sha),
        };
        let me = self.clone();
        let me2 = self.clone();
        let result = tus
            .upload(
                api.session.as_ref(),
                &prepared.path,
                &meta,
                &mut resume,
                move |st| {
                    let st = st.clone();
                    me.update_item(job_id, item_id, |i| i.resume = st);
                },
                move || me2.is_paused(job_id),
            )
            .await;
        match result {
            Ok(Outcome::Uploaded { upload_id }) | Ok(Outcome::Existing { item_id: upload_id }) => {
                self.update_item(job_id, item_id, |i| {
                    i.upload_ref = Some(upload_id);
                    i.state = ItemState::Uploaded;
                    i.error = None;
                });
            }
            Ok(Outcome::Paused) => self.update_item(job_id, item_id, |i| i.state = ItemState::Queued),
            Err(TusError::Api(ApiError::Auth(AuthError::Expired))) => {
                self.update_item(job_id, item_id, |i| i.state = ItemState::Queued);
                self.sign_in_expired();
            }
            Err(e) if e.is_transient() => self.retry_later(job_id, item_id, &e.to_string()),
            Err(e) => self.fail(job_id, item_id, &describe_error(&e)),
        }
    }

    fn retry_later(&self, job: Uuid, item: u32, error: &str) {
        self.update_item(job, item, |i| {
            i.attempts += 1;
            if i.attempts >= MAX_ATTEMPTS {
                i.state = ItemState::Failed;
                i.error = Some(error.to_string());
            } else {
                i.state = ItemState::Queued;
                let secs = (1u64 << i.attempts).min(60);
                i.retry_at = Some(Instant::now() + Duration::from_secs(secs));
            }
        });
    }

    fn fail(&self, job: Uuid, item: u32, error: &str) {
        self.update_item(job, item, |i| {
            i.state = ItemState::Failed;
            i.error = Some(error.to_string());
        });
    }

    fn sign_in_expired(&self) {
        *self.inner.api.lock().unwrap() = None;
        (self.inner.listener)(EngineEvent::SignInExpired);
    }

    /// Sends every pending batch of this server, one at a time.
    async fn send_pending(&self, api: Api, server: &str) {
        loop {
            let next = self.with_queue(|q| {
                q.jobs
                    .iter()
                    .filter(|j| j.server == server && !j.paused)
                    .find_map(|j| j.pending_batch().map(|b| (j.id, b.clone(), build_send(j, b))))
            });
            let Some((job_id, batch, body)) = next else {
                return;
            };
            match api.create_send(&body, &batch.id.to_string()).await {
                Ok(send) => {
                    let recorded = {
                        let mut q = self.inner.queue.lock().unwrap();
                        q.job_mut(job_id)
                            .map(|j| j.batch_sent(batch.id, &send.id))
                            .unwrap_or_default()
                    };
                    {
                        let mut idx = self.inner.index.lock().unwrap();
                        for (sha, frames) in &recorded {
                            idx.record(server, sha, frames);
                        }
                        if let Err(e) = idx.save() {
                            log::warn!("could not save the sent index: {e}");
                        }
                    }
                    *self.inner.send_retry_at.lock().unwrap() = None;
                    self.changed();
                }
                Err(ApiError::Auth(AuthError::Expired)) => {
                    self.sign_in_expired();
                    return;
                }
                Err(e) if e.is_transient() => {
                    *self.inner.send_retry_at.lock().unwrap() = Some(Instant::now() + Duration::from_secs(15));
                    return;
                }
                Err(e) => {
                    let reupload = e.code() == Some("upload_not_found");
                    if let Some(j) = self.inner.queue.lock().unwrap().job_mut(job_id) {
                        j.batch_failed(batch.id, &describe_api_error(&e), reupload);
                    }
                    self.changed();
                }
            }
        }
    }
}

fn build_send(job: &Job, batch: &crate::queue::Batch) -> SendCreate {
    SendCreate {
        frame_ids: batch.frames.clone(),
        items: batch
            .items
            .iter()
            .filter_map(|id| job.item(*id))
            .map(|i| SendItemCreate {
                upload_id: i.upload_ref.clone().unwrap_or_default(),
                caption: i.caption.clone(),
                captured_at: Some(i.taken_at),
            })
            .collect(),
    }
}

fn describe_api_error(e: &ApiError) -> String {
    match e.code() {
        Some("upload_too_large") => crate::i18n::t("error.tooLarge").to_string(),
        Some("video_too_long") => crate::i18n::t("error.videoTooLong").to_string(),
        Some("unsupported_media") => crate::i18n::t("error.unsupported").to_string(),
        Some("missing_permission") | Some("forbidden") => crate::i18n::t("error.noPermission").to_string(),
        _ => e.to_string(),
    }
}

fn describe_error(e: &TusError) -> String {
    match e {
        TusError::Api(a) => describe_api_error(a),
        TusError::File(f) => crate::i18n::tf("error.unreadable", &[("error", f)]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::queue::FrameRef;
    use crate::scan::{DateSource, MediaKind, ScannedFile};
    use crate::tus::tests::{FakeTus, mount_fake};
    use std::sync::atomic::AtomicUsize;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

    #[derive(Clone, Default)]
    struct Sends {
        keys: Arc<Mutex<Vec<String>>>,
        sizes: Arc<Mutex<Vec<usize>>>,
    }
    impl Respond for Sends {
        fn respond(&self, req: &Request) -> ResponseTemplate {
            let key = req.headers["idempotency-key"].to_str().unwrap().to_string();
            let body: serde_json::Value = serde_json::from_slice(&req.body).unwrap();
            self.keys.lock().unwrap().push(key.clone());
            self.sizes.lock().unwrap().push(body["items"].as_array().unwrap().len());
            ResponseTemplate::new(202).set_body_json(serde_json::json!({
                "id": format!("send-{key}"), "created_at": "2026-10-22T13:05:00Z", "items": [], "frames": []
            }))
        }
    }

    fn photos(dir: &std::path::Path, n: usize) -> Vec<ScannedFile> {
        (0..n)
            .map(|i| {
                let p = dir.join(format!("IMG_{i}.jpg"));
                std::fs::write(&p, format!("photo number {i}")).unwrap();
                crate::scan::describe(&p, MediaKind::Photo, "image/jpeg").unwrap()
            })
            .map(|mut f| {
                f.date_source = DateSource::FileTime;
                f
            })
            .collect()
    }

    async fn wait_until(engine: &Engine, f: impl Fn(&Queue) -> bool) {
        for _ in 0..200 {
            if engine.with_queue(&f) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        panic!("timed out; queue: {:#?}", engine.with_queue(|q| q.clone()));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn bulk_import_end_to_end_with_dedupe() {
        let server = MockServer::start().await;
        let fake = FakeTus::default();
        mount_fake(&server, fake).await;
        let sends = Sends::default();
        Mock::given(method("POST"))
            .and(path("/v1/sends"))
            .respond_with(sends.clone())
            .mount(&server)
            .await;

        let dir = tempfile::tempdir().unwrap();
        let media = dir.path().join("media");
        std::fs::create_dir_all(&media).unwrap();
        let files = photos(&media, 5);
        let events = Arc::new(AtomicUsize::new(0));
        let ev = events.clone();
        let engine = Engine::start(
            &tokio::runtime::Handle::current(),
            EngineConfig::new(dir.path().join("queue.json"), dir.path().join("sent.json")),
            move |e| {
                if matches!(e, EngineEvent::JobFinished { .. }) {
                    ev.fetch_add(1, Ordering::SeqCst);
                }
            },
        );
        let api = crate::api::tests::dev_api(&server.uri());
        let api = {
            // max 2 items per send
            let mut d = api.discovery().clone();
            d.limits.max_items_per_send = 2;
            let s = crate::auth::Session::new(
                api.http.clone(),
                d,
                Arc::new(crate::auth::MemoryStore::default()),
                crate::auth::Credentials::Dev {
                    email: "a@b.c".into(),
                    name: "A".into(),
                },
                Some(("tok".into(), 3600)),
            );
            Api::new(api.http.clone(), Arc::new(s), "en")
        };
        let server_key = api.discovery().server_key();
        engine.set_api(Some(api));
        let frames = vec![FrameRef {
            id: "f1".into(),
            name: "Oma".into(),
        }];
        let job = engine.add_job(Job::new(&server_key, "Summer", frames.clone(), &files, "{folder}"));
        wait_until(&engine, |q| q.job(job).unwrap().is_done()).await;
        let c = engine.with_queue(|q| q.job(job).unwrap().counts());
        assert_eq!((c.sent, c.failed, c.duplicate), (5, 0, 0));
        assert_eq!(*sends.sizes.lock().unwrap(), vec![2, 2, 1]);
        let keys = sends.keys.lock().unwrap().clone();
        assert_eq!(
            keys.iter().collect::<HashSet<_>>().len(),
            3,
            "distinct idempotency keys"
        );

        // The same folder again: everything is a duplicate, nothing is uploaded or sent.
        let again = engine.add_job(Job::new(&server_key, "Summer again", frames, &files, ""));
        wait_until(&engine, |q| q.job(again).unwrap().is_done()).await;
        let c = engine.with_queue(|q| q.job(again).unwrap().counts());
        assert_eq!(c.duplicate, 5);
        assert_eq!(sends.sizes.lock().unwrap().len(), 3);
        for _ in 0..40 {
            if events.load(Ordering::SeqCst) == 2 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
        assert_eq!(events.load(Ordering::SeqCst), 2);

        // The queue on disk reflects the result.
        engine.flush();
        let q = Queue::load(&dir.path().join("queue.json"));
        assert_eq!(q.jobs.len(), 2);
        assert_eq!(q.jobs[0].items[0].caption.as_deref(), Some("media"));
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn permanent_failures_are_per_file_and_retryable() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/uploads"))
            .respond_with(ResponseTemplate::new(413).set_body_json(serde_json::json!({
                "title": "Too large", "status": 413, "code": "upload_too_large"
            })))
            .mount(&server)
            .await;
        let dir = tempfile::tempdir().unwrap();
        let files = photos(dir.path(), 2);
        let engine = Engine::start(
            &tokio::runtime::Handle::current(),
            EngineConfig::new(dir.path().join("queue.json"), dir.path().join("sent.json")),
            |_| {},
        );
        let api = crate::api::tests::dev_api(&server.uri());
        let key = api.discovery().server_key();
        engine.set_api(Some(api));
        let job = engine.add_job(Job::new(
            &key,
            "x",
            vec![FrameRef {
                id: "f".into(),
                name: "F".into(),
            }],
            &files,
            "",
        ));
        wait_until(&engine, |q| q.job(job).unwrap().is_done()).await;
        engine.with_queue(|q| {
            let j = q.job(job).unwrap();
            assert_eq!(j.counts().failed, 2);
            assert_eq!(j.items[0].error.as_deref(), Some(crate::i18n::t("error.tooLarge")));
        });
        engine.set_paused(job, true);
        engine.retry_failed(job);
        engine.with_queue(|q| assert_eq!(q.job(job).unwrap().items[0].state, ItemState::Queued));
    }
}
