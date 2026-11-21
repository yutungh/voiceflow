// vf-sync: serves a VoiceFlow profile folder to devices over HTTP, one token per device.
// No merging and no database: every request reads the files as they are on disk now.
// The user and device always come from the token, never from the request.

import fs from "node:fs/promises";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

import { compileSchema } from "./schema.mjs";
import { Root, FileError } from "./files.mjs";
import { TokenStore } from "./tokens.mjs";
import { Buckets } from "./limits.mjs";
import { Rollout, parseRelease, parseFlags, VC_DIR_RE } from "./release.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_SCHEMA_DIR = path.resolve(HERE, "../../pack/schema");

const OP_FILE_RE = /^([a-z0-9][a-z0-9._-]{0,99})\.json$/;
const LAYERS = ["household", "generated", "personal"];

export const DEFAULT_LIMITS = Object.freeze({
	layerBytes: 1024 * 1024, // the pack's limit for a layer file
	opBytes: 64 * 1024, // the pack's limit for an edit file
	statusBytes: 64 * 1024,
	backupBytes: 1024 * 1024,
	releaseJsonBytes: 16 * 1024,
	maxOps: 20000, // per user; new edits are refused (507) beyond it
	maxBackupsPerDevice: 5,
	maxReleases: 1000, // entries in releases/android; more than this holds every release
	apkStreams: 4, // APK downloads at once (one per device)
	apkSeconds: 1800, // an APK download is cut off after 30 minutes
	maxUrl: 300,
	inflight: 32,
	authFailures: { capacity: 10, perSecond: 10 / 600 }, // 10 bad tokens, then one more per minute
	device: { capacity: 120, perSecond: 2 },
	writes: { capacity: 30, perSecond: 0.5 },
	// Bytes a device may write: 8 MB at once, refilled at 8 MB a day.
	writeBytes: { capacity: 8 * 1024 * 1024, perSecond: (8 * 1024 * 1024) / 86400 },
});

const SECURITY_HEADERS = {
	"cache-control": "no-store",
	"x-content-type-options": "nosniff",
	"referrer-policy": "no-referrer",
};

class HttpError extends Error {
	constructor(status, message, extra = {}) {
		super(message);
		this.status = status;
		this.extra = extra;
	}
}

const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest("hex");

/** If-None-Match: `*`, or a list of tags compared weakly (W/ prefix ignored). */
function etagMatches(header, etag) {
	if (typeof header !== "string") return false;
	if (header.trim() === "*") return true;
	const bare = (t) => t.trim().replace(/^W\//, "");
	return header.split(",").some((t) => bare(t) === etag);
}

const utf8 = new TextDecoder("utf-8", { fatal: true });

function parseJsonBody(buf) {
	let text;
	try {
		text = utf8.decode(buf);
	} catch {
		throw new HttpError(400, "body is not UTF-8");
	}
	try {
		return JSON.parse(text);
	} catch {
		throw new HttpError(400, "body is not JSON");
	}
}

export async function loadSchemas(dir = DEFAULT_SCHEMA_DIR) {
	const read = async (f) => compileSchema(JSON.parse(await fs.readFile(path.join(dir, f), "utf8")));
	return {
		op: await read("voiceflow-op.v1.schema.json"),
		status: await read("voiceflow-status.v1.schema.json"),
	};
}

/**
 * @param {object} config see README.md
 * @param {{now?:() => number, log?:(o:object) => void}} deps
 */
export async function createGateway(config, { now = Date.now, log = defaultLog } = {}) {
	const limits = { ...DEFAULT_LIMITS, ...(config.limits ?? {}) };
	const root = await Root.open(config.dataRoot);
	const schemas = await loadSchemas(config.schemaDir ?? DEFAULT_SCHEMA_DIR);
	const tokens = new TokenStore(config.tokensFile, log);
	await tokens.refresh();
	await fs.mkdir(config.stateDir, { recursive: true });
	const ro = config.rollout ?? {};
	const rollout = new Rollout({
		stateDir: config.stateDir,
		staged: ro.staged,
		canaryUser: ro.canaryUser ?? null,
		canaryDevices: ro.canaryDevices ?? [],
		minAgeHours: ro.minAgeHours ?? 24,
		freshHours: ro.freshHours ?? 2,
		now,
		log,
	});
	const authFailures = new Buckets({ ...limits.authFailures, now });
	const perDevice = new Buckets({ ...limits.device, now });
	const perDeviceWrites = new Buckets({ ...limits.writes, now });
	const perDeviceBytes = new Buckets({ ...limits.writeBytes, now });
	let inflight = 0;
	const apkActive = new Set();

	// Writes for one user run one at a time, so a quota check and the create it
	// guards cannot interleave with another write of the same user.
	const userChains = new Map();
	function serialPerUser(user, fn) {
		const prev = userChains.get(user) ?? Promise.resolve();
		const run = prev.then(fn, fn);
		const tail = run.catch(() => {});
		userChains.set(user, tail);
		tail.then(() => { if (userChains.get(user) === tail) userChains.delete(user); });
		return run;
	}

	/** Charge a write's size to the device's daily byte budget. */
	function chargeBytes(device, n) {
		const wait = perDeviceBytes.take(device, n);
		if (wait) throw new HttpError(429, "this device has written too much today", { headers: { "retry-after": String(Math.min(wait, 86400)) } });
	}

	// ---------- helpers ----------

	function clientIp(req) {
		if (config.trustProxy) {
			const real = req.headers["x-real-ip"];
			if (typeof real === "string" && /^[0-9A-Fa-f:.]{2,45}$/.test(real)) return real;
		}
		return req.socket.remoteAddress ?? "?";
	}

	function readBody(req, max) {
		const declared = Number(req.headers["content-length"]);
		if (Number.isFinite(declared) && declared > max) return Promise.reject(new HttpError(413, "body too large"));
		return new Promise((resolve, reject) => {
			const chunks = [];
			let size = 0;
			let done = false;
			req.on("data", (c) => {
				if (done) return;
				size += c.length;
				if (size > max) {
					done = true;
					reject(new HttpError(413, "body too large"));
					return;
				}
				chunks.push(c);
			});
			req.on("end", () => { if (!done) { done = true; resolve(Buffer.concat(chunks)); } });
			req.on("error", () => { if (!done) { done = true; reject(new HttpError(400, "body not received")); } });
		});
	}

	async function readLayer(user, layer) {
		const where =
			layer === "household" ? [["profiles", "household"], "profile.json"]
			: layer === "generated" ? [["profiles", user, "generated"], "profile.json"]
			: [["profiles", user], "profile.json"];
		return root.read(where[0], where[1], limits.layerBytes);
	}

	async function releases() {
		const base = ["releases", "android"];
		const dir = await root.dir(base);
		if (!dir) return { list: [], flags: { hold: [], force: [] } };
		// Flags: truly absent means none; anything present but unreadable (a folder, a
		// link, too big, bad JSON) holds every release.
		let flagsText = null;
		try {
			flagsText = (await root.read(base, "flags.json", limits.releaseJsonBytes)).toString("utf8");
		} catch {
			const present = await fs.lstat(path.join(dir, "flags.json")).then(() => true, () => false);
			if (present) flagsText = "\0unreadable";
		}
		const list = [];
		const dirs = (await root.listDirs(base, VC_DIR_RE, limits.maxReleases)) // throws past the cap: no offer
			.sort((a, b) => Number(b) - Number(a))
			.slice(0, 20); // the newest 20 are plenty to choose from
		for (const name of dirs) {
			try {
				const buf = await root.read([...base, name], "release.json", limits.releaseJsonBytes);
				const r = parseRelease(JSON.parse(buf.toString("utf8")), name);
				if (r) list.push(r);
			} catch {
				// a release without a valid release.json is not offered
			}
		}
		return { list, flags: parseFlags(flagsText) };
	}

	async function currentOffer(user) {
		let found;
		try {
			found = await releases();
		} catch (e) {
			if (e instanceof FileError) return null; // too many release folders: hold everything
			throw e;
		}
		const { list, flags } = found;
		const r = await rollout.offer(user, list, flags);
		if (!r) return null;
		// Offer it only when the APK it names is really there, with the stated size.
		try {
			const { fh, size } = await root.openFile(["releases", "android", String(r.versionCode)], r.apk);
			await fh.close();
			if (size !== r.size) return null;
		} catch {
			return null;
		}
		return r;
	}

	function send(res, status, body, headers = {}) {
		const buf = body === null ? null : Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body));
		res.writeHead(status, {
			...SECURITY_HEADERS,
			...(buf ? { "content-type": "application/json; charset=utf-8", "content-length": buf.length } : {}),
			...headers,
		});
		res.end(buf ?? undefined);
	}

	function sendEtagged(req, res, buf) {
		const etag = `"${sha256(buf).slice(0, 40)}"`;
		if (etagMatches(req.headers["if-none-match"], etag)) return send(res, 304, null, { etag });
		send(res, 200, buf, { etag });
	}

	// ---------- routes ----------

	async function manifest(req, res, who) {
		const layers = {};
		for (const layer of LAYERS) {
			try {
				const buf = await readLayer(who.user, layer);
				layers[layer] = { sha256: sha256(buf), size: buf.length };
			} catch (e) {
				if (!(e instanceof FileError)) throw e;
				layers[layer] = e.code === "too-large" ? { error: "over the size limit" } : null;
			}
		}
		// Listing allows twice the create limit, for edits other writers add over SMB.
		const ops = await root.list(["profiles", who.user, "ops"], OP_FILE_RE, limits.maxOps * 2, limits.maxOps * 4);
		const body = Buffer.from(JSON.stringify({ schema: "vf-sync-manifest", version: 1, user: who.user, device: who.device, layers, ops }));
		sendEtagged(req, res, body);
	}

	async function getLayer(req, res, who, layer) {
		sendEtagged(req, res, await readLayer(who.user, layer));
	}

	async function getOp(req, res, who, name) {
		sendEtagged(req, res, await root.read(["profiles", who.user, "ops"], name, limits.opBytes));
	}

	async function putOp(req, res, who, name, ctx) {
		const id = OP_FILE_RE.exec(name)[1];
		if (!id.startsWith(`${who.device}-`)) throw new HttpError(403, "an edit's name must start with this device's id");
		const buf = await readBody(req, limits.opBytes);
		const op = parseJsonBody(buf);
		const errors = schemas.op(op);
		if (errors.length) throw new HttpError(422, "edit does not match voiceflow-op v1", { errors: errors.slice(0, 20) });
		if (op.id !== id) throw new HttpError(422, "the edit's id must equal its file name");
		if (op.device !== who.device) throw new HttpError(403, "the edit's device must be this device");
		if (!Number.isFinite(Date.parse(op.createdAt))) throw new HttpError(422, "createdAt is not a date");
		ctx.name = name;
		const r = await serialPerUser(who.user, async () => {
			if ((await root.count(["profiles", who.user, "ops"], OP_FILE_RE, limits.maxOps)) >= limits.maxOps) {
				throw new HttpError(507, "this user has too many edits on the server");
			}
			chargeBytes(who.device, buf.length);
			return root.createExclusive(["profiles", who.user, "ops"], name, buf, { createDir: true, maxExisting: limits.opBytes });
		});
		if (r.result === "created") return send(res, 201, { ok: true, result: "created" });
		// The same bytes again is a retry; anything else under that name is a conflict.
		if (r.existing.equals(buf)) return send(res, 200, { ok: true, result: "already there" });
		throw new HttpError(409, "a different edit already has this name");
	}

	async function putStatus(req, res, who) {
		const buf = await readBody(req, limits.statusBytes);
		const status = parseJsonBody(buf);
		const errors = schemas.status(status);
		if (errors.length) throw new HttpError(422, "status does not match voiceflow-status v1", { errors: errors.slice(0, 20) });
		if (status.user !== who.user || status.device !== who.device) throw new HttpError(403, "status must name this device and its user");
		chargeBytes(who.device, buf.length);
		await root.replace(["status", who.user], `${who.device}.json`, buf, { createDir: true });
		await rollout.recordStatus(who.device, status);
		send(res, 200, { ok: true });
	}

	async function putBackup(req, res, who, name, ctx) {
		if (!name.startsWith(`${who.device}-`)) throw new HttpError(403, "a backup's name must start with this device's id");
		const buf = await readBody(req, limits.backupBytes);
		parseJsonBody(buf);
		ctx.name = name;
		const mine = new RegExp(`^${who.device}-[a-z0-9._-]*\\.json$`);
		const r = await serialPerUser(who.user, async () => {
			const existing = await root.list(["archive", who.user], mine, 1000);
			if (!existing.includes(name) && existing.length >= limits.maxBackupsPerDevice) {
				throw new HttpError(507, "this device already has its backups on the server");
			}
			chargeBytes(who.device, buf.length);
			return root.createExclusive(["archive", who.user], name, buf, { createDir: true, maxExisting: limits.backupBytes });
		});
		if (r.result === "created") return send(res, 201, { ok: true, result: "created" });
		if (r.existing.equals(buf)) return send(res, 200, { ok: true, result: "already there" });
		throw new HttpError(409, "a different backup already has this name");
	}

	async function getRelease(req, res, who) {
		const r = await currentOffer(who.user);
		send(res, 200, {
			offer: r && {
				versionCode: r.versionCode,
				versionName: r.versionName,
				sha256: r.sha256,
				size: r.size,
				url: `/v1/release/android/apk/${r.versionCode}`,
			},
		});
	}

	async function getApk(req, res, who, vc) {
		if (apkActive.has(who.device) || apkActive.size >= limits.apkStreams) {
			throw new HttpError(429, "too many downloads at once", { headers: { "retry-after": "60" } });
		}
		const r = await currentOffer(who.user);
		if (!r || r.versionCode !== vc) throw new HttpError(404, "not found");
		const { fh, size } = await root.openFile(["releases", "android", String(vc)], r.apk);
		if (size !== r.size) {
			await fh.close();
			throw new HttpError(404, "not found");
		}
		res.writeHead(200, {
			...SECURITY_HEADERS,
			"content-type": "application/vnd.android.package-archive",
			"content-length": size,
			"x-sha256": r.sha256,
		});
		const stream = fh.createReadStream();
		apkActive.add(who.device);
		const deadline = setTimeout(() => res.destroy(), limits.apkSeconds * 1000);
		try {
			await new Promise((resolve) => {
				stream.on("error", () => { res.destroy(); resolve(); });
				res.on("close", () => { stream.destroy(); resolve(); });
				stream.pipe(res);
			});
		} finally {
			clearTimeout(deadline);
			apkActive.delete(who.device);
		}
	}

	const ROUTES = [
		{ re: /^\/v1\/manifest$/, GET: (q, s, w) => manifest(q, s, w), t: "/v1/manifest" },
		{ re: /^\/v1\/layer\/(household|generated|personal)$/, GET: (q, s, w, m) => getLayer(q, s, w, m[1]), t: "/v1/layer/:layer" },
		{
			re: /^\/v1\/ops\/([a-z0-9][a-z0-9._-]{0,99}\.json)$/,
			GET: (q, s, w, m) => getOp(q, s, w, m[1]),
			PUT: (q, s, w, m, c) => putOp(q, s, w, m[1], c),
			t: "/v1/ops/:name",
		},
		{ re: /^\/v1\/status$/, PUT: (q, s, w) => putStatus(q, s, w), t: "/v1/status" },
		{ re: /^\/v1\/backup\/([a-z0-9][a-z0-9._-]{0,99}\.json)$/, PUT: (q, s, w, m, c) => putBackup(q, s, w, m[1], c), t: "/v1/backup/:name" },
		{ re: /^\/v1\/release\/android$/, GET: (q, s, w) => getRelease(q, s, w), t: "/v1/release/android" },
		{ re: /^\/v1\/release\/android\/apk\/([1-9][0-9]{0,8})$/, GET: (q, s, w, m) => getApk(q, s, w, Number(m[1])), t: "/v1/release/android/apk/:vc" },
	];

	// ---------- the handler ----------

	async function handle(req, res, ctx) {
		const url = req.url ?? "";
		if (url.length > limits.maxUrl) throw new HttpError(414, "address too long");
		const q = url.indexOf("?");
		const pathname = q === -1 ? url : url.slice(0, q);
		if (pathname === "/healthz" && req.method === "GET") return send(res, 200, { ok: true });
		if (!pathname.startsWith("/v1/")) throw new HttpError(404, "not found");

		const auth = req.headers.authorization;
		const m = typeof auth === "string" ? /^Bearer ([^\s]+)$/.exec(auth) : null;
		const who = m ? await tokens.lookup(m[1]) : null;
		if (!who) {
			// Only failures are limited by address: both phones may share one home
			// address, and a valid token is never held back by another device's failures.
			const wait = authFailures.blocked(ctx.ip) || authFailures.take(ctx.ip);
			if (wait) throw new HttpError(429, "too many failed sign-ins", { headers: { "retry-after": String(wait) } });
			throw new HttpError(401, "a valid device token is required", { headers: { "www-authenticate": "Bearer" } });
		}
		ctx.user = who.user;
		ctx.device = who.device;

		const route = ROUTES.find((r) => r.re.test(pathname));
		if (!route) throw new HttpError(404, "not found");
		ctx.route = route.t;
		const fn = route[req.method];
		if (!fn) {
			const allow = ["GET", "PUT"].filter((x) => route[x]).join(", ");
			throw new HttpError(405, "method not allowed", { headers: { allow } });
		}
		const dWait = perDevice.take(who.device) || (req.method === "PUT" ? perDeviceWrites.take(who.device) : 0);
		if (dWait) throw new HttpError(429, "too many requests", { headers: { "retry-after": String(dWait) } });
		await fn(req, res, who, route.re.exec(pathname), ctx);
	}

	async function handler(req, res) {
		const started = now();
		const ctx = { ip: clientIp(req), route: null, user: null, device: null, name: null };
		inflight += 1;
		res.on("close", () => {
			log({
				event: "request",
				ip: ctx.ip,
				m: req.method,
				r: ctx.route ?? "-",
				s: res.statusCode,
				ms: now() - started,
				...(ctx.user ? { u: ctx.user, d: ctx.device } : {}),
				...(ctx.name ? { n: ctx.name } : {}),
				...(ctx.err ? { err: ctx.err } : {}),
			});
		});
		try {
			if (inflight > limits.inflight) throw new HttpError(503, "busy, try again", { headers: { "retry-after": "5" } });
			await handle(req, res, ctx);
		} catch (e) {
			let status = 500, body = { error: "server error" }, headers = {};
			if (e instanceof HttpError) {
				status = e.status;
				body = { error: e.message, ...(e.extra.errors ? { errors: e.extra.errors } : {}) };
				headers = e.extra.headers ?? {};
			} else if (e instanceof FileError) {
				status = e.code === "too-large" ? 422 : 404;
				body = { error: e.code === "too-large" ? "file over the size limit" : "not found" };
			} else {
				ctx.err = e?.code ?? e?.name ?? "error";
			}
			if (status === 413) headers.connection = "close";
			// Any refused write from a signed-in device counts against the canary's version
			// (rate limits and server faults do not).
			if (req.method === "PUT" && ctx.device && status >= 400 && status < 500 && status !== 429) {
				await rollout.recordRefusal(ctx.device).catch(() => {});
			}
			if (res.headersSent) {
				res.destroy();
				return;
			}
			send(res, status, body, headers);
		} finally {
			// Counted until the work is finished, not when the client goes away.
			inflight -= 1;
		}
	}

	return { handler, tokens, rollout };
}

function defaultLog(o) {
	process.stdout.write(`${JSON.stringify({ t: new Date().toISOString(), ...o })}\n`);
}

/** Apply the server timeouts the gateway expects. */
export function hardenServer(server) {
	server.headersTimeout = 10_000;
	server.requestTimeout = 30_000;
	// A socket with no progress either way for 2 minutes (a stalled APK download) is closed.
	server.timeout = 120_000;
	server.keepAliveTimeout = 5_000;
	server.maxHeadersCount = 50;
	server.maxConnections = 128;
	return server;
}
