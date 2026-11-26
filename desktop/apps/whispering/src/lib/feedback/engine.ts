/**
 * VoiceFlow phase F: one feedback round. Evidence + feedback + related words
 * go to the person's configured model once; the reply is parsed against the
 * contract and every proposal is reviewed against the word lists. Nothing is
 * written here: lasting fixes are applied only when the person ticks them.
 *
 * VoiceFlow 0.9.16 (feedback v2): one feedback can name up to four problems.
 * The request carries what changed at each stage (computed by code,
 * `stage-diff.ts`) and, when the person typed it, what it should have said;
 * the answer names the layer that failed for each problem and its own fixes.
 */
import {
	parseFixResponseV2,
	FIX_RESPONSE_SCHEMA_V2,
	type Diagnosis,
	type DropCode,
	type Layer,
	type ModelProposal,
} from './contract';
import type { DictationEvidence } from './evidence';
import { buildFixRequestV2, type FixRequestDataV2 } from './prompt';
import type { CompleteArgs, CompleteResult } from './providers';
import type { Observation } from './stage-diff';
import { reviewProposals, type Dropped, type ReviewContext, type ReviewedFix } from './validate';

export type FixIssue = {
	layer: Layer;
	summary: string;
	/** Why this problem has no lasting fix, when the model said. */
	noFixReason: string | null;
};

export type FixResult = {
	/** 2: the answer named issues (VoiceFlow 0.9.16). Absent: a v1 answer (one diagnosis). */
	contract?: 2;
	/** v1 answers only. */
	diagnosis?: Diagnosis;
	explanation: string;
	correctedText: string;
	/** v2: the model's corrected text when the person typed what it should have said. */
	suggestedText?: string;
	/** v2: the problems, in the answer's order (v1 records: see `issuesOf`). */
	issues?: FixIssue[];
	fixes: ReviewedFix[];
	dropped: Dropped[];
	/** v1 answers only. */
	noFixReason?: string | null;
	/** The model's proposals as parsed, to review again if the lists change. */
	proposals: ModelProposal[];
	/** The word-list snapshot the fixes were reviewed against ('' when off). */
	reviewedAgainst: string;
	/** v2: what changed at each stage, as sent. */
	observations?: Observation[];
	observationsTruncated?: boolean;
};

export type FixOutcome = { ok: true; result: FixResult } | { ok: false; message: string };

const DROP_REASONS: Record<DropCode, string> = {
	malformed: 'A suggestion didn’t match what VoiceFlow can safely change, so it was left out.',
	layer: 'A suggestion didn’t fit the problem it was given for, so it was left out.',
	limit: 'A suggestion was past the limit (three word fixes and one style change per feedback), so it was left out.',
	'issue-limit': 'Only the first four problems are looked at, so a fix for a later one was left out.',
};

const LAYER_OF_DIAGNOSIS: Record<Diagnosis, Layer> = {
	mishearing: 'transcriber',
	'tidy-up-rewrite': 'style-prompt',
	'style-mismatch': 'app-context',
	'delivery-privacy': 'delivery-privacy',
	uncertain: 'uncertain',
};

/** A result's problems: v2 as answered; a v1 result as its one diagnosis. */
export function issuesOf(result: FixResult): FixIssue[] {
	if (result.issues?.length) return result.issues;
	return [
		{
			layer: LAYER_OF_DIAGNOSIS[result.diagnosis ?? 'uncertain'] ?? 'uncertain',
			summary: result.explanation,
			noFixReason: result.noFixReason ?? null,
		},
	];
}

/** The issue a fix or a dropped note belongs to (v1: the one issue). */
export function issueOf(item: { issue?: number }): number {
	return item.issue ?? 0;
}

export async function runFix({
	evidence,
	feedback,
	wanted = null,
	review,
	provider,
	complete,
	signal,
	onRequest,
}: {
	evidence: DictationEvidence;
	feedback: string;
	/** What the person says it should have said (optional, their words). */
	wanted?: string | null;
	review: Omit<ReviewContext, 'evidence' | 'wanted' | 'layers'> & { revision: string };
	provider: { apiKey: string; model: string; fetch?: typeof fetch };
	complete: (args: CompleteArgs) => Promise<CompleteResult>;
	signal?: AbortSignal;
	/** Sees exactly what is sent (tests assert the payload). */
	onRequest?: (request: { system: string; data: FixRequestDataV2; user: string }) => void;
}): Promise<FixOutcome> {
	if (!feedback.trim()) return { ok: false, message: 'Say or type what should have been different.' };
	if (!provider.apiKey) return { ok: false, message: 'Add an API key in Settings first.' };
	const request = buildFixRequestV2({
		evidence,
		feedback,
		wanted,
		snapshot: review.snapshot,
		...(review.deviceStyles ? { deviceStyles: review.deviceStyles } : {}),
	});
	onRequest?.(request);
	const reply = await complete({
		apiKey: provider.apiKey,
		model: provider.model,
		system: request.system,
		user: request.user,
		signal,
		fetch: provider.fetch,
		schema: FIX_RESPONSE_SCHEMA_V2 as unknown as Record<string, unknown>,
		schemaName: 'voiceflow_fix_v2',
	});
	if (!reply.ok) return reply;
	const parsed = parseFixResponseV2(reply.text, request.data.delivered_text, request.data.wanted_text);
	if (!parsed.ok) return { ok: false, message: `${parsed.message} Nothing was changed.` };
	const issues: FixIssue[] = parsed.value.issues.map((i) => ({ ...i }));
	const wantedText = request.data.wanted_text;
	// Fixes are checked against the speaker's whole text, not the copy sent.
	const wantedFull = wantedText !== null && wanted ? wanted.replace(/\r\n?/g, '\n') : null;
	const { fixes, dropped } = reviewProposals(parsed.value.proposals, {
		...review,
		evidence,
		wanted: wantedFull,
		layers: issues.map((i) => i.layer),
	});
	for (const code of parsed.value.dropped) dropped.push({ reason: DROP_REASONS[code] });
	return {
		ok: true,
		result: {
			contract: 2,
			explanation: parsed.value.explanation,
			// The speaker's own words, in full, are the truth; the model's version stays a suggestion.
			correctedText: wantedFull ?? parsed.value.correctedText,
			...(wantedText !== null ? { suggestedText: parsed.value.correctedText } : {}),
			issues,
			fixes,
			dropped,
			proposals: parsed.value.proposals,
			reviewedAgainst: review.revision,
			observations: request.data.observations,
			observationsTruncated: request.data.observations_truncated,
		},
	};
}

export const DIAGNOSIS_LABELS: Record<Diagnosis, string> = {
	mishearing: 'Misheard',
	'tidy-up-rewrite': 'Tidy-up changed it',
	'style-mismatch': 'Wrong style for this app',
	'delivery-privacy': 'Paste or privacy',
	uncertain: 'Not sure',
};

/** v2: the layer that failed, in plain words. */
export const LAYER_LABELS: Record<Layer, string> = {
	transcriber: 'Misheard',
	spelling: 'A saved spelling changed it',
	expression: 'Too formal or too lively',
	'app-context': 'Wrong style for this app',
	'style-prompt': 'The style itself',
	'delivery-privacy': 'Paste or privacy',
	uncertain: 'Not sure',
};

/** What an issue with no fix says, by layer. */
export function noFixNote(issue: FixIssue): string {
	switch (issue.layer) {
		case 'style-prompt':
			return 'Changing the style itself from feedback comes in a later update. This is noted.';
		case 'delivery-privacy':
			return 'Noted. Paste and privacy problems don’t change your word lists or styles.';
		case 'uncertain':
			return 'VoiceFlow isn’t sure what went wrong here, so nothing lasting is suggested.';
		default:
			return `No lasting fix suggested${issue.noFixReason ? `: ${issue.noFixReason}` : '.'}`;
	}
}
