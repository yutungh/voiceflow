// Device tokens. The gateway stores only SHA-256 hashes, one device per line:
//
//   <user> <device> <sha256-hex> [created] [label...]
//
// Lines starting with # are ignored, so revoking a device is deleting or commenting
// out its line. The file is re-read whenever it changes (checked on every request),
// so a revocation takes effect on the next request with no restart.
//
// Tokens are 32 random bytes, so a plain SHA-256 is enough: there is nothing to
// brute-force, and a slow hash would only hand an attacker a cheap way to load the CPU.

import fs from "node:fs/promises";
import crypto from "node:crypto";

export const TOKEN_RE = /^vfs1\.[A-Za-z0-9_-]{43}$/;
export const NAME_RE = /^[a-z0-9][a-z0-9_-]{0,31}$/;
const HASH_RE = /^[0-9a-f]{64}$/;

export function newToken() {
	return "vfs1." + crypto.randomBytes(32).toString("base64url");
}

export function hashToken(token) {
	return crypto.createHash("sha256").update(token, "utf8").digest("hex");
}

/** Parse the tokens file. Bad lines and devices listed twice are dropped and reported. */
export function parseTokens(text) {
	const entries = [];
	const problems = [];
	const lines = text.split(/\r?\n/);
	lines.forEach((raw, i) => {
		const line = raw.trim();
		if (line === "" || line.startsWith("#")) return;
		const [user, device, hash] = line.split(/\s+/);
		if (!NAME_RE.test(user ?? "") || user === "household" || !NAME_RE.test(device ?? "") || !HASH_RE.test(hash ?? "")) {
			problems.push(`line ${i + 1}: not "<user> <device> <sha256>"`);
			return;
		}
		entries.push({ user, device, hash: Buffer.from(hash, "hex") });
	});
	const count = new Map();
	for (const e of entries) count.set(e.device, (count.get(e.device) ?? 0) + 1);
	const hashCount = new Map();
	for (const e of entries) hashCount.set(e.hash.toString("hex"), (hashCount.get(e.hash.toString("hex")) ?? 0) + 1);
	const kept = entries.filter((e) => count.get(e.device) === 1 && hashCount.get(e.hash.toString("hex")) === 1);
	for (const [d, n] of count) if (n > 1) problems.push(`device ${d} is listed ${n} times; all its lines are ignored`);
	for (const [, n] of hashCount) if (n > 1) problems.push(`a token hash is listed ${n} times; those lines are ignored`);
	return { entries: kept, problems };
}

export class TokenStore {
	constructor(file, log) {
		this.file = file;
		this.log = log;
		this.sig = null;
		this.entries = [];
	}

	async refresh() {
		let st;
		try {
			st = await fs.stat(this.file);
		} catch {
			if (this.sig !== "missing") this.log?.({ event: "tokens", state: "missing" });
			this.sig = "missing";
			this.entries = [];
			return;
		}
		const sig = `${st.ino}:${st.size}:${st.mtimeMs}`;
		if (sig === this.sig) return;
		const { entries, problems } = parseTokens(await fs.readFile(this.file, "utf8"));
		this.entries = entries;
		this.sig = sig;
		this.log?.({ event: "tokens", state: "loaded", devices: entries.length, problems });
	}

	/**
	 * Find the device for a presented token. The hash is compared against every entry
	 * with a constant-time compare, whether or not an earlier one matched.
	 */
	async lookup(token) {
		await this.refresh();
		if (typeof token !== "string" || !TOKEN_RE.test(token)) return null;
		const h = Buffer.from(hashToken(token), "hex");
		let found = null;
		for (const e of this.entries) {
			if (crypto.timingSafeEqual(h, e.hash) && !found) found = e;
		}
		return found ? { user: found.user, device: found.device } : null;
	}
}
