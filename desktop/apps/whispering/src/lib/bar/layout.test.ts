/**
 * VoiceFlow A2 (0.9.5), 0.9.8 and 0.9.9: the bar keeps one size and place
 * for a whole dictation; the card grows upward only (0.9.9: also for a
 * second chip row) and its bottom row never moves; the More list belongs to
 * one recording; notices stay short and hide by themselves.
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { DictationState } from './contract';
import { demoState, DEMO_NAMES } from './fixtures';
import {
	CARD_HEIGHT_DICTATION,
	CARD_HEIGHT_DICTATION_TALL,
	CARD_HEIGHT_FEEDBACK,
	CARD_RADIUS,
	CHIP_HEIGHT,
	ROW_GAP,
	CARD_MAX_HEIGHT,
	CARD_WIDTH,
	MARGIN,
	PILL_BAND,
	POPOVER_GAP,
	POPOVER_MAX_HEIGHT,
	RESERVED_RECT,
	ROW_HEIGHT,
	ROW_RECT,
	WINDOW_HEIGHT,
	WINDOW_WIDTH,
	cardBand,
	cardHeightFor,
	cardRect,
	formatElapsed,
	hitRects,
} from './geometry';
import { noticeFor } from './notice';
import { createPopover, popoverAllowed } from './popover';
import {
	ACTIVE_OPACITY,
	NOTICE_OPACITY,
	layerKey,
	pillOpacity,
	presenceFor,
} from './presence';

const listening = (over: Partial<DictationState> = {}): DictationState => ({
	...demoState('listening', 1_000)!,
	...over,
});

const page = readFileSync(join(import.meta.dir, '../../routes/bar/+page.svelte'), 'utf8');
const rust = readFileSync(join(import.meta.dir, '../../../src-tauri/src/bar.rs'), 'utf8');

describe('fixed geometry', () => {
	test('one window size, with room for the More list above the tallest card', () => {
		expect(ROW_HEIGHT).toBe(48);
		expect(CARD_WIDTH).toBeGreaterThanOrEqual(420);
		expect(CARD_WIDTH).toBeLessThanOrEqual(480);
		expect(CARD_HEIGHT_DICTATION).toBeGreaterThanOrEqual(140);
		expect(CARD_HEIGHT_DICTATION).toBeLessThanOrEqual(160);
		// 0.9.9: room for two chip rows (whole style names) and the More list.
		expect(CARD_HEIGHT_DICTATION_TALL).toBe(CARD_HEIGHT_DICTATION + CHIP_HEIGHT + ROW_GAP);
		expect(CARD_MAX_HEIGHT).toBe(CARD_HEIGHT_DICTATION_TALL);
		// Rounder: the 48 px row alone is a full pill.
		expect(CARD_RADIUS).toBe(ROW_HEIGHT / 2);
		expect(page).toContain(`border-radius: ${CARD_RADIUS}px;`);
		expect(WINDOW_WIDTH).toBe(CARD_WIDTH + 2 * MARGIN);
		expect(WINDOW_HEIGHT).toBe(MARGIN + POPOVER_MAX_HEIGHT + POPOVER_GAP + CARD_MAX_HEIGHT + MARGIN);
		expect(ROW_RECT.y + ROW_RECT.height + MARGIN).toBe(WINDOW_HEIGHT);
		// Whole physical pixels at the common scales, so no DPI rounding drift.
		const sizes = [
			WINDOW_WIDTH,
			WINDOW_HEIGHT,
			CARD_WIDTH,
			ROW_HEIGHT,
			CARD_HEIGHT_DICTATION,
			CARD_HEIGHT_DICTATION_TALL,
			CARD_HEIGHT_FEEDBACK,
		];
		for (const scale of [1, 1.25, 1.5, 1.75, 2]) {
			for (const v of sizes) expect(Number.isInteger(v * scale)).toBe(true);
		}
	});

	test('the card is three rows only while a recording listens', () => {
		expect(cardHeightFor(listening())).toBe(CARD_HEIGHT_DICTATION);
		expect(cardHeightFor(listening({ purpose: 'feedback' }))).toBe(CARD_HEIGHT_FEEDBACK);
		expect(CARD_HEIGHT_FEEDBACK).toBeLessThan(CARD_HEIGHT_DICTATION);
		expect(CARD_HEIGHT_FEEDBACK).toBeGreaterThan(ROW_HEIGHT);
		const later = ['transcribing', 'tidying', 'pasted', 'copied', 'failed', 'cancelled'] as const;
		for (const phase of later) expect(cardHeightFor(listening({ phase }))).toBe(ROW_HEIGHT);
		expect(cardHeightFor(null)).toBe(ROW_HEIGHT);
		// 0.9.9: a second chip row, never a third; a feedback card has no chips.
		expect(cardHeightFor(listening(), 2)).toBe(CARD_HEIGHT_DICTATION_TALL);
		expect(cardHeightFor(listening(), 3)).toBe(CARD_HEIGHT_DICTATION_TALL);
		expect(cardHeightFor(listening(), Number.NaN)).toBe(CARD_HEIGHT_DICTATION);
		expect(cardHeightFor(listening({ purpose: 'feedback' }), 2)).toBe(CARD_HEIGHT_FEEDBACK);
		expect(cardHeightFor(listening({ phase: 'pasted' }), 2)).toBe(ROW_HEIGHT);
	});

	test('the bottom row is at the same place in every state; the card grows upward only', () => {
		const bottoms = new Set<number>();
		const rows = new Set<string>();
		for (const name of DEMO_NAMES) {
			for (const chipRows of [1, 2]) {
				const view = demoState(name, 1)!;
				const card = cardRect(cardHeightFor(view, chipRows));
				bottoms.add(card.y + card.height);
				// The bottom row is the card's last 48 px, whatever the card's height.
				rows.add(JSON.stringify({ x: card.x, y: card.y + card.height - ROW_HEIGHT, w: card.width }));
				expect(card.x).toBe(MARGIN);
				expect(card.width).toBe(CARD_WIDTH);
				expect(card.y).toBeGreaterThanOrEqual(RESERVED_RECT.height + MARGIN);
			}
		}
		expect(bottoms).toEqual(new Set([WINDOW_HEIGHT - MARGIN]));
		expect(rows).toEqual(new Set([JSON.stringify({ x: ROW_RECT.x, y: ROW_RECT.y, w: ROW_RECT.width })]));
		// In the page: the card is anchored at the bottom and only its height
		// changes; the bottom row is pinned to the card's bottom edge.
		expect(page).toMatch(/\.card \{\s*position: absolute;\s*left: 16px;\s*bottom: 16px;\s*width: 480px;/);
		expect(page).toContain('style:height={`${cardHeight}px`}');
		expect(page).toMatch(/\.row \{\s*position: absolute;\s*left: 0;\s*right: 0;\s*bottom: 0;\s*height: 48px;/);
		expect(page).toMatch(/\.upper \{\s*position: absolute;\s*left: 0;\s*right: 0;\s*bottom: 48px;/);
		// The upper rows are as tall as the card's chip rows say.
		expect(page).toContain('style:height={`${cardHeightFor(u, chipRows) - ROW_HEIGHT}px`}');
		expect(page).toContain('const cardHeight = $derived(cardHeightFor(view, chipRows));');
		expect(page).toContain('height 180ms');
	});

	test('the page asks for the same window size in every state (never a measured one)', () => {
		// Before 0.9.5 the page measured its content per state and the window
		// was resized and re-centred: the shift. Now only the constants go.
		expect(page).not.toContain('getBoundingClientRect().width');
		expect(page).toMatch(/bar_present', \{\s*width: WINDOW_WIDTH,\s*height: WINDOW_HEIGHT,/);
		// Rust's fallback size is the same window.
		expect(rust).toContain(`const INITIAL_SIZE: (f64, f64) = (${WINDOW_WIDTH}.0, ${WINDOW_HEIGHT}.0);`);
	});

	test('the window is presented again only for a new dictation, never per state', () => {
		// `present()` is called from apply() only on a new recording or after
		// being hidden; Rust also locks the place until the next bar_target.
		expect(page).toMatch(/if \(wasHidden \|\| newRecording\) void present\(!wasHidden\);/);
		expect(rust).toContain('i.placement.reset();');
		expect(rust).toContain('i.placement.rect(area');
	});

	test('the region is the card at its height plus what is drawn above it', () => {
		expect(hitRects(ROW_HEIGHT, null)).toEqual([PILL_BAND]);
		expect(PILL_BAND).toEqual({ x: 0, y: WINDOW_HEIGHT - 80, width: WINDOW_WIDTH, height: 80 });
		const one = cardBand(CARD_HEIGHT_DICTATION);
		expect(hitRects(CARD_HEIGHT_DICTATION, null)).toEqual([one]);
		const tall = cardBand(CARD_MAX_HEIGHT);
		expect(hitRects(CARD_MAX_HEIGHT, null)).toEqual([tall]);
		expect(tall.y).toBe(RESERVED_RECT.height);
		expect(tall.y + tall.height).toBe(WINDOW_HEIGHT);
		// The More list above the tall card: only its own width (and shadow);
		// the space beside it lets clicks through.
		expect(hitRects(CARD_MAX_HEIGHT, { x: 256, y: 60, width: 200, height: 76 })).toEqual([
			tall,
			{ x: 240, y: 44, width: 232, height: tall.y - 44 },
		]);
		// The focus hint above a notice: joined to the short band.
		const note = hitRects(ROW_HEIGHT, { x: 100.5, y: 240, width: 200, height: 30 });
		expect(note[1]).toEqual({ x: 84, y: 224, width: 233, height: PILL_BAND.y - 224 });
		// Never outside the window, never into the band; bad input is the band alone.
		const wild = hitRects(CARD_MAX_HEIGHT, { x: -40, y: -50, width: 900, height: 400 });
		expect(wild[1]).toEqual({ x: 0, y: 0, width: WINDOW_WIDTH, height: tall.y });
		expect(hitRects(ROW_HEIGHT, { x: Number.NaN, y: 10, width: 10, height: 10 })).toEqual([PILL_BAND]);
		expect(hitRects(ROW_HEIGHT, { x: 0, y: 0, width: 0, height: 0 })).toEqual([PILL_BAND]);
		expect(hitRects(Number.NaN, null)).toEqual([PILL_BAND]);
		expect(hitRects(9_999, null)).toEqual([tall]);
	});

	test('the region grows at once and shrinks only after the card has shrunk', () => {
		// Growing: sent before the taller card can be clicked.
		expect(page).toMatch(/if \(growing\) \{\s*await tick\(\);\s*relayout\(\);\s*sendRegion\(Math\.max\(regionHeight, cardHeight\)\);/);
		// Shrinking: after the height change and the fade.
		expect(page).toContain('Math.max(FADE_MS, RESIZE_MS)) + 40');
		expect(page).toContain('void syncHit(cardHeight > heightBefore);');
		// 0.9.9: a second chip row is measured before the window is shown or grown.
		expect(page).toMatch(/async function present\(keep = false\) \{\s*clearTimeout\(shrinkTimer\);\s*await tick\(\);\s*relayout\(\);/);
		expect(page).toMatch(/if \(growing\) \{\s*await tick\(\);\s*relayout\(\);/);
	});

	test('the timer text fits one slot from 0:09 to 59:59', () => {
		expect(formatElapsed(9_400)).toBe('0:09');
		expect(formatElapsed(10_000)).toBe('0:10');
		expect(formatElapsed(60_000)).toBe('1:00');
		expect(formatElapsed(3_599_000)).toBe('59:59');
		expect(formatElapsed(-5)).toBe('0:00');
		expect(page).toContain('font-variant-numeric: tabular-nums;');
	});
});

describe('popover', () => {
	test('opens only for a dictation that is listening and can change style', () => {
		expect(popoverAllowed(listening())).toBe(true);
		expect(popoverAllowed(listening({ purpose: 'feedback' }))).toBe(false);
		expect(popoverAllowed(listening({ phase: 'transcribing' }))).toBe(false);
		expect(popoverAllowed(listening({ actions: ['stop', 'cancel'] }))).toBe(false);
		expect(popoverAllowed(null)).toBe(false);
		const pop = createPopover();
		expect(pop.toggle(listening({ phase: 'tidying' }))).toBe(false);
		expect(pop.openFor).toBeNull();
	});

	test('the style name toggles it; picking a style closes it', () => {
		const pop = createPopover();
		const view = listening();
		expect(pop.toggle(view)).toBe(true);
		expect(pop.isOpen(view)).toBe(true);
		expect(pop.toggle(view)).toBe(false);
		pop.toggle(view);
		pop.picked();
		expect(pop.isOpen(view)).toBe(false);
	});

	test('it belongs to one recording: a new one, Stop or feedback closes it', () => {
		const pop = createPopover();
		const a = listening({ recordingId: 'a' });
		pop.toggle(a);
		pop.viewChanged(listening({ recordingId: 'a', seq: 9 }));
		expect(pop.isOpen(a)).toBe(true);
		pop.viewChanged(listening({ recordingId: 'b' }));
		expect(pop.openFor).toBeNull();
		// Even if a view for "a" came back, it stays closed.
		expect(pop.isOpen(a)).toBe(false);

		pop.toggle(a);
		pop.viewChanged({ ...a, phase: 'transcribing', actions: [] });
		expect(pop.openFor).toBeNull();

		pop.toggle(a);
		pop.viewChanged({ ...a, purpose: 'feedback', actions: ['stop', 'cancel'] });
		expect(pop.openFor).toBeNull();

		pop.toggle(a);
		pop.viewChanged(null);
		expect(pop.openFor).toBeNull();
	});

	test('isOpen never reports open for another recording', () => {
		const pop = createPopover();
		pop.toggle(listening({ recordingId: 'a' }));
		expect(pop.isOpen(listening({ recordingId: 'b' }))).toBe(false);
	});

	test('it closes after a short idle, but not while the pointer is in it', () => {
		let t = 0;
		const pop = createPopover({ now: () => t, idleMs: 4000 });
		const view = listening();
		pop.toggle(view);
		t = 3999;
		expect(pop.tick()).toBe(false);
		pop.activity(true);
		t = 20_000;
		expect(pop.tick()).toBe(false);
		expect(pop.isOpen(view)).toBe(true);
		pop.activity(false);
		t = 23_999;
		expect(pop.tick()).toBe(false);
		t = 24_000;
		expect(pop.tick()).toBe(true);
		expect(pop.isOpen(view)).toBe(false);
	});

	test('the page sends commands with their own recording id and keeps focus rules', () => {
		expect(page).toContain("send({ type: 'set-style', recordingId, styleId })");
		// A drag sends each stop for the recording it started on, without
		// restoring focus mid-drag; the front is given back once, on release.
		expect(page).toMatch(
			/\{ type: 'set-expression', recordingId: drag\.recordingId, expression: level \},\s*\{ restore: false \},/,
		);
		expect(page).toMatch(/function sliderEnd\(event: PointerEvent\) \{[\s\S]*?drag = null;[\s\S]*?void restoreFocus\(\);/);
		// Opening More is a deliberate click too: the target gets focus back.
		expect(page).toMatch(/function toggleMore\(event: MouseEvent\) \{[\s\S]*?restoreFocus\(\)/);
		// Feedback with nothing to give feedback on still gives the front back.
		expect(page).toMatch(/function toggleFeedback\(\) \{[\s\S]*?else void restoreFocus\(\);/);
	});

	test('a drag starts from the level shown (one just sent may not be in the view yet)', () => {
		expect(page).toMatch(/drag = \{\s*pointerId: event\.pointerId,\s*recordingId: upperView\.recordingId,\s*level: shownLevel,\s*sent: shownLevel,/);
		// Bar commands leave one after another, in click order.
		expect(page).toContain('sending = sending.then(async () => {');
	});

	test('nothing on the bar takes focus', () => {
		// No focus() calls, no tab stop on the slider, and a press on a
		// control doesn't move DOM focus (its click still runs).
		expect(page).not.toMatch(/\.focus\(/);
		expect(page).not.toMatch(/role="slider"[^>]*tabindex/);
		expect(page).toContain(`closest?.('button, [role="slider"]')) event.preventDefault();`);
		// The slider captures its pointer and ends once on up, cancel or lost capture.
		expect(page).toContain('el.setPointerCapture(event.pointerId);');
		for (const handler of ['onpointerup={sliderEnd}', 'onpointercancel={sliderEnd}', 'onlostpointercapture={sliderEnd}']) {
			expect(page).toContain(handler);
		}
		expect(page).toContain('touch-action: none;');
		// Rust keeps the window from activating on a click.
		expect(rust).toContain('return MA_NOACTIVATE as LRESULT;');
		expect(rust).toContain('.focusable(false)');
	});

	test('the Feedback button is always there while recording, never icon-only', () => {
		expect(page).toMatch(/<Icon name="chat-circle" class="i" \/>Feedback<\/button/);
		expect(page).toContain('Back to dictation</button');
		expect(page).toContain("'No dictation to give feedback on yet'");
		// Unavailable is aria-disabled (the tooltip still shows), not hidden.
		expect(page).toContain("aria-disabled={!can('use-as-feedback')}");
	});
});

describe('presence and opacity', () => {
	test('active states are slightly see-through; notices settle to 80 % until hovered', () => {
		expect(pillOpacity('listening', 0, false)).toBe(ACTIVE_OPACITY);
		expect(pillOpacity('tidying', 99_000, false)).toBe(ACTIVE_OPACITY);
		expect(pillOpacity('pasted', 500, false)).toBe(1);
		expect(pillOpacity('pasted', 1000, false)).toBe(NOTICE_OPACITY);
		expect(pillOpacity('failed', 5000, true)).toBe(1);
		expect(pillOpacity('listening', 0, true)).toBe(1);
	});

	test('a timed notice lingers while hovered; anything else leaves', () => {
		const pasted = demoState('pasted', 1)!;
		const failed = demoState('failed-tidy', 1)!;
		const deadline = pasted.hidesAt!;
		expect(presenceFor(pasted, null, true, deadline + 20)).toBe('linger');
		expect(presenceFor(pasted, null, false, deadline + 20)).toBe('leave');
		// Hidden on purpose before its time (Feedback, ×): leaves even if hovered.
		expect(presenceFor(pasted, null, true, deadline - 2000)).toBe('leave');
		// 0.9.9: a problem hides by itself too, and the pointer keeps it the same way.
		expect(presenceFor(failed, null, true, failed.hidesAt!)).toBe('linger');
		expect(presenceFor(failed, null, true, failed.hidesAt! - 2000)).toBe('leave');
		expect(presenceFor(null, null, true)).toBe('leave');
		expect(presenceFor(pasted, failed, true)).toBe('show');
		// A click on a lingering notice ends it.
		expect(page).toMatch(/as BarCommand\);\s+\/\/ A notice kept[^\n]*\n\s+if \(lingering\) leave\(\);/);
	});

	test('contents crossfade per phase, never per timer tick or level', () => {
		const a = listening({ recordingId: 'a' });
		const tick: DictationState = { ...a, seq: 2, startedAt: 5 };
		const styled: DictationState = { ...a, style: { id: 'work', name: 'Work', kind: 'business' } };
		expect(layerKey(tick)).toBe(layerKey(a));
		expect(layerKey(styled)).toBe(layerKey(a));
		expect(layerKey({ ...a, phase: 'transcribing' })).not.toBe(layerKey(a));
	});
});

describe('notices', () => {
	test('every demo state fits: one line, at most two text buttons', () => {
		for (const name of DEMO_NAMES) {
			const view = demoState(name, 1)!;
			const notice = noticeFor(view);
			if (['listening', 'transcribing', 'tidying'].includes(view.phase)) {
				expect(notice).toBeNull();
				continue;
			}
			expect(notice).not.toBeNull();
			expect(notice!.buttons.length).toBeLessThanOrEqual(2);
			for (const b of notice!.buttons) expect(view.actions).toContain(b.action);
			expect(notice!.title.length).toBeLessThanOrEqual(40);
		}
	});

	test('Pasted shows the app and Feedback and fades by itself; Copied fades too, and can be closed', () => {
		const pasted = noticeFor(demoState('pasted', 1)!)!;
		expect(pasted.title).toBe('Pasted');
		expect(pasted.aside).toBe('Kite');
		expect(pasted.buttons).toEqual([{ action: 'feedback', label: 'Feedback' }]);
		expect(pasted.closable).toBe(false);
		const copied = demoState('copied', 1)!;
		expect(copied.hidesAt).toBe(1 + 4000);
		expect(noticeFor(copied)!.closable).toBe(true);
	});

	test('a failed tidy-up offers Retry and Paste raw; the details are in the tooltip', () => {
		const n = noticeFor(demoState('failed-tidy', 1)!)!;
		expect(n.title).toBe('Not pasted');
		expect(n.buttons.map((b) => b.label)).toEqual(['Retry', 'Paste raw']);
		expect(n.detail).toContain('Tidy-up took over 10 seconds');
		// 0.9.12: Nothing heard (3 s or more) offers Retry.
		expect(noticeFor(demoState('failed-empty', 1)!)!.buttons.map((b) => b.label)).toEqual(['Retry']);
	});
});
