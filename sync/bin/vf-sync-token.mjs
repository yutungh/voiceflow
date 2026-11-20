#!/usr/bin/env node
// Manage device tokens in the gateway's tokens file (only hashes are stored).
//
//   vf-sync-token issue  --file <tokens> --user <user> --device <device> --out <file> [--label <text>]
//   vf-sync-token revoke --file <tokens> --device <device>
//   vf-sync-token list   --file <tokens>
//
// `issue` writes the new token to --out (created, mode 600; it must not exist) and
// never prints it. `revoke` comments the device's line out; the gateway notices the
// change on its next request. Changes hold <tokens>.lock while they run.

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { newToken, hashToken, parseTokens, NAME_RE } from "../src/tokens.mjs";

class Fail extends Error {}
const fail = (msg) => { throw new Fail(msg); };

function args(argv) {
	const out = { _: [] };
	for (let i = 0; i < argv.length; i++) {
		const a = argv[i];
		if (a.startsWith("--")) out[a.slice(2)] = argv[++i];
		else out._.push(a);
	}
	return out;
}

function readLines(file) {
	return fs.existsSync(file) ? fs.readFileSync(file, "utf8").split(/\r?\n/) : [];
}

function writeAtomic(file, lines) {
	const text = lines.join("\n").replace(/\n*$/, "\n");
	const tmp = path.join(path.dirname(file), `.${path.basename(file)}.${crypto.randomBytes(6).toString("hex")}.tmp`);
	fs.writeFileSync(tmp, text, { mode: 0o600, flag: "wx" });
	fs.renameSync(tmp, file);
}

/** Hold <file>.lock for the whole read-change-write, so two runs never undo each other. */
function locked(file, fn) {
	const lock = `${file}.lock`;
	let fd;
	try {
		fd = fs.openSync(lock, "wx", 0o600);
	} catch {
		fail(`${lock} exists: another run is changing the tokens (remove it if none is)`);
	}
	try {
		fn();
	} finally {
		fs.closeSync(fd);
		fs.unlinkSync(lock);
	}
}

function issue(a) {
	if (!NAME_RE.test(a.user ?? "") || a.user === "household") fail("--user must be a short lower-case name (not household)");
	if (!NAME_RE.test(a.device ?? "")) fail("--device must be a short lower-case id");
	if (!a.out) fail("--out is required (the token is written there, never printed)");
	const lines = readLines(a.file);
	const { entries } = parseTokens(lines.join("\n"));
	if (entries.some((e) => e.device === a.device)) fail(`device ${a.device} already has a token; revoke it first`);
	const label = (a.label ?? "").replace(/[^\w .-]/g, "").slice(0, 60);
	const token = newToken();
	try {
		fs.writeFileSync(a.out, `${token}\n`, { mode: 0o600, flag: "wx" });
	} catch {
		fail(`could not create ${a.out} (it must not exist yet)`);
	}
	const created = new Date().toISOString().slice(0, 10);
	const kept = lines.length && lines[lines.length - 1] === "" ? lines.slice(0, -1) : lines;
	writeAtomic(a.file, [...kept, `${a.user} ${a.device} ${hashToken(token)} ${created} ${label}`.trim()]);
	console.log(`issued a token for ${a.user}/${a.device}; it is in ${a.out}`);
}

function revoke(a) {
	if (!NAME_RE.test(a.device ?? "")) fail("--device is required");
	const lines = readLines(a.file);
	let n = 0;
	const today = new Date().toISOString().slice(0, 10);
	const next = lines.map((l) => {
		const parts = l.trim().split(/\s+/);
		if (!l.trim().startsWith("#") && parts[1] === a.device) {
			n += 1;
			return `# revoked ${today}: ${l.trim()}`;
		}
		return l;
	});
	if (n === 0) fail(`no active token for device ${a.device}`);
	writeAtomic(a.file, next);
	console.log(`revoked ${n} token line(s) for ${a.device}`);
}

function list(a) {
	const lines = readLines(a.file);
	const { entries, problems } = parseTokens(lines.join("\n"));
	for (const l of lines) {
		const p = l.trim().split(/\s+/);
		if (l.trim() === "" || l.trim().startsWith("#")) continue;
		if (entries.some((e) => e.device === p[1])) console.log(`${p[0]}\t${p[1]}\t${p[3] ?? ""}\t${p.slice(4).join(" ")}`);
	}
	for (const p of problems) console.log(`problem: ${p}`);
}

try {
	const a = args(process.argv.slice(2));
	const cmd = a._[0];
	if (!a.file) fail("--file is required");
	if (cmd === "issue") locked(a.file, () => issue(a));
	else if (cmd === "revoke") locked(a.file, () => revoke(a));
	else if (cmd === "list") list(a);
	else fail("usage: issue | revoke | list (see the header of this file)");
} catch (e) {
	if (!(e instanceof Fail)) throw e;
	console.error(`vf-sync-token: ${e.message}`);
	process.exit(2);
}
