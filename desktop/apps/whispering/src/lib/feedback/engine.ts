/**
 * VoiceFlow phase F: one feedback round. Evidence + feedback + related words
 * go to the person's configured model once; the reply is parsed against the
 * contract and every proposal is reviewed against the word lists. Nothing is
 * written here: lasting fixes are applied only when the person ticks them.
 */
import { parseFixResponse, type Diagnosis, type ModelProposal } from './contract';
import type { DictationEvidence } from './evidence';
import { buildFixRequest, type FixRequestData } from './prompt';
import type { CompleteArgs, CompleteResult } from './providers';
import { reviewProposals, type Dropped, type ReviewContext, type ReviewedFix } from './validate';

export type FixResult = {
	diagnosis: Diagnosis;
	explanation: string;
	correctedText: string;
	fixes: ReviewedFix[];
	dropped: Dropped[];
	noFixReason: string | null;
	/** The model's proposals as parsed, to review again if the lists change. */
	proposals: ModelProposal[];
	/** The word-list snapshot the fixes were reviewed against ('' when off). */
	reviewedAgainst: string;
};

export type FixOutcome = { ok: true; result: FixResult } | { ok: false; message: string };

export async function runFix({
	evidence,
	feedback,
	review,
	provider,
	complete,
	signal,
	onRequest,
}: {
	evidence: DictationEvidence;
	feedback: string;
	review: Omit<ReviewContext, 'evidence'> & { revision: string };
	provider: { apiKey: string; model: string; fetch?: typeof fetch };
	complete: (args: CompleteArgs) => Promise<CompleteResult>;
	signal?: AbortSignal;
	/** Sees exactly what is sent (tests assert the payload). */
	onRequest?: (request: { system: string; data: FixRequestData; user: string }) => void;
}): Promise<FixOutcome> {
	if (!feedback.trim()) return { ok: false, message: 'Say or type what should have been different.' };
	if (!provider.apiKey) return { ok: false, message: 'Add an API key in Settings first.' };
	const request = buildFixRequest({ evidence, feedback, snapshot: review.snapshot });
	onRequest?.(request);
	const reply = await complete({
		apiKey: provider.apiKey,
		model: provider.model,
		system: request.system,
		user: request.user,
		signal,
		fetch: provider.fetch,
	});
	if (!reply.ok) return reply;
	const parsed = parseFixResponse(reply.text, request.data.delivered_text);
	if (!parsed.ok) return { ok: false, message: `${parsed.message} Nothing was changed.` };
	const { fixes, dropped } = reviewProposals(parsed.value.proposals, { ...review, evidence });
	for (let i = 0; i < parsed.value.dropped; i++)
		dropped.push({
			reason:
				parsed.value.diagnosis === 'delivery-privacy'
					? 'Paste and privacy problems are noted, not turned into lasting changes.'
					: 'A suggestion didn’t match what VoiceFlow can safely change, so it was left out.',
		});
	return {
		ok: true,
		result: {
			diagnosis: parsed.value.diagnosis,
			explanation: parsed.value.explanation,
			correctedText: parsed.value.correctedText,
			fixes,
			dropped,
			noFixReason: parsed.value.noFixReason,
			proposals: parsed.value.proposals,
			reviewedAgainst: review.revision,
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
