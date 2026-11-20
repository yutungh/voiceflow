import { expect, test } from 'bun:test';
import { tidyUpTimeoutMs } from './timeout';

const snap = (cleanupTimeoutS?: number) => ({
	defaults: { promptCharsEffective: 600, promptTermsEffective: 40, fromLayer: {}, cleanupTimeoutS },
});

test('tidy-up time comes from the word lists, else the default', () => {
	expect(tidyUpTimeoutMs(snap(60), 10_000)).toBe(60_000);
	expect(tidyUpTimeoutMs(snap(), 10_000)).toBe(10_000);
	expect(tidyUpTimeoutMs(null, 10_000)).toBe(10_000);
	expect(tidyUpTimeoutMs(snap(0), 10_000)).toBe(10_000);
	expect(tidyUpTimeoutMs(snap(500), 10_000)).toBe(10_000);
});
