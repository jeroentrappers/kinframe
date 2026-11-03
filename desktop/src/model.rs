//! API types (docs/api/openapi.yaml). Only the fields the desktop app uses; unknown fields are ignored.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};

/// The name the server gives new accounts whose token carries no name claim (web/src/lib/auth.svelte.ts).
pub const PLACEHOLDER_NAME: &str = "New member";

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct User {
    pub id: String,
    pub display_name: String,
    #[serde(default)]
    pub email: Option<String>,
    #[serde(default)]
    pub locale: Option<String>,
}

#[derive(Debug, Clone, Default, Deserialize, Serialize, PartialEq)]
pub struct Permissions {
    #[serde(default)]
    pub send: bool,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct FrameSummary {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub location: Option<String>,
    #[serde(default)]
    pub owner_name: String,
    pub role: String,
    #[serde(default)]
    pub permissions: Permissions,
    #[serde(default)]
    pub online: bool,
    #[serde(default)]
    pub last_seen_at: Option<DateTime<Utc>>,
}

impl FrameSummary {
    pub fn can_send(&self) -> bool {
        self.role == "owner" || self.role == "admin" || self.permissions.send
    }
}

#[derive(Debug, Clone, Deserialize, PartialEq)]
pub struct RedeemResult {
    pub status: String,
    pub frame: FrameSummary,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Item {
    pub id: String,
    pub kind: String,
    pub status: String,
    #[serde(default)]
    pub failure_code: Option<String>,
    #[serde(default)]
    pub caption: Option<String>,
    #[serde(default)]
    pub thumb_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct SendPlacement {
    pub placement_id: String,
    pub item_id: String,
    pub status: String,
    #[serde(default)]
    pub reaction: Option<String>,
    #[serde(default)]
    pub replies: Vec<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct SendFrame {
    pub frame_id: String,
    pub frame_name: String,
    #[serde(default)]
    pub online: Option<bool>,
    #[serde(default)]
    pub placements: Vec<SendPlacement>,
}

impl SendFrame {
    /// Placements that reached the frame (delivered or already seen).
    pub fn delivered(&self) -> usize {
        self.placements
            .iter()
            .filter(|p| p.status == "delivered" || p.status == "seen")
            .count()
    }
    pub fn seen(&self) -> usize {
        self.placements.iter().filter(|p| p.status == "seen").count()
    }
    pub fn failed(&self) -> usize {
        self.placements.iter().filter(|p| p.status == "failed").count()
    }
    /// Live placements (removed ones don't count).
    pub fn total(&self) -> usize {
        self.placements.iter().filter(|p| p.status != "removed").count()
    }
}

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct Send {
    pub id: String,
    pub created_at: DateTime<Utc>,
    #[serde(default)]
    pub items: Vec<Item>,
    #[serde(default)]
    pub frames: Vec<SendFrame>,
}

impl Send {
    pub fn photos(&self) -> usize {
        self.items.iter().filter(|i| i.kind == "photo").count()
    }
    pub fn videos(&self) -> usize {
        self.items.iter().filter(|i| i.kind == "video").count()
    }
    /// Reactions across frames, counted per kind, in a stable order.
    pub fn reactions(&self) -> Vec<(String, usize)> {
        let mut out: Vec<(String, usize)> = Vec::new();
        for r in ["love", "laugh", "wow", "sad"] {
            let n = self
                .frames
                .iter()
                .flat_map(|f| &f.placements)
                .filter(|p| p.reaction.as_deref() == Some(r))
                .count();
            if n > 0 {
                out.push((r.to_string(), n));
            }
        }
        out
    }
    pub fn set_reaction(&mut self, placement_id: &str, reaction: Option<String>) -> bool {
        for p in self.frames.iter_mut().flat_map(|f| f.placements.iter_mut()) {
            if p.placement_id == placement_id {
                p.reaction = reaction;
                return true;
            }
        }
        false
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct SendsPage {
    pub sends: Vec<Send>,
    #[serde(default)]
    pub next_cursor: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct SendCreate {
    pub frame_ids: Vec<String>,
    pub items: Vec<SendItemCreate>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct SendItemCreate {
    pub upload_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub caption: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub captured_at: Option<DateTime<Utc>>,
}

/// RFC 9457 problem details with kinframe's machine `code`.
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
pub struct Problem {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub status: u16,
    #[serde(default)]
    pub code: String,
    #[serde(default)]
    pub detail: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn send_counts() {
        let s: Send = serde_json::from_value(serde_json::json!({
            "id": "s", "created_at": "2026-10-22T13:05:00Z",
            "items": [{"id": "a", "kind": "photo", "status": "ready"}, {"id": "b", "kind": "video", "status": "ready"}],
            "frames": [{"frame_id": "f", "frame_name": "Oma", "placements": [
                {"placement_id": "p1", "item_id": "a", "status": "seen", "reaction": "love"},
                {"placement_id": "p2", "item_id": "b", "status": "delivered", "reaction": null},
                {"placement_id": "p3", "item_id": "c", "status": "removed"}
            ]}],
            "summary": {"seen": 1}
        }))
        .unwrap();
        assert_eq!((s.photos(), s.videos()), (1, 1));
        let f = &s.frames[0];
        assert_eq!((f.delivered(), f.seen(), f.total()), (2, 1, 2));
        assert_eq!(s.reactions(), vec![("love".to_string(), 1)]);
    }
}
