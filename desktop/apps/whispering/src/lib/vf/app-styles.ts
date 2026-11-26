/**
 * VoiceFlow 0.9.21: Settings > Tidy-up > Style for each app, the pure part
 * (unit tested): which apps have a starting style, what each row says, which
 * styles can be chosen, the apps to add from History, and a typed program
 * name.
 *
 * The setting is the app's `app-style` edits (the same edits an approved
 * feedback fix writes), so it is personal and part of the word lists'
 * edits; it stays on this PC until every device of the person reads it
 * (`UPLOADABLE_OP_TYPES`). Over the word lists' own rule for the app, field
 * by field: the person's setting wins.
 */
import { EXPRESSION_LABELS } from '../bar/expression';
import { validRuleId } from '../profile/ops';
import {
	RAW_REF,
	builtInTitle,
	decodePcStyleRef,
	isBuiltInRef,
	resolveStyleRef,
	styleRefOf,
} from '../profile/style-refs';
import type { ProfileSnapshot } from '../profile/types';

/** One style the setting can choose. */
export type StyleChoice = {
	/** What the edit stores (`style-refs.ts`). */
	ref: string;
	/** As the bar names it. */
	barId: string;
	label: string;
	group: 'lists' | 'builtin' | 'pc' | 'raw';
	/** The style's own level, when its word list sets one. */
	ownLevel?: number;
};

export type DeviceStyle = { id: string; title: string };

/**
 * Every style a rule can name, the word lists' first, then the built-ins,
 * then the rest made on this PC, then Raw. A built-in the word lists shadow
 * (same id) is left out: the lists' one is what the reference finds.
 */
export function styleChoices(snapshot: ProfileSnapshot | null, device: readonly DeviceStyle[]): StyleChoice[] {
	const lists: StyleChoice[] = (snapshot?.styles ?? [])
		.filter((s) => s.hidden !== true && validRuleId(s.id))
		.map((s) => ({
			ref: s.id,
			barId: `profile:${s.id}`,
			label: s.label || 'Untitled style',
			group: 'lists',
			...(typeof s.expression === 'number' ? { ownLevel: s.expression } : {}),
		}));
	// Every word-list id, hidden ones too: a reference finds theirs first.
	const taken = new Set((snapshot?.styles ?? []).map((s) => s.id));
	const own: StyleChoice[] = [];
	for (const s of device) {
		const ref = styleRefOf(s.id);
		if (!ref || ref === RAW_REF || taken.has(ref)) continue;
		taken.add(ref);
		own.push({
			ref,
			barId: s.id,
			label: s.title || 'Untitled style',
			group: isBuiltInRef(ref) ? 'builtin' : 'pc',
		});
	}
	return [
		...lists,
		...own.filter((c) => c.group === 'builtin'),
		...own.filter((c) => c.group === 'pc'),
		{ ref: RAW_REF, barId: RAW_REF, label: 'Raw (no tidy-up)', group: 'raw' },
	];
}

export const GROUP_LABELS: Record<StyleChoice['group'], string> = {
	lists: 'From your word lists',
	builtin: 'Built in',
	pc: 'Made on this PC',
	raw: 'No tidy-up',
};

/** An app rule as the setting reads it (merged snapshot, or this PC's edits). */
export type RuleIn = {
	app: string;
	style?: string;
	expression?: number;
	/** The edits in force for the app (empty: only the word lists' rule). */
	editIds: string[];
	/** The word lists' own rule under the edits (null: none). */
	listsRule: { style?: string; expression?: number } | null;
	/** The level in force is the word lists' (the person's own setting gives none). */
	levelFromLists?: boolean;
};

/** The app rules in force: from the merged word lists, or with them off this PC's edits. */
export function rulesIn(
	snapshot: ProfileSnapshot | null,
	local: ReadonlyMap<string, { style?: string; expression?: number; editIds?: string[] }>,
): RuleIn[] {
	if (!snapshot) {
		return [...local.entries()].map(([app, r]) => ({
			app,
			...(r.style !== undefined ? { style: r.style } : {}),
			...(r.expression !== undefined ? { expression: r.expression } : {}),
			editIds: r.editIds ?? [],
			listsRule: null,
		}));
	}
	const out: RuleIn[] = [];
	for (const raw of snapshot.apps) {
		if (!raw || typeof raw !== 'object') continue;
		const r = raw as Record<string, unknown>;
		if (typeof r.id !== 'string') continue;
		const style = typeof r.style === 'string' ? r.style : undefined;
		const expression = typeof r.expression === 'number' ? r.expression : undefined;
		// A rule with neither (words only) is not a starting style.
		if (style === undefined && expression === undefined) continue;
		const editIds = Array.isArray(r.editIds)
			? r.editIds.filter((id): id is string => typeof id === 'string')
			: typeof r.editId === 'string'
				? [r.editId]
				: [];
		const under = r.listsRule && typeof r.listsRule === 'object' ? (r.listsRule as Record<string, unknown>) : null;
		const listsRule =
			editIds.length === 0
				? { ...(style !== undefined ? { style } : {}), ...(expression !== undefined ? { expression } : {}) }
				: under && (typeof under.style === 'string' || typeof under.expression === 'number')
					? {
							...(typeof under.style === 'string' ? { style: under.style } : {}),
							...(typeof under.expression === 'number' ? { expression: under.expression } : {}),
						}
					: null;
		const app = r.id.toLowerCase();
		// The level comes from the word lists' rule when no edit in force set
		// it ("Automatic" over a list level): the merge names the edit that
		// did (`expressionEditId`), another device's cached edit included.
		const levelFromLists =
			editIds.length > 0 && expression !== undefined && typeof r.expressionEditId !== 'string';
		out.push({
			app,
			...(style !== undefined ? { style } : {}),
			...(expression !== undefined ? { expression } : {}),
			editIds,
			listsRule,
			...(levelFromLists ? { levelFromLists } : {}),
		});
	}
	return out;
}

export type AppRow = {
	app: string;
	/** The app's name from History, else its program name. */
	name: string;
	/** The style reference in force (undefined: the rule sets only a level). */
	ref?: string;
	/** The style's name ("Default style" when the rule names none). */
	styleLabel: string;
	/** `missing`: the style isn't on this PC or in the lists; the default is used. */
	status: 'ok' | 'pending' | 'missing';
	/** The level the rule sets, or null (Automatic). */
	level: number | null;
	/** What Automatic means here, in words ("" when it adds nothing). */
	autoNote: string;
	/** The same, as a level name only ("Subtle"), for the closed level box. */
	autoLevel: string;
	/** Whose setting it is. */
	source: 'mine' | 'lists';
	/** The plain sentence: "Telegram starts in Friends". */
	summary: string;
	/** The remove button's words, or null when there is nothing to remove. */
	removeLabel: string | null;
	/** What removing brings back, in words. */
	afterRemove: string;
	/** The edits a removal retires. */
	editIds: string[];
};

const levelName = (n: number) => EXPRESSION_LABELS[n] ?? String(n);

/** A program name without its `.exe`, capitalised: the name when History has none. */
export function nameFromId(app: string): string {
	const base = app.replace(/\.exe$/i, '');
	const last = base.includes('.') && !/\.exe$/i.test(app) ? (base.split('.').pop() ?? base) : base;
	return last ? last[0]!.toUpperCase() + last.slice(1) : app;
}

export function appRows({
	rules,
	snapshot,
	device,
	deviceLoaded,
	names,
	defaultLabel,
}: {
	rules: readonly RuleIn[];
	snapshot: ProfileSnapshot | null;
	device: readonly DeviceStyle[];
	deviceLoaded: boolean;
	/** App id to the name History knows it by. */
	names: ReadonlyMap<string, string>;
	/** The default style's name ("Raw" when none). */
	defaultLabel: string;
}): AppRow[] {
	const choices = styleChoices(snapshot, device);
	const labelOf = (ref: string) =>
		choices.find((c) => c.ref === ref)?.label ??
		snapshot?.styles.find((s) => s.id === ref)?.label ??
		device.find((d) => styleRefOf(d.id) === ref)?.title ??
		builtInTitle(ref) ??
		ref;
	const rows = rules.map((rule): AppRow => {
		const name = names.get(rule.app) ?? nameFromId(rule.app);
		const source: AppRow['source'] = rule.editIds.length > 0 ? 'mine' : 'lists';
		let status: AppRow['status'] = 'ok';
		let styleLabel = defaultLabel;
		if (rule.style !== undefined) {
			const found = resolveStyleRef(rule.style, {
				profileStyleIds: (snapshot?.styles ?? []).map((s) => s.id),
				deviceStyleIds: deviceLoaded ? device.map((d) => d.id) : null,
			});
			status = found.status;
			styleLabel = rule.style === RAW_REF ? 'Raw' : labelOf(rule.style);
		}
		const own = choices.find((c) => c.ref === rule.style)?.ownLevel;
		const isRaw = rule.style === RAW_REF;
		// A level the person's own setting doesn't give is Automatic, even
		// when the word lists' rule under it sets one.
		const inForce = rule.expression ?? null;
		const level = rule.levelFromLists ? null : inForce;
		const autoNote =
			isRaw || level !== null
				? ''
				: rule.levelFromLists && inForce !== null
					? `${levelName(inForce)}, from your word lists`
					: typeof own === 'number'
						? `the style’s own level, ${levelName(own)}`
						: '';
		const autoLevel =
			isRaw || level !== null
				? ''
				: rule.levelFromLists && inForce !== null
					? levelName(inForce)
					: typeof own === 'number'
						? levelName(own)
						: '';
		// A style made on another PC has no name here.
		const unnamedPc = rule.style !== undefined && styleLabel === rule.style && decodePcStyleRef(rule.style) !== null;
		const startsIn =
			status === 'missing'
				? unnamedPc
					? `${name} starts in your default style (${defaultLabel}): its style was made on another PC`
					: `${name} starts in your default style (${defaultLabel}): ${styleLabel} isn’t on this PC`
				: rule.style === undefined
					? `${name} starts in your default style (${defaultLabel})`
					: `${name} starts in ${styleLabel}`;
		const levelPart =
			inForce !== null && !isRaw && status !== 'missing'
				? `, ${levelName(inForce)}${rule.levelFromLists ? ' (level from your word lists)' : ''}`
				: '';
		const summary = `${startsIn}${levelPart}`;
		const under = rule.listsRule;
		const afterRemove =
			source === 'lists'
				? ''
				: under?.style !== undefined
					? `Back to your word lists’ choice: ${under.style === RAW_REF ? 'Raw' : labelOf(under.style)}`
					: `Back to your default style (${defaultLabel})`;
		return {
			app: rule.app,
			name,
			...(rule.style !== undefined ? { ref: rule.style } : {}),
			styleLabel,
			status,
			level,
			autoNote,
			autoLevel,
			source,
			summary,
			removeLabel: source === 'lists' ? null : under ? 'Restore word-list rule' : 'Remove',
			afterRemove,
			editIds: rule.editIds,
		};
	});
	return rows.sort((a, b) => a.name.localeCompare(b.name));
}

/** The level options: Automatic, then the five levels by name. */
export function levelOptions(): { value: string; label: string }[] {
	return [
		{ value: 'auto', label: 'Automatic' },
		...EXPRESSION_LABELS.map((label, i) => ({ value: String(i), label })),
	];
}

export type RecentApp = { id: string; name: string };

/**
 * Apps recently dictated into, newest first, once each, without the ones
 * already listed: the id a rule is keyed by (the macOS bundle id when there
 * is one, else the exe name, lower case) and the name History shows.
 */
export function recentApps(
	recordings: readonly {
		appName?: string | null;
		appExe?: string | null;
		appBundleId?: string | null;
		createdAt: string;
		deletedAt?: string | null;
	}[],
	listed: ReadonlySet<string>,
	max = 12,
): RecentApp[] {
	const seen = new Set<string>();
	const out: RecentApp[] = [];
	const sorted = [...recordings].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
	for (const r of sorted) {
		if (r.deletedAt) continue;
		const id = (r.appBundleId || r.appExe || '').toLowerCase();
		if (!id || !validRuleId(id) || seen.has(id) || listed.has(id)) continue;
		// VoiceFlow itself is never a dictation's target app.
		if (id === 'voiceflow.exe' || id === 'com.voiceflowkeyboard.desktop') continue;
		seen.add(id);
		out.push({ id, name: r.appName?.trim() || nameFromId(id) });
		if (out.length >= max) break;
	}
	return out;
}

/** App id to the name History knows it by (newest name wins). */
export function appNames(
	recordings: readonly { appName?: string | null; appExe?: string | null; appBundleId?: string | null; createdAt: string }[],
): Map<string, string> {
	const names = new Map<string, string>();
	const sorted = [...recordings].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
	for (const r of sorted) {
		const name = r.appName?.trim();
		if (!name) continue;
		for (const id of [r.appExe, r.appBundleId]) if (id) names.set(id.toLowerCase(), name);
	}
	return names;
}

/**
 * A typed program name as an app id: trimmed and lower case; on Windows a
 * name without a dot gets `.exe` ("telegram" is `telegram.exe`).
 */
export function programId(input: string, windows: boolean): { ok: true; id: string } | { ok: false; message: string } {
	let id = input.trim().toLowerCase();
	if (!id) return { ok: false, message: windows ? 'Type the program’s name, for example telegram.exe.' : 'Type the app’s bundle id, for example com.tinyspeck.slackmacgap.' };
	if (windows && !id.includes('.')) id = `${id}.exe`;
	if (!validRuleId(id))
		return {
			ok: false,
			message: windows
				? 'Use the program’s file name, like telegram.exe: letters, numbers, dots, dashes and underscores only.'
				: 'Use the app’s bundle id, like com.example.app: letters, numbers, dots, dashes and underscores only.',
		};
	return { ok: true, id };
}
