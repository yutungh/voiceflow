/**
 * VoiceFlow A2 (0.9.5): the style popover above the pill. Pure, so it is
 * unit tested; `/bar` drives it.
 *
 * - It opens only from a click on the style name, while a dictation (not a
 *   feedback recording) is listening and its style can still change.
 * - Picking a style closes it; changing expression keeps it open.
 * - It closes by itself after a short idle while the pointer is outside it
 *   and the pill.
 * - It belongs to one recording: a new recording, Stop, or any state where
 *   the style can no longer change closes it, so a click in it can never
 *   reach another dictation.
 */
import type { DictationState } from './contract';

export const POPOVER_IDLE_MS = 4000;

type ViewLike = Pick<DictationState, 'recordingId' | 'phase' | 'purpose' | 'actions'> | null;

/** True when the popover may be open for this view. */
export function popoverAllowed(view: ViewLike): boolean {
	return (
		!!view &&
		view.phase === 'listening' &&
		view.purpose === 'dictation' &&
		view.actions.includes('set-style')
	);
}

export function createPopover({
	now = () => Date.now(),
	idleMs = POPOVER_IDLE_MS,
}: { now?: () => number; idleMs?: number } = {}) {
	let openFor: string | null = null;
	let lastActivity = 0;
	let inside = false;

	const close = () => {
		openFor = null;
		inside = false;
	};

	return {
		/** The recording the popover is open for, or null when closed. */
		get openFor() {
			return openFor;
		},

		/** True when open for this view's recording (and still allowed). */
		isOpen(view: ViewLike): boolean {
			return !!view && openFor === view.recordingId && popoverAllowed(view);
		},

		/** The style name was clicked. Returns whether it is now open. */
		toggle(view: ViewLike): boolean {
			if (!view || !popoverAllowed(view) || openFor === view.recordingId) {
				close();
				return false;
			}
			openFor = view.recordingId;
			lastActivity = now();
			return true;
		},

		/** A style was picked: done. */
		picked() {
			close();
		},

		/** The pointer moved in the popover or the pill, or a control was used. */
		activity(pointerInside = inside) {
			inside = pointerInside;
			lastActivity = now();
		},

		/** A new view arrived: closes for another recording or a later phase. */
		viewChanged(view: ViewLike) {
			if (openFor === null) return;
			if (!view || view.recordingId !== openFor || !popoverAllowed(view)) close();
		},

		/** Called on a timer: closes after the idle time, unless the pointer is in it. */
		tick(): boolean {
			if (openFor === null || inside) return false;
			if (now() - lastActivity < idleMs) return false;
			close();
			return true;
		},

		close,
	};
}

export type Popover = ReturnType<typeof createPopover>;
