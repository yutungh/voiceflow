/**
 * VoiceFlow phase F: what the feedback UI can ask the engine (main window)
 * to do. History calls the service directly; the feedback window calls it
 * over events (`rpc.ts`). Every call names the dictation or the feedback
 * record it is about.
 */
import type { FeedbackRecord } from './log';
import type { Selection } from './validate';

export type FeedbackSubjectView = {
	recordingId: string;
	appName: string | null;
	at: string;
	styleName: string | null;
	deliveredText: string;
	rawText: string;
	/** The dictation has an app to paste back into. */
	canPaste: boolean;
};

export type FeedbackSetup = {
	subject: FeedbackSubjectView | null;
	provider: string;
	model: string;
	/** "Sends this dictation's text … to OpenAI." */
	disclosure: string;
	/** A key is set for the provider. */
	ready: boolean;
};

export type Reply<T> = ({ ok: true } & T) | { ok: false; message: string; record?: FeedbackRecord; stale?: boolean };

export type FeedbackRequest =
	| { op: 'setup'; recordingId: string }
	| { op: 'saveDraft'; recordingId: string; feedback: string; recordId?: string }
	| {
			op: 'submit';
			recordingId: string;
			feedback: string;
			recordId?: string;
			/** The provider and model the person was shown; a change is refused. */
			expect?: { provider: string; model: string };
	  }
	| { op: 'apply'; recordId: string; selections: Selection[] }
	| { op: 'undo'; recordId: string; opId: string }
	| { op: 'copy'; recordId: string; text: string }
	| { op: 'paste'; recordId: string; text: string };

export interface FeedbackClient {
	setup(recordingId: string): Promise<Reply<{ setup: FeedbackSetup }>>;
	saveDraft(a: { recordingId: string; feedback: string; recordId?: string }): Promise<Reply<{ record: FeedbackRecord }>>;
	submit(a: {
		recordingId: string;
		feedback: string;
		recordId?: string;
		expect?: { provider: string; model: string };
	}): Promise<Reply<{ record: FeedbackRecord }>>;
	apply(a: { recordId: string; selections: Selection[] }): Promise<Reply<{ record: FeedbackRecord }>>;
	undo(a: { recordId: string; opId: string }): Promise<Reply<{ record: FeedbackRecord }>>;
	copy(a: { recordId: string; text: string }): Promise<Reply<{ message: string; record: FeedbackRecord }>>;
	paste(a: { recordId: string; text: string }): Promise<Reply<{ message: string; record: FeedbackRecord }>>;
}

export const FEEDBACK_REQUEST_EVENT = 'feedback://request';
export const FEEDBACK_RESPONSE_EVENT = 'feedback://response';
