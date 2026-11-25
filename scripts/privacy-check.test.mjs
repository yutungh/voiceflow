// Self-test for scripts/privacy-check.mjs: node --test scripts/privacy-check.test.mjs
// Builds throwaway repositories in the system temp folder with a made-up term list, and checks
// where the list is looked for and that a missing list always blocks.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'privacy-check.mjs');
const TERM_A = 'Quorvalith'; // made up; never a real name
const TERM_B = 'Zentrimble';
const EMAIL = 'tester@users.noreply.github.com';

function git(cwd, ...args) {
	return execFileSync('git', ['-c', `user.name=Tester`, '-c', `user.email=${EMAIL}`, ...args], {
		cwd,
		encoding: 'utf8',
		windowsHide: true,
		stdio: ['ignore', 'pipe', 'pipe'],
	}).trim();
}

function check(repo, args, input = '') {
	const r = spawnSync(process.execPath, [SCRIPT, '--repo', repo, ...args], {
		encoding: 'utf8',
		windowsHide: true,
		input,
	});
	return { code: r.status, err: r.stderr };
}

function writeTerms(tree, ...terms) {
	mkdirSync(path.join(tree, 'private-docs'), { recursive: true });
	writeFileSync(path.join(tree, 'private-docs', 'privacy-terms.txt'), `# test list\n${terms.join('\n')}\n`);
}

// A main checkout plus a linked worktree on its own branch, neither with a term list yet.
function setup(t) {
	const base = mkdtempSync(path.join(tmpdir(), 'privacy-check-test-'));
	t.after(() => rmSync(base, { recursive: true, force: true }));
	const main = path.join(base, 'main');
	const linked = path.join(base, 'linked');
	mkdirSync(main);
	git(main, 'init', '-q', '-b', 'main');
	writeFileSync(path.join(main, '.git', 'info', 'exclude'), 'private-docs/\n');
	writeFileSync(path.join(main, 'readme.txt'), 'nothing to see\n');
	git(main, 'add', 'readme.txt');
	git(main, 'commit', '-q', '-m', 'start');
	git(main, 'worktree', 'add', '-q', '-b', 'side', linked);
	return { main, linked };
}

function addFile(tree, name, text) {
	writeFileSync(path.join(tree, name), text);
	git(tree, 'add', name);
	git(tree, 'commit', '-q', '-m', `add ${name}`);
}

test('a linked worktree with no list of its own uses the main checkout list', (t) => {
	const { main, linked } = setup(t);
	writeTerms(main, TERM_A);
	assert.equal(check(linked, ['--tree']).code, 0);
	addFile(linked, 'note.txt', `hello ${TERM_A}\n`);
	const r = check(linked, ['--tree']);
	assert.equal(r.code, 1, r.err);
	assert.match(r.err, /note\.txt:1/);
});

test('no list anywhere blocks, in every mode, and names where it looked', (t) => {
	const { main, linked } = setup(t);
	for (const repo of [main, linked]) {
		const tree = check(repo, ['--tree']);
		assert.equal(tree.code, 2, tree.err);
		assert.match(tree.err, /Push blocked/);
		const head = git(repo, 'rev-parse', 'HEAD');
		const push = check(repo, ['--pre-push', 'origin'], `refs/heads/x ${head} refs/heads/x ${'0'.repeat(40)}\n`);
		assert.equal(push.code, 2, push.err);
	}
	const r = check(linked, ['--tree']);
	assert.ok(r.err.includes(path.join(linked, 'private-docs', 'privacy-terms.txt')));
	assert.ok(r.err.includes(path.join(main, 'private-docs', 'privacy-terms.txt')));
});

test("the worktree's own list wins over the main checkout's", (t) => {
	const { main, linked } = setup(t);
	writeTerms(main, TERM_A);
	writeTerms(linked, TERM_B);
	addFile(linked, 'a.txt', `${TERM_A}\n`);
	assert.equal(check(linked, ['--tree']).code, 0);
	addFile(linked, 'b.txt', `${TERM_B}\n`);
	assert.equal(check(linked, ['--tree']).code, 1);
});

test('the main checkout finds its own list', (t) => {
	const { main } = setup(t);
	writeTerms(main, TERM_A);
	addFile(main, 'c.txt', `${TERM_A}\n`);
	assert.equal(check(main, ['--tree']).code, 1);
});

test('a missing --terms file blocks without falling back', (t) => {
	const { main, linked } = setup(t);
	writeTerms(main, TERM_A);
	const r = check(linked, ['--tree', '--terms', path.join(main, 'no-such-list.txt')]);
	assert.equal(r.code, 2, r.err);
	assert.match(r.err, /given with --terms/);
});

test('pre-push with a list found through the main checkout scans the pushed commits', (t) => {
	const { main, linked } = setup(t);
	writeTerms(main, TERM_A);
	addFile(linked, 'd.txt', `${TERM_A}\n`);
	const head = git(linked, 'rev-parse', 'HEAD');
	const r = check(linked, ['--pre-push', 'origin'], `refs/heads/side ${head} refs/heads/side ${'0'.repeat(40)}\n`);
	assert.equal(r.code, 1, r.err);
	assert.match(r.err, /d\.txt:1/);
});
