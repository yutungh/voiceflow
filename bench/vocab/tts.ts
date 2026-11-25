/**
 * Synthetic recordings for the pilot: every script sentence read by an
 * OpenAI text-to-speech voice, then converted to 16 kHz mono like a dictation.
 * Each invented name gets its pronunciation in the voice instructions.
 *
 *   bun tts.ts --voices coral,ash
 *
 * Audio goes to DATA_DIR/audio/tts-<voice>/ (outside the repository).
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { hasAudio, prepare, seconds } from './lib/audio';
import { tts } from './lib/calls';
import { costOf, record } from './lib/cost';
import { SENTENCES, WORDS } from './lib/data';
import { DATA_DIR } from './lib/paths';

const voicesArg = process.argv.indexOf('--voices');
const voices = (voicesArg > 0 ? process.argv[voicesArg + 1]! : 'coral,ash').split(',');

const BASE = 'Read this like someone dictating a short message: natural, clear, at an ordinary pace, with no extra pauses.';

function instructions(text: string): string {
	const said = WORDS.filter((w) => w.say && w.kind === 'person' && new RegExp(`\\b${w.text}\\b`).test(text));
	if (!said.length) return BASE;
	return `${BASE} ${said.map((w) => `"${w.text}" is a person's name, pronounced ${w.say}.`).join(' ')}`;
}

for (const voice of voices) {
	const set = `tts-${voice}`;
	const raw = path.join(DATA_DIR, 'tts', voice);
	mkdirSync(raw, { recursive: true });
	let total = 0;
	for (const s of SENTENCES) {
		if (hasAudio(set, s.id)) continue;
		const file = path.join(raw, `${s.id}.wav`);
		writeFileSync(file, Buffer.from(await tts(s.text, voice, instructions(s.text))));
		prepare(set, s.id, file);
		const cost = costOf('gpt-4o-mini-tts', null, { seconds: seconds(set, s.id) });
		record({ set, step: 'pilot:tts', arm: 'tts', model: 'gpt-4o-mini-tts', cost });
		total += seconds(set, s.id);
	}
	console.log(`${set}: ${total.toFixed(0)} s of new audio`);
}
