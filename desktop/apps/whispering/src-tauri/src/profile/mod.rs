//! VoiceFlow word lists (plan v2, phase B + C2): the profile service.
//!
//! - Reads the household, generated and personal layers from a folder (a
//!   local folder or an SMB/UNC share), checks each, keeps a last good copy
//!   of each on this device and merges them with the app's edits by
//!   precedence (`merge.rs`).
//! - Uploads this device's edit files to the share, idempotently, and reads
//!   the other devices' edits for the same user.
//! - Refreshes at start (first from the cache, then from the share), every
//!   hour (every 5 minutes while the share can't be reached) and on demand,
//!   but never while a dictation is running: the app
//!   marks dictations busy and pins the snapshot it started with.
//! - Writes a status file per device to the share (no dictated text).
//!
//! The webview reads the merged snapshot with `profile_state` and hears
//! about changes on the `profile://changed` event.

pub mod merge;
pub mod model;
pub mod sync;

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Serialize;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::Notify;

use merge::Snapshot;
use sync::{Config, Paths, SyncReport};

pub const EVENT_CHANGED: &str = "profile://changed";
const HOURLY: Duration = Duration::from_secs(60 * 60);
const RETRY_UNREACHABLE: Duration = Duration::from_secs(5 * 60);
/// A dictation marked busy longer than this is treated as finished (a pin
/// that was never released must not stop refreshes for good).
const BUSY_EXPIRY: Duration = Duration::from_secs(15 * 60);
/// A share that doesn't answer in this time counts as unreachable.
const SHARE_TIMEOUT: Duration = Duration::from_secs(45);

/// Held by the worker that reads and writes the share for as long as it
/// runs, even after its refresh gave up waiting for it: a stalled worker and
/// a later one never write the same files at once.
static SHARE_IO: Mutex<()> = Mutex::new(());

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub refreshing: bool,
    /// A refresh is waiting for the dictation in progress to finish.
    pub deferred: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub last_sync_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub report: Option<SyncReport>,
}

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileState {
    pub config: Config,
    pub snapshot: Option<Snapshot>,
    pub status: Status,
}

#[derive(Default)]
pub struct ProfileService {
    state: Mutex<ProfileState>,
    busy_since: Mutex<Option<Instant>>,
    wake: Notify,
    /// Serialises refreshes (the hourly one and "Refresh now").
    running: tokio::sync::Mutex<()>,
}

impl ProfileService {
    fn snapshot_state(&self) -> ProfileState {
        self.state.lock().map(|s| s.clone()).unwrap_or_default()
    }

    fn is_busy(&self) -> bool {
        match *self.busy_since.lock().unwrap_or_else(|e| e.into_inner()) {
            Some(since) => since.elapsed() < BUSY_EXPIRY,
            None => false,
        }
    }
}

fn app_data(app: &AppHandle) -> Result<PathBuf, String> {
    app.path().app_data_dir().map_err(|e| e.to_string())
}

fn emit(app: &AppHandle, service: &ProfileService) {
    let _ = app.emit(EVENT_CHANGED, service.snapshot_state());
}

/// Merges from this device only (cache + local edits): instant, used at
/// start and right after an edit is saved.
fn refresh_local(app: &AppHandle, service: &ProfileService) {
    let Ok(dir) = app_data(app) else { return };
    let config = sync::load_config(&dir);
    let mut state = service.state.lock().unwrap_or_else(|e| e.into_inner());
    state.config = config.clone();
    if !config.enabled || config.check().is_err() {
        state.snapshot = None;
        state.status.report = None;
        return;
    }
    let paths = Paths::new(&dir, &config);
    let outcome = sync::refresh(&paths, false, &sync::now_iso());
    state.snapshot = Some(outcome.snapshot);
    // Keep the last report from the share; only fill one in if there is none.
    if state.status.report.is_none() {
        state.status.report = Some(outcome.report);
    }
}

/// Reads the share (uploading pending edits) on a worker thread.
async fn refresh_online(app: &AppHandle, service: &ProfileService) {
    let _guard = service.running.lock().await;
    let Ok(dir) = app_data(app) else { return };
    let config = sync::load_config(&dir);
    if !config.enabled || config.check().is_err() {
        refresh_local(app, service);
        emit(app, service);
        return;
    }
    if let Ok(mut state) = service.state.lock() {
        state.status.refreshing = true;
        state.status.deferred = false;
    }
    emit(app, service);

    let version = app.package_info().version.to_string();
    let paths = Paths::new(&dir, &config);
    let now = sync::now_iso();
    let task = tokio::task::spawn_blocking(move || {
        let _io = SHARE_IO.lock().unwrap_or_else(|e| e.into_inner());
        let outcome = sync::refresh(&paths, true, &now);
        let mut outcome = outcome;
        if outcome.report.source_reachable {
            let synced = outcome.report.synced_at.clone();
            if let Err(e) = sync::write_status(&paths, &version, &outcome.snapshot, &outcome.report, synced.as_deref()) {
                outcome.report.errors.push(format!("Couldn't write this device's status: {e}"));
            }
        }
        outcome
    });
    let failure = match tokio::time::timeout(SHARE_TIMEOUT, task).await {
        Ok(Ok(outcome)) => Ok(outcome),
        Ok(Err(e)) => Err(format!("The word-list refresh failed ({e}); using the last good copy.")),
        Err(_) => Err(format!("{} didn't answer in time; using the last good copy.", config.source)),
    };
    // The share hung or the worker failed: use this device's copy.
    let outcome = failure.unwrap_or_else(|message| {
        let paths = Paths::new(&dir, &config);
        let mut outcome = sync::refresh(&paths, false, &sync::now_iso());
        outcome.report.errors.push(message);
        outcome
    });

    // A dictation started while the share was read: it keeps the word lists
    // and settings it started with, so the new ones wait until it is done.
    if service.is_busy() {
        if let Ok(mut state) = service.state.lock() {
            state.status.deferred = true;
        }
        emit(app, service);
        while service.is_busy() {
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        if let Ok(mut state) = service.state.lock() {
            state.status.deferred = false;
        }
    }

    if let Ok(mut state) = service.state.lock() {
        // The settings may have changed while the share was read.
        if state.config.user == config.user && state.config.source == config.source {
            if outcome.report.synced_at.is_some() {
                state.status.last_sync_at = outcome.report.synced_at.clone();
            }
            state.snapshot = Some(outcome.snapshot);
            state.status.report = Some(outcome.report);
        }
        state.status.refreshing = false;
    }
    emit(app, service);
}

/// The background loop: a refresh on start, every hour and when asked,
/// each one waiting for any dictation in progress to finish.
pub fn start(app: &AppHandle) {
    let handle = app.clone();
    tauri::async_runtime::spawn(async move {
        {
            let service = handle.state::<ProfileService>();
            refresh_local(&handle, &service);
            emit(&handle, &service);
        }
        let mut first = true;
        loop {
            let service = handle.state::<ProfileService>();
            if !first {
                // A share that wasn't there (the PC just woke or signed in
                // before the network) is tried again sooner than hourly.
                let unreachable = service
                    .state
                    .lock()
                    .map(|s| {
                        s.config.enabled
                            && s.status.report.as_ref().is_some_and(|r| !r.source_reachable)
                    })
                    .unwrap_or(false);
                let wait = if unreachable { RETRY_UNREACHABLE } else { HOURLY };
                tokio::select! {
                    _ = service.wake.notified() => {}
                    _ = tokio::time::sleep(wait) => {}
                }
            }
            first = false;
            if service.is_busy() {
                if let Ok(mut state) = service.state.lock() {
                    state.status.deferred = true;
                }
                emit(&handle, &service);
                while service.is_busy() {
                    tokio::time::sleep(Duration::from_secs(2)).await;
                }
            }
            refresh_online(&handle, &service).await;
        }
    });
}

/// Whether a window title matches a title pattern from the word lists in
/// force. Matched here, in linear time, and only on this device: the title
/// is never stored or sent by this check.
pub fn title_denied(app: &AppHandle, title: &str) -> bool {
    let patterns = app
        .try_state::<ProfileService>()
        .and_then(|service| {
            service
                .state
                .lock()
                .ok()
                .and_then(|s| s.snapshot.as_ref().map(|snap| snap.privacy.title_deny_re.clone()))
        })
        .unwrap_or_default();
    patterns
        .iter()
        .filter_map(|p| model::title_pattern(p).ok())
        .any(|re| re.is_match(title))
}

/// The word lists block this app (`privacy.blockApps`; lower-case exe name or
/// bundle id). Read at the hotkey by the native gate (`hotkey.rs`).
pub fn blocked_app(app: &AppHandle, exe: Option<&str>, bundle: Option<&str>) -> bool {
    let listed = app
        .try_state::<ProfileService>()
        .and_then(|service| {
            service
                .state
                .lock()
                .ok()
                .and_then(|s| s.snapshot.as_ref().map(|snap| snap.privacy.block_apps.clone()))
        })
        .unwrap_or_default();
    listed.iter().any(|a| {
        let a = a.to_lowercase();
        exe == Some(a.as_str()) || bundle == Some(a.as_str())
    })
}

#[tauri::command]
pub fn profile_state(service: State<'_, ProfileService>) -> ProfileState {
    service.snapshot_state()
}

/// Saves the word-list settings (source, user, assistant), merges from the
/// cache at once and refreshes from the share in the background.
#[tauri::command]
pub fn profile_set_config(
    app: AppHandle,
    service: State<'_, ProfileService>,
    config: Config,
) -> Result<ProfileState, String> {
    let config = Config {
        source: config.source.trim().to_string(),
        user: config.user.trim().to_lowercase(),
        assistant: config.assistant.trim().to_string(),
        ..config
    };
    config.check()?;
    let dir = app_data(&app)?;
    sync::save_config(&dir, &config)?;
    if let Ok(mut state) = service.state.lock() {
        state.status = Status::default();
    }
    refresh_local(&app, &service);
    service.wake.notify_one();
    emit(&app, &service);
    Ok(service.snapshot_state())
}

/// "Refresh now". Runs after the dictation in progress, if any.
#[tauri::command]
pub fn profile_refresh(app: AppHandle, service: State<'_, ProfileService>) -> ProfileState {
    service.wake.notify_one();
    if service.is_busy() {
        if let Ok(mut state) = service.state.lock() {
            state.status.deferred = true;
        }
        emit(&app, &service);
    }
    service.snapshot_state()
}

/// An edit file was written in `profile-ops`: merge it in now (from this
/// device) and upload it in the background.
#[tauri::command]
pub fn profile_edits_changed(app: AppHandle, service: State<'_, ProfileService>) -> ProfileState {
    refresh_local(&app, &service);
    emit(&app, &service);
    service.wake.notify_one();
    service.snapshot_state()
}

/// The users a word-list folder has (`profiles/<user>/`, not `household`),
/// for the Settings picker. Empty when the folder can't be read in time.
#[tauri::command]
pub async fn profile_list_users(source: String) -> Vec<String> {
    let task = tokio::task::spawn_blocking(move || {
        let Ok(entries) = std::fs::read_dir(PathBuf::from(source.trim()).join("profiles")) else {
            return Vec::new();
        };
        let mut users: Vec<String> = entries
            .filter_map(Result::ok)
            .filter(|e| e.file_type().map(|t| t.is_dir()).unwrap_or(false))
            .map(|e| e.file_name().to_string_lossy().to_string())
            .filter(|n| n != "household" && model::valid_name(n))
            .collect();
        users.sort();
        users
    });
    match tokio::time::timeout(Duration::from_secs(10), task).await {
        Ok(Ok(users)) => users,
        _ => Vec::new(),
    }
}

/// The app marks a dictation in progress so no refresh swaps the word lists
/// under it. A refresh asked for meanwhile runs when it is cleared.
#[tauri::command]
pub fn profile_set_busy(service: State<'_, ProfileService>, busy: bool) {
    let mut since = service.busy_since.lock().unwrap_or_else(|e| e.into_inner());
    *since = if busy { Some(Instant::now()) } else { None };
}
