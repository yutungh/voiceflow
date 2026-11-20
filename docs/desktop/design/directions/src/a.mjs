// Direction A, "Calm Teal": continues the Android app. Light first, soft teal, 18 px cards, pill buttons, friendly.
import { esc, icon, logo, nav, last, cleaned, changes, failure, suggestions, history, terms, corrections, appRules, pack, styles, styleInput, perApp, blocked, hotkeys, shell } from './data.mjs';

const k = (keys) => `<span class="keys">${keys.map((x) => `<kbd>${esc(x)}</kbd>`).join('')}</span>`;
const bars = (n = 18, seed = 3) => `<span class="lvl" aria-hidden="true">${Array.from({ length: n }, (_, i) => `<i style="height:${Math.round(5 + 15 * Math.abs(Math.sin((i + seed) * 0.9) * Math.cos(i * 0.37)))}px"></i>`).join('')}</span>`;

let tbn = 0;
const titlebar = (t = 'VoiceFlow') => `<div class="tb">${logo(18, 'tb' + (++tbn))}<span>${t}</span><span class="tb-c"><i>–</i><i>□</i><i>✕</i></span></div>`;

const side = (active) => `<aside class="side">
  <div class="brand">${logo(30, 'sb' + active)}<div><b>VoiceFlow</b><span>Ready</span></div></div>
  <nav aria-label="Main">${nav.map((n) => `<a class="${n.id === active ? 'on' : ''}" href="#">${icon(n.icon)}<span>${n.label}</span>${n.badge ? `<em>${n.badge}</em>` : ''}</a>`).join('')}</nav>
  <div class="side-foot">
    <div class="sf-row">${icon('sync')}<span>Vocabulary synced<br><b>Today 07:12</b></span></div>
    <div class="sf-keys"><span>Dictate</span>${k(['Ctrl', 'Space'])}</div>
  </div>
</aside>`;

const app = (active, main, extra = '') => `<div class="win">${titlebar()}<div class="body">${side(active)}<main class="main">${main}</main></div>${extra}</div>`;

const head = (title, sub, right = '') => `<header class="ph"><div><h1>${title}</h1>${sub ? `<p>${sub}</p>` : ''}</div>${right}</header>`;

const failCard = (compact = false) => `<div class="card fail">
  <div class="fc-ic">${icon('alert')}</div>
  <div class="fc-b">
    <div class="fc-t"><b>${failure.title}</b><span>Telegram, ${failure.time}</span></div>
    ${compact ? '' : `<p>${failure.detail}</p>`}
    <div class="acts"><a class="btn pri" href="#">${icon('retry')}Retry clean-up</a><a class="btn" href="#">Paste raw to Telegram</a>${compact ? '' : '<a class="btn ghost" href="#">Dismiss</a>'}</div>
  </div>
</div>`;

const sugCard = () => `<div class="card sug"><div class="fc-ic">${icon('spark')}</div><div class="fc-b"><div class="fc-t"><b>VoiceFlow heard “Versal” 3 times. Vercel?</b><span>1 more waiting</span></div><div class="acts"><a class="btn pri ind" href="#">Review suggestions</a></div></div></div>`;

const cleanedHtml = (mark = false) => last.segs.map((s) => (typeof s === 'string' ? esc(s) : mark ? `<mark>${esc(s.to)}</mark>` : esc(s.to))).join('');

const lastCard = (open = true, narrow = false) => `<section class="card last">
  <div class="lc-meta"><span class="chip app">${icon('out')}Kite</span><span class="chip">Casual</span><span class="muted">Pasted at ${last.time}</span></div>
  <p class="lc-text">${cleanedHtml(true)}</p>
  <div class="acts"><a class="btn" href="#">${icon('copy')}Copy</a><a class="btn" href="#">${icon('play')}Play audio</a>${narrow ? '' : `<a class="btn" href="#">${icon('retry')}Re-run clean-up</a>`}</div>
  <details class="det" ${open ? 'open' : ''}>
    <summary>${icon('down')}<b>Details</b><span>raw words, ${changes.length} changes, ${last.latency.total}</span></summary>
    <div class="det-grid">
      <div><h3>What you said</h3><p class="raw">${esc(last.raw)}</p></div>
      <div><h3>What corrections changed</h3><ul class="chg">${changes.map((c) => `<li><s>${esc(c.from)}</s>${icon('chev', 'i sm')}<b>${esc(c.to)}</b><span>${c.why}</span></li>`).join('')}</ul></div>
      <div><h3>Timing</h3><dl class="tim"><dt>Transcribe</dt><dd>${last.latency.transcribe}</dd><dt>Clean-up</dt><dd>${last.latency.cleanup}</dd><dt>Total</dt><dd>${last.latency.total}</dd></dl></div>
    </div>
  </details>
</section>`;

const targetCard = () => `<section class="card target">
  <h2>Last target app</h2>
  <p class="muted sm">Opening VoiceFlow puts it in front, so this is the last app you dictated into.</p>
  <div class="tg-row"><span class="sel">${icon('out')}Kite${icon('down', 'i sm')}</span><span class="arrow">${icon('chev')}</span><span class="sel">Casual${icon('down', 'i sm')}</span></div>
  <p class="muted sm">Saved on this PC. Next time you dictate into Kite, it uses Casual.</p>
</section>`;

const homeMain = () => `${head('Ready when you are', `Press ${k(['Ctrl', 'Space'])} in any app, or hold ${k(['Alt', 'Shift', 'D'])} to talk.`, `<div class="ph-r"><span class="status warn">${icon('alert')}1 problem</span></div>`)}
<div class="home-grid">
  <div class="col">
    <h2 class="lbl">Needs you</h2>
    ${failCard(true)}
    ${sugCard()}
    <h2 class="lbl">Last dictation</h2>
    ${lastCard(true)}
  </div>
  <div class="col side-col">
    ${targetCard()}
    <section class="card mini"><h2>Vocabulary</h2><p><b>${pack.terms} terms</b>, ${pack.corrections} corrections, ${pack.rules} app rules</p><p class="muted sm">Pack ${pack.version}, synced ${pack.synced.toLowerCase()}</p></section>
    <section class="card mini"><h2>Today</h2><dl class="tim"><dt>Dictations</dt><dd>14</dd><dt>Failed</dt><dd class="bad">2</dd><dt>Average wait</dt><dd>2.4 s</dd></dl></section>
  </div>
</div>`;

const narrowTop = (title) => `<div class="ntop"><a class="nbtn" href="#">${icon('menu')}<span>Menu</span></a><b>${title}</b><span class="status warn sm">${icon('alert')}1 problem</span></div>`;

const homeNarrow = () => `<div class="win narrow">${titlebar()}${narrowTop('Home')}<main class="main nmain">
  <p class="nready">Ready. Press ${k(['Ctrl', 'Space'])} to dictate.</p>
  ${failCard(true)}
  <h2 class="lbl">Last dictation</h2>
  ${lastCard(false, true)}
  <section class="card target slim"><h2>Last target app</h2><div class="tg-row"><span class="sel">Kite${icon('down', 'i sm')}</span><span class="arrow">${icon('chev')}</span><span class="sel">Casual${icon('down', 'i sm')}</span></div></section>
</main></div>`;

const menuNarrow = () => `<div class="win narrow">${titlebar()}${narrowTop('Home')}<main class="main nmain dim">
  <p class="nready">Ready. Press ${k(['Ctrl', 'Space'])} to dictate.</p>${failCard(true)}</main>
  <div class="scrim"></div>
  <aside class="drawer"><div class="brand">${logo(30, 'dr')}<div><b>VoiceFlow</b><span class="warn-t">1 problem</span></div><a class="nbtn close" href="#">${icon('x')}<span>Close</span></a></div>
  <nav aria-label="Main">${nav.map((n) => `<a class="${n.id === 'home' ? 'on' : ''}" href="#">${icon(n.icon)}<span>${n.label}</span>${n.badge ? `<em>${n.badge}</em>` : ''}</a>`).join('')}</nav>
  <div class="side-foot"><div class="sf-row">${icon('sync')}<span>Vocabulary synced<br><b>Today 07:12</b></span></div><a class="btn wide" href="#">${icon('pause')}Pause dictation</a></div></aside>
</div>`;

// Pill states
const pill = (cls, mark, title, sub, tail = '') => `<div class="pill ${cls}">${mark}<div class="pl-t"><b>${title}</b>${sub ? `<span>${sub}</span>` : ''}</div>${tail}</div>`;
const pmark = (id) => `<span class="pm">${logo(30, 'pm' + id)}</span>`;
const spinner = '<span class="spin" aria-hidden="true"></span>';
const errPill = (id, title, app, body, actions) => `<div class="pill err">
  <div class="pe-top">${pmark(id)}<div class="pl-t"><b>${title}</b><span>${app}</span></div><span class="pe-x">${icon('x')}</span></div>
  <p>${body}</p>
  <div class="acts">${actions}</div>
</div>`;
const pillFrame = () => `<div class="desk">
  <div class="bgwin"><div class="gh-bar"></div><div class="gh-l"></div><div class="gh-l s"></div><div class="gh-l"></div><div class="gh-in"><span class="caret"></span></div></div>
  <div class="pill-cols">
    <div class="pcol"><h3>While you talk</h3>
      <div class="prow"><span class="pcap">Listening</span>${pill('', pmark('a'), 'Listening', 'Kite, Casual', bars() + `<span class="hint">${k(['Ctrl', 'Space'])} stop</span>`)}</div>
      <div class="prow"><span class="pcap">Transcribing</span>${pill('', pmark('b'), 'Writing it down', 'Kite, Casual', spinner)}</div>
      <div class="prow"><span class="pcap">Cleaning</span>${pill('', pmark('c'), 'Tidying up', 'Casual style', spinner)}</div>
      <div class="prow"><span class="pcap">Pasted</span>${pill('ok', pmark('d'), 'Pasted to Kite · Casual', '', `<span class="ok-ic">${icon('check')}</span>`)}</div>
      <p class="pnote">The pill sits at the bottom centre of the screen, never takes focus, and fades 2 seconds after a paste.</p>
    </div>
    <div class="pcol"><h3>When something goes wrong (stays until you act)</h3>
      ${errPill('e', 'Transcription failed', 'Kite, 18:02', 'OpenAI did not answer. Your audio is saved on this PC.', '<a class="btn pri" href="#">Retry transcription</a><a class="btn ghost" href="#">Dismiss</a>')}
      ${errPill('f', 'Clean-up failed — nothing pasted', 'Telegram, 10:42', 'The clean-up model timed out, so nothing went into Telegram.', '<a class="btn pri" href="#">Retry clean-up</a><a class="btn" href="#">Paste raw to Telegram</a><a class="btn ghost" href="#">Dismiss</a>')}
      ${errPill('g', 'Paste blocked (password field)', 'Chrome', 'The cursor is in a password field. The text stays in VoiceFlow.', '<a class="btn" href="#">Copy text</a><a class="btn ghost" href="#">Dismiss</a>')}
      ${errPill('h', 'Target app unknown — Send anyway?', 'Audio kept on this PC', 'VoiceFlow could not tell which app is in front, so nothing was sent.', '<a class="btn pri" href="#">Send anyway, this dictation only</a><a class="btn ghost" href="#">Dismiss</a>')}
    </div>
  </div>
</div>`;

const trayMenu = (problem) => `<div class="tray ${problem ? '' : 'calm'}">
  <div class="tr-h">${logo(26, 'tr' + (problem ? 'p' : 'o'))}<b>VoiceFlow</b>${problem ? `<span class="status warn sm">${icon('alert')}1 problem</span>` : `<span class="status ok sm">${icon('check')}OK</span>`}</div>
  ${problem ? `<div class="tr-prob"><b>Clean-up failed in Telegram</b><span>10:42, nothing pasted</span><div class="acts"><a class="btn pri sm" href="#">Retry clean-up</a><a class="btn sm" href="#">Paste raw to Telegram</a></div></div>` : ''}
  <div class="tr-last"><span class="muted sm">Last pasted, Kite at 10:58</span><p>${esc(cleaned.slice(0, 92))}…</p><a class="btn sm" href="#">${icon('copy')}Copy</a></div>
  <div class="tr-items">
    <a href="#">${icon('styles')}<span>Style for Kite</span><em>Casual${icon('chev', 'i sm')}</em></a>
    <a href="#">${icon('pause')}<span>Pause dictation</span></a>
    <a href="#">${icon('sync')}<span>Refresh vocabulary</span></a>
    <a href="#">${icon('out')}<span>Open VoiceFlow</span></a>
    <a href="#" class="q"><span>Quit</span></a>
  </div>
</div>`;
const trayFrame = () => `<div class="desk tdesk">
  <div class="bgwin wide"><div class="gh-bar"></div><div class="gh-l"></div><div class="gh-l s"></div></div>
  <div class="tray-pair">
    <div><p class="tcap">With a problem: amber icon, the problem first</p>${trayMenu(true)}</div>
    <div><p class="tcap">All fine</p>${trayMenu(false)}</div>
  </div>
  <div class="taskbar"><span class="tk-ic">${logo(22, 'tk')}<i class="amber"></i></span><span class="tk-t">10:59 AM<br>11/16/2026</span></div>
</div>`;

const historyMain = () => `${head('History', '1,642 dictations since June. Cleaned text first.', '<div class="ph-r"><a class="btn" href="#">Retention: text forever, audio 30 days</a></div>')}
<div class="toolbar"><span class="search">${icon('search')}<span>Search your dictations</span></span>
<span class="fpill">App: All${icon('down', 'i sm')}</span><span class="fpill">Date: Any${icon('down', 'i sm')}</span><span class="fpill">Style: All${icon('down', 'i sm')}</span><span class="fpill bad">${icon('alert')}Failed only<em>3</em></span></div>
<div class="hlist">${history.map((g) => `<h3 class="hday">${g.day}</h3>${g.items.map((r) => `<div class="hrow ${r.fail === 'held' ? 'h' : r.fail ? 'f' : ''}"><span class="ht">${r.t}</span><span class="chip app">${esc(r.app)}</span><span class="hs">${r.style}</span>
<span class="htext">${r.fail === 'transcribe' ? '<b class="bad">Transcription failed.</b> Audio is saved.' : r.fail === 'cleanup' ? `<b class="bad">Clean-up failed, nothing pasted.</b> Raw: ${esc(r.text)}` : r.fail === 'held' ? `<b class="warn-t">Held: target app unknown.</b> Audio is saved on this PC.` : esc(r.text)}</span>
<span class="hacts">${r.fail === 'transcribe' ? '<a class="btn sm pri" href="#">Retry transcription</a>' : r.fail === 'cleanup' ? '<a class="btn sm pri" href="#">Retry clean-up</a>' : r.fail === 'held' ? '<a class="btn sm pri" href="#">Send anyway</a>' : ''}<a class="ib" href="#" title="Play audio">${icon('play')}</a><a class="ib" href="#" title="Copy">${icon('copy')}</a></span></div>`).join('')}`).join('')}
<p class="hmore">Scroll for older dictations, loaded as you go</p></div>`;

const vocabMain = () => `${head('Vocabulary', 'The names and words VoiceFlow listens for.', `<div class="ph-r"><span class="badge-c">${icon('lock')}Managed by Atlas — ask Milo to change</span></div>`)}
<div class="packbar"><span><b>Pack ${pack.version}</b></span><span>Synced ${pack.synced.toLowerCase()}</span><span>${pack.terms} terms</span><span>${pack.corrections} corrections</span><span>${pack.rules} app rules</span><a class="btn sm" href="#">${icon('sync')}Check for a new pack</a></div>
<div class="vgrid">
  <section class="card vt"><h2>Terms<span class="muted">${pack.terms}</span></h2><table class="t"><thead><tr><th>Term</th><th>Often heard as</th><th>Kind</th><th>Where</th></tr></thead><tbody>${terms.map((t) => `<tr><td><b>${t.text}</b></td><td class="muted">${t.heard.join(', ')}</td><td>${t.kind}</td><td class="muted">${t.apps}</td></tr>`).join('')}</tbody></table><p class="muted sm more">and 204 more</p></section>
  <div class="col">
    <section class="card"><h2>Approved corrections<span class="muted">${corrections.length}</span></h2><p class="muted sm">These always rewrite the text, once, after clean-up.</p><ul class="corr">${corrections.map((c) => `<li><s>${c.from}</s>${icon('chev', 'i sm')}<b>${c.to}</b><span class="chip">${c.scope}</span></li>`).join('')}</ul></section>
    <section class="card"><h2>App rules<span class="muted">${appRules.length}</span></h2><ul class="rules">${appRules.map((r) => `<li><b>${r.app}</b><span>${r.rule}</span></li>`).join('')}</ul></section>
  </div>
</div>
<div class="owner"><div><h3>${icon('lock')}Atlas owns</h3><p>Terms, corrections, app rules, style definitions</p></div><div><h3>${icon('kbd')}This PC owns</h3><p>Hotkeys, microphone, OpenAI key, style per app</p></div></div>`;

const suggestMain = () => `${head('Suggestions', 'Words VoiceFlow keeps mishearing. Nothing changes until you answer.', '<div class="ph-r"><span class="muted">2 waiting</span></div>')}
<div class="sgrid"><div class="col">${suggestions.map((s, i) => `<section class="card sq ${i ? 'second' : ''}">
  <div class="sq-top"><span class="chip ind">${icon('spark')}Heard ${s.count} times</span>${s.telegram ? `<span class="chip tg">${icon('telegram')}Also asked in Telegram</span>` : ''}</div>
  <h2 class="sq-q">VoiceFlow heard “${s.heard}” ${s.count}× — ${s.guess}?</h2>
  ${i ? '' : `<ul class="clips">${s.clips.map((c) => `<li>${icon('play')}<span>${c}</span></li>`).join('')}</ul>`}
  <p class="muted sm">In ${s.apps}. Last heard ${s.last}.</p>
  <div class="acts"><a class="btn pri" href="#">Approve as correction</a><a class="btn" href="#">Hint only</a><a class="btn ghost" href="#">No</a></div>
</section>`).join('')}</div>
<aside class="col"><section class="card"><h2>What each answer does</h2><dl class="ans"><dt>Approve as correction</dt><dd>“Versal” is always rewritten to “Vercel”.</dd><dt>Hint only</dt><dd>Vercel is added as a term. VoiceFlow listens for it but never rewrites.</dd><dt>No</dt><dd>Never asked again.</dd></dl><p class="muted sm">Your answer goes to Atlas, so the same question disappears from Telegram too.</p></section>
<section class="card"><h2>Answered</h2><ul class="rules"><li><b>Mylo → Milo</b><span>Correction, Nov 12</span></li><li><b>Juno</b><span>No, Nov 9</span></li><li><b>Cooper Netties → Kubernetes</b><span>Hint only, Nov 3</span></li></ul></section></aside></div>`;

const stylesMain = () => `${head('Styles', 'How your words are tidied before they are pasted.', `<div class="ph-r"><span class="badge-c">${icon('lock')}Style wording comes from Atlas</span></div>`)}
<div class="stgrid">
  <section class="card"><h2>Same words, five styles</h2><p class="said"><span class="muted sm">You said</span>${esc(styleInput)}</p>
  <ul class="stlist">${styles.map((s, i) => `<li class="${i === 0 ? 'on' : ''}"><div class="st-n"><b>${s.label}</b>${s.sub ? `<span>${s.sub}</span>` : ''}${i === 0 ? '<em>Default</em>' : ''}</div><p>${esc(s.sample)}</p><span class="muted sm">${s.note}</span></li>`).join('')}</ul></section>
  <section class="card"><h2>Style per app on this PC</h2><p class="muted sm">Set here, not in Atlas. New apps use the default.</p>
  <table class="t pa"><tbody>${perApp.map((p) => `<tr><td><b>${p.app}</b></td><td><span class="sel sm">${p.style}${icon('down', 'i sm')}</span></td><td class="muted">${p.seen}</td></tr>`).join('')}</tbody></table>
  <p class="muted sm">Switch for one dictation with ${k(['Ctrl', 'Shift', 'X'])}</p></section>
</div>`;

const settingsMain = () => `${head('Settings', 'Only what belongs to this PC.')}
<div class="setg">
  <div class="col">
    <section class="card"><h2>Hotkeys</h2><ul class="hk">${hotkeys.map((h) => `<li><span>${h.name}${h.note ? `<i class="muted"> ${h.note}</i>` : ''}</span>${k(h.keys)}</li>`).join('')}</ul><a class="btn sm" href="#">Change hotkeys</a></section>
    <section class="card"><h2>Microphone</h2><span class="sel wide">USB microphone (default)${icon('down', 'i sm')}</span><div class="micl">${bars(26, 1)}</div></section>
  </div>
  <div class="col">
    <section class="card"><h2>Privacy mode</h2>
      <label class="rad on"><span class="r"></span><span><b>Strict</b>If VoiceFlow cannot confirm the field is safe, it keeps the audio and asks.</span></label>
      <label class="rad"><span class="r"></span><span><b>Best-effort</b>Blocks only when it actually detects a password field.</span></label>
      <p class="muted sm">Always blocked (from Atlas): ${blocked.join(', ')}</p></section>
    <section class="card"><h2>OpenAI key</h2><p>${icon('lock', 'i sm')} Saved in Windows Credential Manager</p><a class="btn sm" href="#">Replace key</a></section>
    <section class="card"><h2>Vocabulary account</h2><p><b>vf-me</b> on your NAS <span class="status ok sm">${icon('check')}Connected</span></p><p class="muted sm">Last sync today 07:12, checks hourly</p></section>
  </div>
  <div class="col">
    <section class="card"><h2>History and audio</h2><dl class="kv"><dt>Keep text</dt><dd><span class="sel sm">Forever${icon('down', 'i sm')}</span></dd><dt>Keep audio</dt><dd><span class="sel sm">30 days${icon('down', 'i sm')}</span></dd></dl><a class="btn sm danger" href="#">Delete history…</a></section>
    <section class="card"><h2>Updates</h2><p>VoiceFlow 1.0.0 <span class="muted">is up to date</span></p><p class="muted sm">Checked today 07:12</p></section>
    <section class="card"><h2>About</h2><p class="sm">Based on Whispering by Braden Wong. Free software under the AGPL-3.0-or-later licence.</p><div class="acts"><a class="btn sm" href="#">Licence</a><a class="btn sm" href="#">Source code</a></div></section>
  </div>
</div>`;

const firstRun = () => `<div class="win">${titlebar('Set up VoiceFlow')}<div class="fr">
  <div class="fr-card">
    <div class="fr-h">${logo(52, 'fr')}<div><h1>Set up VoiceFlow</h1><p class="muted">Four steps, about two minutes.</p></div></div>
    <ol class="steps">
      <li class="done"><span class="sn">${icon('check')}</span><div><b>Microphone</b><span>USB microphone, sounds good</span></div></li>
      <li class="done"><span class="sn">${icon('check')}</span><div><b>Hotkey</b><span>${k(['Ctrl', 'Space'])} to start and stop</span></div></li>
      <li class="done"><span class="sn">${icon('check')}</span><div><b>Vocabulary</b><span>Connected as vf-me, 214 terms</span></div></li>
      <li class="now"><span class="sn">4</span><div><b>Try it</b><span>Click the box, press ${k(['Ctrl', 'Space'])} and say: “Ask Milo to check the Pinewood preview on Vercel.”</span></div></li>
    </ol>
    <div class="trybox"><p>Ask Milo to check the Pinewood preview on Vercel.</p><span class="caret"></span></div>
    <p class="fr-ok">${icon('check')}That worked. Pinewood and Vercel came from your vocabulary.</p>
    <div class="acts end"><a class="btn ghost" href="#">Back</a><a class="btn pri" href="#">Finish setup</a></div>
  </div>
</div></div>`;

const css = `
.A{--bg:#F4F6F8;--surf:#FFFFFF;--alt:#EEF2F6;--text:#1F2328;--muted:#5D6778;--line:#E5E7EB;--teal:#0F766E;--teal-ink:#0F766E;--soft:#D8F3EE;--mint:#2DD4BF;--danger:#B42318;--dsoft:#FFF1EE;--dline:#F9C9C0;--warn:#93370D;--wsoft:#FEF0C7;--ind:#4F46C8;--isoft:#EEEEFD;--shadow:0 1px 2px rgba(16,24,40,.05),0 6px 18px rgba(16,24,40,.05);
  font-family:'Figtree',system-ui,sans-serif;color:var(--text);background:var(--bg);width:100%;height:100%;font-size:15px;line-height:1.45}
.A.dark{--bg:#0E1A1F;--surf:#15242B;--alt:#1C2F37;--text:#E8EEF0;--muted:#9AABB2;--line:#26393F;--teal:#14B8A6;--teal-ink:#5EEAD4;--soft:#123C3A;--danger:#FDA29B;--dsoft:#2B1A1A;--dline:#5A2A26;--warn:#FEC84B;--wsoft:#3A2E12;--ind:#A5A6F6;--isoft:#22224A;--shadow:none}
.A .i{width:18px;height:18px;stroke-width:1.8;flex:none}.A .i.sm{width:14px;height:14px}
.A b{font-weight:650}.A .muted{color:var(--muted)}.A .sm{font-size:13px}.A .bad{color:var(--danger)}.A .warn-t{color:var(--warn)}
.A kbd{font:600 12px/1 'Figtree',sans-serif;background:var(--surf);border:1px solid var(--line);border-bottom-width:2px;border-radius:7px;padding:4px 7px;color:var(--text)}
.A .keys{display:inline-flex;gap:3px;vertical-align:middle}
.A .win{display:flex;flex-direction:column;height:100%}
.A .tb{height:34px;display:flex;align-items:center;gap:8px;padding:0 0 0 12px;font-size:12px;color:var(--muted);background:var(--bg);flex:none}
.A .tb-c{margin-left:auto;display:flex}.A .tb-c i{font-style:normal;width:44px;text-align:center;line-height:34px}
.A .body{display:flex;flex:1;min-height:0}
.A .side{width:224px;padding:8px 14px 16px;display:flex;flex-direction:column;flex:none}
.A .brand{display:flex;gap:10px;align-items:center;padding:6px 8px 18px}.A .brand div{display:flex;flex-direction:column;line-height:1.2}.A .brand b{font-size:16px}.A .brand span{font-size:13px;color:var(--teal-ink)}
.A nav a{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:999px;color:var(--text);text-decoration:none;font-weight:550;margin-bottom:2px}
.A nav a.on{background:var(--soft);color:var(--teal-ink)}
.A nav a em{margin-left:auto;font-style:normal;font-size:12px;font-weight:700;background:var(--ind);color:#fff;border-radius:999px;padding:1px 8px}
.A .side-foot{margin-top:auto;display:flex;flex-direction:column;gap:12px;padding:0 8px}
.A .sf-row{display:flex;gap:10px;font-size:13px;color:var(--muted);line-height:1.35}.A .sf-row b{color:var(--text);font-weight:600}
.A .sf-keys{display:flex;justify-content:space-between;align-items:center;font-size:13px;color:var(--muted)}
.A .main{flex:1;min-width:0;background:var(--bg);padding:6px 28px 20px 12px;overflow:hidden}
.A .ph{display:flex;justify-content:space-between;align-items:flex-start;margin:6px 0 16px}
.A .ph h1{font-size:26px;font-weight:700;letter-spacing:-.01em}.A .ph p{color:var(--muted);margin-top:2px}
.A .ph-r{display:flex;gap:8px;align-items:center}
.A .status{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:5px 12px;font-weight:650;font-size:14px}
.A .status.warn{background:var(--wsoft);color:var(--warn)}.A .status.ok{background:var(--soft);color:var(--teal-ink)}.A .status.sm{font-size:12px;padding:3px 9px}
.A .card{background:var(--surf);border-radius:18px;padding:16px 18px;box-shadow:var(--shadow);border:1px solid var(--line)}
.A.dark .card{border-color:var(--line)}
.A h2{font-size:15px;font-weight:700;display:flex;gap:8px;align-items:baseline;margin-bottom:6px}
.A h2.lbl{font-size:13px;color:var(--muted);font-weight:650;margin:16px 4px 8px}.A .col>h2.lbl:first-child{margin-top:0}
.A .home-grid{display:grid;grid-template-columns:1fr 300px;gap:20px}
.A .col{display:flex;flex-direction:column;gap:12px;min-width:0}.A .col>h2.lbl{margin-bottom:-4px}
.A .fail,.A .sug{display:flex;gap:14px;padding:14px 16px}
.A .fail{background:var(--dsoft);border-color:var(--dline)}.A .sug{background:var(--isoft);border-color:transparent}
.A .fc-ic{width:34px;height:34px;border-radius:12px;display:grid;place-items:center;flex:none;background:var(--surf);color:var(--danger)}.A .sug .fc-ic{color:var(--ind)}
.A .fc-b{flex:1;min-width:0}.A .fc-t{display:flex;justify-content:space-between;gap:12px;align-items:baseline}.A .fc-t span{font-size:13px;color:var(--muted);flex:none}
.A .fail .fc-t b{color:var(--danger)}.A .fc-b p{font-size:14px;margin-top:2px}
.A .acts{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}.A .acts.end{justify-content:flex-end}
.A .btn{display:inline-flex;align-items:center;gap:7px;height:36px;padding:0 15px;border-radius:999px;background:var(--alt);color:var(--text);text-decoration:none;font-weight:600;font-size:14px;white-space:nowrap}
.A .btn.pri{background:var(--teal);color:#fff}.A.dark .btn.pri{color:#06201D}.A .btn.pri.ind{background:var(--ind)}.A.dark .btn.pri.ind{color:#12123A}
.A .btn.ghost{background:transparent;color:var(--muted)}.A .btn.sm{height:30px;padding:0 12px;font-size:13px}.A .btn.danger{color:var(--danger)}.A .btn.wide{justify-content:center}
.A .btn .i{width:16px;height:16px}
.A .chip{display:inline-flex;align-items:center;gap:5px;font-size:13px;font-weight:600;background:var(--alt);border-radius:999px;padding:3px 10px;white-space:nowrap}
.A .chip.app{background:var(--soft);color:var(--teal-ink)}.A .chip .i{width:13px;height:13px}
.A .chip.ind{background:var(--isoft);color:var(--ind)}.A .chip.tg{background:#E3F2FB;color:#1D5F86}
.A .last{padding:18px 20px}
.A .lc-meta{display:flex;gap:8px;align-items:center;font-size:13px}.A .lc-meta .muted{margin-left:auto}
.A .lc-text{font-size:21px;line-height:1.45;margin:12px 0 4px;font-weight:500;letter-spacing:-.005em;max-width:60ch}
.A mark{background:none;color:var(--teal-ink);font-weight:650;box-shadow:inset 0 -2px 0 var(--mint)}
.A .det{margin-top:14px;border-top:1px solid var(--line);padding-top:10px}
.A .det summary{list-style:none;display:flex;gap:8px;align-items:center;cursor:pointer}.A .det summary::-webkit-details-marker{display:none}
.A .det summary span{color:var(--muted);font-size:13px}
.A .det-grid{display:grid;grid-template-columns:1.2fr 1.3fr .7fr;gap:20px;margin-top:10px}
.A h3{font-size:12.5px;font-weight:650;color:var(--muted);margin-bottom:6px}
.A .raw{font-size:14px;color:var(--muted);line-height:1.5}
.A .chg{list-style:none;display:flex;flex-direction:column;gap:5px;font-size:14px}.A .chg li{display:flex;align-items:center;gap:6px}
.A s{color:var(--muted)}.A .chg li span{margin-left:auto;font-size:12px;color:var(--muted)}
.A .tim{display:grid;grid-template-columns:1fr auto;gap:4px 10px;font-size:14px}.A .tim dd{font-weight:650;font-variant-numeric:tabular-nums;text-align:right}
.A .target .tg-row{display:flex;align-items:center;gap:6px;margin:10px 0}
.A .sel{display:inline-flex;align-items:center;gap:7px;height:38px;padding:0 12px;border:1px solid var(--line);border-radius:12px;background:var(--surf);font-weight:600}
.A .sel.sm{height:30px;font-size:13px;border-radius:10px}.A .sel.wide{width:100%;justify-content:space-between;margin:6px 0 12px}
.A .arrow{color:var(--muted)}
.A .mini p{font-size:14px}
/* narrow */
.A .ntop{height:52px;display:flex;align-items:center;gap:10px;padding:0 14px;background:var(--bg);border-bottom:1px solid var(--line);flex:none}
.A .ntop b{font-size:17px}.A .ntop .status{margin-left:auto}
.A .nbtn{display:inline-flex;align-items:center;gap:6px;height:36px;padding:0 12px 0 10px;border-radius:999px;background:var(--surf);border:1px solid var(--line);color:var(--text);text-decoration:none;font-weight:600;font-size:14px}
.A .nmain{padding:14px;display:flex;flex-direction:column;gap:12px}
.A .nready{font-size:14px;color:var(--muted)}
.A .narrow .lc-text{font-size:18px}.A .narrow .fc-t{flex-direction:column;gap:0}.A .narrow h2.lbl{margin:4px 4px -4px}
.A .slim{padding:12px 16px}.A .slim .tg-row{margin:4px 0 0}
.A .dim{filter:saturate(.8)}
.A .scrim{position:absolute;inset:34px 0 0 0;background:rgba(16,24,40,.35)}
.A .drawer{position:absolute;top:34px;left:0;bottom:0;width:300px;background:var(--surf);padding:12px 14px 18px;display:flex;flex-direction:column;box-shadow:8px 0 30px rgba(16,24,40,.18);border-radius:0 18px 18px 0}
.A .drawer .brand{padding:4px 4px 16px}.A .drawer .close{margin-left:auto}
/* pill */
.A .desk{position:relative;width:100%;height:100%;background:linear-gradient(160deg,#DCE7EA,#C9D8DE 60%,#C3CDE6);overflow:hidden}
.A .bgwin{position:absolute;left:40px;top:40px;width:560px;height:700px;background:rgba(255,255,255,.75);border-radius:14px;padding:16px;box-shadow:0 10px 40px rgba(16,24,40,.08)}
.A .bgwin.wide{width:900px;height:560px}
.A .gh-bar{height:18px;border-radius:6px;background:#E6ECEF;margin-bottom:22px}.A .gh-l{height:12px;border-radius:6px;background:#E6ECEF;margin:12px 0;width:80%}.A .gh-l.s{width:55%}
.A .gh-in{position:absolute;left:16px;right:16px;bottom:16px;height:64px;border:1.5px solid var(--teal);border-radius:12px;background:#fff}
.A .caret{display:inline-block;width:2px;height:22px;background:var(--text);margin:20px 16px}
.A .pill-cols{position:absolute;left:150px;right:40px;top:40px;bottom:30px;display:grid;grid-template-columns:480px 1fr;gap:22px}
.A .pcol h3{font-size:14px;color:var(--text);margin-bottom:12px;background:rgba(255,255,255,.7);display:inline-block;padding:4px 10px;border-radius:999px}
.A .prow{display:flex;align-items:center;gap:12px;margin-bottom:16px}.A .pcap{width:88px;font-size:13px;font-weight:650;color:#344054;text-align:right;flex:none}
.A .pill{display:flex;align-items:center;gap:12px;background:#fff;border-radius:999px;padding:8px 16px 8px 8px;box-shadow:0 10px 28px rgba(15,61,58,.18),0 0 0 1px rgba(16,24,40,.06);color:#1F2328}
.A .pm{display:block;width:30px;height:30px;flex:none}
.A .pl-t{display:flex;flex-direction:column;line-height:1.2;white-space:nowrap}.A .pl-t b{font-size:14.5px}.A .pl-t span{font-size:12px;color:#5D6778}
.A .lvl{display:flex;align-items:center;gap:2px;height:22px}.A .lvl i{display:block;width:3px;border-radius:2px;background:linear-gradient(#2DD4BF,#0F766E)}
.A .hint{font-size:12px;color:#5D6778;display:flex;gap:5px;align-items:center}.A .hint kbd{font-size:11px;padding:3px 5px}
.A .spin{width:18px;height:18px;border-radius:50%;border:2.5px solid #D8F3EE;border-top-color:#0F766E;margin-left:6px}
.A .pill.ok{background:#fff}.A .ok-ic{width:24px;height:24px;border-radius:50%;background:#0F766E;color:#fff;display:grid;place-items:center}.A .ok-ic .i{width:15px;height:15px;stroke-width:2.4}
.A .pnote{font-size:13px;color:#344054;max-width:330px;margin:8px 0 0 100px}
.A .pill.err{flex-direction:column;align-items:stretch;border-radius:20px;padding:12px 14px 14px;gap:0;margin-bottom:12px;max-width:520px;box-shadow:0 10px 28px rgba(80,20,10,.16),0 0 0 1.5px #F9C9C0}
.A .pe-top{display:flex;gap:12px;align-items:center}.A .pe-top b{color:#B42318}.A .pe-x{margin-left:auto;color:#5D6778}
.A .pill.err p{font-size:13.5px;margin:6px 0 0 42px}.A .pill.err .acts{margin:8px 0 0 42px}
/* tray */
.A .tdesk .bgwin{left:30px;top:30px}
.A .tray-pair{position:absolute;right:24px;bottom:62px;display:flex;gap:22px;align-items:flex-end}
.A .tcap{font-size:13px;font-weight:650;color:#344054;margin:0 0 8px 4px}
.A .tray{width:340px;background:#fff;border-radius:18px;padding:14px;box-shadow:0 18px 50px rgba(16,24,40,.22);color:#1F2328}
.A .tray.calm{width:300px}
.A .tr-h{display:flex;align-items:center;gap:10px;padding-bottom:10px}.A .tr-h .status{margin-left:auto}
.A .tr-prob{background:#FFF1EE;border-radius:14px;padding:10px 12px;display:flex;flex-direction:column}.A .tr-prob b{color:#B42318}.A .tr-prob span{font-size:13px;color:#5D6778}.A .tr-prob .acts{margin-top:8px}
.A .tr-last{padding:12px 4px;border-bottom:1px solid #E5E7EB}.A .tr-last p{font-size:14px;margin:3px 0 8px}
.A .tr-items{display:flex;flex-direction:column;padding-top:6px}.A .tr-items a{display:flex;align-items:center;gap:10px;padding:8px 6px;border-radius:10px;color:#1F2328;text-decoration:none;font-weight:550;font-size:14px}
.A .tr-items a em{margin-left:auto;font-style:normal;color:#0F766E;display:flex;align-items:center;gap:4px;font-weight:650}
.A .tr-items a.q{color:#5D6778;padding-left:34px}
.A .taskbar{position:absolute;left:0;right:0;bottom:0;height:48px;background:rgba(243,246,248,.92);display:flex;justify-content:flex-end;align-items:center;gap:18px;padding:0 18px;border-top:1px solid #D0D5DD}
.A .tk-ic{position:relative;padding:6px;border-radius:8px;background:#E3E8EC}.A .tk-ic .amber{position:absolute;right:2px;bottom:2px;width:10px;height:10px;border-radius:50%;background:#F79009;border:2px solid #fff}
.A .tk-t{font-size:12px;text-align:right;line-height:1.3}
/* history */
.A .toolbar{display:flex;gap:8px;align-items:center;margin-bottom:12px}
.A .search{flex:1;display:flex;align-items:center;gap:10px;height:42px;border-radius:999px;background:var(--surf);border:1px solid var(--line);padding:0 16px;color:var(--muted)}
.A .fpill{display:inline-flex;align-items:center;gap:6px;height:42px;padding:0 14px;border-radius:999px;background:var(--surf);border:1px solid var(--line);font-weight:600;font-size:14px}
.A .fpill.bad{color:var(--danger)}.A .fpill em{font-style:normal;background:var(--dsoft);border-radius:999px;padding:0 7px;font-size:12px}
.A .hlist{background:var(--surf);border:1px solid var(--line);border-radius:18px;padding:4px 8px 6px;box-shadow:var(--shadow)}
.A .hday{font-size:13px;color:var(--muted);padding:12px 12px 4px}
.A .hrow{display:grid;grid-template-columns:52px 120px 66px 1fr auto;gap:12px;align-items:center;padding:9px 12px;border-radius:12px}
.A .hrow+.hrow{box-shadow:0 -1px 0 var(--line)}
.A .hrow.f{background:var(--dsoft);box-shadow:none}
.A .hrow .chip{justify-self:start}.A .hrow.h{background:var(--wsoft);box-shadow:none}.A .ht{font-variant-numeric:tabular-nums;color:var(--muted);font-size:14px}.A .hs{font-size:13px;color:var(--muted)}
.A .htext{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:14.5px}
.A .hacts{display:flex;gap:4px;align-items:center}.A .ib{width:32px;height:32px;display:grid;place-items:center;border-radius:50%;color:var(--muted)}
.A .hmore{text-align:center;color:var(--muted);font-size:13px;padding:8px}
/* vocab */
.A .badge-c{display:inline-flex;align-items:center;gap:7px;background:var(--soft);color:var(--teal-ink);border-radius:999px;padding:7px 14px;font-weight:650;font-size:14px}
.A .packbar{display:flex;gap:18px;align-items:center;font-size:14px;color:var(--muted);margin-bottom:14px;padding:0 4px}.A .packbar b{color:var(--text)}.A .packbar .btn{margin-left:auto}
.A .vgrid{display:grid;grid-template-columns:1.25fr 1fr;gap:16px}
.A .t{width:100%;border-collapse:collapse;font-size:14px}.A .t th{text-align:left;font-size:12.5px;color:var(--muted);font-weight:650;padding:6px 8px;border-bottom:1px solid var(--line)}.A .t td{padding:7px 8px;border-bottom:1px solid var(--line)}
.A .t tr:last-child td{border-bottom:0}.A .more{padding:6px 8px 0}
.A .corr{list-style:none;display:flex;flex-direction:column;gap:8px;margin-top:8px;font-size:14px}.A .corr li{display:flex;align-items:center;gap:6px}.A .corr .chip{margin-left:auto;font-size:12px}
.A .rules{list-style:none;display:flex;flex-direction:column;gap:7px;font-size:14px}.A .rules li{display:flex;justify-content:space-between;gap:12px}.A .rules span{color:var(--muted);text-align:right}
.A .owner{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:14px}.A .owner>div{border:1.5px dashed var(--line);border-radius:18px;padding:12px 16px}
.A .owner h3{display:flex;gap:8px;align-items:center;color:var(--text);font-size:14px;margin:0}.A .owner p{font-size:14px;color:var(--muted);margin-left:26px}
/* suggestions */
.A .sgrid{display:grid;grid-template-columns:1.5fr 1fr;gap:18px}
.A .sq{padding:20px 22px}.A .sq-top{display:flex;gap:8px}
.A .sq-q{font-size:24px;line-height:1.25;margin:12px 0;font-weight:700;letter-spacing:-.01em}
.A .sq.second .sq-q{font-size:19px}
.A .clips{list-style:none;display:flex;flex-direction:column;gap:6px;margin-bottom:8px}.A .clips li{display:flex;gap:10px;align-items:center;font-size:14px;background:var(--alt);border-radius:12px;padding:7px 12px}.A .clips .i{color:var(--teal-ink);width:15px;height:15px}
.A .ans{display:grid;gap:2px 0;font-size:14px}.A .ans dt{font-weight:650;margin-top:8px}.A .ans dd{color:var(--muted)}.A .ans+p{margin-top:12px}
/* styles */
.A .stgrid{display:grid;grid-template-columns:1.6fr 1fr;gap:18px}
.A .said{background:var(--alt);border-radius:12px;padding:8px 12px;font-size:14px;margin:6px 0 10px;display:flex;flex-direction:column}
.A .stlist{list-style:none;display:flex;flex-direction:column;gap:6px}
.A .stlist li{display:grid;grid-template-columns:130px 1fr;gap:2px 14px;padding:9px 12px;border-radius:14px;border:1px solid var(--line)}
.A .stlist li.on{border:1.5px solid var(--teal);background:var(--soft)}
.A .st-n{grid-row:span 2;display:flex;flex-direction:column}.A .st-n span{font-size:13px;color:var(--muted)}.A .st-n em{font-style:normal;font-size:12px;color:var(--teal-ink);font-weight:650}
.A .stlist p{font-size:14.5px}
.A .pa td{padding:8px}
/* settings */
.A .setg{display:grid;grid-template-columns:1.1fr 1fr 1fr;gap:14px}.A .setg .col{gap:12px}.A .setg .card{padding:14px 16px}
.A .hk{list-style:none;display:flex;flex-direction:column;gap:9px;font-size:14px;margin:6px 0 12px}.A .hk li{display:flex;justify-content:space-between;align-items:center;gap:8px}
.A .micl .lvl{height:26px}.A .micl .lvl i{width:5px}
.A .rad{display:flex;gap:10px;padding:8px 10px;border-radius:12px;font-size:13.5px;margin-top:4px}.A .rad.on{background:var(--soft)}
.A .rad b{display:block;font-size:14px}.A .r{width:16px;height:16px;border-radius:50%;border:2px solid var(--muted);flex:none;margin-top:2px}.A .rad.on .r{border:5px solid var(--teal)}
.A .setg p{font-size:14px;margin-bottom:6px}.A .setg .status{margin-left:6px}
.A .kv{display:grid;grid-template-columns:auto 1fr;gap:8px 12px;align-items:center;font-size:14px;margin:6px 0 10px}
/* first run */
.A .fr{flex:1;display:grid;place-items:center;background:radial-gradient(circle at 20% 10%,#E3F6F2,transparent 50%),var(--bg)}
.A .fr-card{width:640px;background:var(--surf);border-radius:24px;padding:28px 32px;box-shadow:0 20px 60px rgba(16,24,40,.10);border:1px solid var(--line)}
.A .fr-h{display:flex;gap:16px;align-items:center;margin-bottom:18px}.A .fr-h h1{font-size:26px;letter-spacing:-.01em}
.A .steps{list-style:none;display:flex;flex-direction:column;gap:10px}
.A .steps li{display:flex;gap:14px;align-items:flex-start}.A .steps li div{display:flex;flex-direction:column}.A .steps li span:not(.sn){font-size:14px;color:var(--muted)}
.A .sn{width:28px;height:28px;border-radius:50%;display:grid;place-items:center;font-weight:700;font-size:14px;flex:none;background:var(--soft);color:var(--teal-ink)}.A .sn .i{width:15px;height:15px;stroke-width:2.4}
.A .steps li.now .sn{background:var(--teal);color:#fff}
.A .trybox{margin:16px 0 8px 42px;border:1.5px solid var(--teal);border-radius:14px;padding:12px 14px;min-height:70px;display:flex;align-items:flex-start}
.A .trybox p{font-size:17px}.A .trybox .caret{margin:3px 0 0 2px;height:20px}
.A .fr-ok{display:flex;gap:8px;align-items:center;color:var(--teal-ink);font-weight:600;font-size:14px;margin-left:42px}
`;

export function render() {
  const homeFrame = (dark) => `<div class="A ${dark ? 'dark' : ''}">${app('home', homeMain())}</div>`;
  const frameHtml = {
    pill: `<div class="A">${pillFrame()}</div>`,
    tray: `<div class="A">${trayFrame()}</div>`,
    home: homeFrame(false),
    'home-narrow': `<div class="A">${homeNarrow()}</div>`,
    'menu-narrow': `<div class="A" style="position:relative">${menuNarrow()}</div>`,
    'home-alt': homeFrame(true),
    history: `<div class="A">${app('history', historyMain())}</div>`,
    vocab: `<div class="A">${app('vocab', vocabMain())}</div>`,
    suggest: `<div class="A">${app('suggest', suggestMain())}</div>`,
    styles: `<div class="A">${app('styles', stylesMain())}</div>`,
    settings: `<div class="A">${app('settings', settingsMain())}</div>`,
    firstrun: `<div class="A">${firstRun()}</div>`,
  };
  return shell({
    title: 'VoiceFlow, direction A: Calm Teal',
    fonts: 'https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700;800&display=swap',
    css, frameHtml,
    intro: 'Continues the Android app: light first, soft teal, 18 px rounded cards and pill buttons. Static mockup with demo content; every screen uses the same structure as directions B and C.',
  });
}
