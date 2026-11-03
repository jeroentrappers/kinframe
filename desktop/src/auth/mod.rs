//! Signing in: Logto (RFC 8252 loopback + PKCE in the system browser) or dev auth on development servers.

pub mod loopback;
pub mod oidc;
pub mod pkce;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::discovery::Discovery;

const KEYRING_SERVICE: &str = "app.kinframe.desktop";
/// Refresh this long before the access token expires.
const EXPIRY_MARGIN: Duration = Duration::from_secs(60);

// ------------------------------------------------------------------ secret storage

/// Where the refresh token lives: the OS keychain (Keychain, Credential Manager, Secret Service).
pub trait SecretStore: Send + Sync {
    fn get(&self, key: &str) -> Option<String>;
    fn set(&self, key: &str, value: &str) -> bool;
    fn delete(&self, key: &str);
    /// False when secrets only live for this run (no keychain available).
    fn persistent(&self) -> bool;
}

pub struct KeyringStore;

impl SecretStore for KeyringStore {
    fn get(&self, key: &str) -> Option<String> {
        keyring::Entry::new(KEYRING_SERVICE, key).ok()?.get_password().ok()
    }
    fn set(&self, key: &str, value: &str) -> bool {
        keyring::Entry::new(KEYRING_SERVICE, key)
            .and_then(|e| e.set_password(value))
            .map_err(|e| log::warn!("keychain write failed: {e}"))
            .is_ok()
    }
    fn delete(&self, key: &str) {
        if let Ok(e) = keyring::Entry::new(KEYRING_SERVICE, key) {
            let _ = e.delete_credential();
        }
    }
    fn persistent(&self) -> bool {
        true
    }
}

#[derive(Default)]
pub struct MemoryStore(Mutex<HashMap<String, String>>);

impl SecretStore for MemoryStore {
    fn get(&self, key: &str) -> Option<String> {
        self.0.lock().unwrap().get(key).cloned()
    }
    fn set(&self, key: &str, value: &str) -> bool {
        self.0.lock().unwrap().insert(key.into(), value.into());
        true
    }
    fn delete(&self, key: &str) {
        self.0.lock().unwrap().remove(key);
    }
    fn persistent(&self) -> bool {
        false
    }
}

/// The OS keychain when it works, else memory (the person then signs in again next time).
pub fn default_store() -> Arc<dyn SecretStore> {
    match keyring::Entry::store_status() {
        Ok(()) => Arc::new(KeyringStore),
        Err(e) => {
            log::warn!("no OS keychain ({e}); the sign-in lasts until the app closes");
            Arc::new(MemoryStore::default())
        }
    }
}

pub fn refresh_key(server_key: &str) -> String {
    format!("refresh_token {server_key}")
}

// ------------------------------------------------------------------ session

#[derive(Debug, Clone, thiserror::Error, PartialEq)]
pub enum AuthError {
    #[error("Your sign-in expired. Please sign in again.")]
    Expired,
    #[error("Can't reach the server ({0})")]
    Network(String),
    #[error("{0}")]
    Other(String),
}

impl From<oidc::OidcError> for AuthError {
    fn from(e: oidc::OidcError) -> Self {
        match e {
            oidc::OidcError::InvalidGrant => AuthError::Expired,
            oidc::OidcError::Network(e) => AuthError::Network(e.to_string()),
            oidc::OidcError::Other(s) => AuthError::Other(s),
        }
    }
}

#[derive(Clone)]
pub enum Credentials {
    Logto {
        meta: oidc::ProviderMetadata,
        client_id: String,
        resource: String,
        refresh_token: String,
    },
    Dev {
        email: String,
        name: String,
    },
}

/// Hands out access tokens for one server, refreshing them as needed. Shared by the API client, the uploader
/// and the websocket.
pub struct Session {
    http: reqwest::Client,
    discovery: Discovery,
    store: Arc<dyn SecretStore>,
    creds: tokio::sync::Mutex<Credentials>,
    cached: Mutex<Option<(String, Instant)>>,
}

impl Session {
    pub fn new(
        http: reqwest::Client,
        discovery: Discovery,
        store: Arc<dyn SecretStore>,
        creds: Credentials,
        access: Option<(String, u64)>,
    ) -> Self {
        let cached = access.map(|(t, exp)| (t, Instant::now() + Duration::from_secs(exp)));
        Self {
            http,
            discovery,
            store,
            creds: tokio::sync::Mutex::new(creds),
            cached: Mutex::new(cached),
        }
    }

    pub fn discovery(&self) -> &Discovery {
        &self.discovery
    }

    pub fn is_dev(&self) -> bool {
        matches!(self.creds.try_lock().as_deref(), Ok(Credentials::Dev { .. }))
    }

    /// Restores a Logto session from the keychain; `None` when there is no stored refresh token.
    pub async fn restore(
        http: reqwest::Client,
        discovery: Discovery,
        store: Arc<dyn SecretStore>,
    ) -> Result<Option<Session>, AuthError> {
        let Some(client_id) = discovery.desktop_client_id().map(str::to_string) else {
            return Ok(None);
        };
        let Some(refresh_token) = store.get(&refresh_key(&discovery.server_key())) else {
            return Ok(None);
        };
        let meta = oidc::metadata(&http, &discovery.auth.issuer).await?;
        let resource = discovery.auth.api_resource.clone();
        let s = Session::new(
            http,
            discovery,
            store,
            Credentials::Logto {
                meta,
                client_id,
                resource,
                refresh_token,
            },
            None,
        );
        match s.access_token().await {
            Ok(_) => Ok(Some(s)),
            Err(AuthError::Expired) => {
                s.forget();
                Ok(None)
            }
            Err(e) => Err(e),
        }
    }

    /// A valid access token, refreshed (once, even with concurrent callers) when it is about to expire.
    pub async fn access_token(&self) -> Result<String, AuthError> {
        if let Some(t) = self.fresh_cached() {
            return Ok(t);
        }
        let mut creds = self.creds.lock().await;
        if let Some(t) = self.fresh_cached() {
            return Ok(t);
        }
        let (token, expires_in) = match &mut *creds {
            Credentials::Logto {
                meta,
                client_id,
                resource,
                refresh_token,
            } => {
                let r = oidc::refresh(&self.http, meta, client_id, refresh_token, resource).await?;
                if let Some(rt) = r.refresh_token {
                    // Logto rotates refresh tokens.
                    *refresh_token = rt;
                    self.store
                        .set(&refresh_key(&self.discovery.server_key()), refresh_token);
                }
                (r.access_token, r.expires_in)
            }
            Credentials::Dev { email, name } => dev_token(&self.http, &self.discovery, email, name).await?,
        };
        *self.cached.lock().unwrap() = Some((token.clone(), Instant::now() + Duration::from_secs(expires_in)));
        Ok(token)
    }

    fn fresh_cached(&self) -> Option<String> {
        let c = self.cached.lock().unwrap();
        c.as_ref()
            .filter(|(_, exp)| Instant::now() + EXPIRY_MARGIN < *exp)
            .map(|(t, _)| t.clone())
    }

    /// Drops the cached access token, e.g. after a 401, so the next call refreshes.
    pub fn invalidate(&self) {
        *self.cached.lock().unwrap() = None;
    }

    fn forget(&self) {
        self.store.delete(&refresh_key(&self.discovery.server_key()));
    }

    /// Revokes the refresh token and removes it from the keychain.
    pub async fn sign_out(&self) {
        let creds = self.creds.lock().await.clone();
        if let Credentials::Logto {
            meta,
            client_id,
            refresh_token,
            ..
        } = creds
        {
            oidc::revoke(&self.http, &meta, &client_id, &refresh_token).await;
        }
        self.forget();
        self.invalidate();
    }
}

/// `POST /dev/token` on development servers (discovery `dev_auth: true`).
pub async fn dev_token(
    http: &reqwest::Client,
    discovery: &Discovery,
    email: &str,
    name: &str,
) -> Result<(String, u64), AuthError> {
    let base = discovery.api_base();
    let url = base.join("/dev/token").map_err(|e| AuthError::Other(e.to_string()))?;
    let resp = http
        .post(url)
        .json(&serde_json::json!({"email": email, "name": name}))
        .send()
        .await
        .map_err(|e| AuthError::Network(e.to_string()))?;
    if !resp.status().is_success() {
        return Err(AuthError::Other(format!("dev sign-in failed ({})", resp.status())));
    }
    #[derive(serde::Deserialize)]
    struct T {
        access_token: String,
        #[serde(default)]
        expires_in: Option<u64>,
    }
    let t: T = resp.json().await.map_err(|e| AuthError::Other(e.to_string()))?;
    Ok((t.access_token, t.expires_in.unwrap_or(3600)))
}

/// What a finished browser sign-in yields.
pub struct SignedIn {
    pub session: Session,
    /// The `name` claim, used to replace the server's placeholder display name.
    pub name_claim: Option<String>,
}

/// Runs the whole RFC 8252 flow: loopback listener, system browser, code exchange. `open` receives the
/// authorization URL (it opens the browser and lets the UI offer "open again").
pub async fn sign_in_logto(
    http: reqwest::Client,
    discovery: Discovery,
    store: Arc<dyn SecretStore>,
    locale: &str,
    force_login: bool,
    done_page: String,
    open: impl FnOnce(url::Url),
) -> Result<SignedIn, AuthError> {
    let client_id = discovery
        .desktop_client_id()
        .ok_or_else(|| AuthError::Other("This server does not support the desktop app yet.".into()))?
        .to_string();
    let meta = oidc::metadata(&http, &discovery.auth.issuer).await?;
    let lb = loopback::Loopback::bind()
        .await
        .map_err(|e| AuthError::Other(e.to_string()))?;
    let redirect_uri = lb.redirect_uri();
    let pkce = pkce::Pkce::new();
    let state = pkce::random_urlsafe(16);
    let resource = discovery.auth.api_resource.clone();
    let url = oidc::authorize_url(
        &meta,
        &oidc::AuthorizeRequest {
            client_id: &client_id,
            redirect_uri: &redirect_uri,
            resource: &resource,
            state: &state,
            pkce: &pkce,
            locale,
            force_login,
        },
    )?;
    open(url);
    let cb = lb
        .wait(&done_page, Duration::from_secs(15 * 60))
        .await
        .map_err(|e| AuthError::Other(e.to_string()))?;
    let code = match cb {
        loopback::Callback::Code { code, state: s } if s == state => code,
        loopback::Callback::Code { .. } => return Err(AuthError::Other("state mismatch".into())),
        loopback::Callback::Error { error, description } => {
            return Err(AuthError::Other(description.unwrap_or(error)));
        }
    };
    let tokens = oidc::exchange_code(&http, &meta, &client_id, &code, &redirect_uri, &pkce, &resource).await?;
    let refresh_token = tokens
        .refresh_token
        .clone()
        .ok_or_else(|| AuthError::Other("no refresh token".into()))?;
    store.set(&refresh_key(&discovery.server_key()), &refresh_token);
    let name_claim = tokens.id_token.as_deref().and_then(oidc::id_token_name);
    let session = Session::new(
        http,
        discovery,
        store,
        Credentials::Logto {
            meta,
            client_id,
            resource,
            refresh_token,
        },
        Some((tokens.access_token, tokens.expires_in)),
    );
    Ok(SignedIn { session, name_claim })
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn discovery(base: &str) -> Discovery {
        crate::discovery::parse(
            serde_json::json!({
                "name": "t", "version": "0", "api": format!("{base}/v1"),
                "auth": {"issuer": format!("{base}/oidc"), "desktop_client_id": "desk", "api_resource": "res"},
                "limits": {}
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap()
    }

    #[tokio::test]
    async fn restores_refreshes_and_rotates() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/oidc/.well-known/openid-configuration"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "authorization_endpoint": format!("{}/oidc/auth", server.uri()),
                "token_endpoint": format!("{}/oidc/token", server.uri()),
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/oidc/token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "at", "refresh_token": "rt2", "expires_in": 3600
            })))
            .expect(1)
            .mount(&server)
            .await;
        let d = discovery(&server.uri());
        let store: Arc<dyn SecretStore> = Arc::new(MemoryStore::default());
        let http = reqwest::Client::new();
        assert!(
            Session::restore(http.clone(), d.clone(), store.clone())
                .await
                .unwrap()
                .is_none()
        );

        store.set(&refresh_key(&d.server_key()), "rt1");
        let s = Session::restore(http, d.clone(), store.clone()).await.unwrap().unwrap();
        // Cached: the second call doesn't hit the token endpoint (expect(1) above).
        assert_eq!(s.access_token().await.unwrap(), "at");
        assert_eq!(store.get(&refresh_key(&d.server_key())).as_deref(), Some("rt2"));
        s.sign_out().await;
        assert_eq!(store.get(&refresh_key(&d.server_key())), None);
    }
}
