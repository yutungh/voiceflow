/**
 * VoiceFlow: per-dictation timings (speed work, part 1).
 *
 * Each hotkey dictation gets a trace: milliseconds since the hotkey at which
 * each stage was reached. Finished traces are appended as one JSON line to
 * `<app local data>/timing/dictations.jsonl`. A trace holds timings, byte and
 * word counts and model names only: never text, titles or keys.
 *
 * This file is pure (no Tauri imports) so it can be unit tested; storage
 * lives in `timing-store.ts`.
 */

/** Stages in the order a dictation reaches them. */
export const STAGES = [
	'hotkey', // the start command ran
	'target', // the app in front was captured and checked
	'mic-ready', // the recorder is capturing
	'stop', // the stop command ran
	'audio-saved', // the recording is closed and its audio read back
	'db-saved', // the recording is in history
	'transcribe-start', // transcription began
	'upload-start', // the upload request is about to go out (audio prep done)
	'transcribed', // the transcript came back
	'transcript-saved', // the transcript is in history
	'tidy-start', // tidy-up began
	'tidy-done', // tidy-up returned
	'corrections-done', // approved corrections applied (not in the app yet)
	'paste-checked', // paste-time check done
	'paste-done', // the text was pasted
	'copy-done', // 0.9.11: nowhere to paste, the text was copied instead
] as const;

export type Stage = (typeof STAGES)[number];

export type TraceOutcome =
	| 'pasted'
	| 'copied'
	| 'not-pasted'
	| 'held'
	/** 0.9.11: no speech in the recording. */
	| 'nothing-heard'
	| 'transcription-failed'
	| 'tidy-failed';

/** One finished dictation, as stored. */
export type TimingRecord = {
	v: 1;
	/** When the hotkey was pressed (ISO). */
	at: string;
	outcome: TraceOutcome;
	/** Milliseconds since the hotkey, per stage reached. */
	marks: Partial<Record<Stage, number>>;
	/** Bytes of the recording as read back after stop. */
	audioBytes?: number;
	/** Bytes of the upload(s). */
	uploadBytes?: number;
	/** Number of upload requests (more than one when a recording is split). */
	uploads?: number;
	model?: string;
	/** Words in the final text (a count only). */
	words?: number;
};

type Trace = {
	t0: number;
	at: string;
	marks: Partial<Record<Stage, number>>;
	meta: Omit<TimingRecord, 'v' | 'at' | 'outcome' | 'marks'>;
};

export type TimingClock = { now(): number; isoNow(): string };

const defaultClock: TimingClock = {
	now: () => performance.now(),
	isoNow: () => new Date().toISOString(),
};

/** Keeps the traces of dictations in flight. One instance per app. */
export function createDictationTiming({
	clock = defaultClock,
	write,
}: {
	clock?: TimingClock;
	/** Called once per finished trace. Must not throw. */
	write: (record: TimingRecord) => void;
}) {
	/** The trace between hotkey and stop (not yet tied to a recording). */
	let open: Trace | null = null;
	const byRecording = new Map<string, Trace>();

	const elapsed = (trace: Trace) => Math.round((clock.now() - trace.t0) * 10) / 10;

	function setMark(trace: Trace | null | undefined, stage: Stage) {
		if (trace && trace.marks[stage] === undefined) {
			trace.marks[stage] = elapsed(trace);
		}
	}

	return {
		/**
		 * The start hotkey. Replaces a trace that never reached stop.
		 * VoiceFlow: `agoMs` backdates it to when a native hotkey was pressed
		 * (src-tauri/src/hotkey.rs), so the marks count from the key.
		 */
		hotkey(agoMs = 0) {
			const ago = Number.isFinite(agoMs) && agoMs > 0 ? agoMs : 0;
			const t0 = clock.now() - ago;
			const at = ago ? new Date(Date.parse(clock.isoNow()) - ago).toISOString() : clock.isoNow();
			open = { t0, at, marks: { hotkey: 0 }, meta: {} };
		},

		/** Marks the trace between hotkey and stop. */
		mark(stage: Stage) {
			setMark(open, stage);
		},

		/**
		 * Drops the open trace without a record: the recording is not a
		 * dictation (a voice feedback recording is transcribed only).
		 */
		discardOpen() {
			open = null;
		},

		/** Ties the open trace to its recording once the audio is saved. */
		bind(recordingId: string, meta: { audioBytes?: number } = {}) {
			if (!open) return;
			setMark(open, 'audio-saved');
			Object.assign(open.meta, meta);
			byRecording.set(recordingId, open);
			open = null;
		},

		/** Marks a recording's trace. No trace (an upload, a retry): nothing happens. */
		markRecording(recordingId: string, stage: Stage) {
			setMark(byRecording.get(recordingId), stage);
		},

		/**
		 * An upload is starting. Services don't know the recording, so the
		 * oldest trace that is transcribing and has no upload yet gets it;
		 * later parts of a split recording only add their bytes.
		 */
		uploadStarting({ bytes, model }: { bytes: number; model?: string }) {
			const traces = [...byRecording.values()].filter(
				(t) => t.marks['transcribe-start'] !== undefined && t.marks.transcribed === undefined,
			);
			const fresh = traces.find((t) => t.marks['upload-start'] === undefined);
			const trace = fresh ?? traces[0];
			if (!trace) return;
			setMark(trace, 'upload-start');
			trace.meta.uploadBytes = (trace.meta.uploadBytes ?? 0) + bytes;
			trace.meta.uploads = (trace.meta.uploads ?? 0) + 1;
			if (model) trace.meta.model = model;
		},

		/** Ends a recording's trace and hands it to `write`. */
		finish(
			recordingId: string,
			{ outcome, words }: { outcome?: TraceOutcome; words?: number } = {},
		) {
			const trace = byRecording.get(recordingId);
			if (!trace) return;
			byRecording.delete(recordingId);
			const record: TimingRecord = {
				v: 1,
				at: trace.at,
				outcome:
					outcome ??
					(trace.marks['paste-done'] !== undefined
						? 'pasted'
						: trace.marks['copy-done'] !== undefined
							? 'copied'
							: 'not-pasted'),
				marks: trace.marks,
				...trace.meta,
				...(words !== undefined ? { words } : {}),
			};
			write(record);
		},

		/** For tests: traces still in flight. */
		inFlight() {
			return { open: open !== null, recordings: byRecording.size };
		},
	};
}

export type DictationTiming = ReturnType<typeof createDictationTiming>;

export function countWords(text: string | null | undefined): number {
	return text ? text.trim().split(/\s+/).filter(Boolean).length : 0;
}

// ---------------------------------------------------------------------------
// Summaries for Settings > Speed
// ---------------------------------------------------------------------------

/** A span shown in Settings: from one stage to the next one reached. */
export type Segment = {
	id: string;
	label: string;
	from: Stage | Stage[];
	to: Stage;
};

/**
 * The spans shown in Settings > Speed. `from` lists fall back in order (the
 * first stage the trace reached is used), so paste is timed from tidy-up when
 * there was one and from the transcript otherwise.
 */
export const SEGMENTS: Segment[] = [
	{ id: 'release-to-paste', label: 'Release → pasted (total)', from: 'stop', to: 'paste-done' },
	{ id: 'hotkey-to-mic', label: 'Hotkey → microphone ready', from: 'hotkey', to: 'mic-ready' },
	{ id: 'target', label: '  app check at the hotkey', from: 'hotkey', to: 'target' },
	{ id: 'mic-open', label: '  microphone start', from: 'target', to: 'mic-ready' },
	{ id: 'stop', label: 'Stop and read back the audio', from: 'stop', to: 'audio-saved' },
	{ id: 'save', label: 'Save to history', from: 'audio-saved', to: 'db-saved' },
	{ id: 'prep', label: 'Audio prep (read, compress or convert)', from: 'transcribe-start', to: 'upload-start' },
	{ id: 'transcribe', label: 'Upload + transcription', from: 'upload-start', to: 'transcribed' },
	{ id: 'save-transcript', label: 'Save the transcript', from: 'transcribed', to: 'transcript-saved' },
	{ id: 'tidy', label: 'Tidy-up', from: 'tidy-start', to: 'tidy-done' },
	{ id: 'corrections', label: 'Corrections', from: 'tidy-done', to: 'corrections-done' },
	{ id: 'paste-check', label: 'Paste-time app check', from: ['corrections-done', 'tidy-done', 'transcript-saved'], to: 'paste-checked' },
	{ id: 'paste', label: 'Paste', from: 'paste-checked', to: 'paste-done' },
];

export type SegmentStats = {
	id: string;
	label: string;
	n: number;
	p50: number | null;
	p90: number | null;
};

/** Nearest-rank percentile; null for no values. */
export function percentile(values: number[], p: number): number | null {
	const xs = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
	if (xs.length === 0) return null;
	const rank = Math.ceil((p / 100) * xs.length);
	return xs[Math.min(xs.length - 1, Math.max(0, rank - 1))] ?? null;
}

export function segmentDuration(record: TimingRecord, segment: Segment): number | null {
	const end = record.marks[segment.to];
	if (end === undefined) return null;
	const froms = Array.isArray(segment.from) ? segment.from : [segment.from];
	for (const from of froms) {
		const start = record.marks[from];
		if (start !== undefined) return Math.max(0, end - start);
	}
	return null;
}

/** p50 / p90 per segment over the last `lastN` records. */
export function summarize(records: TimingRecord[], lastN = 50): SegmentStats[] {
	const recent = records.slice(-lastN);
	return SEGMENTS.map((segment) => {
		const values = recent
			.map((r) => segmentDuration(r, segment))
			.filter((v): v is number => v !== null);
		return {
			id: segment.id,
			label: segment.label,
			n: values.length,
			p50: percentile(values, 50),
			p90: percentile(values, 90),
		};
	});
}

/** Parses the JSONL file, skipping lines that aren't records. */
export function parseRecords(jsonl: string): TimingRecord[] {
	const records: TimingRecord[] = [];
	for (const line of jsonl.split('\n')) {
		if (!line.trim()) continue;
		try {
			const r = JSON.parse(line) as TimingRecord;
			if (r && r.v === 1 && r.marks && typeof r.marks === 'object') records.push(r);
		} catch {
			// A torn last line from a crash: skip it.
		}
	}
	return records;
}
