#!/usr/bin/env node
// privacy-check.mjs - block private words and personal emails from reaching a public remote.
//
// OPTIONAL MAINTAINER TOOL. It needs a private term list that only the maintainer has, and it
// fails closed without one (exit 2). Contributors should not install or run it; the repository's
// public CI does not depend on it.
//
// Maintainers keep a private term list outside version control (default:
// private-docs/privacy-terms.txt, which .git/info/exclude keeps out of git).
// This script holds no terms of its own.
//
// Modes:
//   node scripts/privacy-check.mjs --pre-push <remote> [url]   (stdin: git pre-push lines)
//   node scripts/privacy-check.mjs --range <rev-range>          (e.g. origin/main..HEAD)
//   node scripts/privacy-check.mjs --tree                       (every tracked file and path at HEAD)
// Options:
//   --terms <file>   term list to use instead of the default
//   --repo <dir>     repository to check (default: the current directory)
//
// Term list format, one entry per line:
//   # comment                      ignored (also blank lines)
//   Some Name                      literal; case-insensitive, whole word
//   re:<regex>                     JavaScript regex; case-insensitive, used as written
//   <term>  @allow <glob>[, ...]   the term may appear in files matching these globs
//   @define <NAME> <glob>[, ...]   name a glob set; "@allow NAME" then uses it
// Globs: "**" matches across folders, "*" within one path segment.
//
// What is checked for each pushed commit: added lines, names of added or renamed
// files, the commit message, and author and committer emails (tags: tagger email
// and message). Emails must be GitHub no-reply addresses.
//
// Exit codes: 0 clean, 1 findings, 2 setup error (fails closed).

import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import path from 'node:path';

const ZERO = /^0+$/;
const MAX_BUFFER = 1024 * 1024 * 1024;

function git(args, opts = {}) {
	return execFileSync('git', args, {
		encoding: 'utf8',
		maxBuffer: MAX_BUFFER,
		windowsHide: true,
		cwd: repoDir,
		stdio: ['ignore', 'pipe', 'pipe'],
		...opts,
	});
}

function fail(msg) {
	console.error(`privacy-check: ${msg}`);
	process.exit(2);
}

// ---------- arguments ----------
const argv = process.argv.slice(2);
let mode = null;
let modeArgs = [];
let termsPath = null;
let repoDir = process.cwd();
for (let i = 0; i < argv.length; i++) {
	const a = argv[i];
	if (a === '--terms') termsPath = argv[++i];
	else if (a === '--repo') repoDir = path.resolve(argv[++i]);
	else if (a === '--pre-push' || a === '--range' || a === '--tree') mode = a.slice(2);
	else modeArgs.push(a);
}
if (!mode) fail('usage: --pre-push <remote> [url] | --range <rev-range> | --tree  [--terms <file>]');

let root;
try {
	root = git(['rev-parse', '--show-toplevel']).trim();
} catch {
	fail('not inside a git repository');
}
termsPath = termsPath ? path.resolve(termsPath) : path.join(root, 'private-docs', 'privacy-terms.txt');
if (!existsSync(termsPath)) fail(`term list not found: ${termsPath} (refusing to pass without it)`);

// ---------- term list ----------
function globToRegex(glob) {
	let re = '';
	for (let i = 0; i < glob.length; i++) {
		const c = glob[i];
		if (c === '*') {
			if (glob[i + 1] === '*') {
				re += '.*';
				i++;
				if (glob[i + 1] === '/') i++;
			} else re += '[^/]*';
		} else if (c === '?') re += '[^/]';
		else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
	}
	return new RegExp(`^${re}$`, 'i');
}

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const WORD = '\\p{L}\\p{N}_';

function loadTerms(file) {
	const defines = new Map();
	const terms = [];
	const lines = readFileSync(file, 'utf8').replace(/^﻿/, '').split(/\r?\n/);
	lines.forEach((raw, idx) => {
		const line = raw.trim();
		if (!line || line.startsWith('#')) return;
		const splitGlobs = (s) =>
			s.split(',').map((g) => g.trim()).filter(Boolean);
		if (line.startsWith('@define ')) {
			const m = line.match(/^@define\s+(\S+)\s+(.+)$/);
			if (!m) fail(`${file}:${idx + 1}: bad @define`);
			defines.set(m[1], splitGlobs(m[2]));
			return;
		}
		let body = line;
		let allow = [];
		const at = line.search(/\s@allow\s/);
		if (at >= 0) {
			body = line.slice(0, at).trim();
			allow = splitGlobs(line.slice(at).replace(/^\s*@allow\s+/, ''));
		}
		const globs = allow.flatMap((g) => defines.get(g) ?? [g]).map(globToRegex);
		let regex;
		try {
			regex = body.startsWith('re:')
				? new RegExp(body.slice(3), 'giu')
				: new RegExp(`(?<![${WORD}])${escapeRe(body)}(?![${WORD}])`, 'giu');
		} catch (e) {
			fail(`${file}:${idx + 1}: bad pattern (${e.message})`);
		}
		terms.push({ label: `line ${idx + 1}`, regex, allow: globs });
	});
	if (terms.length === 0) fail(`term list is empty: ${file}`);
	return terms;
}

const TERMS = loadTerms(termsPath);
const ANY = new RegExp(TERMS.map((t) => `(?:${t.regex.source})`).join('|'), 'iu');

const findings = [];
// Each hit names where it is, the line of the term list that matched, and the matched text.
function scanText(text, where, file) {
	if (!ANY.test(text)) return;
	for (const t of TERMS) {
		if (file && t.allow.some((g) => g.test(file))) continue;
		t.regex.lastIndex = 0;
		const m = t.regex.exec(text);
		if (m) findings.push(`${where}: term list ${t.label} matched "${m[0]}"`);
	}
}

const OK_EMAIL = /^(?:[^@\s]+@users\.noreply\.github\.com|noreply@github\.com)$/i;
function checkEmail(email, where) {
	if (!OK_EMAIL.test(email.trim())) findings.push(`${where}: email is not a GitHub no-reply address`);
}

// ---------- commit scanning ----------
function scanCommit(sha) {
	const short = sha.slice(0, 10);
	const meta = git(['show', '-s', '--format=%ae%x00%ce%x00%P%x00%B', sha]);
	const [ae, ce, parents, ...msgParts] = meta.split('\0');
	checkEmail(ae, `commit ${short} author`);
	checkEmail(ce, `commit ${short} committer`);
	const msg = msgParts.join('\0');
	msg.split('\n').forEach((l, i) => scanText(l, `commit ${short} message line ${i + 1}`));

	const isMerge = parents.trim().split(/\s+/).filter(Boolean).length > 1;
	// Names of added and renamed files.
	if (!isMerge) {
		const ns = git(['diff-tree', '-r', '--root', '--no-commit-id', '-M', '--name-status', '-z', sha]);
		const parts = ns.split('\0').filter((p) => p !== '');
		for (let i = 0; i < parts.length; ) {
			const status = parts[i++];
			const kind = status[0];
			const count = kind === 'R' || kind === 'C' ? 2 : 1;
			const names = parts.slice(i, i + count);
			i += count;
			if (kind === 'A' || kind === 'R' || kind === 'C') {
				const name = names[names.length - 1];
				scanText(name, `commit ${short} new file name ${name}`, name);
			}
		}
	}
	// Added lines. A merge is checked only for what it adds beyond its parents (--cc).
	const diffArgs = isMerge
		? ['diff-tree', '-p', '--cc', '-U0', '--no-color', '--no-ext-diff', '--no-commit-id', sha]
		: ['diff-tree', '-p', '-r', '--root', '-M', '-U0', '--no-color', '--no-ext-diff', '--no-commit-id', sha];
	scanPatch(git(diffArgs), short, isMerge ? parents.trim().split(/\s+/).length : 1);
}

function scanPatch(patch, short, nParents) {
	let file = null;
	let lineNo = 0;
	for (const line of patch.split('\n')) {
		if (line.startsWith('+++ ')) {
			const p = line.slice(4);
			file = p === '/dev/null' ? null : p.replace(/^"?b\//, '').replace(/"$/, '');
			continue;
		}
		if (line.startsWith('@@')) {
			const m = line.match(/\+(\d+)(?:,\d+)? @@/);
			lineNo = m ? Number(m[1]) : 0;
			continue;
		}
		if (file === null) continue;
		const prefix = line.slice(0, nParents);
		if (prefix.length < nParents) continue;
		if (prefix.includes('-')) continue;
		if (prefix.includes('+')) {
			scanText(line.slice(nParents), `commit ${short} ${file}:${lineNo}`, file);
			lineNo++;
		} else if (/^ +$/.test(prefix)) lineNo++;
	}
}

function scanTag(sha, refName) {
	const type = git(['cat-file', '-t', sha]).trim();
	if (type !== 'tag') return;
	const body = git(['cat-file', '-p', sha]);
	const [head, ...rest] = body.split(/\n\n/);
	const tagger = head.match(/^tagger .*<([^>]*)>/m);
	if (tagger) checkEmail(tagger[1], `tag ${refName} tagger`);
	rest.join('\n\n').split('\n').forEach((l, i) => scanText(l, `tag ${refName} message line ${i + 1}`));
}

function revList(args) {
	const out = git(['rev-list', ...args]).trim();
	return out ? out.split('\n') : [];
}

// ---------- modes ----------
if (mode === 'pre-push') {
	const remote = modeArgs[0] || 'origin';
	const input = readFileSync(0, 'utf8');
	const seen = new Set();
	for (const row of input.split(/\r?\n/)) {
		if (!row.trim()) continue;
		const [localRef, localSha, , remoteSha] = row.trim().split(/\s+/);
		if (!localSha || ZERO.test(localSha)) continue; // deleting a ref
		if (localRef.startsWith('refs/tags/')) scanTag(localSha, localRef);
		let shas;
		if (remoteSha && !ZERO.test(remoteSha) && hasObject(remoteSha)) {
			shas = revList([`${remoteSha}..${localSha}`]);
		} else {
			// New ref: everything not already on this remote.
			shas = revList([localSha, '--not', `--remotes=${remote}`]);
		}
		for (const s of shas) {
			if (seen.has(s)) continue;
			seen.add(s);
			scanCommit(s);
		}
	}
} else if (mode === 'range') {
	if (!modeArgs[0]) fail('--range needs a revision range');
	for (const s of revList([modeArgs[0]])) scanCommit(s);
} else if (mode === 'tree') {
	const files = git(['ls-files', '-z']).split('\0').filter(Boolean);
	for (const f of files) {
		scanText(f, `path ${f}`, f);
		let buf;
		try {
			buf = readFileSync(path.join(root, f));
		} catch {
			continue;
		}
		if (buf.subarray(0, 8000).includes(0)) continue; // binary
		const text = buf.toString('utf8');
		if (!ANY.test(text)) continue;
		text.split('\n').forEach((l, i) => scanText(l, `${f}:${i + 1}`, f));
	}
}

function hasObject(sha) {
	try {
		git(['cat-file', '-e', `${sha}^{commit}`]);
		return true;
	} catch {
		return false;
	}
}

if (findings.length) {
	console.error(`privacy-check: ${findings.length} finding(s) - push blocked.`);
	for (const f of findings) console.error(`  ${f}`);
	console.error('Fix the content (or allowlist a false hit in the private term list) and try again.');
	process.exit(1);
}
console.error(`privacy-check: clean (${TERMS.length} terms, mode ${mode}).`);
