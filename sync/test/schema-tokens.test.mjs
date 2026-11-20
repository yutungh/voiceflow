import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import { compileSchema } from "../src/schema.mjs";
import { loadSchemas, DEFAULT_SCHEMA_DIR } from "../src/gateway.mjs";
import { parseTokens, hashToken, TOKEN_RE, TokenStore } from "../src/tokens.mjs";

const run = promisify(execFile);
const HERE = path.dirname(fileURLToPath(import.meta.url));
const FIXTURES = path.resolve(HERE, "../../pack/fixtures/demo");
const CLI = path.resolve(HERE, "../bin/vf-sync-token.mjs");

test("every op and status in the pack's demo fixtures passes the pack schemas", async () => {
	const s = await loadSchemas(DEFAULT_SCHEMA_DIR);
	const opsDir = path.join(FIXTURES, "profiles/sam/ops");
	for (const f of await fs.readdir(opsDir)) {
		const errs = s.op(JSON.parse(await fs.readFile(path.join(opsDir, f), "utf8")));
		assert.deepEqual(errs, [], f);
	}
	const st = JSON.parse(await fs.readFile(path.join(FIXTURES, "status/sam/demo1.json"), "utf8"));
	assert.deepEqual(s.status(st), []);
});

// The deploy bundle may carry only pack/schema and pack/fixtures: then this one is skipped.
const VECTORS = path.resolve(HERE, "../../pack/vectors");
const haveVectors = await fs.access(path.join(VECTORS, "styles.json")).then(() => true, () => false);

test("the pack's edit vectors: every edit a client reads passes the op schema, every one it skips fails it", { skip: !haveVectors && "pack/vectors not in this bundle" }, async () => {
	const s = await loadSchemas(DEFAULT_SCHEMA_DIR);
	let checked = 0;
	for (const file of ["merge.json", "styles.json"]) {
		const doc = JSON.parse(await fs.readFile(path.join(VECTORS, file), "utf8"));
		for (const c of doc.cases) {
			const skipped = new Set(c.expect.skippedOps ?? []);
			for (const op of c.ops ?? []) {
				const errs = s.op(op);
				assert.equal(errs.length === 0, !skipped.has(op.id), `${file} / ${c.name} / ${op.id}: ${errs.join("; ")}`);
				checked++;
			}
		}
	}
	assert.ok(checked >= 30, `${checked} edits checked`);
});

test("0.9.13: style-set, default-set and app-style edits; a status with reads", async () => {
	const s = await loadSchemas(DEFAULT_SCHEMA_DIR);
	const base = { schema: "voiceflow-op", version: 1, id: "ph1a2b3c4d-x", device: "ph1a2b3c4d", createdAt: "2026-11-19T10:00:00Z" };
	assert.deepEqual(s.op({ ...base, type: "style-set", style: "clean", set: { expression: 3, model: null } }), []);
	assert.deepEqual(s.op({ ...base, type: "default-set", set: { pinnedStyles: ["clean", "work"], cleanupStyle: "raw" } }), []);
	assert.deepEqual(s.op({ ...base, type: "app-style", app: "org.example.chat", expression: 1 }), []);
	assert.ok(s.op({ ...base, type: "style-set", style: "clean", set: {} }).length, "sets nothing");
	assert.ok(s.op({ ...base, type: "style-set", style: "clean", set: { label: null } }).length, "a label can't be cleared");
	assert.ok(s.op({ ...base, type: "default-set", set: { language: "en" } }).length, "not a default an edit sets");
	assert.ok(s.op({ ...base, type: "rename", style: "x" }).length, "an unknown type");
	const st = JSON.parse(await fs.readFile(path.join(FIXTURES, "status/sam/demo1.json"), "utf8"));
	assert.deepEqual(s.status({ ...st, reads: { profile: [1, 2], op: ["correction", "style-set"] } }), []);
});

test("the validator refuses a schema keyword it does not implement", () => {
	assert.throws(() => compileSchema({ type: "object", patternProperties: {} }), /unsupported keyword "patternProperties"/);
	assert.throws(() => compileSchema({ properties: { a: { format: "date-time" } } }), /unsupported keyword "format"/);
});

test("the validator's core keywords", () => {
	const v = compileSchema({
		type: "object",
		required: ["a"],
		additionalProperties: false,
		properties: {
			a: { type: "string", minLength: 2, maxLength: 3, pattern: "^[a-z]+$" },
			b: { type: ["integer", "null"], minimum: 0, maximum: 4 },
			c: { type: "array", minItems: 1, maxItems: 2, items: { enum: ["x", "y"] } },
		},
		anyOf: [{ required: ["b"] }, { required: ["c"] }],
	});
	assert.deepEqual(v({ a: "ab", b: 1 }), []);
	assert.deepEqual(v({ a: "ab", b: null }), []);
	assert.equal(v({ a: "ab" }).length, 1);
	assert.ok(v({ a: "a", b: 1 }).length);
	assert.ok(v({ a: "abcd", b: 1 }).length);
	assert.ok(v({ a: "AB", b: 1 }).length);
	assert.ok(v({ a: "ab", b: 1.5 }).length);
	assert.ok(v({ a: "ab", b: 5 }).length);
	assert.ok(v({ a: "ab", c: [] }).length);
	assert.ok(v({ a: "ab", c: ["z"] }).length);
	assert.ok(v({ a: "ab", b: 1, d: 1 }).length);
	assert.equal(v({ a: "😀😀", b: 1 }).length, 1, "length counts code points; the pattern still fails");
});

test("token file parsing drops bad lines and devices listed twice", () => {
	const h = "a".repeat(64), h2 = "b".repeat(64), h3 = "c".repeat(64);
	const { entries, problems } = parseTokens([
		`sam ph1 ${h} 2026-11-19 phone`,
		`# robin ph2 ${h2}`,
		`household ph3 ${h3}`,
		`Robin ph4 ${h3}`,
		`robin ph5 short`,
		`robin ph6 ${h2}`,
		`robin ph6 ${h3}`,
	].join("\n"));
	assert.deepEqual(entries.map((e) => e.device), ["ph1"]);
	assert.equal(problems.length, 4);
});

test("token lookup", async () => {
	const dir = await fs.mkdtemp(path.join(os.tmpdir(), "vf-sync-tok-"));
	const file = path.join(dir, "tokens.txt");
	const tok = "vfs1." + "A".repeat(43);
	await fs.writeFile(file, `sam ph1 ${hashToken(tok)}\n`);
	const store = new TokenStore(file);
	assert.deepEqual(await store.lookup(tok), { user: "sam", device: "ph1" });
	assert.equal(await store.lookup("vfs1." + "B".repeat(43)), null);
	assert.equal(await store.lookup(hashToken(tok)), null, "the hash itself is not a token");
	assert.equal(await store.lookup(undefined), null);
});

test("the token tool issues to a file (never stdout), lists and revokes", async () => {
	const dir = await fs.mkdtemp(path.join(os.tmpdir(), "vf-sync-cli-"));
	const file = path.join(dir, "tokens.txt");
	const out = path.join(dir, "ph1.token");
	const issued = await run(process.execPath, [CLI, "issue", "--file", file, "--user", "sam", "--device", "ph1", "--out", out, "--label", "phone"]);
	const token = (await fs.readFile(out, "utf8")).trim();
	assert.match(token, TOKEN_RE);
	assert.ok(!issued.stdout.includes(token) && !issued.stderr.includes(token));
	assert.ok((await fs.readFile(file, "utf8")).includes(hashToken(token)));
	await assert.rejects(run(process.execPath, [CLI, "issue", "--file", file, "--user", "sam", "--device", "ph1", "--out", path.join(dir, "again")]));
	await assert.rejects(run(process.execPath, [CLI, "issue", "--file", file, "--user", "sam", "--device", "ph2", "--out", out]), "never overwrites a token file");

	const listed = await run(process.execPath, [CLI, "list", "--file", file]);
	assert.match(listed.stdout, /sam\tph1\t\d{4}-\d{2}-\d{2}\tphone/);
	assert.ok(!listed.stdout.includes(hashToken(token)));

	// A run that finds the lock waits for nobody: it refuses and changes nothing.
	await fs.writeFile(`${file}.lock`, "");
	await assert.rejects(run(process.execPath, [CLI, "revoke", "--file", file, "--device", "ph1"]));
	await fs.rename(`${file}.lock`, path.join(dir, "old.lock"));

	const store = new TokenStore(file);
	assert.ok(await store.lookup(token));
	await run(process.execPath, [CLI, "revoke", "--file", file, "--device", "ph1"]);
	assert.equal(await store.lookup(token), null);
	assert.match(await fs.readFile(file, "utf8"), /^# revoked \d{4}-\d{2}-\d{2}: sam ph1 /m);
});
