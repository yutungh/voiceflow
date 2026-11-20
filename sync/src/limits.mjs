// Token buckets keyed by device or client address, with a bounded number of keys.

export class Buckets {
	/**
	 * @param {{capacity:number, perSecond:number, maxKeys?:number, now?:() => number}} o
	 */
	constructor({ capacity, perSecond, maxKeys = 10000, now = Date.now }) {
		this.capacity = capacity;
		this.perSecond = perSecond;
		this.maxKeys = maxKeys;
		this.now = now;
		this.map = new Map();
	}

	#state(key) {
		const t = this.now();
		let s = this.map.get(key);
		if (s) {
			s.tokens = Math.min(this.capacity, s.tokens + ((t - s.t) / 1000) * this.perSecond);
			s.t = t;
			// Refresh insertion order so the oldest-touched key is evicted first.
			this.map.delete(key);
			this.map.set(key, s);
			return s;
		}
		if (this.map.size >= this.maxKeys) this.map.delete(this.map.keys().next().value);
		s = { tokens: this.capacity, t };
		this.map.set(key, s);
		return s;
	}

	/** Take `n` tokens (default 1). Returns 0 when allowed, else the seconds to wait. */
	take(key, n = 1) {
		const s = this.#state(key);
		if (s.tokens >= n) {
			s.tokens -= n;
			return 0;
		}
		return Math.max(1, Math.ceil((n - s.tokens) / this.perSecond));
	}

	/** Whether a key is out of tokens, without spending one. */
	blocked(key) {
		if (!this.map.has(key)) return 0;
		const s = this.#state(key);
		return s.tokens >= 1 ? 0 : Math.max(1, Math.ceil((1 - s.tokens) / this.perSecond));
	}
}
