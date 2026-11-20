/**
 * VoiceFlow 0.9.12: the bar at the top of the screen is a mirror of the bar
 * at the bottom. The fixed row is the card's first 48 px and sits at the
 * same place in every state; the card grows down; the More list and the
 * hint open below it; the window region (what catches clicks) follows.
 * The window keeps one size, so DPI only scales it (Rust, `bar.rs` tests).
 */
import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { demoState, DEMO_NAMES } from './fixtures';
import {
	CARD_HEIGHT_DICTATION,
	CARD_MAX_HEIGHT,
	CARD_WIDTH,
	MARGIN,
	POPOVER_GAP,
	POPOVER_MAX_HEIGHT,
	ROW_HEIGHT,
	ROW_RECT,
	WINDOW_HEIGHT,
	WINDOW_WIDTH,
	cardBand,
	cardHeightFor,
	cardRect,
	edgeOf,
	hitRects,
	reservedRect,
	rowRect,
	type BarEdge,
	type Rect,
} from './geometry';

const page = readFileSync(join(import.meta.dir, '../../routes/bar/+page.svelte'), 'utf8').replace(/\r\n/g, '\n');
const controller = readFileSync(join(import.meta.dir, 'controller.ts'), 'utf8').replace(/\r\n/g, '\n');
const settings = readFileSync(join(import.meta.dir, '../settings/settings.ts'), 'utf8').replace(/\r\n/g, '\n');
const settingsPage = readFileSync(
	join(import.meta.dir, '../../routes/(app)/(config)/settings/+page.svelte'),
	'utf8',
).replace(/\r\n/g, '\n');
const rust = readFileSync(join(import.meta.dir, '../../../src-tauri/src/bar.rs'), 'utf8').replace(/\r\n/g, '\n');

/** The rectangle mirrored top to bottom inside the window. */
const flip = (r: Rect): Rect => ({ ...r, y: WINDOW_HEIGHT - r.y - r.height });

describe('the top edge mirrors the bottom', () => {
	test('settings map to an edge; anything unknown is the bottom', () => {
		expect(edgeOf('top-center')).toBe('top');
		expect(edgeOf('top-right')).toBe('top');
		expect(edgeOf('bottom-left')).toBe('bottom');
		expect(edgeOf(undefined)).toBe('bottom');
		expect(edgeOf('sideways')).toBe('bottom');
	});

	test('the fixed row stays put across every state and chip count, at each edge', () => {
		for (const edge of ['bottom', 'top'] as BarEdge[]) {
			const rows = new Set<string>();
			for (const name of DEMO_NAMES) {
				for (const chipRows of [1, 2]) {
					const card = cardRect(cardHeightFor(demoState(name, 1), chipRows), edge);
					// The fixed row is the card's edge-side 48 px.
					const rowY = edge === 'top' ? card.y : card.y + card.height - ROW_HEIGHT;
					rows.add(`${card.x},${rowY},${card.width}`);
					// The card stays inside its band, clear of the reserved area.
					const reserved = reservedRect(edge);
					if (edge === 'top') expect(card.y + card.height + MARGIN).toBeLessThanOrEqual(reserved.y);
					else expect(card.y - MARGIN).toBeGreaterThanOrEqual(reserved.height);
				}
			}
			const row = rowRect(edge);
			expect(rows).toEqual(new Set([`${row.x},${row.y},${row.width}`]));
		}
		expect(rowRect('top')).toEqual({ x: MARGIN, y: MARGIN, width: CARD_WIDTH, height: ROW_HEIGHT });
		expect(rowRect('bottom')).toEqual(ROW_RECT);
	});

	test('every rectangle at the top is the bottom one mirrored', () => {
		for (const h of [ROW_HEIGHT, CARD_HEIGHT_DICTATION, CARD_MAX_HEIGHT]) {
			expect(cardRect(h, 'top')).toEqual(flip(cardRect(h, 'bottom')));
			expect(cardBand(h, 'top')).toEqual(flip(cardBand(h, 'bottom')));
		}
		expect(rowRect('top')).toEqual(flip(rowRect('bottom')));
		expect(reservedRect('top')).toEqual(flip(reservedRect('bottom')));
		// The More list fits below the tallest card exactly as it fits above it.
		expect(reservedRect('top').height).toBe(reservedRect('bottom').height);
		expect(reservedRect('top').height).toBe(POPOVER_MAX_HEIGHT + POPOVER_GAP);
	});

	test('the click-through region mirrors: the list or hint joins the band from below', () => {
		for (const h of [ROW_HEIGHT, CARD_HEIGHT_DICTATION, CARD_MAX_HEIGHT]) {
			expect(hitRects(h, null, 'top')).toEqual([cardBand(h, 'top')]);
		}
		// The More list under the tallest card, measured by the page.
		const tall = cardBand(CARD_MAX_HEIGHT, 'top');
		const list: Rect = { x: 256, y: MARGIN + CARD_MAX_HEIGHT + POPOVER_GAP, width: 200, height: 76 };
		const bottomList = flip(list);
		const top = hitRects(CARD_MAX_HEIGHT, list, 'top');
		const bottom = hitRects(CARD_MAX_HEIGHT, bottomList, 'bottom');
		expect(top).toHaveLength(2);
		expect(top.map(flip)).toEqual(bottom);
		// Only the list's width (plus shadow): beside it, clicks pass through.
		expect(top[1]).toEqual({ x: 240, y: tall.height, width: 232, height: list.y + list.height + MARGIN - tall.height });
		// Never outside the window; bad or empty boxes are the band alone.
		const wild = hitRects(CARD_MAX_HEIGHT, { x: -40, y: 300, width: 900, height: 400 }, 'top');
		expect(wild[1]).toEqual({ x: 0, y: tall.height, width: WINDOW_WIDTH, height: WINDOW_HEIGHT - tall.height });
		expect(hitRects(ROW_HEIGHT, { x: Number.NaN, y: 10, width: 10, height: 10 }, 'top')).toEqual([
			cardBand(ROW_HEIGHT, 'top'),
		]);
		// A box that does not reach past the band adds nothing.
		expect(hitRects(CARD_MAX_HEIGHT, { x: 40, y: 20, width: 100, height: 30 }, 'top')).toEqual([tall]);
	});

	test('the page hangs the card from the top, rows reversed, list and hint below', () => {
		expect(page).toContain("class:top={edge === 'top'}");
		expect(page).toMatch(/\.stage\.top \.card \{\s*top: 16px;\s*bottom: auto;/);
		expect(page).toMatch(/\.stage\.top \.row \{\s*top: 0;\s*bottom: auto;/);
		expect(page).toMatch(/\.stage\.top \.upper \{\s*top: 48px;\s*bottom: auto;\s*flex-direction: column-reverse;/);
		// The list and the hint: below the card at the top, above it at the bottom.
		const below = "style:top={edge === 'top' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}";
		const above = "style:bottom={edge === 'bottom' ? `${MARGIN + cardHeight + POPOVER_GAP}px` : undefined}";
		expect(page.split(below)).toHaveLength(3);
		expect(page.split(above)).toHaveLength(3);
		// The region is built for the same edge as the layout.
		expect(page).toContain("hitRects(height, aboveRect(), edge)");
		// The edge changes only when the window is placed (a new dictation, or shown again).
		expect(page).toMatch(/if \(wasHidden \|\| newRecording\) edge = nextEdge === 'top' \? 'top' : 'bottom';/);
		expect(page).toContain('apply(next, payload.edge);');
	});
});

describe('where the bar goes', () => {
	test('settings: Show on (main monitor by default) and Position/Alignment kept in one value', () => {
		expect(settings).toContain("'bar.monitor': type.enumerated('primary', 'mouse', 'target').default('primary'),");
		expect(settings).toContain(
			".enumerated('bottom-center', 'bottom-left', 'bottom-right', 'top-center', 'top-left', 'top-right')",
		);
		expect(settings).toContain(".default('bottom-center'),");
		for (const label of ['Main monitor', 'The monitor your mouse is on', "The monitor of the app you're typing in"])
			expect(settingsPage).toContain(label);
		expect(settingsPage).toContain("{ value: 'top', label: 'Top' }");
		expect(settingsPage).toContain("settings.updateKey('bar.position', `${edge}-${side}` as BarPositionValue);");
	});

	test('the controller tells Rust the monitor and place first; views wait for it', () => {
		expect(controller).toMatch(/placing = invoke\('bar_target', \{[\s\S]*?position,\s*monitor: settings\.value\['bar\.monitor'\],/);
		expect(controller).toContain('void placing\n\t\t\t.then(() => import(\'@tauri-apps/api/event\'))');
		expect(controller).toContain('const message: BarStateMessage = { state: view, edge: placedEdge };');
		// A refusal is placed like a new dictation too.
		expect(controller).toMatch(/refused\([\s\S]*?placeBar\(\{/);
	});

	test('Rust: chosen at the start, kept until hidden, re-placed only for a display change', () => {
		expect(rust).toContain('pub fn bar_target(');
		expect(rust).toContain('monitor: Option<BarMonitor>,');
		expect(rust).toContain('let screen = screen_for(&app, mode, window_id.as_deref());');
		// Later presents keep the monitor; a vanished one hands over to the main one.
		expect(rust).toContain('Some(all) => relocate(&all, &locked.id).cloned().unwrap_or(locked),');
		// The display watcher re-places on the main thread, only while shown.
		expect(rust).toMatch(/displays\.run_on_main_thread\(move \|\| \{\s*if !BAR_VISIBLE\.load\(Ordering::Relaxed\) \|\| !display_changed\(&app\)/);
		// The window size Rust falls back on is still the page's one size.
		expect(rust).toContain(`const INITIAL_SIZE: (f64, f64) = (${WINDOW_WIDTH}.0, ${WINDOW_HEIGHT}.0);`);
	});
});
