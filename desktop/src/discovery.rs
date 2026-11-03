//! Server discovery: `GET /.well-known/kinframe` (docs/architecture.md, "Server discovery").

use serde::{Deserialize, Serialize};
use url::Url;

pub const DEFAULT_SERVER: &str = "https://kinframe.appmire.be";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Discovery {
    pub name: String,
    pub version: String,
    pub api: String,
    #[serde(default)]
    pub web: Option<String>,
    pub auth: AuthInfo,
    pub limits: Limits,
    #[serde(default)]
    pub dev_auth: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AuthInfo {
    #[serde(default)]
    pub issuer: String,
    #[serde(default)]
    pub mobile_client_id: String,
    /// Logto Native app with the loopback redirect; absent on servers bootstrapped before it existed.
    #[serde(default)]
    pub desktop_client_id: Option<String>,
    #[serde(default)]
    pub web_client_id: String,
    #[serde(default)]
    pub api_resource: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Limits {
    #[serde(default = "default_max_items")]
    pub max_items_per_send: u32,
    #[serde(default = "default_max_video")]
    pub max_video_seconds: u32,
    #[serde(default = "default_max_upload")]
    pub max_upload_bytes: u64,
}

fn default_max_items() -> u32 {
    200
}
fn default_max_video() -> u32 {
    300
}
fn default_max_upload() -> u64 {
    2 << 30
}

#[derive(Debug, thiserror::Error, PartialEq)]
pub enum DiscoveryError {
    #[error("That doesn't look like a web address")]
    InvalidUrl,
    #[error("Only http and https addresses work")]
    UnsupportedScheme,
    #[error("This server is not a kinframe server")]
    NotKinframe,
    #[error("Can't reach the server: {0}")]
    Unreachable(String),
}

/// Turns what someone typed ("frames.example.org", "https://frames.example.org/", a pasted discovery URL, a
/// join link) into the server's origin URL. Common typos are tolerated, like the frame's setup wizard does.
pub fn normalize_server_url(input: &str) -> Result<Url, DiscoveryError> {
    let mut s = input.trim().trim_end_matches('/').to_string();
    if s.is_empty() {
        return Err(DiscoveryError::InvalidUrl);
    }
    for typo in ["htps://", "htttps://", "https//", "https:/"] {
        if let Some(rest) = s.strip_prefix(typo)
            && !s.starts_with("https://")
        {
            s = format!("https://{}", rest.trim_start_matches('/'));
            break;
        }
    }
    if !s.contains("://") {
        s = format!("https://{s}");
    }
    let mut url = Url::parse(&s).map_err(|_| DiscoveryError::InvalidUrl)?;
    if url.scheme() != "https" && url.scheme() != "http" {
        return Err(DiscoveryError::UnsupportedScheme);
    }
    let host = url.host_str().ok_or(DiscoveryError::InvalidUrl)?;
    if host.is_empty() || host.contains(' ') {
        return Err(DiscoveryError::InvalidUrl);
    }
    // Keep only the origin: discovery lives at the root.
    url.set_path("/");
    url.set_query(None);
    url.set_fragment(None);
    let _ = url.set_username("");
    let _ = url.set_password(None);
    Ok(url)
}

/// The origin as shown and stored: `https://host[:port]`, no trailing slash.
pub fn origin_string(url: &Url) -> String {
    url.as_str().trim_end_matches('/').to_string()
}

/// The well-known discovery URL for a server origin.
pub fn discovery_url(server: &Url) -> Url {
    server.join("/.well-known/kinframe").expect("static path joins")
}

/// Parses and sanity-checks a discovery document.
pub fn parse(body: &[u8]) -> Result<Discovery, DiscoveryError> {
    let d: Discovery = serde_json::from_slice(body).map_err(|_| DiscoveryError::NotKinframe)?;
    let api = Url::parse(&d.api).map_err(|_| DiscoveryError::NotKinframe)?;
    if api.scheme() != "https" && api.scheme() != "http" {
        return Err(DiscoveryError::NotKinframe);
    }
    if !d.dev_auth && Url::parse(&d.auth.issuer).is_err() {
        return Err(DiscoveryError::NotKinframe);
    }
    Ok(d)
}

pub async fn fetch(http: &reqwest::Client, server: &Url) -> Result<Discovery, DiscoveryError> {
    let resp = http
        .get(discovery_url(server))
        .header("Accept", "application/json")
        .send()
        .await
        .map_err(|e| DiscoveryError::Unreachable(short_error(&e)))?;
    if !resp.status().is_success() {
        return Err(DiscoveryError::NotKinframe);
    }
    let body = resp
        .bytes()
        .await
        .map_err(|e| DiscoveryError::Unreachable(short_error(&e)))?;
    parse(&body)
}

fn short_error(e: &reqwest::Error) -> String {
    if e.is_timeout() {
        "timed out".into()
    } else if e.is_connect() {
        "connection failed".into()
    } else {
        e.to_string()
    }
}

impl Discovery {
    /// API base with a trailing slash, so `join("frames")` works.
    pub fn api_base(&self) -> Url {
        let mut s = self.api.trim_end_matches('/').to_string();
        s.push('/');
        Url::parse(&s).expect("validated in parse")
    }

    pub fn api_url(&self, path: &str) -> Url {
        self.api_base()
            .join(path.trim_start_matches('/'))
            .expect("relative API path")
    }

    /// The user websocket (`/v1/ws`, docs/api/README.md §6.2).
    pub fn ws_url(&self, access_token: &str) -> Url {
        let mut u = self.api_url("ws");
        let scheme = if u.scheme() == "https" { "wss" } else { "ws" };
        u.set_scheme(scheme).expect("ws scheme");
        u.query_pairs_mut().append_pair("access_token", access_token);
        u
    }

    /// The Logto client the desktop app signs in with.
    pub fn desktop_client_id(&self) -> Option<&str> {
        self.auth.desktop_client_id.as_deref().filter(|s| !s.is_empty())
    }

    pub fn logto_available(&self) -> bool {
        !self.auth.issuer.is_empty() && self.desktop_client_id().is_some()
    }

    /// `https://host` of the API, used to key tokens and the sent index per server.
    pub fn server_key(&self) -> String {
        let u = self.api_base();
        let mut key = format!("{}://{}", u.scheme(), u.host_str().unwrap_or_default());
        if let Some(p) = u.port() {
            key.push_str(&format!(":{p}"));
        }
        key
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const DOC: &str = r#"{
        "name": "Trappers family", "version": "0.1.0", "api": "https://frames.example.org/v1",
        "web": "https://frames.example.org",
        "auth": {"issuer": "https://auth.frames.example.org/oidc", "mobile_client_id": "m", "web_client_id": "w",
                 "desktop_client_id": "d", "api_resource": "https://frames.example.org/v1"},
        "limits": {"max_items_per_send": 150, "max_video_seconds": 300, "max_upload_bytes": 1000},
        "future_field": {"ignored": true}
    }"#;

    #[test]
    fn parses_discovery() {
        let d = parse(DOC.as_bytes()).unwrap();
        assert_eq!(d.name, "Trappers family");
        assert_eq!(d.limits.max_items_per_send, 150);
        assert_eq!(d.desktop_client_id(), Some("d"));
        assert!(d.logto_available());
        assert!(!d.dev_auth);
        assert_eq!(d.api_url("/frames").as_str(), "https://frames.example.org/v1/frames");
        assert_eq!(
            d.ws_url("a b").as_str(),
            "wss://frames.example.org/v1/ws?access_token=a+b"
        );
        assert_eq!(d.server_key(), "https://frames.example.org");
    }

    #[test]
    fn older_servers_and_dev_servers() {
        let old = DOC.replace(r#""desktop_client_id": "d","#, "");
        let d = parse(old.as_bytes()).unwrap();
        assert_eq!(d.desktop_client_id(), None);
        assert!(!d.logto_available());

        let dev = r#"{"name":"dev","version":"0","api":"http://localhost:8080/v1","auth":{"issuer":""},
                      "limits":{},"dev_auth":true}"#;
        let d = parse(dev.as_bytes()).unwrap();
        assert!(d.dev_auth);
        assert_eq!(d.limits.max_items_per_send, 200);
        assert_eq!(d.ws_url("t").as_str(), "ws://localhost:8080/v1/ws?access_token=t");
        assert_eq!(d.server_key(), "http://localhost:8080");
    }

    #[test]
    fn rejects_non_kinframe() {
        assert_eq!(parse(b"<html>"), Err(DiscoveryError::NotKinframe));
        assert_eq!(parse(br#"{"name":"x"}"#), Err(DiscoveryError::NotKinframe));
        let no_issuer = DOC.replace("https://auth.frames.example.org/oidc", "");
        assert_eq!(parse(no_issuer.as_bytes()), Err(DiscoveryError::NotKinframe));
    }

    #[test]
    fn normalizes_typed_urls() {
        let n = |s| normalize_server_url(s).map(|u| origin_string(&u));
        assert_eq!(n("frames.example.org").unwrap(), "https://frames.example.org");
        assert_eq!(
            n("  https://frames.example.org/  ").unwrap(),
            "https://frames.example.org"
        );
        assert_eq!(n("htps://frames.example.org").unwrap(), "https://frames.example.org");
        assert_eq!(n("https//frames.example.org").unwrap(), "https://frames.example.org");
        assert_eq!(
            n("https://frames.example.org/.well-known/kinframe").unwrap(),
            "https://frames.example.org"
        );
        assert_eq!(
            n("https://frames.example.org/f/48213907").unwrap(),
            "https://frames.example.org"
        );
        assert_eq!(n("http://localhost:8080").unwrap(), "http://localhost:8080");
        assert_eq!(n(""), Err(DiscoveryError::InvalidUrl));
        assert_eq!(n("ftp://x.org"), Err(DiscoveryError::UnsupportedScheme));
        assert_eq!(n("https://exa mple.org"), Err(DiscoveryError::InvalidUrl));
        assert_eq!(
            discovery_url(&normalize_server_url("x.org").unwrap()).as_str(),
            "https://x.org/.well-known/kinframe"
        );
    }
}
