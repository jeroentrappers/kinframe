//! Where the app keeps its files, and the small settings file.

use std::io::Write;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::discovery::Discovery;

/// The app's directories. `KINFRAME_DESKTOP_HOME` overrides both (tests, portable use, screenshots).
#[derive(Debug, Clone)]
pub struct Dirs {
    pub config: PathBuf,
    pub data: PathBuf,
}

impl Dirs {
    pub fn system() -> Self {
        if let Some(home) = std::env::var_os("KINFRAME_DESKTOP_HOME") {
            let home = PathBuf::from(home);
            return Self {
                config: home.clone(),
                data: home,
            };
        }
        let pd = directories::ProjectDirs::from("app", "kinframe", "kinframe");
        match pd {
            Some(pd) => Self {
                config: pd.config_dir().to_path_buf(),
                data: pd.data_dir().to_path_buf(),
            },
            None => {
                let tmp = std::env::temp_dir().join("kinframe-desktop");
                Self {
                    config: tmp.clone(),
                    data: tmp,
                }
            }
        }
    }

    pub fn at(dir: &Path) -> Self {
        Self {
            config: dir.to_path_buf(),
            data: dir.to_path_buf(),
        }
    }

    pub fn settings_file(&self) -> PathBuf {
        self.config.join("settings.json")
    }
    pub fn queue_file(&self) -> PathBuf {
        self.data.join("queue.json")
    }
    pub fn sent_index_file(&self) -> PathBuf {
        self.data.join("sent-index.json")
    }
}

/// Writes via a temporary file and rename, so a crash or power loss never leaves a half-written file.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("tmp");
    {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
    }
    std::fs::rename(&tmp, path)
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
pub struct Settings {
    /// Origin of the chosen server, e.g. `https://kinframe.appmire.be`.
    #[serde(default)]
    pub server: Option<String>,
    /// Last discovery document, so the app starts without waiting for the network.
    #[serde(default)]
    pub discovery: Option<Discovery>,
    /// Language code; `None` follows the OS.
    #[serde(default)]
    pub language: Option<String>,
    /// Dev-auth sign-in on development servers (not a secret: dev servers trust anyone).
    #[serde(default)]
    pub dev_email: Option<String>,
    #[serde(default)]
    pub dev_name: Option<String>,
    /// Set after "Sign out", so the next sign-in asks for the account instead of reusing the browser session.
    #[serde(default)]
    pub signed_out: bool,
    /// Frames last chosen for a bulk import.
    #[serde(default)]
    pub last_frames: Vec<String>,
    #[serde(default)]
    pub caption_template: String,
}

impl Settings {
    pub fn load(path: &Path) -> Self {
        std::fs::read(path)
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, path: &Path) {
        if let Err(e) = serde_json::to_vec_pretty(self)
            .map_err(std::io::Error::other)
            .and_then(|b| write_atomic(path, &b))
        {
            log::warn!("could not save settings: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_round_trip() {
        let dir = tempfile::tempdir().unwrap();
        let dirs = Dirs::at(dir.path());
        assert_eq!(Settings::load(&dirs.settings_file()), Settings::default());
        let s = Settings {
            server: Some("https://x.org".into()),
            language: Some("nl".into()),
            ..Default::default()
        };
        s.save(&dirs.settings_file());
        assert_eq!(Settings::load(&dirs.settings_file()), s);
        assert!(!dirs.settings_file().with_extension("tmp").exists());
    }
}
