import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { setup, op, status } from "./helpers.mjs";
import { hashToken, newToken } from "../src/tokens.mjs";

let t;
// Generous write limits here: limits.test.mjs tests them.
before(async () => { t = await setup({ limits: { writes: { capacity: 1000, perSecond: 10 } } }); });
after(async () => { await t.close(); });

const ALL_ROUTES = [
	["GET", "/v1/manifest"],
	["GET", "/v1/layer/household"],
	["GET", "/v1/layer/personal"],
	["GET", "/v1/ops/pcalice1-x.json"],
	["PUT", "/v1/ops/pcalice1-x.json"],
	["PUT", "/v1/status"],
	["PUT", "/v1/backup/pcalice1-x.json"],
	["GET", "/v1/release/android"],
	["GET", "/v1/release/android/apk/1"],
];

// ---------- authentication ----------

test("no token, a malformed token, an unknown token and a revoked token all get 401", async () => {
	for (const [m, p] of ALL_ROUTES) {
		const r = await t.req(m, p, { headers: { "x-real-ip": "10.0.0.1" } });
		assert.equal(r.status, 401, `${m} ${p}`);
		assert.equal(r.headers["www-authenticate"], "Bearer");
	}
	const bad = [
		{ authorization: "Bearer nope" },
		{ authorization: `Basic ${Buffer.from("a:b").toString("base64")}` },
		{ authorization: `Bearer ${newToken()}` },
		{ authorization: `Bearer ${t.tok.revoked}` },
		{ authorization: `bearer ${t.tok.alice}` },
		{ authorization: `Bearer  ${t.tok.alice}` },
		{ authorization: `Bearer ${t.tok.alice}x` },
	];
	for (const headers of bad) {
		const r = await t.req("GET", "/v1/manifest", { headers: { ...headers, "x-real-ip": "10.0.0.2" } });
		assert.equal(r.status, 401);
	}
});

test("the token in a query string is not accepted", async () => {
	const r = await t.req("GET", `/v1/manifest?token=${t.tok.alice}`, { headers: { "x-real-ip": "10.0.0.3" } });
	assert.equal(r.status, 401);
});

test("healthz needs no token and says nothing else", async () => {
	const r = await t.req("GET", "/healthz");
	assert.equal(r.status, 200);
	assert.deepEqual(r.json, { ok: true });
});

test("a token added or revoked in the file works on the next request, with no restart", async () => {
	const fresh = newToken();
	const before = await fs.readFile(t.tokensFile, "utf8");
	assert.equal((await t.req("GET", "/v1/manifest", { token: fresh, headers: { "x-real-ip": "10.0.0.4" } })).status, 401);
	await fs.writeFile(t.tokensFile, `${before}bob phbob0003 ${hashToken(fresh)}\n`);
	assert.equal((await t.req("GET", "/v1/manifest", { token: fresh })).status, 200);
	await fs.writeFile(t.tokensFile, before);
	assert.equal((await t.req("GET", "/v1/manifest", { token: fresh, headers: { "x-real-ip": "10.0.0.4" } })).status, 401);
});

// ---------- reading: isolation ----------

test("alice's manifest has household and her own layers and ops", async () => {
	const r = await t.req("GET", "/v1/manifest", { token: t.tok.alice });
	assert.equal(r.status, 200);
	assert.equal(r.json.user, "alice");
	assert.equal(r.json.device, "pcalice1");
	assert.ok(r.json.layers.household.sha256);
	assert.ok(r.json.layers.generated.sha256);
	assert.ok(r.json.layers.personal.sha256);
	assert.deepEqual(r.json.ops, [`${op("pcalice1", 1).id}.json`]);
});

test("bob never sees alice's files: manifest, layers, ops, and the error text", async () => {
	const m = await t.req("GET", "/v1/manifest", { token: t.tok.bob });
	assert.equal(m.status, 200);
	assert.equal(m.json.user, "bob");
	assert.equal(m.json.layers.generated, null);
	assert.deepEqual(m.json.ops, []);
	assert.ok(!m.text.includes("alice"));
	assert.ok(!m.text.includes("Zorblax"));

	const personal = await t.req("GET", "/v1/layer/personal", { token: t.tok.bob });
	assert.equal(personal.status, 200);
	assert.match(personal.text, /Flumox/);
	assert.doesNotMatch(personal.text, /Zorblax|Quibbit/);
	assert.equal((await t.req("GET", "/v1/layer/generated", { token: t.tok.bob })).status, 404);

	const aliceOp = `${op("pcalice1", 1).id}.json`;
	const r = await t.req("GET", `/v1/ops/${aliceOp}`, { token: t.tok.bob });
	assert.equal(r.status, 404);
	assert.deepEqual(r.json, { error: "not found" });
	const own = await t.req("GET", `/v1/ops/${aliceOp}`, { token: t.tok.alice });
	assert.equal(own.status, 200);
});

test("both users read the same household file", async () => {
	const a = await t.req("GET", "/v1/layer/household", { token: t.tok.alice });
	const b = await t.req("GET", "/v1/layer/household", { token: t.tok.bob });
	assert.equal(a.status, 200);
	assert.equal(a.text, b.text);
});

test("ETag and 304 on the manifest; a status write does not change it; an SMB-written op does", async () => {
	const first = await t.req("GET", "/v1/manifest", { token: t.tok.alice });
	const etag = first.headers.etag;
	assert.ok(etag);
	const again = await t.req("GET", "/v1/manifest", { token: t.tok.alice, headers: { "if-none-match": etag } });
	assert.equal(again.status, 304);
	assert.equal(again.buf.length, 0);

	assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.alice, body: status("alice", "pcalice1") })).status, 200);
	assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.alice, headers: { "if-none-match": etag } })).status, 304);

	// Another writer (the PC over SMB) drops an op straight into the folder.
	const o = op("pcalice9", 7);
	await t.write(`profiles/alice/ops/${o.id}.json`, JSON.stringify(o));
	const after = await t.req("GET", "/v1/manifest", { token: t.tok.alice, headers: { "if-none-match": etag } });
	assert.equal(after.status, 200);
	assert.ok(after.json.ops.includes(`${o.id}.json`));
});

test("If-None-Match: lists, weak tags and * also give 304", async () => {
	const { headers } = await t.req("GET", "/v1/layer/household", { token: t.tok.bob });
	for (const inm of [`"x", ${headers.etag}`, `W/${headers.etag}`, "*"]) {
		assert.equal((await t.req("GET", "/v1/layer/household", { token: t.tok.bob, headers: { "if-none-match": inm } })).status, 304, inm);
	}
	assert.equal((await t.req("GET", "/v1/layer/household", { token: t.tok.bob, headers: { "if-none-match": '"other"' } })).status, 200);
});

test("a hard link planted to another user's file is not served", async () => {
	const name = "phbob0001-hardlink.json";
	await fs.link(path.join(t.data, "profiles/alice/profile.json"), path.join(t.data, "profiles/bob/ops", name));
	const r = await t.req("GET", `/v1/ops/${name}`, { token: t.tok.bob });
	assert.equal(r.status, 404);
	assert.doesNotMatch(r.text, /Zorblax/);
	// Alice's own file now has two links, so it is refused to her too until the link goes.
	assert.equal((await t.req("GET", "/v1/layer/personal", { token: t.tok.alice })).status, 404);
	await fs.rename(path.join(t.data, "profiles/bob/ops", name), path.join(t.base, name));
	assert.equal((await t.req("GET", "/v1/layer/personal", { token: t.tok.alice })).status, 404, "still linked from outside");
	await fs.copyFile(path.join(t.base, name), path.join(t.base, `${name}.copy`));
	await fs.rename(path.join(t.base, `${name}.copy`), path.join(t.data, "profiles/alice/profile.json"));
	assert.equal((await t.req("GET", "/v1/layer/personal", { token: t.tok.alice })).status, 200);
});

test("a layer edited on disk is served fresh on the next request", async () => {
	const p = path.join(t.data, "profiles/bob/profile.json");
	const old = await fs.readFile(p, "utf8");
	await fs.writeFile(p, old.replace("Flumox", "Flumoxx"));
	const r = await t.req("GET", "/v1/layer/personal", { token: t.tok.bob });
	assert.match(r.text, /Flumoxx/);
	await fs.writeFile(p, old);
});

// ---------- writing ops ----------

test("an op upload is create-only: 201, the same again 200, different content 409", async () => {
	const o = op("phbob0001", 1);
	const url = `/v1/ops/${o.id}.json`;
	const r1 = await t.req("PUT", url, { token: t.tok.bob, body: o });
	assert.equal(r1.status, 201);
	const r2 = await t.req("PUT", url, { token: t.tok.bob, body: o });
	assert.equal(r2.status, 200);
	const r3 = await t.req("PUT", url, { token: t.tok.bob, body: { ...o, to: "Flumax" } });
	assert.equal(r3.status, 409);
	// Same meaning, different bytes: still a conflict (idempotency is byte-exact).
	assert.equal((await t.req("PUT", url, { token: t.tok.bob, body: JSON.stringify(o, null, 2) })).status, 409);
	const onDisk = JSON.parse(await fs.readFile(path.join(t.data, "profiles/bob/ops", `${o.id}.json`), "utf8"));
	assert.equal(onDisk.to, "Flumox");
	const left = await fs.readdir(path.join(t.data, "profiles/bob/ops"));
	assert.deepEqual(left.filter((n) => n.endsWith(".tmp")), []);
});

test("an op must be named for, and claim, the token's own device", async () => {
	const other = op("phbob0002", 2);
	assert.equal((await t.req("PUT", `/v1/ops/${other.id}.json`, { token: t.tok.bob, body: other })).status, 403);
	const spoof = { ...op("phbob0001", 3), device: "phbob0002" };
	assert.equal((await t.req("PUT", `/v1/ops/${spoof.id}.json`, { token: t.tok.bob, body: spoof })).status, 403);
	const misnamed = op("phbob0001", 4);
	assert.equal((await t.req("PUT", `/v1/ops/phbob0001-other.json`, { token: t.tok.bob, body: misnamed })).status, 422);
});

test("ops are validated against the pack's op schema", async () => {
	const cases = [
		{ ...op("phbob0001", 10), type: "rename" },
		{ ...op("phbob0001", 11), schema: "something-else" },
		{ ...op("phbob0001", 12), from: [] },
		{ ...op("phbob0001", 13), to: "<script>" },
		{ ...op("phbob0001", 14), to: "a‮b" },
		{ ...op("phbob0001", 15), to: "x".repeat(61) },
		(() => { const o = op("phbob0001", 16); delete o.scope; return o; })(),
		{ ...op("phbob0001", 17), type: "term", term: { text: "Ok", priority: 101 } },
		{ ...op("phbob0001", 18), type: "tombstone" },
		{ ...op("phbob0001", 19), createdAt: "not a date" },
	];
	for (const o of cases) {
		const r = await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.bob, body: o });
		assert.equal(r.status, 422, JSON.stringify(o));
		assert.ok(!r.text.includes("<script>"), "errors never echo content");
	}
	const ok = [
		{ ...op("phbob0001", 20), type: "term", term: { text: "Kestrel", heardAs: ["Castrel"], priority: 80 } },
		{ ...op("phbob0001", 21), type: "tombstone", target: op("phbob0001", 20).id },
	];
	for (const o of ok) {
		for (const k of ["from", "to", "scope"]) if (o.type !== "correction") delete o[k];
		assert.equal((await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.bob, body: o })).status, 201);
	}
});

test("bodies that are not JSON or not UTF-8 are refused", async () => {
	const id = op("phbob0001", 30).id;
	assert.equal((await t.req("PUT", `/v1/ops/${id}.json`, { token: t.tok.bob, body: "{nope" })).status, 400);
	assert.equal((await t.req("PUT", `/v1/ops/${id}.json`, { token: t.tok.bob, raw: Buffer.from([0x7b, 0xff, 0xfe, 0x7d]) })).status, 400);
});

test("size limits: an op over 64 KB and a status over 64 KB get 413", async () => {
	const o = { ...op("phbob0001", 40), note: "n".repeat(70 * 1024) };
	const r = await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.bob, body: o });
	assert.equal(r.status, 413);
	const s = { ...status("bob", "phbob0001"), pad: "p".repeat(70 * 1024) };
	assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.bob, body: s })).status, 413);
});

// ---------- paths ----------

test("path traversal never escapes: encoded, dotted, backslashed and absolute forms", async () => {
	const tries = [
		"/v1/ops/../profile.json",
		"/v1/ops/..%2F..%2Falice%2Fprofile.json",
		"/v1/ops/%2e%2e%2fprofile.json",
		"/v1/ops/..\\..\\alice\\profile.json",
		"/v1/ops/.json",
		"/v1/ops/..json",
		"/v1/layer/../../alice/profile.json",
		"/v1/layer/personal/../../alice",
		"/v1/layer/%2e%2e",
		"/v1//manifest",
		"/v1/ops//etc/passwd",
		"/v1/ops/C:%5Cwindows",
		"/v1/backup/../../profiles/alice/profile.json",
		"/v1/release/android/apk/../../../profiles/alice/profile.json",
	];
	for (const p of tries) {
		for (const m of ["GET", "PUT"]) {
			const r = await t.req(m, p, { token: t.tok.bob, body: m === "PUT" ? "{}" : undefined });
			assert.ok([400, 404, 405].includes(r.status), `${m} ${p} gave ${r.status}`);
			assert.doesNotMatch(r.text, /Zorblax|Quibbit|root:/);
		}
	}
});

test("a symlinked op file or symlinked user folder is not followed", { skip: process.platform === "win32" }, async () => {
	const target = path.join(t.data, "profiles/alice/profile.json");
	await fs.symlink(target, path.join(t.data, "profiles/bob/ops/phbob0001-link.json"));
	const r = await t.req("GET", "/v1/ops/phbob0001-link.json", { token: t.tok.bob });
	assert.equal(r.status, 404);
	const m = await t.req("GET", "/v1/manifest", { token: t.tok.bob });
	assert.ok(!m.json.ops.includes("phbob0001-link.json"), "symlinks are not listed");

	// A user folder that is a symlink to another user's folder is refused outright.
	const carol = newToken();
	const before = await fs.readFile(t.tokensFile, "utf8");
	await fs.writeFile(t.tokensFile, `${before}carol phcarol01 ${hashToken(carol)}\n`);
	await fs.symlink(path.join(t.data, "profiles/alice"), path.join(t.data, "profiles/carol"));
	for (const p of ["/v1/layer/personal", "/v1/layer/generated", `/v1/ops/${op("pcalice1", 1).id}.json`]) {
		const x = await t.req("GET", p, { token: carol });
		assert.equal(x.status, 404, p);
		assert.doesNotMatch(x.text, /Zorblax|Quibbit/);
	}
	const cm = await t.req("GET", "/v1/manifest", { token: carol });
	assert.equal(cm.status, 404);
	await fs.writeFile(t.tokensFile, before);
});

// ---------- status and backup ----------

test("status: writes only status/<user>/<device>.json and must name the token's device", async () => {
	const ok = await t.req("PUT", "/v1/status", { token: t.tok.bob, body: status("bob", "phbob0001") });
	assert.equal(ok.status, 200);
	const files = await fs.readdir(path.join(t.data, "status/bob"));
	assert.deepEqual(files, ["phbob0001.json"]);
	assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.bob, body: status("alice", "phbob0001") })).status, 403);
	assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.bob, body: status("bob", "pcalice1") })).status, 403);
	const bad = status("bob", "phbob0001");
	delete bad.profile;
	assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.bob, body: bad })).status, 422);
	assert.equal((await t.req("GET", "/v1/status", { token: t.tok.bob })).status, 405);
});

test("backup: create-only, into archive/<user>/, named for the device", async () => {
	const body = { prompts: [], vocabulary: [] };
	assert.equal((await t.req("PUT", "/v1/backup/phbob0001-2026-11-19.json", { token: t.tok.bob, body })).status, 201);
	assert.equal((await t.req("PUT", "/v1/backup/phbob0001-2026-11-19.json", { token: t.tok.bob, body })).status, 200);
	assert.equal((await t.req("PUT", "/v1/backup/phbob0001-2026-11-19.json", { token: t.tok.bob, body: { x: 1 } })).status, 409);
	assert.equal((await t.req("PUT", "/v1/backup/pcalice1-2026-11-19.json", { token: t.tok.bob, body })).status, 403);
	const files = await fs.readdir(path.join(t.data, "archive/bob"));
	assert.deepEqual(files, ["phbob0001-2026-11-19.json"]);
	// At most 5 backups per device.
	for (let i = 2; i <= 5; i++) assert.equal((await t.req("PUT", `/v1/backup/phbob0001-b${i}.json`, { token: t.tok.bob, body })).status, 201);
	assert.equal((await t.req("PUT", "/v1/backup/phbob0001-b6.json", { token: t.tok.bob, body })).status, 507);
	assert.equal((await t.req("PUT", "/v1/backup/phbob0001-b5.json", { token: t.tok.bob, body })).status, 200, "a retry of an existing one still works");
});

// ---------- logs ----------

test("logs never hold a token or any file content", async () => {
	const joined = t.logs.join("\n");
	for (const tok of Object.values(t.tok)) {
		assert.ok(!joined.includes(tok), "a token appeared in the logs");
		assert.ok(!joined.includes(hashToken(tok)), "a token hash appeared in the logs");
	}
	for (const word of ["Zorblax", "Quibbit", "Flumox", "Kestrel", "Flu Mocks"]) assert.ok(!joined.includes(word), `${word} appeared in the logs`);
	assert.ok(t.logs.some((l) => l.includes('"event":"request"')));
});
