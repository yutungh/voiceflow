/**
 * VoiceFlow word lists (plan v2, B + C2): what the Rust profile service
 * (`src-tauri/src/profile/`) sends the webview. The file formats themselves
 * are documented in `pack/README.md`.
 */

/** Precedence, lowest first: default < household < generated < personal < edit. */
export type ProfileLayer =
	| 'default'
	| 'household'
	| 'generated'
	| 'personal'
	| 'edit';

export type ProfileTerm = {
	id: string;
	text: string;
	heardAs: string[];
	kind?: string;
	gloss?: string;
	priority: number;
	/** ["*"] or lower-case app ids. */
	apps: string[];
	layer: ProfileLayer;
	device?: string;
	createdAt?: string;
};

export type ProfileCorrection = {
	id: string;
	from: string[];
	to: string;
	scope: { apps: string[] };
	layer: ProfileLayer;
	note?: string;
	device?: string;
	createdAt?: string;
};

export type ProfileStyle = {
	id: string;
	label: string;
	prompt: string;
	model?: string;
	/** The style's default level, 0-4. */
	expression?: number;
	layer: ProfileLayer;
	// 0.9.13: voiceflow-profile v2 fields (absent when unset).
	/** A model per tidy-up provider (openai, anthropic, google, groq, openrouter, xai, custom). */
	models?: Partial<Record<string, string>>;
	/** Added after the expression guidance, taking precedence over it. */
	expressionOverride?: string;
	/** A short icon (an emoji or a few characters). */
	icon?: string;
	/** Which shared expression guidance it follows (casual, business, ..., fun_*, custom). */
	kind?: string;
	/** The style's voice in a sentence or two (shown as its description). */
	guidance?: string;
	/** Not offered in pickers; still works where a default or an app rule names it. */
	hidden?: boolean;
	/** The style-set edits in force on it, oldest first. */
	editIds?: string[];
};

export type ProfileDefaults = {
	transcriptionModel?: string;
	language?: string;
	cleanupModel?: string;
	cleanupStyle?: string;
	cleanupTimeoutS?: number;
	promptChars?: number;
	promptTerms?: number;
	/** 0.9.13 (v2): the styles pinned to the bar, in order. */
	pinnedStyles?: string[];
	promptCharsEffective: number;
	promptTermsEffective: number;
	fromLayer: Record<string, ProfileLayer>;
};

export type ProfileIssue = {
	code: string;
	message: string;
	layer?: ProfileLayer;
	ids: string[];
};

export type ProfileSnapshot = {
	/** Changes when the merged content changes; pinned per dictation. */
	id: string;
	user: string;
	terms: ProfileTerm[];
	/** Lowest precedence first: for one heard form, a later one wins. */
	corrections: ProfileCorrection[];
	styles: ProfileStyle[];
	defaults: ProfileDefaults;
	privacy: { blockApps?: string[]; titleDenyRe?: string[] };
	apps: unknown[];
	issues: ProfileIssue[];
};

export type ProfileConfig = {
	enabled: boolean;
	source: string;
	user: string;
	assistant: string;
	device: string;
};

export type LayerInfo = {
	layer: ProfileLayer | null;
	state: 'ok' | 'cached' | 'offline' | 'missing';
	updatedAt?: string;
	error?: string;
	words: number;
	corrections: number;
};

export type SyncReport = {
	sourceReachable: boolean;
	attemptedAt: string;
	syncedAt?: string;
	usingCache: boolean;
	layers: LayerInfo[];
	pendingEdits: number;
	uploadedNow: number;
	errors: string[];
};

export type ProfileState = {
	config: ProfileConfig;
	snapshot: ProfileSnapshot | null;
	status: {
		refreshing: boolean;
		deferred: boolean;
		lastSyncAt?: string;
		report?: SyncReport;
	};
};
