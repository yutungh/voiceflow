/**
 * VoiceFlow A2 (0.9.5): how the pill appears, fades and leaves. Pure, so it
 * is unit tested; `/bar` applies it. 0.9.9: the pointer pauses a notice's
 * time-out, and leaving it restarts a short one.
 */
import type { BarPhase, DictationState } from './contract';

/** Listening and working: the pill is slightly see-through. */
export const ACTIVE_OPACITY = 0.92;
/** A result notice, once it has been seen for a moment. */
export const NOTICE_OPACITY = 0.8;
/** How long a result notice stays at full strength before it fades back. */
export const NOTICE_SETTLE_MS = 1000;
/** Crossfade between states, and the fade out. */
export const FADE_MS = 140;

const ACTIVE: ReadonlySet<BarPhase> = new Set(['listening', 'transcribing', 'tidying']);

export function isActivePhase(phase: BarPhase): boolean {
	return ACTIVE.has(phase);
}

/** The pill's opacity: full when hovered or just shown, quieter otherwise. */
export function pillOpacity(phase: BarPhase, shownForMs: number, hovered: boolean): number {
	if (hovered) return 1;
	if (isActivePhase(phase)) return ACTIVE_OPACITY;
	return shownForMs < NOTICE_SETTLE_MS ? 1 : NOTICE_OPACITY;
}

/** A hide that arrives this close to a notice's deadline is its time-out. */
const DEADLINE_SLACK_MS = 250;

/**
 * 0.9.9: after the pointer leaves a notice whose time is up (or nearly up),
 * it stays this much longer, so it never vanishes the instant the pointer
 * moves off it. Coming back pauses it again.
 */
export const LEAVE_GRACE_MS = 2000;

/** Where the pointer is: on the card, or when it last left it. */
export type PointerState = { inside: boolean; leftAt: number | null };

/** True while the pointer keeps a notice: on it, or just left it. */
export function pointerHolds(pointer: PointerState | boolean, now: number): boolean {
	if (typeof pointer === 'boolean') return pointer;
	if (pointer.inside) return true;
	return pointer.leftAt !== null && now - pointer.leftAt < LEAVE_GRACE_MS;
}

/** How long a lingering notice still waits after the pointer left (0: go now). */
export function graceLeft(pointer: PointerState, now: number): number {
	if (pointer.inside || pointer.leftAt === null) return 0;
	return Math.max(0, pointer.leftAt + LEAVE_GRACE_MS - now);
}

/**
 * What to do when the engine sends `incoming` while `shown` is drawn:
 * - `show`: draw it.
 * - `linger`: a notice timed out (0.9.9: every notice hides by itself)
 *   while the pointer is on it, or just left it: keep it until the pointer
 *   has been off it for `LEAVE_GRACE_MS`.
 * - `leave`: fade out and hide the window (also for a hide the person asked
 *   for, such as Feedback or ×, even while hovered).
 */
export function presenceFor(
	shown: Pick<DictationState, 'hidesAt'> | null,
	incoming: DictationState | null,
	pointer: PointerState | boolean,
	now: number = Date.now(),
): 'show' | 'linger' | 'leave' {
	if (incoming) return 'show';
	if (
		shown &&
		shown.hidesAt !== null &&
		now >= shown.hidesAt - DEADLINE_SLACK_MS &&
		pointerHolds(pointer, now)
	) {
		return 'linger';
	}
	return 'leave';
}

/**
 * The key that decides when the pill's contents crossfade: a new layer for
 * each phase (and for a feedback recording), never for a timer tick, a level
 * or a style change.
 */
export function layerKey(view: Pick<DictationState, 'recordingId' | 'phase' | 'purpose'>): string {
	return `${view.recordingId}:${view.phase}:${view.purpose}`;
}
