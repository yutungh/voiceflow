/**
 * VoiceFlow: how often each password-field check outcome happened (spec
 * §4.3). They measure what Strict mode costs in availability, not how well
 * the check detects. Kept on this computer only (per-device convenience).
 */
import {
	countCheck,
	emptyCounters,
	type PasswordCheckCounters,
	type PasswordCheckResult,
} from './policy';

const STORAGE_KEY = 'voiceflow.privacy.passwordCheckCounters';

function load(): PasswordCheckCounters {
	try {
		const raw = localStorage.getItem(STORAGE_KEY);
		if (!raw) return emptyCounters();
		const parsed = JSON.parse(raw) as Partial<PasswordCheckCounters>;
		const counters = emptyCounters();
		for (const key of Object.keys(counters) as (keyof PasswordCheckCounters)[]) {
			const value = parsed[key];
			if (typeof value === 'number' && Number.isFinite(value) && value >= 0) {
				counters[key] = value;
			}
		}
		return counters;
	} catch {
		return emptyCounters();
	}
}

let counters = $state<PasswordCheckCounters>(load());

export const passwordCheckCounters = {
	get value() {
		return counters;
	},
	record(result: PasswordCheckResult) {
		counters = countCheck(counters, result);
		try {
			localStorage.setItem(STORAGE_KEY, JSON.stringify(counters));
		} catch {
			// Storage unavailable: the counts still show for this session.
		}
	},
	reset() {
		counters = emptyCounters();
		try {
			localStorage.removeItem(STORAGE_KEY);
		} catch {
			// ignore
		}
	},
};
