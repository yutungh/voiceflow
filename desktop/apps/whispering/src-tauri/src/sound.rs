//! VoiceFlow: the app's sounds, played natively.
//!
//! Three sets of five cues (desktop/tools/sounds/generate.mjs; original,
//! synthesised in code, CC0 1.0), embedded from the same WAV files the
//! webview bundles. They play here, not in the webview, so:
//! - a dictation recorded by Rust (hotkey.rs) gets its start, stop and cancel
//!   cue the moment the microphone opens or closes, even while the webview is
//!   busy or asleep (`on_event`);
//! - the webview's own cues (done, error, previews) go through the same
//!   player and volume (`sound_play`).
//!
//! One thread owns the output stream (cpal streams are not `Send` on every
//! platform). The stream is opened on demand, or ahead of time when a
//! recording is about to start (`prewarm`), and closed after a few idle
//! seconds so the audio device is not held open.

use serde::Deserialize;
use std::collections::HashMap;
use std::io::Cursor;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// The WAVs are 48 kHz mono 16-bit.
pub const CLIP_RATE: u32 = 48_000;
/// The output stream closes after this long with nothing to play.
const IDLE_CLOSE: Duration = Duration::from_secs(5);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SoundSet {
    Glass,
    Soft,
    Minimal,
}

/// The setting: a set, or no sounds.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SetChoice {
    Glass,
    Soft,
    Minimal,
    Off,
}

impl SetChoice {
    fn set(self) -> Option<SoundSet> {
        match self {
            SetChoice::Glass => Some(SoundSet::Glass),
            SetChoice::Soft => Some(SoundSet::Soft),
            SetChoice::Minimal => Some(SoundSet::Minimal),
            SetChoice::Off => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Cue {
    Start,
    Stop,
    Done,
    Error,
    Cancel,
}

/// What the webview tells Rust (`nativeSoundConfig` in the webview).
#[derive(Clone, Copy, Debug, PartialEq, Deserialize)]
pub struct Config {
    pub set: SetChoice,
    pub volume: f32,
    pub start: bool,
    pub stop: bool,
    pub cancel: bool,
}

macro_rules! clip {
    ($set:literal, $cue:literal) => {
        include_bytes!(concat!(
            "../../src/lib/services/isomorphic/sound/assets/",
            $set,
            "/",
            $cue,
            ".wav"
        ))
    };
}

fn wav_bytes(set: SoundSet, cue: Cue) -> &'static [u8] {
    use Cue::*;
    use SoundSet::*;
    match (set, cue) {
        (Glass, Start) => clip!("glass", "start"),
        (Glass, Stop) => clip!("glass", "stop"),
        (Glass, Done) => clip!("glass", "done"),
        (Glass, Error) => clip!("glass", "error"),
        (Glass, Cancel) => clip!("glass", "cancel"),
        (Soft, Start) => clip!("soft", "start"),
        (Soft, Stop) => clip!("soft", "stop"),
        (Soft, Done) => clip!("soft", "done"),
        (Soft, Error) => clip!("soft", "error"),
        (Soft, Cancel) => clip!("soft", "cancel"),
        (Minimal, Start) => clip!("minimal", "start"),
        (Minimal, Stop) => clip!("minimal", "stop"),
        (Minimal, Done) => clip!("minimal", "done"),
        (Minimal, Error) => clip!("minimal", "error"),
        (Minimal, Cancel) => clip!("minimal", "cancel"),
    }
}

/// One clip as f32 samples at `CLIP_RATE`.
pub fn decode(set: SoundSet, cue: Cue) -> Result<Vec<f32>, String> {
    let mut reader =
        hound::WavReader::new(Cursor::new(wav_bytes(set, cue))).map_err(|e| e.to_string())?;
    let spec = reader.spec();
    if spec.channels != 1 || spec.sample_rate != CLIP_RATE || spec.bits_per_sample != 16 {
        return Err(format!("unexpected clip format {spec:?}"));
    }
    reader
        .samples::<i16>()
        .map(|s| s.map(|v| v as f32 / 32768.0).map_err(|e| e.to_string()))
        .collect()
}

/// Amplitude for a 0..1 volume setting (squared: closer to how loud it sounds).
pub fn gain_of(volume: f32) -> f32 {
    let v = if volume.is_finite() { volume.clamp(0.0, 1.0) } else { 0.0 };
    v * v
}

/// What a dictation recorded by Rust plays for `cue` under `config`
/// (None before the webview has configured anything: play nothing).
pub fn native_cue(config: Option<&Config>, cue: Cue) -> Option<(SoundSet, f32)> {
    let config = config?;
    let set = config.set.set()?;
    let on = match cue {
        Cue::Start => config.start,
        Cue::Stop => config.stop,
        Cue::Cancel => config.cancel,
        Cue::Done | Cue::Error => false,
    };
    let gain = gain_of(config.volume);
    (on && gain > 0.0).then_some((set, gain))
}

/// Windowed-sinc resampling (Blackman window, 16 zero crossings each side).
/// Only for these short clips, once per output rate.
pub fn resample(input: &[f32], from: u32, to: u32) -> Vec<f32> {
    if from == to || input.is_empty() {
        return input.to_vec();
    }
    let ratio = to as f64 / from as f64;
    let cutoff = ratio.min(1.0) * 0.98; // of the input Nyquist
    const HALF: f64 = 16.0;
    let reach = HALF / cutoff;
    let out_len = (input.len() as f64 * ratio).round() as usize;
    let mut out = Vec::with_capacity(out_len);
    for n in 0..out_len {
        let center = n as f64 / ratio;
        let lo = (center - reach).floor().max(0.0) as usize;
        let hi = ((center + reach).ceil() as usize).min(input.len() - 1);
        let mut acc = 0.0f64;
        for (k, sample) in input.iter().enumerate().take(hi + 1).skip(lo) {
            let x = (k as f64 - center) * cutoff;
            if x.abs() >= HALF {
                continue;
            }
            let sinc = if x == 0.0 {
                1.0
            } else {
                (std::f64::consts::PI * x).sin() / (std::f64::consts::PI * x)
            };
            let w = 0.42 + 0.5 * (std::f64::consts::PI * x / HALF).cos()
                + 0.08 * (2.0 * std::f64::consts::PI * x / HALF).cos();
            acc += *sample as f64 * sinc * w * cutoff;
        }
        out.push(acc as f32);
    }
    out
}

/// A clip being played.
pub struct Voice {
    pub samples: Arc<[f32]>,
    pub pos: usize,
    pub gain: f32,
}

/// Mixes the playing voices straight into the device buffer (interleaved,
/// `channels` wide; silence when nothing plays) and drops the voices that
/// have finished. Allocates nothing: it runs in the audio callback.
pub fn mix<T: cpal::Sample + cpal::FromSample<f32>>(
    voices: &mut Vec<Voice>,
    data: &mut [T],
    channels: usize,
) {
    let channels = channels.max(1);
    for frame in data.chunks_mut(channels) {
        let mut v = 0.0f32;
        for voice in voices.iter_mut() {
            if let Some(s) = voice.samples.get(voice.pos) {
                v += s * voice.gain;
                voice.pos += 1;
            }
        }
        let out = T::from_sample(v.clamp(-1.0, 1.0));
        frame.iter_mut().for_each(|s| *s = out);
    }
    voices.retain(|voice| voice.pos < voice.samples.len());
}

// ---------------------------------------------------------------------------
// The player thread
// ---------------------------------------------------------------------------

enum Msg {
    Prewarm,
    Play {
        set: SoundSet,
        cue: Cue,
        gain: f32,
        /// When the key was pressed, for the log (native cues only).
        key_at_ms: Option<u64>,
        /// Told whether the sound reached the device (`sound_play`).
        reply: Option<tokio::sync::oneshot::Sender<Result<(), String>>>,
    },
}

fn config_slot() -> &'static Mutex<Option<Config>> {
    static CONFIG: OnceLock<Mutex<Option<Config>>> = OnceLock::new();
    CONFIG.get_or_init(|| Mutex::new(None))
}

fn current_config() -> Option<Config> {
    *config_slot().lock().unwrap_or_else(|e| e.into_inner())
}

fn send(msg: Msg) -> Result<(), String> {
    static TX: OnceLock<Mutex<mpsc::Sender<Msg>>> = OnceLock::new();
    let tx = TX.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        let spawned = std::thread::Builder::new()
            .name("voiceflow-sounds".into())
            .spawn(move || output::run(rx));
        if let Err(e) = spawned {
            log::error!("[sound] the sound thread did not start: {e}");
        }
        Mutex::new(tx)
    });
    tx.lock()
        .unwrap_or_else(|e| e.into_inner())
        .send(msg)
        .map_err(|_| "the sound thread stopped".to_string())
}

/// A recording is about to start: open the output now, so the start cue is
/// not held up by opening it after the microphone. Nothing when the start
/// cue is off.
pub fn prewarm() {
    if native_cue(current_config().as_ref(), Cue::Start).is_some() {
        let _ = send(Msg::Prewarm);
    }
}

/// A dictation recorded by Rust changed state: play its cue, if it has one.
pub fn on_native(cue: Cue, key_at_ms: u64) {
    if let Some((set, gain)) = native_cue(current_config().as_ref(), cue) {
        if let Err(e) = send(Msg::Play { set, cue, gain, key_at_ms: Some(key_at_ms), reply: None }) {
            log::warn!("[sound] {cue:?} not played: {e}");
        }
    }
}

#[tauri::command]
pub fn sound_configure(config: Config) {
    log::info!("[sound] configured: {config:?}");
    *config_slot().lock().unwrap_or_else(|e| e.into_inner()) = Some(config);
}

/// The webview's cues (done, error, the webview's own recordings) and
/// Settings' previews. The webview has already checked the toggles. An error
/// (no output device, the stream failed) lets the webview play it instead.
#[tauri::command]
pub async fn sound_play(set: SoundSet, cue: Cue, volume: f32) -> Result<(), String> {
    let gain = gain_of(volume);
    if gain == 0.0 {
        return Ok(());
    }
    let (tx, rx) = tokio::sync::oneshot::channel();
    send(Msg::Play { set, cue, gain, key_at_ms: None, reply: Some(tx) })?;
    match tokio::time::timeout(Duration::from_secs(2), rx).await {
        Ok(Ok(result)) => result,
        Ok(Err(_)) => Err("the sound thread stopped".into()),
        // Still opening the device: it will play; a fallback would double it.
        Err(_) => Ok(()),
    }
}

mod output {
    use super::*;
    use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
    use cpal::{FromSample, SampleFormat, SizedSample};

    struct Output {
        _stream: cpal::Stream,
        voices: Arc<Mutex<Vec<Voice>>>,
        rate: u32,
        /// Set by the stream's error callback (device unplugged, and so on):
        /// the stream is rebuilt before the next sound.
        failed: Arc<AtomicBool>,
    }

    impl Output {
        fn open() -> Result<Output, String> {
            let device = cpal::default_host()
                .default_output_device()
                .ok_or("no audio output device")?;
            let supported = device.default_output_config().map_err(|e| e.to_string())?;
            let format = supported.sample_format();
            let config: cpal::StreamConfig = supported.config();
            let channels = config.channels as usize;
            let voices: Arc<Mutex<Vec<Voice>>> = Arc::new(Mutex::new(Vec::new()));
            let failed = Arc::new(AtomicBool::new(false));
            let (v, f) = (voices.clone(), failed.clone());
            let stream = match format {
                SampleFormat::F32 => build::<f32>(&device, &config, channels, v, f),
                SampleFormat::I16 => build::<i16>(&device, &config, channels, v, f),
                SampleFormat::U16 => build::<u16>(&device, &config, channels, v, f),
                SampleFormat::I32 => build::<i32>(&device, &config, channels, v, f),
                other => return Err(format!("unsupported output format {other:?}")),
            }
            .map_err(|e| e.to_string())?;
            stream.play().map_err(|e| e.to_string())?;
            Ok(Output { _stream: stream, voices, rate: config.sample_rate.0, failed })
        }

        fn idle(&self) -> bool {
            self.voices.lock().map(|v| v.is_empty()).unwrap_or(true)
        }

        fn failed(&self) -> bool {
            self.failed.load(Ordering::Relaxed)
        }
    }

    fn build<T: SizedSample + FromSample<f32>>(
        device: &cpal::Device,
        config: &cpal::StreamConfig,
        channels: usize,
        voices: Arc<Mutex<Vec<Voice>>>,
        failed: Arc<AtomicBool>,
    ) -> Result<cpal::Stream, cpal::BuildStreamError> {
        device.build_output_stream(
            config,
            move |data: &mut [T], _| {
                // Never wait in the audio callback: a busy lock is a silent buffer.
                match voices.try_lock() {
                    Ok(mut playing) => mix(&mut playing, data, channels),
                    Err(_) => data.iter_mut().for_each(|s| *s = T::EQUILIBRIUM),
                }
            },
            move |e| {
                log::warn!("[sound] output stream error: {e}");
                failed.store(true, Ordering::Relaxed);
            },
            None,
        )
    }

    pub(super) fn run(rx: mpsc::Receiver<Msg>) {
        let mut output: Option<Output> = None;
        let mut clips: HashMap<(SoundSet, Cue, u32), Arc<[f32]>> = HashMap::new();
        loop {
            let mut msg = if let Some(out) = &output {
                match rx.recv_timeout(IDLE_CLOSE) {
                    Ok(msg) => msg,
                    Err(RecvTimeoutError::Timeout) => {
                        if out.idle() || out.failed() {
                            output = None; // closes the device
                        }
                        continue;
                    }
                    Err(RecvTimeoutError::Disconnected) => break,
                }
            } else {
                match rx.recv() {
                    Ok(msg) => msg,
                    Err(_) => break,
                }
            };
            let reply = match &mut msg {
                Msg::Play { reply, .. } => reply.take(),
                Msg::Prewarm => None,
            };
            let answer = |r: Result<(), String>| {
                if let Some(tx) = reply {
                    let _ = tx.send(r);
                }
            };
            if output.as_ref().is_some_and(|o| o.failed()) {
                log::info!("[sound] the output stream failed; opening it again");
                output = None;
            }
            if output.is_none() {
                let opened = std::time::Instant::now();
                match Output::open() {
                    Ok(out) => {
                        log::info!(
                            "[sound] output open at {} Hz in {} ms",
                            out.rate,
                            opened.elapsed().as_millis()
                        );
                        output = Some(out);
                    }
                    Err(e) => {
                        log::warn!("[sound] no sound: {e}");
                        answer(Err(e));
                        continue;
                    }
                }
            }
            let Some(out) = &output else { continue };
            let Msg::Play { set, cue, gain, key_at_ms, .. } = msg else { continue };
            let samples = match clips.get(&(set, cue, out.rate)) {
                Some(s) => s.clone(),
                None => match decode(set, cue) {
                    Ok(raw) => {
                        let s: Arc<[f32]> = resample(&raw, CLIP_RATE, out.rate).into();
                        clips.insert((set, cue, out.rate), s.clone());
                        s
                    }
                    Err(e) => {
                        log::warn!("[sound] {set:?}/{cue:?} could not be read: {e}");
                        answer(Err(e));
                        continue;
                    }
                },
            };
            out.voices
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(Voice { samples, pos: 0, gain });
            answer(Ok(()));
            if let Some(at) = key_at_ms {
                log::info!(
                    "[sound] {cue:?} queued {} ms after the key",
                    crate::hotkey::now_ms().saturating_sub(at)
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL_SETS: [SoundSet; 3] = [SoundSet::Glass, SoundSet::Soft, SoundSet::Minimal];
    const ALL_CUES: [Cue; 5] = [Cue::Start, Cue::Stop, Cue::Done, Cue::Error, Cue::Cancel];

    fn config(set: SetChoice) -> Config {
        Config { set, volume: 0.8, start: true, stop: true, cancel: true }
    }

    #[test]
    fn every_clip_decodes_short_and_quiet_at_the_ends() {
        for set in ALL_SETS {
            for cue in ALL_CUES {
                let s = decode(set, cue).unwrap();
                let ms = s.len() as u32 * 1000 / CLIP_RATE;
                assert!((80..=300).contains(&ms), "{set:?}/{cue:?} is {ms} ms");
                assert_eq!(s[0], 0.0, "{set:?}/{cue:?} starts with a click");
                assert_eq!(*s.last().unwrap(), 0.0, "{set:?}/{cue:?} ends with a click");
                let peak = s.iter().fold(0.0f32, |p, v| p.max(v.abs()));
                assert!(peak <= 0.7080, "{set:?}/{cue:?} peaks above -3 dBFS ({peak})");
            }
        }
    }

    #[test]
    fn nothing_plays_before_the_webview_configures() {
        assert_eq!(native_cue(None, Cue::Start), None);
    }

    #[test]
    fn native_cues_follow_the_set_and_toggles() {
        let c = config(SetChoice::Soft);
        let (set, gain) = native_cue(Some(&c), Cue::Start).unwrap();
        assert_eq!(set, SoundSet::Soft);
        assert!((gain - 0.64).abs() < 1e-6);
        assert_eq!(native_cue(Some(&config(SetChoice::Off)), Cue::Start), None);
        let no_stop = Config { stop: false, ..c };
        assert_eq!(native_cue(Some(&no_stop), Cue::Stop), None);
        assert!(native_cue(Some(&no_stop), Cue::Cancel).is_some());
        // Done and error are the webview's to play.
        assert_eq!(native_cue(Some(&c), Cue::Done), None);
        let silent = Config { volume: 0.0, ..c };
        assert_eq!(native_cue(Some(&silent), Cue::Start), None);
    }

    #[test]
    fn config_reads_the_webview_shape() {
        let c: Config = serde_json::from_value(serde_json::json!({
            "set": "minimal", "volume": 0.5, "start": true, "stop": false, "cancel": true
        }))
        .unwrap();
        assert_eq!(c.set, SetChoice::Minimal);
        assert!(!c.stop);
    }

    #[test]
    fn gain_is_clamped_and_squared() {
        assert_eq!(gain_of(1.0), 1.0);
        assert_eq!(gain_of(0.5), 0.25);
        assert_eq!(gain_of(3.0), 1.0);
        assert_eq!(gain_of(-1.0), 0.0);
        assert_eq!(gain_of(f32::NAN), 0.0);
    }

    #[test]
    fn resampling_keeps_pitch_and_level() {
        let tone: Vec<f32> = (0..4800)
            .map(|i| (2.0 * std::f32::consts::PI * 1000.0 * i as f32 / 48_000.0).sin() * 0.5)
            .collect();
        let out = resample(&tone, 48_000, 44_100);
        assert_eq!(out.len(), 4410);
        // Away from the edges, it is the same 1 kHz tone at 44.1 kHz.
        let mut err = 0.0f32;
        for (n, v) in out.iter().enumerate().skip(200).take(4000) {
            let want = (2.0 * std::f32::consts::PI * 1000.0 * n as f32 / 44_100.0).sin() * 0.5;
            err = err.max((v - want).abs());
        }
        assert!(err < 0.01, "max error {err}");
        assert_eq!(resample(&tone, 48_000, 48_000), tone);
    }

    #[test]
    fn mixing_sums_voices_and_drops_finished_ones() {
        let mut voices = vec![
            Voice { samples: Arc::from(vec![0.5f32, 0.5]), pos: 0, gain: 1.0 },
            Voice { samples: Arc::from(vec![0.25f32; 4]), pos: 0, gain: 0.5 },
        ];
        let mut frames = vec![9.0f32; 6]; // 3 stereo frames
        mix(&mut voices, &mut frames[..], 2);
        assert_eq!(frames, vec![0.625, 0.625, 0.625, 0.625, 0.125, 0.125]);
        assert_eq!(voices.len(), 1);
        let mut more = vec![9.0f32; 4];
        mix(&mut voices, &mut more[..], 2);
        assert_eq!(more, vec![0.125, 0.125, 0.0, 0.0]);
        assert!(voices.is_empty());
    }

    #[test]
    fn loud_sums_are_clipped() {
        let mut voices = vec![
            Voice { samples: Arc::from(vec![0.9f32]), pos: 0, gain: 1.0 },
            Voice { samples: Arc::from(vec![0.9f32]), pos: 0, gain: 1.0 },
        ];
        let mut frames = vec![0.0f32; 1];
        mix(&mut voices, &mut frames[..], 1);
        assert_eq!(frames, vec![1.0]);
    }
}
