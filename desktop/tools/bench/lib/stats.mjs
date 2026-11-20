// Small statistics helpers: nearest-rank percentiles over finite numbers.

export function percentile(values, p) {
	const xs = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
	if (xs.length === 0) return null;
	const rank = Math.ceil((p / 100) * xs.length);
	return xs[Math.min(xs.length - 1, Math.max(0, rank - 1))];
}

export function summarize(values) {
	const xs = values.filter((v) => Number.isFinite(v));
	return {
		n: xs.length,
		p50: percentile(xs, 50),
		p90: percentile(xs, 90),
		min: xs.length ? Math.min(...xs) : null,
		max: xs.length ? Math.max(...xs) : null,
	};
}

export function fmtMs(v) {
	return v === null || v === undefined ? '-' : `${Math.round(v)}`;
}

/** Counts exact, case-sensitive, whole-word occurrences of each term. */
export function countTerms(text, terms) {
	const counts = {};
	if (!text) return counts;
	for (const term of terms) {
		const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
		const m = text.match(new RegExp(`(?<![\\w])${escaped}(?![\\w])`, 'g'));
		if (m) counts[term] = m.length;
	}
	return counts;
}

export function wordCount(text) {
	return text ? text.trim().split(/\s+/).filter(Boolean).length : 0;
}

export function sleep(ms) {
	return new Promise((r) => setTimeout(r, ms));
}
