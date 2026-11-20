/**
 * VoiceFlow 0.9.11, warnings audit quick wins: fewer toasts, nothing that
 * stays until closed, one-click clearing. These modules import `$lib` paths
 * that `bun test` cannot resolve, so the source is checked.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const src = (...path: string[]) =>
	readFileSync(join(import.meta.dir, '..', '..', '..', ...path), 'utf8').replace(/\r\n/g, '\n');
const actions = src('lib', 'query', 'isomorphic', 'actions.ts');
const delivery = src('lib', 'query', 'isomorphic', 'delivery.ts');
const notify = src('lib', 'query', 'isomorphic', 'notify.ts');
const osNotify = src('lib', 'services', 'isomorphic', 'notifications', 'desktop.ts');
const layout = src('routes', '+layout.svelte');
const home = src('routes', '(app)', '+page.svelte');
const controller = src('lib', 'bar', 'controller.ts');
const rust = src('..', 'src-tauri', 'src', 'lib.rs');

describe('warnings quick wins (0.9.11)', () => {
	test('F2: with the bar on, no routine toasts', () => {
		for (const title of ["'Starting…'", "title: 'Listening'", "'Dictation cancelled'", "'Turning your words into text…'", 'title: completionTitle']) {
			const at = actions.indexOf(title);
			expect(at).toBeGreaterThan(-1);
			expect(actions.slice(Math.max(0, at - 160), at)).toContain('if (routineToasts())');
		}
		expect(actions).toContain("return !settings.value['bar.show'];");
		const pasted = delivery.slice(delivery.indexOf("case 'pasted':"), delivery.indexOf("case 'not-pasted':"));
		expect(pasted.match(/if \(!settings\.value\['bar\.show'\]\)/g)).toHaveLength(2);
		expect(layout).toContain('closeButton: false,');
	});

	test('F3c: problem toasts hide by themselves', () => {
		expect(actions).not.toMatch(/notify\.error\.execute\(\{[^}]*?persist: true/s);
		expect(delivery).toContain('persist: false,');
		expect(delivery).toContain('`paste-${recordingId}`');
	});

	test('F3a/b: dismissing a problem anywhere closes its toasts', () => {
		const dismiss = actions.slice(actions.indexOf('async dismiss(recordingId: string)'));
		expect(dismiss.slice(0, 400)).toContain("for (const prefix of ['tidy-up-', 'transcribe-', 'paste-'])");
		expect(controller).toContain("case 'dismiss':\n\t\t\t\treturn handlers.dismiss?.(id, kind);");
		expect(actions).toContain('? recovery.dismiss(recordingId)');
	});

	test('F4a: Clear all on Needs you', () => {
		expect(home).toContain('recovery.dismissAll(problems.map((p) => p.recording.id))');
		expect(home).toContain('>Clear all</button>');
	});

	test('F1c: no Windows toasts for dictations; no failing removal call', () => {
		expect(notify).toContain("(variant === 'error' || variant === 'warning') && fullOptions.os");
		expect(osNotify).not.toContain('await removeNotificationById(id);\n');
	});

	test('F5: Nothing heard removes its audio when kept audio is off, with no extra toast', () => {
		const empty = actions.slice(actions.indexOf('if (!transcribedText.trim())'), actions.indexOf('const transformationId = styleIdFor(overrides);'));
		expect(empty).toContain('await removeAudioIfOff(recording.id);');
		expect(empty).not.toContain('notify.info');
	});

	test('F9: tao logs errors only', () => {
		expect(rust).toContain('.level_for("tao", log::LevelFilter::Error)');
	});
});
