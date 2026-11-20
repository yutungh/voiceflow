// File access confined to one root folder. Every path is built by the server from
// fixed segments that already passed a strict pattern; these helpers add a second
// line of defence: the folder must resolve (realpath) to exactly where it should be,
// so a symlinked folder cannot redirect a read or a write; files are opened with
// O_NOFOLLOW, so a symlinked file is never followed; a file with a second hard link
// is refused; and O_NONBLOCK keeps a planted FIFO from hanging a worker thread.
//
// Threat model: only the gateway and trusted writers (the owner's PC and scripts, NAS
// admins) can write to the folder. Phones write only through the gateway, which never
// creates links. These checks stop accidents and planted links; they do not close the
// window between checking a folder and opening a file in it, which only a trusted
// writer, who can already read everything, could race.

import fs from "node:fs/promises";
import { constants } from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const O_NOFOLLOW = constants.O_NOFOLLOW ?? 0;
const O_NONBLOCK = constants.O_NONBLOCK ?? 0;

export class FileError extends Error {
	constructor(code, message) {
		super(message);
		this.code = code; // "missing" | "too-large" | "outside"
	}
}

/**
 * A regular file with one name. The gateway's own create step links a file twice for
 * a moment; a read in that instant sees "not found" and the device retries later.
 */
function isPlainFile(st) {
	return st.isFile() && st.nlink === 1;
}

export class Root {
	/** @param {string} realRoot an absolute path that is already a realpath */
	constructor(realRoot) {
		this.root = realRoot;
	}

	static async open(dir) {
		return new Root(await fs.realpath(dir));
	}

	/** The folder `segments` under the root, checked to resolve to itself. Returns null when missing. */
	async dir(segments, { create = false } = {}) {
		for (const s of segments) {
			if (typeof s !== "string" || s === "" || s === "." || s === ".." || /[\\/\0]/.test(s)) {
				throw new FileError("outside", "bad path segment");
			}
		}
		const want = path.join(this.root, ...segments);
		let real;
		try {
			real = await fs.realpath(want);
		} catch (e) {
			if (e.code !== "ENOENT" || !create) {
				if (e.code === "ENOENT" || e.code === "ENOTDIR") return null;
				throw e;
			}
			// Create only the last segment, and only when its parent is genuine.
			const parent = await this.dir(segments.slice(0, -1));
			if (!parent) return null;
			try {
				await fs.mkdir(want, { mode: 0o775 });
			} catch (e2) {
				if (e2.code !== "EEXIST") throw e2;
			}
			real = await fs.realpath(want);
		}
		if (real !== want) throw new FileError("outside", "folder resolves elsewhere");
		const st = await fs.stat(real);
		if (!st.isDirectory()) return null;
		return real;
	}

	/** Read a whole regular file (no symlink, one link), at most `maxBytes`. */
	async read(segments, name, maxBytes) {
		const dir = await this.dir(segments);
		if (!dir) throw new FileError("missing", "not found");
		return readFileIn(dir, name, maxBytes);
	}

	/**
	 * Names of regular entries in a folder that match `re` (sorted). Missing folder: [].
	 * Read incrementally; at most `maxScan` entries of any kind are looked at.
	 */
	async list(segments, re, maxEntries, maxScan = maxEntries * 2) {
		const dir = await this.dir(segments);
		if (!dir) return [];
		const out = [];
		let scanned = 0;
		// for-await closes the folder handle however the loop ends.
		for await (const ent of await fs.opendir(dir, { bufferSize: 64 })) {
			if (++scanned > maxScan) throw new FileError("too-large", "too many entries");
			if (!ent.isFile() || !re.test(ent.name)) continue;
			out.push(ent.name);
			if (out.length > maxEntries) throw new FileError("too-large", "too many entries");
		}
		return out.sort();
	}

	/** Count regular entries matching `re` (stops counting at `max`). */
	async count(segments, re, max) {
		const dir = await this.dir(segments);
		if (!dir) return 0;
		let n = 0;
		for await (const ent of await fs.opendir(dir, { bufferSize: 64 })) {
			if (ent.isFile() && re.test(ent.name) && ++n >= max) return n;
		}
		return n;
	}

	/** Names of sub-folders matching `re`. More than `maxScan` entries is an error, never a silent subset. */
	async listDirs(segments, re, maxScan) {
		const dir = await this.dir(segments);
		if (!dir) return [];
		const out = [];
		let scanned = 0;
		for await (const ent of await fs.opendir(dir, { bufferSize: 32 })) {
			if (++scanned > maxScan) throw new FileError("too-large", "too many entries");
			if (ent.isDirectory() && re.test(ent.name)) out.push(ent.name);
		}
		return out;
	}

	/**
	 * Create `name` in the folder with exactly `buf`, never replacing an existing file.
	 * The content is written to a temporary file first and then hard-linked into place,
	 * so a reader never sees a half-written file and an existing file is never touched.
	 * Returns {result:"created"} or {result:"exists", existing:<bytes>}.
	 */
	async createExclusive(segments, name, buf, { createDir = false, maxExisting = buf.length } = {}) {
		const dir = await this.dir(segments, { create: createDir });
		if (!dir) throw new FileError("missing", "folder not found");
		const final = path.join(dir, name);
		const tmp = path.join(dir, `.vf-sync-${crypto.randomBytes(8).toString("hex")}.tmp`);
		await fs.writeFile(tmp, buf, { flag: "wx", mode: 0o664 });
		let linked = false;
		try {
			await fs.link(tmp, final);
			linked = true;
		} catch (e) {
			if (e.code !== "EEXIST") throw e;
		} finally {
			await fs.unlink(tmp).catch(() => {});
		}
		if (linked) return { result: "created" };
		return { result: "exists", existing: await readFileIn(dir, name, maxExisting) };
	}

	/** Replace `name` in the folder with `buf` atomically (write, then rename). */
	async replace(segments, name, buf, { createDir = false } = {}) {
		const dir = await this.dir(segments, { create: createDir });
		if (!dir) throw new FileError("missing", "folder not found");
		const tmp = path.join(dir, `.vf-sync-${crypto.randomBytes(8).toString("hex")}.tmp`);
		await fs.writeFile(tmp, buf, { flag: "wx", mode: 0o664 });
		try {
			await fs.rename(tmp, path.join(dir, name));
		} catch (e) {
			await fs.unlink(tmp).catch(() => {});
			throw e;
		}
	}

	/** Open a regular file for streaming. Caller closes the handle. */
	async openFile(segments, name) {
		const dir = await this.dir(segments);
		if (!dir) throw new FileError("missing", "not found");
		const fh = await openNoFollow(path.join(dir, name));
		const st = await fh.stat();
		if (!isPlainFile(st)) {
			await fh.close();
			throw new FileError("missing", "not found");
		}
		return { fh, size: st.size };
	}
}

async function openNoFollow(p) {
	try {
		return await fs.open(p, constants.O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
	} catch (e) {
		if (["ENOENT", "ELOOP", "ENOTDIR", "EISDIR", "ENXIO", "EAGAIN"].includes(e.code)) {
			throw new FileError("missing", "not found");
		}
		throw e;
	}
}

async function readFileIn(dir, name, maxBytes) {
	const fh = await openNoFollow(path.join(dir, name));
	try {
		const st = await fh.stat();
		if (!isPlainFile(st)) throw new FileError("missing", "not found");
		if (st.size > maxBytes) throw new FileError("too-large", "file over the size limit");
		const buf = Buffer.alloc(st.size);
		let off = 0;
		while (off < st.size) {
			const { bytesRead } = await fh.read(buf, off, st.size - off, off);
			if (bytesRead === 0) break;
			off += bytesRead;
		}
		return buf.subarray(0, off);
	} finally {
		await fh.close();
	}
}
