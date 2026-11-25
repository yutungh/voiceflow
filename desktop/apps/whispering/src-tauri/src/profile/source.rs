//! Where the word lists come from: a folder (a local folder, a synced folder
//! or a network share) or a private GitHub repository (`github.rs`).
//!
//! `sync::refresh` reads and writes through this interface only, so both
//! sources share one set of rules: the last good copy of each layer, edits
//! uploaded once and never changed, and the status file. Paths are relative
//! to the source, with `/` (`profiles/household/profile.json`).

use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};

use super::sync::{read_limited, upload_op, write_atomic};

/// One file read from the source.
#[derive(Debug, PartialEq)]
pub enum Fetch {
    Text(String),
    /// The source has no such file.
    NotFound,
    /// Not read this time (the time budget ran out, or the source asked to
    /// wait); tried again at the next check. Not an error in the file.
    Deferred(String),
    /// The file is there but couldn't be read (too large, an I/O error).
    Failed(String),
}

/// One edit file put on the source.
#[derive(Debug, PartialEq)]
pub enum Upload {
    Done,
    /// Not tried now (time budget, upload limit per check); stays pending.
    Deferred,
    /// The source refused uploads (for example a token that may only read):
    /// a notice, not an error; the edits stay on this device.
    Refused(String),
    Failed(String),
}

pub trait Source {
    /// Reaches the source and reads its listing. `Err` means unreachable,
    /// with the message to show.
    fn open(&mut self) -> Result<(), String>;
    /// Why the source couldn't be reached, as a short code (`offline`,
    /// `auth`, `not-found`, `rate-limited`, `empty`, `truncated`).
    fn issue(&self) -> Option<&'static str> {
        None
    }
    /// A content id for a file, when the source has one (a git blob sha):
    /// lets an unchanged layer be taken from the last good copy unread.
    fn revision(&self, _rel: &str) -> Option<String> {
        None
    }
    fn read(&mut self, rel: &str, max: u64) -> Fetch;
    /// The `.json` files directly in a folder (names only, sorted; hidden
    /// files left out). A missing folder is empty.
    fn list_json(&mut self, rel_dir: &str) -> Vec<String>;
    /// Whether this device sends its edits and status (a setting).
    fn uploads(&self) -> bool {
        true
    }
    /// Puts one edit file (immutable): the same file already there counts
    /// as done, a different one under the same name is an error and is
    /// never overwritten.
    fn create(&mut self, rel_dir: &str, name: &str, local: &Path) -> Upload;
    /// Replaces this device's status file. `Ok` also when it was left as it
    /// was on purpose.
    fn put_status(&mut self, rel: &str, text: &str) -> Result<(), String>;
    /// Problems and calm notices gathered during the check.
    fn take_messages(&mut self) -> (Vec<String>, Vec<String>) {
        (Vec::new(), Vec::new())
    }
    /// Keeps what the source needs next time (a listing, a retry time).
    fn finish(&mut self) {}
}

/// A folder: exactly what VoiceFlow did before sources existed.
pub struct FolderSource {
    root: PathBuf,
}

impl FolderSource {
    pub fn new(root: &Path) -> Self {
        FolderSource { root: root.to_path_buf() }
    }

    fn path(&self, rel: &str) -> PathBuf {
        rel.split('/').fold(self.root.clone(), |p, part| p.join(part))
    }
}

impl Source for FolderSource {
    fn open(&mut self) -> Result<(), String> {
        // The share is there when its `profiles` folder can be listed.
        if self.root.join("profiles").is_dir() {
            Ok(())
        } else {
            Err(format!("Can't reach {}; using the last good copy.", self.root.display()))
        }
    }

    fn issue(&self) -> Option<&'static str> {
        Some("offline")
    }

    fn read(&mut self, rel: &str, max: u64) -> Fetch {
        match read_limited(&self.path(rel), max) {
            Ok(text) => Fetch::Text(text),
            Err(e) if e.kind() == ErrorKind::NotFound => Fetch::NotFound,
            Err(e) => Fetch::Failed(e.to_string()),
        }
    }

    fn list_json(&mut self, rel_dir: &str) -> Vec<String> {
        let Ok(entries) = fs::read_dir(self.path(rel_dir)) else {
            return Vec::new();
        };
        let mut names: Vec<String> = entries
            .filter_map(Result::ok)
            .filter_map(|e| {
                let name = e.file_name().to_string_lossy().to_string();
                let is_file = e.file_type().map(|t| t.is_file()).unwrap_or(false);
                (is_file && name.ends_with(".json") && !name.starts_with('.')).then_some(name)
            })
            .collect();
        names.sort();
        names
    }

    fn create(&mut self, rel_dir: &str, name: &str, local: &Path) -> Upload {
        match upload_op(local, &self.path(rel_dir), name) {
            Ok(()) => Upload::Done,
            Err(e) => Upload::Failed(e),
        }
    }

    fn put_status(&mut self, rel: &str, text: &str) -> Result<(), String> {
        write_atomic(&self.path(rel), text.as_bytes())
    }
}
