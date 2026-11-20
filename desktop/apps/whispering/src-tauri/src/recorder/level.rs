//! VoiceFlow A2: the microphone level shown on the recording bar.
//!
//! The cpal callback calls [`record`] with each chunk it writes while
//! recording. It only does arithmetic and lock-free atomics, so it never
//! blocks the audio thread. A pump (`bar.rs`) calls [`take`] about 25 times a
//! second and sends the loudest chunk since the last read to the bar.

use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

/// The loudest chunk's RMS since the last [`take`], as `f32` bits.
static PEAK_RMS: AtomicU32 = AtomicU32::new(0);
/// Set when a chunk was recorded since the last [`take`].
static FRESH: AtomicBool = AtomicBool::new(false);

/// Quieter than this (dBFS) shows as silence.
const FLOOR_DB: f32 = -60.0;
/// Louder than this (dBFS) shows as full.
const CEILING_DB: f32 = -12.0;

/// Root mean square of a chunk of samples in -1.0..=1.0. `None` when empty.
pub fn rms<I: IntoIterator<Item = f32>>(samples: I) -> Option<f32> {
    let mut sum = 0.0f64;
    let mut n = 0u32;
    for s in samples {
        sum += f64::from(s) * f64::from(s);
        n += 1;
    }
    if n == 0 {
        return None;
    }
    Some((sum / f64::from(n)).sqrt() as f32)
}

/// Notes one chunk from the audio callback (keeps the loudest).
pub fn record<I: IntoIterator<Item = f32>>(samples: I) {
    let Some(level) = rms(samples) else { return };
    let mut current = PEAK_RMS.load(Ordering::Relaxed);
    while f32::from_bits(current) < level {
        match PEAK_RMS.compare_exchange_weak(
            current,
            level.to_bits(),
            Ordering::Relaxed,
            Ordering::Relaxed,
        ) {
            Ok(_) => break,
            Err(seen) => current = seen,
        }
    }
    FRESH.store(true, Ordering::Release);
}

/// The loudest RMS since the last call, or `None` when nothing was recorded
/// (not recording, or the device stopped delivering audio).
pub fn take() -> Option<f32> {
    if !FRESH.swap(false, Ordering::Acquire) {
        return None;
    }
    Some(f32::from_bits(PEAK_RMS.swap(0, Ordering::Relaxed)))
}

/// Maps an RMS value to 0.0..=1.0 on a decibel scale, for drawing.
pub fn to_display(rms: f32) -> f32 {
    if !rms.is_finite() || rms <= 0.0 {
        return 0.0;
    }
    let db = 20.0 * rms.log10();
    ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).clamp(0.0, 1.0)
}

/// i16 sample to -1.0..=1.0.
pub fn from_i16(s: i16) -> f32 {
    f32::from(s) / 32768.0
}

/// u16 sample (offset binary) to -1.0..=1.0.
pub fn from_u16(s: u16) -> f32 {
    (f32::from(s) - 32768.0) / 32768.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rms_of_a_full_scale_square_wave_is_one() {
        let r = rms([1.0, -1.0, 1.0, -1.0]).unwrap();
        assert!((r - 1.0).abs() < 1e-6);
        assert_eq!(rms(std::iter::empty()), None);
    }

    #[test]
    fn display_scale_is_clamped_and_monotonic() {
        assert_eq!(to_display(0.0), 0.0);
        assert_eq!(to_display(f32::NAN), 0.0);
        assert_eq!(to_display(1.0), 1.0);
        assert_eq!(to_display(0.0005), 0.0); // about -66 dBFS
        let quiet = to_display(0.01); // -40 dBFS
        let loud = to_display(0.1); // -20 dBFS
        assert!(quiet > 0.0 && quiet < loud && loud < 1.0);
    }

    #[test]
    fn sample_conversions_cover_the_range() {
        assert_eq!(from_i16(0), 0.0);
        assert_eq!(from_i16(i16::MIN), -1.0);
        assert_eq!(from_u16(32768), 0.0);
        assert_eq!(from_u16(0), -1.0);
    }

    #[test]
    fn take_returns_the_loudest_chunk_once() {
        // One test owns the shared statics (tests in this module run in
        // parallel, so the record/take sequence stays in one place).
        let _ = take();
        record([0.1, -0.1]);
        record([0.5, -0.5]);
        record([0.2, -0.2]);
        let got = take().unwrap();
        assert!((got - 0.5).abs() < 1e-6);
        assert_eq!(take(), None);
    }
}
