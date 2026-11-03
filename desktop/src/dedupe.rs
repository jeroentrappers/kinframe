//! Duplicate detection: a local index of which files (by SHA-256) were already sent to which frame.
//!
//! The server adds a second line of defence: tus creation answers `Kinframe-Existing-Item` when the sender already
//! has an item with that hash, so the bytes are not uploaded again.

use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// `server → sha256 → frame ids`.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct SentIndex {
    #[serde(default)]
    servers: BTreeMap<String, BTreeMap<String, BTreeSet<String>>>,
    #[serde(skip)]
    path: Option<PathBuf>,
}

impl SentIndex {
    pub fn load(path: &Path) -> Self {
        let mut idx: SentIndex = std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        idx.path = Some(path.to_path_buf());
        idx
    }

    pub fn save(&self) -> std::io::Result<()> {
        match &self.path {
            Some(p) => crate::store::write_atomic(p, &serde_json::to_vec(self)?),
            None => Ok(()),
        }
    }

    /// Frames this file was already sent to.
    pub fn sent_to(&self, server: &str, sha256: &str) -> BTreeSet<String> {
        self.servers
            .get(server)
            .and_then(|m| m.get(sha256))
            .cloned()
            .unwrap_or_default()
    }

    /// Of `targets`, the frames that don't have this file yet, in the given order.
    pub fn missing(&self, server: &str, sha256: &str, targets: &[String]) -> Vec<String> {
        let have = self.sent_to(server, sha256);
        targets.iter().filter(|f| !have.contains(*f)).cloned().collect()
    }

    pub fn record(&mut self, server: &str, sha256: &str, frames: &[String]) {
        self.servers
            .entry(server.to_string())
            .or_default()
            .entry(sha256.to_string())
            .or_default()
            .extend(frames.iter().cloned());
    }

    pub fn len(&self, server: &str) -> usize {
        self.servers.get(server).map_or(0, |m| m.len())
    }

    pub fn is_empty(&self) -> bool {
        self.servers.values().all(|m| m.is_empty())
    }
}

/// Streams a file through SHA-256. `progress` receives bytes hashed so far.
pub fn hash_file(path: &Path, mut progress: impl FnMut(u64)) -> std::io::Result<String> {
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut done = 0u64;
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
        done += n as u64;
        progress(done);
    }
    Ok(hex::encode(h.finalize()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hashes_files() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("a.jpg");
        std::fs::write(&p, b"abc").unwrap();
        assert_eq!(
            hash_file(&p, |_| {}).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn index_tracks_frames_per_server_and_persists() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sent.json");
        let mut idx = SentIndex::load(&path);
        assert!(idx.is_empty());
        let targets = vec!["f1".to_string(), "f2".to_string()];
        assert_eq!(idx.missing("https://a", "h1", &targets), targets);
        idx.record("https://a", "h1", &["f1".into()]);
        idx.save().unwrap();

        let idx = SentIndex::load(&path);
        assert_eq!(idx.missing("https://a", "h1", &targets), vec!["f2".to_string()]);
        // Another server never shares the index.
        assert_eq!(idx.missing("https://b", "h1", &targets), targets);
        assert_eq!(idx.len("https://a"), 1);
    }

    #[test]
    fn corrupt_index_starts_empty() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("sent.json");
        std::fs::write(&path, b"{nope").unwrap();
        assert!(SentIndex::load(&path).is_empty());
    }
}
