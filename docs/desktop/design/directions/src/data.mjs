// Demo content shared by the three directions. Invented sentences only: no real transcript, no real person.
export const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);

export const hotkeys = [
  { id: 'toggle', name: 'Start or stop dictation', keys: ['Ctrl', 'Space'] },
  { id: 'ptt', name: 'Push to talk (hold)', keys: ['Alt', 'Shift', 'D'] },
  { id: 'cancel', name: 'Cancel this dictation', keys: ['Esc'] , note: 'while listening' },
  { id: 'rerun', name: 'Re-run clean-up on the last one', keys: ['Ctrl', 'Shift', 'R'] },
  { id: 'picker', name: 'Pick a style', keys: ['Ctrl', 'Shift', 'X'] },
];

export const nav = [
  { id: 'home', label: 'Home', icon: 'home', key: '1' },
  { id: 'history', label: 'History', icon: 'history', key: '2' },
  { id: 'vocab', label: 'Vocabulary', icon: 'book', key: '3' },
  { id: 'suggest', label: 'Suggestions', icon: 'spark', key: '4', badge: 2 },
  { id: 'styles', label: 'Styles', icon: 'styles', key: '5' },
  { id: 'settings', label: 'Settings', icon: 'gear', key: '6' },
];

// The last dictation, as segments: plain text, or a change {from, to, why}.
export const last = {
  time: '10:58',
  app: 'Kite',
  style: 'Casual',
  latency: { transcribe: '1.4 s', cleanup: '0.9 s', total: '2.6 s' },
  pack: '2026-11-16.2',
  segs: [
    'Hey ', { from: 'phoebe', to: 'Febe', why: 'Kite app rule' },
    ', can you check whether the ', { from: 'pinewud', to: 'Pinewood', why: 'Correction' },
    ' preview deployed on ', { from: 'versal', to: 'Vercel', why: 'Vocabulary hint' },
    '? If it did, send ', { from: 'mylo', to: 'Milo', why: 'Correction' },
    ' the link and I’ll review it after lunch.',
  ],
  raw: 'hey phoebe can you check whether the pinewud preview deployed on versal if it did send mylo the link and ill review it after lunch',
};
export const cleaned = last.segs.map((s) => (typeof s === 'string' ? s : s.to)).join('');
export const changes = last.segs.filter((s) => typeof s !== 'string');

export const failure = {
  title: 'Clean-up failed — nothing pasted',
  app: 'Telegram', time: '10:42',
  detail: 'The clean-up model did not answer within 10 seconds. Your words are safe in History.',
  raw: 'tell the team the demo moved to thursday at two and i will bring the slides',
};

export const suggestions = [
  { heard: 'Versal', count: 3, guess: 'Vercel', apps: 'Kite, Claude Code', last: 'today 10:58', telegram: true,
    clips: ['“…deployed on versal if it did…”', '“…the versal build is green…”', '“…check versal logs for…”'] },
  { heard: 'Zefira', count: 2, guess: 'Zephyra', apps: 'Telegram', last: 'yesterday 21:14', telegram: false,
    clips: ['“…ask zefira for the weekly reset…”', '“…zefira said the plan…”'] },
];

export const history = [
  { day: 'Today', items: [
    { t: '10:58', app: 'Kite', style: 'Casual', text: cleaned, dur: '0:09' },
    { t: '10:42', app: 'Telegram', style: 'Casual', text: 'tell the team the demo moved to thursday at two and i will bring the slides', dur: '0:06', fail: 'cleanup' },
    { t: '10:31', app: 'Claude Code', style: 'Raw', text: 'Run the Pinewood tests again and show me only the failures.', dur: '0:05' },
    { t: '09:47', app: 'Outlook', style: 'Work', text: 'Thanks for the update. I will review the OKR numbers before Friday and come back with questions.', dur: '0:11' },
    { t: '09:12', app: 'Unknown app', style: 'Casual', text: 'Audio kept on this PC. Not sent: the target app could not be identified.', dur: '0:04', fail: 'held' },
    { t: '08:55', app: 'Kite', style: 'Casual', text: 'Milo, add a reminder to renew the Vercel domain next Tuesday.', dur: '0:05' },
  ] },
  { day: 'Yesterday', items: [
    { t: '21:14', app: 'Telegram', style: 'Partner', text: 'On my way home now, want me to pick up anything?', dur: '0:03' },
    { t: '18:02', app: 'Kite', style: 'Casual', text: '', dur: '0:07', fail: 'transcribe' },
    { t: '16:40', app: 'Teams', style: 'Work', text: 'I can take the QBR review if nobody else has it.', dur: '0:04' },
    { t: '14:09', app: 'Claude Code', style: 'Raw', text: 'Open the pack schema and add a gloss field to terms.', dur: '0:06' },
  ] },
];

export const terms = [
  { text: 'Milo', kind: 'Bot', heard: ['Mylo', 'Meelo'], apps: 'All apps' },
  { text: 'Febe', kind: 'Bot', heard: ['Phoebe', 'Fifi'], apps: 'Kite' },
  { text: 'Pinewood', kind: 'Project', heard: ['Pinewud', 'Pine Wud'], apps: 'All apps' },
  { text: 'Claude Code', kind: 'Product', heard: ['Cloud Code'], apps: 'All apps' },
  { text: 'Kite', kind: 'Product', heard: ['Kyte'], apps: 'All apps' },
  { text: 'Vercel', kind: 'Product', heard: ['Versal'], apps: 'All apps' },
  { text: 'Zephyra', kind: 'Bot', heard: ['Zefira'], apps: 'Telegram' },
  { text: 'Codex', kind: 'Product', heard: ['Codecs'], apps: 'Kite' },
  { text: 'QBR', kind: 'Acronym', heard: ['Q B R', 'cue bar'], apps: 'Outlook, Teams' },
  { text: 'OKR', kind: 'Acronym', heard: ['O K R'], apps: 'Outlook, Teams' },
];

export const corrections = [
  { from: 'Pinewud, Pinewud', to: 'Pinewood', scope: 'All apps' },
  { from: 'Cloud Code', to: 'Claude Code', scope: 'Kite, Telegram' },
  { from: 'Mylo', to: 'Milo', scope: 'All apps' },
  { from: 'Chachapiti, ChatGypty', to: 'ChatGPT', scope: 'All apps' },
];

export const appRules = [
  { app: 'Kite', rule: '“Phoebe” here usually means Febe', extra: 'Terms first: Febe, Milo, Zephyra, Claude Code, Codex', style: 'Casual' },
  { app: 'Telegram', rule: 'People first; Phoebe may be a real person', extra: 'Terms first: names from your people notes', style: 'Casual' },
  { app: 'Outlook, Teams', rule: 'Work acronyms first', extra: 'Terms first: QBR, OKR', style: 'Work' },
  { app: 'Browsers', rule: 'Base terms only', extra: 'Titles that look like banking or sign-in pages send no context', style: 'Casual' },
];

export const pack = { version: '2026-11-16.2', synced: 'Today 07:12', terms: 214, corrections: 4, rules: 4 };

export const styles = [
  { id: 'casual', label: 'Casual', sub: 'Friends', note: 'Light tidy-up. Keeps your words, fixes punctuation.',
    sample: 'Running ten minutes late, save me a seat. The Pinewood demo went well!' },
  { id: 'business', label: 'Work', sub: '', note: 'Full sentences, no slang, short paragraphs.',
    sample: 'I am running about ten minutes late; please save me a seat. The Pinewood demo went well.' },
  { id: 'family', label: 'Family', sub: '', note: 'Warm and plain. Keeps it short.',
    sample: 'Running 10 minutes late, save me a seat! The Pinewood demo went really well.' },
  { id: 'partner', label: 'Partner', sub: '', note: 'Your own voice, a little softer.',
    sample: 'Running ten minutes late, love, save me a seat? The Pinewood demo went well.' },
  { id: 'raw', label: 'Raw', sub: '', note: 'No clean-up. Corrections still apply.',
    sample: 'running ten minutes late save me a seat the pinewood demo went well' },
];
export const styleInput = 'running ten minutes late save me a seat the pinewud demo went well';

export const perApp = [
  { app: 'Kite', style: 'Casual', seen: 'Today' },
  { app: 'Telegram', style: 'Casual', seen: 'Today' },
  { app: 'Claude Code', style: 'Raw', seen: 'Today' },
  { app: 'Outlook', style: 'Work', seen: 'Today' },
  { app: 'Teams', style: 'Work', seen: 'Yesterday' },
  { app: 'Any other app', style: 'Casual', seen: 'Default' },
];

export const blocked = ['1Password', 'Bitwarden', 'Microsoft Authenticator', 'Chase'];

// Line icons, 24 px grid, stroke-only. Each direction picks its own stroke width.
const P = {
  home: '<path d="M4 11.5 12 5l8 6.5"/><path d="M6.5 10v9h11v-9"/>',
  history: '<path d="M4.5 12a7.5 7.5 0 1 0 2.2-5.3"/><path d="M4.5 4.5v3.2h3.2"/><path d="M12 8.5V12l2.5 1.6"/>',
  book: '<path d="M5 5.5A2 2 0 0 1 7 4h12v14H7a2 2 0 0 0-2 2z"/><path d="M5 20V5.5"/><path d="M9 8h6"/>',
  spark: '<path d="M12 3.5v4M12 16.5v4M3.5 12h4M16.5 12h4"/><path d="M12 9.2 13 11l1.8 1-1.8 1-1 1.8-1-1.8-1.8-1 1.8-1z"/>',
  styles: '<path d="M5 19 15.5 8.5"/><path d="m14 7 3 3"/><path d="M17.5 3.5v2.5M16.3 4.8h2.5M6 4.5v2M5 5.5h2M19 15v2M18 16h2"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 3.5v2.2M12 18.3v2.2M3.5 12h2.2M18.3 12h2.2M6 6l1.6 1.6M16.4 16.4 18 18M6 18l1.6-1.6M16.4 7.6 18 6"/>',
  copy: '<rect x="8" y="8" width="11" height="11" rx="2"/><path d="M5 15.5V6.5A1.5 1.5 0 0 1 6.5 5h9"/>',
  play: '<path d="M8 5.5v13l10-6.5z"/>',
  retry: '<path d="M19 12a7 7 0 1 1-2.1-5"/><path d="M19 4.5V9h-4.5"/>',
  check: '<path d="m5 12.5 4.2 4.2L19 7"/>',
  alert: '<path d="M12 4 3.5 19h17z"/><path d="M12 10v4M12 16.8v.2"/>',
  pause: '<path d="M9 5.5v13M15 5.5v13"/>',
  search: '<circle cx="11" cy="11" r="6"/><path d="m15.5 15.5 4 4"/>',
  chev: '<path d="m9 6 6 6-6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  lock: '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
  sync: '<path d="M19.5 11A7.5 7.5 0 0 0 6 7.5"/><path d="M4.5 13A7.5 7.5 0 0 0 18 16.5"/><path d="M6 3.5v4h4M18 20.5v-4h-4"/>',
  kbd: '<rect x="3" y="6.5" width="18" height="11" rx="2"/><path d="M7 10h.01M10 10h.01M13 10h.01M16 10h.01M8 14h8"/>',
  menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
  mic: '<rect x="9" y="3.5" width="6" height="11" rx="3"/><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v2.5"/>',
  out: '<path d="M14 5h5v5"/><path d="M19 5l-8 8"/><path d="M17 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 4 18.5v-10A1.5 1.5 0 0 1 5.5 7H10"/>',
  telegram: '<path d="M20 5 3.5 11.4l5.3 1.9 2 6 3-3.6 4.6 3.3z"/><path d="m8.8 13.3 7.7-5.3"/>',
  dot: '<circle cx="12" cy="12" r="4"/>',
  shield: '<path d="M12 3.5 5 6v5.5c0 4.2 3 7.4 7 9 4-1.6 7-4.8 7-9V6z"/>',
  wave: '<path d="M3 12c2-5 4.5-5 6 0s4 5 6 0 3.5-4 6-2"/>',
};
export const icon = (name, cls = 'i') => `<svg class="${cls}" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">${P[name] || ''}</svg>`;

// The VoiceFlow mark (the Android icon, redrawn small). `id` keeps gradient ids unique per use.
export const logo = (size = 32, id = 'g') => `<svg width="${size}" height="${size}" viewBox="0 0 1024 1024" aria-hidden="true"><defs><linearGradient id="${id}bg" x1="112" y1="96" x2="912" y2="928" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#07111F"/><stop offset=".42" stop-color="#0F766E"/><stop offset=".72" stop-color="#2DD4BF"/><stop offset="1" stop-color="#5B5CE2"/></linearGradient><radialGradient id="${id}gb" cx="78%" cy="78%" r="54%"><stop offset="0" stop-color="#8B5CF6" stop-opacity=".46"/><stop offset="1" stop-color="#8B5CF6" stop-opacity="0"/></radialGradient></defs><rect x="40" y="40" width="944" height="944" rx="228" fill="url(#${id}bg)"/><rect x="40" y="40" width="944" height="944" rx="228" fill="url(#${id}gb)"/><path d="M260 444C318 314 422 314 470 444C517 574 614 574 764 404" fill="none" stroke="#fff" stroke-width="72" stroke-linecap="round"/><path d="M260 444C318 314 422 314 470 444C517 574 614 574 764 404" fill="none" stroke="#BFF7EF" stroke-width="30" stroke-linecap="round"/><rect x="214" y="610" width="596" height="190" rx="56" fill="#fff" opacity=".96"/><rect x="276" y="666" width="72" height="48" rx="18" fill="#0B1727" opacity=".26"/><rect x="382" y="666" width="72" height="48" rx="18" fill="#0B1727" opacity=".26"/><rect x="488" y="666" width="72" height="48" rx="18" fill="#0B1727" opacity=".26"/><rect x="594" y="666" width="72" height="48" rx="18" fill="#0B1727" opacity=".26"/><rect x="700" y="666" width="48" height="48" rx="18" fill="#0B1727" opacity=".26"/><rect x="332" y="736" width="360" height="34" rx="17" fill="#0B1727" opacity=".22"/></svg>`;

// The screens every direction shows, with their capture size.
export const frames = [
  { id: 'pill', label: 'Recording pill, every state', w: 1280, h: 800 },
  { id: 'tray', label: 'Tray menu', w: 1280, h: 800 },
  { id: 'home', label: 'Home', w: 1280, h: 800 },
  { id: 'home-narrow', label: 'Home, narrow window', w: 420, h: 720 },
  { id: 'menu-narrow', label: 'Menu open, narrow window', w: 420, h: 720 },
  { id: 'home-alt', label: 'Home, other mode', w: 1280, h: 800 },
  { id: 'history', label: 'History', w: 1280, h: 800 },
  { id: 'vocab', label: 'Vocabulary', w: 1280, h: 800 },
  { id: 'suggest', label: 'Suggestions', w: 1280, h: 800 },
  { id: 'styles', label: 'Styles', w: 1280, h: 800 },
  { id: 'settings', label: 'Settings', w: 1280, h: 800 },
  { id: 'firstrun', label: 'First run', w: 1280, h: 800 },
];

// A page that stacks the frames on a neutral canvas with an index of links (no script).
export function shell({ title, fonts, css, bodyClass = '', intro, frameHtml, canvas = '#d9dde2', canvasInk = '#1f2328' }) {
  const index = frames.map((f) => `<a href="#${f.id}">${esc(f.label)}</a>`).join('');
  const body = frames.map((f) => `<section class="sheet" id="${f.id}"><h2 class="sheet-t">${esc(f.label)}<span>${f.w} × ${f.h}</span></h2><div class="frame" data-shot="${f.id}" style="width:${f.w}px;height:${f.h}px">${frameHtml[f.id] || ''}</div></section>`).join('\n');
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex, nofollow, noarchive">
<title>${esc(title)}</title>
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="${fonts}" rel="stylesheet">
<style>
*{box-sizing:border-box;margin:0;padding:0}
html{background:${canvas};color:${canvasInk}}
body.sheetbody{font:15px/1.45 system-ui,sans-serif;padding:32px 24px 80px;background:${canvas}}
.sheet-top{max-width:1280px;margin:0 auto 28px}
.sheet-top h1{font-size:22px;margin-bottom:6px}
.sheet-top p{max-width:70ch;opacity:.8}
.sheet-idx{display:flex;flex-wrap:wrap;gap:6px 14px;margin-top:12px;font-size:14px}
.sheet-idx a{color:inherit}
.sheet{max-width:1280px;margin:0 auto 44px}
.sheet-t{font:600 14px/1 system-ui,sans-serif;margin-bottom:10px;display:flex;gap:10px;align-items:baseline}
.sheet-t span{font-weight:400;opacity:.6}
.frame{position:relative;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.18)}
@media (max-width:1320px){.frame{transform-origin:0 0}}
${css}
</style></head>
<body class="sheetbody ${bodyClass}">
<header class="sheet-top"><h1>${esc(title)}</h1><p>${intro}</p><nav class="sheet-idx" aria-label="Screens">${index}</nav></header>
${body}
</body></html>`;
}
