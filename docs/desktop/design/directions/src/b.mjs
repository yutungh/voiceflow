// Direction B, "Night Console": dark first, dense, keyboard first. Command palette, shortcut hints on everything, a status
// bar like an editor's, mint on the icon's own deep navy.
import { esc, icon, logo, nav, last, cleaned, changes, failure, suggestions, history, terms, corrections, appRules, pack, styles, styleInput, perApp, blocked, hotkeys, shell } from './data.mjs';

const k = (keys) => `<span class="keys">${keys.map((x) => `<kbd>${esc(x)}</kbd>`).join('')}</span>`;
let n = 0;
const uid = (p) => `${p}${++n}`;
const bars = (count = 22, seed = 2) => `<span class="lvl" aria-hidden="true">${Array.from({ length: count }, (_, i) => `<i style="height:${Math.round(3 + 15 * Math.abs(Math.sin((i + seed) * 0.8) * Math.cos(i * 0.31)))}px"></i>`).join('')}</span>`;
const btn = (label, keys, cls = '') => `<a class="btn ${cls}" href="#"><span>${label}</span>${keys ? k(keys) : ''}</a>`;

const titlebar = (t = 'VoiceFlow') => `<div class="tb">${logo(16, uid('tb'))}<span>${t}</span><span class="tb-c"><i>–</i><i>□</i><i>✕</i></span></div>`;

const rail = (active) => `<aside class="rail">
  <div class="cmd">${icon('search')}<span>Go to or run…</span>${k(['Ctrl', 'K'])}</div>
  <nav aria-label="Main">${nav.map((x) => `<a class="${x.id === active ? 'on' : ''}" href="#">${icon(x.icon)}<span>${x.label}</span>${x.badge ? `<em>${x.badge}</em>` : ''}${k(['Ctrl', x.key])}</a>`).join('')}</nav>
  <div class="rail-foot"><p>Dictate anywhere</p><div>${k(['Ctrl', 'Space'])}<span>toggle</span></div><div>${k(['Alt', 'Shift', 'D'])}<span>hold</span></div></div>
</aside>`;

const statusbarN = () => `<footer class="sb"><span class="sb-ok"><i class="dot ok"></i>Ready</span><span class="sb-warn"><i class="dot warn"></i>1 problem</span><span class="sb-r">Synced 07:12</span></footer>`;
const statusbar = () => `<footer class="sb"><span class="sb-ok"><i class="dot ok"></i>Ready</span><span class="sb-warn"><i class="dot warn"></i>1 problem</span><span>Pack ${pack.version}</span><span>Synced 07:12</span><span>Kite → Casual</span><span class="sb-r">Privacy: strict</span><span>v1.0.0</span></footer>`;

const app = (active, main, overlay = '') => `<div class="win">${titlebar()}<div class="body">${rail(active)}<main class="main">${main}</main></div>${statusbar()}${overlay}</div>`;

const head = (title, sub, right = '') => `<header class="ph"><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}<div class="ph-r">${right}</div></header>`;

const cleanedHtml = () => last.segs.map((s) => (typeof s === 'string' ? esc(s) : `<span class="fix" title="${esc(s.from)}">${esc(s.to)}</span>`)).join('');

const attention = (narrow = false) => `<section class="panel att">
  <h2>Needs you<span class="cnt">2</span></h2>
  <div class="arow bad"><i class="dot bad"></i><div class="a-b"><b>${failure.title}</b><span>Telegram, ${failure.time}. Raw words kept.</span></div>
  <div class="a-acts">${btn('Retry clean-up', ['R'], 'pri')}${btn('Paste raw to Telegram', ['P'])}${narrow ? '' : btn('Dismiss', ['D'], 'ghost')}</div></div>
  ${narrow ? '' : `<div class="arow ind"><i class="dot ind"></i><div class="a-b"><b>Heard “Versal” 3×. Vercel?</b><span>2 suggestions waiting</span></div><div class="a-acts">${btn('Review', ['G', 'S'])}</div></div>`}
</section>`;

const lastPanel = (narrow = false) => `<section class="panel last">
  <h2>Last dictation<span class="meta"><span class="tag mint">Kite</span><span class="tag">Casual</span><span>${last.time}</span></span></h2>
  <p class="lc">${cleanedHtml()}</p>
  <div class="acts">${btn('Copy', ['C'])}${btn('Play', ['Space'])}${narrow ? '' : btn('Re-run clean-up', ['Ctrl', 'Shift', 'R'])}${btn(narrow ? 'Details' : 'Hide details', ['I'], 'ghost')}</div>
  ${narrow ? '' : `<div class="det">
    <div class="d-raw"><h3>Raw</h3><p>${esc(last.raw)}</p></div>
    <div class="d-chg"><h3>Changed</h3><table class="t">${changes.map((c) => `<tr><td class="m">${esc(c.from)}</td><td class="arr">→</td><td><b>${esc(c.to)}</b></td><td class="dim">${c.why}</td></tr>`).join('')}</table></div>
    <div class="d-tim"><h3>Timing</h3><table class="t">${[['transcribe', last.latency.transcribe], ['clean-up', last.latency.cleanup], ['total', last.latency.total]].map(([a, b]) => `<tr><td class="dim">${a}</td><td class="m r">${b}</td></tr>`).join('')}</table></div>
  </div>`}
</section>`;

const targetPanel = () => `<section class="panel tgt">
  <h2>Last target app</h2>
  <p class="dim sm">VoiceFlow is in front now, so this is the last app you dictated into.</p>
  <div class="map"><span class="sel">Kite${icon('down', 'i sm')}</span><span class="arr">→</span><span class="sel">Casual${icon('down', 'i sm')}</span></div>
  <p class="dim sm">Change for one dictation: ${k(['Ctrl', 'Shift', 'X'])}</p>
</section>`;

const todayPanel = () => `<section class="panel"><h2>Today</h2><table class="t kv"><tr><td class="dim">dictations</td><td class="m r">14</td></tr><tr><td class="dim">failed</td><td class="m r bad">2</td></tr><tr><td class="dim">median wait</td><td class="m r">2.4 s</td></tr><tr><td class="dim">vocabulary</td><td class="m r">214 terms</td></tr></table></section>`;

const homeMain = () => `${head('Home', '', `<span class="pill-st warn"><i class="dot warn"></i>1 problem</span>`)}
<div class="hg"><div class="col">${attention()}${lastPanel()}</div><div class="col">${targetPanel()}${todayPanel()}</div></div>`;

const narrowTop = () => `<div class="ntop"><a class="nbtn" href="#">${icon('menu')}<span>Menu</span>${k(['Ctrl', '.'])}</a><b>Home</b><span class="pill-st warn sm"><i class="dot warn"></i>1 problem</span></div>`;
const homeNarrow = () => `<div class="win">${titlebar()}${narrowTop()}<main class="main nm">${attention(true)}${lastPanel(true)}<section class="panel tgt"><h2>Last target app</h2><div class="map"><span class="sel">Kite${icon('down', 'i sm')}</span><span class="arr">→</span><span class="sel">Casual${icon('down', 'i sm')}</span></div></section>${todayPanel()}</main>${statusbarN()}</div>`;
const menuNarrow = () => `<div class="win">${titlebar()}${narrowTop()}<main class="main nm dimmed">${attention(true)}</main>${statusbarN()}
<div class="scrim"></div><div class="pal npal"><div class="pal-in">${icon('search')}<span class="ph-t">Go to…</span><kbd>Esc</kbd></div>
<ul>${nav.map((x, i) => `<li class="${i === 0 ? 'on' : ''}">${icon(x.icon)}<span>${x.label}</span>${x.badge ? `<em>${x.badge}</em>` : ''}${k(['Ctrl', x.key])}</li>`).join('')}
<li class="sep">Actions</li><li>${icon('pause')}<span>Pause dictation</span>${k(['Ctrl', 'Shift', 'P'])}</li><li>${icon('sync')}<span>Refresh vocabulary</span></li></ul></div></div>`;

// pill
const pmark = () => `<span class="pm">${logo(22, uid('pm'))}</span>`;
const pill = (cls, body) => `<div class="pill ${cls}">${pmark()}${body}</div>`;
const errPill = (title, where, body, actions, note = '') => `<div class="pill err"><div class="pe-h">${pmark()}<b>${title}</b><span class="dim">${where}</span><span class="x">${icon('x')}</span></div><p>${body}</p><div class="acts">${actions}</div>${note ? `<p class="pn">${note}</p>` : ''}</div>`;
const pillFrame = () => `<div class="desk">
  <div class="bgwin"><div class="bw-t"></div><div class="bw-l"></div><div class="bw-l s"></div><div class="bw-l"></div></div>
  <div class="pgrid">
    <div class="pc"><h3>While you talk</h3>
      <div class="pr"><span class="cap">listening</span>${pill('', `<i class="dot rec"></i><b>Listening</b>${bars(18)}<span class="dim">Kite</span><span class="hk">${k(['Ctrl', 'Space'])}stop</span><span class="hk">${k(['Esc'])}cancel</span>`)}</div>
      <div class="pr"><span class="cap">transcribing</span>${pill('', `<span class="spin"></span><b>Transcribing</b><span class="dim">0:09 of audio</span><span class="hk">${k(['Esc'])}cancel</span>`)}</div>
      <div class="pr"><span class="cap">cleaning</span>${pill('', `<span class="spin"></span><b>Cleaning up</b><span class="dim">Casual</span>`)}</div>
      <div class="pr"><span class="cap">pasted</span>${pill('ok', `<span class="okc">${icon('check')}</span><b>Pasted to Kite · Casual</b><span class="hk">${k(['Ctrl', 'Shift', 'R'])}re-run</span>`)}</div>
      <p class="pnote">Bottom centre, always on top, never takes focus. Only real global hotkeys are shown on the pill; the buttons are for the mouse.</p>
    </div>
    <div class="pc"><h3>Failures stay until you act</h3>
      ${errPill('Transcription failed', 'Kite, 18:02', 'OpenAI returned 503. Audio saved on this PC.', `${btn('Retry transcription', null, 'pri')}${btn('Dismiss', null, 'ghost')}`)}
      ${errPill('Clean-up failed — nothing pasted', 'Telegram, 10:42', 'Clean-up timed out after 10 s. Telegram got nothing.', `${btn('Retry clean-up', ['Ctrl', 'Shift', 'R'], 'pri')}${btn('Paste raw to Telegram')}${btn('Dismiss', null, 'ghost')}`)}
      ${errPill('Paste blocked (password field)', 'Chrome', 'The focused field is a password field. Text kept here.', `${btn('Copy text')}${btn('Dismiss', null, 'ghost')}`)}
      ${errPill('Target app unknown — Send anyway?', 'audio held', 'Could not identify the app in front. Nothing uploaded.', `${btn('Send anyway, this dictation only', null, 'pri')}${btn('Dismiss', null, 'ghost')}`)}
    </div>
  </div>
</div>`;

const trayMenu = (problem) => `<div class="tray">
  <div class="tr-h">${logo(18, uid('tr'))}<b>VoiceFlow</b>${problem ? '<span class="pill-st warn sm"><i class="dot warn"></i>1 problem</span>' : '<span class="pill-st ok sm"><i class="dot ok"></i>OK</span>'}</div>
  ${problem ? `<div class="tr-p"><b>Clean-up failed in Telegram, 10:42</b><span>Nothing pasted</span></div><a class="mi" href="#"><span>Retry clean-up</span>${k(['Ctrl', 'Shift', 'R'])}</a><a class="mi" href="#"><span>Paste raw to Telegram</span></a><hr>` : ''}
  <div class="tr-l"><span class="dim sm">Last pasted to Kite, 10:58</span><p>${esc(cleaned.slice(0, 84))}…</p></div>
  <a class="mi" href="#"><span>Copy last result</span>${k(['C'])}</a><hr>
  <a class="mi" href="#"><span>Style: <b class="mint">Casual</b></span>${k(['Ctrl', 'Shift', 'X'])}</a>
  <a class="mi" href="#"><span>Pause dictation</span>${k(['P'])}</a>
  <a class="mi" href="#"><span>Refresh vocabulary</span>${k(['V'])}</a>
  <a class="mi" href="#"><span>Open VoiceFlow</span>${k(['O'])}</a><hr>
  <a class="mi" href="#"><span>Quit</span>${k(['Q'])}</a>
</div>`;
const trayFrame = () => `<div class="desk">
  <div class="bgwin wide"><div class="bw-t"></div><div class="bw-l"></div><div class="bw-l s"></div></div>
  <div class="tpair"><div><p class="tcap">One problem: amber icon and text, the problem first</p>${trayMenu(true)}</div><div><p class="tcap">All fine</p>${trayMenu(false)}</div></div>
  <div class="taskbar"><span class="tk">${logo(20, uid('tk'))}<i></i></span><span class="tk-t">10:59 AM<br>11/16/2026</span></div>
</div>`;

const hrow = (r, sel) => {
  const text = r.fail === 'transcribe' ? '<span class="bad">Transcription failed.</span> <span class="dim">Audio saved.</span>' : r.fail === 'cleanup' ? `<span class="bad">Clean-up failed, nothing pasted.</span> <span class="dim">${esc(r.text)}</span>` : r.fail === 'held' ? '<span class="warn">Held, app unknown.</span> <span class="dim">Audio saved on this PC.</span>' : esc(r.text);
  return `<tr class="${sel ? 'cur' : ''} ${r.fail ? 'f' : ''}"><td class="m dim">${r.t}</td><td>${esc(r.app)}</td><td class="dim">${r.style}</td><td class="tx">${text}</td><td class="m dim r">${r.dur}</td></tr>`;
};
const historyMain = () => `${head('History', '1,642 dictations, 3 failed', `${btn('Retention', ['Ctrl', ','], 'ghost')}`)}
<div class="qbar">${icon('search')}<span class="qq"><b class="tok">failed:any</b> <b class="tok">app:*</b> <span class="dim">type to search, or app: style: date:</span></span>${k(['/'])}</div>
<div class="hsplit"><div class="tbl-wrap"><table class="t ht"><thead><tr><th>time</th><th>app</th><th>style</th><th>cleaned text</th><th class="r">len</th></tr></thead>
${history.map((g) => `<tbody><tr class="grp"><td colspan="5">${g.day}</td></tr>${g.items.map((r) => hrow(r, r.t === '10:42')).join('')}</tbody>`).join('')}</table>
<p class="foot dim">${k(['J'])}${k(['K'])} move ${k(['Space'])} play ${k(['C'])} copy ${k(['R'])} retry. Older rows load as you scroll.</p></div>
<aside class="panel hd"><h2>10:42, Telegram</h2><span class="pill-st bad sm"><i class="dot bad"></i>Clean-up failed</span>
<p class="sm">Nothing was pasted. The raw words are kept:</p><p class="rawq">${esc(failure.raw)}</p>
<div class="acts col">${btn('Retry clean-up', ['R'], 'pri')}${btn('Paste raw to Telegram', ['P'])}${btn('Play audio', ['Space'])}${btn('Copy raw', ['C'])}</div>
<table class="t kv"><tr><td class="dim">style</td><td>Casual</td></tr><tr><td class="dim">pack</td><td class="m">${pack.version}</td></tr><tr><td class="dim">transcribe</td><td class="m">1.1 s</td></tr><tr><td class="dim">clean-up</td><td class="m bad">timeout 10 s</td></tr></table></aside></div>`;

const vocabMain = () => `${head('Vocabulary', `Pack ${pack.version}, synced today 07:12`, `<span class="own">${icon('lock')}Managed by Atlas — ask Milo to change</span>`)}
<div class="tabs"><a class="on" href="#">Terms <span>${pack.terms}</span>${k(['1'])}</a><a href="#">Corrections <span>${pack.corrections}</span>${k(['2'])}</a><a href="#">App rules <span>${pack.rules}</span>${k(['3'])}</a><span class="sp"></span>${btn('Check for new pack', ['Ctrl', 'R'], 'ghost')}</div>
<div class="vg"><section class="panel flush"><table class="t vt"><thead><tr><th>term</th><th>heard as</th><th>kind</th><th>apps</th></tr></thead><tbody>${terms.map((t) => `<tr><td><b>${t.text}</b></td><td class="dim">${t.heard.join(', ')}</td><td class="dim">${t.kind.toLowerCase()}</td><td class="dim">${t.apps}</td></tr>`).join('')}</tbody></table><p class="foot dim">204 more. ${k(['/'])} to filter.</p></section>
<div class="col"><section class="panel"><h2>Approved corrections<span class="cnt">4</span></h2><table class="t">${corrections.map((c) => `<tr><td class="dim">${c.from}</td><td class="arr">→</td><td><b>${c.to}</b></td><td class="dim r">${c.scope}</td></tr>`).join('')}</table></section>
<section class="panel"><h2>App rules<span class="cnt">4</span></h2><table class="t">${appRules.map((r) => `<tr><td><b>${r.app}</b></td><td class="dim">${r.rule}</td></tr>`).join('')}</table></section>
<section class="panel split"><div><h3>Atlas owns</h3><p>terms, corrections, app rules, style definitions</p></div><div><h3>This PC owns</h3><p>hotkeys, microphone, OpenAI key, style per app</p></div></section></div></div>`;

const suggestMain = () => { const s = suggestions[0]; return `${head('Suggestions', '1 of 2 waiting', `${btn('Previous', ['K'], 'ghost')}${btn('Next', ['J'], 'ghost')}`)}
<div class="sg"><section class="panel big"><div class="sq-tags"><span class="tag ind">heard ${s.count}×</span><span class="tag">${icon('telegram', 'i sm')}also asked in Telegram</span></div>
<h2 class="sq-q">VoiceFlow heard “${s.heard}” 3× — ${s.guess}?</h2>
<table class="t clips">${s.clips.map((c, i) => `<tr><td>${k([String(i + 1)])}</td><td>${c}</td><td class="dim r">${['Kite, today 10:58', 'Claude Code, today 09:30', 'Kite, Nov 14'][i]}</td></tr>`).join('')}</table>
<div class="choice">${btn('Approve as correction', ['A'], 'pri')}${btn('Hint only', ['H'])}${btn('No', ['N'], 'ghost')}</div>
<p class="dim sm">A rewrites “Versal” to “Vercel” every time. H only adds Vercel as a term to listen for. N means never asked again. Your answer goes to Atlas, so Telegram stops asking too.</p></section>
<div class="col"><section class="panel"><h2>Up next</h2><div class="arow ind"><i class="dot ind"></i><div class="a-b"><b>“Zefira” 2× — Zephyra?</b><span>Telegram, yesterday 21:14</span></div></div></section>
<section class="panel"><h2>Answered</h2><table class="t"><tr><td>Mylo → Milo</td><td class="dim r">correction, Nov 12</td></tr><tr><td>Juno</td><td class="dim r">no, Nov 9</td></tr><tr><td>Cooper Netties → Kubernetes</td><td class="dim r">hint, Nov 3</td></tr></table></section></div></div>`; };

const stylesMain = () => `${head('Styles', 'Wording from Atlas; the choice per app is yours', `<span class="own">${icon('lock')}Style wording managed by your profile</span>`)}
<div class="stg"><section class="panel flush"><table class="t st"><thead><tr><th></th><th>style</th><th>same words, styled</th></tr></thead><tbody>${styles.map((s, i) => `<tr class="${i === 0 ? 'cur' : ''}"><td>${k([String(i + 1)])}</td><td><b>${s.label}</b>${s.sub ? `<span class="dim"> (${s.sub})</span>` : ''}${i === 0 ? '<div class="mint sm">default</div>' : ''}</td><td>${esc(s.sample)}<div class="dim sm">${s.note}</div></td></tr>`).join('')}</tbody></table><p class="foot dim">You said: <span class="m">${esc(styleInput)}</span></p></section>
<section class="panel"><h2>Style per app, this PC</h2><table class="t">${perApp.map((p) => `<tr><td><b>${p.app}</b></td><td><span class="sel sm">${p.style}${icon('down', 'i sm')}</span></td><td class="dim r">${p.seen}</td></tr>`).join('')}</table></section></div>`;
const palette = () => `<div class="pal low"><div class="pal-in">${icon('search')}<span>Style for Kite…</span><kbd>Esc</kbd></div><ul>${styles.map((s, i) => `<li class="${i === 0 ? 'on' : ''}"><span class="ck">${i === 0 ? icon('check') : ''}</span><span>${s.label}</span><span class="dim">${s.note}</span>${k([String(i + 1)])}</li>`).join('')}</ul><p class="pal-f dim">${k(['Enter'])} set for Kite ${k(['Shift', 'Enter'])} this dictation only</p></div>`;

const settingsMain = () => `${head('Settings', 'This PC only. Vocabulary and styles live in Atlas.', btn('Open settings file', null, 'ghost'))}
<div class="setg"><section class="panel flush">
<h3 class="gh">Hotkeys</h3><table class="t">${hotkeys.map((h) => `<tr><td>${h.name}${h.note ? ` <span class="dim">(${h.note})</span>` : ''}</td><td class="r">${k(h.keys)}</td><td class="r"><a class="lk" href="#">change</a></td></tr>`).join('')}</table>
<h3 class="gh">Microphone</h3><table class="t"><tr><td>Input</td><td class="r"><span class="sel sm">USB microphone (default)${icon('down', 'i sm')}</span></td><td class="r">${bars(14, 4)}</td></tr></table>
<h3 class="gh">OpenAI key</h3><table class="t"><tr><td>Stored in Windows Credential Manager</td><td class="r"><span class="pill-st ok sm"><i class="dot ok"></i>valid</span></td><td class="r"><a class="lk" href="#">replace</a></td></tr></table>
<h3 class="gh">Vocabulary account</h3><table class="t"><tr><td>vf-me on your NAS, checked hourly</td><td class="r"><span class="pill-st ok sm"><i class="dot ok"></i>synced 07:12</span></td><td class="r"><a class="lk" href="#">sign out</a></td></tr></table>
</section>
<section class="panel flush"><h3 class="gh">Privacy mode</h3>
<div class="opt on"><span class="rb"></span><div><b>Strict</b><span>Unsure whether the field is a password? Keep the audio and ask.</span></div></div>
<div class="opt"><span class="rb"></span><div><b>Best-effort</b><span>Block only when a password field is actually detected.</span></div></div>
<p class="blk dim">Always blocked (from Atlas): ${blocked.join(', ')}</p>
<h3 class="gh">History and audio</h3><table class="t"><tr><td>Keep text</td><td class="r"><span class="sel sm">forever${icon('down', 'i sm')}</span></td></tr><tr><td>Keep audio</td><td class="r"><span class="sel sm">30 days${icon('down', 'i sm')}</span></td></tr><tr><td colspan="2"><a class="lk bad" href="#">Delete history…</a></td></tr></table>
<h3 class="gh">Updates</h3><table class="t"><tr><td>VoiceFlow 1.0.0</td><td class="r dim">up to date, checked 07:12</td></tr></table>
<h3 class="gh">About</h3><p class="about">Based on Whispering by Braden Wong. AGPL-3.0-or-later. <a class="lk" href="#">licence</a> <a class="lk" href="#">source</a></p></section></div>`;

const firstRun = () => `<div class="win">${titlebar('Set up VoiceFlow')}<div class="fr">
<aside class="fr-steps"><div class="fr-brand">${logo(36, uid('fr'))}<b>Set up VoiceFlow</b></div><ol>
<li class="done"><span class="n">${icon('check')}</span><div><b>Microphone</b><span>USB microphone</span></div></li>
<li class="done"><span class="n">${icon('check')}</span><div><b>Hotkey</b><span>${k(['Ctrl', 'Space'])}</span></div></li>
<li class="done"><span class="n">${icon('check')}</span><div><b>Vocabulary</b><span>vf-me, 214 terms</span></div></li>
<li class="now"><span class="n">4</span><div><b>Test dictation</b><span>Last step</span></div></li></ol></aside>
<section class="fr-main"><h1>Test dictation</h1><p class="dim">Focus the box, press ${k(['Ctrl', 'Space'])}, and say:</p><blockquote>“Ask Milo to check the Pinewood preview on Vercel.”</blockquote>
<div class="tbox"><span>Ask Milo to check the <span class="fix">Pinewood</span> preview on <span class="fix">Vercel</span>.</span><span class="caret"></span></div>
<p class="okline"><i class="dot ok"></i>Pasted in 2.1 s. Pinewood and Vercel came from your vocabulary.</p>
<table class="t frt"><tr><td class="dim">heard</td><td class="m">ask milo to check the pinewud preview on versal</td></tr><tr><td class="dim">terms used</td><td>Milo, Pinewood, Vercel</td></tr><tr><td class="dim">corrections</td><td>pinewud &rarr; Pinewood</td></tr><tr><td class="dim">timing</td><td class="m">transcribe 1.2 s, clean-up 0.9 s</td></tr></table>
<div class="acts">${btn("Back", ["Esc"], "ghost")}${btn("Finish setup", ["Enter"], "pri")}</div></section></div></div>`;

const css = `
.B{--bg:#071016;--panel:#0B1820;--raised:#10222B;--line:#1A2F38;--line2:#24414C;--text:#E2EBED;--muted:#93A8AF;--dim:#7E949C;--mint:#2DD4BF;--teal:#0F766E;--bad:#FF8577;--warn:#F5B544;--ind:#A3A4FA;--okbg:rgba(45,212,191,.12);--badbg:rgba(255,133,119,.10);--warnbg:rgba(245,181,68,.12);--indbg:rgba(163,164,250,.12);--kbd:#16303A;--prif:#06221F;
  font-family:'Geist',system-ui,sans-serif;font-size:13.5px;line-height:1.45;color:var(--text);background:var(--bg);width:100%;height:100%}
.B.light{--bg:#F2F5F6;--panel:#FFFFFF;--raised:#F6F8F9;--line:#E1E7EA;--line2:#CBD5DA;--text:#14232A;--muted:#4E636B;--dim:#5B6F77;--mint:#0F766E;--bad:#B42318;--warn:#93370D;--ind:#4F46C8;--okbg:#DDF3EF;--badbg:#FDECEA;--warnbg:#FEF0C7;--indbg:#EDEDFC;--kbd:#EEF2F4;--prif:#fff}
.B .i{width:16px;height:16px;stroke-width:1.7;flex:none}.B .i.sm{width:13px;height:13px}
.B b{font-weight:600}.B .dim{color:var(--dim)}.B .sm{font-size:12.5px}.B .m{font-family:'Geist Mono',monospace;font-size:12.5px}.B .r{text-align:right}.B .bad{color:var(--bad)}.B .warn{color:var(--warn)}.B .mint{color:var(--mint)}
.B kbd{font:500 11px/1 'Geist Mono',monospace;background:var(--kbd);border:1px solid var(--line2);border-radius:4px;padding:3px 5px;color:var(--muted);min-width:18px;text-align:center}
.B .keys{display:inline-flex;gap:2px;align-items:center}
.B .dot{display:inline-block;width:8px;height:8px;border-radius:50%;flex:none}.B .dot.ok{background:var(--mint)}.B .dot.warn{background:var(--warn)}.B .dot.bad{background:var(--bad)}.B .dot.ind{background:var(--ind)}.B .dot.rec{background:#FF5A4E;box-shadow:0 0 0 4px rgba(255,90,78,.2)}
.B .win{display:flex;flex-direction:column;height:100%;position:relative}
.B .tb{height:30px;display:flex;align-items:center;gap:8px;padding-left:10px;font-size:12px;color:var(--dim);border-bottom:1px solid var(--line);flex:none}.B .tb-c{margin-left:auto;display:flex}.B .tb-c i{font-style:normal;width:42px;text-align:center}
.B .body{display:flex;flex:1;min-height:0}
.B .rail{width:236px;border-right:1px solid var(--line);padding:10px;display:flex;flex-direction:column;flex:none;background:var(--panel)}
.B .cmd{display:flex;align-items:center;gap:8px;height:32px;padding:0 8px;border:1px solid var(--line2);border-radius:6px;color:var(--dim);margin-bottom:12px}.B .cmd .keys{margin-left:auto}
.B .rail nav a{display:flex;align-items:center;gap:10px;height:32px;padding:0 8px;border-radius:6px;color:var(--muted);text-decoration:none;font-weight:500}
.B .rail nav a .keys{margin-left:auto;opacity:.75}.B .rail nav a em{font-style:normal;font-size:11px;font-weight:600;color:var(--ind);background:var(--indbg);border-radius:4px;padding:0 5px}
.B .rail nav a.on{background:var(--raised);color:var(--text);box-shadow:inset 2px 0 0 var(--mint)}
.B .rail-foot{margin-top:auto;font-size:12px;color:var(--dim);display:flex;flex-direction:column;gap:6px;padding:8px}.B .rail-foot div{display:flex;gap:8px;align-items:center}
.B .sb{height:26px;display:flex;align-items:center;gap:16px;padding:0 12px;font-size:12px;color:var(--dim);border-top:1px solid var(--line);background:var(--panel);flex:none}
.B .sb span{white-space:nowrap;display:flex;align-items:center;gap:6px}.B .sb-ok{color:var(--mint)}.B .sb-warn{color:var(--warn)}.B .sb-r{margin-left:auto}
.B .main{flex:1;min-width:0;padding:14px 20px;overflow:hidden}
.B .ph{display:flex;align-items:baseline;gap:12px;margin-bottom:12px}.B .ph h1{font-size:19px;font-weight:600;letter-spacing:-.01em}.B .ph p{color:var(--dim)}.B .ph-r{margin-left:auto;display:flex;gap:6px;align-items:center}
.B .pill-st{display:inline-flex;align-items:center;gap:6px;font-weight:600;font-size:12.5px;border-radius:5px;padding:3px 8px}.B .pill-st.warn{background:var(--warnbg);color:var(--warn)}.B .pill-st.ok{background:var(--okbg);color:var(--mint)}.B .pill-st.bad{background:var(--badbg);color:var(--bad)}.B .pill-st.sm{font-size:11.5px;padding:2px 7px}
.B .panel{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:12px 14px}
.B .panel.flush{padding:0;overflow:hidden}
.B h2{font-size:12.5px;font-weight:600;color:var(--muted);display:flex;align-items:center;gap:8px;margin-bottom:8px}
.B h2 .cnt{font-family:'Geist Mono',monospace;font-weight:500;color:var(--dim)}.B h2 .meta{margin-left:auto;display:flex;gap:6px;align-items:center;font-weight:500;color:var(--dim)}
.B h3{font-size:12px;font-weight:600;color:var(--dim);margin-bottom:4px}
.B .tag{display:inline-flex;align-items:center;gap:4px;font-size:12px;background:var(--raised);border:1px solid var(--line2);border-radius:4px;padding:1px 7px;color:var(--muted)}.B .tag.mint{color:var(--mint);border-color:rgba(45,212,191,.35)}.B .tag.ind{color:var(--ind);border-color:rgba(163,164,250,.35)}
.B.light .tag.mint{border-color:#9FD8CF}
.B .btn{display:inline-flex;align-items:center;gap:8px;height:30px;padding:0 6px 0 10px;border-radius:6px;background:var(--raised);border:1px solid var(--line2);color:var(--text);text-decoration:none;font-weight:500;white-space:nowrap}
.B .btn:not(:has(.keys)){padding-right:10px}
.B .btn.pri{background:var(--mint);border-color:var(--mint);color:var(--prif);font-weight:600}.B .btn.pri kbd{background:rgba(0,0,0,.14);border-color:transparent;color:var(--prif)}
.B.light .btn.pri{background:var(--teal);border-color:var(--teal)}.B.light .btn.pri kbd{background:rgba(255,255,255,.2);color:#fff}
.B .btn.ghost{background:transparent;border-color:transparent;color:var(--muted)}
.B .acts{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}.B .acts.col{flex-direction:column;align-items:stretch}.B .acts.col .btn{justify-content:space-between}
.B .hg{display:grid;grid-template-columns:1fr 290px;gap:12px}.B .col{display:flex;flex-direction:column;gap:12px;min-width:0}
.B .att{padding:10px 12px}.B .arow{display:flex;gap:10px;align-items:center;padding:8px 10px;border-radius:6px}.B .arow+.arow{margin-top:6px}
.B .arow.bad{background:var(--badbg)}.B .arow.ind{background:var(--indbg)}.B .arow .dot{margin-top:0}
.B .a-b{display:flex;flex-direction:column;min-width:0;flex:1}.B .a-b b{white-space:nowrap}.B .arow.bad b{color:var(--bad)}.B .a-b span{color:var(--dim);font-size:12.5px}
.B .a-acts{display:flex;gap:6px}
.B .last{padding:14px 16px}.B .lc{font-size:19px;line-height:1.5;font-weight:450;letter-spacing:-.005em;max-width:62ch;margin:4px 0}
.B .fix{color:var(--mint);border-bottom:1px dashed var(--mint)}
.B .det{display:grid;grid-template-columns:1.2fr 1.4fr .8fr;gap:16px;border-top:1px solid var(--line);margin-top:12px;padding-top:12px}
.B .d-raw p{color:var(--muted);font-family:'Geist Mono',monospace;font-size:12.5px;line-height:1.55}
.B .t{width:100%;border-collapse:collapse}.B .t td,.B .t th{padding:4px 6px;border-bottom:1px solid var(--line);vertical-align:top}.B .t tr:last-child td{border-bottom:0}
.B .t th{font-size:11.5px;font-weight:500;color:var(--dim);text-align:left;background:var(--raised)}
.B .arr{color:var(--dim);width:14px}
.B .tgt .map{display:flex;align-items:center;gap:8px;margin:8px 0}.B .sel{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 10px;border:1px solid var(--line2);border-radius:6px;background:var(--raised);font-weight:500}.B .sel.sm{height:26px;font-size:12.5px}
.B .kv td{padding:5px 6px}
/* narrow */
.B .ntop{height:44px;display:flex;align-items:center;gap:10px;padding:0 10px;border-bottom:1px solid var(--line);flex:none}.B .ntop b{font-size:15px}.B .ntop .pill-st{margin-left:auto}
.B .nbtn{display:inline-flex;align-items:center;gap:6px;height:30px;padding:0 6px 0 8px;border:1px solid var(--line2);border-radius:6px;color:var(--text);text-decoration:none;font-weight:500;background:var(--raised)}
.B .nm{padding:10px;display:flex;flex-direction:column;gap:10px}
.B .nm .arow{flex-wrap:wrap}.B .nm .a-acts{width:100%;padding-left:18px;flex-wrap:wrap}.B .nm .lc{font-size:16.5px}
.B .dimmed{opacity:.5}
.B .scrim{position:absolute;inset:30px 0 0 0;background:rgba(2,8,12,.55)}.B .scrim.s2{inset:30px 0 0 0}
.B .pal{position:absolute;left:50%;top:120px;transform:translateX(-50%);width:560px;background:var(--panel);border:1px solid var(--line2);border-radius:10px;box-shadow:0 24px 60px rgba(0,0,0,.5);overflow:hidden}
.B .pal-in{display:flex;align-items:center;gap:10px;height:46px;padding:0 14px;border-bottom:1px solid var(--line);font-size:15px}.B .pal-in kbd{margin-left:auto}
.B .pal ul{list-style:none;padding:6px}.B .pal li{display:flex;align-items:center;gap:10px;height:36px;padding:0 10px;border-radius:6px;color:var(--muted)}.B .pal li .keys{margin-left:auto}
.B .pal li.on{background:var(--raised);color:var(--text);box-shadow:inset 2px 0 0 var(--mint)}.B .pal li .dim{font-size:12.5px}
.B .pal li.sep{height:26px;font-size:11.5px;color:var(--dim)}.B .pal li em{font-style:normal;font-size:11px;color:var(--ind);background:var(--indbg);border-radius:4px;padding:0 5px}
.B .ck{width:16px;color:var(--mint)}.B .pal-f{display:flex;gap:6px;align-items:center;padding:8px 14px;border-top:1px solid var(--line);font-size:12px}
.B .npal{top:84px;width:384px}.B .pal.low{top:500px;left:auto;right:30px;transform:none;width:600px;box-shadow:0 24px 60px rgba(0,0,0,.6),0 0 0 1px var(--mint)}
/* pill */
.B .desk{position:relative;width:100%;height:100%;background:radial-gradient(circle at 70% 20%,#16323B,#0A161C 60%);overflow:hidden}
.B .bgwin{position:absolute;left:30px;top:30px;width:520px;height:740px;background:#122129;border:1px solid #1E3540;border-radius:10px;padding:14px}.B .bgwin.wide{width:880px;height:560px}
.B .bw-t{height:14px;background:#1A2E37;border-radius:4px;margin-bottom:20px}.B .bw-l{height:10px;width:78%;background:#1A2E37;border-radius:4px;margin:12px 0}.B .bw-l.s{width:50%}
.B .pgrid{position:absolute;left:70px;right:30px;top:36px;display:grid;grid-template-columns:610px 1fr;gap:24px}
.B .pc h3{font-size:12.5px;color:#C9D6DA;margin-bottom:12px}
.B .pr{display:flex;align-items:center;gap:10px;margin-bottom:14px}.B .cap{width:84px;text-align:right;font-size:12px;color:#8FA5AD;flex:none}
.B .pill{display:inline-flex;align-items:center;gap:10px;height:40px;padding:0 12px 0 8px;background:#0B1820;border:1px solid #2A4955;border-radius:999px;box-shadow:0 12px 30px rgba(0,0,0,.45);white-space:nowrap}
.B .pm{display:block;width:22px;height:22px}.B .pill .hk{display:flex;gap:4px;align-items:center;font-size:11.5px;color:var(--dim)}
.B .lvl{display:flex;align-items:center;gap:2px;height:20px}.B .lvl i{display:block;width:2px;border-radius:1px;background:var(--mint)}
.B .spin{width:14px;height:14px;border-radius:50%;border:2px solid #24414C;border-top-color:var(--mint)}
.B .okc{width:18px;height:18px;border-radius:50%;background:var(--mint);color:#06221F;display:grid;place-items:center}.B .okc .i{width:12px;height:12px;stroke-width:2.6}
.B .pnote{font-size:12.5px;color:#9FB3BA;margin:10px 0 0 94px;max-width:400px}
.B .pill.err{display:flex;flex-direction:column;align-items:stretch;height:auto;border-radius:12px;padding:10px 12px;gap:0;margin-bottom:10px;border-color:rgba(255,133,119,.45);white-space:normal}
.B .pe-h{display:flex;gap:10px;align-items:center}.B .pe-h b{color:var(--bad)}.B .pe-h .x{margin-left:auto;color:var(--dim)}
.B .pill.err p{color:var(--muted);font-size:12.5px;margin:4px 0 0 32px}.B .pill.err .acts{margin:8px 0 0 32px}
/* tray */
.B .tpair{position:absolute;right:20px;bottom:54px;display:flex;gap:18px;align-items:flex-end}.B .tcap{font-size:12px;color:#B5C4C9;margin:0 0 6px 2px}
.B .tray{width:330px;background:#0E1D25;border:1px solid #27434E;border-radius:8px;padding:6px;box-shadow:0 20px 50px rgba(0,0,0,.55)}
.B .tr-h{display:flex;gap:8px;align-items:center;padding:6px 8px 8px}.B .tr-h .pill-st{margin-left:auto}
.B .tr-p{margin:0 4px 4px;padding:8px 10px;background:var(--badbg);border-radius:6px;display:flex;flex-direction:column}.B .tr-p b{color:var(--bad)}.B .tr-p span{color:var(--dim);font-size:12.5px}
.B .tr-l{padding:6px 10px}.B .tr-l p{font-size:12.5px;color:var(--muted);margin-top:2px}
.B .mi{display:flex;align-items:center;height:30px;padding:0 10px;border-radius:5px;color:var(--text);text-decoration:none}.B .mi .keys{margin-left:auto}
.B .tray hr{border:0;border-top:1px solid var(--line);margin:4px 0}
.B .taskbar{position:absolute;left:0;right:0;bottom:0;height:44px;background:#0A1419;border-top:1px solid #1E3540;display:flex;justify-content:flex-end;align-items:center;gap:16px;padding:0 16px;color:#C9D6DA}
.B .tk{position:relative;padding:5px;border-radius:6px;background:#17303A}.B .tk i{position:absolute;right:1px;bottom:1px;width:9px;height:9px;border-radius:50%;background:var(--warn);border:2px solid #0A1419}.B .tk-t{font-size:11.5px;text-align:right;line-height:1.3}
/* history */
.B .qbar{display:flex;align-items:center;gap:10px;height:36px;padding:0 10px;border:1px solid var(--line2);border-radius:6px;background:var(--panel);margin-bottom:10px;color:var(--dim)}.B .qbar .qq{flex:1}
.B .tok{font-family:'Geist Mono',monospace;font-weight:500;font-size:12px;color:var(--mint);background:var(--okbg);border-radius:4px;padding:1px 6px}
.B .hsplit{display:grid;grid-template-columns:1fr 300px;gap:12px}
.B .tbl-wrap{border:1px solid var(--line);border-radius:8px;overflow:hidden;background:var(--panel)}
.B .ht td{padding:6px 8px;white-space:nowrap}.B .ht td.tx{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:420px}
.B .ht tr.grp td{background:var(--raised);font-size:11.5px;color:var(--dim);padding:4px 8px}
.B .ht tr.cur td{background:var(--raised)}.B .ht tr.cur td:first-child{box-shadow:inset 2px 0 0 var(--mint)}
.B .foot{display:flex;gap:4px;align-items:center;flex-wrap:wrap;padding:8px 10px;font-size:12px;border-top:1px solid var(--line)}
.B .hd{display:flex;flex-direction:column;gap:8px}.B .hd h2{margin:0;color:var(--text);font-size:14px}
.B .rawq{font-family:'Geist Mono',monospace;font-size:12.5px;color:var(--muted);background:var(--raised);border-radius:6px;padding:8px}
/* vocab */
.B .own{display:inline-flex;align-items:center;gap:6px;color:var(--mint);background:var(--okbg);border-radius:5px;padding:4px 10px;font-weight:600;font-size:12.5px}
.B .tabs{display:flex;align-items:center;gap:4px;border-bottom:1px solid var(--line);margin-bottom:12px}
.B .tabs a{display:flex;gap:6px;align-items:center;height:34px;padding:0 10px;color:var(--muted);text-decoration:none;font-weight:500}.B .tabs>a>span{font-family:'Geist Mono',monospace;color:var(--dim);font-size:12px}
.B .tabs a.on{color:var(--text);box-shadow:inset 0 -2px 0 var(--mint)}.B .tabs .sp{flex:1}
.B .vg{display:grid;grid-template-columns:1.2fr 1fr;gap:12px}.B .vt td{padding:6px 10px}.B .vt th{padding:6px 10px}
.B .split{display:grid;grid-template-columns:1fr 1fr;gap:12px}.B .split p{color:var(--muted);font-size:12.5px}
/* suggestions */
.B .sg{display:grid;grid-template-columns:1.5fr 1fr;gap:12px}.B .big{padding:18px 20px}
.B .sq-tags{display:flex;gap:6px}.B .sq-q{font-size:24px;color:var(--text);font-weight:600;letter-spacing:-.015em;margin:14px 0}
.B .clips td{padding:6px 6px}.B .choice{display:flex;gap:8px;margin:14px 0 10px}.B .choice .btn{height:36px}
/* styles */
.B .stg{display:grid;grid-template-columns:1.6fr 1fr;gap:12px}.B .st td{padding:8px 10px}.B .st tr.cur td{background:var(--raised)}.B .st tr.cur td:first-child{box-shadow:inset 2px 0 0 var(--mint)}
/* settings */
.B .setg{display:grid;grid-template-columns:1.15fr 1fr;gap:12px}
.B .gh{background:var(--raised);padding:6px 12px;margin:0;border-bottom:1px solid var(--line);border-top:1px solid var(--line)}.B .panel.flush .gh:first-child{border-top:0}
.B .setg .t td{padding:6px 12px;vertical-align:middle}.B .lk{color:var(--mint);text-decoration:none;font-size:12.5px}.B .lk.bad{color:var(--bad)}
.B .opt{display:flex;gap:10px;padding:8px 12px;border-bottom:1px solid var(--line)}.B .opt div{display:flex;flex-direction:column}.B .opt span:not(.rb){color:var(--dim);font-size:12.5px}
.B .opt.on{background:var(--okbg)}.B .rb{width:14px;height:14px;border-radius:50%;border:1.5px solid var(--dim);margin-top:3px;flex:none}.B .opt.on .rb{border:4px solid var(--mint)}
.B .blk{padding:6px 12px 8px;font-size:12.5px}.B .about{padding:8px 12px;color:var(--muted)}
/* first run */
.B .fr{flex:1;display:grid;grid-template-columns:300px 1fr;min-height:0}
.B .fr-steps{border-right:1px solid var(--line);padding:24px 18px;background:var(--panel)}.B .fr-brand{display:flex;gap:10px;align-items:center;margin-bottom:26px;font-size:15px}
.B .fr-steps ol{list-style:none;display:flex;flex-direction:column;gap:4px}.B .fr-steps li{display:flex;gap:12px;padding:10px;border-radius:6px}.B .fr-steps li div{display:flex;flex-direction:column}.B .fr-steps li span:not(.n){color:var(--dim);font-size:12.5px}
.B .fr-steps li.now{background:var(--raised);box-shadow:inset 2px 0 0 var(--mint)}
.B .fr-steps .n{width:22px;height:22px;border-radius:5px;display:grid;place-items:center;font-family:'Geist Mono',monospace;font-size:12px;background:var(--okbg);color:var(--mint);flex:none}.B .fr-steps .n .i{width:13px;height:13px;stroke-width:2.4}
.B .fr-steps li.now .n{background:var(--mint);color:#06221F}
.B .fr-main{padding:64px 72px;max-width:760px}.B .fr-main h1{font-size:26px;font-weight:600;letter-spacing:-.015em;margin-bottom:8px}
.B blockquote{font-size:18px;margin:14px 0 18px;padding-left:14px;box-shadow:inset 2px 0 0 var(--line2);color:var(--text)}
.B .tbox{border:1px solid var(--mint);border-radius:8px;padding:14px;min-height:90px;font-size:17px;background:var(--panel)}
.B .caret{display:inline-block;width:2px;height:19px;background:var(--text);vertical-align:-3px;margin-left:2px}
.B .okline{display:flex;gap:8px;align-items:center;color:var(--mint);margin:12px 0 14px}.B .frt{max-width:560px;margin-bottom:22px}.B .frt td{padding:6px 8px}.B .frt td:first-child{width:110px}
`;

export function render() {
  const homeFrame = (light) => `<div class="B ${light ? 'light' : ''}">${app('home', homeMain())}</div>`;
  const frameHtml = {
    pill: `<div class="B">${pillFrame()}</div>`,
    tray: `<div class="B">${trayFrame()}</div>`,
    home: homeFrame(false),
    'home-narrow': `<div class="B">${homeNarrow()}</div>`,
    'menu-narrow': `<div class="B">${menuNarrow()}</div>`,
    'home-alt': homeFrame(true),
    history: `<div class="B">${app('history', historyMain())}</div>`,
    vocab: `<div class="B">${app('vocab', vocabMain())}</div>`,
    suggest: `<div class="B">${app('suggest', suggestMain())}</div>`,
    styles: `<div class="B">${app('styles', stylesMain(), palette())}</div>`,
    settings: `<div class="B">${app('settings', settingsMain())}</div>`,
    firstrun: `<div class="B">${firstRun()}</div>`,
  };
  return shell({
    title: 'VoiceFlow, direction B: Night Console',
    fonts: 'https://fonts.googleapis.com/css2?family=Geist:wght@400;450;500;600;700&family=Geist+Mono:wght@400;500&display=swap',
    css, frameHtml, canvas: '#2A3439', canvasInk: '#E2EBED',
    intro: 'Dark first, dense and keyboard first: a command palette, shortcut hints on every action, a status bar like an editor’s. Mint on the icon’s own deep navy. Static mockup with demo content; same structure as A and C.',
  });
}
