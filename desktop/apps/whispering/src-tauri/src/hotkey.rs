//! VoiceFlow: the dictation hotkeys, handled natively.
//!
//! Until 0.9.3 every global shortcut went from the OS to the main webview
//! (tauri-plugin-global-shortcut's JS `register(..., callback)`), and the
//! webview then captured the target app and started the microphone. When
//! Windows or WebView2 put that webview to sleep (a minimized or hidden
//! window), a press reached Rust and went no further: nothing recorded,
//! nothing logged (plan v2, "A-gate").
//!
//! Now the dictation keys (toggle, push-to-talk, start, stop, cancel) are
//! registered here with a Rust handler. A press is queued to one worker
//! thread, which, in order and without the webview:
//!
//! 1. captures the app in front (`context::capture_now`, the same snapshot
//!    the webview used to take) and refuses at once a blocked app or a
//!    password field (spec §4.3 Tier B: nothing is recorded);
//! 2. starts or stops the Rust recorder;
//! 3. tells the webview what happened (`vf-native-hotkey`), which then
//!    applies the full privacy decision before anything is uploaded and runs
//!    transcription, tidy-up and delivery.
//!
//! Each event carries a sequence number. The webview acknowledges it twice
//! (`hotkey_ack`): when it arrives, and when it has been handled. Until the
//! first, a watchdog re-sends it every second, logs that the webview is
//! late, and shows "VoiceFlow is waking up…" in the tray. Until the second,
//! Rust keeps it, so a page that reloads mid-way asks for it again
//! (`hotkey_pending`). The webview handles each sequence number once, so a
//! re-send never starts a second recording, and a recording stopped while
//! the webview slept is picked up when it wakes.
//!
//! When the recording method is not the Rust recorder (`recorder` is None in
//! the config), presses are forwarded to the webview as `key` events with the
//! same acknowledgement, and the webview runs its own commands.

use crate::context::TargetSnapshot;
use crate::recorder::recorder::AudioRecording;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{mpsc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// The event the main webview listens to.
pub const EVENT: &str = "vf-native-hotkey";
/// The webview that runs the dictation engine.
pub const ENGINE_WINDOW: &str = "main";
/// How long the webview has to acknowledge an event before it counts as late.
pub const ACK_BUDGET: Duration = Duration::from_secs(1);
/// How often an unacknowledged event is sent again.
const RETRY_EVERY: Duration = Duration::from_secs(1);
/// Unfinished events kept before the oldest are dropped (with a log). A
/// `stopped` event is never dropped: it is the only pointer to its audio.
const MAX_PENDING: usize = 256;
const WATCHDOG_TICK: Duration = Duration::from_millis(250);

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Action {
    Toggle,
    Start,
    Stop,
    Cancel,
    PushToTalk,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum KeyState {
    Pressed,
    Released,
}

/// Where a trigger came from: a global hotkey, or the app itself (a button,
/// the recording bar, a key inside the window).
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Source {
    Hotkey,
    App,
}

#[derive(Clone, Debug, PartialEq)]
pub enum Op {
    Key {
        action: Action,
        state: KeyState,
        source: Source,
        purpose: Option<String>,
        at_ms: u64,
    },
    /// The webview's own privacy decision refused a recording that the
    /// native gate let start: stop it and delete its audio.
    Discard { recording_id: String },
}

// ---------------------------------------------------------------------------
// The state machine (pure)
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
pub enum Phase {
    Idle,
    Recording {
        id: String,
        ptt: bool,
        purpose: Option<String>,
    },
}

#[derive(Clone, Debug, PartialEq)]
pub enum Plan {
    Start { ptt: bool },
    Stop,
    Cancel,
    Ignore(&'static str),
}

/// What a key does in a phase.
pub fn plan(phase: &Phase, action: Action, state: KeyState) -> Plan {
    use Action::*;
    use KeyState::*;
    let recording = matches!(phase, Phase::Recording { .. });
    match (action, state) {
        (Toggle, Pressed) if recording => Plan::Stop,
        (Toggle, Pressed) => Plan::Start { ptt: false },
        (Start, Pressed) if recording => Plan::Ignore("already recording"),
        (Start, Pressed) => Plan::Start { ptt: false },
        (Stop, Pressed) if recording => Plan::Stop,
        (Stop, Pressed) => Plan::Ignore("not recording"),
        (Cancel, Pressed) if recording => Plan::Cancel,
        (Cancel, Pressed) => Plan::Ignore("nothing to cancel"),
        (PushToTalk, Pressed) if recording => Plan::Ignore("already recording"),
        (PushToTalk, Pressed) => Plan::Start { ptt: true },
        (PushToTalk, Released) => match phase {
            Phase::Recording { ptt: true, .. } => Plan::Stop,
            Phase::Recording { .. } => Plan::Ignore("this recording was not started by push-to-talk"),
            Phase::Idle => Plan::Ignore("not recording"),
        },
        (_, Released) => Plan::Ignore("key released"),
    }
}

// ---------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------

/// Which microphone was used, in the webview's `DeviceAcquisitionOutcome`
/// shape.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DeviceOutcome {
    /// "success" | "fallback"
    pub outcome: &'static str,
    /// "no-device-selected" | "preferred-device-unavailable" (fallback only)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
    pub device_id: String,
}

/// The microphone to use: the chosen one, else the first one found.
pub fn choose_device(devices: &[String], preferred: Option<&str>) -> Result<DeviceOutcome, String> {
    let Some(first) = devices.first() else {
        return Err(if preferred.is_some() {
            "VoiceFlow couldn't find the selected microphone. Make sure it's connected and try again.".into()
        } else {
            "VoiceFlow couldn't find any microphone. Make sure one is connected and try again.".into()
        });
    };
    match preferred {
        None => Ok(DeviceOutcome {
            outcome: "fallback",
            reason: Some("no-device-selected"),
            device_id: first.clone(),
        }),
        Some(p) if devices.iter().any(|d| d == p) => Ok(DeviceOutcome {
            outcome: "success",
            reason: None,
            device_id: p.to_string(),
        }),
        Some(_) => Ok(DeviceOutcome {
            outcome: "fallback",
            reason: Some("preferred-device-unavailable"),
            device_id: first.clone(),
        }),
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase", rename_all_fields = "camelCase")]
pub enum Event {
    /// The microphone is on; `target` is the app snapshot taken at the key.
    Started {
        recording_id: String,
        target: Box<TargetSnapshot>,
        device: DeviceOutcome,
        ptt: bool,
        purpose: Option<String>,
        source: Source,
    },
    /// The microphone is off and the audio file is final.
    Stopped {
        recording_id: String,
        recording: AudioRecording,
        /// VoiceFlow 0.9.11: the app in front at Stop (identity only, no
        /// field check). The text is pasted only if this window is still in
        /// front when it is ready; otherwise it is copied.
        target: Option<Box<TargetSnapshot>>,
        purpose: Option<String>,
        source: Source,
    },
    /// The recording was stopped and its audio deleted.
    Cancelled {
        recording_id: String,
        /// True when the webview asked for it (its privacy decision refused).
        discarded: bool,
        source: Source,
    },
    /// The native gate refused to record (blocked app or password field).
    Refused {
        target: Box<TargetSnapshot>,
        purpose: Option<String>,
        source: Source,
    },
    Failed {
        /// "start" | "stop" | "cancel"
        step: &'static str,
        message: String,
        recording_id: Option<String>,
        source: Source,
    },
    /// A key forwarded to the webview (the recording method is not the Rust
    /// recorder, so the webview runs its own command).
    Key {
        action: Action,
        state: KeyState,
        source: Source,
    },
}

/// One event as the webview receives it.
#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Envelope {
    pub seq: u64,
    /// When the key was pressed (ms since the Unix epoch).
    pub at_ms: u64,
    #[serde(flatten)]
    pub event: Event,
}

// ---------------------------------------------------------------------------
// Delivery: acknowledgements and re-sends (pure, clocked by the caller)
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct Pending {
    envelope: Envelope,
    first_sent: Instant,
    last_sent: Instant,
    late: bool,
    /// The webview has it (first acknowledgement); kept until handled.
    received: bool,
}

#[derive(Debug, Default)]
pub struct Delivery {
    next_seq: u64,
    pending: VecDeque<Pending>,
}

#[derive(Debug, Default)]
pub struct Tick {
    /// Events to send again now.
    pub resend: Vec<Envelope>,
    /// Events that just went past the acknowledgement budget.
    pub newly_late: Vec<u64>,
    /// Some event is past the budget and still unacknowledged.
    pub waiting: bool,
    /// Events dropped because too many were waiting.
    pub dropped: Vec<u64>,
}

#[derive(Debug, PartialEq)]
pub struct Ack {
    pub latency: Duration,
    pub late: bool,
    /// Some other event is still late after this one.
    pub still_waiting: bool,
}

impl Delivery {
    /// Numbers the event and keeps it until it is acknowledged.
    pub fn push(&mut self, event: Event, at_ms: u64, now: Instant) -> (Envelope, Vec<u64>) {
        self.next_seq += 1;
        let envelope = Envelope { seq: self.next_seq, at_ms, event };
        let mut dropped = Vec::new();
        while self.pending.len() >= MAX_PENDING {
            let Some(index) = self
                .pending
                .iter()
                .position(|p| !matches!(p.envelope.event, Event::Stopped { .. }))
            else {
                break;
            };
            if let Some(old) = self.pending.remove(index) {
                dropped.push(old.envelope.seq);
            }
        }
        self.pending.push_back(Pending {
            envelope: envelope.clone(),
            first_sent: now,
            last_sent: now,
            late: false,
            received: false,
        });
        (envelope, dropped)
    }

    /// The webview has the event (`done`: and has handled it). Returns the
    /// arrival latency the first time it arrives, else None.
    pub fn ack(&mut self, seq: u64, done: bool, now: Instant) -> Option<Ack> {
        let index = self.pending.iter().position(|p| p.envelope.seq == seq)?;
        let first = !self.pending[index].received;
        let latency = now.saturating_duration_since(self.pending[index].first_sent);
        let late = self.pending[index].late || latency > ACK_BUDGET;
        if done {
            self.pending.remove(index);
        } else {
            self.pending[index].received = true;
        }
        first.then(|| Ack {
            latency,
            late,
            still_waiting: self.pending.iter().any(|p| p.late && !p.received),
        })
    }

    pub fn tick(&mut self, now: Instant) -> Tick {
        let mut tick = Tick::default();
        for p in self.pending.iter_mut().filter(|p| !p.received) {
            let age = now.saturating_duration_since(p.first_sent);
            if age > ACK_BUDGET {
                if !p.late {
                    p.late = true;
                    tick.newly_late.push(p.envelope.seq);
                }
                tick.waiting = true;
                if now.saturating_duration_since(p.last_sent) >= RETRY_EVERY {
                    p.last_sent = now;
                    tick.resend.push(p.envelope.clone());
                }
            }
        }
        tick
    }

    /// Everything not yet handled, oldest first.
    pub fn unacknowledged(&self) -> Vec<Envelope> {
        self.pending.iter().map(|p| p.envelope.clone()).collect()
    }
}

// ---------------------------------------------------------------------------
// The engine: one press, start to finish
// ---------------------------------------------------------------------------

/// The started recording.
#[derive(Clone, Debug, PartialEq)]
pub struct StartedRecording {
    pub recording_id: String,
    pub device: DeviceOutcome,
}

/// What the machine does to the world. The real one is `TauriEngine`; tests
/// use a fake.
pub trait Engine {
    fn capture(&mut self) -> TargetSnapshot;
    /// VoiceFlow 0.9.11: the app in front at Stop, identity only (quick).
    fn capture_at_stop(&mut self) -> TargetSnapshot;
    /// Tier B, decided here so a refused app never has the microphone on.
    fn refuses(&self, target: &TargetSnapshot) -> bool;
    fn start(&mut self) -> Result<StartedRecording, String>;
    fn stop(&mut self) -> Result<AudioRecording, String>;
    fn cancel(&mut self) -> Result<(), String>;
    /// The recorder's own state: the id of the recording in progress.
    fn recording_id(&self) -> Option<String>;
    /// True when the Rust recorder is the recording method.
    fn records(&self) -> bool;
    fn publish(&mut self, event: Event, at_ms: u64);
    fn set_recording(&mut self, recording: bool);
}

#[derive(Debug, Default)]
pub struct Machine {
    pub phase: Option<Phase>,
}

impl Machine {
    fn phase(&self) -> Phase {
        self.phase.clone().unwrap_or(Phase::Idle)
    }

    /// Lines the phase up with the recorder: a recording started or ended
    /// another way (the webview's own path, a failure) is taken as it is.
    fn reconcile(&mut self, engine: &dyn Engine) {
        let actual = engine.recording_id();
        let phase = self.phase();
        self.phase = Some(match (phase, actual) {
            (Phase::Recording { id, ptt, purpose }, Some(now)) if id == now => {
                Phase::Recording { id, ptt, purpose }
            }
            (_, Some(now)) => Phase::Recording { id: now, ptt: false, purpose: None },
            (_, None) => Phase::Idle,
        });
    }

    /// Runs one operation. Returns what was planned (for the log and tests).
    pub fn handle(&mut self, engine: &mut dyn Engine, op: Op) -> Plan {
        match op {
            Op::Discard { recording_id } => {
                self.reconcile(engine);
                match self.phase() {
                    Phase::Recording { id, .. } if id == recording_id => {
                        self.cancel(engine, id, true, Source::App, now_ms());
                        Plan::Cancel
                    }
                    _ => Plan::Ignore("that recording is not in progress"),
                }
            }
            Op::Key { action, state, source, purpose, at_ms } => {
                if !engine.records() {
                    engine.publish(Event::Key { action, state, source }, at_ms);
                    return Plan::Ignore("forwarded to the webview");
                }
                self.reconcile(engine);
                let phase = self.phase();
                let plan = plan(&phase, action, state);
                match (&plan, phase) {
                    (Plan::Start { ptt }, _) => self.start(engine, *ptt, purpose, source, at_ms),
                    (Plan::Stop, Phase::Recording { id, purpose, .. }) => {
                        self.stop(engine, id, purpose, source, at_ms)
                    }
                    (Plan::Cancel, Phase::Recording { id, .. }) => {
                        self.cancel(engine, id, false, source, at_ms)
                    }
                    _ => {}
                }
                plan
            }
        }
    }

    fn start(
        &mut self,
        engine: &mut dyn Engine,
        ptt: bool,
        purpose: Option<String>,
        source: Source,
        at_ms: u64,
    ) {
        let target = engine.capture();
        if engine.refuses(&target) {
            self.phase = Some(Phase::Idle);
            engine.publish(Event::Refused { target: Box::new(target), purpose, source }, at_ms);
            return;
        }
        match engine.start() {
            Ok(started) => {
                self.phase = Some(Phase::Recording {
                    id: started.recording_id.clone(),
                    ptt,
                    purpose: purpose.clone(),
                });
                engine.set_recording(true);
                engine.publish(
                    Event::Started {
                        recording_id: started.recording_id,
                        target: Box::new(target),
                        device: started.device,
                        ptt,
                        purpose,
                        source,
                    },
                    at_ms,
                );
            }
            Err(message) => {
                self.phase = Some(Phase::Idle);
                engine.publish(
                    Event::Failed { step: "start", message, recording_id: None, source },
                    at_ms,
                );
            }
        }
    }

    fn stop(
        &mut self,
        engine: &mut dyn Engine,
        id: String,
        purpose: Option<String>,
        source: Source,
        at_ms: u64,
    ) {
        self.phase = Some(Phase::Idle);
        // The window he left his cursor in, read before anything else moves.
        let target = engine.capture_at_stop();
        engine.set_recording(false);
        match engine.stop() {
            Ok(recording) => engine.publish(
                Event::Stopped {
                    recording_id: id,
                    recording,
                    target: Some(Box::new(target)),
                    purpose,
                    source,
                },
                at_ms,
            ),
            Err(message) => engine.publish(
                Event::Failed { step: "stop", message, recording_id: Some(id), source },
                at_ms,
            ),
        }
    }

    fn cancel(&mut self, engine: &mut dyn Engine, id: String, discarded: bool, source: Source, at_ms: u64) {
        self.phase = Some(Phase::Idle);
        engine.set_recording(false);
        match engine.cancel() {
            Ok(()) => engine.publish(Event::Cancelled { recording_id: id, discarded, source }, at_ms),
            Err(message) => engine.publish(
                Event::Failed { step: "cancel", message, recording_id: Some(id), source },
                at_ms,
            ),
        }
    }
}

/// Tier B at the key: a blocked app (password managers). VoiceFlow itself
/// and an unresolved snapshot are never refused: since 0.9.11 they record
/// anywhere, and the paste rule decides where the text goes.
pub fn refused_by(target: &TargetSnapshot, blocked: &dyn Fn(Option<&str>, Option<&str>) -> bool) -> bool {
    if !target.resolved || target.is_self {
        return false;
    }
    // 0.9.11: the field check never refuses a start; a password field only
    // means the text is copied instead of pasted.
    let exe = target.exe_name.as_deref().map(str::to_lowercase);
    let bundle = target.bundle_id.as_deref().map(str::to_lowercase);
    blocked(exe.as_deref(), bundle.as_deref())
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// A 21-character id like the webview's nanoid (also the audio file name).
pub fn new_recording_id() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::atomic::{AtomicU64, Ordering};
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let mut out = String::with_capacity(21);
    let mut word = 0u64;
    let mut bits = 0u32;
    while out.len() < 21 {
        if bits < 6 {
            let mut h = RandomState::new().build_hasher();
            h.write_u64(COUNTER.fetch_add(1, Ordering::Relaxed));
            h.write_u128(SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
            word = h.finish();
            bits = 64;
        }
        out.push(ALPHABET[(word & 63) as usize] as char);
        word >>= 6;
        bits -= 6;
    }
    out
}

// ---------------------------------------------------------------------------
// Configuration from the webview
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Binding {
    pub action: Option<Action>,
    pub accelerator: String,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecorderParams {
    pub device_id: Option<String>,
    pub output_folder: String,
    pub sample_rate: Option<u32>,
}

#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    #[serde(default)]
    pub bindings: Vec<Binding>,
    /// None: the recording method is not the Rust recorder.
    #[serde(default)]
    pub recorder: Option<RecorderParams>,
    /// The built-in privacy list: lower-case exe names and bundle ids.
    #[serde(default)]
    pub blocked_apps: Vec<String>,
    /// Another recording mode (voice activated) is running in the webview:
    /// keys go to the webview, which stops it before the microphone opens.
    #[serde(default)]
    pub forward: bool,
}

// ---------------------------------------------------------------------------
// The running service
// ---------------------------------------------------------------------------

#[derive(Default)]
pub struct NativeHotkeys {
    tx: Mutex<Option<mpsc::Sender<Op>>>,
    config: Mutex<Config>,
    /// (action, accelerator) pairs registered natively.
    registered: Mutex<Vec<(Action, String)>>,
    delivery: Mutex<Delivery>,
    waking: Mutex<bool>,
}

impl NativeHotkeys {
    fn send(&self, op: Op) -> Result<(), String> {
        let guard = self.tx.lock().unwrap_or_else(|e| e.into_inner());
        match guard.as_ref() {
            Some(tx) => tx.send(op).map_err(|e| format!("hotkey worker stopped: {e}")),
            None => Err("hotkey worker not started".into()),
        }
    }

    fn config(&self) -> Config {
        self.config.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
}

#[cfg(desktop)]
pub mod runtime {
    use super::*;
    use crate::recorder::commands::AppData;
    use tauri::{AppHandle, Emitter, Manager};
    use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

    /// The real effects.
    pub struct TauriEngine {
        pub app: AppHandle,
    }

    impl TauriEngine {
        fn service(&self) -> tauri::State<'_, NativeHotkeys> {
            self.app.state::<NativeHotkeys>()
        }

        fn with_recorder<T>(
            &self,
            f: impl FnOnce(&mut crate::recorder::recorder::RecorderState) -> Result<T, String>,
        ) -> Result<T, String> {
            let data = self.app.state::<AppData>();
            let mut recorder = data
                .recorder
                .lock()
                .map_err(|e| format!("Failed to lock recorder: {e}"))?;
            f(&mut recorder)
        }
    }

    impl Engine for TauriEngine {
        fn capture(&mut self) -> TargetSnapshot {
            crate::context::capture_now(&self.app, true)
        }

        fn capture_at_stop(&mut self) -> TargetSnapshot {
            crate::context::capture_now(&self.app, false)
        }

        fn refuses(&self, target: &TargetSnapshot) -> bool {
            let listed = self.service().config().blocked_apps;
            let app = self.app.clone();
            refused_by(target, &|exe, bundle| {
                exe.is_some_and(|e| listed.iter().any(|b| b == e))
                    || bundle.is_some_and(|b| listed.iter().any(|l| l == b))
                    || crate::profile::blocked_app(&app, exe, bundle)
            })
        }

        fn start(&mut self) -> Result<StartedRecording, String> {
            // Open the sound output while the microphone opens.
            crate::sound::prewarm();
            let params = self
                .service()
                .config()
                .recorder
                .ok_or_else(|| "The Rust recorder is not the recording method.".to_string())?;
            let folder = PathBuf::from(&params.output_folder);
            if !folder.exists() {
                std::fs::create_dir_all(&folder)
                    .map_err(|e| format!("Failed to create output folder: {e}"))?;
            }
            if !folder.is_dir() {
                return Err(format!("Output path is not a directory: {folder:?}"));
            }
            let recording_id = new_recording_id();
            self.with_recorder(|recorder| {
                let devices = recorder.enumerate_devices()?;
                let device = choose_device(&devices, params.device_id.as_deref())?;
                log::info!(
                    "Initializing recording session: device={}, id={}, folder={}, sample_rate={:?} (hotkey)",
                    device.device_id, recording_id, params.output_folder, params.sample_rate
                );
                let opened = recorder
                    .init_session(device.device_id.clone(), folder.clone(), recording_id.clone(), params.sample_rate)
                    .and_then(|()| recorder.start_recording());
                if let Err(e) = opened {
                    let _ = recorder.close_session();
                    return Err(format!(
                        "VoiceFlow couldn't open the microphone ({e}). Another app may be using it, Windows may be blocking it, or it may be unplugged."
                    ));
                }
                Ok(StartedRecording { recording_id: recording_id.clone(), device })
            })
        }

        fn stop(&mut self) -> Result<AudioRecording, String> {
            self.with_recorder(|recorder| {
                let result = recorder.stop_recording();
                let _ = recorder.close_session();
                result
            })
        }

        fn cancel(&mut self) -> Result<(), String> {
            self.with_recorder(|recorder| recorder.cancel_recording())
        }

        fn recording_id(&self) -> Option<String> {
            self.with_recorder(|recorder| Ok(recorder.get_current_recording_id()))
                .ok()
                .flatten()
        }

        fn records(&self) -> bool {
            let config = self.service().config();
            config.recorder.is_some() && !config.forward
        }

        fn publish(&mut self, event: Event, at_ms: u64) {
            // The dictation's cue, now, without waiting for the webview.
            match &event {
                Event::Started { .. } => crate::sound::on_native(crate::sound::Cue::Start, at_ms),
                Event::Stopped { .. } => crate::sound::on_native(crate::sound::Cue::Stop, at_ms),
                Event::Cancelled { discarded: false, .. } => {
                    crate::sound::on_native(crate::sound::Cue::Cancel, at_ms)
                }
                _ => {}
            }
            let (envelope, dropped) = {
                let service = self.service();
                let mut delivery = service.delivery.lock().unwrap_or_else(|e| e.into_inner());
                delivery.push(event, at_ms, Instant::now())
            };
            for seq in dropped {
                log::warn!("[hotkey] event {seq} dropped unacknowledged (too many waiting)");
            }
            log::info!("[hotkey] event {} ({}) sent to the webview", envelope.seq, kind_of(&envelope.event));
            emit(&self.app, &envelope);
        }

        fn set_recording(&mut self, recording: bool) {
            crate::tray::show_recording(&self.app, recording);
        }
    }

    fn emit(app: &AppHandle, envelope: &Envelope) {
        if let Err(e) = app.emit_to(ENGINE_WINDOW, EVENT, envelope) {
            log::warn!("[hotkey] could not send event {} to the webview: {e}", envelope.seq);
        }
    }

    /// Starts the worker and the watchdog. Called once from `setup`.
    pub fn start(app: &AppHandle) {
        let (tx, rx) = mpsc::channel::<Op>();
        *app.state::<NativeHotkeys>().tx.lock().unwrap_or_else(|e| e.into_inner()) = Some(tx);

        let worker_app = app.clone();
        let spawned = std::thread::Builder::new()
            .name("voiceflow-hotkeys".into())
            .spawn(move || {
                let mut engine = TauriEngine { app: worker_app };
                let mut machine = Machine::default();
                for op in rx {
                    let label = describe(&op);
                    let plan = machine.handle(&mut engine, op);
                    log::info!("[hotkey] {label} -> {plan:?}");
                }
            });
        if let Err(e) = spawned {
            log::error!("[hotkey] the hotkey worker did not start: {e}");
        }

        let watch_app = app.clone();
        let spawned = std::thread::Builder::new()
            .name("voiceflow-hotkey-watchdog".into())
            .spawn(move || loop {
                std::thread::sleep(WATCHDOG_TICK);
                watchdog_tick(&watch_app);
            });
        if let Err(e) = spawned {
            log::error!("[hotkey] the hotkey watchdog did not start: {e}");
        }
    }

    fn watchdog_tick(app: &AppHandle) {
        let service = app.state::<NativeHotkeys>();
        let tick = {
            let mut delivery = service.delivery.lock().unwrap_or_else(|e| e.into_inner());
            delivery.tick(Instant::now())
        };
        for seq in &tick.newly_late {
            log::warn!(
                "[hotkey] the webview has not acknowledged event {seq} within {} ms; retrying until it wakes",
                ACK_BUDGET.as_millis()
            );
        }
        for envelope in &tick.resend {
            emit(app, envelope);
        }
        set_waking(app, tick.waiting);
    }

    fn set_waking(app: &AppHandle, waking: bool) {
        let changed = {
            let service = app.state::<NativeHotkeys>();
            let mut current = service.waking.lock().unwrap_or_else(|e| e.into_inner());
            let changed = *current != waking;
            *current = waking;
            changed
        };
        // Outside the lock: a tray update from this thread waits for the main
        // thread, which may itself be in `hotkey_ack` wanting the lock.
        if changed {
            crate::tray::show_waking(app, waking);
        }
    }

    fn describe(op: &Op) -> String {
        match op {
            Op::Key { action, state, source, at_ms, .. } => {
                format!("{action:?} {state:?} ({source:?}, pressed at {at_ms})")
            }
            Op::Discard { recording_id } => format!("discard {recording_id}"),
        }
    }

    fn kind_of(event: &Event) -> &'static str {
        match event {
            Event::Started { .. } => "started",
            Event::Stopped { .. } => "stopped",
            Event::Cancelled { .. } => "cancelled",
            Event::Refused { .. } => "refused",
            Event::Failed { .. } => "failed",
            Event::Key { .. } => "key",
        }
    }

    /// Registers the dictation keys natively (sync, so it runs on the main
    /// thread like the plugin's own `register`). Keys that did not change
    /// stay registered; a key the webview had registered is taken over.
    #[tauri::command]
    pub fn hotkey_configure(app: AppHandle, config: Config) -> Result<Vec<String>, String> {
        let service = app.state::<NativeHotkeys>();
        let wanted: Vec<(Action, String)> = config
            .bindings
            .iter()
            .filter_map(|b| b.action.map(|a| (a, b.accelerator.clone())))
            .filter(|(_, accel)| !accel.trim().is_empty())
            .collect();
        *service.config.lock().unwrap_or_else(|e| e.into_inner()) = config;

        let shortcuts = app.global_shortcut();
        let mut registered = service.registered.lock().unwrap_or_else(|e| e.into_inner());
        let mut problems = Vec::new();

        // Drop keys no longer wanted (or now bound to another action).
        let mut keep = Vec::new();
        for (action, accel) in registered.drain(..) {
            if wanted.contains(&(action, accel.clone())) && shortcuts.is_registered(accel.as_str()) {
                keep.push((action, accel));
            } else if shortcuts.is_registered(accel.as_str()) {
                match shortcuts.unregister(accel.as_str()) {
                    Ok(()) => log::info!("[hotkey] unregistered {action:?} = {accel}"),
                    Err(e) => log::warn!("[hotkey] could not unregister {action:?} = {accel}: {e}"),
                }
            }
        }
        *registered = keep;

        for (action, accel) in wanted {
            if registered.contains(&(action, accel.clone())) {
                continue;
            }
            // A key the webview registered itself (an older path) is taken over,
            // so one press never runs twice.
            if shortcuts.is_registered(accel.as_str()) {
                let _ = shortcuts.unregister(accel.as_str());
            }
            let worker = app.clone();
            let result = shortcuts.on_shortcut(accel.as_str(), move |_app, _shortcut, event| {
                let state = match event.state {
                    ShortcutState::Pressed => KeyState::Pressed,
                    ShortcutState::Released => KeyState::Released,
                };
                let at_ms = now_ms();
                // Quick and lock-free of the plugin: the plugin holds its own
                // lock while calling this.
                let sent = worker.state::<NativeHotkeys>().send(Op::Key {
                    action,
                    state,
                    source: Source::Hotkey,
                    purpose: None,
                    at_ms,
                });
                match sent {
                    Ok(()) => log::info!("[hotkey] {action:?} {state:?} at {at_ms}"),
                    Err(e) => log::error!("[hotkey] {action:?} {state:?} at {at_ms} was lost: {e}"),
                }
            });
            match result {
                Ok(()) => {
                    log::info!("[hotkey] registered {action:?} = {accel}");
                    registered.push((action, accel));
                }
                Err(e) => {
                    log::warn!("[hotkey] could not register {action:?} = {accel}: {e}");
                    problems.push(format!("{accel}: {e}"));
                }
            }
        }
        Ok(problems)
    }

    /// The app's own start/stop/cancel (buttons, the bar, keys inside the
    /// window), run through the same worker so every recording change is in
    /// one order.
    #[tauri::command]
    pub fn hotkey_trigger(app: AppHandle, action: Action, purpose: Option<String>) -> Result<(), String> {
        app.state::<NativeHotkeys>().send(Op::Key {
            action,
            state: KeyState::Pressed,
            source: Source::App,
            purpose,
            at_ms: now_ms(),
        })
    }

    /// The webview's privacy decision refused a recording the native gate
    /// let start: stop it and delete its audio.
    #[tauri::command]
    pub fn hotkey_discard(app: AppHandle, recording_id: String) -> Result<(), String> {
        app.state::<NativeHotkeys>().send(Op::Discard { recording_id })
    }

    /// The webview has the event (`done`: and has handled it).
    #[tauri::command]
    pub fn hotkey_ack(app: AppHandle, seq: u64, done: Option<bool>) {
        let service = app.state::<NativeHotkeys>();
        let ack = {
            let mut delivery = service.delivery.lock().unwrap_or_else(|e| e.into_inner());
            delivery.ack(seq, done.unwrap_or(true), Instant::now())
        };
        if let Some(ack) = ack {
            let ms = ack.latency.as_millis();
            if ack.late {
                log::warn!("[hotkey] event {seq} acknowledged by the webview after {ms} ms (late)");
            } else {
                log::info!("[hotkey] event {seq} acknowledged by the webview after {ms} ms");
            }
            if !ack.still_waiting {
                set_waking(&app, false);
            }
        }
    }

    /// Events not yet acknowledged (the webview asks when it starts or wakes).
    #[tauri::command]
    pub fn hotkey_pending(app: AppHandle) -> Vec<Envelope> {
        app.state::<NativeHotkeys>()
            .delivery
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .unacknowledged()
    }
}

#[cfg(desktop)]
pub use runtime::start;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::context::PasswordCheck;

    fn target(resolved: bool) -> TargetSnapshot {
        let mut t: TargetSnapshot = serde_json::from_value(serde_json::json!({
            "platform": "windows", "resolved": resolved, "isSelf": false,
            "appName": "Notepad", "exeName": "notepad.exe", "exePath": null, "bundleId": null,
            "title": "notes", "windowId": "1", "pid": 7, "samples": 1, "identityMs": 1.0,
            "passwordCheck": "not-password", "passwordCheckMs": 1.0, "error": null
        }))
        .unwrap();
        t.resolved = resolved;
        t
    }

    fn audio(path: &str) -> AudioRecording {
        AudioRecording {
            audio_data: Vec::new(),
            sample_rate: 16000,
            channels: 1,
            duration_seconds: 1.0,
            file_path: Some(path.into()),
        }
    }

    /// A fake world: a recorder, a list of what was published.
    #[derive(Default)]
    struct Fake {
        recording: Option<String>,
        next: u32,
        refuse: bool,
        fail_start: bool,
        forward: bool,
        published: Vec<Event>,
        starts: u32,
        stops: u32,
        cancels: u32,
        tray: Vec<bool>,
    }

    impl Engine for Fake {
        fn capture(&mut self) -> TargetSnapshot {
            target(true)
        }
        fn capture_at_stop(&mut self) -> TargetSnapshot {
            target(true)
        }
        fn refuses(&self, _target: &TargetSnapshot) -> bool {
            self.refuse
        }
        fn start(&mut self) -> Result<StartedRecording, String> {
            if self.fail_start {
                return Err("no microphone".into());
            }
            if self.recording.is_some() {
                panic!("started while recording");
            }
            self.next += 1;
            self.starts += 1;
            let id = format!("rec{}", self.next);
            self.recording = Some(id.clone());
            Ok(StartedRecording {
                recording_id: id.clone(),
                device: DeviceOutcome { outcome: "success", reason: None, device_id: "mic".into() },
            })
        }
        fn stop(&mut self) -> Result<AudioRecording, String> {
            let id = self.recording.take().expect("stop while idle");
            self.stops += 1;
            Ok(audio(&format!("{id}.wav")))
        }
        fn cancel(&mut self) -> Result<(), String> {
            self.recording.take().expect("cancel while idle");
            self.cancels += 1;
            Ok(())
        }
        fn recording_id(&self) -> Option<String> {
            self.recording.clone()
        }
        fn records(&self) -> bool {
            !self.forward
        }
        fn publish(&mut self, event: Event, _at_ms: u64) {
            self.published.push(event);
        }
        fn set_recording(&mut self, recording: bool) {
            self.tray.push(recording);
        }
    }

    fn key(action: Action, state: KeyState) -> Op {
        Op::Key { action, state, source: Source::Hotkey, purpose: None, at_ms: 1 }
    }
    fn press(action: Action) -> Op {
        key(action, KeyState::Pressed)
    }

    fn kinds(fake: &Fake) -> Vec<&'static str> {
        fake.published
            .iter()
            .map(|e| match e {
                Event::Started { .. } => "started",
                Event::Stopped { .. } => "stopped",
                Event::Cancelled { .. } => "cancelled",
                Event::Refused { .. } => "refused",
                Event::Failed { .. } => "failed",
                Event::Key { .. } => "key",
            })
            .collect()
    }

    #[test]
    fn toggle_starts_then_stops() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        assert_eq!(m.handle(&mut f, press(Action::Toggle)), Plan::Start { ptt: false });
        assert_eq!(f.recording.as_deref(), Some("rec1"));
        assert_eq!(m.handle(&mut f, press(Action::Toggle)), Plan::Stop);
        assert_eq!(kinds(&f), ["started", "stopped"]);
        assert!(f.recording.is_none());
        assert_eq!(f.tray, [true, false]);
        match &f.published[1] {
            Event::Stopped { recording_id, recording, target, .. } => {
                assert_eq!(recording_id, "rec1");
                assert_eq!(recording.file_path.as_deref(), Some("rec1.wav"));
                // 0.9.11: the window in front at Stop travels with it.
                assert!(target.as_ref().is_some_and(|t| t.resolved));
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn toggle_released_is_ignored() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(m.handle(&mut f, key(Action::Toggle, KeyState::Released)), Plan::Ignore("key released"));
        assert_eq!(kinds(&f), ["started"]);
        assert!(f.recording.is_some());
    }

    #[test]
    fn push_to_talk_records_while_held() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        assert_eq!(m.handle(&mut f, press(Action::PushToTalk)), Plan::Start { ptt: true });
        // Key repeat or a second press while held: no second recording.
        assert_eq!(m.handle(&mut f, press(Action::PushToTalk)), Plan::Ignore("already recording"));
        assert_eq!(m.handle(&mut f, key(Action::PushToTalk, KeyState::Released)), Plan::Stop);
        assert_eq!(kinds(&f), ["started", "stopped"]);
        assert_eq!(f.starts, 1);
    }

    #[test]
    fn push_to_talk_release_before_any_press_does_nothing() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        assert_eq!(
            m.handle(&mut f, key(Action::PushToTalk, KeyState::Released)),
            Plan::Ignore("not recording")
        );
        assert!(f.published.is_empty());
    }

    #[test]
    fn push_to_talk_release_does_not_stop_a_toggle_recording() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        m.handle(&mut f, press(Action::PushToTalk));
        assert!(matches!(
            m.handle(&mut f, key(Action::PushToTalk, KeyState::Released)),
            Plan::Ignore(_)
        ));
        assert!(f.recording.is_some());
    }

    #[test]
    fn push_to_talk_press_and_release_in_quick_succession_are_both_handled() {
        // Both reach the worker before it runs: they are handled in order.
        let (mut m, mut f) = (Machine::default(), Fake::default());
        let ops = [press(Action::PushToTalk), key(Action::PushToTalk, KeyState::Released)];
        for op in ops {
            m.handle(&mut f, op);
        }
        assert_eq!(kinds(&f), ["started", "stopped"]);
        assert!(f.recording.is_none());
    }

    #[test]
    fn cancel_discards_and_cancel_when_idle_does_nothing() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        assert_eq!(m.handle(&mut f, press(Action::Cancel)), Plan::Ignore("nothing to cancel"));
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(m.handle(&mut f, press(Action::Cancel)), Plan::Cancel);
        assert_eq!(kinds(&f), ["started", "cancelled"]);
        assert_eq!((f.cancels, f.stops), (1, 0));
        assert!(f.recording.is_none());
    }

    #[test]
    fn double_press_starts_and_stops_one_recording() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(f.starts, 1);
        assert_eq!(f.stops, 1);
    }

    #[test]
    fn a_new_recording_can_start_while_the_last_one_is_processed() {
        // Stop leaves the machine idle at once; transcription is the webview's.
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(m.handle(&mut f, press(Action::Toggle)), Plan::Start { ptt: false });
        assert_eq!(f.recording.as_deref(), Some("rec2"));
        assert_eq!(kinds(&f), ["started", "stopped", "started"]);
    }

    #[test]
    fn start_and_stop_keys() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        assert_eq!(m.handle(&mut f, press(Action::Stop)), Plan::Ignore("not recording"));
        m.handle(&mut f, press(Action::Start));
        assert_eq!(m.handle(&mut f, press(Action::Start)), Plan::Ignore("already recording"));
        assert_eq!(m.handle(&mut f, press(Action::Stop)), Plan::Stop);
        assert_eq!(kinds(&f), ["started", "stopped"]);
    }

    #[test]
    fn refused_target_never_opens_the_microphone() {
        let (mut m, mut f) = (Machine::default(), Fake { refuse: true, ..Fake::default() });
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(kinds(&f), ["refused"]);
        assert_eq!(f.starts, 0);
        // The push-to-talk release after a refused press does nothing.
        let (mut m, mut f) = (Machine::default(), Fake { refuse: true, ..Fake::default() });
        m.handle(&mut f, press(Action::PushToTalk));
        m.handle(&mut f, key(Action::PushToTalk, KeyState::Released));
        assert_eq!(kinds(&f), ["refused"]);
    }

    #[test]
    fn failed_start_reports_and_stays_idle() {
        let (mut m, mut f) = (Machine::default(), Fake { fail_start: true, ..Fake::default() });
        m.handle(&mut f, press(Action::Toggle));
        assert_eq!(kinds(&f), ["failed"]);
        assert_eq!(m.phase, Some(Phase::Idle));
        assert!(f.tray.is_empty());
    }

    #[test]
    fn discard_cancels_only_the_named_recording() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        assert!(matches!(
            m.handle(&mut f, Op::Discard { recording_id: "other".into() }),
            Plan::Ignore(_)
        ));
        assert_eq!(m.handle(&mut f, Op::Discard { recording_id: "rec1".into() }), Plan::Cancel);
        match f.published.last().unwrap() {
            Event::Cancelled { recording_id, discarded, .. } => {
                assert_eq!(recording_id, "rec1");
                assert!(*discarded);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_recording_started_elsewhere_is_stopped_by_the_hotkey() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        f.recording = Some("fromapp".into());
        assert_eq!(m.handle(&mut f, press(Action::Toggle)), Plan::Stop);
        match &f.published[0] {
            Event::Stopped { recording_id, .. } => assert_eq!(recording_id, "fromapp"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_recording_ended_elsewhere_is_not_stopped_twice() {
        let (mut m, mut f) = (Machine::default(), Fake::default());
        m.handle(&mut f, press(Action::Toggle));
        f.recording = None; // stopped by another path
        assert_eq!(m.handle(&mut f, press(Action::Toggle)), Plan::Start { ptt: false });
        assert_eq!(f.stops, 0);
    }

    #[test]
    fn config_forward_flag_defaults_off() {
        let c: Config = serde_json::from_value(serde_json::json!({ "bindings": [] })).unwrap();
        assert!(!c.forward);
    }

    #[test]
    fn keys_are_forwarded_when_the_rust_recorder_is_not_used() {
        let (mut m, mut f) = (Machine::default(), Fake { forward: true, ..Fake::default() });
        m.handle(&mut f, press(Action::PushToTalk));
        m.handle(&mut f, key(Action::PushToTalk, KeyState::Released));
        assert_eq!(kinds(&f), ["key", "key"]);
        assert_eq!(f.starts, 0);
    }

    #[test]
    fn refusal_rules() {
        let blocked = |exe: Option<&str>, _b: Option<&str>| exe == Some("1password.exe");
        let mut t = target(true);
        assert!(!refused_by(&t, &blocked));
        t.exe_name = Some("1Password.exe".into());
        assert!(refused_by(&t, &blocked), "exe names compare case-insensitively");
        let mut t = target(true);
        t.password_check = PasswordCheck::Password;
        assert!(!refused_by(&t, &blocked), "0.9.11: a password field never refuses a start");
        t.exe_name = Some("1password.exe".into());
        t.is_self = true;
        assert!(!refused_by(&t, &blocked), "VoiceFlow itself is never refused here");
        let mut t = target(false);
        t.password_check = PasswordCheck::Password;
        assert!(!refused_by(&t, &blocked), "unresolved records anyway (0.9.11: start anywhere)");
    }

    #[test]
    fn device_choice() {
        let devices = vec!["A".to_string(), "B".to_string()];
        assert_eq!(choose_device(&devices, Some("B")).unwrap().outcome, "success");
        let d = choose_device(&devices, Some("C")).unwrap();
        assert_eq!((d.outcome, d.reason, d.device_id.as_str()), ("fallback", Some("preferred-device-unavailable"), "A"));
        let d = choose_device(&devices, None).unwrap();
        assert_eq!(d.reason, Some("no-device-selected"));
        assert!(choose_device(&[], Some("A")).is_err());
    }

    #[test]
    fn recording_ids_are_unique_and_file_safe() {
        let a = new_recording_id();
        let b = new_recording_id();
        assert_eq!(a.len(), 21);
        assert_ne!(a, b);
        assert!(a.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-'));
    }

    // --- Delivery: the webview asleep -----------------------------------

    fn started(id: &str) -> Event {
        Event::Started {
            recording_id: id.into(),
            target: Box::new(target(true)),
            device: DeviceOutcome { outcome: "success", reason: None, device_id: "mic".into() },
            ptt: false,
            purpose: None,
            source: Source::Hotkey,
        }
    }
    fn stopped(id: &str) -> Event {
        Event::Stopped {
            recording_id: id.into(),
            recording: audio("x.wav"),
            target: None,
            purpose: None,
            source: Source::Hotkey,
        }
    }

    #[test]
    fn prompt_ack_is_not_late() {
        let t0 = Instant::now();
        let mut d = Delivery::default();
        let (e, _) = d.push(started("r"), 0, t0);
        assert_eq!(e.seq, 1);
        let ack = d.ack(1, true, t0 + Duration::from_millis(30)).unwrap();
        assert!(!ack.late);
        assert!(d.tick(t0 + Duration::from_secs(5)).resend.is_empty());
    }

    #[test]
    fn asleep_webview_gets_retries_then_each_event_once() {
        let t0 = Instant::now();
        let mut d = Delivery::default();
        d.push(started("r"), 0, t0);
        d.push(stopped("r"), 0, t0 + Duration::from_millis(500));

        // Within the budget: nothing yet.
        let tick = d.tick(t0 + Duration::from_millis(900));
        assert!(tick.resend.is_empty() && !tick.waiting);

        // Past it: event 1 is late and re-sent; event 2 not yet.
        let tick = d.tick(t0 + Duration::from_millis(1100));
        assert_eq!(tick.newly_late, [1]);
        assert_eq!(tick.resend.iter().map(|e| e.seq).collect::<Vec<_>>(), [1]);
        assert!(tick.waiting);

        // Asleep for a minute: re-sent about once a second, still in order.
        let mut sends = 0;
        for ms in (1350..60_000).step_by(250) {
            sends += d.tick(t0 + Duration::from_millis(ms)).resend.iter().filter(|e| e.seq == 1).count();
        }
        assert!((55..=60).contains(&sends), "{sends} re-sends in a minute");
        assert_eq!(d.unacknowledged().iter().map(|e| e.seq).collect::<Vec<_>>(), [1, 2]);

        // It wakes: acknowledges both, late; a duplicate ack is ignored.
        let wake = t0 + Duration::from_secs(61);
        let a1 = d.ack(1, true, wake).unwrap();
        assert!(a1.late && a1.still_waiting);
        assert!(d.ack(1, true, wake).is_none(), "a re-sent copy acknowledged twice counts once");
        let a2 = d.ack(2, true, wake).unwrap();
        assert!(a2.late && !a2.still_waiting);
        let tick = d.tick(wake + Duration::from_secs(2));
        assert!(tick.resend.is_empty() && !tick.waiting);
    }

    #[test]
    fn webview_asleep_through_a_whole_dictation_loses_nothing() {
        // The keys work while the webview sleeps: one recording, both events
        // kept for it, nothing started twice by the re-sends.
        let (mut m, mut f) = (Machine::default(), Fake::default());
        let mut d = Delivery::default();
        let t0 = Instant::now();
        m.handle(&mut f, press(Action::Toggle));
        m.handle(&mut f, press(Action::Toggle));
        for (i, e) in f.published.drain(..).enumerate() {
            d.push(e, 0, t0 + Duration::from_millis(i as u64));
        }
        for ms in (250..30_000).step_by(250) {
            d.tick(t0 + Duration::from_millis(ms));
        }
        assert_eq!(f.starts, 1);
        assert_eq!(f.stops, 1);
        let waiting = d.unacknowledged();
        assert_eq!(waiting.len(), 2);
        assert!(matches!(waiting[0].event, Event::Started { .. }));
        assert!(matches!(waiting[1].event, Event::Stopped { .. }));
    }

    #[test]
    fn pending_is_bounded_but_never_drops_a_stopped_recording() {
        let t0 = Instant::now();
        let mut d = Delivery::default();
        let mut dropped = Vec::new();
        dropped.extend(d.push(stopped("keep"), 0, t0).1); // seq 1
        for _ in 0..(MAX_PENDING + 2) {
            dropped.extend(d.push(started("r"), 0, t0).1);
        }
        assert_eq!(dropped, [2, 3, 4], "the oldest non-stop events go first");
        assert_eq!(d.unacknowledged()[0].seq, 1);
        assert_eq!(d.unacknowledged().len(), MAX_PENDING);
        // Only stops waiting: nothing is dropped, the list grows.
        let mut d = Delivery::default();
        for _ in 0..(MAX_PENDING + 2) {
            assert!(d.push(stopped("r"), 0, t0).1.is_empty());
        }
        assert_eq!(d.unacknowledged().len(), MAX_PENDING + 2);
    }

    #[test]
    fn received_events_stop_the_resends_but_stay_until_handled() {
        let t0 = Instant::now();
        let mut d = Delivery::default();
        d.push(stopped("r"), 0, t0);
        let first = d.ack(1, false, t0 + Duration::from_millis(20)).unwrap();
        assert!(!first.late);
        // Handling takes a while (reading the audio): no re-send, not late.
        let tick = d.tick(t0 + Duration::from_secs(5));
        assert!(tick.resend.is_empty() && !tick.waiting && tick.newly_late.is_empty());
        // A page that reloads meanwhile gets it again.
        assert_eq!(d.unacknowledged().len(), 1);
        // Handled: gone, and the second acknowledgement logs nothing new.
        assert!(d.ack(1, true, t0 + Duration::from_secs(6)).is_none());
        assert!(d.unacknowledged().is_empty());
    }

    #[test]
    fn envelope_shape_for_the_webview() {
        let mut d = Delivery::default();
        let (e, _) = d.push(started("abc"), 42, Instant::now());
        let v = serde_json::to_value(&e).unwrap();
        assert_eq!(v["seq"], 1);
        assert_eq!(v["atMs"], 42);
        assert_eq!(v["kind"], "started");
        assert_eq!(v["recordingId"], "abc");
        assert_eq!(v["device"]["deviceId"], "mic");
        assert_eq!(v["target"]["exeName"], "notepad.exe");
        assert_eq!(v["source"], "hotkey");
        let key = Envelope {
            seq: 2,
            at_ms: 0,
            event: Event::Key { action: Action::PushToTalk, state: KeyState::Released, source: Source::Hotkey },
        };
        let v = serde_json::to_value(&key).unwrap();
        assert_eq!((v["kind"].as_str(), v["action"].as_str(), v["state"].as_str()), (Some("key"), Some("pushToTalk"), Some("Released")));
    }

    #[test]
    fn config_from_the_webview() {
        let c: Config = serde_json::from_value(serde_json::json!({
            "bindings": [
                { "action": "toggle", "accelerator": "Control+Space" },
                { "action": "pushToTalk", "accelerator": "Alt+Shift+D" },
                { "action": "cancel", "accelerator": "Control+Shift+'" }
            ],
            "recorder": { "deviceId": null, "outputFolder": "C:\\rec", "sampleRate": 16000 },
            "blockedApps": ["1password.exe"]
        }))
        .unwrap();
        assert_eq!(c.bindings[1].action, Some(Action::PushToTalk));
        assert_eq!(c.recorder.unwrap().sample_rate, Some(16000));
    }
}
