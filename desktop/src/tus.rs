//! A small tus 1.0 client (creation, HEAD/PATCH resume) for `/v1/uploads` (docs/api/README.md §5.3).

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD;
use reqwest::{Method, StatusCode};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::api::{ApiError, problem_from};
use crate::auth::{AuthError, Session};

pub const CHUNK_SIZE: usize = 4 << 20;
const TUS_VERSION: &str = "1.0.0";

/// Something that hands out bearer tokens: the signed-in [`Session`], or a fixed token in tests.
pub trait TokenSource: Send + Sync {
    fn token(&self) -> impl std::future::Future<Output = Result<String, AuthError>> + Send;
    fn invalidate(&self);
}

impl TokenSource for Session {
    async fn token(&self) -> Result<String, AuthError> {
        self.access_token().await
    }
    fn invalidate(&self) {
        Session::invalidate(self)
    }
}

/// Where an upload stands; persisted in the queue so uploads resume after a restart.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct ResumeState {
    /// The upload URL from the creation response.
    pub url: Option<String>,
    /// Bytes the server confirmed.
    pub offset: u64,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    /// All bytes are on the server; reference the upload in a send by this id.
    Uploaded { upload_id: String },
    /// The server already has an item with this SHA-256 (`Kinframe-Existing-Item`); send that item id instead.
    Existing { item_id: String },
    /// Stopped between chunks because the job was paused.
    Paused,
}

#[derive(Debug, Clone, thiserror::Error, PartialEq)]
pub enum TusError {
    #[error("{0}")]
    Api(#[from] ApiError),
    #[error("The file can't be read: {0}")]
    File(String),
}

impl TusError {
    pub fn is_transient(&self) -> bool {
        matches!(self, TusError::Api(e) if e.is_transient())
    }
}

pub struct Metadata<'a> {
    pub filename: &'a str,
    pub filetype: &'a str,
    pub sha256: Option<&'a str>,
}

/// `Upload-Metadata`: comma-separated `key base64(value)` pairs.
pub fn encode_metadata(m: &Metadata<'_>) -> String {
    let mut pairs = vec![
        format!("filename {}", STANDARD.encode(m.filename)),
        format!("filetype {}", STANDARD.encode(m.filetype)),
    ];
    if let Some(h) = m.sha256 {
        pairs.push(format!("sha256 {}", STANDARD.encode(h)));
    }
    pairs.join(",")
}

/// The id a send references: the last path segment of the upload URL.
pub fn upload_id(url: &str) -> String {
    url.trim_end_matches('/')
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .to_string()
}

pub struct Tus {
    http: reqwest::Client,
    endpoint: Url,
    chunk_size: usize,
}

impl Tus {
    pub fn new(http: reqwest::Client, endpoint: Url) -> Self {
        Self {
            http,
            endpoint,
            chunk_size: CHUNK_SIZE,
        }
    }

    pub fn with_chunk_size(mut self, n: usize) -> Self {
        self.chunk_size = n;
        self
    }

    async fn call<T: TokenSource>(
        &self,
        tokens: &T,
        build: impl Fn() -> reqwest::RequestBuilder,
    ) -> Result<reqwest::Response, ApiError> {
        for attempt in 0..2 {
            let token = tokens.token().await?;
            let resp = build()
                .bearer_auth(token)
                .header("Tus-Resumable", TUS_VERSION)
                .send()
                .await?;
            if resp.status() == StatusCode::UNAUTHORIZED && attempt == 0 {
                tokens.invalidate();
                continue;
            }
            return Ok(resp);
        }
        Err(ApiError::Auth(AuthError::Expired))
    }

    /// Creation. Returns the upload URL, or the existing item id when the server reports a duplicate.
    pub async fn create<T: TokenSource>(&self, tokens: &T, len: u64, meta: &Metadata<'_>) -> Result<Created, ApiError> {
        let md = encode_metadata(meta);
        let resp = self
            .call(tokens, || {
                self.http
                    .request(Method::POST, self.endpoint.clone())
                    .header("Upload-Length", len.to_string())
                    .header("Upload-Metadata", &md)
                    .header("Content-Length", "0")
            })
            .await?;
        if resp.status() != StatusCode::CREATED {
            return Err(problem_from(resp).await);
        }
        let header = |n: &str| resp.headers().get(n).and_then(|v| v.to_str().ok()).map(str::to_string);
        if let Some(item) = header("Kinframe-Existing-Item").filter(|s| !s.is_empty()) {
            return Ok(Created::Existing(item));
        }
        let loc = header("Location").ok_or_else(|| ApiError::Decode("no Location".into()))?;
        let url = self.endpoint.join(&loc).map_err(|e| ApiError::Decode(e.to_string()))?;
        Ok(Created::Upload(url.to_string()))
    }

    /// The server's offset for an upload, or `None` when it is gone (expired or unknown).
    pub async fn offset<T: TokenSource>(&self, tokens: &T, url: &str) -> Result<Option<u64>, ApiError> {
        let resp = self.call(tokens, || self.http.request(Method::HEAD, url)).await?;
        match resp.status() {
            s if s.is_success() => Ok(resp
                .headers()
                .get("Upload-Offset")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse().ok())),
            StatusCode::NOT_FOUND | StatusCode::GONE | StatusCode::FORBIDDEN => Ok(None),
            _ => Err(problem_from(resp).await),
        }
    }

    async fn patch<T: TokenSource>(
        &self,
        tokens: &T,
        url: &str,
        offset: u64,
        body: Vec<u8>,
    ) -> Result<Patched, ApiError> {
        let resp = self
            .call(tokens, || {
                self.http
                    .request(Method::PATCH, url)
                    .header("Content-Type", "application/offset+octet-stream")
                    .header("Upload-Offset", offset.to_string())
                    .body(body.clone())
            })
            .await?;
        match resp.status() {
            s if s.is_success() => {
                let o = resp
                    .headers()
                    .get("Upload-Offset")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse().ok())
                    .ok_or_else(|| ApiError::Decode("no Upload-Offset".into()))?;
                Ok(Patched::Offset(o))
            }
            StatusCode::CONFLICT => Ok(Patched::Conflict),
            StatusCode::NOT_FOUND | StatusCode::GONE => Ok(Patched::Gone),
            _ => Err(problem_from(resp).await),
        }
    }

    /// Uploads `file`, resuming from `state`. `on_progress` sees every confirmed offset (persist it there);
    /// `paused` is checked between chunks.
    pub async fn upload<T: TokenSource>(
        &self,
        tokens: &T,
        file: &Path,
        meta: &Metadata<'_>,
        state: &mut ResumeState,
        mut on_progress: impl FnMut(&ResumeState) + Send,
        mut paused: impl FnMut() -> bool + Send,
    ) -> Result<Outcome, TusError> {
        let mut f = std::fs::File::open(file).map_err(|e| TusError::File(e.to_string()))?;
        let len = f.metadata().map_err(|e| TusError::File(e.to_string()))?.len();
        let mut recreated = false;

        // Resume: ask the server where we are.
        if let Some(url) = state.url.clone() {
            match self.offset(tokens, &url).await? {
                Some(o) if o <= len => state.offset = o,
                _ => *state = ResumeState::default(),
            }
            on_progress(state);
        }
        loop {
            if state.url.is_none() {
                match self.create(tokens, len, meta).await? {
                    Created::Existing(item_id) => return Ok(Outcome::Existing { item_id }),
                    Created::Upload(url) => {
                        state.url = Some(url);
                        state.offset = 0;
                        on_progress(state);
                    }
                }
            }
            let url = state.url.clone().expect("set above");
            if state.offset >= len {
                return Ok(Outcome::Uploaded {
                    upload_id: upload_id(&url),
                });
            }
            if paused() {
                return Ok(Outcome::Paused);
            }
            let n = (len - state.offset).min(self.chunk_size as u64) as usize;
            let mut buf = vec![0u8; n];
            f.seek(SeekFrom::Start(state.offset))
                .and_then(|_| f.read_exact(&mut buf))
                .map_err(|e| TusError::File(e.to_string()))?;
            match self.patch(tokens, &url, state.offset, buf).await? {
                Patched::Offset(o) => {
                    state.offset = o;
                    on_progress(state);
                }
                Patched::Conflict => {
                    // Our offset is stale (e.g. a chunk landed but the answer got lost): resync.
                    match self.offset(tokens, &url).await? {
                        Some(o) => state.offset = o,
                        None => *state = ResumeState::default(),
                    }
                    on_progress(state);
                }
                Patched::Gone if !recreated => {
                    recreated = true;
                    *state = ResumeState::default();
                    on_progress(state);
                }
                Patched::Gone => {
                    return Err(TusError::Api(ApiError::Network("upload disappeared".into())));
                }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Created {
    Upload(String),
    Existing(String),
}

enum Patched {
    Offset(u64),
    Conflict,
    Gone,
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

    pub struct StaticToken;
    impl TokenSource for StaticToken {
        async fn token(&self) -> Result<String, AuthError> {
            Ok("tok".into())
        }
        fn invalidate(&self) {}
    }

    /// An in-memory tus server: POST creates `up1`, `up2`, …; HEAD/PATCH honour offsets.
    #[derive(Clone, Default)]
    pub struct FakeTus {
        pub uploads: Arc<Mutex<std::collections::HashMap<String, Vec<u8>>>>,
        pub created: Arc<Mutex<usize>>,
        pub patches: Arc<Mutex<usize>>,
        /// Fail PATCH number n (1-based) with a 500, to simulate a dropped connection.
        pub fail_patch: Option<usize>,
    }

    impl FakeTus {
        pub fn data(&self, id: &str) -> Vec<u8> {
            self.uploads.lock().unwrap().get(id).cloned().unwrap_or_default()
        }
    }

    impl Respond for FakeTus {
        fn respond(&self, req: &Request) -> ResponseTemplate {
            let mut uploads = self.uploads.lock().unwrap();
            if req.method.as_str() == "POST" {
                let mut n = self.created.lock().unwrap();
                *n += 1;
                let id = format!("up{n}");
                uploads.insert(id.clone(), vec![]);
                return ResponseTemplate::new(201).insert_header("Location", format!("/v1/uploads/{id}"));
            }
            let id = req.url.path().rsplit('/').next().unwrap().to_string();
            let Some(data) = uploads.get_mut(&id) else {
                return ResponseTemplate::new(404);
            };
            match req.method.as_str() {
                "HEAD" => ResponseTemplate::new(200).insert_header("Upload-Offset", data.len().to_string()),
                "PATCH" => {
                    let mut n = self.patches.lock().unwrap();
                    *n += 1;
                    if Some(*n) == self.fail_patch {
                        return ResponseTemplate::new(500);
                    }
                    let off: usize = req.headers["upload-offset"].to_str().unwrap().parse().unwrap();
                    if off != data.len() {
                        return ResponseTemplate::new(409);
                    }
                    data.extend_from_slice(&req.body);
                    ResponseTemplate::new(204).insert_header("Upload-Offset", data.len().to_string())
                }
                _ => ResponseTemplate::new(405),
            }
        }
    }

    pub async fn mount_fake(server: &MockServer, fake: FakeTus) {
        Mock::given(method("POST"))
            .and(path("/v1/uploads"))
            .and(header("Tus-Resumable", "1.0.0"))
            .respond_with(fake.clone())
            .mount(server)
            .await;
        Mock::given(wiremock::matchers::path_regex("^/v1/uploads/up[0-9]+$"))
            .respond_with(fake)
            .mount(server)
            .await;
    }

    fn file_with(len: usize) -> (tempfile::TempDir, std::path::PathBuf, Vec<u8>) {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("a.jpg");
        let bytes: Vec<u8> = (0..len).map(|i| (i % 251) as u8).collect();
        std::fs::write(&p, &bytes).unwrap();
        (dir, p, bytes)
    }

    const META: Metadata<'static> = Metadata {
        filename: "a.jpg",
        filetype: "image/jpeg",
        sha256: Some("ab"),
    };

    #[test]
    fn metadata_encoding() {
        assert_eq!(
            encode_metadata(&META),
            "filename YS5qcGc=,filetype aW1hZ2UvanBlZw==,sha256 YWI="
        );
        assert_eq!(upload_id("https://x/v1/uploads/abc"), "abc");
    }

    #[tokio::test]
    async fn uploads_in_chunks() {
        let server = MockServer::start().await;
        let fake = FakeTus::default();
        mount_fake(&server, fake.clone()).await;
        let (_d, p, bytes) = file_with(25);
        let tus = Tus::new(
            reqwest::Client::new(),
            format!("{}/v1/uploads", server.uri()).parse().unwrap(),
        )
        .with_chunk_size(10);
        let mut st = ResumeState::default();
        let mut seen = vec![];
        let out = tus
            .upload(&StaticToken, &p, &META, &mut st, |s| seen.push(s.offset), || false)
            .await
            .unwrap();
        assert_eq!(
            out,
            Outcome::Uploaded {
                upload_id: "up1".into()
            }
        );
        assert_eq!(fake.data("up1"), bytes);
        assert_eq!(seen, vec![0, 10, 20, 25]);
        assert_eq!(st.url.as_deref(), Some(&*format!("{}/v1/uploads/up1", server.uri())));
    }

    #[tokio::test]
    async fn resumes_after_failure_and_pause() {
        let server = MockServer::start().await;
        let fake = FakeTus {
            fail_patch: Some(2),
            ..Default::default()
        };
        mount_fake(&server, fake.clone()).await;
        let (_d, p, bytes) = file_with(30);
        let tus = Tus::new(
            reqwest::Client::new(),
            format!("{}/v1/uploads", server.uri()).parse().unwrap(),
        )
        .with_chunk_size(10);

        // First attempt: chunk 2 fails with a server error after chunk 1 landed.
        let mut st = ResumeState::default();
        let err = tus
            .upload(&StaticToken, &p, &META, &mut st, |_| {}, || false)
            .await
            .unwrap_err();
        assert!(err.is_transient());
        assert_eq!(st.offset, 10);

        // Simulate a restart with a persisted state that lags behind the server (offset 0): HEAD resyncs.
        let mut restored = ResumeState {
            url: st.url.clone(),
            offset: 0,
        };
        let mut calls = 0;
        let out = tus
            .upload(
                &StaticToken,
                &p,
                &META,
                &mut restored,
                |_| {},
                || {
                    calls += 1;
                    calls > 1
                },
            )
            .await
            .unwrap();
        assert_eq!(out, Outcome::Paused);
        assert_eq!(restored.offset, 20);

        let out = tus
            .upload(&StaticToken, &p, &META, &mut restored, |_| {}, || false)
            .await
            .unwrap();
        assert_eq!(
            out,
            Outcome::Uploaded {
                upload_id: "up1".into()
            }
        );
        assert_eq!(fake.data("up1"), bytes);
    }

    #[tokio::test]
    async fn existing_item_skips_the_upload() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/uploads"))
            .and(header("Upload-Length", "5"))
            .respond_with(
                ResponseTemplate::new(201)
                    .insert_header("Location", "/v1/uploads/x")
                    .insert_header("Kinframe-Existing-Item", "item-7"),
            )
            .mount(&server)
            .await;
        let (_d, p, _) = file_with(5);
        let tus = Tus::new(
            reqwest::Client::new(),
            format!("{}/v1/uploads", server.uri()).parse().unwrap(),
        );
        let mut st = ResumeState::default();
        let out = tus
            .upload(&StaticToken, &p, &META, &mut st, |_| {}, || false)
            .await
            .unwrap();
        assert_eq!(
            out,
            Outcome::Existing {
                item_id: "item-7".into()
            }
        );
    }

    #[tokio::test]
    async fn expired_upload_is_recreated() {
        let server = MockServer::start().await;
        Mock::given(method("HEAD"))
            .and(path("/v1/uploads/old"))
            .respond_with(ResponseTemplate::new(404))
            .mount(&server)
            .await;
        let fake = FakeTus::default();
        mount_fake(&server, fake.clone()).await;
        let (_d, p, bytes) = file_with(8);
        let tus = Tus::new(
            reqwest::Client::new(),
            format!("{}/v1/uploads", server.uri()).parse().unwrap(),
        );
        let mut st = ResumeState {
            url: Some(format!("{}/v1/uploads/old", server.uri())),
            offset: 4,
        };
        let out = tus
            .upload(&StaticToken, &p, &META, &mut st, |_| {}, || false)
            .await
            .unwrap();
        assert_eq!(
            out,
            Outcome::Uploaded {
                upload_id: "up1".into()
            }
        );
        assert_eq!(fake.data("up1"), bytes);
    }

    #[tokio::test]
    async fn too_large_is_a_permanent_error() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/uploads"))
            .respond_with(ResponseTemplate::new(413).set_body_json(serde_json::json!({
                "title": "Too large", "status": 413, "code": "upload_too_large"
            })))
            .mount(&server)
            .await;
        let (_d, p, _) = file_with(5);
        let tus = Tus::new(
            reqwest::Client::new(),
            format!("{}/v1/uploads", server.uri()).parse().unwrap(),
        );
        let err = tus
            .upload(&StaticToken, &p, &META, &mut ResumeState::default(), |_| {}, || false)
            .await
            .unwrap_err();
        assert!(!err.is_transient());
        assert!(matches!(err, TusError::Api(ApiError::Problem(ref pr)) if pr.code == "upload_too_large"));
    }
}
