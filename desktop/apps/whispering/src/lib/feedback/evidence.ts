/**
 * VoiceFlow phase F: stage evidence for one dictation, so feedback on it can
 * be diagnosed (PLAN-v2, addendum "style switching on the bar, and
 * feedback"). Kept on this device next to the History entry
 * (`<app data>/dictation-evidence/<recording id>.json`), written as the
 * dictation moves through the pipeline.
 *
 * It never holds a window title, a window handle, a process id or an
 * executable path: only the app's name and id (exe name or bundle id).
 *
 * Pure: the file work is in `evidence-store.ts`.
 */
import type { Recording } from '$lib/services/isomorphic/db';

export const EVIDENCE_SCHEMA = 'voiceflow-dictation-evidence' as const;

export const DELIVERY_OUTCOMES = [
	'pasted',
	'copied',
	'not-pasted',
	'held',
	'tidy-failed',
	'transcription-failed',
	'kept',
] as const;
export type EvidenceOutcome = (typeof DELIVERY_OUTCOMES)[number];

export type AppliedSpelling = {
	/** The correction's id (an edit or a word-list correction). */
	id: string;
	heard: string;
	written: string;
};

export type DictationEvidence = {
	schema: typeof EVIDENCE_SCHEMA;
	version: 1;
	recordingId: string;
	updatedAt: string;
	appName?: string;
	/** Lower-case exe name or bundle id (what spellings are scoped by). */
	appId?: string;
	/** macOS bundle id, lower case (app rules use it there). Never sent. */
	appBundleId?: string;
	/** What speech-to-text heard. */
	rawText?: string;
	/** Tidy-up's output, before spellings. Absent: no tidy-up ran. */
	tidyOutput?: string;
	/** What VoiceFlow delivered (tidied or raw, with spellings applied). */
	deliveredText?: string;
	/** The style used: a style id (`profile:<id>` for a word-list style) or `raw`. */
	styleId?: string;
	styleName?: string;
	/** The expression tidy-up used (0–4); null: the style's own prompt only. */
	expression?: number | null;
	/** The spellings that changed the text. */
	corrections?: AppliedSpelling[];
	outcome?: EvidenceOutcome;
	/** "Pasted into <app>", from the paste-time authorization. */
	pastedInto?: string;
	models?: { transcription?: string; tidyUp?: string };
	/** The word-list snapshot pinned for this dictation. */
	profileId?: string;
	/** Built from History alone (a dictation from before VoiceFlow 0.9.7). */
	partial?: boolean;
	/** VoiceFlow 0.9.16: the tidy-up as it ran (absent: no tidy-up, or an older dictation). */
	tidyConfig?: TidyConfig;
};

/**
 * VoiceFlow 0.9.16 (feedback v2): the tidy-up exactly as it ran for this
 * dictation, so it can be re-run the same way later (phase 2's before/after
 * check). Kept on this PC with the evidence; never sent with feedback.
 */
export type TidyConfig = {
	/** The style run: `profile:<id>` for a word-list style, else this PC's style id. */
	styleId: string;
	styleName?: string;
	source: 'word-lists' | 'this-pc';
	/** The style as stored (steps before expression and words): sha-256, first 16 hex. */
	styleRevision: string;
	/** A word-list style's layer and the style edits in force on it. */
	styleLayer?: string;
	styleEdits?: string[];
	/** The level used, 0–4; null: the style's own prompt only. */
	expression: number | null;
	/** The first AI step's provider and model. */
	model?: string;
	/** The word-list snapshot pinned for this dictation. */
	profileId?: string;
	/**
	 * The tidy-up's word list, most important first, each with the forms
	 * speech-to-text wrote for it (the language guard credits them too).
	 */
	words: TidyConfigWord[];
	/** The exact steps run (style, expression and words): sha-256, first 16 hex. */
	runRevision: string;
	/** The exact steps run; empty when they were too large to keep (`stepsOmitted`). */
	steps: unknown[];
	stepsOmitted?: true;
	/** The language guard made a strict retry. */
	retried?: boolean;
};

/** One word in the tidy-up's list as it ran. */
export type TidyConfigWord = { text: string; heardAs: string[] };

/** The largest steps kept with one dictation (characters of JSON). */
export const MAX_TIDY_STEPS_CHARS = 60_000;

async function sha16(text: string): Promise<string> {
	const bytes = new TextEncoder().encode(text);
	const digest = await crypto.subtle.digest('SHA-256', bytes);
	return [...new Uint8Array(digest)]
		.slice(0, 8)
		.map((b) => b.toString(16).padStart(2, '0'))
		.join('');
}

/** Builds the tidy configuration from what the pipeline ran. */
export async function tidyConfigOf(a: {
	styleId: string;
	styleName?: string;
	source: TidyConfig['source'];
	/** The style as stored (before expression and words). */
	styleSteps: readonly unknown[];
	/** The steps actually run. */
	runSteps: readonly unknown[];
	styleLayer?: string;
	styleEdits?: readonly string[];
	expression: number | null;
	model?: string;
	profileId?: string;
	words: readonly TidyConfigWord[];
	retried?: boolean;
}): Promise<TidyConfig> {
	const run = JSON.stringify(a.runSteps);
	const keep = run.length <= MAX_TIDY_STEPS_CHARS;
	return {
		styleId: a.styleId,
		...(a.styleName ? { styleName: a.styleName } : {}),
		source: a.source,
		styleRevision: await sha16(JSON.stringify(a.styleSteps)),
		...(a.styleLayer ? { styleLayer: a.styleLayer } : {}),
		...(a.styleEdits?.length ? { styleEdits: [...a.styleEdits] } : {}),
		expression: a.expression,
		...(a.model ? { model: a.model } : {}),
		...(a.profileId ? { profileId: a.profileId } : {}),
		words: a.words.slice(0, 200).map((w) => ({ text: w.text, heardAs: [...w.heardAs] })),
		runRevision: await sha16(run),
		steps: keep ? (JSON.parse(run) as unknown[]) : [],
		...(keep ? {} : { stepsOmitted: true as const }),
		...(a.retried ? { retried: true } : {}),
	};
}

function parseTidyConfig(value: unknown): TidyConfig | null {
	if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
	const v = value as Record<string, unknown>;
	if (typeof v.styleId !== 'string' || !v.styleId) return null;
	if (v.source !== 'word-lists' && v.source !== 'this-pc') return null;
	if (typeof v.styleRevision !== 'string' || typeof v.runRevision !== 'string') return null;
	const expression =
		typeof v.expression === 'number' && Number.isInteger(v.expression)
			? Math.max(0, Math.min(4, v.expression))
			: null;
	const strings = (x: unknown) =>
		Array.isArray(x) ? x.filter((s): s is string => typeof s === 'string') : [];
	return {
		styleId: v.styleId,
		...(typeof v.styleName === 'string' ? { styleName: v.styleName } : {}),
		source: v.source,
		styleRevision: v.styleRevision,
		...(typeof v.styleLayer === 'string' ? { styleLayer: v.styleLayer } : {}),
		...(Array.isArray(v.styleEdits) ? { styleEdits: strings(v.styleEdits) } : {}),
		expression,
		...(typeof v.model === 'string' ? { model: v.model } : {}),
		...(typeof v.profileId === 'string' ? { profileId: v.profileId } : {}),
		// A plain word (an earlier build kept words without their forms) reads with none.
		words: Array.isArray(v.words)
			? v.words.flatMap((w) =>
					typeof w === 'string'
						? [{ text: w, heardAs: [] }]
						: w && typeof w === 'object' && typeof (w as TidyConfigWord).text === 'string'
							? [{ text: (w as TidyConfigWord).text, heardAs: strings((w as TidyConfigWord).heardAs) }]
							: [],
				)
			: [],
		runRevision: v.runRevision,
		steps: Array.isArray(v.steps) ? v.steps : [],
		...(v.stepsOmitted === true ? { stepsOmitted: true as const } : {}),
		...(v.retried === true ? { retried: true } : {}),
	};
}

/** Fields the pipeline may set. */
export type EvidencePatch = Partial<
	Omit<DictationEvidence, 'schema' | 'version' | 'recordingId' | 'updatedAt'>
>;

const TEXT_LIMIT = 20_000;

function clip(text: string | undefined): string | undefined {
	if (text === undefined) return undefined;
	return text.length > TEXT_LIMIT ? text.slice(0, TEXT_LIMIT) : text;
}

/**
 * Applies a patch: a field set to undefined is removed, others replace the
 * stored value. Text is capped so a runaway output can't grow the file.
 */
export function mergeEvidence(
	recordingId: string,
	stored: DictationEvidence | null,
	patch: EvidencePatch,
	now: string,
): DictationEvidence {
	const base: DictationEvidence = stored ?? {
		schema: EVIDENCE_SCHEMA,
		version: 1,
		recordingId,
		updatedAt: now,
	};
	const next: Record<string, unknown> = { ...base, updatedAt: now };
	for (const [key, value] of Object.entries(patch)) {
		if (value === undefined) delete next[key];
		// Models arrive one stage at a time: transcription, then tidy-up.
		else if (key === 'models' && value && typeof value === 'object')
			next.models = { ...(base.models ?? {}), ...(value as object) };
		else next[key] = value;
	}
	const out = next as DictationEvidence;
	out.rawText = clip(out.rawText);
	out.tidyOutput = clip(out.tidyOutput);
	out.deliveredText = clip(out.deliveredText);
	for (const key of ['rawText', 'tidyOutput', 'deliveredText'] as const)
		if (out[key] === undefined) delete out[key];
	// Never kept, whatever a caller passes (window titles stay local).
	for (const key of ['windowTitle', 'appWindowId', 'appPid', 'appExePath'])
		delete (out as Record<string, unknown>)[key];
	return out;
}

const isString = (v: unknown): v is string => typeof v === 'string';

/** Reads a stored evidence file; null when it is not one. */
export function parseEvidence(value: unknown): DictationEvidence | null {
	if (!value || typeof value !== 'object') return null;
	const v = value as Record<string, unknown>;
	if (v.schema !== EVIDENCE_SCHEMA || v.version !== 1) return null;
	if (!isString(v.recordingId) || !v.recordingId) return null;
	const out: DictationEvidence = {
		schema: EVIDENCE_SCHEMA,
		version: 1,
		recordingId: v.recordingId,
		updatedAt: isString(v.updatedAt) ? v.updatedAt : '',
	};
	for (const key of [
		'appName',
		'appId',
		'appBundleId',
		'rawText',
		'tidyOutput',
		'deliveredText',
		'styleId',
		'styleName',
		'pastedInto',
		'profileId',
	] as const) {
		if (isString(v[key])) out[key] = v[key] as string;
	}
	if (v.expression === null) out.expression = null;
	else if (typeof v.expression === 'number' && Number.isInteger(v.expression))
		out.expression = Math.max(0, Math.min(4, v.expression));
	if (isString(v.outcome) && (DELIVERY_OUTCOMES as readonly string[]).includes(v.outcome))
		out.outcome = v.outcome as EvidenceOutcome;
	if (Array.isArray(v.corrections))
		out.corrections = v.corrections.filter(
			(c): c is AppliedSpelling =>
				!!c &&
				typeof c === 'object' &&
				isString((c as AppliedSpelling).id) &&
				isString((c as AppliedSpelling).heard) &&
				isString((c as AppliedSpelling).written),
		);
	if (v.models && typeof v.models === 'object') {
		const m = v.models as Record<string, unknown>;
		out.models = {};
		if (isString(m.transcription)) out.models.transcription = m.transcription;
		if (isString(m.tidyUp)) out.models.tidyUp = m.tidyUp;
	}
	if (v.partial === true) out.partial = true;
	const tidyConfig = parseTidyConfig(v.tidyConfig);
	if (tidyConfig) out.tidyConfig = tidyConfig;
	return out;
}

/** The spellings a feedback request names, at most this many. */
export const MAX_SPELLING_REFS = 20;

/**
 * The spellings that changed a dictation, each with the short ref the fix
 * request names it by (`s1`, `s2`…), in the order they were applied. The
 * spelling's own id stays on this PC.
 */
export function spellingRefs(evidence: Pick<DictationEvidence, 'corrections'>): Array<AppliedSpelling & { ref: string }> {
	return (evidence.corrections ?? []).slice(0, MAX_SPELLING_REFS).map((c, i) => ({ ...c, ref: `s${i + 1}` }));
}

const OUTCOME_OF_RESULT: Record<NonNullable<Recording['result']>, EvidenceOutcome> = {
	pasted: 'pasted',
	copied: 'copied',
	'not-pasted': 'not-pasted',
	'no-text': 'transcription-failed',
};

/**
 * The evidence for a dictation: what the pipeline kept, filled in from the
 * History entry where it has nothing (older dictations: `partial`).
 */
export function evidenceFor(
	recording: Recording,
	stored: DictationEvidence | null,
	appId: string | null,
): DictationEvidence {
	const fromHistory: DictationEvidence = {
		schema: EVIDENCE_SCHEMA,
		version: 1,
		recordingId: recording.id,
		updatedAt: recording.updatedAt,
		...(recording.appName ? { appName: recording.appName } : {}),
		...(appId ? { appId } : {}),
		...(recording.appBundleId ? { appBundleId: recording.appBundleId.toLowerCase() } : {}),
		rawText: recording.transcribedText ?? '',
		...(recording.finalText ? { deliveredText: recording.finalText } : {}),
		...(recording.styleName ? { styleName: recording.styleName } : {}),
		...(recording.privacy === 'held'
			? { outcome: 'held' as const }
			: recording.result
				? { outcome: OUTCOME_OF_RESULT[recording.result] }
				: {}),
		partial: true,
	};
	if (!stored) return fromHistory;
	const merged: DictationEvidence = { ...fromHistory, ...stored };
	delete merged.partial;
	// History is the truth for the text the person sees now.
	if (!merged.rawText) merged.rawText = recording.transcribedText ?? '';
	if (!merged.deliveredText && recording.finalText) merged.deliveredText = recording.finalText;
	return merged;
}
