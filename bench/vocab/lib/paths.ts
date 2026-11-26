import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** This folder: bench/vocab. */
export const BENCH_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** VoiceFlow's desktop source, whose prompt code the harness imports as built. */
export const VF_LIB = path.resolve(BENCH_DIR, '..', '..', 'desktop', 'apps', 'whispering', 'src', 'lib');

/**
 * Where audio and raw API outputs live: outside the repository, so no voice
 * recording is ever committed. Override with VOCAB_DATA_DIR.
 */
export const DATA_DIR = process.env.VOCAB_DATA_DIR || 'C:\\vf\\vocab-test';
