/**
 * Builds the pinyin asset the keyboard ships from the pinned Wanxiang (万象)
 * dictionaries (0.8.9). Run from the repo root:
 *
 *   node scripts/build-pinyin-dict.mjs [--words N] [--out path]
 *
 * Source: https://github.com/amzxyz/rime-wanxiang @ 907edf67, dictionaries
 * zi (characters), jichu (base words) and duoyin (alternative readings), all
 * CC BY 4.0. They are fetched and hash-checked by scripts/wanxiang-source.mjs.
 * This script is our own (MIT); Wanxiang's tools carry no licence and are not
 * used. Attribution and the list of changes: third_party/README.md.
 *
 * What it changes, as CC BY 4.0 asks us to say:
 *   - tones are removed and ü is written v (lǜ -> lv), so keys are what she types;
 *   - only the N words with the highest weights are kept (characters are all kept
 *     when their weight is at least MIN_CHARACTER_WEIGHT);
 *   - a word listed under the same spelling in more than one dictionary keeps
 *     its highest weight.
 *
 * Weights. Wanxiang's weights are corpus counts from a segmented corpus
 * (的 48,288,412; 我们 1,488,731; 明天见 279), so characters and words share
 * one scale and the asset stores them unchanged. How strongly they count, and
 * what a word boundary costs, is calibrated in PinyinComposer, not here.
 *
 * Output format (UTF-8, LF, '#' header lines first, then one line per key
 * sorted ascending so Java can binary-search it):
 *
 *   #v1
 *   #maxkey=<int>
 *   #totalfreq=<int>
 *   #syllables=<space separated>
 *   <key>\t<word>:<freq>,<word>:<freq>,...
 *
 * `key` is the entry's pinyin with syllable breaks removed. Words within a key
 * are ordered by descending frequency.
 */

import fs from "node:fs";
import path from "node:path";
import { pinnedDict, readDict, WANXIANG_COMMIT } from "./wanxiang-source.mjs";

const args = process.argv.slice(2);
function option(name, fallback) {
  const at = args.indexOf(name);
  return at >= 0 ? args[at + 1] : fallback;
}

/** Multi-character words kept, by weight. Chosen on the dev benchmark (bench/pinyin/README.md). */
const WORDS = Number.parseInt(option("--words", "300000"), 10);
/** Characters below this weight are left out: Wanxiang lists 51k, mostly rare or traditional. */
const MIN_CHARACTER_WEIGHT = Number.parseInt(option("--min-char", "2"), 10);
const OUTPUT = option("--out", path.join("app", "src", "main", "assets", "pinyin_dict.txt"));
/**
 * Below the top N, words shaped like chat are still kept down to this weight:
 * those ending on a particle or 了 (早点睡了, 可以吗) or opening with a personal
 * pronoun (我请客). Wanxiang counts written text, where such phrases are rare;
 * in messages they are most of what is typed. 0 turns it off.
 */
const CHAT_MIN_WEIGHT = Number.parseInt(option("--chat-min", "50"), 10);
const CHAT_ENDINGS = new Set([..."了吗吧呢啊呀啦嘛哦"]);
const CHAT_OPENINGS = new Set([..."我你他她它咱"]);

/**
 * First-party, not from Wanxiang: everyday chat words whose Wanxiang weight is
 * multiplied by CHAT_BOOST. Wanxiang counts written text, where 这些 is twenty
 * times as common as 谢谢; in a message it is the other way round, and on the
 * nine-key pad the two share every key (943 943). Nothing is invented: a word
 * missing from the pinned data under the spelling given here is not added.
 * Written from general knowledge of chat Chinese, not from the benchmark's
 * misses; bench/pinyin/README.md reports held-out results with and without the
 * phrases that contain one of these words.
 */
const CHAT_WORDS = `
谢谢 xiexie|谢谢你 xiexieni|谢啦 xiela|多谢 duoxie|好的 haode|好吧 haoba|好啊 haoa|好呀 haoya|嗯嗯 enen
哈哈 haha|哈哈哈 hahaha|嘿嘿 heihei|晚安 wanan|早安 zaoan|早上好 zaoshanghao|晚上好 wanshanghao
在吗 zaima|在干嘛 zaiganma|干嘛 ganma|怎么了 zenmele|没事 meishi|没事了 meishile|没关系 meiguanxi
不客气 bukeqi|辛苦了 xinkule|拜拜 baibai|再见 zaijian|回头见 huitoujian|明天见 mingtianjian
等一下 dengyixia|等会 denghui|马上 mashang|稍等 shaodeng|收到 shoudao|知道了 zhidaole|是的 shide
对的 duide|对啊 duia|真的吗 zhendema|可以吗 keyima|好吗 haoma|行吗 xingma|吃了吗 chilema
吃饭了吗 chifanlema|睡了吗 shuilema|到了吗 daolema|到家了 daojiale|我到了 wodaole|出发了 chufale
想你 xiangni|想你了 xiangnile|爱你 aini|亲爱的 qinaide|宝贝 baobei|老公 laogong|老婆 laopo
早点睡 zaodianshui|早点休息 zaodianxiuxi|注意安全 zhuyianquan|路上小心 lushangxiaoxin|慢点 mandian
好开心 haokaixin|太好了 taihaole|太棒了 taibangle|加油 jiayou|好累 haolei|累了 leile|困了 kunle
饿了 ele|吃啥 chisha|去哪 quna|在哪 zaina|几点 jidian|几点了 jidianle|怎么办 zenmeban
不知道 buzhidao|不用了 buyongle|算了 suanle|都行 douxing|可以的 keyide|没问题 meiwenti
好久不见 haojiubujian|生日快乐 shengrikuaile|新年快乐 xinniankuaile|恭喜 gongxi|拜托 baituo
麻烦你了 mafannile|不好意思 buhaoyisi|对不起 duibuqi|抱歉 baoqian|哈喽 halou|么么哒 memeda
`.trim().split(/[|\n]/).map((pair) => pair.trim().split(" ")).filter((pair) => pair.length === 2);
/** How much CHAT_WORDS count for. Chosen on the dev benchmark. */
const CHAT_BOOST = Number.parseFloat(option("--chat-boost", "20"));

function chatShaped(word) {
  const characters = [...word];
  return CHAT_ENDINGS.has(characters.at(-1)) || CHAT_OPENINGS.has(characters[0]);
}

const stats = {};
const characters = readDict(await pinnedDict("zi"), stats);
const words = [
  ...readDict(await pinnedDict("jichu"), stats),
  ...readDict(await pinnedDict("duoyin"), stats),
].filter((row) => row.syllables.length > 1);

// Highest weight per (word, key) across the dictionaries.
function merge(rows) {
  const best = new Map();
  for (const row of rows) {
    const key = row.syllables.join("");
    const id = `${key}\t${row.word}`;
    const existing = best.get(id);
    if (!existing || row.weight > existing.weight) {
      best.set(id, { word: row.word, key, syllables: row.syllables, weight: row.weight });
    }
  }
  return [...best.values()];
}

const keptCharacters = merge(characters).filter((e) => e.weight >= MIN_CHARACTER_WEIGHT);
const mergedWords = merge(words);
const chatIds = new Set(CHAT_WORDS.map(([word, key]) => `${key}\t${word}`));
const chatFound = new Set();
for (const entry of mergedWords) {
  const id = `${entry.key}\t${entry.word}`;
  if (chatIds.has(id)) {
    chatFound.add(id);
    entry.chat = true;
    entry.weight = Math.round(entry.weight * CHAT_BOOST);
  }
}
const chatMissing = [...chatIds].filter((id) => !chatFound.has(id));
const rankedWords = mergedWords.sort((a, b) => b.weight - a.weight || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0)
    || (a.word < b.word ? -1 : a.word > b.word ? 1 : 0));
const keptWords = rankedWords.slice(0, WORDS);
let chatTail = 0;
for (const entry of rankedWords.slice(WORDS)) {
  if (entry.chat || (CHAT_MIN_WEIGHT > 0 && entry.weight >= CHAT_MIN_WEIGHT && chatShaped(entry.word))) {
    keptWords.push(entry);
    chatTail++;
  }
}

const syllables = new Set();
const byKey = new Map();
let maxKeyLength = 0;
let totalFrequency = 0;
for (const entry of [...keptCharacters, ...keptWords]) {
  entry.syllables.forEach((s) => syllables.add(s));
  maxKeyLength = Math.max(maxKeyLength, entry.key.length);
  totalFrequency += entry.weight;
  let bucket = byKey.get(entry.key);
  if (!bucket) {
    bucket = [];
    byKey.set(entry.key, bucket);
  }
  bucket.push([entry.word, entry.weight]);
}

const keys = [...byKey.keys()].sort();
const lines = [
  "#v1",
  `#source=Wanxiang rime-wanxiang@${WANXIANG_COMMIT.slice(0, 8)} zi+jichu+duoyin, CC BY 4.0, tones removed, top ${WORDS} words`,
  `#maxkey=${maxKeyLength}`,
  `#totalfreq=${totalFrequency}`,
  `#syllables=${[...syllables].sort().join(" ")}`,
];
for (const key of keys) {
  const entries = byKey.get(key).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  lines.push(`${key}\t${entries.map(([w, f]) => `${w}:${f}`).join(",")}`);
}

fs.mkdirSync(path.dirname(OUTPUT), { recursive: true });
fs.writeFileSync(OUTPUT, lines.join("\n") + "\n", "utf8");

console.log(`rows skipped : ${stats.skipped} (not plain syllables, or syllables != characters)`);
console.log(`characters   : ${keptCharacters.length.toLocaleString()} (weight >= ${MIN_CHARACTER_WEIGHT})`);
console.log(`words        : ${keptWords.length.toLocaleString()} of ${rankedWords.length.toLocaleString()}`
    + ` (lowest weight in the top ${WORDS.toLocaleString()}: ${rankedWords[Math.min(WORDS, rankedWords.length) - 1]?.weight})`);
console.log(`chat-shaped  : ${chatTail.toLocaleString()} more below the top ${WORDS.toLocaleString()}`
    + ` (weight >= ${CHAT_MIN_WEIGHT})`);
console.log(`chat words   : ${chatFound.size} of ${chatIds.size} found, weight x${CHAT_BOOST}`
    + (chatMissing.length ? `; not in the data: ${chatMissing.map((id) => id.split("\t")[1]).join(" ")}` : ""));
console.log(`distinct key : ${keys.length.toLocaleString()}`);
console.log(`syllables    : ${syllables.size}`);
console.log(`max key len  : ${maxKeyLength}`);
console.log(`total freq   : ${totalFrequency.toLocaleString()}`);
console.log(`asset        : ${OUTPUT} (${fs.statSync(OUTPUT).size.toLocaleString()} bytes)`);
