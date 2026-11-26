/**
 * VoiceFlow A2 + B: which styles the recording bar offers for one dictation,
 * and which one it starts with. Pure, so it is unit tested.
 *
 * - With word lists on, the styles come from the snapshot pinned when the
 *   dictation started: the word lists' styles first (`profile:<id>`), then
 *   the styles made on this PC, then Raw.
 * - The starting style: the word lists' rule for the app in front, when one
 *   names a style it has (or `raw`); else the default style, which is the
 *   word lists' default when they set one, else this PC's.
 * - 0.9.21: the app's rule is the one in force: the person's own setting
 *   for the app (Settings > Tidy-up > Style for each app, or an approved
 *   feedback fix: `app-style` edits) over the word lists' rule, field by
 *   field. It may name a built-in style or one made on this PC
 *   (`profile/style-refs.ts`). A style picked on the bar still wins for
 *   that dictation.
 */
import type { TargetSnapshot } from '$lib/privacy/policy';
import { profileStyleIdOf, profileStyleKey } from '../profile/ownership';
import type { ProfileSnapshot } from '$lib/profile/types';
import { builtInTitle, resolveStyleRef } from '../profile/style-refs';
import { RAW_STYLE_ID, type BarStyleOption } from './contract';
import { presetKindOf, presetKindOfProfileStyle } from './expression';

export const RAW_OPTION: BarStyleOption = { id: RAW_STYLE_ID, name: 'Raw', kind: 'raw' };

/**
 * The ids an app rule may name for this app, lower case: the Windows exe
 * name and the macOS bundle id (macOS fills in both; the word lists use the
 * bundle id there).
 */
export function targetAppIds(target: Pick<TargetSnapshot, 'exeName' | 'bundleId'>): string[] {
	return [target.exeName, target.bundleId]
		.filter((id): id is string => !!id)
		.map((id) => id.toLowerCase());
}

/** The word lists' styles as bar options (0.9.13: a hidden one is left out). */
export function profileStyleOptions(snapshot: ProfileSnapshot | null): BarStyleOption[] {
	return (snapshot?.styles ?? []).filter((style) => style.hidden !== true).map((style) => ({
		id: profileStyleKey(style.id),
		name: style.label || 'Untitled style',
		kind: presetKindOfProfileStyle(style),
		...(typeof style.expression === 'number' ? { expression: style.expression } : {}),
	}));
}

/**
 * The style an app rule's reference names, as the bar names it: `raw`, a
 * `profile:<id>` the snapshot has, or (0.9.21) a built-in or this PC's own
 * style. Null: no reference, or a style that isn't here.
 *
 * `deviceStyles` null means this PC's styles are still loading: a built-in
 * or PC reference then keeps its id, so the dictation still starts in it.
 */
export function ruleStyleId(
	ref: string | undefined,
	snapshot: ProfileSnapshot | null,
	deviceStyles: readonly Pick<BarStyleOption, 'id'>[] | null,
): string | null {
	if (!ref) return null;
	const found = resolveStyleRef(ref, {
		profileStyleIds: (snapshot?.styles ?? []).map((s) => s.id),
		deviceStyleIds: deviceStyles ? deviceStyles.map((s) => s.id) : null,
	});
	return found.status === 'missing' ? null : found.barId;
}

/**
 * The style id the app rule in force gives this app (see `ruleStyleId`), or
 * null. Word lists only, unless `deviceStyles` is given (then built-in and
 * PC references count too).
 */
export function appRuleStyleId(
	snapshot: ProfileSnapshot | null,
	appIds: string[],
	deviceStyles: readonly Pick<BarStyleOption, 'id'>[] | null = [],
	local: ReadonlyMap<string, AppRule> | null = null,
): string | null {
	return ruleStyleId(appRuleFor(snapshot, appIds, local)?.style, snapshot, deviceStyles);
}

/**
 * 0.9.13 (v2): the word lists' pinned styles, as bar style ids, in order
 * (`defaults.pinnedStyles`); empty when they pin none.
 */
export function profilePinnedIds(snapshot: ProfileSnapshot | null): string[] {
	return (snapshot?.defaults.pinnedStyles ?? [])
		.filter((id) => snapshot?.styles.some((s) => s.id === id && s.hidden !== true))
		.map(profileStyleKey);
}

/** An app rule's fields that phase F reads (style and expression). */
export type AppRule = { style?: string; expression?: number };

/**
 * VoiceFlow phase F: the app rule for this app: from the pinned word lists
 * (which merge approved feedback fixes in), or with word lists off from this
 * device's own edits.
 */
export function appRuleFor(
	snapshot: ProfileSnapshot | null,
	appIds: string[],
	local: ReadonlyMap<string, AppRule> | null,
): AppRule | null {
	if (appIds.length === 0) return null;
	const wanted = appIds.map((id) => id.toLowerCase());
	if (!snapshot) {
		for (const id of wanted) {
			const rule = local?.get(id);
			if (rule) return rule;
		}
		return null;
	}
	for (const rule of snapshot.apps) {
		if (!rule || typeof rule !== 'object') continue;
		const { id, style, expression } = rule as { id?: unknown; style?: unknown; expression?: unknown };
		if (typeof id !== 'string' || !wanted.includes(id.toLowerCase())) continue;
		return {
			...(typeof style === 'string' ? { style } : {}),
			...(typeof expression === 'number' && Number.isInteger(expression) && expression >= 0 && expression <= 4
				? { expression }
				: {}),
		};
	}
	return null;
}

/**
 * Gives the starting style the app rule's expression, when the rule has one
 * and names that style (or no style): the bar shows it and tidy-up uses it
 * unless the person picks another level for this dictation.
 */
export function withAppExpression(
	choice: BarStyleChoice,
	rule: AppRule | null,
	snapshot: ProfileSnapshot | null = null,
	deviceStyles: readonly Pick<BarStyleOption, 'id'>[] | null = null,
): BarStyleChoice {
	if (!rule || rule.expression === undefined || choice.style.id === RAW_STYLE_ID) return choice;
	// 0.9.21: the rule's style as the bar names it (a built-in or PC style too).
	if (rule.style !== undefined) {
		const named =
			ruleStyleId(rule.style, snapshot, deviceStyles) ??
			(snapshot ? null : profileStyleKey(rule.style));
		if (named !== choice.style.id) return choice;
	}
	const style = { ...choice.style, appExpression: rule.expression };
	return {
		style,
		styles: choice.styles.map((s) => (s.id === style.id ? style : s)),
	};
}

/**
 * The default style id from the pinned snapshot when its word lists set one
 * (null: Raw), else this PC's default.
 */
export function defaultStyleId(
	snapshot: ProfileSnapshot | null,
	deviceDefaultId: string | null,
): string | null {
	const fromLists = snapshot?.defaults.cleanupStyle;
	if (fromLists) return fromLists === RAW_STYLE_ID ? null : profileStyleKey(fromLists);
	return deviceDefaultId;
}

function hiddenOption(snapshot: ProfileSnapshot | null, id: string): BarStyleOption | undefined {
	const styleId = profileStyleIdOf(id);
	const style = styleId ? snapshot?.styles.find((s) => s.id === styleId) : undefined;
	if (!style) return undefined;
	return {
		id,
		name: style.label || 'Untitled style',
		kind: presetKindOfProfileStyle(style),
		...(typeof style.expression === 'number' ? { expression: style.expression } : {}),
	};
}

function builtInOption(id: string): BarStyleOption | undefined {
	const name = builtInTitle(id);
	return name ? { id, name, kind: presetKindOf(name) } : undefined;
}

export type BarStyleChoice = {
	/** The style this dictation starts with. */
	style: BarStyleOption;
	/** Every style to offer, Raw last. */
	styles: BarStyleOption[];
};

export function barStyleChoice({
	target,
	snapshot,
	deviceStyles,
	deviceDefaultId,
	appRule,
	deviceStylesLoaded = true,
}: {
	target: Pick<TargetSnapshot, 'exeName' | 'bundleId'>;
	/** The snapshot pinned at record start (null: word lists off). */
	snapshot: ProfileSnapshot | null;
	/** The styles made on this PC. */
	deviceStyles: BarStyleOption[];
	/** `transformations.selectedTransformationId` as this PC stores it. */
	deviceDefaultId: string | null;
	/**
	 * 0.9.21: the app rule in force (`appRuleFor`: the word lists' rule with
	 * this person's own app settings over it; with word lists off, this
	 * device's own). Absent: the snapshot's rule.
	 */
	appRule?: AppRule | null;
	/** False while this PC's styles are loading (a built-in rule keeps its id). */
	deviceStylesLoaded?: boolean;
}): BarStyleChoice {
	const listed = [
		...profileStyleOptions(snapshot),
		...deviceStyles.filter((s) => s.id !== RAW_STYLE_ID && profileStyleIdOf(s.id) === null),
	];
	// Precedence (0.9.21): the app's rule (its own setting over the word
	// lists'), then the default. A style picked on the bar wins over both,
	// in the bar itself. A rule naming a style that isn't here is skipped.
	const rule = appRule !== undefined ? appRule : appRuleFor(snapshot, targetAppIds(target), null);
	const id =
		ruleStyleId(rule?.style, snapshot, deviceStylesLoaded ? deviceStyles : null) ??
		defaultStyleId(snapshot, deviceDefaultId);
	let style: BarStyleOption;
	if (!id || id === RAW_STYLE_ID) style = RAW_OPTION;
	else
		style =
			listed.find((s) => s.id === id) ??
			// 0.9.13: a hidden word-list style named by the default or the
			// app's rule still starts the dictation, under its own name.
			hiddenOption(snapshot, id) ??
			// 0.9.21: a built-in not loaded yet, under its own name.
			builtInOption(id) ??
			// Not loaded yet (or renamed meanwhile): keep the id, so the
			// dictation is still tidied with it; only the chip's name is generic.
			({ id, name: 'Default style', kind: 'custom' } satisfies BarStyleOption);
	const styles = listed.some((s) => s.id === style.id) || style.id === RAW_STYLE_ID
		? listed
		: [style, ...listed];
	return { style, styles: [...styles, RAW_OPTION] };
}
