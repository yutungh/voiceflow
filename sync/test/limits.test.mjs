import { test } from "node:test";
import assert from "node:assert/strict";
import { setup, op, status } from "./helpers.mjs";
import { newToken } from "../src/tokens.mjs";
import { Buckets } from "../src/limits.mjs";

test("failed sign-ins from one address are cut off with 429, other addresses are not", async () => {
	const t = await setup({ limits: { authFailures: { capacity: 3, perSecond: 0.001 } } });
	try {
		const ip = { "x-real-ip": "203.0.113.9" };
		for (let i = 0; i < 3; i++) assert.equal((await t.req("GET", "/v1/manifest", { token: newToken(), headers: ip })).status, 401);
		const blocked = await t.req("GET", "/v1/manifest", { token: newToken(), headers: ip });
		assert.equal(blocked.status, 429);
		assert.ok(Number(blocked.headers["retry-after"]) >= 1);
		assert.equal((await t.req("GET", "/v1/manifest", { headers: ip })).status, 429);
		// A valid token from the same address (the other phone at home) is not held back.
		assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.alice, headers: ip })).status, 200);
		assert.equal((await t.req("GET", "/v1/manifest", { token: newToken(), headers: { "x-real-ip": "203.0.113.10" } })).status, 401);
		// A spoofed, malformed X-Real-IP falls back to the socket address rather than being trusted.
		assert.equal((await t.req("GET", "/v1/manifest", { token: newToken(), headers: { "x-real-ip": "not an ip" } })).status, 401);
	} finally {
		await t.close();
	}
});

test("one device is rate limited, and writes have their own tighter limit", async () => {
	const t = await setup({ limits: { device: { capacity: 5, perSecond: 0.001 }, writes: { capacity: 2, perSecond: 0.001 } } });
	try {
		for (let i = 0; i < 2; i++) {
			const o = op("phbob0001", 100 + i);
			assert.equal((await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.bob, body: o })).status, 201);
		}
		const o = op("phbob0001", 102);
		assert.equal((await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.bob, body: o })).status, 429);
		// Reads still have budget left (5 total, 3 spent).
		assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.bob })).status, 200);
		assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.bob })).status, 200);
		assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.bob })).status, 429);
		// Another device is unaffected.
		assert.equal((await t.req("GET", "/v1/manifest", { token: t.tok.alice })).status, 200);
	} finally {
		await t.close();
	}
});

test("storage quotas: a user's edit count and a device's daily bytes", async () => {
	const t = await setup({ limits: { maxOps: 3, writeBytes: { capacity: 2000, perSecond: 0.001 } } });
	try {
		// alice already has one edit on disk; two more fit, the fourth is refused.
		for (let i = 1; i <= 2; i++) {
			const o = op("pcalice1", 200 + i);
			assert.equal((await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.alice, body: o })).status, 201);
		}
		const o = op("pcalice1", 203);
		assert.equal((await t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.alice, body: o })).status, 507);
		// bob: a 2000-byte budget; each status is a few hundred bytes.
		let last = 0;
		for (let i = 0; i < 20 && last !== 429; i++) {
			last = (await t.req("PUT", "/v1/status", { token: t.tok.bob, body: status("bob", "phbob0001") })).status;
		}
		assert.equal(last, 429);
	} finally {
		await t.close();
	}
});

test("an address that is too long is refused before anything else", async () => {
	const t = await setup();
	try {
		const r = await t.req("GET", `/v1/ops/${"a".repeat(400)}.json`, { token: t.tok.bob });
		assert.equal(r.status, 414);
	} finally {
		await t.close();
	}
});

test("buckets refill over time and keep a bounded number of keys", () => {
	let now = 0;
	const b = new Buckets({ capacity: 2, perSecond: 1, maxKeys: 3, now: () => now });
	assert.equal(b.take("a"), 0);
	assert.equal(b.take("a"), 0);
	assert.ok(b.take("a") > 0);
	now += 1000;
	assert.equal(b.take("a"), 0);
	for (const k of ["b", "c", "d", "e"]) b.take(k);
	assert.ok(b.map.size <= 3);
});
