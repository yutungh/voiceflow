/**
 * The pinned Wanxiang (万象) pinyin dictionaries, shared by the scripts that read
 * them. Written for this repository (MIT); no Wanxiang tooling is used, because
 * the Wanxiang repositories license their data under CC BY 4.0 but grant no
 * licence for their program code.
 *
 * Source: https://github.com/amzxyz/rime-wanxiang @ 907edf67 (CC BY 4.0).
 * Attribution and the list of our changes: third_party/README.md.
 *
 * The dictionaries are not committed (jichu alone is 51 MB). They are fetched
 * on first use into third_party/rime-wanxiang/dicts/ (gitignored) and refused
 * unless their SHA-256 matches the pin below, so every build reads exactly the
 * same bytes.
 */

import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export const WANXIANG_REPO = "amzxyz/rime-wanxiang";
export const WANXIANG_COMMIT = "907edf67ce6d1f5c73b9f65ce9a2de55f8d7c82d";
export const WANXIANG_DIR = path.join("third_party", "rime-wanxiang", "dicts");

/** Upstream path -> SHA-256 of the bytes at the pinned commit. */
export const WANXIANG_FILES = {
  "dicts/zi.dict.yaml": "f321215a3aa7eef6317ec3b9e9725bf777480627488f5b6a42febaf7687e4924",
  "dicts/jichu.dict.yaml": "4a4598568f9f52cafb6ff9858adad4641a235165995c8d93c83aa67fe47343c7",
  "dicts/duoyin.dict.yaml": "85c2fe78121a05c50511e8defa473bd801c9697be79c4a548bb2f265c45f15f2",
};

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

/** Local path of one pinned dictionary, fetching and verifying it if needed. */
export async function pinnedDict(name) {
  const upstream = `dicts/${name}.dict.yaml`;
  const expected = WANXIANG_FILES[upstream];
  if (!expected) {
    throw new Error(`Not a pinned Wanxiang dictionary: ${name}`);
  }
  const local = path.join(WANXIANG_DIR, `${name}.dict.yaml`);
  if (!fs.existsSync(local)) {
    const url = `https://raw.githubusercontent.com/${WANXIANG_REPO}/${WANXIANG_COMMIT}/${upstream}`;
    console.log(`fetching ${url}`);
    const response = await fetch(url);
    if (!response.ok) {
      throw new Error(`Fetch failed (${response.status}): ${url}`);
    }
    fs.mkdirSync(WANXIANG_DIR, { recursive: true });
    fs.writeFileSync(local, Buffer.from(await response.arrayBuffer()));
  }
  const actual = sha256(local);
  if (actual !== expected) {
    throw new Error(`${local}: SHA-256 ${actual} does not match the pin ${expected}. Delete it to refetch.`);
  }
  return local;
}

/**
 * One toneless syllable: tone marks dropped, ü written v ("lǜ" -> "lv",
 * "nüè" -> "nve", "ń" -> "n"). Null when what is left is not plain a-z.
 */
export function toneless(syllable) {
  const decomposed = syllable.normalize("NFD").toLowerCase();
  let out = "";
  for (let i = 0; i < decomposed.length; i++) {
    const c = decomposed[i];
    if (c === "u" && decomposed[i + 1] === "̈") {
      out += "v";
      i++;
      continue;
    }
    if (c >= "̀" && c <= "ͯ") {
      continue;
    }
    out += c;
  }
  return /^[a-z]+$/.test(out) ? out : null;
}

/**
 * Rows of a Rime dictionary: { word, syllables (toneless), weight, pinyin (as written) }.
 * Rows whose pinyin does not reduce to plain syllables, or whose syllable
 * count differs from the word's character count, are skipped and counted.
 */
export function readDict(file, stats = {}) {
  const raw = fs.readFileSync(file, "utf8");
  const terminator = raw.search(/^\.\.\.\s*$/m);
  if (terminator < 0) {
    throw new Error(`${file}: no '...' YAML terminator`);
  }
  const rows = [];
  stats.skipped = stats.skipped || 0;
  for (const line of raw.slice(terminator).split(/\r?\n/).slice(1)) {
    if (!line || line.startsWith("#")) {
      continue;
    }
    const fields = line.split("\t");
    if (fields.length < 3) {
      stats.skipped++;
      continue;
    }
    const [word, pinyin, weightText] = fields;
    const weight = /^[0-9]+$/.test(weightText.trim()) ? Number.parseInt(weightText, 10) : Number.NaN;
    const syllables = pinyin.trim().split(/\s+/).map(toneless);
    const characters = [...word];
    if (!Number.isFinite(weight) || syllables.some((s) => s === null)
        || syllables.length !== characters.length
        || /[:,\t\sA-Za-z0-9]/.test(word)) {
      stats.skipped++;
      continue;
    }
    rows.push({ word, syllables, weight, pinyin: pinyin.trim() });
  }
  return rows;
}
