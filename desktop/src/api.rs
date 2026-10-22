//! REST client for the kinframe API (docs/api/README.md).

use std::sync::Arc;

use reqwest::{Method, RequestBuilder, StatusCode};
use serde::de::DeserializeOwned;

use crate::auth::{AuthError, Session};
use crate::model::*;

#[derive(Debug, Clone, thiserror::Error, PartialEq)]
pub enum ApiError {
    #[error("{0}")]
    Auth(#[from] AuthError),
    #[error("{}", .0.detail.clone().filter(|d| !d.is_empty()).unwrap_or_else(|| .0.title.clone()))]
    Problem(Problem),
    #[error("Can't reach the server ({0})")]
    Network(String),
    #[error("Unexpected answer from the server ({0})")]
    Decode(String),
}

impl ApiError {
    pub fn code(&self) -> Option<&str> {
        match self {
            ApiError::Problem(p) => Some(&p.code),
            _ => None,
        }
    }
    /// Worth retrying later without changing anything.
    pub fn is_transient(&self) -> bool {
        match self {
            ApiError::Network(_) | ApiError::Auth(AuthError::Network(_)) => true,
            ApiError::Problem(p) => p.status >= 500 || p.status == 429,
            _ => false,
        }
    }
}

impl From<reqwest::Error> for ApiError {
    fn from(e: reqwest::Error) -> Self {
        if e.is_decode() {
            ApiError::Decode(e.to_string())
        } else {
            ApiError::Network(e.to_string())
        }
    }
}

/// Reads a problem+json body, falling back to the status line.
pub async fn problem_from(resp: reqwest::Response) -> ApiError {
    let status = resp.status();
    let mut p: Problem = resp.json().await.unwrap_or_default();
    if p.status == 0 {
        p.status = status.as_u16();
    }
    if p.title.is_empty() {
        p.title = status.canonical_reason().unwrap_or("Error").to_string();
    }
    if p.code.is_empty() {
        p.code = match status {
            StatusCode::UNAUTHORIZED => "unauthorized",
            StatusCode::FORBIDDEN => "forbidden",
            StatusCode::NOT_FOUND => "not_found",
            StatusCode::TOO_MANY_REQUESTS => "rate_limited",
            _ => "server_error",
        }
        .into();
    }
    ApiError::Problem(p)
}

#[derive(Clone)]
pub struct Api {
    pub http: reqwest::Client,
    pub session: Arc<Session>,
    locale: String,
}

impl Api {
    pub fn new(http: reqwest::Client, session: Arc<Session>, locale: &str) -> Self {
        Self {
            http,
            session,
            locale: locale.to_string(),
        }
    }

    pub fn discovery(&self) -> &crate::discovery::Discovery {
        self.session.discovery()
    }

    fn request(&self, method: Method, path: &str) -> RequestBuilder {
        self.http
            .request(method, self.discovery().api_url(path))
            .header("Accept", "application/json")
            .header("Accept-Language", &self.locale)
    }

    /// Sends with a bearer token; on 401 refreshes once and retries.
    async fn send(&self, build: impl Fn() -> RequestBuilder) -> Result<reqwest::Response, ApiError> {
        for attempt in 0..2 {
            let token = self.session.access_token().await?;
            let resp = build().bearer_auth(token).send().await?;
            if resp.status() == StatusCode::UNAUTHORIZED && attempt == 0 {
                self.session.invalidate();
                continue;
            }
            if resp.status() == StatusCode::UNAUTHORIZED {
                return Err(ApiError::Auth(AuthError::Expired));
            }
            if !resp.status().is_success() {
                return Err(problem_from(resp).await);
            }
            return Ok(resp);
        }
        unreachable!()
    }

    async fn json<T: DeserializeOwned>(&self, build: impl Fn() -> RequestBuilder) -> Result<T, ApiError> {
        let resp = self.send(build).await?;
        resp.json::<T>().await.map_err(|e| ApiError::Decode(e.to_string()))
    }

    pub async fn me(&self) -> Result<User, ApiError> {
        self.json(|| self.request(Method::GET, "me")).await
    }

    pub async fn update_me(&self, display_name: Option<&str>, locale: Option<&str>) -> Result<User, ApiError> {
        let mut body = serde_json::Map::new();
        if let Some(n) = display_name {
            body.insert("display_name".into(), n.into());
        }
        if let Some(l) = locale {
            body.insert("locale".into(), l.into());
        }
        let body = serde_json::Value::Object(body);
        self.json(|| self.request(Method::PATCH, "me").json(&body)).await
    }

    pub async fn frames(&self) -> Result<Vec<FrameSummary>, ApiError> {
        self.json(|| self.request(Method::GET, "frames")).await
    }

    pub async fn redeem(&self, code: &str, idempotency_key: &str) -> Result<RedeemResult, ApiError> {
        let body = serde_json::json!({ "code": code });
        self.json(|| {
            self.request(Method::POST, "frames/redeem")
                .header("Idempotency-Key", idempotency_key)
                .json(&body)
        })
        .await
    }

    pub async fn sends(&self, cursor: Option<&str>, limit: u32) -> Result<SendsPage, ApiError> {
        self.json(|| {
            let mut q = vec![("limit", limit.to_string())];
            if let Some(c) = cursor {
                q.push(("cursor", c.to_string()));
            }
            self.request(Method::GET, "sends").query(&q)
        })
        .await
    }

    pub async fn send_by_id(&self, id: &str) -> Result<Send, ApiError> {
        self.json(|| self.request(Method::GET, &format!("sends/{id}"))).await
    }

    /// `POST /v1/sends`. The idempotency key makes retries after a crash or timeout safe.
    pub async fn create_send(&self, body: &SendCreate, idempotency_key: &str) -> Result<Send, ApiError> {
        self.json(|| {
            self.request(Method::POST, "sends")
                .header("Idempotency-Key", idempotency_key)
                .json(body)
        })
        .await
    }

    /// Downloads a short-lived signed URL (thumbnails); no Authorization header needed.
    pub async fn fetch_bytes(&self, url: &str) -> Result<Vec<u8>, ApiError> {
        let resp = self.http.get(url).send().await?;
        if !resp.status().is_success() {
            return Err(problem_from(resp).await);
        }
        Ok(resp.bytes().await?.to_vec())
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::auth::{Credentials, MemoryStore};
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    pub(crate) fn dev_api(base: &str) -> Api {
        let d = crate::discovery::parse(
            serde_json::json!({"name":"t","version":"0","api":format!("{base}/v1"),"auth":{},"limits":{},"dev_auth":true})
                .to_string()
                .as_bytes(),
        )
        .unwrap();
        let http = reqwest::Client::new();
        let s = Session::new(
            http.clone(),
            d,
            Arc::new(MemoryStore::default()),
            Credentials::Dev {
                email: "a@b.c".into(),
                name: "A".into(),
            },
            Some(("tok".into(), 3600)),
        );
        Api::new(http, Arc::new(s), "en")
    }

    #[tokio::test]
    async fn problems_and_idempotency() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/v1/frames/redeem"))
            .and(header("Idempotency-Key", "k1"))
            .and(header("Authorization", "Bearer tok"))
            .respond_with(ResponseTemplate::new(404).set_body_json(serde_json::json!({
                "type": "x", "title": "This code doesn't work", "status": 404, "code": "code_invalid"
            })))
            .mount(&server)
            .await;
        let api = dev_api(&server.uri());
        let err = api.redeem("12345678", "k1").await.unwrap_err();
        assert_eq!(err.code(), Some("code_invalid"));
        assert_eq!(err.to_string(), "This code doesn't work");
        assert!(!err.is_transient());
    }

    #[tokio::test]
    async fn retries_once_after_401() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/dev/token"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({"access_token": "fresh", "expires_in": 3600})),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v1/frames"))
            .and(header("Authorization", "Bearer tok"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/v1/frames"))
            .and(header("Authorization", "Bearer fresh"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!([
                {"id": "f1", "name": "Oma", "role": "member", "permissions": {"send": true}, "online": true}
            ])))
            .mount(&server)
            .await;
        let frames = dev_api(&server.uri()).frames().await.unwrap();
        assert_eq!(frames[0].name, "Oma");
        assert!(frames[0].can_send());
    }
}
