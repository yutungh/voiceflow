// VoiceFlow B3: B2's calm structure, warmed up. Warm low-chroma greys, one deep-teal accent used tonally (tint fill, never a
// solid block in light), Phosphor icons (MIT) in three weights, tinted icon tiles, real target-app icons on every dictation,
// the waveform as the signature visual, soft elevation, Inter with Inter Display sizes, a 7-day stat strip, spot
// illustrations for empty states and first run, and self-explaining problems with one fix each. Demo content only.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
import { esc, logo } from './data.mjs';
const here = path.dirname(fileURLToPath(import.meta.url));
const PH = JSON.parse(fs.readFileSync(path.join(here, 'phosphor.json'), 'utf8'));

// ---------- primitives ----------
const ic = (name, w = 'regular', cls = 'i') => `<svg class="${cls}" viewBox="0 0 256 256" fill="currentColor" aria-hidden="true">${PH[name][w]}</svg>`;
let n = 0; const uid = (p) => `${p}${++n}`;
const kc = (keys) => `<span class="keys" aria-label="${esc(keys.join(' plus '))}">${keys.map((x) => `<kbd>${esc(x)}</kbd>`).join('')}</span>`;
const btn = (label, { kind = 'sec', keys, icon, cls = '' } = {}) => `<button class="btn ${kind} ${cls}" type="button">${icon ? ic(icon) : ''}<span>${label}</span>${keys ? kc(keys) : ''}</button>`;
const tile = (name, tone = 'acc', size = '') => `<span class="tile ${tone} ${size}">${ic(name, 'duotone')}</span>`;
const wave = (count = 24, seed = 1, h = 16, cls = 'wv') => `<span class="${cls}" aria-hidden="true">${Array.from({ length: count }, (_, i) => `<i style="height:${Math.max(2, Math.round(h * (0.18 + 0.82 * Math.abs(Math.sin((i + seed) * 0.9) * Math.cos((i + seed * 2) * 0.37)))))}px"></i>`).join('')}</span>`;
const spark = (vals, cls = 'spark', unit = '', tipAt = -1, fmt = (v) => v.toLocaleString('en-US')) => { const max = Math.max(...vals); return `<span class="sparkw"><span class="spl">Last 7 days</span><span class="${cls}" role="img" aria-label="Last 7 days: ${vals.map(fmt).join(', ')}">${vals.map((v, i) => `<span class="spb" title="${DAYN[i]}: ${fmt(v)}${unit}"><i class="${i === vals.length - 1 ? 'today' : ''}" style="height:${Math.max(3, Math.round(26 * v / max))}px"></i><small>${DAYS[i]}</small>${i === tipAt ? `<span class="sp-tip">${DAYN[i]}: ${fmt(v)}${unit}</span>` : ''}</span>`).join('')}</span></span>`; };
const ASSIST = 'Milo'; // per-user setting (Settings > Your assistant's name); another person's install might say Febe
const TIDY = 'Fixes punctuation and filler words in your chosen style';
const term = (word, tip, open = false) => `<span class="term${open ? ' open' : ''}" tabindex="0">${word}<span class="tipb" role="tooltip">${tip}</span></span>`;
const DAYS = ['T', 'W', 'T', 'F', 'S', 'S', 'M']; const DAYN = ['Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday', 'Today'];
const chip = (tone, text, icon) => `<span class="chip ${tone}">${icon ? ic(icon, 'fill', 'i xs') : `<i class="dot ${tone}"></i>`}${text}</span>`;
const sel = (label, cls = '') => `<span class="sel ${cls}">${label}${ic('caret-down', 'regular', 'i sm')}</span>`;

// Target-app stand-ins: simple, recognisable shapes and colours, always labelled next to them.
const APPS = {
  kite: { name: 'Kite', svg: '<rect width="24" height="24" rx="6" fill="#14181F"/><path d="M4.5 14.2c2.6-4.3 7.6-6 12.4-4.2 1.3.5 2.3 1.4 3 2.5-1.6-.4-3.1-.1-4.3.8-2.2 1.6-5.2 2.2-8 1.4l-3.1-.5z" fill="#fff"/><path d="M10.6 8.9 12.4 5l1 4.4z" fill="#fff"/><ellipse cx="9.4" cy="13" rx="1.6" ry=".8" fill="#14181F"/>' },
  telegram: { name: 'Telegram', svg: '<circle cx="12" cy="12" r="12" fill="#2AA3E0"/><path d="M5.3 11.6 17.4 6.9c.6-.2 1.1.1.9.9l-2 9.6c-.2.7-.6.8-1.2.5l-3.2-2.4-1.6 1.5c-.2.2-.3.3-.6.3l.2-3.3 6-5.4c.3-.2-.1-.4-.4-.2l-7.4 4.7-3.2-1c-.7-.2-.7-.7.1-1z" fill="#fff"/>' },
  chrome: { name: 'Chrome', svg: '<circle cx="12" cy="12" r="12" fill="#2E9E4F"/><path d="M12 0a12 12 0 0 1 10.4 6H12a6 6 0 0 0-5.2 3L3.6 3.5A12 12 0 0 1 12 0z" fill="#D9473C"/><path d="M22.4 6A12 12 0 0 1 12 24l5.2-9a6 6 0 0 0 0-6z" fill="#F2C230"/><circle cx="12" cy="12" r="5.2" fill="#fff"/><circle cx="12" cy="12" r="4" fill="#3B7BE8"/>' },
  vscode: { name: 'VS Code', svg: '<rect width="24" height="24" rx="6" fill="#1F7BC8"/><path d="M16.6 4.5 9.4 11.2 6.2 8.7 4.8 9.4v5.2l1.4.7 3.2-2.5 7.2 6.7 2.6-1.2V5.7zm0 4.3v6.4L12.4 12z" fill="#fff"/>' },
  outlook: { name: 'Outlook', svg: '<rect width="24" height="24" rx="6" fill="#0F6CBD"/><rect x="11" y="6.5" width="9" height="11" rx="1.5" fill="#5EB0F2"/><path d="m11 8.4 4.5 3.1L20 8.4" stroke="#0F6CBD" stroke-width="1.2" fill="none"/><rect x="3.5" y="5" width="10" height="14" rx="2" fill="#0A4F8C"/><ellipse cx="8.5" cy="12" rx="2.6" ry="3.1" fill="none" stroke="#fff" stroke-width="1.6"/>' },
  claude: { name: 'Claude Code', svg: '<rect width="24" height="24" rx="6" fill="#2A2724"/><path d="m6.5 8.5 3.5 3.5-3.5 3.5" fill="none" stroke="#F1E9DF" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><path d="M12 16h5.5" stroke="#E8A87C" stroke-width="1.8" stroke-linecap="round"/>' },
  unknown: { name: 'Unknown app', svg: '<rect width="24" height="24" rx="6" fill="#8A857D"/><path d="M9.6 9.4a2.5 2.5 0 1 1 3.6 2.3c-.8.4-1.2 1-1.2 1.8v.4" fill="none" stroke="#fff" stroke-width="1.8" stroke-linecap="round"/><circle cx="12" cy="17" r="1.1" fill="#fff"/>' },
};
const app = (id, size = 20) => `<svg class="appi" width="${size}" height="${size}" viewBox="0 0 24 24" role="img" aria-label="${APPS[id].name}">${APPS[id].svg}</svg>`;
const appL = (id, size = 20) => `<span class="appl">${app(id, size)}<span>${APPS[id].name}</span></span>`;

// Spot illustrations: 112 px duotone line art in the brand palette, built from the waveform, microphone and keycap motifs.
const ILL = {
  mic: '<rect x="40" y="18" width="32" height="50" rx="16" class="f"/><rect x="40" y="18" width="32" height="50" rx="16" class="s"/><path d="M30 56a26 26 0 0 0 52 0M56 82v12M44 94h24" class="s"/><path d="M14 46v10M20 40v22M92 40v22M98 46v10" class="s m"/>',
  keys: '<rect x="10" y="50" width="44" height="34" rx="9" class="f"/><rect x="10" y="50" width="44" height="34" rx="9" class="s"/><rect x="62" y="50" width="40" height="34" rx="9" class="f"/><rect x="62" y="50" width="40" height="34" rx="9" class="s"/><path d="M10 76h44M62 76h40" class="s t"/><text x="32" y="69" class="k">Ctrl</text><text x="82" y="69" class="k">Space</text><path d="M30 34v8M38 26v24M46 32v12M54 22v32M62 28v20M70 34v8M78 30v16" class="s m"/>',
  done: '<circle cx="56" cy="56" r="30" class="f"/><circle cx="56" cy="56" r="30" class="s"/><path d="m44 57 8 8 16-17" class="s b"/><path d="M8 52v8M14 46v20M98 46v20M104 52v8" class="s m"/>',
  history: '<rect x="22" y="22" width="68" height="20" rx="7" class="f"/><rect x="22" y="22" width="68" height="20" rx="7" class="s"/><rect x="22" y="48" width="68" height="20" rx="7" class="s"/><rect x="22" y="74" width="68" height="20" rx="7" class="s t"/><path d="M32 32h2M38 28v8M44 30v4M50 27v10M56 30v4M62 29v6" class="s m"/><path d="M32 58h20" class="s t"/><path d="M32 84h14" class="s t"/>',
  search: '<circle cx="48" cy="48" r="26" class="f"/><circle cx="48" cy="48" r="26" class="s"/><path d="m67 67 22 22" class="s b"/><path d="M34 48v2M40 42v14M46 38v20M52 44v8M58 46v4" class="s m"/>',
  bulb: '<path d="M56 16a28 28 0 0 0-16 51c3 2 4 5 4 8v5h24v-5c0-3 1-6 4-8a28 28 0 0 0-16-51z" class="f"/><path d="M56 16a28 28 0 0 0-16 51c3 2 4 5 4 8v5h24v-5c0-3 1-6 4-8a28 28 0 0 0-16-51z" class="s"/><path d="M46 92h20" class="s"/><path d="m46 46 7 7 13-13" class="s b"/>',
  book: '<path d="M16 26c14-6 28-6 40 2 12-8 26-8 40-2v58c-14-6-28-6-40 2-12-8-26-8-40-2z" class="f"/><path d="M16 26c14-6 28-6 40 2 12-8 26-8 40-2v58c-14-6-28-6-40 2-12-8-26-8-40-2zM56 28v58" class="s"/><path d="M28 44h16M28 54h12M68 44h16M68 54h10" class="s t"/>',
  micoff: '<rect x="40" y="18" width="32" height="50" rx="16" class="f"/><rect x="40" y="18" width="32" height="50" rx="16" class="s"/><path d="M30 56a26 26 0 0 0 52 0M56 82v12M44 94h24" class="s"/><path d="m24 20 64 72" class="s b w"/>',
  wave: '<path d="M14 56h4M24 44v24M34 34v44M44 26v60M54 38v36M64 22v68M74 34v44M84 44v24M94 52v8" class="s b"/><circle cx="56" cy="56" r="44" class="f o"/>',
};
const illo = (k, size = 112) => `<svg class="illo" width="${size}" height="${size}" viewBox="0 0 112 112" aria-hidden="true">${ILL[k]}</svg>`;

// ---------- data ----------
const segs = ['Hey ', ['phoebe', 'Febe', 'Kite app rule'], ', can you check whether the ', ['pinewud', 'Pinewood', 'Correction'], ' preview deployed on ', ['versal', 'Vercel', 'Word list'], '? If it did, send ', ['mylo', 'Milo', 'Correction'], ' the link and I’ll review it after lunch.'];
const cleaned = segs.map((s) => (typeof s === 'string' ? s : s[1])).join('');
const lastHtml = () => segs.map((s) => (typeof s === 'string' ? esc(s) : `<span class="fix" title="Heard “${esc(s[0])}”">${esc(s[1])}</span>`)).join('');
const H = [
  { day: 'Today', items: [
    { t: '10:58', app: 'kite', text: cleaned, dur: '0:09', seed: 1 },
    { t: '10:42', app: 'telegram', text: 'tell the team the demo moved to thursday at two and i will bring the slides', dur: '0:06', fail: 'cleanup', seed: 2 },
    { t: '10:31', app: 'claude', text: 'Run the Pinewood tests again and show me only the failures.', dur: '0:05', seed: 3 },
    { t: '09:47', app: 'outlook', text: 'Thanks for the update. I’ll review the numbers before Friday and come back with questions.', dur: '0:11', seed: 4 },
    { t: '09:12', app: 'unknown', text: '', dur: '0:04', fail: 'held', seed: 5 },
    { t: '08:55', app: 'kite', text: 'Milo, add a reminder to renew the Vercel domain next Tuesday.', dur: '0:05', seed: 6 },
  ] },
  { day: 'Yesterday', items: [
    { t: '18:02', app: 'kite', text: '', dur: '0:07', fail: 'transcribe', seed: 7 },
    { t: '16:20', app: 'telegram', text: 'Ask Zefira for the weekly reset when you get a chance.', dur: '0:04', seed: 8 },
    { t: '15:05', app: 'vscode', text: 'Rename the pack loader to loadPack and add a test for it.', dur: '0:06', seed: 9 },
    { t: '14:09', app: 'chrome', text: 'Vercel preview deployment limits', dur: '0:03', seed: 10 },
  ] },
];
const NAV = [['home', 'Home', 'house'], ['history', 'History', 'clock-counter-clockwise'], ['vocab', 'Vocabulary', 'book-open-text'], ['suggest', 'Ideas', 'lightbulb'], ['styles', 'Styles', 'magic-wand'], ['settings', 'Settings', 'gear-six']];

// ---------- shell ----------
const titlebar = (t = 'VoiceFlow') => `<div class="tb">${logo(16, uid('tb'))}<span>${t}</span><span class="tb-c" aria-label="Window controls"><i>–</i><i>□</i><i>✕</i></span></div>`;
const rail = (active, { badges = true } = {}) => `<aside class="rail">
  <div class="brand">${logo(26, uid('br'))}<b>VoiceFlow</b></div>
  <button class="cmd" type="button">${ic('magnifying-glass')}<span>Search or run a command</span>${kc(['Ctrl', 'K'])}</button>
  <nav aria-label="Main">${NAV.map(([id, label, icon]) => `<a class="${id === active ? 'on' : ''}" href="#">${ic(icon, id === active ? 'fill' : 'regular')}<span class="lbl">${label}</span>${id === 'suggest' && badges ? '<em>2</em>' : ''}</a>`).join('')}</nav>
  <div class="rail-foot">${wave(18, 3, 14, 'wv rf')}<p>Dictate in any app</p><div>${kc(['Ctrl', 'Space'])}<span>Start or stop</span></div><div>${kc(['Alt', 'Shift', 'D'])}<span>Hold to talk</span></div></div>
</aside>`;
const statusbar = (problem = true, fresh = false) => `<footer class="sb"><span class="ok"><i class="dot"></i>Ready to dictate</span>${problem ? '<a class="warn" href="#"><i class="dot"></i>1 problem needs you</a>' : ''}<span>Vocabulary: 214 words, updated 07:12</span>${fresh ? '' : '<span>Kite uses Casual</span>'}<span class="r">Privacy: strict</span><span>Theme: System</span></footer>`;
const shell = (active, main, { overlay = '', problem = true, fresh = false } = {}) => `<div class="win">${titlebar()}<div class="body">${rail(active, { badges: !fresh })}<main class="main">${main}</main></div>${statusbar(problem, fresh)}${overlay}</div>`;
const head = (title, sub, right = '') => `<header class="ph"><div><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}</div><div class="ph-r">${right}</div></header>`;

// ---------- Home ----------
const stat = (icon, label, value, delta, vals, deltaTone = 'up', unit = '', tipAt = -1, fmt) => `<section class="card stat"><div class="st-h">${tile(icon)}<span>${label}</span></div><div class="st-b"><div><b class="num">${value}</b><span class="delta ${deltaTone}">${delta}</span></div>${spark(vals, 'spark', unit, tipAt, fmt)}</div></section>`;
const statStrip = (empty = false) => empty
  ? `<div class="stats">${['Words this week', 'Speaking speed', 'Time saved'].map((l, i) => `<section class="card stat"><div class="st-h">${tile(['text-aa', 'gauge', 'timer'][i])}<span>${l}</span></div><div class="st-b"><div><b class="num dim">—</b><span class="delta">Starts after your first dictation</span></div>${spark([1, 1, 1, 1, 1, 1, 1], 'spark empty')}</div></section>`).join('')}</div>`
  : `<div class="stats">${stat('text-aa', 'Words this week', '3,412', `${ic('arrow-right', 'regular', 'i xs up')}18% more than last week`, [380, 420, 510, 300, 460, 690, 652], 'up', ' words', 5)}${stat('gauge', 'Speaking speed', '148 wpm', 'About 3× your typing', [139, 142, 151, 147, 144, 150, 148], 'flat', ' wpm')}${stat('timer', 'Time saved', '1 h 52 m', 'Compared with typing', [12, 14, 18, 9, 15, 23, 21], 'flat', ' min')}</div>`;

const problemCard = ({ narrow = false } = {}) => `<div class="prob bad">
  <span class="tile bad">${ic('warning', 'duotone')}</span>
  <div class="pb">
    <b>Nothing was pasted into Telegram</b>
    <p>${term('Tidy-up', TIDY)} took over 10 seconds, so your words were kept instead of pasted. Today <span class="num">10:42</span>.</p>
    <div class="acts">${btn('Retry tidy-up', { kind: 'pri', icon: 'arrow-clockwise', keys: narrow ? null : ['R'] })}${btn('Paste raw text', { kind: 'text' })}${narrow ? '' : btn('Dismiss', { kind: 'text' })}</div>
  </div></div>`;
const suggestRow = (narrow = false) => `<div class="prob ind"><span class="tile ind">${ic('lightbulb', 'duotone')}</span><div class="pb"><b>Did you mean “Vercel”?</b><p>VoiceFlow heard “Versal” 3 times this week.</p>${narrow ? `<div class="acts">${btn('Review', { kind: 'sec' })}</div>` : ''}</div>${narrow ? '' : `<div class="pside">${btn('Review suggestions', { kind: 'sec' })}</div>`}</div>`;
const needsYou = (narrow = false) => `<section class="card ny"><div class="c-h">${tile('warning-circle', 'bad')}<h2>Needs you</h2><span class="cnt">1</span></div>${problemCard({ narrow })}</section>`;
const ideasRow = (narrow = false) => `<section class="card ideas">${tile('lightbulb', 'ind')}<div class="pb"><b>Ideas for you <span class="cnt">2</span></b><p>Did you mean “Vercel”? VoiceFlow heard “Versal” 3 times${narrow ? '.' : ' this week.'}</p></div>${btn(narrow ? 'Review' : 'Review ideas', { kind: 'text', icon: 'arrow-right' })}</section>`;
const lastCard = (narrow = false) => `<section class="card last"><div class="c-h">${tile('waveform')}<h2>Last dictation</h2><span class="meta">${appL('kite', 18)}${chip('neutral', 'Casual')}<span class="num">10:58</span></span></div>
  <p class="lc">${lastHtml()}</p>
  <div class="lfoot">${wave(narrow ? 18 : 30, 1, 18, 'wv big')}<span class="num sub">0:09</span>${narrow ? '' : `<span class="fixes">${ic('book-open-text', 'regular', 'i sm')}4 words fixed</span>`}<span class="lacts">${btn('Copy', { icon: 'copy', kind: 'sec', keys: narrow ? null : ['C'] })}${btn('Play', { icon: 'play', kind: 'text' })}</span></div></section>`;
const recent = () => `<section class="card rec"><div class="c-h">${tile('clock-counter-clockwise')}<h2>Recent</h2><a class="lk" href="#">All history</a></div>
  <ul class="rl">${[...H[0].items.slice(1, 5), 'Yesterday', ...H[1].items.slice(1, 3)].map((r) => r === 'Yesterday' ? '<li class="rdiv">Yesterday</li>' : `<li>${app(r.app, 20)}<div><b>${APPS[r.app].name}<span class="num sub">${r.t}</span></b><span>${r.fail === 'cleanup' ? '<span class="bad">Not pasted.</span> Tidy-up timed out' : r.fail === 'held' ? '<span class="warn">Held.</span> App unknown' : esc(r.text)}</span></div></li>`).join('')}</ul></section>`;
const intoCard = () => `<section class="card into"><div class="c-h">${tile('magic-wand')}<h2>Dictating into</h2></div><div class="into-b">${app('kite', 32)}<div><b>Kite</b><span>Style: Casual</span></div>${btn('Change', { kind: 'text' })}</div></section>`;
const intoChip = () => `<div class="into"><span class="lab">Dictating into</span>${app('kite', 20)}<b>Kite</b><span class="sub">Casual style</span>${btn('Change', { kind: 'text' })}</div>`;
const homeMain = () => `${head('Home', `<span class="ready"><i class="dot ok"></i>Ready</span> Press ${kc(['Ctrl', 'Space'])} in any app, or hold ${kc(['Alt', 'Shift', 'D'])}`, intoChip())}
${statStrip()}
<div class="hg"><div class="col">${needsYou()}${ideasRow()}${lastCard()}</div><div class="col">${recent()}</div></div>`;
const homeEmptyMain = () => `${head('Home', `<span class="ready"><i class="dot ok"></i>Ready</span> Set up and waiting for your first dictation`)}
${statStrip(true)}
<div class="hg"><div class="col"><section class="card empty-big">${illo('keys', 120)}<div><h2 class="eh">Your dictations will show up here</h2>
<p class="sub">Each one shows the text VoiceFlow pasted, the app it went to, and which words it fixed.</p>
<ol class="howto"><li><span class="n">1</span><span>Click where you want to type, in any app</span></li><li><span class="n">2</span><span>Press ${kc(['Ctrl', 'Space'])} and talk</span></li><li><span class="n">3</span><span>Press ${kc(['Ctrl', 'Space'])} again to paste</span></li></ol>
<div class="acts">${btn('Try a test dictation', { kind: 'pri', icon: 'microphone' })}${btn('Change the keys', { kind: 'text' })}</div></div></section></div>
<div class="col"><section class="card"><div class="c-h">${tile('shield-check', 'ok')}<h2>Ready to go</h2></div><ul class="checks">
<li>${ic('check-circle', 'fill', 'i ok')}<div><b>Microphone</b><span>USB microphone</span></div></li><li>${ic('check-circle', 'fill', 'i ok')}<div><b>Speech to text</b><span>OpenAI key works</span></div></li>
<li>${ic('check-circle', 'fill', 'i ok')}<div><b>Vocabulary</b><span>214 words from your notes</span></div></li><li>${ic('check-circle', 'fill', 'i ok')}<div><b>Privacy</b><span>Password boxes are never filled</span></div></li></ul></section></div></div>`;

// narrow
const ntop = (title = 'Home') => `<div class="ntop"><button class="btn sec nb" type="button">${ic('list')}<span>Menu</span></button><b>${title}</b>${chip('warn', '1 problem')}</div>`;
const homeNarrow = () => `<div class="win">${titlebar()}${ntop()}<main class="main nm">
<div class="stats one">${stat('text-aa', 'Words this week', '3,412', `${ic('arrow-right', 'regular', 'i xs up')}18% more than last week`, [380, 420, 510, 300, 460, 690, 652], 'up', ' words')}</div>
${needsYou(true)}${ideasRow(true)}${lastCard(true)}</main><footer class="sb"><span class="ok"><i class="dot"></i>Ready</span><a class="warn" href="#"><i class="dot"></i>1 problem</a><span class="r">Updated 07:12</span></footer></div>`;
const drawer = () => `<div class="win">${titlebar()}${ntop()}<main class="main nm">${needsYou(true)}</main><footer class="sb"><span class="ok"><i class="dot"></i>Ready</span><span class="r">Updated 07:12</span></footer>
<div class="scrim"></div><aside class="drawer" aria-label="Menu"><div class="dr-h">${logo(24, uid('dr'))}<b>VoiceFlow</b>${btn('Close', { kind: 'text', icon: 'x' })}</div>
<button class="cmd" type="button">${ic('magnifying-glass')}<span>Search or run a command</span>${kc(['Ctrl', 'K'])}</button>
<nav aria-label="Main">${NAV.map(([id, label, icon]) => `<a class="${id === 'home' ? 'on' : ''}" href="#">${ic(icon, id === 'home' ? 'fill' : 'regular')}<span class="lbl">${label}</span>${id === 'suggest' ? '<em>2</em>' : ''}</a>`).join('')}</nav>
<div class="rail-foot">${wave(18, 3, 14, 'wv rf')}<p>Dictate in any app</p><div>${kc(['Ctrl', 'Space'])}<span>Start or stop</span></div><div>${kc(['Alt', 'Shift', 'D'])}<span>Hold to talk</span></div></div></aside></div>`;

// ---------- Pill ----------
const pm = () => `<span class="pm">${logo(22, uid('pm'))}</span>`;
const pill = (cls, body) => `<div class="pill ${cls}">${pm()}${body}</div>`;
const hint = (label, keys) => `<span class="hk"><span>${label}</span>${kc(keys)}</span>`;
const PILLS = {
  listening: pill('', `<i class="rec"></i><b>Listening</b>${wave(22, 2, 20, 'wv live')}${app('kite', 18)}<span class="sub">Kite</span><span class="vr"></span>${hint('Stop', ['Ctrl', 'Space'])}${hint('Cancel', ['Esc'])}`),
  listenStop: pill('', `<i class="rec"></i><b>Listening</b>${wave(22, 2, 20, 'wv live')}<span class="vr"></span>${hint('Stop', ['Ctrl', 'Space'])}`),
  processing: pill('', `<span class="shim">${wave(22, 2, 6, 'wv rest')}</span><b>Tidying up</b><span class="sub">Casual style</span><span class="vr"></span>${hint('Cancel', ['Esc'])}`),
  done: pill('ok', `<span class="okc">${ic('check', 'regular')}</span><b>Pasted into Kite</b>${app('kite', 18)}<span class="vr"></span>${hint('Re-run tidy-up', ['Ctrl', 'Shift', 'R'])}`),
  noaudio: pill('warn', `${ic('microphone-slash', 'duotone', 'i wi')}<span class="flat">${wave(10, 2, 2, 'wv still')}</span><div class="pt"><b>No sound from your microphone</b><span class="sub">Is your mic muted or unplugged?</span></div>${btn('Choose microphone', { kind: 'pri' })}${btn('Sound settings', { kind: 'text', icon: 'arrow-square-out' })}`),
  error: pill('bad', `${ic('warning', 'duotone', 'i bi')}<div class="pt"><b>Nothing was pasted into Telegram</b><span class="sub">Tidy-up timed out. Your words are saved.</span></div>${btn('Retry', { kind: 'pri', icon: 'arrow-clockwise' })}${btn('Paste raw', { kind: 'text' })}`),
  paused: pill('', `${ic('pause', 'duotone', 'i sub')}<b>Dictation is paused</b><span class="sub">Resume from the tray menu</span>`),
};
const desk = (inner, { taskbar = false, win = true } = {}) => `<div class="desk">${win ? `<div class="bgw"><div class="bw-h">${app('kite', 16)}<span>Kite</span></div><div class="bw-msg"><span class="av">F</span><p>Febe: I pushed the preview. Want to check it?</p></div><div class="bw-l"></div><div class="bw-l s"></div><div class="bw-in"><span>Message Febe</span><i class="caret"></i></div></div>` : ''}${inner}${taskbar ? `<div class="taskbar"><span class="tk">${logo(20, uid('tk'))}<i></i></span><span class="tk-t num">10:59<br>16/11/2026</span></div>` : ''}</div>`;
const pillFrame = () => desk(`<div class="pcol"><h3>The recording pill</h3><p class="pcap">Bottom centre of the screen, above every app. It never takes your keyboard. Each state has its own icon and words, not only a colour.</p>
${[['Listening', 'listening'], ['Tidying up', 'processing'], ['Pasted', 'done'], ['No sound', 'noaudio'], ['Something went wrong', 'error'], ['Paused', 'paused']].map(([c, k]) => `<div class="pr"><span class="cap">${c}</span>${PILLS[k]}</div>`).join('')}</div>
<div class="plive">${PILLS.listening}</div>`);
const pillNoAudio = () => desk(`<div class="plive">${PILLS.noaudio}</div>`);
// micro-interaction notes
const PM = {
  appear: pill('pmin', `<i class="rec"></i><b>Listening</b>${wave(12, 2, 16, 'wv live')}`),
  listen: pill('pmin', `<i class="rec"></i><b>Listening</b>${wave(12, 2, 16, 'wv live')}`),
  nosound: pill('pmin warn', `${ic('microphone-slash', 'duotone', 'i wi')}<b>No sound</b>${wave(10, 2, 2, 'wv still')}`),
  clean: pill('pmin', `<span class="shim">${wave(12, 2, 5, 'wv rest')}</span><b>Tidying up</b>`),
  done: pill('pmin ok', `<span class="okc">${ic('check')}</span><b>Pasted</b>${app('kite', 16)}`),
  error: pill('pmin bad', `${ic('warning', 'duotone', 'i bi')}<b>Not pasted</b>${btn('Retry', { kind: 'pri' })}`),
};
const step = (num, title, body, art) => `<div class="mstep"><div class="mart">${art}</div><p class="mn"><span class="n">${num}</span><b>${title}</b></p><p class="md">${body}</p></div>`;
const motionFrame = () => `<div class="desk plain"><div class="motion"><h3>How the pill moves</h3><p class="pcap">Motion only shows a change of state. It is quick (under 200 ms), uses no bounce, and turns off with Windows' reduce-motion setting.</p>
<div class="msteps">
${step(1, 'Appears', `Press ${kc(['Ctrl', 'Space'])}. The pill rises 8 px and fades in.<span class="tm">180 ms, ease-out</span>`, `<div class="ghost">${PM.appear}</div><div class="arrow-up">${ic('arrow-right', 'regular', 'i')}</div>`)}
${step(2, 'Listens', 'Bars follow your voice live, so you can see the microphone works.<span class="tm">Bars only, 60 fps</span>', PM.listen)}
${step(3, 'No sound', 'Nothing coming in for 3 seconds: the bars go flat and the pill says so, with Choose microphone.<span class="tm">Cross-fade, 200 ms</span>', PM.nosound)}
${step(4, 'Tidies up', 'Bars ease down to a resting line; a soft shimmer runs across them while the text is tidied.<span class="tm">200 ms, then a 1.2 s loop</span>', PM.clean)}
${step(5, 'Pastes', 'The tick cross-fades in (scale 0.25 to 1, blur 4 to 0 px), holds 600 ms, then the pill slides down 8 px and fades.<span class="tm">300 ms in, 150 ms out</span>', PM.done)}
${step(6, 'Needs you', 'A problem does not fade. The pill grows a line of text and one fix, and stays until you choose.<span class="tm">Height change, 200 ms</span>', PM.error)}
</div>
<div class="rms"><div class="rm">${ic('info', 'duotone', 'i')}<p><b>Reduced motion:</b> no slide and no shimmer. States swap instantly with the same icons and words, so nothing depends on movement.</p></div>
<div class="rm">${ic('hand', 'duotone', 'i')}<p><b>Buttons:</b> press shrinks them to 96% for 100 ms. Hover changes colour only. Nothing animates on ordinary clicks.</p></div></div></div></div>`;
// problems
const pcard = (tone, icon, title, cause, fix, extra = '') => `<div class="prob ${tone} lone"><span class="tile ${tone}">${ic(icon, 'duotone')}</span><div class="pb"><b>${title}</b><p>${cause}</p><div class="acts">${fix}${extra}</div></div></div>`;
const problemsFrame = () => `<div class="desk plain"><div class="perr"><h3>Problems explain themselves</h3><p class="pcap">Each one: what happened, why, and one fix. A thin edge and an icon show how serious it is. Your words are never lost.</p>
<div class="pgrid">
${pcard('bad', 'warning', 'Nothing was pasted into Telegram', 'Tidy-up took longer than 10 seconds. Your words are saved in History.', btn('Retry tidy-up', { kind: 'pri', icon: 'arrow-clockwise' }), btn('Paste raw text', { kind: 'text' }))}
${pcard('bad', 'microphone-slash', 'VoiceFlow can’t hear you', 'Windows privacy settings are blocking the microphone for VoiceFlow.', btn('Open microphone settings', { kind: 'pri', icon: 'arrow-square-out' }))}
${pcard('warn', 'lock-simple', 'Not pasted: this looks like a password box', 'VoiceFlow never types into password fields. Your text is kept here.', btn('Copy text', { kind: 'pri', icon: 'copy' }))}
${pcard('warn', 'question', 'Which app is this?', 'VoiceFlow couldn’t tell which app is in front, so nothing was sent. The audio is kept on this PC.', btn('Send this one anyway', { kind: 'pri' }), btn('Keep in History', { kind: 'text' }))}
${pcard('bad', 'x-circle', 'Speech to text didn’t answer', 'OpenAI returned an error (503). Your audio is saved on this PC.', btn('Try again', { kind: 'pri', icon: 'arrow-clockwise' }))}
${pcard('warn', 'key', 'Your OpenAI key stopped working', 'OpenAI says the key is no longer valid. Dictation is paused until it’s replaced.', btn('Replace key', { kind: 'pri' }))}
</div>
<div class="rules3"><div>${tile('text-aa')}<p><b>Title says what happened</b>Plain words, and where: “Nothing was pasted into Telegram”, never a code alone.</p></div><div>${tile('info')}<p><b>One line says why</b>The cause, and that your words or audio are safe.</p></div><div>${tile('hand')}<p><b>One fix, first</b>The fix is the only tinted button. Raw text is pasted only when you ask.</p></div></div></div></div>`;

// ---------- Tray ----------
const mi = (label, { icon, keys, cls = '', after = '' } = {}) => `<a class="mi ${cls}" href="#">${icon ? ic(icon) : '<i class="i"></i>'}<span>${label}</span>${keys ? kc(keys) : ''}${after}</a>`;
const segc = (opts, on, xs = '') => `<span class="seg ${xs}" role="radiogroup">${opts.map(([v, l, i]) => `<button type="button" role="radio" aria-checked="${v === on}" class="${v === on ? 'on' : ''}" data-set-theme="${v}">${i ? ic(i, v === on ? 'fill' : 'regular', 'i sm') : ''}<span>${l}</span></button>`).join('')}</span>`;
const THEMES = [['system', 'System', 'monitor'], ['light', 'Light', 'sun'], ['dark', 'Dark', 'moon']];
const trayMenu = (problem) => `<div class="tray">
  <div class="tr-h">${logo(20, uid('tr'))}<b>VoiceFlow</b>${problem ? chip('warn', '1 problem') : chip('ok', 'Ready')}</div>
  ${problem ? `<div class="prob bad pmini"><span class="tile bad sm">${ic('warning', 'duotone')}</span><div class="pb"><b>Nothing was pasted into Telegram</b><p>Tidy-up timed out at 10:42. Words saved.</p></div></div>${mi('Retry tidy-up', { icon: 'arrow-clockwise', keys: ['Ctrl', 'Shift', 'R'] })}${mi('Paste raw text into Telegram', { icon: 'copy' })}<hr>` : `<p class="tr-ok">Press ${kc(['Ctrl', 'Space'])} in any app to dictate.</p><hr>`}
  <div class="tr-l">${app('kite', 18)}<div><span class="sub">Last pasted into Kite, <span class="num">10:58</span></span><p>${esc(cleaned.slice(0, 64))}…</p></div></div>
  ${mi('Copy last dictation', { icon: 'copy' })}<hr>
  ${mi('Style: <b>Casual</b>', { icon: 'magic-wand', cls: problem ? 'open' : '', after: ic('caret-right', 'regular', 'i sm sub') })}
  ${mi('Pause dictation', { icon: 'pause' })}
  ${mi('Check for new words', { icon: 'arrows-clockwise' })}
  <div class="mi-row">${ic('sun')}<span>Appearance</span>${segc(THEMES.map(([v, l]) => [v, l]), 'system', 'xs')}</div><hr>
  ${mi('Open VoiceFlow', { icon: 'arrow-square-out' })}
  ${mi('Quit VoiceFlow', { icon: 'power' })}
</div>`;
const styleSub = () => `<div class="tray sub-menu"><p class="m-h">Style for Kite</p>${[['Casual', 'Light tidy-up, your own words'], ['Work', 'Full sentences, no slang'], ['Family', 'Warm and short'], ['Raw', 'No tidy-up']].map(([l, d], i) => `<a class="mi ${i === 0 ? 'chk' : ''}" href="#">${i === 0 ? ic('check', 'regular', 'i acc') : '<i class="i"></i>'}<span>${l}<small>${d}</small></span></a>`).join('')}</div>`;
const trayFrame = () => desk(`<div class="tpair"><div><p class="tcap">With a problem: the problem comes first</p><div class="tstack">${styleSub()}${trayMenu(true)}</div></div><div><p class="tcap">All fine</p>${trayMenu(false)}</div></div>`, { taskbar: true });

// ---------- History ----------
const result = (r) => (r.fail === 'cleanup' ? `<span class="res bad">${ic('warning-circle', 'fill', 'i sm')}Not pasted</span>` : r.fail === 'held' ? `<span class="res warn">${ic('pause', 'fill', 'i sm')}Held</span>` : r.fail === 'transcribe' ? `<span class="res bad">${ic('x-circle', 'fill', 'i sm')}No text</span>` : `<span class="res ok">${ic('check-circle', 'fill', 'i sm')}Pasted</span>`);
const hrow = (r, cur, skip) => skip === r.t ? '' : `<tr class="${cur === r.t ? 'cur' : ''}"><td class="num sub">${r.t}</td><td>${appL(r.app, 18)}</td><td class="tx">${r.fail === 'transcribe' ? '<span class="sub">Audio saved on this PC</span>' : r.fail === 'held' ? '<span class="sub">Audio kept on this PC, not sent</span>' : r.fail === 'cleanup' ? `<span class="sub">${esc(r.text)}</span>` : esc(r.text)}</td><td class="wvc">${wave(10, r.seed, 14, 'wv thumb')}<span class="num sub">${r.dur}</span></td><td>${result(r)}</td></tr>`;
const historyTable = (cur, skip) => `<div class="card flush tblw"><table class="t ht"><colgroup><col style="width:66px"><col style="width:140px"><col><col style="width:100px"><col style="width:116px"></colgroup><thead><tr><th>Time</th><th>App</th><th>Text</th><th>Length</th><th>Result</th></tr></thead>
${H.map((g) => `<tbody><tr class="grp"><td colspan="5">${g.day}</td></tr>${g.items.map((r) => hrow(r, cur, skip)).join('')}</tbody>`).join('')}</table>
<p class="foot">Keys: ${kc(['J'])}${kc(['K'])}<span>move</span>${kc(['Space'])}<span>play</span>${kc(['C'])}<span>copy</span>${kc(['Del'])}<span>delete</span></p></div>`;
const toolbar = () => `<div class="toolbar"><label class="search">${ic('magnifying-glass')}<span>Search your dictations</span>${kc(['/'])}</label><button class="btn sec drop" type="button"><span class="stack">${app('telegram', 16)}${app('kite', 16)}${app('outlook', 16)}</span><span>All apps</span>${ic('caret-down', 'regular', 'i sm')}</button><button class="btn sec drop" type="button">${ic('clock-counter-clockwise')}<span>Any time</span>${ic('caret-down', 'regular', 'i sm')}</button><button class="tgl" type="button">${ic('warning-circle')}<span>Problems only</span><em>3</em></button><button class="tgl del" type="button">${ic('trash')}<span>Recently deleted</span><small>30 days</small></button></div>`;
const detailFail = () => `<aside class="card hd"><div class="hd-h">${app('telegram', 28)}<div><h2>Telegram</h2><span class="sub num">Today 10:42</span></div></div>
<div class="prob bad pmini"><span class="tile bad sm">${ic('warning', 'duotone')}</span><div class="pb"><b>Nothing was pasted</b><p>${term('Tidy-up', TIDY, true)} took longer than 10 seconds.</p></div></div>
<p class="lab">Your words, as heard</p><p class="rawq">tell the team the demo moved to thursday at two and i will bring the slides</p>
<div class="acts col">${btn('Retry tidy-up', { kind: 'pri', icon: 'arrow-clockwise', keys: ['R'] })}${btn('Paste raw text into Telegram', { kind: 'sec' })}</div>
<div class="rowacts">${btn('Play', { kind: 'text', icon: 'play' })}${btn('Copy', { kind: 'text', icon: 'copy' })}${btn('Fix a spelling', { kind: 'text', icon: 'pencil-simple' })}${btn('Delete', { kind: 'text', icon: 'trash', cls: 'del' })}</div></aside>`;
const detailOk = (fix = false, state = '') => `<aside class="card hd"><div class="hd-h">${app('telegram', 28)}<div><h2>Telegram</h2><span class="sub num">Yesterday 16:20</span></div>${chip('ok', 'Pasted', 'check-circle')}</div>
<p class="lab">Pasted text</p><p class="quote">Ask <span class="${fix ? 'mark' : ''}">Zefira</span> for the weekly reset when you get a chance.</p>
${wave(40, 8, 22, 'wv big')}
${state === 'saved' ? `<p class="saved">${ic('check-circle', 'fill', 'i sm')}<span><b>Spelling saved.</b> “Zephyra” is used from your next dictation; this one stays as it was.</span></p>` : ''}<div class="acts col">${state === 'copied' ? `<button class="btn pri done" type="button">${ic('check')}<span>Copied</span></button>` : btn('Copy text', { kind: 'pri', icon: 'copy', keys: ['C'] })}</div>
<div class="rowacts">${btn('Play', { kind: 'text', icon: 'play' })}${btn('Re-run tidy-up', { kind: 'text', icon: 'arrow-clockwise' })}${btn('Fix a spelling', { kind: 'text', icon: 'pencil-simple', cls: fix ? 'active' : '' })}${btn('Delete', { kind: 'text', icon: 'trash', cls: 'del' })}</div>
${fix ? `<div class="pop" role="dialog" aria-label="Fix a spelling"><div class="pop-h">${tile('pencil-simple')}<b>Teach VoiceFlow a spelling</b></div>
<label>It heard</label><div class="inp">Zefira</div><label>Always write it as</label><div class="inp focus">Zephyra<i class="caret"></i></div>
<label>Where</label>${sel('Every app', 'wide')}
<p class="note">${ic('info', 'regular', 'i sm')}Saved to your notes, so it works on your other devices too. Used from your next dictation.</p>
<div class="acts">${btn('Save spelling', { kind: 'pri' })}${btn('Cancel', { kind: 'text' })}</div></div>` : ''}</aside>`;
const historyMain = (mode = 'fail') => `${head('History', '1,642 dictations, kept on this PC')}
${toolbar()}<div class="hsplit">${historyTable(mode === 'fail' ? '10:42' : mode === 'undo' ? '' : '16:20', mode === 'undo' ? '16:20' : '')}${mode === 'fail' ? detailFail() : mode === 'fix' ? detailOk(true) : mode === 'copied' ? detailOk(false, 'copied') : mode === 'saved' ? detailOk(false, 'saved') : mode === 'undo' ? `<aside class="card hd ghost-panel">${illo('history', 96)}<p class="sub">Pick a dictation to see it here.</p></aside>` : detailOk()}</div>
${mode === 'undo' ? `<div class="toast" role="status">${ic('trash', 'duotone', 'i')}<span class="tmsg"><b>Dictation deleted.</b> Undo within 8 seconds, or find it in Recently deleted for 30 days.</span>${btn('Undo', { kind: 'pri', keys: ['Ctrl', 'Z'] })}<span class="tcount num">8 s</span><span class="tbar"><i></i></span></div>` : ''}`;

// ---------- Vocabulary ----------
const vocabMain = () => `${head('Vocabulary', 'Words VoiceFlow listens for, and how it spells them')}
<div class="managed">${tile('lock-simple')}<div><b>Managed in ${term('your notes', 'Your notes are kept by Atlas on your NAS. Your assistant reads and updates them.')}</b><span>To add or change a word, use <b>Fix a spelling</b> on any dictation, or ask your assistant, ${ASSIST}. Updated today 07:12.</span></div>${btn('Check for new words', { kind: 'text', icon: 'arrows-clockwise' })}</div>
<div class="tabs" role="tablist"><a class="on" href="#">${ic('text-aa', 'fill')}Words <span>214</span></a><a href="#">${ic('arrows-clockwise')}Corrections <span>4</span></a><a href="#">${ic('sliders-horizontal')}App rules <span>4</span></a><span class="sp"></span><label class="search sm">${ic('magnifying-glass', 'regular', 'i sm')}<span>Filter words</span></label></div>
<div class="vg"><div class="card flush"><table class="t vt"><thead><tr><th>Word</th><th>Often heard as</th><th>Used in</th></tr></thead><tbody>${[['Milo', 'Mylo, Meelo', 'Assistant', ['kite', 'telegram']], ['Febe', 'Phoebe, Fifi', 'Assistant', ['kite']], ['Pinewood', 'Pinewud, Pine Wud', 'Project', ['kite', 'outlook', 'claude']], ['Claude Code', 'Cloud Code', 'App', ['kite', 'telegram']], ['Kite', 'Kyte', 'App', ['telegram', 'kite']], ['Vercel', 'Versal', 'Service', ['kite', 'vscode', 'chrome']], ['Zephyra', 'Zefira', 'Assistant', ['telegram']], ['Codex', 'Codecs', 'App', ['kite', 'vscode']]].map(([w, h, k, a]) => `<tr><td><b>${w}</b></td><td class="sub">${h}</td><td><span class="used">${a.map((x) => appL(x, 16)).join('')}</span></td></tr>`).join('')}</tbody></table><p class="foot">206 more words. Type in the filter to find one.</p></div>
<div class="col"><section class="card"><div class="c-h">${tile('arrows-clockwise')}<h2>Corrections</h2><span class="cnt">4</span></div><p class="note">Always rewritten, in the apps shown.</p><ul class="corr">${[['Pinewud', 'Pinewood'], ['Cloud Code', 'Claude Code'], ['Mylo', 'Milo'], ['Chachapiti', 'ChatGPT']].map(([a, b]) => `<li><s>${a}</s>${ic('arrow-right', 'regular', 'i sm sub')}<b>${b}</b></li>`).join('')}</ul></section>
<section class="card"><div class="c-h">${tile('sliders-horizontal')}<h2>App rules</h2><span class="cnt">4</span></div><ul class="rules">${[['kite', '“Phoebe” here means Febe'], ['telegram', 'People’s names first'], ['outlook', 'Work acronyms first'], ['chrome', 'Basic words only in browsers']].map(([a, r]) => `<li>${app(a, 18)}<span>${r}</span></li>`).join('')}</ul></section></div></div>`;

// ---------- Suggestions ----------
const suggestMain = () => `${head('Ideas', 'Words VoiceFlow keeps hearing but doesn’t know yet', `<span class="prog num">1 of 2</span>${btn('Skip for now', { kind: 'text', icon: 'skip-forward' })}`)}
<div class="sg"><section class="card big">
  <div class="sq-h">${tile('lightbulb', 'ind', 'lg')}<div><h2 class="sq-q">Always write “Vercel” instead of “Versal”?</h2><p class="sub">VoiceFlow heard “Versal” in 3 dictations this week. Here is what would change:</p></div></div>
  <ul class="ba">${[['kite', 'Kite, today 10:58', '…check whether the preview deployed on ', ' if it did…'], ['vscode', 'VS Code, today 09:30', '…the ', ' build is green, ship it…'], ['kite', 'Kite, Nov 14', '…check ', ' logs for the error…']].map(([a, src, pre, post]) => `<li>${app(a, 20)}<p>${pre}<s>Versal</s> <ins>Vercel</ins>${post}</p><span class="sub">${src}</span>${btn('Play', { kind: 'text', icon: 'play', cls: 'icn' })}</li>`).join('')}</ul>
  <div class="acts answers">${btn('Always write “Vercel”', { kind: 'pri', icon: 'check', keys: ['A'] })}${btn('Add “Vercel” as a word, no auto-fix', { kind: 'sec', icon: 'plus' })}${btn('No, “Versal” is right', { kind: 'text', icon: 'x' })}</div>
  <p class="scope">${ic('clock-counter-clockwise', 'regular', 'i sm')}Changes new dictations only. Past ones stay as they were.</p>
  <p class="note">${ic('info', 'regular', 'i sm')}Your answer is saved to your notes, so your assistant, ${ASSIST}, stops asking about it too. You can undo it below.</p>
</section>
<div class="col"><section class="card"><div class="c-h">${tile('lightbulb', 'ind')}<h2>Waiting next</h2></div><div class="nx">${app('telegram', 20)}<div><b>“Zefira” or “Zephyra”?</b><span>Heard 2 times in Telegram</span></div></div></section>
<section class="card"><div class="c-h">${tile('clock-counter-clockwise')}<h2>Answered recently</h2></div><ul class="ans">${[['Mylo', 'Milo', 'Always written, Nov 12'], ['Juno', '', 'Kept as heard, Nov 9'], ['Tailscale', '', 'Added as a word, Nov 3']].map(([a, b, d]) => `<li><div><b>${b ? `<s>${a}</s> ${b}` : a}</b><span>${d}</span></div>${btn('Undo', { kind: 'text', icon: 'arrow-counter-clockwise' })}</li>`).join('')}</ul></section></div></div>`;

// ---------- Styles ----------
const STY = [
  { cat: 'Email', apps: ['outlook'], style: 'Work', icon: 'envelope-simple', said: 'running ten minutes late save me a seat the pinewud demo went well', out: 'I’m running about ten minutes late; please save me a seat. The Pinewood demo went well.' },
  { cat: 'Chat', apps: ['telegram', 'kite'], style: 'Casual', icon: 'chat-circle', said: 'running ten minutes late save me a seat the pinewud demo went well', out: 'Running ten minutes late, save me a seat! The Pinewood demo went well.' },
  { cat: 'Code', apps: ['vscode', 'claude'], style: 'Raw', icon: 'terminal-window', said: 'run the pinewud tests again and show me only the failures', out: 'run the Pinewood tests again and show me only the failures' },
  { cat: 'Everything else', apps: ['chrome', 'unknown'], style: 'Casual', icon: 'cursor-text', said: 'vercel preview deployment limits', out: 'Vercel preview deployment limits' },
];
const stylesMain = (open = true) => `${head('Styles', 'How VoiceFlow tidies your words for each kind of app')}
<div class="stg">${STY.map((s, i) => `<section class="card sty ${i === 0 ? 'focus' : ''}"><div class="c-h">${tile(s.icon)}<div class="sty-t"><h2>${s.cat}</h2><span class="stack lab">${s.apps.map((a) => appL(a, 16)).join('')}</span></div>${sel(s.style, i === 0 && open ? 'open' : '')}</div>
<p class="said"><span class="lab">You say</span>“${esc(s.said)}”</p><p class="out"><span class="lab">${s.style} writes</span>${esc(s.out)}</p></section>`).join('')}</div>
${open ? '' : '<!--'}<div class="menu" role="listbox" aria-label="Style for Email"><p class="m-h">Style for Email</p>${[['Casual', 'Light tidy-up, your own words'], ['Work', 'Full sentences, no slang'], ['Family', 'Warm and short'], ['Partner', 'Your voice, a little softer'], ['Raw', 'No tidy-up; corrections still apply']].map(([l, d]) => `<div class="m-i ${l === 'Work' ? 'on' : ''}"><span class="ck">${l === 'Work' ? ic('check') : ''}</span><div><b>${l}</b><span>${d}</span></div></div>`).join('')}<p class="m-f">${ic('info', 'regular', 'i sm')}Applies to new dictations only</p></div>${open ? '' : '-->'}
<p class="note ctr">${ic('lock-simple', 'regular', 'i sm')}What each style does comes from your notes. Which style each kind of app uses is yours, on this PC.</p>`;

// ---------- Settings ----------
const srow = (label, help, control) => `<div class="srow"><div><b>${label}</b>${help ? `<span>${help}</span>` : ''}</div>${control}</div>`;
const settingsMain = () => `${head('Settings', 'These apply to this PC and save as you change them')}
<div class="setg"><div class="col">
<section class="card sp"><div class="c-h">${tile('keyboard')}<h2>Dictation keys</h2></div>
${srow('Start or stop dictation', 'Press once to start, again to paste', `${kc(['Ctrl', 'Space'])}${btn('Change', { kind: 'sec' })}`)}
${srow('Hold to talk', 'Hold while you talk, let go to paste', `${kc(['Alt', 'Shift', 'D'])}${btn('Change', { kind: 'sec' })}`)}
${srow('Pick a style for one dictation', '', `${kc(['Ctrl', 'Shift', 'X'])}${btn('Change', { kind: 'sec' })}`)}</section>
<section class="card sp"><div class="c-h">${tile('microphone')}<h2>Microphone</h2></div>
${srow('Input', 'The bars move when VoiceFlow hears you', `${sel('USB microphone', '')}${wave(14, 4, 14, 'wv meter')}`)}</section>
<section class="card sp"><div class="c-h">${tile('sun')}<h2>Appearance</h2></div>
${srow('Theme', 'System follows Windows', segc(THEMES, 'system'))}
${srow('Density', 'Compact fits more and shows every shortcut', `<span class="seg"><button class="on" type="button"><span>Comfortable</span></button><button type="button"><span>Compact</span></button></span>`)}</section>
<p class="about">VoiceFlow 1.0.0, up to date. Your notes are kept by Atlas. Based on Whispering by Braden Wong, AGPL-3.0-or-later. Icons: Phosphor (MIT). <a class="lk" href="#">Licences</a> <a class="lk" href="#">Source code</a></p>
</div><div class="col">
<section class="card sp"><div class="c-h">${tile('shield-check')}<h2>Privacy</h2></div>
<label class="opt on"><span class="rb"></span><div><b>Strict</b><span>If VoiceFlow can’t tell whether a box is for a password, it keeps your words and asks.</span></div></label>
<label class="opt"><span class="rb"></span><div><b>Best effort</b><span>Stops only when it’s sure the box is for a password.</span></div></label></section>
<section class="card sp"><div class="c-h">${tile('clock-counter-clockwise')}<h2>History and audio</h2></div>
${srow('Keep text', '', sel('Forever'))}${srow('Keep audio', 'Lets you replay and retry', sel('30 days'))}</section>
<section class="card sp"><div class="c-h">${tile('user-circle')}<h2>Accounts</h2></div>
${srow('OpenAI key', 'In Windows Credential Manager', `${chip('ok', 'Working', 'check-circle')}${btn('Replace', { kind: 'text' })}`)}
${srow('Your notes', 'Your words and styles, account vf-me', `${chip('ok', 'Updated 07:12', 'check-circle')}${btn('Sign out', { kind: 'text' })}`)}
${srow('Your assistant’s name', 'Used when VoiceFlow mentions your assistant', `<span class="inp sm">${ASSIST}</span>${btn('Change', { kind: 'text' })}`)}</section>
<button class="adv" type="button">${ic('sliders-horizontal')}<span><b>Advanced</b><small>Tidy-up model, paste method, logs, delete history</small></span>${ic('caret-right', 'regular', 'i sm')}</button>
</div></div>`;

// ---------- First run: one real test dictation ----------
const frDots = (i) => `<div class="stepi"><span class="stepl">Step ${i + 1} of 3</span><span class="dots" aria-hidden="true">${[0, 1, 2].map((k) => `<i class="${k === i ? 'on' : k < i ? 'done' : ''}"></i>`).join('')}</span></div>`;
const fr = (i, art, body, foot) => `<div class="win">${titlebar('Set up VoiceFlow')}<div class="fr"><div class="fr-in">${frDots(i)}${illo(art, 120)}${body}</div><div class="fr-foot">${foot}</div></div></div>`;
const fr1 = () => fr(0, 'mic', `<h1>Talk in any app. VoiceFlow types it.</h1><p class="lead">One quick test and you’re done. It takes about a minute.</p>
<div class="frbox">${srow('Microphone', 'Say anything to test your microphone. The bars react when it hears you.', sel('USB microphone', ''))}<div class="meter">${wave(46, 3, 26, 'wv meter big')}</div><p class="okline">${ic('check-circle', 'fill', 'i sm')}Sounds good</p></div>`,
`${btn('Skip for now', { kind: 'text' })}${btn('Start the test', { kind: 'pri', keys: ['Enter'] })}`);
const fr2 = () => fr(1, 'keys', `<h1>Say one sentence</h1><p class="lead">Press ${kc(['Ctrl', 'Space'])}, read the sentence in the box aloud, then press ${kc(['Ctrl', 'Space'])} again.</p>
<div class="tbox guide"><span class="g-l">Read this aloud</span><span class="g-s">“Hello VoiceFlow, this is my first dictation.”</span><span class="g-c"><i class="caret"></i><span class="ph">Your words will appear here</span></span></div>
<div class="frpill">${PILLS.listenStop}</div>`, `${btn('Back', { kind: 'text' })}${btn('Skip for now', { kind: 'text' })}`);
const fr3 = () => fr(2, 'done', `<h1>That’s it. You’re ready.</h1><p class="lead">Your first dictation: <b class="num">8 words in 2.1 seconds</b>.</p>
<div class="tbox done"><span>Hello VoiceFlow, this is my first dictation.</span></div>
<div class="next">${tile('waveform')}<div><b>From now on</b><span>VoiceFlow waits in the tray by the clock. Press ${kc(['Ctrl', 'Space'])} in any app. Problems show on the pill and the tray icon.</span></div></div>
<div class="next">${tile('hand')}<div><b>Tip: hold to talk</b><span>You can also hold ${kc(['Alt', 'Shift', 'D'])} while you talk, and let go to paste.</span></div></div>
<div class="next">${tile('book-open-text')}<div><b>Optional: connect your vocabulary</b><span>So the names you use, like Pinewood, are spelled right. You can do it later in Settings.</span></div>${btn('Connect', { kind: 'sec' })}</div>`,
`<span></span>${btn('Start using VoiceFlow', { kind: 'pri', keys: ['Enter'] })}`);

// ---------- empty states ----------
const mini = (title, body) => `<div class="mini"><div class="mini-h">${title}</div><div class="mini-b">${body}</div></div>`;
const es = (art, t, p, a = '') => `<div class="es">${illo(art, 104)}<b>${t}</b><p>${p}</p>${a}</div>`;
const skel = () => `<div class="skel">${[0, 1, 2, 3].map((i) => `<div class="sk-row"><i class="sk c"></i><i class="sk a"></i><i class="sk b" style="width:${[62, 48, 70, 55][i]}%"></i><i class="sk w"></i></div>`).join('')}<p class="sub">Loading 1,642 dictations…</p></div>`;
const emptiesFrame = () => `<div class="desk plain"><div class="egrid">
${mini('History, before the first dictation', es('history', 'No dictations yet', `Press ${kc(['Ctrl', 'Space'])} in any app and talk. Each dictation appears here with the app it went to.`, btn('Try a test dictation', { kind: 'pri', icon: 'microphone' })))}
${mini('History, a search with no results', es('search', 'Nothing matches “invoice” in Telegram', 'Try fewer words, or search every app.', btn('Search every app', { kind: 'sec' })))}
${mini('Ideas, nothing waiting', es('bulb', 'You’re all caught up', 'When VoiceFlow keeps hearing a word it doesn’t know, it asks here, and your assistant asks too.'))}
${mini('Vocabulary, not connected', es('book', 'Vocabulary isn’t connected', 'Dictation works, but the names you use, like Pinewood, won’t be spelled right.', btn('Connect vocabulary', { kind: 'pri' })))}
${mini('History, loading', skel())}
${mini('Microphone blocked', es('micoff', 'VoiceFlow can’t hear you', 'Windows privacy settings are blocking the microphone for VoiceFlow.', btn('Open microphone settings', { kind: 'pri', icon: 'arrow-square-out' })))}
</div></div>`;


// ---------- reference sheet: the visual kit ----------
const kitFrame = () => `<div class="desk plain"><div class="kit">
<section class="card"><div class="c-h">${tile("sparkle")}<h2>One icon family: Phosphor, three weights</h2></div>
<div class="kgrid">${[["house","Home"],["clock-counter-clockwise","History"],["book-open-text","Vocabulary"],["lightbulb","Ideas"],["magic-wand","Styles"],["gear-six","Settings"]].map(([n,l]) => `<div class="kic"><span>${ic(n)}${ic(n,"fill","i acc")}${ic(n,"duotone","i acc")}</span><small>${l}</small></div>`).join("")}</div>
<p class="note">${ic("info","regular","i sm")}Regular for rows and navigation, fill for the active item, duotone inside tiles. 20 px, colour from the text.</p></section>
<section class="card"><div class="c-h">${tile("waveform")}<h2>Tiles carry small doses of colour</h2></div>
<div class="ktiles">${[["waveform","acc","Feature"],["warning","bad","Problem"],["question","warn","Needs a look"],["lightbulb","ind","Idea"],["check-circle","ok","Done"]].map(([n,t,l]) => `<div>${tile(n,t,"lg")}<small>${l}</small></div>`).join("")}</div>
<div class="kbtns">${btn("Retry tidy-up",{kind:"pri",icon:"arrow-clockwise"})}${btn("Paste raw text",{kind:"sec"})}${btn("Dismiss",{kind:"text"})}<small>Main action is tonal; secondary white; tertiary text only</small></div></section>
<section class="card"><div class="c-h">${tile("monitor")}<h2>The real app on every dictation</h2></div>
<div class="kapps">${["kite","telegram","chrome","vscode","outlook","claude","unknown"].map((a) => `<div>${app(a,32)}<small>${APPS[a].name}</small></div>`).join("")}</div>
<p class="note">${ic("info","regular","i sm")}Simple stand-ins drawn for the mockup; the app shows each program's own icon.</p></section>
<section class="card"><div class="c-h">${tile("waveform")}<h2>The waveform is the signature</h2></div>
<div class="kwave"><div>${wave(28,2,22,"wv live")}<small>Live, in the pill</small></div><div>${wave(10,4,14,"wv thumb")}<small>History thumbnail</small></div><div>${spark([380,420,510,300,460,690,652])}<small>7-day stat bars</small></div><div>${wave(28,2,2,"wv still")}<small>No sound</small></div></div></section>
<section class="card wide"><div class="c-h">${tile("pencil-simple")}<h2>Spot illustrations, drawn from the same motifs</h2></div>
<div class="killo">${[["mic","Welcome"],["keys","Try it"],["done","Done"],["history","No dictations"],["search","No results"],["bulb","Caught up"],["book","Not connected"],["micoff","Mic blocked"]].map(([k,l]) => `<div>${illo(k,96)}<small>${l}</small></div>`).join("")}</div></section>
</div></div>`;

const FRAMES = [
  ['home', 'Home', 1280, 800, () => shell('home', homeMain())],
  ['home-narrow', 'Home, narrow window', 420, 720, homeNarrow],
  ['drawer-narrow', 'Menu open, narrow window', 420, 720, drawer],
  ['home-empty', 'Home, before the first dictation', 1280, 800, () => shell('home', homeEmptyMain(), { problem: false, fresh: true })],
  ['pill', 'Recording pill, every state', 1280, 800, pillFrame],
  ['pill-noaudio', 'Pill: no sound (alone, for testing)', 1280, 800, pillNoAudio],
  ['pill-motion', 'How the pill moves', 1280, 800, motionFrame],
  ['problems', 'Problems explain themselves', 1280, 800, problemsFrame],
  ['tray', 'Tray menu', 1280, 800, trayFrame],
  ['history', 'History, a problem selected', 1280, 800, () => shell('history', historyMain('fail'))],
  ['history-ok', 'History, a dictation selected', 1280, 800, () => shell('history', historyMain('ok'))],
  ['history-fix', 'History, Fix a spelling', 1280, 800, () => shell('history', historyMain('fix'))],
  ['history-undo', 'History, deleted with Undo', 1280, 800, () => shell('history', historyMain('undo'))],
  ['history-copied', 'History, after Copy', 1280, 800, () => shell('history', historyMain('copied'))],
  ['history-saved', 'History, spelling saved', 1280, 800, () => shell('history', historyMain('saved'))],
  ['vocab', 'Vocabulary', 1280, 800, () => shell('vocab', vocabMain())],
  ['suggest', 'Suggestions', 1280, 800, () => shell('suggest', suggestMain())],
  ['styles', 'Styles, a style menu open', 1280, 800, () => shell('styles', stylesMain())],
  ['styles-closed', 'Styles', 1280, 800, () => shell('styles', stylesMain(false))],
  ['settings', 'Settings', 1280, 800, () => shell('settings', settingsMain())],
  ['firstrun-1', 'First run 1: welcome and microphone', 1280, 800, fr1],
  ['firstrun-2', 'First run 2: the test dictation', 1280, 800, fr2],
  ['firstrun-3', 'First run 3: done', 1280, 800, fr3],
  ['empties', 'Empty, loading and blocked states', 1280, 800, emptiesFrame],
  ['kit', 'Icons, app icons and illustrations', 1280, 800, kitFrame],
];

const css = fs.readFileSync(path.join(here, 'b31.css'), 'utf8');
export function render() {
  const idx = FRAMES.map(([id, l]) => `<a href="#${id}">${esc(l)}</a>`).join('');
  const body = FRAMES.map(([id, l, w, h, f]) => `<section class="sheet" id="${id}"><h2 class="sheet-t">${esc(l)}<span>${w} × ${h}</span></h2><div class="frame" data-shot="${id}" style="width:${w}px;height:${h}px"><div class="B">${f()}</div></div></section>`).join('\n');
  return `<!doctype html>
<html lang="en" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex, nofollow, noarchive">
<title>VoiceFlow B3.1 mockup</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Inter:opsz,wght@14..32,400..700&display=swap" rel="stylesheet">
<style>${css}</style></head>
<body class="sheetbody">
<div class="sheet-bar"><b>VoiceFlow B3.1</b><span>Theme</span><span class="tt" role="group" aria-label="Theme"><button type="button" data-theme-btn="light" aria-pressed="true">Light</button><button type="button" data-theme-btn="dark" aria-pressed="false">Dark</button></span></div>
<header class="sheet-top"><h1>VoiceFlow Desktop B3.1</h1><p>B2’s calm structure, warmed up: warm greys, a tonal teal for the one main action, Phosphor icons (MIT) with tinted tiles, the real app icon on every dictation, the waveform as the signature, soft depth, a weekly stat strip, and spot illustrations. Static mockup with demo content.</p><nav class="sheet-idx" aria-label="Screens">${idx}</nav></header>
${body}
<script>
(function(){var root=document.documentElement;function set(t){if(t==='system'){t=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light'}root.setAttribute('data-theme',t);document.querySelectorAll('[data-theme-btn]').forEach(function(b){b.setAttribute('aria-pressed',String(b.getAttribute('data-theme-btn')===t))})}
var q=new URLSearchParams(location.search).get('theme');if(q)set(q);
document.addEventListener('click',function(e){var b=e.target.closest('[data-theme-btn],[data-set-theme]');if(!b)return;set(b.getAttribute('data-theme-btn')||b.getAttribute('data-set-theme'))});})();
</script>
</body></html>`;
}
