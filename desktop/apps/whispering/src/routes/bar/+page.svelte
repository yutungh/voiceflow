<script lang="ts">
	import { onDestroy, onMount, tick } from 'svelte';
	import { fade } from 'svelte/transition';
	import {
		AUDIO_LEVEL_EVENT,
		BAR_READY_EVENT,
		DICTATION_COMMAND_EVENT,
		DICTATION_STATE_EVENT,
		MAIN_WINDOW,
		RAW_STYLE_ID,
		restoresFocus,
		type BarAction,
		type BarCommand,
		type BarStateMessage,
		type DictationState,
	} from '$lib/bar/contract';
	import { chipNameCut, layoutChips, levelAt, stopRatio, type ChipLayout } from '$lib/bar/chips';
	import { EXPRESSION_LABELS, sanitizeExpression } from '$lib/bar/expression';
	import { demoState } from '$lib/bar/fixtures';
	import {
		CHIPS_WIDTH,
		MARGIN,
		MAX_CHIP_ROWS,
		POPOVER_GAP,
		RESIZE_MS,
		ROW_HEIGHT,
		WINDOW_HEIGHT,
		WINDOW_WIDTH,
		cardHeightFor,
		formatElapsed,
		hitRects,
		type BarEdge,
		type Rect,
	} from '$lib/bar/geometry';
	import { noticeFor, type NoticeButton } from '$lib/bar/notice';
	import { createPopover } from '$lib/bar/popover';
	import {
		FADE_MS,
		LEAVE_GRACE_MS,
		graceLeft,
		layerKey,
		pillOpacity,
		presenceFor,
		type PointerState,
	} from '$lib/bar/presence';
	import { getShortcutDisplayLabel } from '$lib/constants/keyboard';
	import Icon from '$lib/vf/Icon.svelte';

	/**
	 * VoiceFlow 0.9.8: the floating recording bar, one quiet card. 0.9.9:
	 * rounder and roomier, chips show whole style names (wrapping to a second
	 * row, the card growing upward), and every notice hides by itself; the
	 * pointer on it pauses that, and leaving restarts a short wait.
	 *
	 * While a dictation records, the card has three rows: style chips, the
	 * expression slider, and the bottom row (rec dot, wave, time, Feedback,
	 * Stop, Cancel). Every other state is the bottom row alone. The card is
	 * anchored to the window's bottom edge, so it grows and shrinks upward
	 * only and the bottom row never moves.
	 *
	 * The window has one fixed size (`$lib/bar/geometry`) and Rust places it
	 * once per dictation, so nothing here can move it. 0.9.12: at the top of
	 * the screen (`edge`) the layout is a mirror: the card hangs from the
	 * window's top edge with its fixed row first, grows downward, and the
	 * More list and the hint open below it. The "More styles" list
	 * and the focus hint open in the reserved area above the card; on Windows
	 * the window region keeps only what is drawn, so clicks elsewhere pass
	 * through. Rust never gives this window focus, and nothing here calls
	 * focus(): a click is followed by `bar_restore_focus`, which gives the
	 * front back to the app being dictated into if the click took it.
	 */

	const desktop = typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;
	const WAVE_BARS = 17;
	/** Below this (0..1) for 3 s while listening: "No sound". */
	const QUIET = 0.04;
	const FOCUS_NOTE_MS = 5000;
	/** Between chips in a row. */
	const CHIP_GAP = 8;

	let view = $state<DictationState | null>(null);
	/** 0.9.12: the screen edge the bar is on; set with each dictation's place. */
	let edge = $state<BarEdge>('bottom');
	/** The last listening view: the upper rows draw from it, so they fade out unchanged. */
	let upperView = $state<DictationState | null>(null);
	let lastSeq = -1;
	let levels = $state<number[]>(Array.from({ length: WAVE_BARS }, () => 0));
	let hasLevels = $state(false);
	let loudAt = $state(0);
	let now = $state(Date.now());
	/** When the current layer (phase) was first drawn. */
	let shownAt = $state(Date.now());
	let hovered = $state(false);
	/** When the pointer last left the card (it keeps a timed-out notice a little longer). */
	let leftAt: number | null = null;
	let leaving = $state(false);
	let lingering = false;
	/** A lingering notice's last wait after the pointer left it. */
	let graceTimer: ReturnType<typeof setTimeout> | undefined;
	/** The fade under way is a notice's time-out (the pointer coming back stops it). */
	let timedOut = false;
	/** A notice button was clicked for this recording: its hide is never a time-out. */
	let actedOn: string | null = null;
	let leaveTimer: ReturnType<typeof setTimeout> | undefined;
	let focusNote = $state<string | null>(null);
	let noteTimer: ReturnType<typeof setTimeout> | undefined;
	let popOpen = $state(false);
	let popEl = $state<HTMLDivElement | null>(null);
	let noteEl = $state<HTMLDivElement | null>(null);
	/** Where the More list opens: its right edge under the More chip's, inside the window. */
	let listRight = $state(MARGIN);
	/** The hidden copies of the chips, measured for the layout. */
	let measureEl = $state<HTMLDivElement | null>(null);
	/** How the chips fit (whole names, at most two rows); from `relayout()`. */
	let layout = $state<ChipLayout | null>(null);
	/** What `layout` was measured for. */
	let layoutFor = '';
	let reduced = $state(false);
	/** Demo screenshots only: the timer shows exactly `elapsed`. */
	let clockFrozen = false;
	/** A slider drag: it belongs to one recording, one pointer and one track. */
	let drag = $state<{
		pointerId: number;
		recordingId: string;
		level: number;
		sent: number;
		track: HTMLElement;
	} | null>(null);
	/** The level a drag ended on, shown until the engine's view has it (no flick back). */
	let settled = $state<{ recordingId: string; level: number } | null>(null);
	let settleTimer: ReturnType<typeof setTimeout> | undefined;
	/** The card height the window region was last built for. */
	let regionHeight = ROW_HEIGHT;
	const popover = createPopover();
	const cleanups: Array<() => void> = [];

	async function invoke<T>(command: string, args?: Record<string, unknown>) {
		if (!desktop) return null;
		const { invoke } = await import('@tauri-apps/api/core');
		return invoke<T>(command, args).catch((error) => {
			console.warn(`[bar] ${command}`, error);
			return null;
		});
	}

	// ---- derived ------------------------------------------------------------

	const chipRows = $derived(layout?.rows ?? 1);
	const cardHeight = $derived(cardHeightFor(view, chipRows));
	const elapsed = $derived(view ? formatElapsed(now - view.startedAt) : '0:00');
	const noSound = $derived(
		!!view &&
			view.phase === 'listening' &&
			hasLevels &&
			now - loudAt > 3000 &&
			now - view.startedAt > 3000,
	);
	const notice = $derived(view ? noticeFor(view) : null);
	const opacity = $derived(view ? pillOpacity(view.phase, now - shownAt, hovered) : 1);
	const keyLabel = (accelerator: string | null) =>
		accelerator ? getShortcutDisplayLabel(accelerator) : '';
	const bars = $derived(
		hasLevels
			? levels
			: Array.from({ length: WAVE_BARS }, (_, i) => 0.18 + 0.14 * Math.abs(Math.sin(i * 0.9))),
	);
	const fadeMs = $derived(reduced ? 0 : FADE_MS);

	// The upper rows, from the last listening view.
	const showUpper = $derived(view?.phase === 'listening');
	const upperKey = $derived(upperView ? `${upperView.recordingId}:${upperView.purpose}` : '');
	// The chips come from the engine (`chips.ts`, kept with the recording). 0.9.9:
	// chips that would need a third row go to More too (never the style in use).
	const row = $derived.by(() => {
		if (!upperView) return { all: [], chips: [], more: [] };
		const all = upperView.favourites;
		const fit = layout && layoutFor === chipsKey ? layout : null;
		const chips = fit ? all.filter((_, i) => fit.shown.includes(i)) : all;
		return { all, chips, more: upperView.styles.filter((s) => !chips.some((c) => c.id === s.id)) };
	});
	/** The chips' identity: what a layout is measured for. */
	const chipsKey = $derived(
		upperView
			? [
					upperView.recordingId,
					upperView.style.id,
					upperView.styles.length,
					...upperView.favourites.map((s) => `${s.id}:${s.name}`),
				].join('|')
			: '',
	);
	const upperCan = (action: BarAction) => !!upperView?.actions.includes(action);
	const isRaw = $derived(upperView?.style.id === RAW_STYLE_ID);
	const exprOn = $derived(!isRaw && upperCan('set-expression'));
	const expression = $derived(
		upperView ? sanitizeExpression(upperView.expression ?? upperView.defaultExpression) : 2,
	);
	const shownLevel = $derived(
		drag && drag.recordingId === upperView?.recordingId
			? drag.level
			: settled && settled.recordingId === upperView?.recordingId
				? settled.level
				: expression,
	);
	const subject = $derived(upperView?.feedbackFor ?? null);
	const subjectTitle = $derived.by(() => {
		if (!subject) return 'Feedback on your last dictation';
		const d = new Date(subject.at);
		const at = Number.isNaN(d.getTime())
			? ''
			: ` at ${d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })}`;
		const snippet = subject.snippet ? `: “${subject.snippet}”` : '';
		return `Feedback on your ${subject.appName ?? 'last'} dictation${at}${snippet}`;
	});

	// ---- the window -------------------------------------------------------

	/** The list's or hint's box, for the window region. */
	function aboveRect(): Rect | null {
		const el = popOpen ? popEl : focusNote ? noteEl : null;
		if (!el) return null;
		const r = el.getBoundingClientRect();
		return { x: r.left, y: r.top, width: r.width, height: r.height };
	}

	function sendRegion(height: number) {
		regionHeight = height;
		void invoke('bar_hit_rects', { hit: hitRects(height, aboveRect(), edge) });
	}

	let shrinkTimer: ReturnType<typeof setTimeout> | undefined;
	/** After the card shrank and its parts faded: the region follows. */
	function shrinkLater() {
		clearTimeout(shrinkTimer);
		shrinkTimer = setTimeout(
			() => sendRegion(cardHeight),
			(reduced ? 0 : Math.max(FADE_MS, RESIZE_MS)) + 40,
		);
	}

	/**
	 * Shows the window: always the same size; Rust keeps it where it was
	 * placed. `keep`: the card was already showing, so the region keeps its
	 * old height until the card has shrunk.
	 */
	async function present(keep = false) {
		clearTimeout(shrinkTimer);
		await tick();
		relayout();
		const height = keep ? Math.max(regionHeight, cardHeight) : cardHeight;
		regionHeight = height;
		await invoke('bar_present', {
			width: WINDOW_WIDTH,
			height: WINDOW_HEIGHT,
			hit: hitRects(height, aboveRect(), edge),
		});
		if (height > cardHeight) shrinkLater();
	}

	/** Grows the window region at once; shrinks it after the card and fades. */
	async function syncHit(growing: boolean) {
		clearTimeout(shrinkTimer);
		if (growing) {
			await tick();
			relayout();
			sendRegion(Math.max(regionHeight, cardHeight));
			if (regionHeight > cardHeight) shrinkLater();
		} else {
			shrinkLater();
		}
	}

	function syncPop() {
		const open = popover.isOpen(view) && row.more.length > 0;
		if (open === popOpen) return;
		popOpen = open;
		if (!open) popover.close();
		void syncHit(open);
	}

	// ---- the chips' layout (0.9.9) -----------------------------------------

	/**
	 * Measures each chip at its natural width (the hidden copies) and lays
	 * them out in at most two rows (`layoutChips`). No name is cut short;
	 * chips that would need a third row go to More, never the style in use.
	 */
	function relayout() {
		const u = upperView;
		if (!u || u.purpose !== 'dictation' || !measureEl) return;
		const chips = [...measureEl.querySelectorAll<HTMLElement>('[data-chip]')];
		if (chips.length !== u.favourites.length) return;
		const more = measureEl.querySelector<HTMLElement>('[data-more]');
		// A chip's own box (the window itself is never measured: it has one size).
		const width = (el: HTMLElement | null) => {
			const box = el?.getBoundingClientRect();
			return box ? Math.ceil(box.width) : 0;
		};
		layout = layoutChips({
			widths: chips.map(width),
			moreWidth: width(more),
			width: CHIPS_WIDTH,
			gap: CHIP_GAP,
			maxRows: MAX_CHIP_ROWS,
			keep: u.favourites.findIndex((s) => s.id === u.style.id),
			moreNeeded: u.styles.length > u.favourites.length,
		});
		layoutFor = chipsKey;
	}

	/** Lays the chips out again and follows the card's new height. */
	async function relayoutAndSync() {
		await tick();
		const before = cardHeight;
		relayout();
		if (view && cardHeight !== before) void syncHit(cardHeight > before);
	}

	$effect(() => {
		// A new recording, another style in use, or other chips.
		void chipsKey;
		void relayoutAndSync();
	});

	// ---- state from the engine ---------------------------------------------

	/** Where the pointer is, for a notice that timed out (a click on it ends it). */
	function pointer(): PointerState {
		if (actedOn === view?.recordingId) return { inside: false, leftAt: null };
		return { inside: hovered, leftAt };
	}

	/** A lingering notice the pointer has left: it goes after the short wait. */
	function waitThenLeave() {
		clearTimeout(graceTimer);
		const wait = graceLeft(pointer(), Date.now());
		graceTimer = setTimeout(() => {
			if (lingering && !hovered) {
				leave();
				timedOut = true;
			}
		}, wait);
	}

	function apply(next: DictationState | null, nextEdge: BarEdge = edge) {
		const decision = presenceFor(view, next, pointer());
		if (decision === 'linger') {
			lingering = true;
			if (!hovered) waitThenLeave();
			return;
		}
		clearTimeout(graceTimer);
		if (decision === 'leave' || !next) {
			leave();
			return;
		}
		const newRecording = next.recordingId !== view?.recordingId;
		const wasHidden = !view || leaving;
		const heightBefore = cardHeight;
		// The edge changes only with the window's place: a new dictation, or shown again.
		if (wasHidden || newRecording) edge = nextEdge === 'top' ? 'top' : 'bottom';
		if (newRecording) {
			leftAt = null;
			setNote(null);
			levels = levels.map(() => 0);
			hasLevels = false;
			loudAt = Date.now();
		}
		if (drag && (drag.recordingId !== next.recordingId || !next.actions.includes('set-expression'))) {
			drag = null;
		}
		if (
			settled &&
			(settled.recordingId !== next.recordingId ||
				sanitizeExpression(next.expression ?? next.defaultExpression) === settled.level ||
				!next.actions.includes('set-expression'))
		) {
			settled = null;
		}
		if (!view || layerKey(next) !== layerKey(view)) {
			shownAt = Date.now();
			// A click (Stop...) was for the state before: a new notice lingers under the pointer.
			actedOn = null;
		}
		clearTimeout(leaveTimer);
		leaving = false;
		lingering = false;
		if (next.phase === 'listening') upperView = next;
		view = next;
		popover.viewChanged(next);
		syncPop();
		// The window moves only for a new dictation (Rust re-places it then).
		if (wasHidden || newRecording) void present(!wasHidden);
		else if (cardHeight !== heightBefore) void syncHit(cardHeight > heightBefore);
	}

	/** Fades the card out, then hides the window. */
	function leave() {
		lingering = false;
		timedOut = false;
		clearTimeout(graceTimer);
		drag = null;
		popover.close();
		syncPop();
		if (!view) {
			void invoke('bar_hide');
			return;
		}
		leaving = true;
		clearTimeout(leaveTimer);
		leaveTimer = setTimeout(
			() => {
				if (!leaving) return;
				leaving = false;
				hovered = false;
				view = null;
				upperView = null;
				setNote(null);
				clearTimeout(shrinkTimer);
				void invoke('bar_hide');
			},
			reduced ? 0 : FADE_MS + 20,
		);
	}

	// ---- clicks -------------------------------------------------------------

	function setNote(text: string | null) {
		clearTimeout(noteTimer);
		const had = !!focusNote;
		focusNote = text;
		if (text) noteTimer = setTimeout(() => setNote(null), FOCUS_NOTE_MS);
		if (!!text !== had) void syncHit(!!text);
	}

	async function restoreFocus() {
		const result = await invoke<string>('bar_restore_focus');
		if (result === 'failed') {
			setNote(
				view?.app.name
					? `Click back into ${view.app.name} to keep typing`
					: 'Click back into your app to keep typing',
			);
		}
	}

	/** Commands leave in the order they were clicked, even while one waits for the focus. */
	let sending: Promise<unknown> = Promise.resolve();

	function send(command: BarCommand, { restore = true }: { restore?: boolean } = {}) {
		if (!desktop) {
			// Web dev build: a page check can collect what would have been sent.
			(window as { __vfBarSent?: BarCommand[] }).__vfBarSent?.push(command);
			return;
		}
		sending = sending.then(async () => {
			if (restore && restoresFocus(command.type)) await restoreFocus();
			const { emitTo } = await import('@tauri-apps/api/event');
			await emitTo(MAIN_WINDOW, DICTATION_COMMAND_EVENT, command);
		}).catch((error) => console.warn('[bar] send', error));
	}

	const can = (action: BarAction) => !!view?.actions.includes(action);

	function command(type: Exclude<BarCommand['type'], 'set-style' | 'set-expression'>) {
		if (!view) return;
		if (type === 'use-as-feedback' || type === 'stop' || type === 'cancel') {
			popover.close();
			syncPop();
		}
		actedOn = view.recordingId;
		void send({ type, recordingId: view.recordingId } as BarCommand);
		// A notice kept only because it was hovered: a click on it ends it.
		if (lingering) leave();
	}

	/** Feedback, or Back to dictation: shown all through a recording. */
	function toggleFeedback() {
		if (!view) return;
		const type = view.purpose === 'feedback' ? 'use-as-dictation' : 'use-as-feedback';
		if (can(type)) command(type);
		// Not available (no dictation yet): the click still gives the front back.
		else void restoreFocus();
	}

	/** The More list's right edge lines up with the More chip's, inside the window. */
	function placeList(chip: HTMLElement) {
		const r = chip.getBoundingClientRect();
		listRight = Math.max(MARGIN, Math.min(WINDOW_WIDTH - MARGIN, Math.round(WINDOW_WIDTH - r.right)));
	}

	function toggleMore(event: MouseEvent) {
		placeList(event.currentTarget as HTMLElement);
		popover.toggle(upperView);
		syncPop();
		void restoreFocus();
	}

	function setStyle(recordingId: string, styleId: string) {
		if (!view || view.recordingId !== recordingId) return;
		popover.picked();
		syncPop();
		if (view.style.id === styleId || !can('set-style')) {
			void restoreFocus();
			return;
		}
		void send({ type: 'set-style', recordingId, styleId });
	}

	function runButton(button: NoticeButton) {
		command(button.action);
	}

	/** A press on a control: no default focus or selection; Rust notes the time. */
	function pointerDown(event: PointerEvent) {
		const target = event.target as Element | null;
		if (event.button === 0 && target?.closest?.('button, [role="slider"]')) event.preventDefault();
		void invoke('bar_pointer_down');
	}

	function cardEnter() {
		hovered = true;
		// Back on a notice whose time is up: it waits again, even mid-fade.
		clearTimeout(graceTimer);
		if (leaving && timedOut && view) {
			clearTimeout(leaveTimer);
			leaving = false;
			timedOut = false;
			lingering = true;
		}
		popover.activity(true);
	}

	function cardLeave() {
		hovered = false;
		leftAt = Date.now();
		popover.activity(false);
		// A notice kept by the pointer goes a short while after it leaves.
		if (lingering) waitThenLeave();
	}

	/** An element fading out can't be clicked (it may belong to another state). */
	function inert(event: Event) {
		(event.currentTarget as HTMLElement).inert = true;
	}

	// ---- the expression slider ---------------------------------------------

	function slideTo(clientX: number) {
		if (!drag) return;
		const r = drag.track.getBoundingClientRect();
		const level = levelAt(clientX, r.left, r.width);
		if (level === drag.level) return;
		drag.level = level;
		if (level === drag.sent) return;
		drag.sent = level;
		// Each stop is sent as it is reached; the front is given back once, on release.
		void send(
			{ type: 'set-expression', recordingId: drag.recordingId, expression: level },
			{ restore: false },
		);
	}

	function sliderDown(event: PointerEvent) {
		if (!upperView || !exprOn || event.button !== 0 || !event.isPrimary || drag) return;
		event.preventDefault();
		const el = event.currentTarget as HTMLElement;
		const track = el.querySelector<HTMLElement>('.track');
		if (!track) return;
		try {
			el.setPointerCapture(event.pointerId);
		} catch {
			// Without capture the drag ends when the pointer leaves; a click still sets it.
		}
		// From what the slider shows: a level just sent may not be in the view yet.
		drag = {
			pointerId: event.pointerId,
			recordingId: upperView.recordingId,
			level: shownLevel,
			sent: shownLevel,
			track,
		};
		popover.activity(true);
		slideTo(event.clientX);
	}

	function sliderMove(event: PointerEvent) {
		if (drag && event.pointerId === drag.pointerId) slideTo(event.clientX);
	}

	/** Pointer up, cancel or lost capture: ends the drag once. */
	function sliderEnd(event: PointerEvent) {
		if (!drag || event.pointerId !== drag.pointerId) return;
		const el = event.currentTarget as HTMLElement;
		const ended = drag;
		drag = null;
		clearTimeout(settleTimer);
		if (ended.sent !== expression) {
			// Shown until the engine's view has it (or, if that never comes, briefly).
			settled = { recordingId: ended.recordingId, level: ended.sent };
			settleTimer = setTimeout(() => (settled = null), 1000);
		} else {
			settled = null;
		}
		if (el.hasPointerCapture?.(event.pointerId)) el.releasePointerCapture(event.pointerId);
		void restoreFocus();
	}

	// ---- wiring -------------------------------------------------------------

	onMount(() => {
		document.documentElement.classList.add('vf-bar-window');
		const motion = matchMedia('(prefers-reduced-motion: reduce)');
		reduced = motion.matches;
		const onMotion = () => (reduced = motion.matches);
		motion.addEventListener('change', onMotion);
		cleanups.push(() => motion.removeEventListener('change', onMotion));

		const timer = setInterval(() => {
			if (!clockFrozen) now = Date.now();
			if (popover.tick()) syncPop();
		}, 250);
		cleanups.push(() => clearInterval(timer));
		// The chips are measured again once the font has loaded.
		void document.fonts?.ready.then(() => relayoutAndSync());

		if (!desktop) {
			// Web dev build: a sample state (`/bar?demo=listening&elapsed=9`).
			const params = new URLSearchParams(location.search);
			const name = params.get('demo') ?? 'listening';
			const at = Date.now();
			const sample = demoState(name, at);
			// 0.9.12: `&edge=top` shows the bar as it sits at the top of the screen.
			if (params.get('edge') === 'top') edge = 'top';
			const secs = Number(params.get('elapsed'));
			if (sample && Number.isFinite(secs) && params.has('elapsed')) {
				sample.startedAt = at - secs * 1000 - 200;
				now = at;
				clockFrozen = true;
			}
			if (sample?.phase === 'listening' && name !== 'listening-quiet') {
				hasLevels = true;
				levels = Array.from({ length: WAVE_BARS }, (_, i) =>
					Math.min(1, 0.12 + 0.88 * Math.abs(Math.sin(i * 0.9) * Math.cos(i * 0.37))),
				);
				loudAt = Date.now() + 3_600_000;
			}
			if (name === 'listening-quiet') {
				hasLevels = true;
				loudAt = 0;
				if (sample) sample.startedAt = at - 8000;
			}
			if (sample?.phase === 'listening') upperView = sample;
			view = sample;
			if (params.get('settled') === '1') shownAt = 0;
			if ((name === 'listening-more' || name === 'listening-wrap-more' || params.get('menu') === '1') && sample) {
				void tick().then(async () => {
					relayout();
					await tick();
					const chip = document.querySelector<HTMLElement>('.card .chip.more');
					if (chip) placeList(chip);
					popover.toggle(sample);
					syncPop();
				});
			}
			if (name === 'listening-note') {
				focusNote = 'Click back into Kite to keep typing';
			}
			// A second state after a delay (`&then=transcribing&after=800`), to look at the change.
			const then = params.get('then');
			if (then && sample) {
				const after = Number(params.get('after') ?? 800);
				setTimeout(() => {
					const next = demoState(then, Date.now());
					if (next) apply({ ...next, startedAt: sample.startedAt, seq: sample.seq + 1 });
				}, Number.isFinite(after) ? after : 800);
			}
			return;
		}

		void (async () => {
			const { listen, emitTo } = await import('@tauri-apps/api/event');
			cleanups.push(
				await listen<BarStateMessage>(DICTATION_STATE_EVENT, ({ payload }) => {
					const next = payload.state;
					if (next && next.seq < lastSeq) return; // a late, older message
					if (next) lastSeq = next.seq;
					apply(next, payload.edge);
				}),
			);
			cleanups.push(
				await listen<number>(AUDIO_LEVEL_EVENT, ({ payload }) => {
					if (view?.phase !== 'listening') return;
					const level = Math.max(0, Math.min(1, Number(payload) || 0));
					hasLevels = true;
					if (level > QUIET) loudAt = Date.now();
					levels = [...levels.slice(1), level];
				}),
			);
			// Ask for the current state, in case it was sent before this loaded.
			await emitTo(MAIN_WINDOW, BAR_READY_EVENT, null);
		})();

		// A DPI change: Rust re-places the bar only if the monitor changed.
		const media = matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
		const onDpi = () => {
			if (view) void present(true);
		};
		media.addEventListener('change', onDpi);
		cleanups.push(() => media.removeEventListener('change', onDpi));
	});

	onDestroy(() => {
		clearTimeout(leaveTimer);
		clearTimeout(noteTimer);
		clearTimeout(shrinkTimer);
		clearTimeout(settleTimer);
		clearTimeout(graceTimer);
		for (const cleanup of cleanups) cleanup();
	});
</script>

<svelte:head>
	<title>VoiceLnq recording bar</title>
</svelte:head>

{#snippet wave()}
	<span class="wave" aria-hidden="true">
		{#each bars as level, i (i)}
			<i style:height={`${Math.max(3, Math.round(3 + level * 17))}px`}></i>
		{/each}
	</span>
{/snippet}

{#if view}
	<div
		class="stage"
		class:top={edge === 'top'}
		style:width={`${WINDOW_WIDTH}px`}
		style:height={`${WINDOW_HEIGHT}px`}
	>
		{#if popOpen && upperView}
			<div
				class="pop"
				bind:this={popEl}
				role="dialog"
				aria-label="More styles"
				tabindex="-1"
				style:right={`${listRight}px`}
				style:bottom={edge === 'bottom' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}
				style:top={edge === 'top' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}
				transition:fade={{ duration: fadeMs }}
				onoutrostart={inert}
				onpointerdown={pointerDown}
				onpointerenter={() => popover.activity(true)}
				onpointerleave={() => popover.activity(false)}
				onpointermove={() => popover.activity(true)}
			>
				<div class="opts" role="radiogroup" aria-label="More styles">
					{#each row.more as style (style.id)}
						{@const recordingId = upperView.recordingId}
						<button
							type="button"
							role="radio"
							class="opt"
							aria-checked="false"
							title={style.name}
							onclick={() => setStyle(recordingId, style.id)}
							><span class="opt-name" class:cut={chipNameCut(style.name)}>{style.name}</span></button
						>
					{/each}
				</div>
			</div>
		{:else if focusNote}
			<div
				class="note"
				bind:this={noteEl}
				role="status"
				style:bottom={edge === 'bottom' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}
				style:top={edge === 'top' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}
				style:opacity={leaving ? 0 : 1}
				transition:fade={{ duration: fadeMs }}
			>
				<Icon name="cursor-text" class="i" />{focusNote}
			</div>
		{/if}

		{#if upperView && upperView.purpose === 'dictation'}
			<!-- The chips at their natural width, measured for the layout (never seen). -->
			<div class="measure" bind:this={measureEl} aria-hidden="true">
				{#each upperView.favourites as style (style.id)}
					<span class="chip" data-chip
						><span class="chip-name" class:cut={chipNameCut(style.name)}>{style.name}</span></span
					>
				{/each}
				<span class="chip more" data-more>More<Icon name="caret-down" class="i caret" /></span>
			</div>
		{/if}

		<div
			class="card tone-{notice?.tone ?? 'live'}"
			class:leaving
			class:tall={cardHeight > ROW_HEIGHT}
			style:height={`${cardHeight}px`}
			style:opacity={leaving ? 0 : opacity}
			role="status"
			aria-live="polite"
			onpointerdown={pointerDown}
			onpointerenter={cardEnter}
			onpointerleave={cardLeave}
		>
			{#if showUpper && upperView}
				{#key upperKey}
					{@const u = upperView}
					<div
						class="upper"
						style:height={`${cardHeightFor(u, chipRows) - ROW_HEIGHT}px`}
						transition:fade|global={{ duration: fadeMs }}
						onoutrostart={inert}
					>
						{#if u.purpose === 'feedback'}
							<div class="about" title={subjectTitle}>
								<div class="about-line">
									<Icon name="chat-circle" class="i" />
									<span class="lbl">Feedback on:</span>
									<span class="quote"
										>{subject?.snippet
											? `“${subject.snippet}”`
											: subject?.appName
												? `your ${subject.appName} dictation`
												: 'your last dictation'}</span
									>
								</div>
								<div class="about-hint">Say what should change, then press Stop.</div>
							</div>
						{:else}
							<div
								class="chips"
								class:two={chipRows > 1}
								role="radiogroup"
								aria-label="Style for this dictation"
							>
								{#each row.chips as style (style.id)}
									<button
										type="button"
										role="radio"
										class="chip"
										class:on={u.style.id === style.id}
										aria-checked={u.style.id === style.id}
										title={style.name}
										onclick={() => setStyle(u.recordingId, style.id)}
										><span class="chip-name" class:cut={chipNameCut(style.name)}>{style.name}</span
										></button
									>
								{/each}
								{#if row.more.length}
									<button
										type="button"
										class="chip more"
										class:open={popOpen}
										aria-haspopup="dialog"
										aria-expanded={popOpen}
										title={`${row.more.length} more ${row.more.length === 1 ? 'style' : 'styles'}`}
										onclick={toggleMore}
										>More<Icon name="caret-down" class="i caret" /></button
									>
								{/if}
							</div>
							<div
								class="expr"
								class:off={!exprOn}
								title={isRaw ? 'Raw has no tidy-up, so expression does not apply' : undefined}
							>
								<span class="lbl">Expression</span>
								<div
									class="slider"
									class:dragging={!!drag}
									role="slider"
									aria-label="Expression"
									aria-valuemin={0}
									aria-valuemax={4}
									aria-valuenow={shownLevel}
									aria-valuetext={exprOn ? EXPRESSION_LABELS[shownLevel] : 'Off for Raw'}
									aria-disabled={!exprOn}
									onpointerdown={sliderDown}
									onpointermove={sliderMove}
									onpointerup={sliderEnd}
									onpointercancel={sliderEnd}
									onlostpointercapture={sliderEnd}
								>
									<div class="track">
										{#if exprOn}
											<span class="fill" style:width={`${stopRatio(shownLevel) * 100}%`}></span>
										{/if}
										{#each EXPRESSION_LABELS as label, level (label)}
											<span
												class="stop"
												class:passed={exprOn && level <= shownLevel}
												style:left={`${stopRatio(level) * 100}%`}
											></span>
										{/each}
										{#if exprOn}
											<span class="thumb" style:left={`${stopRatio(shownLevel) * 100}%`}></span>
										{/if}
									</div>
								</div>
								<span class="val">{exprOn ? EXPRESSION_LABELS[shownLevel] : 'Off for Raw'}</span>
							</div>
						{/if}
					</div>
				{/key}
			{/if}

			<div class="row">
				{#key layerKey(view)}
					<div class="layer" transition:fade={{ duration: fadeMs }} onoutrostart={inert}>
						{#if view.phase === 'listening'}
							<span class="lead"><span class="rec" aria-hidden="true"></span></span>
							<span class="slot-wave">
								{#if noSound}
									<span class="silent" title="No sound from your microphone">No sound</span>
								{:else}
									{@render wave()}
								{/if}
							</span>
							<span class="time" aria-label={`Recording for ${elapsed}`}>{elapsed}</span>
							<span class="tools">
								{#if view.purpose === 'feedback'}
									<button
										type="button"
										class="tb fbk"
										class:dim={!can('use-as-dictation')}
										aria-disabled={!can('use-as-dictation')}
										title={can('use-as-dictation')
											? 'Make this a dictation again'
											: 'Started with the feedback key, so it stays feedback'}
										onclick={toggleFeedback}
										><Icon name="arrow-counter-clockwise" class="i" />Back to dictation</button
									>
								{:else}
									<button
										type="button"
										class="tb fbk"
										class:dim={!can('use-as-feedback')}
										aria-disabled={!can('use-as-feedback')}
										title={can('use-as-feedback')
											? 'Use this recording as feedback on your last dictation'
											: 'No dictation to give feedback on yet'}
										onclick={toggleFeedback}><Icon name="chat-circle" class="i" />Feedback</button
									>
								{/if}
								{#if can('stop')}
									<button
										type="button"
										class="ic stop"
										aria-label="Stop"
										title={view.keys.stop ? `Stop (${keyLabel(view.keys.stop)})` : 'Stop'}
										onclick={() => command('stop')}><span class="sq"></span></button
									>
								{/if}
								{#if can('cancel')}
									<button
										type="button"
										class="ic"
										aria-label="Cancel"
										title={view.keys.cancel ? `Cancel (${keyLabel(view.keys.cancel)})` : 'Cancel'}
										onclick={() => command('cancel')}><Icon name="x" class="i" /></button
									>
								{/if}
							</span>
						{:else if view.phase === 'transcribing' || view.phase === 'tidying'}
							<span class="lead"><span class="work" aria-hidden="true"></span></span>
							<span class="slot-wave shimmer">{@render wave()}</span>
							<span class="msg">
								<span class="title"
									>{view.phase === 'tidying' ? 'Tidying…' : 'Transcribing…'}</span
								>
								{#if view.phase === 'tidying' && view.style.id !== RAW_STYLE_ID}
									<span class="aside"> · {view.style.name}</span>
								{/if}
							</span>
						{:else if notice}
							<span class="lead"><Icon name={notice.icon} weight="fill" class="i n-{notice.tone}" /></span>
							<span class="msg" title={notice.detail}>
								<span class="title">{notice.title}</span>
								{#if notice.aside}<span class="aside"> · {notice.aside}</span>{/if}
							</span>
							<span class="tools">
								{#each notice.buttons as button (button.action)}
									<button type="button" class="tb act" onclick={() => runButton(button)}
										>{button.label}</button
									>
								{/each}
								{#if notice.closable}
									<button
										type="button"
										class="ic"
										aria-label="Close"
										title="Close"
										onclick={() => command('dismiss')}><Icon name="x" class="i" /></button
									>
								{/if}
							</span>
						{/if}
					</div>
				{/key}
			</div>
		</div>
	</div>
{/if}

<style>
	:global(html.vf-bar-window),
	:global(html.vf-bar-window body) {
		background: transparent !important;
		overflow: hidden;
		margin: 0;
	}

	/* The whole window: one fixed size. Parts are placed absolutely, so no
	   state can push anything around. 0.9.9: rounder, roomier, an 8 px rhythm;
	   light mode is a calm warm white, dark mode as before. */
	.stage {
		--pill-bg: rgba(252, 251, 249, 0.9);
		--pill-line: rgba(58, 48, 38, 0.08);
		--pill-fg: #1d1b18;
		--pill-sub: #67615a;
		--pill-faint: #8e877f;
		--pill-hover: rgba(58, 48, 38, 0.06);
		--pill-press: rgba(58, 48, 38, 0.11);
		--pill-shadow: 0 1px 2px rgba(40, 30, 20, 0.05), 0 8px 24px -8px rgba(40, 30, 20, 0.24);
		--wave-ink: rgba(29, 27, 24, 0.7);
		--rec: #e5372b;
		--ok: #1f8a5b;
		--warn: #a86a07;
		--bad: #c2321f;
		--track: rgba(58, 48, 38, 0.12);
		--thumb: #ffffff;
		position: relative;
		overflow: hidden;
		color: var(--pill-fg);
		font-family: var(--font-sans);
		user-select: none;
		cursor: default;
		-webkit-font-smoothing: antialiased;
	}
	:global(.dark) .stage {
		--pill-bg: rgba(16, 21, 27, 0.84);
		--pill-line: rgba(255, 255, 255, 0.09);
		--pill-fg: #eef2f4;
		--pill-sub: #9aa6ad;
		--pill-faint: #7d8990;
		--pill-hover: rgba(255, 255, 255, 0.07);
		--pill-press: rgba(255, 255, 255, 0.13);
		--pill-shadow: 0 1px 2px rgba(0, 0, 0, 0.35), 0 8px 24px -8px rgba(0, 0, 0, 0.6);
		--wave-ink: rgba(238, 242, 244, 0.78);
		--rec: #ff5a4e;
		--ok: #5fd7a0;
		--warn: #f5b544;
		--bad: #ff8a7a;
		--track: rgba(255, 255, 255, 0.14);
		--thumb: #eef2f4;
	}

	/* ---- the card: anchored at the bottom, so it grows upward only ---- */
	.card {
		position: absolute;
		left: 16px;
		bottom: 16px;
		width: 480px;
		box-sizing: border-box;
		overflow: hidden;
		border-radius: 24px;
		background: var(--pill-bg);
		box-shadow:
			inset 0 0 0 1px var(--pill-line),
			var(--pill-shadow);
		transition:
			height 180ms cubic-bezier(0.2, 0, 0, 1),
			opacity 200ms ease-out,
			transform 160ms ease-out;
		animation: appear 180ms ease-out;
	}
	.card.leaving {
		transform: translateY(4px);
		transition:
			opacity 140ms ease-in,
			transform 140ms ease-in;
	}
	/* 0.9.12, at the top of the screen: hung from the top, growing down. */
	.stage.top .card {
		top: 16px;
		bottom: auto;
		animation-name: appear-down;
	}
	.stage.top .card.leaving {
		transform: translateY(-4px);
	}

	/* The bottom row: the same box in every state. */
	.row {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 0;
		height: 48px;
	}
	/* At the top, the fixed row is the card's first 48 px. */
	.stage.top .row {
		top: 0;
		bottom: auto;
	}
	.layer {
		position: absolute;
		inset: 0;
		display: flex;
		align-items: center;
		gap: 12px;
		padding: 0 8px 0 20px;
		white-space: nowrap;
	}

	/* ---- the upper rows (a dictation that is recording) ---- */
	.upper {
		position: absolute;
		left: 0;
		right: 0;
		bottom: 48px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		gap: 8px;
		padding: 16px 16px 8px;
	}
	/* At the top, below the fixed row: expression next to it, the chips
	   (and More) at the far edge, next to where the list opens. */
	.stage.top .upper {
		top: 48px;
		bottom: auto;
		flex-direction: column-reverse;
		padding: 8px 16px 16px;
	}
	.chips {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		align-content: flex-start;
		gap: 8px;
		height: 32px;
		min-width: 0;
	}
	.chips.two {
		height: 72px;
	}
	.chip {
		all: unset;
		box-sizing: border-box;
		/* Whole names: a chip never shrinks (the layout moves it to More instead). */
		flex: none;
		height: 32px;
		padding: 0 12px;
		border-radius: 16px;
		display: inline-flex;
		align-items: center;
		gap: 4px;
		font-size: 13px;
		font-weight: 500;
		color: var(--pill-fg);
		background: var(--pill-hover);
		white-space: nowrap;
		cursor: pointer;
		transition:
			background-color 120ms ease-out,
			color 120ms ease-out,
			box-shadow 120ms ease-out;
	}
	.chip-name {
		white-space: nowrap;
	}
	/* Only an absurdly long name (over 32 characters) is cut; the tooltip has it all. */
	.chip-name.cut {
		display: inline-block;
		max-width: 232px;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.chip:hover {
		background: var(--pill-press);
	}
	/* Selected: tonal, never solid. */
	.chip.on {
		background: var(--primary-tint);
		color: var(--primary-ink);
		box-shadow: inset 0 0 0 1px var(--primary-tint-border);
	}
	.chip.on:hover {
		background: var(--primary-tint-hover);
	}
	.chip.more {
		margin-left: auto;
		padding: 0 10px 0 12px;
		color: var(--pill-sub);
		background: transparent;
		box-shadow: inset 0 0 0 1px var(--pill-line);
	}
	.chip.more:hover,
	.chip.more.open {
		color: var(--pill-fg);
		background: var(--pill-hover);
	}
	.chip.more :global(.caret) {
		width: 10px;
		height: 10px;
		opacity: 0.75;
		transform: rotate(180deg);
	}
	/* The list opens below: the caret points down. */
	.stage.top .chip.more :global(.caret) {
		transform: none;
	}
	/* The hidden copies the layout measures: same chips, never seen or clicked. */
	.measure {
		position: absolute;
		left: 0;
		top: 0;
		display: flex;
		gap: 8px;
		visibility: hidden;
		pointer-events: none;
		white-space: nowrap;
	}
	.measure .chip.more {
		margin-left: 0;
	}

	.expr {
		display: flex;
		align-items: center;
		gap: 12px;
		height: 32px;
		padding: 0 4px;
		font-size: 13px;
	}
	/* The label is quiet: the slider and its value carry the meaning. */
	.expr .lbl {
		flex: none;
		font-size: 12px;
		color: var(--pill-faint);
	}
	.slider {
		position: relative;
		flex: 1 1 auto;
		height: 32px;
		padding: 0 9px;
		display: flex;
		align-items: center;
		cursor: pointer;
		touch-action: none;
	}
	.expr.off .slider {
		cursor: default;
	}
	.track {
		position: relative;
		width: 100%;
		height: 4px;
		border-radius: 2px;
		background: var(--track);
	}
	.fill {
		position: absolute;
		left: 0;
		top: 0;
		bottom: 0;
		border-radius: 2px;
		background: color-mix(in srgb, var(--primary) 70%, transparent);
		transition: width 120ms ease-out;
	}
	.stop {
		position: absolute;
		top: 50%;
		width: 6px;
		height: 6px;
		margin: -3px 0 0 -3px;
		border-radius: 50%;
		background: color-mix(in srgb, var(--pill-fg) 24%, transparent);
	}
	.stop.passed {
		background: var(--primary);
	}
	.thumb {
		position: absolute;
		top: 50%;
		width: 16px;
		height: 16px;
		margin: -8px 0 0 -8px;
		border-radius: 50%;
		background: var(--thumb);
		box-shadow:
			0 0 0 1.5px var(--primary),
			0 1px 3px rgba(16, 24, 32, 0.22);
		transition:
			left 120ms ease-out,
			transform 120ms ease-out;
	}
	.slider:hover .thumb,
	.slider.dragging .thumb {
		transform: scale(1.12);
	}
	.slider.dragging .thumb,
	.slider.dragging .fill {
		transition: none;
	}
	.val {
		flex: none;
		width: 76px;
		font-weight: 500;
		color: var(--pill-fg);
	}
	.expr.off .val {
		color: var(--pill-sub);
	}
	.expr.off .track {
		opacity: 0.6;
	}

	/* A feedback recording: what it is about. */
	.about {
		display: flex;
		flex-direction: column;
		justify-content: center;
		gap: 4px;
		height: 40px;
		padding: 0 4px;
		min-width: 0;
	}
	.about-line {
		display: flex;
		align-items: center;
		gap: 6px;
		min-width: 0;
		font-size: 13px;
		white-space: nowrap;
	}
	.about-line :global(.i) {
		flex: none;
		width: 14px;
		height: 14px;
		color: var(--primary-ink);
	}
	.about-line .lbl {
		flex: none;
		color: var(--pill-sub);
	}
	.quote {
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		font-weight: 500;
		color: var(--pill-fg);
	}
	.about-hint {
		padding-left: 20px;
		font-size: 12px;
		color: var(--pill-faint);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	/* ---- bottom row slots: lead 16, wave 72, time 40, then buttons ---- */
	.lead {
		flex: none;
		width: 16px;
		height: 16px;
		display: grid;
		place-items: center;
	}
	.rec {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--rec);
		box-shadow: 0 0 0 0 color-mix(in srgb, var(--rec) 45%, transparent);
		animation: breathe 1.6s ease-in-out infinite;
	}
	.work {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--pill-sub);
		animation: soft 1.4s ease-in-out infinite;
	}
	.slot-wave {
		flex: none;
		width: 72px;
		height: 20px;
		display: flex;
		align-items: center;
		position: relative;
		overflow: hidden;
	}
	.wave {
		display: flex;
		align-items: center;
		justify-content: space-between;
		width: 70px;
		height: 20px;
	}
	.wave i {
		display: block;
		width: 2px;
		border-radius: 1px;
		background: var(--wave-ink);
		transition: height 70ms linear;
	}
	.shimmer .wave i {
		background: var(--pill-sub);
		opacity: 0.55;
	}
	.shimmer::after {
		content: '';
		position: absolute;
		inset: 0;
		background: linear-gradient(
			90deg,
			transparent 0%,
			color-mix(in srgb, var(--pill-fg) 55%, transparent) 50%,
			transparent 100%
		);
		mix-blend-mode: overlay;
		transform: translateX(-100%);
		animation: sweep 1.3s ease-in-out infinite;
	}
	.silent {
		font-size: 12px;
		font-weight: 500;
		color: var(--warn);
	}
	.time {
		flex: none;
		width: 40px;
		font-size: 13px;
		font-weight: 500;
		font-variant-numeric: tabular-nums;
		letter-spacing: 0.01em;
		color: var(--pill-fg);
	}
	.msg {
		flex: 1 1 auto;
		min-width: 0;
		overflow: hidden;
		text-overflow: ellipsis;
		font-size: 13px;
	}
	.title {
		font-weight: 500;
		color: var(--pill-fg);
	}
	.aside {
		color: var(--pill-sub);
	}
	.tools {
		flex: none;
		display: flex;
		align-items: center;
		gap: 4px;
		margin-left: auto;
	}
	.ic {
		all: unset;
		box-sizing: border-box;
		width: 32px;
		height: 32px;
		border-radius: 50%;
		display: grid;
		place-items: center;
		color: var(--pill-sub);
		cursor: pointer;
		transition:
			background-color 120ms ease-out,
			color 120ms ease-out;
	}
	.ic :global(.i) {
		width: 14px;
		height: 14px;
	}
	.ic:hover {
		background: var(--pill-hover);
		color: var(--pill-fg);
	}
	.ic:active,
	.tb:active:not(.dim),
	.chip:active {
		background: var(--pill-press);
	}
	.ic.stop {
		background: var(--pill-hover);
		color: var(--pill-fg);
	}
	.ic.stop:hover {
		background: var(--pill-press);
	}
	.sq {
		width: 10px;
		height: 10px;
		border-radius: 3px;
		background: currentColor;
	}
	.tb {
		all: unset;
		box-sizing: border-box;
		height: 32px;
		padding: 0 12px;
		border-radius: 16px;
		display: inline-flex;
		align-items: center;
		gap: 6px;
		font-size: 13px;
		font-weight: 500;
		color: var(--pill-fg);
		cursor: pointer;
		transition:
			background-color 120ms ease-out,
			color 120ms ease-out;
	}
	.tb:hover {
		background: var(--pill-hover);
	}
	.tb :global(.i) {
		flex: none;
		width: 14px;
		height: 14px;
	}
	/* A notice's actions: soft tonal pills, so they read as buttons. */
	.tb.act {
		background: var(--pill-hover);
	}
	.tb.act:hover {
		background: var(--pill-press);
	}
	/* Feedback / Back to dictation: always there while recording. */
	.tb.fbk {
		margin-right: 4px;
		background: var(--pill-hover);
		box-shadow: inset 0 0 0 1px var(--pill-line);
	}
	.tb.fbk:hover:not(.dim) {
		background: var(--pill-press);
	}
	.tb.dim {
		color: var(--pill-sub);
		background: transparent;
		opacity: 0.6;
		cursor: default;
	}
	.lead :global(.i) {
		width: 16px;
		height: 16px;
	}
	.lead :global(.n-ok) {
		color: var(--ok);
	}
	.lead :global(.n-quiet) {
		color: var(--pill-sub);
	}
	.lead :global(.n-warn) {
		color: var(--warn);
	}
	.lead :global(.n-bad) {
		color: var(--bad);
	}
	.ic:focus-visible,
	.tb:focus-visible,
	.chip:focus-visible,
	.opt:focus-visible {
		outline: 2px solid var(--ring);
		outline-offset: 1px;
	}

	/* ---- above the card: the More list and the hint ---- */
	.pop {
		position: absolute;
		width: max-content;
		min-width: 168px;
		max-width: 296px;
		max-height: 168px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		padding: 6px;
		border-radius: 20px;
		background: var(--pill-bg);
		box-shadow:
			inset 0 0 0 1px var(--pill-line),
			var(--pill-shadow);
		outline: none;
	}
	.opts {
		display: flex;
		flex-direction: column;
		gap: 2px;
		overflow-y: auto;
		scrollbar-width: thin;
	}
	.opt {
		all: unset;
		box-sizing: border-box;
		flex: none;
		height: 32px;
		padding: 0 12px;
		border-radius: 16px;
		display: flex;
		align-items: center;
		font-size: 13px;
		font-weight: 500;
		color: var(--pill-fg);
		white-space: nowrap;
		cursor: pointer;
		transition: background-color 120ms ease-out;
	}
	.opt-name.cut {
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.opt:hover {
		background: var(--pill-hover);
	}
	.opt:active {
		background: var(--pill-press);
	}
	.note {
		position: absolute;
		left: 50%;
		transform: translateX(-50%);
		max-width: 480px;
		height: 32px;
		box-sizing: border-box;
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 0 14px;
		border-radius: 16px;
		background: var(--pill-bg);
		box-shadow:
			inset 0 0 0 1px var(--pill-line),
			var(--pill-shadow);
		font-size: 13px;
		color: var(--warn);
		transition: opacity 140ms ease-in;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.note :global(.i) {
		width: 14px;
		height: 14px;
		flex: none;
	}

	@keyframes appear {
		from {
			opacity: 0;
			transform: translateY(4px);
		}
	}
	@keyframes appear-down {
		from {
			opacity: 0;
			transform: translateY(-4px);
		}
	}
	@keyframes breathe {
		0%,
		100% {
			box-shadow: 0 0 0 0 color-mix(in srgb, var(--rec) 45%, transparent);
		}
		50% {
			box-shadow: 0 0 0 4px color-mix(in srgb, var(--rec) 0%, transparent);
		}
	}
	@keyframes soft {
		0%,
		100% {
			opacity: 0.45;
		}
		50% {
			opacity: 1;
		}
	}
	@keyframes sweep {
		to {
			transform: translateX(100%);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.card,
		.rec,
		.work,
		.shimmer::after {
			animation: none;
		}
		.card,
		.card.leaving,
		.wave i,
		.ic,
		.tb,
		.chip,
		.opt,
		.fill,
		.thumb {
			transition: none;
		}
		.card.leaving,
		.stage.top .card.leaving {
			transform: none;
		}
	}
</style>
