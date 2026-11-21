/**
 * Checks bench/pinyin/phrases.tsv before it is used: every row well formed,
 * 2-8 characters, one plain syllable per character, each syllable a reading
 * the pinned Wanxiang character table (zi) lists for that character, no
 * duplicate phrase, and each group wholly in one split. Run from the repo root:
 *
 *   node scripts/pinyin-bench/check-phrases.mjs
 *
 * The readings were written by hand; this catches typos, not judgement calls
 * (a polyphone given a valid but wrong reading still passes).
 */

import fs from "node:fs";
import path from "node:path";
import { pinnedDict, readDict } from "../wanxiang-source.mjs";

const PHRASES = path.join("bench", "pinyin", "phrases.tsv");

const readings = new Map();
for (const row of readDict(await pinnedDict("zi"))) {
  const set = readings.get(row.word) || new Set();
  set.add(row.syllables[0]);
  readings.set(row.word, set);
}

const problems = [];
const seen = new Set();
const splitOfGroup = new Map();
const counts = { dev: 0, test: 0 };
let rows = 0;
for (const [index, line] of fs.readFileSync(PHRASES, "utf8").split(/\r?\n/).entries()) {
  if (!line || line.startsWith("#")) {
    continue;
  }
  const where = `line ${index + 1}`;
  const [group, split, hanzi, pinyin, tags = ""] = line.split("\t");
  rows++;
  if (!group || !hanzi || !pinyin || !(split === "dev" || split === "test")) {
    problems.push(`${where}: malformed row`);
    continue;
  }
  counts[split]++;
  if (splitOfGroup.has(group) && splitOfGroup.get(group) !== split) {
    problems.push(`${where}: group ${group} is in both splits`);
  }
  splitOfGroup.set(group, split);
  if (seen.has(hanzi)) {
    problems.push(`${where}: duplicate ${hanzi}`);
  }
  seen.add(hanzi);
  const characters = [...hanzi];
  const syllables = pinyin.split(" ");
  if (characters.length < 2 || characters.length > 8) {
    problems.push(`${where}: ${hanzi} has ${characters.length} characters (2-8 wanted)`);
  }
  if (characters.length !== syllables.length) {
    problems.push(`${where}: ${hanzi} has ${characters.length} characters but ${syllables.length} syllables`);
    continue;
  }
  characters.forEach((character, i) => {
    const syllable = syllables[i];
    if (!/^[a-z]+$/.test(syllable)) {
      problems.push(`${where}: ${hanzi} syllable "${syllable}" is not plain a-z`);
    } else if (!readings.get(character)?.has(syllable)) {
      const known = [...(readings.get(character) || [])].join("/");
      problems.push(`${where}: ${character} read "${syllable}" in ${hanzi}; zi lists ${known || "nothing"}`);
    }
  });
  for (const tag of tags.split(",").filter(Boolean)) {
    if (!["weak", "clash", "name", "colloq", "poly"].includes(tag)) {
      problems.push(`${where}: unknown tag ${tag}`);
    }
  }
}

console.log(`${rows} phrases: ${counts.dev} dev, ${counts.test} test, ${splitOfGroup.size} groups`);
if (problems.length) {
  console.log(problems.join("\n"));
  process.exit(1);
}
console.log("ok");
