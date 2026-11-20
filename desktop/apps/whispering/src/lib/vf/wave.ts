/** A number from a string, so one dictation always draws the same bars. */
export function seedOf(seed: number | string): number {
	if (typeof seed === 'number') return seed;
	let n = 0;
	for (let i = 0; i < seed.length; i++) n = (n * 31 + seed.charCodeAt(i)) % 997;
	return n + 1;
}

/** Bar heights for the decorative waveform (same formula as the mockup). */
export function waveHeights(
	count: number,
	seed: number | string,
	height: number,
): number[] {
	const s = seedOf(seed);
	return Array.from({ length: count }, (_, i) =>
		Math.max(
			2,
			Math.round(
				height *
					(0.18 +
						0.82 *
							Math.abs(Math.sin((i + s) * 0.9) * Math.cos((i + s * 2) * 0.37))),
			),
		),
	);
}
