/**
 * VoiceFlow A2 (0.9.5), card in 0.9.8: the recording bar's fixed geometry.
 *
 * The bar window has ONE size for every state of every dictation, and Rust
 * places it once per dictation (`bar_target` unlocks, the first
 * `bar_present` locks). Nothing the bar draws may change the window's size
 * or position: states swap their contents inside the same card.
 *
 * 0.9.8: while a dictation records, the bar is a card with three rows
 * (styles, expression, and the bottom row that 0.9.5 called the pill). In
 * every other state only the bottom row is left. The card is anchored to the
 * window's bottom edge, so it grows and shrinks upward only, and the bottom
 * row is at exactly the same place in every state.
 *
 * The window is taller than the card at its largest: the area above it is
 * reserved for the "More styles" list and the focus hint. On Windows a
 * window region limits the window to the parts that are drawn (`hitRects`),
 * so the empty area is not there at all: clicks go to the app underneath.
 *
 * 0.9.9: chips show whole style names, so they may wrap to a second row.
 * The card then grows upward by one chip row; the window is tall enough for
 * two chip rows plus the open More list, so it still never changes size.
 * Every size is a multiple of 4 CSS px (whole pixels at 125-200 %) and the
 * spacing follows an 8 px rhythm.
 *
 * 0.9.12: the bar can sit at the top of the screen. The layout is then a
 * mirror: the card is anchored to the window's top edge, its fixed row is
 * its first 48 px and never moves, it grows DOWNWARD, and the More list and
 * the hint open below it. Every function takes the edge (bottom by default).
 *
 * All values are CSS pixels; Rust scales them by the monitor's DPI.
 */
import type { BarEdge, DictationState } from './contract';

export type { BarEdge };

/** The edge a "Position" setting puts the bar on ('top-center' ... : top). */
export function edgeOf(position: string | null | undefined): BarEdge {
	return typeof position === 'string' && position.startsWith('top') ? 'top' : 'bottom';
}

/** The card's width, in every state. */
export const CARD_WIDTH = 480;
/** The bottom row (today's pill): rec dot, wave, time, buttons. */
export const ROW_HEIGHT = 48;
/** The card's inner padding (sides and top). */
export const CARD_PADDING = 16;
/** One chip row, and the expression row. */
export const CHIP_HEIGHT = 32;
/** Between rows. */
export const ROW_GAP = 8;
/** At most this many chip rows; chips that need a third go to More. */
export const MAX_CHIP_ROWS = 2;
/** A dictation that is recording, one chip row: styles, expression, bottom row. */
export const CARD_HEIGHT_DICTATION =
	CARD_PADDING + CHIP_HEIGHT + ROW_GAP + CHIP_HEIGHT + ROW_GAP + ROW_HEIGHT;
/** The same with two chip rows (long style names). */
export const CARD_HEIGHT_DICTATION_TALL = CARD_HEIGHT_DICTATION + CHIP_HEIGHT + ROW_GAP;
/** A feedback recording: the "Feedback on" lines and the bottom row. */
export const CARD_HEIGHT_FEEDBACK = CARD_PADDING + 40 + ROW_GAP + ROW_HEIGHT;
/** The card's corners (the 48 px row alone is then a full pill). */
export const CARD_RADIUS = 24;
/** The width chips may use in a row: the card less its padding. */
export const CHIPS_WIDTH = CARD_WIDTH - 2 * CARD_PADDING;
/** Transparent room around the drawn parts, for their shadow. */
export const MARGIN = 16;
/** The "More styles" list's tallest size (it scrolls beyond this). */
export const POPOVER_MAX_HEIGHT = 168;
/** The "More styles" list's width: as wide as its longest name, within these. */
export const POPOVER_MIN_WIDTH = 168;
export const POPOVER_MAX_WIDTH = 296;
/** Between the list (or hint) and the card. */
export const POPOVER_GAP = 8;
/** The focus hint above the card. */
export const NOTE_HEIGHT = 30;
/** The card's grow and shrink. */
export const RESIZE_MS = 180;

/** 0.9.5 names, kept for the bottom row. */
export const PILL_WIDTH = CARD_WIDTH;
export const PILL_HEIGHT = ROW_HEIGHT;

export const CARD_MAX_HEIGHT = CARD_HEIGHT_DICTATION_TALL;
export const WINDOW_WIDTH = CARD_WIDTH + 2 * MARGIN;
export const WINDOW_HEIGHT =
	MARGIN + POPOVER_MAX_HEIGHT + POPOVER_GAP + CARD_MAX_HEIGHT + MARGIN;

export type Rect = { x: number; y: number; width: number; height: number };

/**
 * How tall the card is for a view: the upper rows only while a recording
 * listens; `chipRows` (1 or 2) is how many rows its chips need.
 */
export function cardHeightFor(
	view: Pick<DictationState, 'phase' | 'purpose'> | null,
	chipRows = 1,
): number {
	if (!view || view.phase !== 'listening') return ROW_HEIGHT;
	if (view.purpose === 'feedback') return CARD_HEIGHT_FEEDBACK;
	const rows = Number.isFinite(chipRows)
		? Math.max(1, Math.min(MAX_CHIP_ROWS, Math.round(chipRows)))
		: 1;
	return CARD_HEIGHT_DICTATION + (rows - 1) * (CHIP_HEIGHT + ROW_GAP);
}

/** Where the card is drawn in the window, at a given height. */
export function cardRect(height: number, edge: BarEdge = 'bottom'): Rect {
	return {
		x: MARGIN,
		y: edge === 'top' ? MARGIN : WINDOW_HEIGHT - MARGIN - height,
		width: CARD_WIDTH,
		height,
	};
}

/** The fixed row (the card's row at its edge): the same rectangle in every state. */
export function rowRect(edge: BarEdge = 'bottom'): Rect {
	return {
		x: MARGIN,
		y: edge === 'top' ? MARGIN : WINDOW_HEIGHT - MARGIN - ROW_HEIGHT,
		width: CARD_WIDTH,
		height: ROW_HEIGHT,
	};
}

/** The bottom row: the same rectangle in every state. */
export const ROW_RECT: Rect = rowRect('bottom');
/** 0.9.5 name. */
export const PILL_RECT = ROW_RECT;

/** The card at a height and its shadow: always part of the window. */
export function cardBand(height: number, edge: BarEdge = 'bottom'): Rect {
	const h = height + 2 * MARGIN;
	return { x: 0, y: edge === 'top' ? 0 : WINDOW_HEIGHT - h, width: WINDOW_WIDTH, height: h };
}

/** The band of the bottom row alone (a notice, transcribing...). */
export const PILL_BAND: Rect = cardBand(ROW_HEIGHT);

/** The area beyond the tallest card (the list and the hint): above it, or below it at the top. */
export function reservedRect(edge: BarEdge = 'bottom'): Rect {
	const tall = cardBand(CARD_MAX_HEIGHT, edge);
	return edge === 'top'
		? { x: 0, y: tall.height, width: WINDOW_WIDTH, height: WINDOW_HEIGHT - tall.height }
		: { x: 0, y: 0, width: WINDOW_WIDTH, height: tall.y };
}

/** The area above the tallest card (the list and the hint). */
export const RESERVED_RECT: Rect = reservedRect('bottom');

/**
 * The parts of the window that exist (clickable and drawn): the card at
 * `cardHeight` with its shadow, plus `above`, the list's or hint's own box
 * in window CSS px as measured by the page, grown by the shadow margin and
 * joined to the card's band (down to it, or up to it at the top). Only that
 * box's width: the space beside it lets clicks through to the app underneath.
 */
export function hitRects(cardHeight: number, above: Rect | null, edge: BarEdge = 'bottom'): Rect[] {
	const height = Number.isFinite(cardHeight)
		? Math.max(ROW_HEIGHT, Math.min(CARD_MAX_HEIGHT, cardHeight))
		: ROW_HEIGHT;
	const band = cardBand(height, edge);
	if (
		!above ||
		!(above.width > 0) ||
		!(above.height > 0) ||
		!Number.isFinite(above.x) ||
		!Number.isFinite(above.y)
	) {
		return [band];
	}
	const left = Math.max(0, Math.floor(above.x - MARGIN));
	const right = Math.min(WINDOW_WIDTH, Math.ceil(above.x + above.width + MARGIN));
	if (edge === 'top') {
		const bandEnd = band.y + band.height;
		const bottom = Math.min(WINDOW_HEIGHT, Math.ceil(above.y + above.height + MARGIN));
		if (bottom <= bandEnd || right <= left) return [band];
		return [band, { x: left, y: bandEnd, width: right - left, height: bottom - bandEnd }];
	}
	const top = Math.max(0, Math.floor(above.y - MARGIN));
	if (band.y <= top || right <= left) return [band];
	return [band, { x: left, y: top, width: right - left, height: band.y - top }];
}

/** "0:09", "1:00", "12:34": the timer text (its slot fits "59:59"). */
export function formatElapsed(ms: number): string {
	const seconds = Math.max(0, Math.floor(ms / 1000));
	return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
