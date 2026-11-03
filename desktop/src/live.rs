//! The user websocket `GET /v1/ws?access_token=…` (docs/api/README.md §6.2), with reconnect back-off.

use std::sync::Arc;
use std::time::Duration;

use futures_util::StreamExt;
use serde::Deserialize;
use tokio_tungstenite::tungstenite::Message;

use crate::auth::{AuthError, Session};

#[derive(Debug, Clone, PartialEq)]
pub enum LiveEvent {
    Connected,
    Disconnected,
    /// A send's processing, delivery or seen counts changed.
    SendUpdated {
        send_id: String,
    },
    Reaction {
        placement_id: String,
        frame_id: String,
        reaction: Option<String>,
    },
    Reply {
        placement_id: String,
    },
    FrameStatus {
        frame_id: String,
        online: bool,
    },
    /// Roles or memberships changed: reload frames.
    FramesChanged,
}

#[derive(Deserialize)]
struct Raw {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    send: Option<serde_json::Value>,
    #[serde(default)]
    placement_id: Option<String>,
    #[serde(default)]
    frame_id: Option<String>,
    #[serde(default)]
    reaction: Option<String>,
    #[serde(default)]
    online: Option<bool>,
}

/// Maps one text frame to an event; unknown types are ignored (§7: clients must ignore them).
pub fn parse(text: &str) -> Option<LiveEvent> {
    let r: Raw = serde_json::from_str(text).ok()?;
    Some(match r.kind.as_str() {
        "send.updated" => LiveEvent::SendUpdated {
            send_id: r.send?.get("id")?.as_str()?.to_string(),
        },
        "reaction" => LiveEvent::Reaction {
            placement_id: r.placement_id?,
            frame_id: r.frame_id.unwrap_or_default(),
            reaction: r.reaction,
        },
        "reply" => LiveEvent::Reply {
            placement_id: r.placement_id?,
        },
        "frame.status" => LiveEvent::FrameStatus {
            frame_id: r.frame_id?,
            online: r.online.unwrap_or(false),
        },
        "membership.updated" => LiveEvent::FramesChanged,
        _ => return None,
    })
}

/// Reconnect delay: 1 s doubling to 60 s, ±20 % jitter.
pub fn backoff(attempt: u32) -> Duration {
    let base = (1u64 << attempt.min(6)).min(60) as f64;
    let mut b = [0u8; 2];
    let _ = getrandom::fill(&mut b);
    let jitter = (u16::from_le_bytes(b) as f64 / u16::MAX as f64) * 0.4 - 0.2;
    Duration::from_secs_f64(base * (1.0 + jitter))
}

/// Runs until the task is aborted. Events go to `emit`.
pub async fn run(session: Arc<Session>, emit: impl Fn(LiveEvent) + Send + Sync + 'static) {
    let mut attempt = 0u32;
    loop {
        match session.access_token().await {
            Ok(token) => {
                let url = session.discovery().ws_url(&token);
                match tokio_tungstenite::connect_async(url.as_str()).await {
                    Ok((mut ws, _)) => {
                        attempt = 0;
                        emit(LiveEvent::Connected);
                        while let Some(msg) = ws.next().await {
                            match msg {
                                Ok(Message::Text(t)) => {
                                    if let Some(e) = parse(&t) {
                                        emit(e);
                                    }
                                }
                                Ok(Message::Close(frame)) => {
                                    // 4401: the token expired; reconnect with a fresh one.
                                    if frame.is_some_and(|f| u16::from(f.code) == 4401) {
                                        session.invalidate();
                                    }
                                    break;
                                }
                                Ok(_) => {}
                                Err(_) => break,
                            }
                        }
                        emit(LiveEvent::Disconnected);
                    }
                    Err(e) => log::debug!("websocket connect failed: {e}"),
                }
            }
            Err(AuthError::Expired) => return,
            Err(_) => {}
        }
        tokio::time::sleep(backoff(attempt)).await;
        attempt += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_events() {
        assert_eq!(
            parse(r#"{"type":"send.updated","send":{"id":"s1","items":[]}}"#),
            Some(LiveEvent::SendUpdated { send_id: "s1".into() })
        );
        assert_eq!(
            parse(r#"{"type":"reaction","placement_id":"p","frame_id":"f","reaction":"love","by_frame_name":"Oma"}"#),
            Some(LiveEvent::Reaction {
                placement_id: "p".into(),
                frame_id: "f".into(),
                reaction: Some("love".into())
            })
        );
        assert_eq!(
            parse(r#"{"type":"reaction","placement_id":"p","frame_id":"f","reaction":null}"#),
            Some(LiveEvent::Reaction {
                placement_id: "p".into(),
                frame_id: "f".into(),
                reaction: None
            })
        );
        assert_eq!(
            parse(r#"{"type":"frame.status","frame_id":"f","online":true,"last_seen_at":null}"#),
            Some(LiveEvent::FrameStatus {
                frame_id: "f".into(),
                online: true
            })
        );
        assert_eq!(parse(r#"{"type":"something.new"}"#), None);
        assert_eq!(parse("not json"), None);
    }

    #[test]
    fn backoff_grows_with_jitter() {
        for _ in 0..50 {
            let d = backoff(0).as_secs_f64();
            assert!((0.8..=1.2).contains(&d), "{d}");
            let d = backoff(10).as_secs_f64();
            assert!((48.0..=72.0).contains(&d), "{d}");
        }
    }
}
