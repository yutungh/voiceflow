/**
 * VoiceFlow 0.9.21: how an app rule names its style.
 *
 * An app rule (`apps[].style` in the word lists, or an `app-style` edit) is a
 * reference in the word lists' id format (`^[a-z0-9][a-z0-9._-]{0,99}$`):
 *
 * - a word-list style's id, or `raw`, as before;
 * - a built-in style by its fixed id, `vf-builtin-<key>` (Friends is
 *   `vf-builtin-friends`): the same id it is added under on every PC, so it
 *   still names the style after the person edits or renames it;
 * - a style made on this PC: `vf-pc-` and the style's own id, each capital
 *   letter written as `.` and its lower case (the ids are nanoids, which
 *   never contain `.`, so this is lossless).
 *
 * The word lists come first, as everywhere: a word-list style with the same
 * id wins. A built-in or PC reference finds the style in this device's own
 * styles; a device without it falls back to the default style.
 *
 * Pure (relative imports), so it is unit tested with bun.
 */
import { PACK_BUILT_INS, BUILT_IN_STYLES } from '../vf/builtin-styles';

export const RAW_REF = 'raw';
export const PC_REF_PREFIX = 'vf-pc-';
const PROFILE_PREFIX = 'profile:';

const BUILT_INS = [...PACK_BUILT_INS, ...BUILT_IN_STYLES];

/** The built-in styles' fixed ids, the references an app rule may use. */
export const BUILT_IN_STYLE_REFS: readonly string[] = BUILT_INS.map((s) => s.id);

/** A built-in's name as it ships (the person's copy may be renamed). */
export function builtInTitle(ref: string): string | null {
	return BUILT_INS.find((s) => s.id === ref)?.title ?? null;
}

/** The word lists' id format (`validRuleId` in ops.ts, `valid_id` in Rust). */
const RULE_ID = /^[a-z0-9][a-z0-9._-]{0,99}$/;
/** A style id made on a PC that a reference can carry: nanoid's alphabet, at most 40 characters. */
const PC_STYLE_ID = /^[A-Za-z0-9_-]{1,40}$/;
/** An encoded body, canonical: no capital, and every `.` before a lower-case letter. */
const PC_BODY = /^(?:[a-z0-9_-]|\.[a-z])+$/;

export function isBuiltInRef(ref: string): boolean {
	return BUILT_IN_STYLE_REFS.includes(ref);
}

/** `vf-pc-<encoded>` for a PC style's id; null for an id a reference can't carry. */
export function encodePcStyleRef(id: string): string | null {
	if (!PC_STYLE_ID.test(id)) return null;
	const ref = PC_REF_PREFIX + id.replace(/[A-Z]/g, (c) => `.${c.toLowerCase()}`);
	return RULE_ID.test(ref) ? ref : null;
}

/** The PC style id a `vf-pc-` reference names; null unless it decodes canonically. */
export function decodePcStyleRef(ref: string): string | null {
	if (!ref.startsWith(PC_REF_PREFIX)) return null;
	const body = ref.slice(PC_REF_PREFIX.length);
	if (!PC_BODY.test(body)) return null;
	const id = body.replace(/\.([a-z])/g, (_, c: string) => c.toUpperCase());
	return PC_STYLE_ID.test(id) ? id : null;
}

/** Whether a reference names a style kept on the device (a built-in, or a PC style). */
export function isDeviceStyleRef(ref: string): boolean {
	return isBuiltInRef(ref) || decodePcStyleRef(ref) !== null;
}

/**
 * The reference an app rule stores for a style as the bar names it (`raw`,
 * `profile:<id>`, or this PC's style id). Null: a style a rule can't name.
 */
export function styleRefOf(barStyleId: string | null | undefined): string | null {
	if (!barStyleId) return null;
	if (barStyleId === RAW_REF) return RAW_REF;
	if (barStyleId.startsWith(PROFILE_PREFIX)) {
		const id = barStyleId.slice(PROFILE_PREFIX.length);
		return RULE_ID.test(id) ? id : null;
	}
	if (isBuiltInRef(barStyleId)) return barStyleId;
	return encodePcStyleRef(barStyleId);
}

export type RefLookup = {
	/** Every word-list style id in force (hidden ones too). */
	profileStyleIds: readonly string[];
	/** This PC's style ids; null while they are still loading. */
	deviceStyleIds: readonly string[] | null;
};

export type ResolvedRef =
	/** The style to start with, as the bar names it. */
	| { status: 'ok'; barId: string }
	/** This PC's styles aren't loaded yet: start with this id anyway. */
	| { status: 'pending'; barId: string }
	/** No such style here (deleted, or made on another device): use the default. */
	| { status: 'missing' };

/** Finds the style a reference names: the word lists first, then this PC. */
export function resolveStyleRef(ref: string, lookup: RefLookup): ResolvedRef {
	if (ref === RAW_REF) return { status: 'ok', barId: RAW_REF };
	if (lookup.profileStyleIds.includes(ref)) return { status: 'ok', barId: PROFILE_PREFIX + ref };
	const deviceId = isBuiltInRef(ref) ? ref : decodePcStyleRef(ref);
	if (!deviceId) return { status: 'missing' };
	if (lookup.deviceStyleIds === null) return { status: 'pending', barId: deviceId };
	return lookup.deviceStyleIds.includes(deviceId) ? { status: 'ok', barId: deviceId } : { status: 'missing' };
}
