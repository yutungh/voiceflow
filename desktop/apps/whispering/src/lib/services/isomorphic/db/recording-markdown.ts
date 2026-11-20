/**
 * VoiceFlow: the recording file format (`recordings/<id>.md`: YAML front
 * matter plus the transcript as the body). Pure, so it can be tested; the
 * file work is in `file-system.ts`. Whispering 7.x wrote the same format
 * with only the base fields, so its files read here as they are.
 */
import { type } from 'arktype';
import matter from 'gray-matter';
import type { Recording } from './models';

/**
 * Schema validator for Recording front matter (everything except transcribedText)
 */
export const RecordingFrontMatter = type({
	id: 'string',
	title: 'string',
	subtitle: 'string',
	timestamp: 'string',
	createdAt: 'string',
	updatedAt: 'string',
	transcriptionStatus: '"UNPROCESSED" | "TRANSCRIBING" | "DONE" | "FAILED"',
	// VoiceFlow (spec §4.3): the app dictated into and how it was authorized.
	'appName?': 'string',
	'appExe?': 'string',
	'appExePath?': 'string',
	'appBundleId?': 'string',
	'windowTitle?': 'string',
	'appWindowId?': 'string',
	'appPid?': 'number',
	'privacy?': '"allowed" | "allowed-no-context" | "held" | "sent-anyway"',
	'passwordCheck?': 'string',
	// VoiceFlow C1: what happened to the dictation (see models/recordings.ts).
	'durationMs?': 'number',
	'result?': '"pasted" | "copied" | "not-pasted" | "no-text"',
	'problemKind?': '"transcription" | "tidy-up" | "paste"',
	'problem?': 'string',
	'problemDismissed?': 'boolean',
	'finalText?': 'string',
	'styleName?': 'string',
	// 0.9.13: the language guard's held-back tidy-up (see models/recordings.ts).
	'heldBackText?': 'string',
	'heldBackReason?': 'string',
	'heldBackStyle?': 'string',
	'deletedAt?': 'string',
	'audioRemovedAt?': 'string',
});

export type RecordingFrontMatter = typeof RecordingFrontMatter.infer;

/**
 * Convert Recording to markdown format (frontmatter + body)
 */
export function recordingToMarkdown(recording: Recording): string {
	const { transcribedText, ...frontMatter } = recording;
	// YAML cannot hold undefined; optional fields without a value are left out.
	const defined = Object.fromEntries(
		Object.entries(frontMatter).filter(([, value]) => value !== undefined),
	);
	return matter.stringify(transcribedText ?? '', defined);
}

/**
 * Convert markdown file (YAML frontmatter + body) to Recording
 */
function markdownToRecording({
	frontMatter,
	body,
}: {
	frontMatter: RecordingFrontMatter;
	body: string;
}): Recording {
	return {
		...frontMatter,
		transcribedText: body,
	};
}

/**
 * Parses one recording file. Null when its front matter does not match the
 * schema (the caller skips it rather than failing the whole history).
 */
export function parseRecordingMarkdown(content: string): Recording | null {
	const { data, content: body } = matter(content);
	const frontMatter = RecordingFrontMatter(data);
	if (frontMatter instanceof type.errors) return null;
	return markdownToRecording({ frontMatter, body });
}
