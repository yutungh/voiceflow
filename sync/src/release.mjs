// Android releases and the canary rollout rule.
//
// On the share:
//   releases/android/<versionCode>/release.json   written last by the release script
//   releases/android/<versionCode>/<apk>          the APK it names
//   releases/android/flags.json                   optional: {"hold":[vc...], "force":[vc...]}
//
// Two modes, chosen by `rollout.staged` (default true):
//
// staged: false  Every device gets the newest release that is not held, at once.
//                `force`, the canary user and the canary evidence play no part.
//
// staged: true   Devices of the canary user get the newest release that is not held.
//                Everyone else gets a version only when it is forced, or when every
//                canary device shows healthy evidence for it: it first reported that
//                versionCode (server time) at least `minAgeHours` ago, its latest
//                status is under `freshHours` old and still shows that version, it
//                completed at least one dictation on it, and no errors were seen for
//                it (reported by the device, or a write of its that the gateway
//                refused). Missing evidence holds the release. A hold beats a force.
//                Non-canary devices only ever learn whether they may update, never
//                anything about the canary.
//
// In both modes a held version is offered to nobody, and an unreadable flags file
// holds everything.

import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";

const VC_DIR_RE = /^[1-9][0-9]{0,8}$/;
const APK_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}\.apk$/;
const SHA_RE = /^[0-9a-f]{64}$/;
const HOUR = 3600 * 1000;

const isCount = (n) => Number.isSafeInteger(n) && n >= 0;
const isVc = (n) => Number.isSafeInteger(n) && n >= 1 && n <= 999999999;

/** Validate one release.json. Returns the release or null. */
export function parseRelease(json, dirName) {
	if (!json || typeof json !== "object" || Array.isArray(json)) return null;
	const { schema, version, versionCode, versionName, apk, sha256, size } = json;
	if (schema !== "voiceflow-release" || version !== 1) return null;
	if (!isVc(versionCode) || String(versionCode) !== dirName) return null;
	if (typeof versionName !== "string" || versionName.length > 40 || !/^[0-9A-Za-z._+-]+$/.test(versionName)) return null;
	if (typeof apk !== "string" || !APK_RE.test(apk)) return null;
	if (typeof sha256 !== "string" || !SHA_RE.test(sha256)) return null;
	if (!isCount(size) || size === 0) return null;
	return { versionCode, versionName, apk, sha256, size };
}

/** Read the `update` block of a status file. Returns null when absent or malformed. */
export function parseUpdate(status) {
	const u = status?.update;
	if (!u || typeof u !== "object" || Array.isArray(u)) return null;
	if (!isVc(u.versionCode)) return null;
	const dictations = u.dictations ?? 0;
	if (!isCount(dictations)) return null;
	const errs = u.errors ?? {};
	if (!errs || typeof errs !== "object" || Array.isArray(errs)) return null;
	for (const k of ["auth", "validation", "install"]) if (errs[k] !== undefined && !isCount(errs[k])) return null;
	const errorCount = (errs.auth ?? 0) + (errs.validation ?? 0) + (errs.install ?? 0);
	return { versionCode: u.versionCode, dictations, errorCount };
}

export class Rollout {
	/**
	 * @param {{stateDir:string, staged?:boolean, canaryUser:string|null, canaryDevices:string[], minAgeHours:number, freshHours:number, now:()=>number, log?:Function}} o
	 */
	constructor(o) {
		if (o.staged !== undefined && typeof o.staged !== "boolean") throw new TypeError('rollout "staged" must be true or false');
		this.o = o;
		this.staged = o.staged ?? true;
		this.file = path.join(o.stateDir, "rollout.json");
		this.state = null;
		this.chain = Promise.resolve();
	}

	isCanaryDevice(device) {
		return this.o.canaryDevices.includes(device);
	}

	/** Load once; every caller shares the same load, so no reader can overwrite a newer state. */
	#load() {
		this.loading ??= (async () => {
			try {
				const s = JSON.parse(await fs.readFile(this.file, "utf8"));
				this.state = s && typeof s === "object" && s.devices && typeof s.devices === "object" ? s : { devices: {} };
			} catch (e) {
				if (e.code !== "ENOENT") this.o.log?.({ event: "rollout-state", state: "unreadable, starting empty" });
				this.state = { devices: {} };
			}
			return this.state;
		})();
		return this.loading;
	}

	async #save() {
		const tmp = `${this.file}.${crypto.randomBytes(6).toString("hex")}.tmp`;
		await fs.writeFile(tmp, JSON.stringify(this.state, null, 1));
		await fs.rename(tmp, this.file);
	}

	#serial(fn) {
		const run = this.chain.then(fn, fn);
		this.chain = run.catch(() => {});
		return run;
	}

	/** Record a status file a canary device just wrote (server receive time). */
	recordStatus(device, status) {
		if (!this.isCanaryDevice(device)) return Promise.resolve();
		return this.#serial(async () => {
			const s = await this.#load();
			const now = this.o.now();
			const d = (s.devices[device] ??= { versions: {} });
			const u = parseUpdate(status);
			d.lastStatusAt = now;
			d.lastVersionCode = u ? u.versionCode : null;
			if (u) {
				const v = (d.versions[u.versionCode] ??= { firstSeenAt: now, dictations: 0, errorAt: null });
				v.dictations = Math.max(v.dictations, u.dictations);
				if (u.errorCount > 0 && !v.errorAt) v.errorAt = now;
				// Keep evidence for the newest 20 versions only.
				const vcs = Object.keys(d.versions).map(Number).sort((a, b) => b - a);
				for (const old of vcs.slice(20)) delete d.versions[old];
			}
			await this.#save();
		});
	}

	/** Record that the gateway refused a write from a canary device (counts as an error). */
	recordRefusal(device) {
		if (!this.isCanaryDevice(device)) return Promise.resolve();
		return this.#serial(async () => {
			const s = await this.#load();
			const d = s.devices[device];
			const vc = d?.lastVersionCode;
			if (!vc || !d.versions[vc]) return;
			if (!d.versions[vc].errorAt) {
				d.versions[vc].errorAt = this.o.now();
				await this.#save();
			}
		});
	}

	async gatePasses(versionCode) {
		const { canaryDevices, minAgeHours, freshHours } = this.o;
		if (canaryDevices.length === 0) return false;
		const s = await this.#load();
		const now = this.o.now();
		return canaryDevices.every((dev) => {
			const d = s.devices[dev];
			const v = d?.versions?.[versionCode];
			return Boolean(
				v && typeof d.lastStatusAt === "number" &&
				now - v.firstSeenAt >= minAgeHours * HOUR &&
				now - d.lastStatusAt <= freshHours * HOUR &&
				d.lastVersionCode === versionCode &&
				v.dictations >= 1 &&
				!v.errorAt,
			);
		});
	}

	/**
	 * The release this device may install, or null.
	 * @param {{versionCode:number}[]} releases valid releases, any order
	 * @param {{hold:number[], force:number[]}|null} flags null when the flags file is unreadable (hold everything)
	 */
	async offer(user, releases, flags) {
		if (!flags) return null;
		const candidates = releases
			.filter((r) => !flags.hold.includes(r.versionCode))
			.sort((a, b) => b.versionCode - a.versionCode);
		if (!this.staged) return candidates[0] ?? null;
		if (this.o.canaryUser && user === this.o.canaryUser) return candidates[0] ?? null;
		for (const r of candidates) {
			if (flags.force.includes(r.versionCode) || (await this.gatePasses(r.versionCode))) return r;
		}
		return null;
	}
}

/** Parse flags.json text. Missing file is "no flags"; anything malformed holds everything (null). */
export function parseFlags(text) {
	if (text === null) return { hold: [], force: [] };
	let j;
	try {
		j = JSON.parse(text);
	} catch {
		return null;
	}
	if (!j || typeof j !== "object" || Array.isArray(j)) return null;
	if (("hold" in j && !Array.isArray(j.hold)) || ("force" in j && !Array.isArray(j.force))) return null;
	const hold = j.hold ?? [], force = j.force ?? [];
	if (![...hold, ...force].every(isVc)) return null;
	for (const k of Object.keys(j)) if (k !== "hold" && k !== "force" && k !== "note") return null;
	return { hold, force };
}

export { VC_DIR_RE };
