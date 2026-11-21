import { test } from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import { setup, status, HOUR } from "./helpers.mjs";
import { parseFlags, parseRelease } from "../src/release.mjs";

async function addRelease(t, vc, { apk = `voiceflow-${vc}.apk`, bytes = Buffer.from(`apk ${vc}`), size } = {}) {
	await t.write(`releases/android/${vc}/${apk}`, bytes);
	await t.write(`releases/android/${vc}/release.json`, JSON.stringify({
		schema: "voiceflow-release", version: 1, versionCode: vc, versionName: `0.8.${vc}`,
		apk, sha256: crypto.createHash("sha256").update(bytes).digest("hex"), size: size ?? bytes.length,
	}));
}

const canaryStatus = (vc, dictations = 1, errors = {}) => status("alice", "phalice1", { versionCode: vc, dictations, errors });

test("no releases: no offer", async () => {
	const t = await setup();
	try {
		const r = await t.req("GET", "/v1/release/android", { token: t.tok.bob });
		assert.deepEqual(r.json, { offer: null });
	} finally {
		await t.close();
	}
});

test("the canary user gets the newest release at once; the other user waits for 24 h of healthy evidence", async () => {
	const t = await setup();
	try {
		await addRelease(t, 50);
		await addRelease(t, 51);
		const a = await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone });
		assert.equal(a.json.offer.versionCode, 51);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);

		// The canary phone reports 51 for the first time, with no dictation yet.
		assert.equal((await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(51, 0) })).status, 200);
		t.clock.now += 25 * HOUR;
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null, "stale status, no dictation");
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(51, 3) });
		const b = await t.req("GET", "/v1/release/android", { token: t.tok.bob });
		assert.equal(b.json.offer.versionCode, 51);
		assert.deepEqual(Object.keys(b.json.offer).sort(), ["sha256", "size", "url", "versionCode", "versionName"]);
		assert.ok(!b.text.includes("phalice1") && !b.text.includes("alice"), "bob learns nothing about the canary");

		// The APK streams for an offered version only.
		const apk = await t.req("GET", "/v1/release/android/apk/51", { token: t.tok.bob });
		assert.equal(apk.status, 200);
		assert.equal(apk.text, "apk 51");
		assert.equal(apk.headers["content-type"], "application/vnd.android.package-archive");
		assert.equal((await t.req("GET", "/v1/release/android/apk/50", { token: t.tok.bob })).status, 404);

		// Fresh means under 2 h: let it go stale and the offer is withdrawn.
		t.clock.now += 3 * HOUR;
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("errors reported for the version, or a refused write, hold it", async () => {
	const t = await setup();
	try {
		await addRelease(t, 60);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(60, 1) });
		t.clock.now += 25 * HOUR;
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(60, 2, { install: 1 }) });
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);

		await addRelease(t, 61);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(61, 1) });
		t.clock.now += 25 * HOUR;
		// The canary sends an invalid edit: the gateway counts the refusal against 61.
		const bad = { schema: "voiceflow-op", version: 1, id: "phalice1-x", device: "phalice1", createdAt: "2026-11-19T00:00:00Z", type: "nope" };
		assert.equal((await t.req("PUT", "/v1/ops/phalice1-x.json", { token: t.tok.alicePhone, body: bad })).status, 422);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(61, 4) });
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("flags: force skips the evidence, hold beats force and also holds the canary; a broken flags file holds everything", async () => {
	const t = await setup();
	try {
		await addRelease(t, 70);
		await addRelease(t, 71);
		await t.write("releases/android/flags.json", JSON.stringify({ force: [70] }));
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer.versionCode, 70);
		await t.write("releases/android/flags.json", JSON.stringify({ force: [70], hold: [70, 71] }));
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer, null);
		await t.write("releases/android/flags.json", "{broken");
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("a malformed or oversized write from the canary also holds its version", async () => {
	const t = await setup();
	try {
		await addRelease(t, 65);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(65, 1) });
		t.clock.now += 25 * HOUR;
		assert.equal((await t.req("PUT", "/v1/ops/phalice1-y.json", { token: t.tok.alicePhone, body: "{not json" })).status, 400);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(65, 2) });
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("more release folders than the cap holds every release rather than picking an arbitrary subset", async () => {
	const t = await setup({ limits: { maxReleases: 3 } });
	try {
		for (const vc of [1, 2, 3]) await addRelease(t, vc);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer.versionCode, 3);
		await addRelease(t, 4);
		const r = await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone });
		assert.equal(r.status, 200);
		assert.equal(r.json.offer, null);
	} finally {
		await t.close();
	}
});

test("one APK download per device at a time", async () => {
	const t = await setup();
	try {
		await addRelease(t, 7, { bytes: Buffer.alloc(32 * 1024 * 1024, 1) });
		const http = await import("node:http");
		const first = await new Promise((resolve) => {
			const r = http.request({ host: "127.0.0.1", port: t.port, path: "/v1/release/android/apk/7", headers: { authorization: `Bearer ${t.tok.alicePhone}` } }, resolve);
			r.end();
		});
		assert.equal(first.statusCode, 200);
		first.pause(); // a slow reader keeps the stream open
		const second = await t.req("GET", "/v1/release/android/apk/7", { token: t.tok.alicePhone });
		assert.equal(second.status, 429);
		first.destroy();
	} finally {
		await t.close();
	}
});

test("parallel edits cannot overshoot the per-user limit", async () => {
	const t = await setup({ limits: { maxOps: 4, writes: { capacity: 100, perSecond: 10 } } });
	try {
		const { op } = await import("./helpers.mjs");
		const results = await Promise.all([1, 2, 3, 4, 5, 6].map((i) => {
			const o = op("pcalice1", 300 + i);
			return t.req("PUT", `/v1/ops/${o.id}.json`, { token: t.tok.alice, body: o });
		}));
		assert.equal(results.filter((r) => r.status === 201).length, 3, "one edit was already there");
		assert.equal(results.filter((r) => r.status === 507).length, 3);
	} finally {
		await t.close();
	}
});

test("a flags.json that is a folder holds every release", async () => {
	const t = await setup();
	try {
		await addRelease(t, 90);
		await t.write("releases/android/flags.json/x", "{}");
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("evidence saved before a restart is kept, and parallel first reads share one load", async () => {
	const t = await setup();
	try {
		await addRelease(t, 95);
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(95, 1, { install: 1 }) });
		const { Rollout } = await import("../src/release.mjs");
		const fresh = new Rollout({ stateDir: t.state, canaryUser: "alice", canaryDevices: ["phalice1"], minAgeHours: 0, freshHours: 2, now: () => t.clock.now });
		const [a, b] = await Promise.all([fresh.gatePasses(95), fresh.gatePasses(95)]);
		assert.equal(a, false);
		assert.equal(b, false);
	} finally {
		await t.close();
	}
});

test("a release whose APK is missing or the wrong size is not offered; no canary configured holds everyone else", async () => {
	const t = await setup({ rollout: { canaryDevices: [] } });
	try {
		await addRelease(t, 80, { size: 999 });
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer, null);
		await addRelease(t, 81);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer.versionCode, 81);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
	} finally {
		await t.close();
	}
});

test("staged: false offers every user's devices the newest release at once, with no canary evidence", async () => {
	const t = await setup({ rollout: { staged: false } });
	try {
		await addRelease(t, 50);
		await addRelease(t, 51);
		for (const token of [t.tok.alicePhone, t.tok.alice, t.tok.bob]) {
			const r = await t.req("GET", "/v1/release/android", { token });
			assert.equal(r.json.offer.versionCode, 51);
		}
		const apk = await t.req("GET", "/v1/release/android/apk/51", { token: t.tok.bob });
		assert.equal(apk.status, 200);
		assert.equal(apk.text, "apk 51");
		assert.equal((await t.req("GET", "/v1/release/android/apk/50", { token: t.tok.bob })).status, 404);

		// Canary evidence against the version changes nothing: errors, no dictation, stale status.
		await t.req("PUT", "/v1/status", { token: t.tok.alicePhone, body: canaryStatus(51, 0, { install: 2 }) });
		t.clock.now += 30 * HOUR;
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer.versionCode, 51);
	} finally {
		await t.close();
	}
});

test("staged: false still honours hold for everyone, ignores force, and a broken flags file holds everything", async () => {
	const t = await setup({ rollout: { staged: false, canaryUser: null, canaryDevices: [] } });
	try {
		await addRelease(t, 70);
		await addRelease(t, 71);
		const offer = async (token) => (await t.req("GET", "/v1/release/android", { token })).json.offer;
		await t.write("releases/android/flags.json", JSON.stringify({ hold: [71], note: "bad build" }));
		assert.equal((await offer(t.tok.alicePhone)).versionCode, 70, "the next newest non-held release");
		assert.equal((await offer(t.tok.bob)).versionCode, 70);
		assert.equal((await t.req("GET", "/v1/release/android/apk/71", { token: t.tok.bob })).status, 404);
		await t.write("releases/android/flags.json", JSON.stringify({ hold: [70, 71] }));
		assert.equal(await offer(t.tok.alicePhone), null);
		assert.equal(await offer(t.tok.bob), null);
		// force does not pick an older version over the newest.
		await t.write("releases/android/flags.json", JSON.stringify({ force: [70] }));
		assert.equal((await offer(t.tok.bob)).versionCode, 71);
		await t.write("releases/android/flags.json", "{broken");
		assert.equal(await offer(t.tok.alicePhone), null);
		assert.equal(await offer(t.tok.bob), null);
		await t.write("releases/android/flags.json", JSON.stringify({ hold: ["71"] }));
		assert.equal(await offer(t.tok.bob), null, "a malformed hold list holds everything");
	} finally {
		await t.close();
	}
});

test("staged: true (also the default) keeps the canary rule; a non-boolean staged is refused at start", async () => {
	const t = await setup({ rollout: { staged: true } });
	try {
		await addRelease(t, 52);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.alicePhone })).json.offer.versionCode, 52);
		assert.equal((await t.req("GET", "/v1/release/android", { token: t.tok.bob })).json.offer, null);
	} finally {
		await t.close();
	}
	const { Rollout } = await import("../src/release.mjs");
	const base = { stateDir: t.state, canaryUser: "alice", canaryDevices: ["phalice1"], minAgeHours: 24, freshHours: 2, now: () => 0 };
	assert.equal(new Rollout(base).staged, true);
	assert.equal(new Rollout({ ...base, staged: false }).staged, false);
	for (const bad of ["false", 0, null]) assert.throws(() => new Rollout({ ...base, staged: bad }), /staged/);
});

test("release.json and flags parsing is strict", () => {
	const good = { schema: "voiceflow-release", version: 1, versionCode: 5, versionName: "0.8.0", apk: "a.apk", sha256: "a".repeat(64), size: 10 };
	assert.ok(parseRelease(good, "5"));
	assert.equal(parseRelease(good, "6"), null);
	assert.equal(parseRelease({ ...good, apk: "../a.apk" }, "5"), null);
	assert.equal(parseRelease({ ...good, apk: "a/b.apk" }, "5"), null);
	assert.equal(parseRelease({ ...good, sha256: "x" }, "5"), null);
	assert.deepEqual(parseFlags(null), { hold: [], force: [] });
	assert.equal(parseFlags('{"hold":["5"]}'), null);
	assert.equal(parseFlags('{"other":[]}'), null);
	assert.equal(parseFlags('{"hold":null,"force":[5]}'), null);
	assert.equal(parseFlags('{"force":"5"}'), null);
	assert.deepEqual(parseFlags('{"hold":[5],"note":"why"}'), { hold: [5], force: [] });
});
