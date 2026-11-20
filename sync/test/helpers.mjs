import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import http from "node:http";
import { createGateway, hardenServer } from "../src/gateway.mjs";
import { newToken, hashToken } from "../src/tokens.mjs";

export const HOUR = 3600 * 1000;

function profile(layer, user, words) {
	return JSON.stringify({
		schema: "voiceflow-profile", schemaVersion: 1, layer, user,
		updatedAt: "2026-11-17T08:00:00Z", updatedBy: user,
		terms: words.map((w) => ({ id: w.toLowerCase(), text: w })),
	}, null, 1);
}

export function op(device, n, extra = {}) {
	const id = `${device}-0b6f9a2e-1111-4c1a-9d55-${String(n).padStart(12, "0")}`;
	return {
		schema: "voiceflow-op", version: 1, id, device,
		createdAt: "2026-11-19T09:00:00.000Z",
		type: "correction", from: ["Flu Mocks"], to: "Flumox", scope: { apps: ["*"] },
		...extra,
	};
}

export function status(user, device, update) {
	return {
		schema: "voiceflow-status", schemaVersion: 1, app: "VoiceFlow", version: "0.8.0",
		os: "android", device, user, writtenAt: "2026-11-19T09:00:00Z",
		profile: { words: 1, corrections: 0, styles: 0, issues: [], errors: [] },
		...(update ? { update } : {}),
	};
}

/** A fresh share with household, alice (canary user) and bob, plus tokens and a running gateway. */
export async function setup({ limits, rollout, clock } = {}) {
	const base = await fs.mkdtemp(path.join(os.tmpdir(), "vf-sync-test-"));
	const data = path.join(base, "share");
	const conf = path.join(base, "config");
	const state = path.join(base, "state");
	const w = async (rel, text) => {
		const p = path.join(data, rel);
		await fs.mkdir(path.dirname(p), { recursive: true });
		await fs.writeFile(p, text);
	};
	await w("profiles/household/profile.json", profile("household", "household", ["Kestrel"]));
	await w("profiles/alice/profile.json", profile("personal", "alice", ["Zorblax"]));
	await w("profiles/alice/generated/profile.json", profile("generated", "alice", ["Quibbit"]));
	await w(`profiles/alice/ops/${op("pcalice1", 1).id}.json`, JSON.stringify(op("pcalice1", 1)));
	await w("profiles/bob/profile.json", profile("personal", "bob", ["Flumox"]));
	await fs.mkdir(path.join(data, "profiles/bob/ops"), { recursive: true });
	await fs.mkdir(path.join(data, "status/alice"), { recursive: true });
	await fs.mkdir(path.join(data, "archive"), { recursive: true });
	await fs.mkdir(conf, { recursive: true });

	const tok = { alice: newToken(), alicePhone: newToken(), bob: newToken(), revoked: newToken() };
	const tokensFile = path.join(conf, "tokens.txt");
	await fs.writeFile(tokensFile, [
		"# user device hash",
		`alice pcalice1 ${hashToken(tok.alice)} 2026-11-19 pc`,
		`alice phalice1 ${hashToken(tok.alicePhone)} 2026-11-19 phone`,
		`bob phbob0001 ${hashToken(tok.bob)}`,
		`# revoked 2026-11-19: bob phbob0002 ${hashToken(tok.revoked)}`,
		"",
	].join("\n"));

	const logs = [];
	const t = clock ?? { now: Date.parse("2026-11-19T10:00:00Z") };
	const gw = await createGateway({
		dataRoot: data, tokensFile, stateDir: state, trustProxy: true,
		rollout: { canaryUser: "alice", canaryDevices: ["phalice1"], ...(rollout ?? {}) },
		limits,
	}, { now: () => t.now, log: (o) => logs.push(JSON.stringify(o)) });
	const server = hardenServer(http.createServer(gw.handler));
	await new Promise((r) => server.listen(0, "127.0.0.1", r));
	const port = server.address().port;

	async function req(method, p, { token, body, headers = {}, raw } = {}) {
		const h = { ...headers };
		if (token) h.authorization = `Bearer ${token}`;
		let payload;
		if (raw !== undefined) payload = raw;
		else if (body !== undefined) payload = Buffer.from(typeof body === "string" ? body : JSON.stringify(body));
		if (payload) h["content-length"] = payload.length;
		return new Promise((resolve, reject) => {
			const r = http.request({ host: "127.0.0.1", port, method, path: p, headers: h }, (res) => {
				const chunks = [];
				res.on("data", (c) => chunks.push(c));
				res.on("end", () => {
					const buf = Buffer.concat(chunks);
					let json = null;
					try { json = JSON.parse(buf.toString("utf8")); } catch {}
					resolve({ status: res.statusCode, headers: res.headers, buf, json, text: buf.toString("utf8") });
				});
			});
			r.on("error", reject);
			if (payload) r.write(payload);
			r.end();
		});
	}

	return {
		base, data, conf, state, tokensFile, tok, logs, clock: t, gw, port, req, write: w,
		close: () => new Promise((r) => server.close(r)),
	};
}
