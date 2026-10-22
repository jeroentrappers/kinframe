//! The step between "file picked" and "bytes uploaded".
//!
//! Today every file is uploaded as is. Client-side preparation (downscaling photos to the master size, transcoding
//! video, the server's `prepared` uploads) slots in here as another [`Preparer`]: the engine hashes the original for
//! duplicate detection, then uploads whatever `prepare` returns. A preparer must be deterministic (same input, same
//! output path and bytes) so a resumed upload continues the same file after a restart.

use std::path::PathBuf;

use crate::queue::JobItem;

#[derive(Debug, Clone, PartialEq)]
pub struct Prepared {
    pub path: PathBuf,
    pub mime: String,
    pub filename: String,
}

pub trait Preparer: Send + Sync {
    fn prepare(&self, item: &JobItem) -> std::io::Result<Prepared>;
}

/// Uploads originals unchanged.
pub struct Originals;

impl Preparer for Originals {
    fn prepare(&self, item: &JobItem) -> std::io::Result<Prepared> {
        if !item.path.is_file() {
            return Err(std::io::Error::new(std::io::ErrorKind::NotFound, "file not found"));
        }
        Ok(Prepared {
            path: item.path.clone(),
            mime: item.mime.clone(),
            filename: item.name.clone(),
        })
    }
}
