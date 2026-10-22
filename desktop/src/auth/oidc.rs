//! OIDC against the server's Logto: Authorization Code + PKCE, refresh and revocation (docs/api/README.md §3.1).

use base64::Engine as _;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::Deserialize;
use url::Url;

use super::pkce::Pkce;

/// The scopes the API needs; `offline_access` gives the refresh token that keeps the app signed in.
pub const SCOPES: &str = "openid profile email offline_access";

#[derive(Debug, Clone, Deserialize)]
pub struct ProviderMetadata {
    pub authorization_endpoint: String,
    pub token_endpoint: String,
    #[serde(default)]
    pub revocation_endpoint: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct TokenResponse {
    pub access_token: String,
    #[serde(default)]
    pub refresh_token: Option<String>,
    #[serde(default)]
    pub id_token: Option<String>,
    #[serde(default = "default_expires")]
    pub expires_in: u64,
}

fn default_expires() -> u64 {
    3600
}

#[derive(Debug, thiserror::Error)]
pub enum OidcError {
    #[error("network error: {0}")]
    Network(#[from] reqwest::Error),
    /// The refresh token (or code) is no longer valid: sign in again.
    #[error("the sign-in expired")]
    InvalidGrant,
    #[error("sign-in failed: {0}")]
    Other(String),
}

pub async fn metadata(http: &reqwest::Client, issuer: &str) -> Result<ProviderMetadata, OidcError> {
    let url = format!("{}/.well-known/openid-configuration", issuer.trim_end_matches('/'));
    let resp = http.get(url).send().await?;
    if !resp.status().is_success() {
        return Err(OidcError::Other(format!("OIDC discovery returned {}", resp.status())));
    }
    Ok(resp.json().await?)
}

pub struct AuthorizeRequest<'a> {
    pub client_id: &'a str,
    pub redirect_uri: &'a str,
    pub resource: &'a str,
    pub state: &'a str,
    pub pkce: &'a Pkce,
    /// `ui_locales`, so the sign-in page and code e-mail use the app's language.
    pub locale: &'a str,
    /// After an explicit sign-out, ask for the account again instead of reusing the browser session.
    pub force_login: bool,
}

pub fn authorize_url(meta: &ProviderMetadata, r: &AuthorizeRequest<'_>) -> Result<Url, OidcError> {
    let mut url =
        Url::parse(&meta.authorization_endpoint).map_err(|_| OidcError::Other("bad authorization endpoint".into()))?;
    // Logto only issues a refresh token for offline_access when consent is prompted.
    let prompt = if r.force_login { "login consent" } else { "consent" };
    url.query_pairs_mut()
        .append_pair("response_type", "code")
        .append_pair("client_id", r.client_id)
        .append_pair("redirect_uri", r.redirect_uri)
        .append_pair("scope", SCOPES)
        .append_pair("resource", r.resource)
        .append_pair("state", r.state)
        .append_pair("code_challenge", &r.pkce.challenge)
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", prompt)
        .append_pair("ui_locales", &format!("{} en", r.locale));
    Ok(url)
}

async fn token_request(
    http: &reqwest::Client,
    meta: &ProviderMetadata,
    form: &[(&str, &str)],
) -> Result<TokenResponse, OidcError> {
    let resp = http.post(&meta.token_endpoint).form(form).send().await?;
    if resp.status().is_success() {
        return Ok(resp.json().await?);
    }
    #[derive(Deserialize)]
    struct Err {
        error: Option<String>,
        error_description: Option<String>,
    }
    let status = resp.status();
    let body: Option<Err> = resp.json().await.ok();
    match body {
        Some(Err { error: Some(e), .. }) if e == "invalid_grant" => Err(OidcError::InvalidGrant),
        Some(Err {
            error,
            error_description,
        }) => Err(OidcError::Other(
            error_description.or(error).unwrap_or_else(|| status.to_string()),
        )),
        None => Err(OidcError::Other(status.to_string())),
    }
}

pub async fn exchange_code(
    http: &reqwest::Client,
    meta: &ProviderMetadata,
    client_id: &str,
    code: &str,
    redirect_uri: &str,
    pkce: &Pkce,
    resource: &str,
) -> Result<TokenResponse, OidcError> {
    token_request(
        http,
        meta,
        &[
            ("grant_type", "authorization_code"),
            ("client_id", client_id),
            ("code", code),
            ("redirect_uri", redirect_uri),
            ("code_verifier", &pkce.verifier),
            ("resource", resource),
        ],
    )
    .await
}

pub async fn refresh(
    http: &reqwest::Client,
    meta: &ProviderMetadata,
    client_id: &str,
    refresh_token: &str,
    resource: &str,
) -> Result<TokenResponse, OidcError> {
    token_request(
        http,
        meta,
        &[
            ("grant_type", "refresh_token"),
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("resource", resource),
        ],
    )
    .await
}

/// Best effort: revokes the refresh token so a stolen copy is useless after sign-out.
pub async fn revoke(http: &reqwest::Client, meta: &ProviderMetadata, client_id: &str, token: &str) {
    if let Some(ep) = &meta.revocation_endpoint {
        let _ = http
            .post(ep)
            .form(&[
                ("client_id", client_id),
                ("token", token),
                ("token_type_hint", "refresh_token"),
            ])
            .send()
            .await;
    }
}

/// The `name` claim of an ID token. The token came straight from the token endpoint over TLS, so its payload is
/// read without verifying the signature (OIDC Core §3.1.3.7 allows this for the code flow).
pub fn id_token_name(id_token: &str) -> Option<String> {
    let payload = id_token.split('.').nth(1)?;
    let bytes = URL_SAFE_NO_PAD.decode(payload.trim_end_matches('=')).ok()?;
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    ["name", "username"]
        .iter()
        .find_map(|k| v.get(k).and_then(|n| n.as_str()))
        .map(|s| s.trim().chars().take(40).collect::<String>())
        .filter(|s| !s.is_empty())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{body_string_contains, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn meta(base: &str) -> ProviderMetadata {
        ProviderMetadata {
            authorization_endpoint: format!("{base}/oidc/auth"),
            token_endpoint: format!("{base}/oidc/token"),
            revocation_endpoint: Some(format!("{base}/oidc/token/revocation")),
        }
    }

    #[test]
    fn builds_the_authorize_url() {
        let pkce = Pkce::from_verifier("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk".into());
        let url = authorize_url(
            &meta("https://auth.example.org"),
            &AuthorizeRequest {
                client_id: "desk",
                redirect_uri: "http://127.0.0.1:5555/callback",
                resource: "https://k.example.org/v1",
                state: "st",
                pkce: &pkce,
                locale: "nl",
                force_login: false,
            },
        )
        .unwrap();
        let q: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
        assert_eq!(q["response_type"], "code");
        assert_eq!(q["client_id"], "desk");
        assert_eq!(q["redirect_uri"], "http://127.0.0.1:5555/callback");
        assert_eq!(q["code_challenge"], "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        assert_eq!(q["code_challenge_method"], "S256");
        assert_eq!(q["scope"], SCOPES);
        assert_eq!(q["prompt"], "consent");
        assert_eq!(q["ui_locales"], "nl en");
    }

    #[tokio::test]
    async fn exchanges_and_refreshes() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/oidc/token"))
            .and(body_string_contains("grant_type=authorization_code"))
            .and(body_string_contains("code_verifier=v"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "access_token": "at1", "refresh_token": "rt1", "expires_in": 3600, "token_type": "Bearer"
            })))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/oidc/token"))
            .and(body_string_contains("refresh_token=gone"))
            .respond_with(ResponseTemplate::new(400).set_body_json(serde_json::json!({"error": "invalid_grant"})))
            .mount(&server)
            .await;
        let http = reqwest::Client::new();
        let m = meta(&server.uri());
        let t = exchange_code(
            &http,
            &m,
            "c",
            "code",
            "http://127.0.0.1:1/callback",
            &Pkce::from_verifier("v".into()),
            "r",
        )
        .await
        .unwrap();
        assert_eq!(t.access_token, "at1");
        assert_eq!(t.refresh_token.as_deref(), Some("rt1"));
        let err = refresh(&http, &m, "c", "gone", "r").await.unwrap_err();
        assert!(matches!(err, OidcError::InvalidGrant));
    }

    #[test]
    fn reads_the_name_claim() {
        let payload = URL_SAFE_NO_PAD.encode(br#"{"sub":"1","name":"Oma Trappers"}"#);
        assert_eq!(
            id_token_name(&format!("h.{payload}.s")).as_deref(),
            Some("Oma Trappers")
        );
        let none = URL_SAFE_NO_PAD.encode(br#"{"sub":"1","name":"  "}"#);
        assert_eq!(id_token_name(&format!("h.{none}.s")), None);
        assert_eq!(id_token_name("garbage"), None);
    }
}
