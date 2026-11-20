//! VoiceFlow word lists: reading the shared folder, the last-good cache,
//! uploading this device's edits and writing the device status file.
//!
//! Everything here is blocking file I/O on a folder that may be a slow or
//! missing network share; the service runs it on a worker thread, never on
//! the dictation path.
//!
//! Shared folder layout (any folder works; for the owner it is an SMB share):
//!
//! ```text
//! <source>/profiles/household/profile.json             layer "household"
//! <source>/profiles/<user>/generated/profile.json      layer "generated" (optional)
//! <source>/profiles/<user>/profile.json                layer "personal"
//! <source>/profiles/<user>/ops/<device>-<uuid>.json    app edits, immutable
//! <source>/status/<user>/<device>.json                 written by each device
//! ```
//!
//! On this device (`<app data>`):
//!
//! ```text
//! profile-ops/<device>-<uuid>.json      edits made here (written by the app)
//! profile/config.json                   source, user, assistant name
//! profile/uploaded.json                 edits confirmed on the share, per folder + user
//! profile/owners.json                   which folder + user each edit made here belongs to
//! profile/cache/<user>-<folder hash>/<layer>.json   last good copy of each layer
//! profile/cache/<user>-<folder hash>/ops/<id>.json  every edit seen on the share
//! ```
//!
//! An edit made here belongs to the folder and user set when it was first
//! merged; it is only ever merged into, and uploaded to, that profile. Caches
//! and upload receipts are kept per folder and user, so switching either
//! never mixes two profiles.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::io::{ErrorKind, Read};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::merge::{merge, Issue, Snapshot};
use super::model::{
    parse_op, parse_profile, valid_name, Layer, Op, ProfileFile, OP_TYPES_READ, PROFILE_VERSIONS_READ, UPLOADABLE_OP_TYPES,
};

/// A layer file larger than this is not read (the format allows 2,000 entries).
pub const MAX_PROFILE_BYTES: u64 = 1024 * 1024;
/// An edit file larger than this is not read.
pub const MAX_OP_BYTES: u64 = 64 * 1024;

/// Device settings for word lists (not part of any profile).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Config {
    /// Profile mode on: profile-owned settings come only from the word lists.
    pub enabled: bool,
    /// A local folder or a UNC path (`\\NAS\voiceflow`).
    pub source: String,
    /// The user whose personal words this device uses (`sam`, `alex`, …).
    pub user: String,
    /// Who manages the "from notes" layer, shown in the app ("Ada").
    pub assistant: String,
    /// This device's id (the same one its edit files carry).
    pub device: String,
}

impl Config {
    pub fn check(&self) -> Result<(), String> {
        if !self.enabled {
            return Ok(());
        }
        if self.source.trim().is_empty() {
            return Err("Choose the folder that holds the word lists.".into());
        }
        if !valid_name(&self.user) || self.user == "household" {
            return Err("The user name must be lower-case letters, digits, '-' or '_' (not “household”).".into());
        }
        if !valid_name(&self.device) {
            return Err("This device has no valid id yet.".into());
        }
        if self.assistant.chars().count() > 40 {
            return Err("The assistant's name is too long.".into());
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LayerInfo {
    pub layer: Option<Layer>,
    /// `ok` (read now), `cached` (last good copy: the share's file was bad or
    /// unreadable), `offline` (share not reachable; last good copy),
    /// `missing` (no file and no copy).
    pub state: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub updated_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub words: usize,
    pub corrections: usize,
}

#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncReport {
    pub source_reachable: bool,
    pub attempted_at: String,
    /// Set when the share was reached and read.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub synced_at: Option<String>,
    pub using_cache: bool,
    pub layers: Vec<LayerInfo>,
    /// Edits made here that the share has not confirmed yet.
    pub pending_edits: usize,
    pub uploaded_now: usize,
    pub errors: Vec<String>,
}

pub struct Paths {
    pub source: PathBuf,
    pub app_data: PathBuf,
    pub user: String,
    pub device: String,
}

impl Paths {
    pub fn new(app_data: &Path, config: &Config) -> Self {
        Paths {
            source: PathBuf::from(config.source.trim()),
            app_data: app_data.to_path_buf(),
            user: config.user.clone(),
            device: config.device.clone(),
        }
    }
    pub fn layer_file(&self, layer: Layer) -> PathBuf {
        let profiles = self.source.join("profiles");
        match layer {
            Layer::Household => profiles.join("household").join("profile.json"),
            Layer::Generated => profiles.join(&self.user).join("generated").join("profile.json"),
            _ => profiles.join(&self.user).join("profile.json"),
        }
    }
    pub fn remote_ops(&self) -> PathBuf {
        self.source.join("profiles").join(&self.user).join("ops")
    }
    pub fn status_file(&self) -> PathBuf {
        self.source
            .join("status")
            .join(&self.user)
            .join(format!("{}.json", self.device))
    }
    pub fn local_ops(&self) -> PathBuf {
        self.app_data.join("profile-ops")
    }
    fn profile_dir(&self) -> PathBuf {
        self.app_data.join("profile")
    }
    /// Which profile this is: the user and the folder (case and slashes
    /// aside on Windows). Caches, receipts and edit owners are kept per key.
    pub fn scope_key(&self) -> String {
        let mut source = self.source.to_string_lossy().trim().to_string();
        if cfg!(windows) {
            source = source.replace('/', "\\").to_lowercase();
        }
        let source = source.trim_end_matches(['/', '\\']);
        // FNV-1a, 64 bit: stable across builds (unlike the std hasher).
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        for byte in source.bytes() {
            hash ^= u64::from(byte);
            hash = hash.wrapping_mul(0x0100_0000_01b3);
        }
        format!("{}-{hash:016x}", self.user)
    }
    /// The cache is per folder and user, so switching never mixes words.
    fn cache_dir(&self) -> PathBuf {
        self.profile_dir().join("cache").join(self.scope_key())
    }
    fn cache_layer(&self, layer: Layer) -> PathBuf {
        self.cache_dir().join(format!("{}.json", layer.as_str()))
    }
    fn cache_ops(&self) -> PathBuf {
        self.cache_dir().join("ops")
    }
    fn uploaded_file(&self) -> PathBuf {
        self.profile_dir().join("uploaded.json")
    }
    fn owners_file(&self) -> PathBuf {
        self.profile_dir().join("owners.json")
    }
}

/// Reads a file of at most `max` bytes; a larger one is an error, not read whole.
pub fn read_limited(path: &Path, max: u64) -> std::io::Result<String> {
    let file = fs::File::open(path)?;
    let mut text = String::new();
    file.take(max + 1).read_to_string(&mut text)?;
    if text.len() as u64 > max {
        return Err(std::io::Error::new(
            ErrorKind::InvalidData,
            format!("larger than {} KB", max / 1024),
        ));
    }
    Ok(text)
}

pub fn config_path(app_data: &Path) -> PathBuf {
    app_data.join("profile").join("config.json")
}

pub fn load_config(app_data: &Path) -> Config {
    fs::read_to_string(config_path(app_data))
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

pub fn save_config(app_data: &Path, config: &Config) -> Result<(), String> {
    let text = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    write_atomic(&config_path(app_data), text.as_bytes())
}

/// Writes next to the target, then renames over it: readers see the old
/// file or the new one, never half of one.
pub fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let dir = path.parent().ok_or("no parent folder")?;
    fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("file");
    // Unique per write: two writers (even in one process) never share one.
    static SEQ: AtomicU64 = AtomicU64::new(0);
    let seq = SEQ.fetch_add(1, Ordering::Relaxed);
    let tmp = dir.join(format!(".{name}.{}.{seq}.tmp", std::process::id()));
    fs::write(&tmp, bytes).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("{}: {e}", tmp.display())
    })?;
    fs::rename(&tmp, path).map_err(|e| {
        let _ = fs::remove_file(&tmp);
        format!("{}: {e}", path.display())
    })
}

/// The share is there when its `profiles` folder can be listed.
pub fn reachable(paths: &Paths) -> bool {
    paths.source.join("profiles").is_dir()
}

fn layer_info(layer: Layer, state: &str, file: Option<&ProfileFile>, error: Option<String>) -> LayerInfo {
    LayerInfo {
        layer: Some(layer),
        state: state.to_string(),
        updated_at: file.and_then(|f| f.updated_at.clone()),
        error,
        words: file.map_or(0, |f| f.terms.len()),
        corrections: file.map_or(0, |f| f.corrections.len()),
    }
}

/// One layer: the share's file when it is valid (and then cached), else
/// the last good copy, else nothing.
fn load_layer(paths: &Paths, layer: Layer, online: bool) -> (Option<ProfileFile>, LayerInfo) {
    let cached = || {
        read_limited(&paths.cache_layer(layer), MAX_PROFILE_BYTES)
            .ok()
            .and_then(|t| parse_profile(&t, layer, &paths.user).ok())
    };
    if !online {
        let file = cached();
        let state = if file.is_some() { "offline" } else { "missing" };
        let info = layer_info(layer, state, file.as_ref(), None);
        return (file, info);
    }
    let remote = paths.layer_file(layer);
    let problem = match read_limited(&remote, MAX_PROFILE_BYTES) {
        Ok(text) => match parse_profile(&text, layer, &paths.user) {
            Ok(file) => {
                let cache_error = write_atomic(&paths.cache_layer(layer), text.as_bytes()).err();
                let info = layer_info(
                    layer,
                    "ok",
                    Some(&file),
                    cache_error.map(|e| format!("couldn't keep a copy on this device: {e}")),
                );
                return (Some(file), info);
            }
            Err(e) => format!("{} was not used: {e}", display_name(paths, &remote)),
        },
        Err(e) if e.kind() == ErrorKind::NotFound => {
            match cached() {
                // A layer that was there before and now is not: keep the
                // copy rather than dropping everyone's words on a glitch.
                Some(file) => {
                    let info = layer_info(
                        layer,
                        "cached",
                        Some(&file),
                        Some(format!(
                            "{} is missing; using the last good copy",
                            display_name(paths, &remote)
                        )),
                    );
                    return (Some(file), info);
                }
                None => return (None, layer_info(layer, "missing", None, None)),
            }
        }
        Err(e) => format!("{} couldn't be read: {e}", display_name(paths, &remote)),
    };
    let file = cached();
    let state = if file.is_some() { "cached" } else { "missing" };
    let info = layer_info(layer, state, file.as_ref(), Some(problem));
    (file, info)
}

/// The path relative to the source, for messages.
fn display_name(paths: &Paths, path: &Path) -> String {
    path.strip_prefix(&paths.source)
        .unwrap_or(path)
        .to_string_lossy()
        .replace('\\', "/")
}

fn json_files(dir: &Path) -> Vec<(String, PathBuf)> {
    let Ok(entries) = fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut files: Vec<(String, PathBuf)> = entries
        .filter_map(Result::ok)
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            let is_file = e.file_type().map(|t| t.is_file()).unwrap_or(false);
            (is_file && name.ends_with(".json") && !name.starts_with('.')).then(|| (name, e.path()))
        })
        .collect();
    files.sort();
    files
}

fn stem(name: &str) -> &str {
    name.strip_suffix(".json").unwrap_or(name)
}

fn load_uploaded(paths: &Paths) -> BTreeSet<String> {
    fs::read_to_string(paths.uploaded_file())
        .ok()
        .and_then(|t| serde_json::from_str::<BTreeMap<String, Vec<String>>>(&t).ok())
        .and_then(|mut m| m.remove(&paths.scope_key()))
        .map(|v| v.into_iter().collect())
        .unwrap_or_default()
}

fn save_uploaded(paths: &Paths, uploaded: &BTreeSet<String>) -> Result<(), String> {
    let mut all: BTreeMap<String, Vec<String>> = fs::read_to_string(paths.uploaded_file())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default();
    all.insert(paths.scope_key(), uploaded.iter().cloned().collect());
    let text = serde_json::to_string_pretty(&all).map_err(|e| e.to_string())?;
    write_atomic(&paths.uploaded_file(), text.as_bytes())
}

/// Edit id -> the profile (`scope_key`) it belongs to.
fn load_owners(paths: &Paths) -> BTreeMap<String, String> {
    fs::read_to_string(paths.owners_file())
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok())
        .unwrap_or_default()
}

fn save_owners(paths: &Paths, owners: &BTreeMap<String, String>) -> Result<(), String> {
    let text = serde_json::to_string_pretty(owners).map_err(|e| e.to_string())?;
    write_atomic(&paths.owners_file(), text.as_bytes())
}

/// One edit file, read within its size limit and checked.
fn read_op(path: &Path, name: &str) -> Result<Op, String> {
    read_limited(path, MAX_OP_BYTES)
        .map_err(|e| e.to_string())
        .and_then(|t| parse_op(&t, Some(stem(name))))
}

/// Puts one edit on the share. Idempotent: the same file already there
/// counts as done; a different file under the same name is an error and
/// nothing is overwritten.
pub fn upload_op(local: &Path, remote_dir: &Path, name: &str) -> Result<(), String> {
    let text = read_limited(local, MAX_OP_BYTES).map_err(|e| format!("{name}: {e}"))?;
    let mine: Value = serde_json::from_str(&text).map_err(|e| format!("{name}: {e}"))?;
    let dest = remote_dir.join(name);
    let same = |path: &Path| -> Result<bool, String> {
        let there = read_limited(path, MAX_OP_BYTES).map_err(|e| format!("{name}: {e}"))?;
        Ok(serde_json::from_str::<Value>(&there).ok().as_ref() == Some(&mine))
    };
    if dest.exists() {
        return if same(&dest)? {
            Ok(())
        } else {
            Err(format!("{name}: a different file with this name is already on the share"))
        };
    }
    fs::create_dir_all(remote_dir).map_err(|e| format!("{}: {e}", remote_dir.display()))?;
    write_atomic(&dest, text.as_bytes())?;
    if same(&dest)? {
        Ok(())
    } else {
        Err(format!("{name}: the copy on the share doesn't match after writing"))
    }
}

pub struct Outcome {
    pub snapshot: Snapshot,
    pub report: SyncReport,
}

/// Reads everything, uploads pending edits when `online`, merges.
/// With `online` false, nothing on the share is touched (fast start-up from
/// the cache, and the fallback when the share can't be reached).
pub fn refresh(paths: &Paths, online: bool, now: &str) -> Outcome {
    let mut errors = Vec::new();
    let reached = online && reachable(paths);
    if online && !reached {
        errors.push(format!(
            "Can't reach {}; using the last good copy.",
            paths.source.display()
        ));
    }

    let mut layers = Vec::new();
    let mut infos = Vec::new();
    for layer in [Layer::Household, Layer::Generated, Layer::Personal] {
        let (file, info) = load_layer(paths, layer, reached);
        if let Some(e) = &info.error {
            errors.push(e.clone());
        }
        layers.push((layer, file));
        infos.push(info);
    }

    // Edits made here: only this profile's. An edit not seen before is
    // claimed by the profile set now (it was just made, or made before word
    // lists were turned on); one claimed by another folder or user is left
    // alone, so it never reaches someone else's words.
    let mut issues: Vec<Issue> = Vec::new();
    let mut ops: BTreeMap<String, Op> = BTreeMap::new();
    let mut local_files = Vec::new();
    let key = paths.scope_key();
    // One refresh at a time reads and rewrites the owners (the quick local
    // merge after an edit can run beside a refresh from the share).
    static OWNERS: std::sync::Mutex<()> = std::sync::Mutex::new(());
    let owners_guard = OWNERS.lock().unwrap_or_else(|e| e.into_inner());
    let mut owners = load_owners(paths);
    let mut claimed = false;
    for (name, path) in json_files(&paths.local_ops()) {
        match read_op(&path, &name) {
            Ok(op) => {
                match owners.get(&op.id) {
                    Some(owner) if *owner != key => continue,
                    Some(_) => {}
                    None => {
                        owners.insert(op.id.clone(), key.clone());
                        claimed = true;
                    }
                }
                // 0.9.13: readers before writers. An edit of a type the
                // phones can't read yet (app-style, style-set, default-set:
                // Android 0.8.2 refuses what it doesn't know) is used on this
                // PC but stays here, unshared, until a version that knows
                // every device reads it uploads it.
                if !UPLOADABLE_OP_TYPES.contains(&op.body.type_name()) {
                    issues.push(Issue {
                        code: "edit-held".into(),
                        message: format!(
                            "A {} edit made on this device ({name}) works here; it is shared once every device can read it.",
                            op.body.type_name()
                        ),
                        layer: Some(Layer::Edit),
                        ids: vec![stem(&name).to_string()],
                    });
                } else {
                    local_files.push((name, path));
                }
                ops.insert(op.id.clone(), op);
            }
            Err(e) => issues.push(rejected_edit(&name, &e)),
        }
    }
    // A claim that couldn't be noted is used here but not uploaded yet: it
    // is only sent once it is on record whose it is.
    let mut owners_unsaved = false;
    if claimed {
        if let Err(e) = save_owners(paths, &owners) {
            errors.push(format!("Couldn't note which profile this device's edits belong to: {e}"));
            owners_unsaved = true;
        }
    }
    let saved_owners = if owners_unsaved { load_owners(paths) } else { owners };
    drop(owners_guard);

    // Upload what the share hasn't confirmed, then read every device's edits.
    let mut uploaded = load_uploaded(paths);
    let mut uploaded_now = 0;
    if reached {
        let remote = paths.remote_ops();
        for (name, path) in &local_files {
            let id = stem(name).to_string();
            if uploaded.contains(&id) || saved_owners.get(&id) != Some(&key) {
                continue;
            }
            match upload_op(path, &remote, name) {
                Ok(()) => {
                    uploaded.insert(id);
                    uploaded_now += 1;
                }
                Err(e) => errors.push(format!("Couldn't upload an edit: {e}")),
            }
        }
        if uploaded_now > 0 {
            if let Err(e) = save_uploaded(paths, &uploaded) {
                errors.push(format!("Couldn't note the uploaded edits: {e}"));
            }
        }
        // Every device's edits: a good copy here is enough (edits never
        // change); otherwise the share's file is read, checked, used now and
        // kept, even when keeping the copy fails.
        let cache_ops = paths.cache_ops();
        for (name, path) in json_files(&remote) {
            let cached = cache_ops.join(&name);
            if let Ok(op) = read_op(&cached, &name) {
                ops.entry(op.id.clone()).or_insert(op);
                continue;
            }
            match read_limited(&path, MAX_OP_BYTES) {
                Ok(text) => match parse_op(&text, Some(stem(&name))) {
                    Ok(op) => {
                        if let Err(e) = write_atomic(&cached, text.as_bytes()) {
                            errors.push(format!("Couldn't keep a copy of {name}: {e}"));
                        }
                        ops.entry(op.id.clone()).or_insert(op);
                    }
                    Err(e) => issues.push(rejected_edit(&name, &e)),
                },
                Err(e) => errors.push(format!("Couldn't read {name}: {e}")),
            }
        }
    }
    for (name, path) in json_files(&paths.cache_ops()) {
        if let Ok(op) = read_op(&path, &name) {
            ops.entry(op.id.clone()).or_insert(op);
        }
    }
    let pending_edits = local_files
        .iter()
        .filter(|(name, _)| !uploaded.contains(stem(name)))
        .count();

    let refs: Vec<(Layer, Option<&ProfileFile>)> = layers.iter().map(|(l, f)| (*l, f.as_ref())).collect();
    let all_ops: Vec<Op> = ops.into_values().collect();
    let mut snapshot = merge(&paths.user, &refs, &all_ops);
    snapshot.issues.extend(issues);

    let using_cache = !reached || infos.iter().any(|i| i.state == "cached");
    let report = SyncReport {
        source_reachable: reached,
        attempted_at: now.to_string(),
        synced_at: reached.then(|| now.to_string()),
        using_cache,
        layers: infos,
        pending_edits,
        uploaded_now,
        errors,
    };
    Outcome { snapshot, report }
}

fn rejected_edit(name: &str, error: &str) -> Issue {
    Issue {
        code: "edit-rejected".into(),
        message: format!("An edit file was skipped ({name}): {error}"),
        layer: Some(Layer::Edit),
        ids: vec![stem(name).to_string()],
    }
}

/// The device status file: what the user (or their assistant) reads to see how
/// this device is doing. No dictated text, ever.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusFile<'a> {
    pub schema: &'static str,
    pub schema_version: u32,
    pub app: &'static str,
    pub version: &'a str,
    pub os: &'static str,
    pub device: &'a str,
    pub user: &'a str,
    pub written_at: &'a str,
    /// 0.9.13: what this version reads (readers before writers).
    pub reads: StatusReads,
    pub profile: StatusProfile<'a>,
}

#[derive(Serialize)]
pub struct StatusReads {
    pub profile: &'static [u64],
    pub op: &'static [&'static str],
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusProfile<'a> {
    pub snapshot_id: &'a str,
    pub words: usize,
    pub corrections: usize,
    pub styles: usize,
    pub last_sync_at: Option<&'a str>,
    pub source_reachable: bool,
    pub using_cache: bool,
    pub pending_edits: usize,
    pub layers: &'a [LayerInfo],
    pub issues: Vec<&'a str>,
    pub errors: &'a [String],
}

/// A message for the status file: cut at the first quote (curly or plain),
/// where the words from a list or a parser's view of them begin. The status
/// file carries counts and diagnostics, never the words; the app shows the
/// full message on this device.
pub fn redact_quoted(message: &str) -> String {
    match message.find(['\u{201c}', '\u{201d}', '"', '\'', '`']) {
        Some(i) => format!("{}\u{2026}", message[..i].trim_end()),
        None => message.to_string(),
    }
}

pub fn write_status(paths: &Paths, version: &str, snapshot: &Snapshot, report: &SyncReport, last_sync: Option<&str>) -> Result<(), String> {
    let layers: Vec<LayerInfo> = report
        .layers
        .iter()
        .map(|l| LayerInfo {
            error: l.error.as_deref().map(redact_quoted),
            ..l.clone()
        })
        .collect();
    let errors: Vec<String> = report.errors.iter().map(|e| redact_quoted(e)).collect();
    let status = StatusFile {
        schema: "voiceflow-status",
        schema_version: 1,
        app: "VoiceFlow",
        version,
        os: std::env::consts::OS,
        device: &paths.device,
        user: &paths.user,
        written_at: &report.attempted_at,
        reads: StatusReads {
            profile: PROFILE_VERSIONS_READ,
            op: OP_TYPES_READ,
        },
        profile: StatusProfile {
            snapshot_id: &snapshot.id,
            words: snapshot.terms.len(),
            corrections: snapshot.corrections.len(),
            styles: snapshot.styles.len(),
            last_sync_at: last_sync,
            source_reachable: report.source_reachable,
            using_cache: report.using_cache,
            pending_edits: report.pending_edits,
            layers: &layers,
            issues: snapshot.issues.iter().map(|i| i.code.as_str()).collect(),
            errors: &errors,
        },
    };
    let text = serde_json::to_string_pretty(&status).map_err(|e| e.to_string())?;
    write_atomic(&paths.status_file(), text.as_bytes())
}

/// UTC now as `YYYY-MM-DDTHH:MM:SSZ`.
pub fn now_iso() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);
    iso_from_unix(secs)
}

pub fn iso_from_unix(secs: i64) -> String {
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    // Civil from days (Howard Hinnant).
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Fixture {
        _root: tempfile::TempDir,
        paths: Paths,
    }

    fn fixture() -> Fixture {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("share");
        let app_data = root.path().join("appdata");
        fs::create_dir_all(source.join("profiles/household")).unwrap();
        fs::create_dir_all(source.join("profiles/sam/ops")).unwrap();
        fs::create_dir_all(&app_data).unwrap();
        let config = Config {
            enabled: true,
            source: source.to_string_lossy().to_string(),
            user: "sam".into(),
            assistant: "Ada".into(),
            device: "dev1".into(),
        };
        let paths = Paths::new(&app_data, &config);
        Fixture { _root: root, paths }
    }

    fn write(path: &Path, text: &str) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, text).unwrap();
    }

    const HOUSEHOLD: &str = r#"{"schema":"voiceflow-profile","schemaVersion":1,"layer":"household","user":"household",
        "terms":[{"id":"ada","text":"Ada","kind":"bot","heardAs":["Aida"]}]}"#;
    const PERSONAL: &str = r#"{"schema":"voiceflow-profile","schemaVersion":1,"layer":"personal","user":"sam",
        "terms":[{"id":"zorblax","text":"Zorblax","priority":80}],
        "corrections":[{"id":"zork","from":["Zork"],"to":"Zorblax"}]}"#;

    fn op_text(id: &str, body: &str) -> String {
        format!(
            r#"{{"schema":"voiceflow-op","version":1,"id":"dev1-{id}","device":"dev1","createdAt":"2026-11-17T10:00:00Z",{body}}}"#
        )
    }

    #[test]
    fn reads_layers_and_caches_them() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Household), HOUSEHOLD);
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        let out = refresh(&f.paths, true, "2026-11-17T10:00:00Z");
        assert!(out.report.source_reachable);
        assert_eq!(out.snapshot.terms.len(), 2);
        assert_eq!(out.snapshot.corrections.len(), 1);
        assert_eq!(out.report.layers[1].state, "missing"); // no generated layer
        assert!(f.paths.cache_layer(Layer::Personal).exists());
    }

    #[test]
    fn bad_file_keeps_the_last_good_copy() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        let good = refresh(&f.paths, true, "t1").snapshot;
        // Half-written by an assistant.
        write(&f.paths.layer_file(Layer::Personal), &PERSONAL[..40]);
        let out = refresh(&f.paths, true, "t2");
        assert_eq!(out.snapshot.id, good.id);
        assert_eq!(out.report.layers[2].state, "cached");
        assert!(out.report.layers[2].error.as_deref().unwrap().contains("was not used"));
        assert!(out.report.using_cache);
        // Another user's file in this user's place.
        write(&f.paths.layer_file(Layer::Personal), &PERSONAL.replace("\"user\":\"sam\"", "\"user\":\"alex\""));
        assert_eq!(refresh(&f.paths, true, "t3").snapshot.id, good.id);
    }

    #[test]
    fn unreachable_share_falls_back_to_cache() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        let good = refresh(&f.paths, true, "t1").snapshot;
        // The same folder, now gone (unplugged NAS).
        let away = f.paths.source.with_file_name("share-away");
        fs::rename(&f.paths.source, &away).unwrap();
        let out = refresh(&f.paths, true, "t2");
        assert!(!out.report.source_reachable);
        assert!(out.report.using_cache);
        assert_eq!(out.snapshot.id, good.id);
        assert_eq!(out.report.layers[2].state, "offline");
        assert!(out.report.synced_at.is_none());
    }

    #[test]
    fn missing_file_after_a_good_read_keeps_the_copy() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Household), HOUSEHOLD);
        refresh(&f.paths, true, "t1");
        fs::rename(
            f.paths.layer_file(Layer::Household),
            f.paths.source.join("profiles/household/moved.txt"),
        )
        .unwrap();
        let out = refresh(&f.paths, true, "t2");
        assert_eq!(out.report.layers[0].state, "cached");
        assert_eq!(out.snapshot.terms.len(), 1);
    }

    #[test]
    fn uploads_edits_once_and_reads_other_devices() {
        let f = fixture();
        let local = f.paths.local_ops();
        write(
            &local.join("dev1-aaa.json"),
            &op_text("aaa", r#""type":"term","term":{"text":"Quibbit"}"#),
        );
        let other = r#"{"schema":"voiceflow-op","version":1,"id":"dev2-bbb","device":"dev2","createdAt":"2026-11-17T11:00:00Z","type":"correction","from":["Kwibbit"],"to":"Quibbit","scope":{"apps":["*"]}}"#;
        write(&f.paths.remote_ops().join("dev2-bbb.json"), other);
        let out = refresh(&f.paths, true, "t1");
        assert_eq!(out.report.uploaded_now, 1);
        assert_eq!(out.report.pending_edits, 0);
        assert!(f.paths.remote_ops().join("dev1-aaa.json").exists());
        assert_eq!(out.snapshot.terms.len(), 1);
        assert_eq!(out.snapshot.corrections.len(), 1);
        // Second run: nothing new to upload, same result.
        let again = refresh(&f.paths, true, "t2");
        assert_eq!(again.report.uploaded_now, 0);
        assert_eq!(again.snapshot.id, out.snapshot.id);
        // Offline, the other device's edit is still known from the cache.
        let offline = refresh(&f.paths, false, "t3");
        assert_eq!(offline.snapshot.corrections.len(), 1);
    }

    #[test]
    fn upload_is_idempotent_and_never_overwrites() {
        let root = tempfile::tempdir().unwrap();
        let local = root.path().join("dev1-x.json");
        let remote = root.path().join("remote");
        write(&local, &op_text("x", r#""type":"tombstone","target":"dev1-y""#));
        upload_op(&local, &remote, "dev1-x.json").unwrap();
        // Same content again (pretty-printed differently): still fine.
        let value: Value = serde_json::from_str(&fs::read_to_string(&local).unwrap()).unwrap();
        fs::write(remote.join("dev1-x.json"), serde_json::to_string_pretty(&value).unwrap()).unwrap();
        upload_op(&local, &remote, "dev1-x.json").unwrap();
        // Different content under the same name: refused, left alone.
        fs::write(remote.join("dev1-x.json"), "{}").unwrap();
        assert!(upload_op(&local, &remote, "dev1-x.json").unwrap_err().contains("different"));
        assert_eq!(fs::read_to_string(remote.join("dev1-x.json")).unwrap(), "{}");
    }

    #[test]
    fn pending_edit_stays_local_while_offline_and_applies() {
        let f = fixture();
        write(
            &f.paths.local_ops().join("dev1-aaa.json"),
            &op_text("aaa", r#""type":"correction","from":["Zork"],"to":"Zorblax","scope":{"apps":["*"]}"#),
        );
        let out = refresh(&f.paths, false, "t1");
        assert_eq!(out.report.pending_edits, 1);
        assert_eq!(out.snapshot.corrections.len(), 1);
        assert!(!f.paths.remote_ops().join("dev1-aaa.json").exists());
    }

    #[test]
    fn malformed_edit_files_are_skipped_and_reported() {
        let f = fixture();
        write(&f.paths.local_ops().join("dev1-bad.json"), "{not json");
        write(&f.paths.remote_ops().join("dev9-zzz.json"), r#"{"schema":"voiceflow-op"}"#);
        let out = refresh(&f.paths, true, "t1");
        let rejected = out.snapshot.issues.iter().filter(|i| i.code == "edit-rejected").count();
        assert_eq!(rejected, 2);
        assert!(!f.paths.remote_ops().join("dev1-bad.json").exists());
    }

    #[test]
    fn status_file_has_no_text_and_lands_in_place() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        let out = refresh(&f.paths, true, "2026-11-17T10:00:00Z");
        write_status(&f.paths, "0.9.0", &out.snapshot, &out.report, out.report.synced_at.as_deref()).unwrap();
        let text = fs::read_to_string(f.paths.status_file()).unwrap();
        let v: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(v["schema"], "voiceflow-status");
        assert_eq!(v["profile"]["words"], 1);
        assert!(!text.contains("Zorblax"), "status must not carry words or text");
        // 0.9.13: what it reads, for the readers-before-writers rule.
        assert_eq!(v["reads"]["profile"], serde_json::json!([1, 2]));
        assert!(v["reads"]["op"].as_array().unwrap().contains(&Value::from("style-set")));
        assert!(super::super::model::readers_ready(&[v], 2, &["style-set", "default-set"]));
    }

    #[test]
    fn style_edits_made_here_are_held_back() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        write(
            &f.paths.local_ops().join("dev1-style.json"),
            r#"{"schema":"voiceflow-op","version":1,"id":"dev1-style","device":"dev1","createdAt":"2026-11-19T10:00:00Z","type":"style-set","style":"casual","set":{"expression":3}}"#,
        );
        // A feedback fix (app-style) made here: used here, not shared until
        // the phones read it (Android 0.8.2 refuses what it doesn't know).
        write(
            &f.paths.local_ops().join("dev1-app.json"),
            r#"{"schema":"voiceflow-op","version":1,"id":"dev1-app","device":"dev1","createdAt":"2026-11-19T10:00:00Z","type":"app-style","app":"kite.exe","expression":1}"#,
        );
        let out = refresh(&f.paths, true, "t1");
        assert!(!f.paths.remote_ops().join("dev1-style.json").exists(), "not uploaded");
        assert!(!f.paths.remote_ops().join("dev1-app.json").exists(), "not uploaded");
        assert_eq!(out.snapshot.issues.iter().filter(|i| i.code == "edit-held").count(), 2);
        assert_eq!(out.report.pending_edits, 0);
        let kite = out.snapshot.apps.iter().find(|a| a["id"] == "kite.exe").expect("applied on this PC");
        assert_eq!(kite["expression"], 1);
        // The same kind of edit from the share (another device) is read.
        write(
            &f.paths.remote_ops().join("dev2-style.json"),
            r#"{"schema":"voiceflow-op","version":1,"id":"dev2-style","device":"dev2","createdAt":"2026-11-19T10:00:00Z","type":"default-set","set":{"pinnedStyles":[]}}"#,
        );
        let out = refresh(&f.paths, true, "t2");
        assert_eq!(out.snapshot.defaults.values.pinned_styles.as_deref(), Some(&[][..]));
    }

    /// The demo folder in `pack/fixtures/demo` reads, validates and merges
    /// as documented (and is only read: nothing is written there).
    #[test]
    fn pack_demo_fixture() {
        let source = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../pack/fixtures/demo");
        assert!(source.join("profiles").is_dir(), "{}", source.display());
        let app_data = tempfile::tempdir().unwrap();
        let config = Config {
            enabled: true,
            source: source.to_string_lossy().to_string(),
            user: "sam".into(),
            assistant: "Ada".into(),
            device: "demo9".into(),
        };
        let paths = Paths::new(app_data.path(), &config);
        let out = refresh(&paths, true, "2026-11-17T09:10:00Z");
        assert!(out.report.errors.is_empty(), "{:?}", out.report.errors);
        assert!(out.report.layers.iter().all(|l| l.state == "ok"), "{:?}", out.report.layers);
        let s = &out.snapshot;
        assert!(s.issues.is_empty(), "{:?}", s.issues);
        assert_eq!(s.terms.len(), 8);
        assert_eq!(s.corrections.len(), 4);
        assert_eq!(s.styles.len(), 1);
        let pip = s.terms.iter().find(|t| t.id == "pip").unwrap();
        assert_eq!(pip.layer, Layer::Personal);
        let zorblax = s.terms.iter().find(|t| t.id == "zorblax").unwrap();
        assert_eq!(zorblax.text, "Zorblax");
        assert!(s.terms.iter().any(|t| t.text == "Flumox" && t.layer == Layer::Edit));
        // The undone typo from the other device is not there.
        assert!(!s.corrections.iter().any(|c| c.to == "Quibit"));
        assert_eq!(s.defaults.values.language.as_deref(), Some("en"));
        assert_eq!(s.privacy.block_apps, vec!["vault-demo.exe".to_string()]);
    }

    /// A real word-list folder (for example a NAS share), read only: no edit
    /// is uploaded and no status is written. `VOICEFLOW_PROFILE_DUMP=<file>`
    /// also saves the merged snapshot there. Run with
    /// `VOICEFLOW_PROFILE_SOURCE=<folder> VOICEFLOW_PROFILE_USER=<user>
    /// cargo test --lib real_source -- --ignored --nocapture`.
    #[test]
    #[ignore = "needs VOICEFLOW_PROFILE_SOURCE and VOICEFLOW_PROFILE_USER"]
    fn real_source_read_only() {
        let source = std::env::var("VOICEFLOW_PROFILE_SOURCE").expect("VOICEFLOW_PROFILE_SOURCE");
        let user = std::env::var("VOICEFLOW_PROFILE_USER").expect("VOICEFLOW_PROFILE_USER");
        let app_data = tempfile::tempdir().unwrap();
        let config = Config {
            enabled: true,
            source,
            user,
            assistant: String::new(),
            device: "readonly-check".into(),
        };
        let paths = Paths::new(app_data.path(), &config);
        let started = std::time::Instant::now();
        let out = refresh(&paths, true, &now_iso());
        println!(
            "reachable={} took={:?} words={} corrections={} styles={} issues={:?} errors={:?}",
            out.report.source_reachable,
            started.elapsed(),
            out.snapshot.terms.len(),
            out.snapshot.corrections.len(),
            out.snapshot.styles.len(),
            out.snapshot.issues.iter().map(|i| &i.code).collect::<Vec<_>>(),
            out.report.errors,
        );
        for layer in &out.report.layers {
            println!("  {:?}: {} ({} words, {} corrections)", layer.layer, layer.state, layer.words, layer.corrections);
        }
        // Optionally keep the merged snapshot (for a headless prompt check).
        if let Ok(dump) = std::env::var("VOICEFLOW_PROFILE_DUMP") {
            fs::write(&dump, serde_json::to_string_pretty(&out.snapshot).unwrap()).unwrap();
        }
        assert!(out.report.source_reachable);
        assert!(out.report.errors.is_empty());
        assert_eq!(out.report.uploaded_now, 0);
        assert!(!paths.status_file().exists() || std::env::var("VOICEFLOW_STATUS_EXISTS").is_ok());
        // Offline from the copy just made: the same snapshot.
        let offline = refresh(&paths, false, &now_iso());
        assert_eq!(offline.snapshot.id, out.snapshot.id);
    }


    #[test]
    fn edits_stay_with_the_profile_they_were_made_for() {
        let f = fixture();
        write(
            &f.paths.local_ops().join("dev1-aaa.json"),
            &op_text("aaa", r#""type":"term","term":{"text":"Quibbit"}"#),
        );
        let sam = refresh(&f.paths, true, "t1");
        assert_eq!(sam.snapshot.terms.len(), 1);
        assert!(f.paths.remote_ops().join("dev1-aaa.json").exists());
        // Switch this device to another user in the same folder.
        fs::create_dir_all(f.paths.source.join("profiles/alex")).unwrap();
        let alex = Paths {
            source: f.paths.source.clone(),
            app_data: f.paths.app_data.clone(),
            user: "alex".into(),
            device: "dev1".into(),
        };
        let out = refresh(&alex, true, "t2");
        assert_eq!(out.snapshot.terms.len(), 0, "Sam's word must not reach Alex");
        assert_eq!(out.report.pending_edits, 0);
        assert!(!alex.remote_ops().join("dev1-aaa.json").exists());
        // A word made while Alex is set belongs to Alex only.
        write(
            &f.paths.local_ops().join("dev1-bbb.json"),
            &op_text("bbb", r#""type":"term","term":{"text":"Zibbet"}"#),
        );
        assert_eq!(refresh(&alex, true, "t3").snapshot.terms.len(), 1);
        let back = refresh(&f.paths, true, "t4");
        assert_eq!(back.snapshot.terms.iter().map(|t| t.text.as_str()).collect::<Vec<_>>(), vec!["Quibbit"]);
    }

    #[test]
    fn another_folder_starts_from_its_own_cache_and_gets_every_edit() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        write(
            &f.paths.local_ops().join("dev1-aaa.json"),
            &op_text("aaa", r#""type":"term","term":{"text":"Quibbit"}"#),
        );
        refresh(&f.paths, true, "t1");
        // Folder B, same user: none of A's layers or edits.
        let b_source = f.paths.source.with_file_name("share-b");
        fs::create_dir_all(b_source.join("profiles/sam")).unwrap();
        let b = Paths {
            source: b_source,
            app_data: f.paths.app_data.clone(),
            user: "sam".into(),
            device: "dev1".into(),
        };
        let out = refresh(&b, true, "t2");
        assert_eq!(out.report.layers[2].state, "missing", "A's personal layer must not stand in for B's");
        assert!(out.snapshot.terms.is_empty());
        assert!(out.snapshot.corrections.is_empty());
    }

    #[test]
    fn a_remote_edit_counts_even_when_its_copy_cannot_be_kept() {
        let f = fixture();
        let other = r#"{"schema":"voiceflow-op","version":1,"id":"dev2-bbb","device":"dev2","createdAt":"2026-11-17T11:00:00Z","type":"term","term":{"text":"Quibbit"}}"#;
        write(&f.paths.remote_ops().join("dev2-bbb.json"), other);
        // The cache folder for ops is a file: no copy can be written there.
        write(&f.paths.cache_ops(), "not a folder");
        let out = refresh(&f.paths, true, "t1");
        assert_eq!(out.snapshot.terms.len(), 1);
        assert!(out.report.errors.iter().any(|e| e.contains("Couldn't keep a copy")));
        // A corrupt copy is replaced from the share.
        let _ = fs::remove_file(f.paths.cache_ops());
        write(&f.paths.cache_ops().join("dev2-bbb.json"), "{broken");
        let again = refresh(&f.paths, true, "t2");
        assert_eq!(again.snapshot.terms.len(), 1);
        assert!(parse_op(&fs::read_to_string(f.paths.cache_ops().join("dev2-bbb.json")).unwrap(), Some("dev2-bbb")).is_ok());
    }

    #[test]
    fn oversized_files_are_not_read() {
        let f = fixture();
        write(&f.paths.layer_file(Layer::Personal), PERSONAL);
        let good = refresh(&f.paths, true, "t1").snapshot;
        let huge = format!("{}{}", &PERSONAL[..PERSONAL.len() - 1], format!(",\"note\":\"{}\"}}", "x".repeat(MAX_PROFILE_BYTES as usize)));
        write(&f.paths.layer_file(Layer::Personal), &huge);
        let out = refresh(&f.paths, true, "t2");
        assert_eq!(out.snapshot.id, good.id);
        assert_eq!(out.report.layers[2].state, "cached");
        let big_op = op_text("big", &format!(r#""type":"tombstone","target":"dev1-x","note":"{}""#, "y".repeat(MAX_OP_BYTES as usize)));
        write(&f.paths.remote_ops().join("dev1-big.json"), &big_op);
        let out = refresh(&f.paths, true, "t3");
        assert!(out.report.errors.iter().any(|e| e.contains("larger than")));
    }

    #[test]
    fn write_atomic_uses_a_fresh_temp_file_each_time() {
        let root = tempfile::tempdir().unwrap();
        let target = root.path().join("a.json");
        let handles: Vec<_> = (0..8)
            .map(|i| {
                let target = target.clone();
                std::thread::spawn(move || {
                    for _ in 0..20 {
                        // Windows may refuse a rename while another lands;
                        // what matters is that no reader sees half a file.
                        let _ = write_atomic(&target, format!("{{\"n\":{i}}}").as_bytes());
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        let v: Value = serde_json::from_str(&fs::read_to_string(&target).unwrap()).unwrap();
        assert!(v["n"].is_u64());
        let leftovers = fs::read_dir(root.path()).unwrap().count();
        assert_eq!(leftovers, 1, "no temp files left behind");
    }

    #[test]
    fn status_leaves_quoted_words_out() {
        assert_eq!(redact_quoted("personal: word “zork” is longer than 60 characters"), "personal: word…");
        assert_eq!(redact_quoted("no quotes"), "no quotes");
        assert_eq!(redact_quoted("invalid type: string \"SecretWord\", expected i64"), "invalid type: string…");
        assert_eq!(redact_quoted("word “a”b” c"), "word…");
    }

    #[test]
    fn scope_key_ignores_case_and_trailing_slash_on_windows() {
        let p = |s: &str| Paths { source: PathBuf::from(s), app_data: PathBuf::new(), user: "sam".into(), device: "d".into() };
        assert_ne!(p("C:/a").scope_key(), p("C:/b").scope_key());
        assert!(p("C:/a").scope_key().starts_with("sam-"));
        if cfg!(windows) {
            assert_eq!(p(r"\\NAS\VoiceFlow\").scope_key(), p("//nas/voiceflow").scope_key());
        }
    }

    #[test]
    fn config_checks() {
        let mut c = Config {
            enabled: true,
            source: "x".into(),
            user: "Sam".into(),
            assistant: String::new(),
            device: "dev1".into(),
        };
        assert!(c.check().is_err());
        c.user = "household".into();
        assert!(c.check().is_err());
        c.user = "sam".into();
        assert!(c.check().is_ok());
        c.enabled = false;
        c.source.clear();
        assert!(c.check().is_ok());
    }

    #[test]
    fn iso_time() {
        assert_eq!(iso_from_unix(0), "1970-01-01T00:00:00Z");
        assert_eq!(iso_from_unix(1_795_000_000), "2026-11-18T11:06:40Z");
    }
}
