/**
 * VoiceFlow phase F: what the feedback UI can ask the engine (main window)
 * to do. History calls the service directly; the feedback window calls it
 * over events (`rpc.ts`). Every call names the dictation or the feedback
 * record it is about.
 */
import type { FeedbackRecord } from './log';
import type { SettingsSection } from './fix-model';
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
	/** The provider and model Find fixes uses now ('' when none can be used). */
	provider: string;
	model: string;
	/** The model's plain name, e.g. "Claude Sonnet 5.5". */
	modelName: string;
	/** VoiceFlow 0.9.17: the one line under Find fixes, naming the provider and model. */
	disclosure: string;
	/** Everything that is sent, in full (shown folded). */
	details: string;
	/** A key is set for the provider. */
	ready: boolean;
	/** When not ready: why, and the Settings section where the key goes. */
	keyNeeded?: { message: string; section: SettingsSection };
};

export type Reply<T> = ({ ok: true } & T) | { ok: false; message: string; record?: FeedbackRecord; stale?: boolean };

export type FeedbackRequest =
	| { op: 'setup'; recordingId: string }
	| { op: 'saveDraft'; recordingId: string; feedback: string; wanted?: string; recordId?: string }
	| {
			op: 'submit';
			recordingId: string;
			feedback: string;
			/** 0.9.16: what it should have said (optional). */
			wanted?: string;
			recordId?: string;
			/** The provider and model the person was shown; a change is refused. */
			expect?: { provider: string; model: string };
	  }
	| { op: 'apply'; recordId: string; selections: Selection[] }
	| { op: 'undo'; recordId: string; opId: string }
	| { op: 'copy'; recordId: string; text: string }
	| { op: 'paste'; recordId: string; text: string }
	/** VoiceFlow 0.9.17: show the main window at a Settings section (to add a key). */
	| { op: 'openSettings'; section: SettingsSection };

export interface FeedbackClient {
	setup(recordingId: string): Promise<Reply<{ setup: FeedbackSetup }>>;
	saveDraft(a: {
		recordingId: string;
		feedback: string;
		wanted?: string;
		recordId?: string;
	}): Promise<Reply<{ record: FeedbackRecord }>>;
	submit(a: {
		recordingId: string;
		feedback: string;
		wanted?: string;
		recordId?: string;
		expect?: { provider: string; model: string };
	}): Promise<Reply<{ record: FeedbackRecord }>>;
	apply(a: { recordId: string; selections: Selection[] }): Promise<Reply<{ record: FeedbackRecord }>>;
	undo(a: { recordId: string; opId: string }): Promise<Reply<{ record: FeedbackRecord; message?: string }>>;
	copy(a: { recordId: string; text: string }): Promise<Reply<{ message: string; record: FeedbackRecord }>>;
	paste(a: { recordId: string; text: string }): Promise<Reply<{ message: string; record: FeedbackRecord }>>;
	openSettings(section: SettingsSection): Promise<Reply<Record<never, never>>>;
}

export const FEEDBACK_REQUEST_EVENT = 'feedback://request';
export const FEEDBACK_RESPONSE_EVENT = 'feedback://response';
