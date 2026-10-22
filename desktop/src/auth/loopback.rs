//! The loopback redirect receiver for RFC 8252 §7.3: listens on `127.0.0.1:<random port>` for exactly one
//! `GET /callback?code=…&state=…` from the system browser.

use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Callback {
    Code {
        code: String,
        state: String,
    },
    /// The authorization server sent `error=…` (e.g. the person cancelled).
    Error {
        error: String,
        description: Option<String>,
    },
}

pub struct Loopback {
    listener: TcpListener,
    port: u16,
}

impl Loopback {
    /// Binds a random free port on the IPv4 loopback interface.
    pub async fn bind() -> std::io::Result<Self> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        Ok(Self { listener, port })
    }

    pub fn redirect_uri(&self) -> String {
        format!("http://127.0.0.1:{}/callback", self.port)
    }

    /// Waits for the browser's redirect and answers it with `page_html`. Other requests (favicon, probes) get a
    /// 404 and are ignored.
    pub async fn wait(self, page_html: &str, timeout: Duration) -> std::io::Result<Callback> {
        let fut = async {
            loop {
                let (mut stream, _) = self.listener.accept().await?;
                match read_target(&mut stream).await {
                    Ok(Some(target)) => {
                        if let Some(cb) = parse_callback(&target) {
                            respond(&mut stream, "200 OK", page_html).await;
                            return Ok(cb);
                        }
                        respond(&mut stream, "404 Not Found", "").await;
                    }
                    _ => respond(&mut stream, "400 Bad Request", "").await,
                }
            }
        };
        tokio::time::timeout(timeout, fut)
            .await
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "sign-in timed out"))?
    }
}

/// Reads the request head and returns the request target of a GET.
async fn read_target(stream: &mut TcpStream) -> std::io::Result<Option<String>> {
    let mut buf = Vec::with_capacity(2048);
    let mut chunk = [0u8; 1024];
    while !buf.windows(4).any(|w| w == b"\r\n\r\n") {
        let n = tokio::time::timeout(Duration::from_secs(10), stream.read(&mut chunk))
            .await
            .map_err(|_| std::io::Error::from(std::io::ErrorKind::TimedOut))??;
        if n == 0 || buf.len() > 16 * 1024 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
    }
    let head = String::from_utf8_lossy(&buf);
    let mut parts = head.lines().next().unwrap_or_default().split_whitespace();
    match (parts.next(), parts.next()) {
        (Some("GET"), Some(target)) => Ok(Some(target.to_string())),
        _ => Ok(None),
    }
}

async fn respond(stream: &mut TcpStream, status: &str, body: &str) {
    let resp = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(resp.as_bytes()).await;
    let _ = stream.shutdown().await;
}

/// Parses `/callback?code=…&state=…` (or `?error=…`). Anything else is `None`.
pub fn parse_callback(target: &str) -> Option<Callback> {
    let url = url::Url::parse(&format!("http://127.0.0.1{target}")).ok()?;
    if url.path() != "/callback" {
        return None;
    }
    let get = |k: &str| {
        url.query_pairs()
            .find(|(name, _)| name == k)
            .map(|(_, v)| v.into_owned())
    };
    if let Some(error) = get("error") {
        return Some(Callback::Error {
            error,
            description: get("error_description"),
        });
    }
    Some(Callback::Code {
        code: get("code")?,
        state: get("state")?,
    })
}

/// The page the browser shows after the redirect.
pub fn done_page(title: &str, body: &str) -> String {
    let esc = |s: &str| s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;");
    format!(
        r##"<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>kinframe</title><style>
body{{margin:0;min-height:100vh;display:grid;place-items:center;background:#f8f3ec;color:#2a221d;
font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}
main{{max-width:28rem;padding:2rem;text-align:center}}h1{{font:600 1.6rem/1.2 Georgia,serif;margin:1rem 0 .5rem}}
p{{color:#675a50}}@media (prefers-color-scheme:dark){{body{{background:#191512;color:#f3ebe3}}p{{color:#bdaea1}}}}
</style></head><body><main>
<svg width="56" height="56" viewBox="0 0 64 64" aria-hidden="true"><rect x="4" y="8" width="56" height="48" rx="12" fill="#a7471e"/><rect x="11" y="15" width="42" height="34" rx="6" fill="#fbf1e6"/><circle cx="41" cy="26" r="5" fill="#e7a23a"/><path d="M11 43 23 31l9 9 6-6 15 15H17a6 6 0 0 1-6-6z" fill="#4f7a5a"/></svg>
<h1>{}</h1><p>{}</p></main></body></html>"##,
        esc(title),
        esc(body)
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_callbacks() {
        assert_eq!(
            parse_callback("/callback?code=abc&state=xyz"),
            Some(Callback::Code {
                code: "abc".into(),
                state: "xyz".into()
            })
        );
        assert_eq!(
            parse_callback("/callback?error=access_denied&error_description=User%20cancelled&state=s"),
            Some(Callback::Error {
                error: "access_denied".into(),
                description: Some("User cancelled".into())
            })
        );
        assert_eq!(parse_callback("/favicon.ico"), None);
        assert_eq!(parse_callback("/callback?code=abc"), None);
    }

    #[tokio::test]
    async fn receives_the_redirect() {
        let lb = Loopback::bind().await.unwrap();
        let uri = lb.redirect_uri();
        assert!(uri.starts_with("http://127.0.0.1:") && uri.ends_with("/callback"));
        let port: u16 = uri
            .trim_start_matches("http://127.0.0.1:")
            .trim_end_matches("/callback")
            .parse()
            .unwrap();
        let waiter = tokio::spawn(lb.wait("<p>ok</p>", Duration::from_secs(5)));
        let http = reqwest::Client::new();
        let probe = http
            .get(format!("http://127.0.0.1:{port}/favicon.ico"))
            .send()
            .await
            .unwrap();
        assert_eq!(probe.status(), 404);
        let resp = http
            .get(format!("http://127.0.0.1:{port}/callback?code=c1&state=s1"))
            .send()
            .await
            .unwrap();
        assert_eq!(resp.status(), 200);
        assert_eq!(resp.text().await.unwrap(), "<p>ok</p>");
        assert_eq!(
            waiter.await.unwrap().unwrap(),
            Callback::Code {
                code: "c1".into(),
                state: "s1".into()
            }
        );
    }
}
