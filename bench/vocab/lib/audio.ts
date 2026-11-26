/**
 * Audio files, kept outside the repository (DATA_DIR/audio/<set>/<id>.*).
 * Every clip is sent as VoiceFlow sends it: 16 kHz mono FLAC to the
 * transcriber, and the same samples as 16-bit WAV to the audio model.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { DATA_DIR } from './paths';

export function audioDir(set: string): string {
	return path.join(DATA_DIR, 'audio', set);
}

export function flacPath(set: string, id: string): string {
	return path.join(audioDir(set), `${id}.flac`);
}

export function wavPath(set: string, id: string): string {
	return path.join(audioDir(set), `${id}.wav`);
}

/** Any input file to the two 16 kHz mono files for this clip. */
export function prepare(set: string, id: string, input: string): void {
	mkdirSync(audioDir(set), { recursive: true });
	const opts = { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] as ('ignore' | 'pipe')[] };
	execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', input, '-ar', '16000', '-ac', '1', '-c:a', 'pcm_s16le', wavPath(set, id)], opts);
	execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', wavPath(set, id), '-c:a', 'flac', flacPath(set, id)], opts);
}

export function hasAudio(set: string, id: string): boolean {
	return existsSync(flacPath(set, id)) && existsSync(wavPath(set, id)) && statSync(wavPath(set, id)).size > 44;
}

/** Seconds of a 16 kHz mono 16-bit WAV. */
export function seconds(set: string, id: string): number {
	return (statSync(wavPath(set, id)).size - 44) / 32000;
}

export function wavBase64(set: string, id: string): string {
	return readFileSync(wavPath(set, id)).toString('base64');
}

export function flacBytes(set: string, id: string): Buffer {
	return readFileSync(flacPath(set, id));
}
