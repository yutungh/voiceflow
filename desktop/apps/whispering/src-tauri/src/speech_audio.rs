//! VoiceFlow (A0): speech audio that stays small.
//!
//! Microphones on Windows usually capture at the device's mix format
//! (often 48 kHz, stereo, 32-bit float: 384 KB/s). Speech-to-text needs
//! 16 kHz mono, so after a recording stops it is rewritten as 16 kHz mono
//! 16-bit WAV (32 KB/s), and each upload is encoded as lossless FLAC in pure
//! Rust. FFmpeg is not needed for either step.
//!
//! A very long recording whose FLAC would still pass the upload limit is
//! split into parts of at most ten minutes, each cut at the quietest moment
//! of the minute before its boundary, so the parts can be transcribed in
//! order and their text joined.

use std::io::{Cursor, Read};
use std::ops::Range;
use std::path::Path;

use rubato::{FftFixedIn, Resampler};

/// Sample rate of every recording and upload.
pub const SPEECH_SAMPLE_RATE: u32 = 16_000;
/// Upload size we never pass (OpenAI and Groq reject files over 25 MB).
pub const MAX_UPLOAD_BYTES: usize = 24_000_000;
/// Longest part when a recording has to be split (10 minutes). At 16 kHz
/// mono 16-bit that is 19.2 MB even uncompressed, so a FLAC part is always
/// under [`MAX_UPLOAD_BYTES`].
pub const MAX_PART_SAMPLES: usize = 600 * SPEECH_SAMPLE_RATE as usize;
/// How far back from a part's boundary to look for a quiet place to cut.
pub const CUT_SEARCH_SAMPLES: usize = 60 * SPEECH_SAMPLE_RATE as usize;
/// Window used to measure loudness when choosing a cut (50 ms).
pub const CUT_WINDOW_SAMPLES: usize = SPEECH_SAMPLE_RATE as usize / 20;

/// Input frames handed to the resampler at a time.
const RESAMPLE_CHUNK: usize = 1024;

/// Average interleaved frames into one channel.
pub fn downmix_to_mono(interleaved: &[f32], channels: usize) -> Vec<f32> {
    if channels <= 1 {
        return interleaved.to_vec();
    }
    interleaved
        .chunks_exact(channels)
        .map(|frame| frame.iter().sum::<f32>() / channels as f32)
        .collect()
}

/// Convert float samples to 16-bit, clipping anything outside [-1, 1).
/// Uses the same 32768 scale as reading 16-bit WAV, so 16-bit audio
/// round-trips exactly.
pub fn f32_to_i16(samples: &[f32]) -> Vec<i16> {
    samples
        .iter()
        .map(|&s| (s * 32768.0).round().clamp(-32768.0, 32767.0) as i16)
        .collect()
}

/// Number of output samples a resample from `from_rate` to `to_rate` yields.
pub fn resampled_len(input_len: usize, from_rate: u32, to_rate: u32) -> usize {
    ((input_len as u128 * to_rate as u128 + from_rate as u128 / 2) / from_rate as u128) as usize
}

/// Streaming mono resampler to [`SPEECH_SAMPLE_RATE`], so a long recording
/// never has to sit in memory at its capture rate.
struct SpeechResampler {
    inner: Option<FftFixedIn<f32>>,
    from_rate: u32,
    pending: Vec<f32>,
    output: Vec<f32>,
    input_len: usize,
}

impl SpeechResampler {
    fn new(from_rate: u32) -> Result<Self, String> {
        if from_rate == 0 {
            return Err("The recording has no sample rate".into());
        }
        let inner = if from_rate == SPEECH_SAMPLE_RATE {
            None
        } else {
            Some(
                FftFixedIn::<f32>::new(
                    from_rate as usize,
                    SPEECH_SAMPLE_RATE as usize,
                    RESAMPLE_CHUNK,
                    2,
                    1,
                )
                .map_err(|e| format!("Couldn't set up resampling: {e}"))?,
            )
        };
        Ok(Self {
            inner,
            from_rate,
            pending: Vec::new(),
            output: Vec::new(),
            input_len: 0,
        })
    }

    fn push(&mut self, mono: &[f32]) -> Result<(), String> {
        self.input_len += mono.len();
        let Some(resampler) = self.inner.as_mut() else {
            self.output.extend_from_slice(mono);
            return Ok(());
        };
        self.pending.extend_from_slice(mono);
        let mut used = 0;
        while self.pending.len() - used >= RESAMPLE_CHUNK {
            let chunk = &self.pending[used..used + RESAMPLE_CHUNK];
            let out = resampler
                .process(&[chunk], None)
                .map_err(|e| format!("Resampling failed: {e}"))?;
            self.output.extend_from_slice(&out[0]);
            used += RESAMPLE_CHUNK;
        }
        self.pending.drain(..used);
        Ok(())
    }

    fn finish(mut self) -> Result<Vec<f32>, String> {
        let Some(mut resampler) = self.inner.take() else {
            return Ok(self.output);
        };
        let expected = resampled_len(self.input_len, self.from_rate, SPEECH_SAMPLE_RATE);
        let delay = resampler.output_delay();
        if !self.pending.is_empty() {
            let out = resampler
                .process_partial(Some(&[self.pending.as_slice()]), None)
                .map_err(|e| format!("Resampling failed: {e}"))?;
            self.output.extend_from_slice(&out[0]);
        }
        // Push silence through until the filter delay has drained.
        while self.output.len() < delay + expected {
            let out = resampler
                .process_partial::<&[f32]>(None, None)
                .map_err(|e| format!("Resampling failed: {e}"))?;
            if out[0].is_empty() {
                break;
            }
            self.output.extend_from_slice(&out[0]);
        }
        let end = (delay + expected).min(self.output.len());
        Ok(self.output[delay.min(end)..end].to_vec())
    }
}

/// Read any PCM or float WAV and return it as 16 kHz mono 16-bit samples.
pub fn read_wav_as_speech<R: Read>(reader: hound::WavReader<R>) -> Result<Vec<i16>, String> {
    let spec = reader.spec();
    let channels = spec.channels as usize;
    if channels == 0 {
        return Err("The recording has no channels".into());
    }
    let mut resampler = SpeechResampler::new(spec.sample_rate)?;
    let frame_block = RESAMPLE_CHUNK * 8 * channels;
    let mut block: Vec<f32> = Vec::with_capacity(frame_block);

    let mut feed = |sample: f32, block: &mut Vec<f32>| -> Result<(), String> {
        block.push(sample);
        if block.len() >= frame_block {
            resampler.push(&downmix_to_mono(block, channels))?;
            block.clear();
        }
        Ok(())
    };

    let read_err = |e: hound::Error| format!("Couldn't read the recording: {e}");
    match (spec.sample_format, spec.bits_per_sample) {
        (hound::SampleFormat::Float, 32) => {
            for s in reader.into_samples::<f32>() {
                feed(s.map_err(read_err)?, &mut block)?;
            }
        }
        (hound::SampleFormat::Int, bits @ 1..=32) => {
            let scale = (1u64 << (bits - 1)) as f32;
            for s in reader.into_samples::<i32>() {
                feed(s.map_err(read_err)? as f32 / scale, &mut block)?;
            }
        }
        (format, bits) => {
            return Err(format!("Unsupported WAV format: {format:?} {bits}-bit"));
        }
    }
    // A trailing partial frame (a truncated file) is dropped by the downmix.
    if !block.is_empty() {
        resampler.push(&downmix_to_mono(&block, channels))?;
    }
    Ok(f32_to_i16(&resampler.finish()?))
}

/// Encode 16 kHz mono 16-bit samples as a WAV file.
pub fn encode_wav(samples: &[i16]) -> Result<Vec<u8>, String> {
    let mut cursor = Cursor::new(Vec::with_capacity(44 + samples.len() * 2));
    {
        let mut writer = hound::WavWriter::new(&mut cursor, speech_wav_spec())
            .map_err(|e| format!("Couldn't write WAV: {e}"))?;
        let mut w = writer.get_i16_writer(samples.len() as u32);
        for &s in samples {
            w.write_sample(s);
        }
        w.flush().map_err(|e| format!("Couldn't write WAV: {e}"))?;
        writer
            .finalize()
            .map_err(|e| format!("Couldn't write WAV: {e}"))?;
    }
    Ok(cursor.into_inner())
}

fn speech_wav_spec() -> hound::WavSpec {
    hound::WavSpec {
        channels: 1,
        sample_rate: SPEECH_SAMPLE_RATE,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    }
}

/// Encode 16 kHz mono 16-bit samples as lossless FLAC.
pub fn encode_flac(samples: &[i16]) -> Result<Vec<u8>, String> {
    use flacenc::component::BitRepr;
    use flacenc::error::Verify;

    if samples.is_empty() {
        return Err("The recording is empty".into());
    }
    let config = flacenc::config::Encoder::default()
        .into_verified()
        .map_err(|(_, e)| format!("FLAC settings are invalid: {e:?}"))?;
    let widened: Vec<i32> = samples.iter().map(|&s| s as i32).collect();
    let source =
        flacenc::source::MemSource::from_samples(&widened, 1, 16, SPEECH_SAMPLE_RATE as usize);
    let stream = flacenc::encode_with_fixed_block_size(&config, source, config.block_size)
        .map_err(|e| format!("FLAC encoding failed: {e:?}"))?;
    let mut sink = flacenc::bitsink::ByteSink::new();
    stream
        .write(&mut sink)
        .map_err(|e| format!("FLAC encoding failed: {e:?}"))?;
    Ok(sink.as_slice().to_vec())
}

/// Where to cut between `search_start` and `hard_end`: the middle of the
/// quietest `window`-sample stretch, preferring the latest one on a tie so
/// parts stay as long as allowed. Always returns a point in
/// `(search_start, hard_end]`.
pub fn quietest_cut(samples: &[i16], search_start: usize, hard_end: usize, window: usize) -> usize {
    let window = window.max(1);
    if hard_end <= search_start + window {
        return hard_end;
    }
    let step = (window / 2).max(1);
    let mut best_energy = u64::MAX;
    let mut best_cut = hard_end;
    let mut pos = hard_end - window;
    loop {
        let energy: u64 = samples[pos..pos + window]
            .iter()
            .map(|&s| (s as i64 * s as i64) as u64)
            .sum();
        if energy < best_energy {
            best_energy = energy;
            best_cut = pos + window / 2;
        }
        if pos < search_start + step {
            break;
        }
        pos -= step;
    }
    best_cut.max(search_start + 1).min(hard_end)
}

/// Split `len` samples into consecutive ranges no longer than `max_part`,
/// each cut at the quietest point in the `search` samples before its limit.
/// The ranges cover every sample exactly once, in order.
pub fn plan_parts(
    samples: &[i16],
    max_part: usize,
    search: usize,
    window: usize,
) -> Vec<Range<usize>> {
    let max_part = max_part.max(1);
    let mut parts = Vec::new();
    let mut start = 0;
    while samples.len() - start > max_part {
        let hard_end = start + max_part;
        let search_start = hard_end.saturating_sub(search).max(start);
        let cut = quietest_cut(samples, search_start, hard_end, window);
        parts.push(start..cut);
        start = cut;
    }
    parts.push(start..samples.len());
    parts
}

/// Turn a recording (any WAV) into the files to upload, in order: one FLAC
/// when it fits under [`MAX_UPLOAD_BYTES`], otherwise ten-minute parts.
pub fn prepare_upload(wav_bytes: &[u8]) -> Result<Vec<Vec<u8>>, String> {
    let reader = hound::WavReader::new(Cursor::new(wav_bytes))
        .map_err(|e| format!("Not a WAV recording: {e}"))?;
    let samples = read_wav_as_speech(reader)?;
    prepare_upload_from_samples(&samples)
}

/// [`prepare_upload`] for samples that are already 16 kHz mono.
pub fn prepare_upload_from_samples(samples: &[i16]) -> Result<Vec<Vec<u8>>, String> {
    let whole = encode_flac(samples)?;
    if whole.len() <= MAX_UPLOAD_BYTES {
        return Ok(vec![whole]);
    }
    let ranges = plan_parts(
        samples,
        MAX_PART_SAMPLES,
        CUT_SEARCH_SAMPLES,
        CUT_WINDOW_SAMPLES,
    );
    let mut parts = Vec::with_capacity(ranges.len());
    for range in ranges {
        let part = encode_flac(&samples[range])?;
        if part.len() > MAX_UPLOAD_BYTES {
            return Err(format!(
                "A part of the recording is still too large to upload ({} bytes)",
                part.len()
            ));
        }
        parts.push(part);
    }
    log::info!("Recording split into {} upload parts", parts.len());
    Ok(parts)
}

/// Pack upload parts for the webview: a little-endian u32 count, then each
/// part as a u32 length followed by its bytes.
pub fn frame_parts(parts: &[Vec<u8>]) -> Vec<u8> {
    let total: usize = parts.iter().map(|p| 4 + p.len()).sum();
    let mut out = Vec::with_capacity(4 + total);
    out.extend_from_slice(&(parts.len() as u32).to_le_bytes());
    for part in parts {
        out.extend_from_slice(&(part.len() as u32).to_le_bytes());
        out.extend_from_slice(part);
    }
    out
}

/// Rewrite a finished recording in place as 16 kHz mono 16-bit WAV. Returns
/// the duration in seconds. A file already in that format is left alone.
pub fn convert_recording_file(path: &Path) -> Result<f32, String> {
    let reader =
        hound::WavReader::open(path).map_err(|e| format!("Couldn't open the recording: {e}"))?;
    let spec = reader.spec();
    if spec == speech_wav_spec() {
        return Ok(reader.duration() as f32 / SPEECH_SAMPLE_RATE as f32);
    }
    let samples = read_wav_as_speech(reader)?;
    let bytes = encode_wav(&samples)?;
    let tmp = path.with_extension("wav.converting");
    std::fs::write(&tmp, &bytes).map_err(|e| format!("Couldn't write the recording: {e}"))?;
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(format!("Couldn't replace the recording: {e}"));
    }
    Ok(samples.len() as f32 / SPEECH_SAMPLE_RATE as f32)
}

/// Tauri command: the webview sends a recording's bytes and gets back the
/// FLAC file(s) to upload, framed by [`frame_parts`]. Anything that isn't a
/// WAV is refused so the caller uploads it as it is.
#[tauri::command]
pub async fn prepare_upload_audio(
    request: tauri::ipc::Request<'_>,
) -> Result<tauri::ipc::Response, String> {
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("Expected the recording's bytes".into());
    };
    let bytes = bytes.clone();
    let framed = tauri::async_runtime::spawn_blocking(move || {
        prepare_upload(&bytes).map(|parts| frame_parts(&parts))
    })
    .await
    .map_err(|e| format!("Audio preparation stopped: {e}"))??;
    Ok(tauri::ipc::Response::new(framed))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sine(rate: u32, seconds: f32, freq: f32, amp: f32) -> Vec<f32> {
        let n = (rate as f32 * seconds) as usize;
        (0..n)
            .map(|i| amp * (2.0 * std::f32::consts::PI * freq * i as f32 / rate as f32).sin())
            .collect()
    }

    fn float_stereo_wav(mono: &[f32], rate: u32) -> Vec<u8> {
        let spec = hound::WavSpec {
            channels: 2,
            sample_rate: rate,
            bits_per_sample: 32,
            sample_format: hound::SampleFormat::Float,
        };
        let mut cursor = Cursor::new(Vec::new());
        {
            let mut w = hound::WavWriter::new(&mut cursor, spec).unwrap();
            for &s in mono {
                w.write_sample(s).unwrap();
                w.write_sample(s * 0.5).unwrap();
            }
            w.finalize().unwrap();
        }
        cursor.into_inner()
    }

    #[test]
    fn downmix_averages_channels() {
        assert_eq!(
            downmix_to_mono(&[1.0, 0.0, 0.5, 0.5, -1.0, 1.0], 2),
            vec![0.5, 0.5, 0.0]
        );
        assert_eq!(downmix_to_mono(&[0.25, 0.5], 1), vec![0.25, 0.5]);
        // A trailing partial frame is dropped.
        assert_eq!(downmix_to_mono(&[1.0, 1.0, 1.0], 2), vec![1.0]);
    }

    #[test]
    fn f32_to_i16_scales_and_clips() {
        assert_eq!(
            f32_to_i16(&[0.0, 1.0, -1.0, 2.0, -2.0, 0.5]),
            vec![0, 32767, -32768, 32767, -32768, 16384]
        );
    }

    #[test]
    fn resampled_len_rounds() {
        assert_eq!(resampled_len(48_000, 48_000, 16_000), 16_000);
        assert_eq!(resampled_len(44_100, 44_100, 16_000), 16_000);
        assert_eq!(resampled_len(10, 48_000, 16_000), 3);
    }

    #[test]
    fn stereo_float_48k_becomes_16k_mono_of_same_length() {
        let mono = sine(48_000, 2.0, 440.0, 0.5);
        let wav = float_stereo_wav(&mono, 48_000);
        let out = read_wav_as_speech(hound::WavReader::new(Cursor::new(&wav)).unwrap()).unwrap();
        assert_eq!(out.len(), 32_000);
        // Downmix of (s, s/2) is 0.75 s: a 440 Hz tone at amplitude 0.375.
        let peak = out[4_000..28_000]
            .iter()
            .map(|s| s.unsigned_abs())
            .max()
            .unwrap() as f32;
        let expected = 0.375 * 32768.0;
        assert!(
            (peak - expected).abs() < expected * 0.03,
            "peak {peak} vs {expected}"
        );
        // The tone is in phase with the input (filter delay removed): compare
        // zero-crossing counts over the middle second.
        let crossings = out[8_000..24_000]
            .windows(2)
            .filter(|w| (w[0] < 0) != (w[1] < 0))
            .count();
        assert!((878..=882).contains(&crossings), "crossings {crossings}");
    }

    #[test]
    fn sixteen_k_mono_pcm_passes_through_unchanged() {
        // Full 16-bit range, including both extremes, comes back bit for bit.
        let samples: Vec<i16> = (0..16_000u32)
            .map(|i| (i.wrapping_mul(2_654_435_761) >> 16) as u16 as i16)
            .chain([i16::MIN, i16::MAX, -1, 0, 1])
            .collect();
        let wav = encode_wav(&samples).unwrap();
        assert_eq!(wav.len(), 44 + samples.len() * 2);
        let back = read_wav_as_speech(hound::WavReader::new(Cursor::new(&wav)).unwrap()).unwrap();
        assert_eq!(back, samples);
    }

    #[test]
    fn upload_is_flac_and_much_smaller_than_capture() {
        let mono = sine(48_000, 18.41, 220.0, 0.3);
        let wav = float_stereo_wav(&mono, 48_000);
        // 18.41 s at 48 kHz stereo f32 is ~7.07 MB, as measured in the app log.
        assert!(wav.len() > 7_000_000);
        let parts = prepare_upload(&wav).unwrap();
        assert_eq!(parts.len(), 1);
        assert_eq!(&parts[0][..4], b"fLaC");
        // Never larger than 16 kHz mono 16-bit PCM (32 KB/s) plus framing.
        assert!(parts[0].len() < (18.41 * 32_000.0) as usize + 4_096);
    }

    #[test]
    fn flac_size_never_exceeds_pcm_bound_even_for_noise() {
        // White noise is the worst case for FLAC: it falls back to verbatim.
        let mut x: u32 = 1;
        let noise: Vec<i16> = (0..SPEECH_SAMPLE_RATE as usize * 5)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                x as i16
            })
            .collect();
        let flac = encode_flac(&noise).unwrap();
        assert!(
            flac.len() <= noise.len() * 2 + noise.len() / 100 + 4_096,
            "{}",
            flac.len()
        );
        // So a 10-minute part is always under the upload limit.
        let worst_part = MAX_PART_SAMPLES * 2 + MAX_PART_SAMPLES / 100 + 4_096;
        assert!(worst_part < MAX_UPLOAD_BYTES);
    }

    #[test]
    fn empty_recording_is_refused() {
        assert!(encode_flac(&[]).is_err());
    }

    #[test]
    fn short_recordings_are_one_part() {
        let samples = vec![0i16; 100];
        assert_eq!(plan_parts(&samples, 100, 10, 4), vec![0..100]);
        assert_eq!(plan_parts(&[], 100, 10, 4), vec![0..0]);
    }

    #[test]
    fn parts_cut_at_the_quiet_gap_before_the_limit() {
        // Loud everywhere except a quiet gap at 850..870.
        let mut samples = vec![10_000i16; 2_500];
        for s in &mut samples[850..870] {
            *s = 0;
        }
        let parts = plan_parts(&samples, 1_000, 300, 10);
        assert_eq!(parts[0].start, 0);
        assert!((850..=870).contains(&parts[0].end), "{:?}", parts);
        assert!(parts.iter().all(|r| r.len() <= 1_000));
        // Contiguous and complete.
        for pair in parts.windows(2) {
            assert_eq!(pair[0].end, pair[1].start);
        }
        assert_eq!(parts.last().unwrap().end, samples.len());
    }

    #[test]
    fn parts_without_silence_still_respect_the_limit() {
        let samples = vec![5_000i16; 10_001];
        let parts = plan_parts(&samples, 1_000, 300, 10);
        assert!(
            parts.iter().all(|r| !r.is_empty() && r.len() <= 1_000),
            "{:?}",
            parts
        );
        assert_eq!(parts.iter().map(|r| r.len()).sum::<usize>(), samples.len());
        // A flat signal is cut as late as allowed, so few parts are needed.
        assert_eq!(parts.len(), 11);
    }

    #[test]
    fn quietest_cut_stays_inside_the_window() {
        let samples = vec![1i16; 100];
        let cut = quietest_cut(&samples, 40, 90, 10);
        assert!(cut > 40 && cut <= 90);
        assert_eq!(quietest_cut(&samples, 85, 90, 10), 90);
    }

    #[test]
    fn frame_parts_layout() {
        let framed = frame_parts(&[vec![1, 2], vec![], vec![3]]);
        assert_eq!(
            framed,
            vec![3, 0, 0, 0, 2, 0, 0, 0, 1, 2, 0, 0, 0, 0, 1, 0, 0, 0, 3]
        );
    }

    #[test]
    fn long_loud_recording_splits_into_parts_under_the_limit() {
        // 21 minutes of noise does not fit as one FLAC, so it is split.
        let mut x: u32 = 7;
        let samples: Vec<i16> = (0..SPEECH_SAMPLE_RATE as usize * 60 * 21)
            .map(|_| {
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                (x as i16) / 2
            })
            .collect();
        let parts = prepare_upload_from_samples(&samples).unwrap();
        assert_eq!(parts.len(), 3);
        assert!(parts
            .iter()
            .all(|p| p.len() <= MAX_UPLOAD_BYTES && &p[..4] == b"fLaC"));
    }

    /// Runs a real recording through the upload path; run by hand with
    /// `VOICEFLOW_A0_WAV=<recording.wav> VOICEFLOW_A0_OUT=<folder>
    /// cargo test real_recording_to_flac -- --ignored`. Writes the FLAC
    /// part(s) to the folder and prints only sizes.
    #[test]
    #[ignore]
    fn real_recording_to_flac() {
        let input = std::env::var("VOICEFLOW_A0_WAV").expect("VOICEFLOW_A0_WAV");
        let out = std::path::PathBuf::from(std::env::var("VOICEFLOW_A0_OUT").expect("VOICEFLOW_A0_OUT"));
        let wav = std::fs::read(&input).unwrap();
        let started = std::time::Instant::now();
        let parts = prepare_upload(&wav).unwrap();
        let ms = started.elapsed().as_millis();
        std::fs::create_dir_all(&out).unwrap();
        let stem = std::path::Path::new(&input).file_stem().unwrap().to_string_lossy().to_string();
        for (i, part) in parts.iter().enumerate() {
            assert_eq!(&part[..4], b"fLaC");
            std::fs::write(out.join(format!("{stem}.part{i}.flac")), part).unwrap();
        }
        let sizes: Vec<usize> = parts.iter().map(Vec::len).collect();
        println!("wav_bytes={} parts={:?} prepare_ms={ms}", wav.len(), sizes);
    }

    #[test]
    fn convert_recording_file_rewrites_in_place() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("rec.wav");
        let mono = sine(48_000, 3.0, 300.0, 0.4);
        std::fs::write(&path, float_stereo_wav(&mono, 48_000)).unwrap();
        let before = std::fs::metadata(&path).unwrap().len();
        let secs = convert_recording_file(&path).unwrap();
        assert!((secs - 3.0).abs() < 0.001);
        let reader = hound::WavReader::open(&path).unwrap();
        assert_eq!(reader.spec(), speech_wav_spec());
        assert_eq!(reader.duration(), 48_000);
        let after = std::fs::metadata(&path).unwrap().len();
        assert_eq!(after, 44 + 96_000);
        assert!(before / after >= 11);
        // Converting again leaves it alone.
        assert!((convert_recording_file(&path).unwrap() - 3.0).abs() < 0.001);
        assert!(!dir.path().join("rec.wav.converting").exists());
    }
}
