//! The egui application: state, background messages and the window chrome. Screens live in submodules.

pub mod demo;
mod frames;
mod history;
mod import;
pub mod theme;
pub mod thumbs;
mod welcome;
pub mod widgets;

use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc::{Receiver, Sender, channel};
use std::time::{Duration, Instant};

use egui::{Align, CornerRadius, Frame, Key, Layout, Margin, RichText, Stroke, Ui, vec2};
use tokio::task::JoinHandle;
use url::Url;

use crate::api::{Api, ApiError};
use crate::auth::{self, AuthError, SecretStore, Session};
use crate::discovery::{self, Discovery};
use crate::engine::{Engine, EngineConfig, EngineEvent};
use crate::i18n::{self, Lang, t, tf};
use crate::live::{self, LiveEvent};
use crate::model::{FrameSummary, PLACEHOLDER_NAME, RedeemResult, Send, SendsPage, User};
use crate::queue::Queue;
use crate::scan::{ScanResult, ScannedFile};
use crate::store::{Dirs, Settings};
use theme::palette;
use thumbs::{Decoded, Request, Source, Thumbs};
use widgets::*;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Send,
    Frames,
    History,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ServerCheck {
    Idle,
    Checking,
    Ok(Box<Discovery>),
    Err(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum AuthState {
    /// Looking for a stored sign-in.
    Restoring,
    SignedOut,
    /// The browser is open; waiting for the loopback redirect.
    Waiting {
        url: Option<Url>,
    },
    SignedIn,
}

#[derive(Debug, Clone, PartialEq)]
pub enum RedeemState {
    Idle,
    Busy,
    Done(RedeemResult),
    Err(String),
}

/// A scanned folder waiting for "Send".
pub struct Draft {
    pub title: String,
    pub files: Vec<ScannedFile>,
    pub selected: Vec<bool>,
    pub skipped: usize,
    pub frames: BTreeSet<String>,
    pub caption_template: String,
}

impl Draft {
    pub fn selected_files(&self) -> Vec<ScannedFile> {
        self.files
            .iter()
            .zip(&self.selected)
            .filter(|(_, s)| **s)
            .map(|(f, _)| f.clone())
            .collect()
    }
    pub fn selected_count(&self) -> usize {
        self.selected.iter().filter(|s| **s).count()
    }
}

pub struct Scanning {
    pub found: Arc<AtomicUsize>,
    pub cancel: Arc<AtomicBool>,
}

pub struct Toast {
    pub text: String,
    pub tone: Tone,
    pub until: Instant,
}

/// Results from background tasks.
pub enum Msg {
    ServerChecked(Result<Discovery, String>),
    DiscoveryRefreshed(Discovery),
    Restored(Result<Option<Arc<Session>>, AuthError>),
    SignInUrl(Url),
    SignedIn(Result<(Arc<Session>, Option<String>), AuthError>),
    Me(Result<User, ApiError>),
    NameSaved(Result<User, ApiError>),
    Frames(Result<Vec<FrameSummary>, ApiError>),
    Redeemed(Result<RedeemResult, ApiError>),
    Picked(Vec<PathBuf>),
    Scanned {
        result: ScanResult,
        title: String,
    },
    History {
        page: Result<SendsPage, ApiError>,
        append: bool,
    },
    SendRefreshed(Send),
    Live(LiveEvent),
    Engine(EngineEvent),
    Thumb(Decoded),
}

pub struct App {
    pub rt: Option<tokio::runtime::Runtime>,
    tx: Sender<Msg>,
    rx: Receiver<Msg>,
    pub ctx: egui::Context,
    pub dirs: Dirs,
    pub settings: Settings,
    http: reqwest::Client,
    store: Arc<dyn SecretStore>,
    pub engine: Option<Engine>,
    /// Shown instead of the engine's queue in demo/screenshot mode.
    pub demo_queue: Option<Queue>,
    pub session: Option<Arc<Session>>,
    pub api: Option<Api>,
    live_task: Option<JoinHandle<()>>,
    signin_task: Option<JoinHandle<()>>,

    // ---- UI state
    pub tab: Tab,
    pub discovery: Option<Discovery>,
    pub custom_server: bool,
    pub server_input: String,
    pub server_check: ServerCheck,
    pub auth: AuthState,
    pub auth_error: Option<String>,
    pub dev_email: String,
    pub dev_name: String,
    pub me: Option<User>,
    pub name_prompt: Option<String>,
    pub frames: Vec<FrameSummary>,
    pub frames_loaded: bool,
    pub frames_error: Option<String>,
    pub code_input: String,
    pub redeem: RedeemState,
    pub draft: Option<Draft>,
    pub scanning: Option<Scanning>,
    pub expanded_job: Option<uuid::Uuid>,
    pub history: Vec<Send>,
    pub history_cursor: Option<String>,
    pub history_loading: bool,
    pub history_loaded: bool,
    pub history_error: Option<String>,
    pub live_connected: bool,
    pub toasts: Vec<Toast>,
    pub thumbs: Thumbs,
    pub show_settings: bool,
    last_history_refresh: Option<Instant>,
    /// "Sign in" was pressed before the server was checked: continue once it is.
    pending_sign_in: bool,
    /// `KINFRAME_SCREENSHOT=<file.png>`: save a screenshot of the real window after a few seconds, then quit.
    screenshot: Option<(PathBuf, Instant)>,
    /// Files or folders passed on the command line ("Open with kinframe"), scanned once signed in.
    pub open_paths: Vec<PathBuf>,
}

impl App {
    /// The real app: loads settings, starts the runtime and the upload engine, restores the sign-in.
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let ctx = cc.egui_ctx.clone();
        theme::install(&ctx);
        let dirs = Dirs::system();
        let settings = Settings::load(&dirs.settings_file());
        let rt = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .enable_all()
            .thread_name("kinframe-worker")
            .build()
            .expect("tokio runtime");
        let mut app = Self::with_parts(ctx, dirs, settings, Some(rt), auth::default_store());
        app.start_engine();
        app.boot();
        app.open_paths = std::env::args_os()
            .skip(1)
            .map(PathBuf::from)
            .filter(|p| p.exists())
            .collect();
        app.screenshot = std::env::var_os("KINFRAME_SCREENSHOT").map(|p| (PathBuf::from(p), Instant::now()));
        app
    }

    pub fn with_parts(
        ctx: egui::Context,
        dirs: Dirs,
        settings: Settings,
        rt: Option<tokio::runtime::Runtime>,
        store: Arc<dyn SecretStore>,
    ) -> Self {
        let (tx, rx) = channel();
        let lang = settings
            .language
            .as_deref()
            .and_then(Lang::from_tag)
            .unwrap_or_else(Lang::system);
        i18n::set_lang(lang);
        let http = reqwest::Client::builder()
            .user_agent(concat!("kinframe-desktop/", env!("CARGO_PKG_VERSION")))
            .connect_timeout(Duration::from_secs(15))
            .build()
            .expect("http client");
        let discovery = settings.discovery.clone();
        let server_input = settings.server.clone().unwrap_or_default();
        let custom_server = settings
            .server
            .as_deref()
            .is_some_and(|s| s != discovery::DEFAULT_SERVER);
        Self {
            rt,
            tx,
            rx,
            ctx,
            store,
            engine: None,
            demo_queue: None,
            session: None,
            api: None,
            live_task: None,
            signin_task: None,
            tab: Tab::Send,
            custom_server,
            server_input,
            server_check: ServerCheck::Idle,
            auth: AuthState::SignedOut,
            auth_error: None,
            dev_email: settings.dev_email.clone().unwrap_or_default(),
            dev_name: settings.dev_name.clone().unwrap_or_default(),
            me: None,
            name_prompt: None,
            frames: vec![],
            frames_loaded: false,
            frames_error: None,
            code_input: String::new(),
            redeem: RedeemState::Idle,
            draft: None,
            scanning: None,
            expanded_job: None,
            history: vec![],
            history_cursor: None,
            history_loading: false,
            history_loaded: false,
            history_error: None,
            live_connected: false,
            toasts: vec![],
            thumbs: Thumbs::default(),
            show_settings: false,
            last_history_refresh: None,
            pending_sign_in: false,
            screenshot: None,
            open_paths: vec![],
            discovery,
            http,
            dirs,
            settings,
        }
    }

    fn start_engine(&mut self) {
        let Some(rt) = &self.rt else { return };
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        let engine = Engine::start(
            rt.handle(),
            EngineConfig::new(self.dirs.queue_file(), self.dirs.sent_index_file()),
            move |e| {
                if e != EngineEvent::Changed {
                    let _ = tx.send(Msg::Engine(e));
                }
                ctx.request_repaint_after(Duration::from_millis(100));
            },
        );
        self.engine = Some(engine);
    }

    /// Runs `fut` on the runtime and delivers its message.
    fn spawn<F>(&self, fut: F) -> Option<JoinHandle<()>>
    where
        F: std::future::Future<Output = Msg> + std::marker::Send + 'static,
    {
        let rt = self.rt.as_ref()?;
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        Some(rt.spawn(async move {
            let msg = fut.await;
            let _ = tx.send(msg);
            ctx.request_repaint();
        }))
    }

    /// On start: restore the stored session for the saved server, and refresh its discovery document.
    fn boot(&mut self) {
        let Some(d) = self.discovery.clone() else { return };
        let http = self.http.clone();
        let server = self.settings.server.clone();
        // Refresh discovery quietly (limits or client ids may have changed).
        if let Some(s) = server.and_then(|s| discovery::normalize_server_url(&s).ok()) {
            let http = http.clone();
            self.spawn(async move {
                match discovery::fetch(&http, &s).await {
                    Ok(d) => Msg::DiscoveryRefreshed(d),
                    Err(e) => Msg::ServerChecked(Err(e.to_string())),
                }
            });
        }
        if d.dev_auth && !self.dev_email.is_empty() && !self.settings.signed_out {
            self.dev_sign_in();
            return;
        }
        if self.settings.signed_out {
            return;
        }
        self.auth = AuthState::Restoring;
        let store = self.store.clone();
        self.spawn(async move { Msg::Restored(Session::restore(http, d, store).await.map(|o| o.map(Arc::new))) });
    }

    pub fn locale(&self) -> &'static str {
        i18n::lang().code()
    }

    fn save_settings(&self) {
        if self.rt.is_some() {
            self.settings.save(&self.dirs.settings_file());
        }
    }

    pub fn toast(&mut self, tone: Tone, text: impl Into<String>) {
        self.toasts.push(Toast {
            text: text.into(),
            tone,
            until: Instant::now() + Duration::from_secs(6),
        });
    }

    // ------------------------------------------------------------------ server and sign-in

    pub fn check_server(&mut self) {
        let input = if self.custom_server {
            self.server_input.clone()
        } else {
            discovery::DEFAULT_SERVER.to_string()
        };
        let url = match discovery::normalize_server_url(&input) {
            Ok(u) => u,
            Err(e) => {
                self.server_check = ServerCheck::Err(e.to_string());
                return;
            }
        };
        self.server_check = ServerCheck::Checking;
        let http = self.http.clone();
        self.spawn(async move { Msg::ServerChecked(discovery::fetch(&http, &url).await.map_err(|e| e.to_string())) });
    }

    /// Starts sign-in on the checked server (checking it first if needed).
    pub fn sign_in(&mut self) {
        let d = match &self.server_check {
            ServerCheck::Ok(d) => (**d).clone(),
            _ => match &self.discovery {
                Some(d) if self.server_matches(d) => d.clone(),
                _ => {
                    self.check_server();
                    self.pending_sign_in = true;
                    return;
                }
            },
        };
        self.adopt_server(d.clone());
        if !d.logto_available() {
            self.auth_error = Some(t("welcome.noDesktopSupport").into());
            return;
        }
        self.auth_error = None;
        self.auth = AuthState::Waiting { url: None };
        let http = self.http.clone();
        let store = self.store.clone();
        let locale = self.locale().to_string();
        let force = self.settings.signed_out;
        let page = auth::loopback::done_page(t("browser.doneTitle"), t("browser.doneBody"));
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        self.signin_task = self.spawn(async move {
            let r = auth::sign_in_logto(http, d, store, &locale, force, page, move |url| {
                if let Err(e) = webbrowser::open(url.as_str()) {
                    log::warn!("could not open the browser: {e}");
                }
                let _ = tx.send(Msg::SignInUrl(url));
                ctx.request_repaint();
            })
            .await;
            Msg::SignedIn(r.map(|s| (Arc::new(s.session), s.name_claim)))
        });
    }

    pub fn cancel_sign_in(&mut self) {
        if let Some(t) = self.signin_task.take() {
            t.abort();
        }
        self.auth = AuthState::SignedOut;
    }

    pub fn dev_sign_in(&mut self) {
        let Some(d) = self.discovery.clone().filter(|d| d.dev_auth) else {
            return;
        };
        let email = self.dev_email.trim().to_string();
        let name = if self.dev_name.trim().is_empty() {
            email.split('@').next().unwrap_or("dev").to_string()
        } else {
            self.dev_name.trim().to_string()
        };
        self.settings.dev_email = Some(email.clone());
        self.settings.dev_name = Some(name.clone());
        self.settings.signed_out = false;
        self.save_settings();
        self.auth = AuthState::Waiting { url: None };
        let http = self.http.clone();
        let store = self.store.clone();
        self.spawn(async move {
            let s = Session::new(http, d, store, auth::Credentials::Dev { email, name }, None);
            match s.access_token().await {
                Ok(_) => Msg::SignedIn(Ok((Arc::new(s), None))),
                Err(e) => Msg::SignedIn(Err(e)),
            }
        });
    }

    fn server_matches(&self, d: &Discovery) -> bool {
        let want = if self.custom_server {
            self.server_input.clone()
        } else {
            discovery::DEFAULT_SERVER.into()
        };
        match (discovery::normalize_server_url(&want), &self.settings.server) {
            (Ok(u), Some(s)) => discovery::origin_string(&u) == *s && self.discovery.as_ref() == Some(d),
            _ => false,
        }
    }

    /// Remembers the server whose discovery document we validated.
    fn adopt_server(&mut self, d: Discovery) {
        let input = if self.custom_server {
            self.server_input.clone()
        } else {
            discovery::DEFAULT_SERVER.into()
        };
        if let Ok(u) = discovery::normalize_server_url(&input) {
            self.settings.server = Some(discovery::origin_string(&u));
        }
        self.settings.discovery = Some(d.clone());
        self.discovery = Some(d);
        self.save_settings();
    }

    fn on_signed_in(&mut self, session: Arc<Session>, name_claim: Option<String>) {
        let api = Api::new(self.http.clone(), session.clone(), self.locale());
        self.session = Some(session.clone());
        self.api = Some(api.clone());
        self.auth = AuthState::SignedIn;
        self.auth_error = None;
        self.settings.signed_out = false;
        self.save_settings();
        if let Some(e) = &self.engine {
            e.set_api(Some(api.clone()));
        }
        self.load_me(name_claim);
        self.reload_frames();
        self.load_history(false);
        self.start_live();
        let paths = std::mem::take(&mut self.open_paths);
        self.scan(paths);
    }

    fn load_me(&mut self, name_claim: Option<String>) {
        let Some(api) = self.api.clone() else { return };
        let locale = self.locale().to_string();
        self.spawn(async move {
            let me = api.me().await;
            // New accounts get a placeholder name: take the real one from the ID token, and tell the server our
            // language on first sign-in.
            match me {
                Ok(u) if u.display_name == PLACEHOLDER_NAME && name_claim.is_some() => Msg::Me(
                    api.update_me(name_claim.as_deref(), u.locale.is_none().then_some(&*locale))
                        .await,
                ),
                Ok(u) if u.locale.is_none() => Msg::Me(api.update_me(None, Some(&locale)).await.or(Ok(u))),
                other => Msg::Me(other),
            }
        });
    }

    pub fn save_name(&mut self, name: String) {
        let Some(api) = self.api.clone() else { return };
        self.spawn(async move { Msg::NameSaved(api.update_me(Some(name.trim()), None).await) });
    }

    pub fn sign_out(&mut self) {
        if let Some(e) = &self.engine {
            e.set_api(None);
        }
        if let Some(t) = self.live_task.take() {
            t.abort();
        }
        if let Some(s) = self.session.take() {
            self.spawn(async move {
                s.sign_out().await;
                Msg::Live(LiveEvent::Disconnected)
            });
        }
        self.api = None;
        self.me = None;
        self.frames.clear();
        self.frames_loaded = false;
        self.history.clear();
        self.history_loaded = false;
        self.history_cursor = None;
        self.auth = AuthState::SignedOut;
        self.settings.signed_out = true;
        self.save_settings();
        self.show_settings = false;
    }

    /// "Change server": signs out and returns to the welcome screen.
    pub fn change_server(&mut self) {
        self.sign_out();
        self.settings.signed_out = false;
        self.server_check = ServerCheck::Idle;
        self.save_settings();
    }

    fn start_live(&mut self) {
        if let Some(t) = self.live_task.take() {
            t.abort();
        }
        let (Some(rt), Some(session)) = (&self.rt, self.session.clone()) else {
            return;
        };
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        self.live_task = Some(rt.spawn(live::run(session, move |e| {
            let _ = tx.send(Msg::Live(e));
            ctx.request_repaint();
        })));
    }

    // ------------------------------------------------------------------ data

    pub fn reload_frames(&mut self) {
        let Some(api) = self.api.clone() else { return };
        self.spawn(async move { Msg::Frames(api.frames().await) });
    }

    pub fn redeem_code(&mut self) {
        let Some(code) = crate::code::normalize(&self.code_input) else {
            self.redeem = RedeemState::Err(t("frames.codeInvalid").into());
            return;
        };
        let Some(api) = self.api.clone() else { return };
        self.redeem = RedeemState::Busy;
        let key = uuid::Uuid::new_v4().to_string();
        self.spawn(async move { Msg::Redeemed(api.redeem(&code, &key).await) });
    }

    pub fn load_history(&mut self, more: bool) {
        let Some(api) = self.api.clone() else { return };
        if self.history_loading {
            return;
        }
        let cursor = if more { self.history_cursor.clone() } else { None };
        if more && cursor.is_none() {
            return;
        }
        self.history_loading = true;
        self.spawn(async move {
            Msg::History {
                page: api.sends(cursor.as_deref(), 20).await,
                append: more,
            }
        });
    }

    fn refresh_send(&mut self, id: String) {
        let Some(api) = self.api.clone() else { return };
        self.spawn(async move {
            match api.send_by_id(&id).await {
                Ok(s) => Msg::SendRefreshed(s),
                Err(e) => Msg::History {
                    page: Err(e),
                    append: true,
                },
            }
        });
    }

    // ------------------------------------------------------------------ import

    pub fn pick_folder(&mut self) {
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            if let Some(dirs) = rfd::FileDialog::new()
                .set_title(t("import.pickFolderTitle"))
                .pick_folders()
            {
                let _ = tx.send(Msg::Picked(dirs));
                ctx.request_repaint();
            }
        });
    }

    pub fn pick_files(&mut self) {
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            if let Some(files) = rfd::FileDialog::new()
                .set_title(t("import.pickFilesTitle"))
                .add_filter(
                    t("import.filterMedia"),
                    &[
                        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "mov", "m4v", "3gp", "mkv", "webm",
                    ],
                )
                .pick_files()
            {
                let _ = tx.send(Msg::Picked(files));
                ctx.request_repaint();
            }
        });
    }

    /// Scans picked or dropped paths in the background.
    pub fn scan(&mut self, paths: Vec<PathBuf>) {
        if paths.is_empty() {
            return;
        }
        if let Some(s) = &self.scanning {
            s.cancel.store(true, Ordering::Relaxed);
        }
        let title = match paths.as_slice() {
            [one] if one.is_dir() => one
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
            [one, ..] => one
                .parent()
                .and_then(|p| p.file_name())
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default(),
            [] => String::new(),
        };
        let found = Arc::new(AtomicUsize::new(0));
        let cancel = Arc::new(AtomicBool::new(false));
        self.scanning = Some(Scanning {
            found: found.clone(),
            cancel: cancel.clone(),
        });
        self.tab = Tab::Send;
        let tx = self.tx.clone();
        let ctx = self.ctx.clone();
        std::thread::spawn(move || {
            let c2 = ctx.clone();
            let result = crate::scan::scan(&paths, &cancel, |n| {
                found.store(n, Ordering::Relaxed);
                c2.request_repaint_after(Duration::from_millis(100));
            });
            if !cancel.load(Ordering::Relaxed) {
                let _ = tx.send(Msg::Scanned { result, title });
            }
            ctx.request_repaint();
        });
    }

    pub fn start_import(&mut self) {
        let (Some(engine), Some(d), Some(draft)) = (&self.engine, &self.discovery, self.draft.take()) else {
            return;
        };
        let frames: Vec<crate::queue::FrameRef> = self
            .frames
            .iter()
            .filter(|f| draft.frames.contains(&f.id))
            .map(|f| crate::queue::FrameRef {
                id: f.id.clone(),
                name: f.name.clone(),
            })
            .collect();
        let files = draft.selected_files();
        let job = crate::queue::Job::new(&d.server_key(), &draft.title, frames, &files, &draft.caption_template);
        self.settings.last_frames = draft.frames.iter().cloned().collect();
        self.settings.caption_template = draft.caption_template.clone();
        self.save_settings();
        let id = engine.add_job(job);
        self.expanded_job = Some(id);
    }

    /// Reads the queue (engine or demo).
    pub fn with_queue<R>(&self, f: impl FnOnce(&Queue) -> R) -> R {
        match (&self.engine, &self.demo_queue) {
            (Some(e), _) => e.with_queue(f),
            (None, Some(q)) => f(q),
            (None, None) => f(&Queue::default()),
        }
    }

    // ------------------------------------------------------------------ thumbnails

    /// The thumbnail texture for a source, kicking off a background load when needed.
    pub fn thumb(&mut self, source: &Source) -> Option<egui::TextureHandle> {
        let (tex, req) = self.thumbs.get(source);
        if let (Some(Request::Load(src)), Some(rt)) = (req, &self.rt) {
            let tx = self.tx.clone();
            let ctx = self.ctx.clone();
            let http = self.http.clone();
            rt.spawn(async move {
                let image = match &src {
                    Source::File(p) => {
                        let p = p.clone();
                        tokio::task::spawn_blocking(move || thumbs::decode(Err(p)))
                            .await
                            .ok()
                            .flatten()
                    }
                    Source::Url(u) => match http.get(u).send().await {
                        Ok(r) if r.status().is_success() => match r.bytes().await {
                            Ok(b) => {
                                let b = b.to_vec();
                                tokio::task::spawn_blocking(move || thumbs::decode(Ok(b)))
                                    .await
                                    .ok()
                                    .flatten()
                            }
                            Err(_) => None,
                        },
                        _ => None,
                    },
                };
                let _ = tx.send(Msg::Thumb(Decoded { source: src, image }));
                ctx.request_repaint();
            });
        }
        tex
    }

    // ------------------------------------------------------------------ messages

    fn handle(&mut self, msg: Msg) {
        match msg {
            Msg::ServerChecked(r) => {
                let ok = r.is_ok();
                self.server_check = match r {
                    Ok(d) => ServerCheck::Ok(Box::new(d)),
                    Err(e) => ServerCheck::Err(e),
                };
                // A failed background refresh at start-up is not shown on the welcome screen when signed in.
                if self.auth == AuthState::SignedIn {
                    self.server_check = ServerCheck::Idle;
                } else if std::mem::take(&mut self.pending_sign_in) && ok {
                    let dev = matches!(&self.server_check, ServerCheck::Ok(d) if d.dev_auth && !d.logto_available());
                    if !dev {
                        self.sign_in();
                    }
                }
            }
            Msg::DiscoveryRefreshed(d) => {
                self.settings.discovery = Some(d.clone());
                self.discovery = Some(d);
                self.save_settings();
            }
            Msg::Restored(r) => match r {
                Ok(Some(s)) => self.on_signed_in(s, None),
                Ok(None) => self.auth = AuthState::SignedOut,
                Err(AuthError::Network(e)) => {
                    self.auth = AuthState::SignedOut;
                    self.auth_error = Some(tf("welcome.offline", &[("error", &e)]));
                }
                Err(e) => {
                    self.auth = AuthState::SignedOut;
                    self.auth_error = Some(e.to_string());
                }
            },
            Msg::SignInUrl(u) => {
                if matches!(self.auth, AuthState::Waiting { .. }) {
                    self.auth = AuthState::Waiting { url: Some(u) };
                }
            }
            Msg::SignedIn(r) => {
                self.signin_task = None;
                match r {
                    Ok((s, name)) => {
                        self.on_signed_in(s, name);
                        self.ctx.send_viewport_cmd(egui::ViewportCommand::Focus);
                    }
                    Err(e) => {
                        self.auth = AuthState::SignedOut;
                        self.auth_error = Some(e.to_string());
                    }
                }
            }
            Msg::Me(r) => match r {
                Ok(u) => {
                    if u.display_name == PLACEHOLDER_NAME {
                        self.name_prompt = Some(String::new());
                    }
                    self.me = Some(u);
                }
                Err(e) => self.api_error(e),
            },
            Msg::NameSaved(r) => match r {
                Ok(u) => {
                    self.me = Some(u);
                    self.name_prompt = None;
                    self.toast(Tone::Good, t("name.saved"));
                }
                Err(e) => self.api_error(e),
            },
            Msg::Frames(r) => match r {
                Ok(f) => {
                    self.frames = f;
                    // A folder scanned before the frames arrived gets the usual targets now.
                    let defaults = self.default_frames();
                    if let Some(d) = self.draft.as_mut().filter(|d| d.frames.is_empty()) {
                        d.frames = defaults;
                    }
                    self.frames_loaded = true;
                    self.frames_error = None;
                }
                Err(e) => {
                    self.frames_error = Some(e.to_string());
                    self.api_error(e);
                }
            },
            Msg::Redeemed(r) => match r {
                Ok(res) => {
                    self.code_input.clear();
                    self.redeem = RedeemState::Done(res);
                    self.reload_frames();
                }
                Err(e) => {
                    self.redeem = RedeemState::Err(match e.code() {
                        Some("code_invalid") => t("frames.codeNotFound").into(),
                        Some("code_expired") => t("frames.codeExpired").into(),
                        Some("rate_limited") => t("frames.codeRateLimited").into(),
                        _ => e.to_string(),
                    })
                }
            },
            Msg::Picked(paths) => self.scan(paths),
            Msg::Scanned { result, title } => {
                self.scanning = None;
                if result.files.is_empty() {
                    self.toast(Tone::Warn, t("import.nothingFound"));
                    return;
                }
                let frames = self.default_frames();
                let n = result.files.len();
                self.draft = Some(Draft {
                    title,
                    files: result.files,
                    selected: vec![true; n],
                    skipped: result.skipped,
                    frames,
                    caption_template: self.settings.caption_template.clone(),
                });
            }
            Msg::History { page, append } => {
                self.history_loading = false;
                match page {
                    Ok(p) => {
                        if append {
                            for s in p.sends {
                                if !self.history.iter().any(|h| h.id == s.id) {
                                    self.history.push(s);
                                }
                            }
                        } else {
                            self.history = p.sends;
                        }
                        self.history_cursor = p.next_cursor;
                        self.history_loaded = true;
                        self.history_error = None;
                    }
                    Err(e) => {
                        self.history_error = Some(e.to_string());
                        self.api_error(e);
                    }
                }
            }
            Msg::SendRefreshed(s) => match self.history.iter_mut().find(|h| h.id == s.id) {
                Some(h) => *h = s,
                None => self.history.insert(0, s),
            },
            Msg::Live(e) => self.on_live(e),
            Msg::Engine(e) => match e {
                EngineEvent::JobFinished {
                    title,
                    sent,
                    duplicates,
                    failed,
                    ..
                } => {
                    let tone = if failed > 0 { Tone::Warn } else { Tone::Good };
                    let mut text = tf("import.finished", &[("title", &title), ("sent", &sent)]);
                    if duplicates > 0 {
                        text.push_str(" · ");
                        text.push_str(&i18n::tn("import.skippedDuplicates", duplicates as u64, &[]));
                    }
                    if failed > 0 {
                        text.push_str(" · ");
                        text.push_str(&i18n::tn("import.failedCount", failed as u64, &[]));
                    }
                    self.toast(tone, text);
                    self.load_history(false);
                }
                EngineEvent::SignInExpired => self.session_expired(),
                EngineEvent::Changed => {}
            },
            Msg::Thumb(d) => self.thumbs.finish(&self.ctx, d),
        }
    }

    /// The frames last sent to (still sendable), or the only frame there is.
    fn default_frames(&self) -> BTreeSet<String> {
        let sendable: Vec<&FrameSummary> = self.frames.iter().filter(|f| f.can_send()).collect();
        let mut frames: BTreeSet<String> = self
            .settings
            .last_frames
            .iter()
            .filter(|id| sendable.iter().any(|f| &f.id == *id))
            .cloned()
            .collect();
        if frames.is_empty() && sendable.len() == 1 {
            frames.insert(sendable[0].id.clone());
        }
        frames
    }

    fn on_live(&mut self, e: LiveEvent) {
        match e {
            LiveEvent::Connected => {
                // Catch up on what happened while disconnected.
                if self.live_connected {
                    return;
                }
                self.live_connected = true;
                if self.history_loaded {
                    self.load_history(false);
                }
                self.reload_frames();
            }
            LiveEvent::Disconnected => self.live_connected = false,
            LiveEvent::SendUpdated { send_id } => self.refresh_send(send_id),
            LiveEvent::Reaction {
                placement_id, reaction, ..
            } => {
                let known = self
                    .history
                    .iter_mut()
                    .any(|s| s.set_reaction(&placement_id, reaction.clone()));
                if !known {
                    self.throttled_history_refresh();
                }
            }
            LiveEvent::Reply { .. } => self.throttled_history_refresh(),
            LiveEvent::FrameStatus { frame_id, online } => {
                if let Some(f) = self.frames.iter_mut().find(|f| f.id == frame_id) {
                    f.online = online;
                    if !online {
                        f.last_seen_at = Some(chrono::Utc::now());
                    }
                }
            }
            LiveEvent::FramesChanged => self.reload_frames(),
        }
    }

    fn throttled_history_refresh(&mut self) {
        let now = Instant::now();
        if self
            .last_history_refresh
            .is_none_or(|t| now - t > Duration::from_secs(5))
        {
            self.last_history_refresh = Some(now);
            self.load_history(false);
        }
    }

    fn api_error(&mut self, e: ApiError) {
        if matches!(e, ApiError::Auth(AuthError::Expired)) {
            self.session_expired();
        }
    }

    fn session_expired(&mut self) {
        if self.auth != AuthState::SignedIn {
            return;
        }
        self.sign_out();
        self.settings.signed_out = false;
        self.save_settings();
        self.auth_error = Some(AuthError::Expired.to_string());
    }

    // ------------------------------------------------------------------ frame

    pub fn ui_root(&mut self, ui: &mut Ui) {
        while let Ok(m) = self.rx.try_recv() {
            self.handle(m);
        }
        self.thumbs.begin_frame();
        self.take_screenshot(ui.ctx());
        let p = palette(ui.ctx());
        self.handle_drops(ui.ctx());
        self.shortcuts(ui.ctx());

        let signed_in = self.auth == AuthState::SignedIn;
        if signed_in {
            egui::Panel::top("top")
                .frame(
                    Frame::new()
                        .fill(p.bg)
                        .inner_margin(Margin::symmetric(24, 12))
                        .stroke(Stroke::NONE),
                )
                .show_separator_line(false)
                .show(ui, |ui| self.top_bar(ui));
        }
        egui::CentralPanel::no_frame().show(ui, |ui| {
            Frame::new()
                .fill(p.bg)
                .inner_margin(Margin::symmetric(0, 0))
                .show(ui, |ui| {
                    ui.set_min_size(ui.available_size());
                    if signed_in {
                        match self.tab {
                            Tab::Send => self.send_tab(ui),
                            Tab::Frames => self.frames_tab(ui),
                            Tab::History => self.history_tab(ui),
                        }
                    } else {
                        self.welcome(ui);
                    }
                });
        });
        if signed_in {
            self.name_modal(ui.ctx());
            self.settings_modal(ui.ctx());
        }
        self.toasts_ui(ui.ctx());
        // Keep progress moving while uploads run.
        let busy = self.with_queue(|q| q.jobs.iter().any(|j| !j.paused && !j.is_done()));
        if busy || self.scanning.is_some() {
            ui.ctx().request_repaint_after(Duration::from_millis(250));
        }
    }

    fn take_screenshot(&mut self, ctx: &egui::Context) {
        let Some((path, started)) = &self.screenshot else {
            return;
        };
        if started.elapsed() < Duration::from_secs(5) {
            ctx.request_repaint_after(Duration::from_millis(200));
            return;
        }
        let path = path.clone();
        self.screenshot = None;
        let c = ctx.clone();
        ctx.send_viewport_cmd(egui::ViewportCommand::Screenshot(egui::ScreenshotCallback::new(
            move |img| {
                let [w, h] = img.size;
                let rgba: Vec<u8> = img.pixels.iter().flat_map(|p| p.to_array()).collect();
                if let Some(buf) = image::RgbaImage::from_raw(w as u32, h as u32, rgba)
                    && let Err(e) = buf.save(&path)
                {
                    log::warn!("screenshot failed: {e}");
                }
                c.send_viewport_cmd(egui::ViewportCommand::Close);
                c.request_repaint();
            },
        )));
    }

    fn handle_drops(&mut self, ctx: &egui::Context) {
        let dropped: Vec<PathBuf> = ctx.input(|i| {
            i.raw
                .dropped_files
                .iter()
                .map(|f| f.path().to_path_buf())
                .filter(|p| !p.as_os_str().is_empty())
                .collect()
        });
        if !dropped.is_empty() && self.auth == AuthState::SignedIn {
            self.scan(dropped);
        }
    }

    fn shortcuts(&mut self, ctx: &egui::Context) {
        if self.auth != AuthState::SignedIn {
            return;
        }
        let cmd = egui::Modifiers::COMMAND;
        let (one, two, three, open, esc) = ctx.input_mut(|i| {
            (
                i.consume_key(cmd, Key::Num1),
                i.consume_key(cmd, Key::Num2),
                i.consume_key(cmd, Key::Num3),
                i.consume_key(cmd, Key::O),
                i.key_pressed(Key::Escape),
            )
        });
        if one {
            self.tab = Tab::Send;
        }
        if two {
            self.tab = Tab::Frames;
        }
        if three {
            self.tab = Tab::History;
        }
        if open {
            self.pick_folder();
        }
        if esc && self.draft.is_some() && self.name_prompt.is_none() && !self.show_settings {
            self.draft = None;
        }
    }

    fn top_bar(&mut self, ui: &mut Ui) {
        let p = palette(ui.ctx());
        ui.horizontal(|ui| {
            ui.set_height(44.0);
            wordmark(ui, 30.0);
            ui.add_space(28.0);
            for (tab, label, key) in [
                (Tab::Send, t("nav.send"), "1"),
                (Tab::Frames, t("nav.frames"), "2"),
                (Tab::History, t("nav.history"), "3"),
            ] {
                let selected = self.tab == tab;
                let (fill, fg) = if selected {
                    (p.primary_soft, p.primary_soft_text)
                } else {
                    (egui::Color32::TRANSPARENT, p.text_muted)
                };
                let resp = ui
                    .add(
                        egui::Button::new(RichText::new(label).font(theme::strong_font(15.0)).color(fg))
                            .fill(fill)
                            .stroke(Stroke::NONE)
                            .corner_radius(CornerRadius::same(255))
                            .min_size(vec2(0.0, 36.0)),
                    )
                    .on_hover_text(format!(
                        "{} {key}",
                        if cfg!(target_os = "macos") { "⌘" } else { "Ctrl+" }
                    ));
                if resp.clicked() {
                    self.tab = tab;
                }
            }
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                let name = self.me.as_ref().map(|m| m.display_name.clone()).unwrap_or_default();
                let resp = avatar(ui, &name, 36.0).on_hover_text(t("settings.title"));
                if resp.clicked() {
                    self.show_settings = true;
                }
                if let Some(d) = &self.discovery {
                    ui.add_space(4.0);
                    ui.label(RichText::new(&d.name).color(p.text_faint).size(13.5));
                    if !self.live_connected && self.rt.is_some() {
                        chip(ui, Tone::Warn, t("live.reconnecting"));
                    }
                }
                let active = self.with_queue(|q| {
                    q.jobs
                        .iter()
                        .filter(|j| !j.is_done())
                        .map(|j| j.counts())
                        .fold((0usize, 0usize), |(a, b), c| (a + c.finished(), b + c.total))
                });
                if active.1 > 0 && self.tab != Tab::Send {
                    let r = chip(
                        ui,
                        Tone::Primary,
                        tf("import.topProgress", &[("done", &active.0), ("total", &active.1)]),
                    );
                    if r.interact(egui::Sense::click()).clicked() {
                        self.tab = Tab::Send;
                    }
                }
            });
        });
    }

    fn name_modal(&mut self, ctx: &egui::Context) {
        let Some(mut name) = self.name_prompt.take() else {
            return;
        };
        let p = palette(ctx);
        let mut save = false;
        egui::Modal::new(egui::Id::unique("name")).show(ctx, |ui| {
            ui.set_width(420.0);
            ui.label(RichText::new(t("name.title")).font(theme::heading(24.0)).color(p.text));
            ui.add_space(4.0);
            ui.add(egui::Label::new(RichText::new(t("name.body")).color(p.text_muted)).wrap());
            ui.add_space(8.0);
            field_label(ui, t("name.label"));
            let r = ui.add(
                egui::TextEdit::singleline(&mut name)
                    .hint_text(t("name.placeholder"))
                    .char_limit(40)
                    .desired_width(f32::INFINITY)
                    .margin(Margin::symmetric(12, 10)),
            );
            if !r.has_focus() && name.is_empty() {
                r.request_focus();
            }
            let enter = r.lost_focus() && ui.input(|i| i.key_pressed(Key::Enter));
            ui.add_space(8.0);
            ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                if primary_button_enabled(ui, !name.trim().is_empty(), t("common.save")).clicked()
                    || (enter && !name.trim().is_empty())
                {
                    save = true;
                }
            });
        });
        if save {
            self.save_name(name.clone());
        }
        self.name_prompt = Some(name);
    }

    fn settings_modal(&mut self, ctx: &egui::Context) {
        if !self.show_settings {
            return;
        }
        let p = palette(ctx);
        let mut close = false;
        let mut sign_out = false;
        let mut change_server = false;
        let mut lang_choice: Option<Option<Lang>> = None;
        let resp = egui::Modal::new(egui::Id::unique("settings")).show(ctx, |ui| {
            ui.set_width(460.0);
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new(t("settings.title"))
                        .font(theme::heading(24.0))
                        .color(p.text),
                );
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if quiet_button(ui, t("common.close")).clicked() {
                        close = true;
                    }
                });
            });
            ui.add_space(6.0);
            soft_panel(ui).show(ui, |ui| {
                ui.set_width(ui.available_width());
                if let Some(me) = &self.me {
                    ui.horizontal(|ui| {
                        avatar(ui, &me.display_name, 40.0);
                        ui.vertical(|ui| {
                            label_strong(ui, &me.display_name);
                            if let Some(e) = &me.email {
                                faint(ui, e);
                            }
                        });
                    });
                    if quiet_button(ui, t("settings.changeName")).clicked() {
                        self.name_prompt = Some(me.display_name.clone());
                        close = true;
                    }
                }
            });
            ui.add_space(6.0);
            field_label(ui, t("settings.language"));
            ui.horizontal_wrapped(|ui| {
                let current = self.settings.language.as_deref().and_then(Lang::from_tag);
                if ui
                    .selectable_label(current.is_none(), t("settings.languageSystem"))
                    .clicked()
                {
                    lang_choice = Some(None);
                }
                for l in Lang::ALL {
                    if ui.selectable_label(current == Some(l), l.native_name()).clicked() {
                        lang_choice = Some(Some(l));
                    }
                }
            });
            ui.add_space(6.0);
            field_label(ui, t("settings.server"));
            if let Some(d) = &self.discovery {
                soft_panel(ui).show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    label_strong(ui, &d.name);
                    faint(
                        ui,
                        format!(
                            "{} · {} {}",
                            self.settings.server.clone().unwrap_or_default(),
                            t("settings.version"),
                            d.version
                        ),
                    );
                    faint(
                        ui,
                        tf(
                            "settings.limits",
                            &[
                                ("items", &d.limits.max_items_per_send),
                                ("minutes", &(d.limits.max_video_seconds / 60)),
                            ],
                        ),
                    );
                    if quiet_button(ui, t("settings.changeServer")).clicked() {
                        change_server = true;
                    }
                });
            }
            ui.add_space(10.0);
            ui.horizontal(|ui| {
                if secondary_button(ui, t("settings.signOut")).clicked() {
                    sign_out = true;
                }
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    faint(ui, format!("kinframe {} · AGPL-3.0", env!("CARGO_PKG_VERSION")));
                });
            });
        });
        if resp.should_close() {
            close = true;
        }
        if let Some(l) = lang_choice {
            self.settings.language = l.map(|l| l.code().to_string());
            i18n::set_lang(l.unwrap_or_else(Lang::system));
            self.save_settings();
            if let Some(api) = self.api.clone() {
                let code = self.locale().to_string();
                self.spawn(async move { Msg::Me(api.update_me(None, Some(&code)).await) });
            }
        }
        if sign_out {
            self.sign_out();
        } else if change_server {
            self.change_server();
        }
        if close {
            self.show_settings = false;
        }
    }

    fn toasts_ui(&mut self, ctx: &egui::Context) {
        let now = Instant::now();
        self.toasts.retain(|t| t.until > now);
        if self.toasts.is_empty() {
            return;
        }
        let p = palette(ctx);
        egui::Area::new(egui::Id::unique("toasts"))
            .anchor(egui::Align2::RIGHT_BOTTOM, vec2(-24.0, -24.0))
            .order(egui::Order::Foreground)
            .show(ctx, |ui| {
                ui.set_max_width(420.0);
                for toast in &self.toasts {
                    let (bg, fg) = toast.tone.colors(p);
                    Frame::new()
                        .fill(p.surface)
                        .stroke(Stroke::new(1.0, p.border))
                        .corner_radius(CornerRadius::same(14))
                        .inner_margin(Margin::symmetric(16, 12))
                        .shadow(egui::epaint::Shadow {
                            offset: [0, 6],
                            blur: 20,
                            spread: 0,
                            color: p.shadow,
                        })
                        .show(ui, |ui| {
                            ui.horizontal(|ui| {
                                let (r, _) = ui.allocate_exact_size(vec2(10.0, 10.0), egui::Sense::hover());
                                ui.painter().circle_filled(r.center(), 5.0, fg);
                                let _ = bg;
                                ui.add(egui::Label::new(RichText::new(&toast.text).color(p.text)).wrap());
                            });
                        });
                }
            });
        ctx.request_repaint_after(Duration::from_millis(500));
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut Ui, _frame: &mut eframe::Frame) {
        self.ui_root(ui);
    }

    fn on_exit(&mut self) {
        if let Some(e) = &self.engine {
            e.flush();
        }
    }
}

/// Human-friendly byte sizes ("2.1 GB").
pub fn human_bytes(n: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KB", "MB", "GB", "TB"];
    let mut v = n as f64;
    let mut u = 0;
    while v >= 1000.0 && u < UNITS.len() - 1 {
        v /= 1000.0;
        u += 1;
    }
    if u == 0 {
        format!("{n} B")
    } else if v >= 100.0 {
        format!("{v:.0} {}", UNITS[u])
    } else {
        format!("{v:.1} {}", UNITS[u])
    }
}

/// "just now", "5 min ago", "3 h ago", "2 days ago".
pub fn relative_time(t: chrono::DateTime<chrono::Utc>) -> String {
    let secs = (chrono::Utc::now() - t).num_seconds().max(0);
    match secs {
        0..60 => i18n::t("time.justNow").to_string(),
        60..3600 => tf("time.minutes", &[("n", &(secs / 60))]),
        3600..86400 => tf("time.hours", &[("n", &(secs / 3600))]),
        _ => i18n::tn("time.days", (secs / 86400) as u64, &[]),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bytes() {
        assert_eq!(human_bytes(512), "512 B");
        assert_eq!(human_bytes(2_100_000_000), "2.1 GB");
        assert_eq!(human_bytes(345_000_000), "345 MB");
    }
}
