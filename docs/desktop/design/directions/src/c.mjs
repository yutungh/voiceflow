// Direction C, "Paper Quiet": text first. Warm neutral paper, a serif for what you said, a grotesk for the controls, teal
// as ink. Corrections are shown the way an editor marks a proof: the fixed word in teal with a note in the margin.
import { esc, icon, logo, nav, last, cleaned, changes, failure, suggestions, history, terms, corrections, appRules, pack, styles, styleInput, perApp, blocked, hotkeys, shell } from './data.mjs';

const k = (keys) => `<span class="keys">${keys.map((x) => `<kbd>${esc(x)}</kbd>`).join('<i>+</i>')}</span>`;
let n = 0;
const uid = (p) => `${p}${++n}`;
const btn = (label, cls = '') => `<a class="btn ${cls}" href="#">${label}</a>`;

// The motif as a level meter: one sine stroke whose height follows the voice.
const wave = (w = 120, h = 22, amp = [0.3, 0.8, 1, 0.6, 0.9, 0.4, 0.7, 0.2]) => {
  const seg = w / amp.length;
  let d = `M0 ${h / 2}`;
  amp.forEach((a, i) => { const x0 = i * seg; const up = (i % 2 ? 1 : -1) * a * (h / 2 - 2); d += ` C${x0 + seg * 0.3} ${h / 2 + up} ${x0 + seg * 0.7} ${h / 2 + up} ${x0 + seg} ${h / 2}`; });
  return `<svg class="wave" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true"><path d="${d}" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>`;
};

const titlebar = (t = 'VoiceFlow') => `<div class="tb">${logo(16, uid('tb'))}<span>${t}</span><span class="tb-c"><i>–</i><i>□</i><i>✕</i></span></div>`;

const side = (active) => `<aside class="side">
  <div class="word">${logo(26, uid('w'))}<span>VoiceFlow</span></div>
  <nav aria-label="Main">${nav.map((x) => `<a class="${x.id === active ? 'on' : ''}" href="#">${x.label}${x.badge ? `<em>${x.badge} waiting</em>` : ''}</a>`).join('')}</nav>
  <p class="colophon"><span class="st warn">One problem.</span> Vocabulary synced at 07:12. Dictate with ${k(['Ctrl', 'Space'])}</p>
</aside>`;

const app = (active, main) => `<div class="win">${titlebar()}<div class="body">${side(active)}<main class="main">${main}</main></div></div>`;

const proof = () => { let i = 0; return last.segs.map((s) => (typeof s === 'string' ? esc(s) : `<span class="pf">${esc(s.to)}<sup>${++i}</sup></span>`)).join(''); };

const notice = (narrow = false) => `<div class="notice nb"><p><b>${failure.title}.</b> Telegram, ${failure.time}. ${narrow ? '' : 'The clean-up model did not answer in 10 seconds; your words are kept.'}</p><div class="acts">${btn('Retry clean-up', 'pri')}${btn('Paste raw to Telegram')}${narrow ? '' : btn('Dismiss', 'quiet')}</div></div>`;
const sugNotice = () => `<div class="notice ni"><p><b>A question about a word.</b> VoiceFlow heard “Versal” three times. Did you mean Vercel?</p><div class="acts">${btn('Answer it')}<span class="aside">1 more waiting</span></div></div>`;

const homeMain = () => `<div class="sheet2">
  <div class="colm">
    <p class="dateline">Monday, November 16</p>
    <h1>Ready. Press ${k(['Ctrl', 'Space'])} anywhere to dictate.</h1>
    <h2 class="sh">Needs you</h2>
    ${notice()}${sugNotice()}
    <h2 class="sh">Last dictation</h2>
    <blockquote class="said">${proof()}</blockquote>
    <div class="acts">${btn('Copy')}${btn('Play audio')}${btn('Re-run clean-up')}</div>
    <details class="fold" open><summary>What you actually said</summary><p class="raw">“${esc(last.raw)}”</p></details>
  </div>
  <aside class="marg">
    <div class="mnote"><h3>Corrections</h3><ol>${changes.map((c) => `<li><s>${esc(c.from)}</s> → ${esc(c.to)}<span>${c.why}</span></li>`).join('')}</ol></div>
    <div class="mnote"><h3>About this one</h3><dl><dt>Pasted into</dt><dd>Kite at ${last.time}</dd><dt>Style</dt><dd>Casual</dd><dt>Took</dt><dd>${last.latency.total} (transcribe ${last.latency.transcribe}, clean-up ${last.latency.cleanup})</dd></dl></div>
    <div class="mnote tgt"><h3>Last target app</h3><p class="sm">VoiceFlow is in front now, so this is the app you last dictated into.</p><p class="pick"><span class="sel">Kite${icon('down', 'i sm')}</span> uses <span class="sel">Casual${icon('down', 'i sm')}</span></p></div>
  </aside>
</div>`;

const ntop = () => `<div class="ntop"><span class="word sm">${logo(20, uid('nw'))}<span>VoiceFlow</span></span><span class="st warn">One problem</span><a class="nbtn" href="#">${icon('menu')}Contents</a></div>`;
const homeNarrow = () => `<div class="win">${titlebar()}${ntop()}<main class="main nm">
  <h1 class="nh">Ready. Press ${k(['Ctrl', 'Space'])} to dictate.</h1>
  <h2 class="sh">Needs you</h2>${notice(true)}
  <h2 class="sh">Last dictation</h2><blockquote class="said">${proof()}</blockquote>
  <p class="meta-l">Kite at ${last.time}, Casual, ${last.latency.total}. <a href="#">4 corrections</a></p>
  <p class="pick"><span class="muted">Last target app</span> <span class="sel">Kite${icon('down', 'i sm')}</span> uses <span class="sel">Casual${icon('down', 'i sm')}</span></p>
</main></div>`;
const menuNarrow = () => `<div class="win">${titlebar()}<div class="ntop"><span class="word sm">${logo(20, uid('nw'))}<span>VoiceFlow</span></span><a class="nbtn" href="#">${icon('x')}Close</a></div>
<main class="main nm contents"><p class="dateline">Contents</p><ol class="toc">${nav.map((x) => `<li class="${x.id === 'home' ? 'on' : ''}"><a href="#">${x.label}</a>${x.badge ? `<em>${x.badge} waiting</em>` : ''}</li>`).join('')}</ol>
<p class="colophon"><span class="st warn">One problem:</span> clean-up failed in Telegram at 10:42.</p><p class="colophon">Vocabulary synced at 07:12.</p>${btn('Pause dictation')}</main></div>`;

// pill
const slip = (cls, body) => `<div class="slip ${cls}">${logo(22, uid('s'))}${body}</div>`;
const errSlip = (title, where, body, actions) => `<div class="slip err"><div class="se-h">${logo(22, uid('s'))}<b>${title}</b><span class="x">${icon('x')}</span></div><p><span class="where">${where}.</span> ${body}</p><div class="acts">${actions}</div></div>`;
const pillFrame = () => `<div class="desk"><div class="dpage"><div class="dl"></div><div class="dl s"></div><div class="dl"></div><div class="dl m"></div></div>
<div class="spec">
  <div class="spcol"><h2 class="sh">While you talk</h2>
    <div class="sprow"><span class="spl">Listening</span>${slip('', `<i class="t">Listening</i><span class="tl">${wave(96)}</span><span class="g">Kite</span><span class="g">${k(['Ctrl', 'Space'])} to stop</span>`)}</div>
    <div class="sprow"><span class="spl">Transcribing</span>${slip('', `<i class="t">Writing it down…</i><span class="g">0:09 of audio</span>`)}</div>
    <div class="sprow"><span class="spl">Cleaning</span>${slip('', `<i class="t">Tidying, in Casual…</i>`)}</div>
    <div class="sprow"><span class="spl">Pasted</span>${slip('ok', `<span class="okm">${icon('check')}</span><i class="t">Pasted to Kite · Casual</i>`)}</div>
    <p class="spnote">The slip sits at the bottom centre of the screen, never takes focus, and leaves two seconds after a paste.</p>
  </div>
  <div class="spcol"><h2 class="sh">When something goes wrong, it stays until you act</h2>
    ${errSlip('Transcription failed', 'Kite, 18:02', 'OpenAI did not answer. The audio is saved on this PC.', `${btn('Retry transcription', 'pri')}${btn('Dismiss', 'quiet')}`)}
    ${errSlip('Clean-up failed — nothing pasted', 'Telegram, 10:42', 'Clean-up timed out, so Telegram got nothing.', `${btn('Retry clean-up', 'pri')}${btn('Paste raw to Telegram')}${btn('Dismiss', 'quiet')}`)}
    ${errSlip('Paste blocked (password field)', 'Chrome', 'The cursor is in a password field; the text stays in VoiceFlow.', `${btn('Copy text')}${btn('Dismiss', 'quiet')}`)}
    ${errSlip('Target app unknown — Send anyway?', 'Audio kept on this PC', 'VoiceFlow could not tell which app is in front, so nothing was sent.', `${btn('Send anyway, this dictation only', 'pri')}${btn('Dismiss', 'quiet')}`)}
  </div>
</div></div>`;

const trayMenu = (problem) => `<div class="tray">
  <div class="tr-h">${logo(20, uid('t'))}<span class="tw">VoiceFlow</span>${problem ? '<span class="st warn">1 problem</span>' : '<span class="st ok">All fine</span>'}</div>
  ${problem ? `<div class="tr-p"><p><b>Clean-up failed in Telegram</b> at 10:42. Nothing was pasted.</p><div class="acts">${btn('Retry clean-up', 'pri sm')}${btn('Paste raw to Telegram', 'sm')}</div></div>` : ''}
  <div class="tr-l"><span class="g">Last pasted, Kite at 10:58</span><p>${esc(cleaned.slice(0, 76).replace(/\s+\S*$/, ''))}…</p><a class="lk" href="#">Copy</a></div>
  <ul class="tr-m"><li><span>Style</span><span class="v">Casual ${icon('chev', 'i sm')}</span></li><li><span>Pause dictation</span></li><li><span>Refresh vocabulary</span></li><li><span>Open VoiceFlow</span></li><li class="q"><span>Quit</span></li></ul>
</div>`;
const trayFrame = () => `<div class="desk"><div class="dpage wide"><div class="dl"></div><div class="dl s"></div></div>
<div class="tpair"><div><p class="tcap">With a problem: amber mark on the icon, the problem first</p>${trayMenu(true)}</div><div><p class="tcap">All fine</p>${trayMenu(false)}</div></div>
<div class="taskbar"><span class="tk">${logo(20, uid('tk'))}<i></i></span><span class="tk-t">10:59 AM<br>11/16/2026</span></div></div>`;

const hentry = (r) => {
  const body = r.fail === 'transcribe' ? '<p class="ht"><b class="bad">Transcription failed.</b> <span class="muted">The audio is saved.</span></p>' : r.fail === 'cleanup' ? `<p class="ht"><b class="bad">Clean-up failed, nothing pasted.</b> <span class="muted">“${esc(r.text)}”</span></p>` : r.fail === 'held' ? '<p class="ht"><b class="warn">Held: the app was not identified.</b> <span class="muted">The audio is saved on this PC.</span></p>' : `<p class="ht">${esc(r.text)}</p>`;
  const act = r.fail === 'transcribe' ? btn('Retry transcription', 'pri sm') : r.fail === 'cleanup' ? btn('Retry clean-up', 'pri sm') : r.fail === 'held' ? btn('Send anyway', 'pri sm') : '';
  return `<li class="he ${r.fail ? 'f' : ''}"><span class="hw">${r.t}</span><div>${body}<p class="hm">${esc(r.app)}, ${r.style}, ${r.dur} <a href="#">play</a> <a href="#">copy</a> ${act}</p></div></li>`;
};
const historyMain = () => `<div class="sheet1"><header class="hh"><h1>History</h1><p class="muted">1,642 dictations since June, newest first. Text is kept forever and audio for 30 days. <a href="#">Change</a></p></header>
<div class="find"><span class="fi">${icon('search')}<span>Find a word or phrase</span></span><span class="fl">Showing <a href="#">all apps</a>, <a href="#">any date</a>, <a href="#">any style</a>. <a class="badl" href="#">Failed only (3)</a></span></div>
<div class="ledger">${history.map((g) => `<h2 class="day">${g.day}</h2><ol class="hl">${g.items.map(hentry).join('')}</ol>`).join('')}</div></div>`;

const vocabMain = () => `<div class="sheet1"><header class="hh"><h1>Vocabulary</h1><p class="stamp">${icon('lock', 'i sm')}Managed by Atlas — ask Milo to change</p>
<p class="muted">Pack ${pack.version}, synced today at 07:12: ${pack.terms} terms, ${pack.corrections} corrections, ${pack.rules} app rules. <a href="#">Check for a new pack</a></p></header>
<div class="vg"><section><h2 class="sh">Terms</h2><dl class="gloss">${terms.map((t) => `<div><dt>${t.text} <i>${t.kind.toLowerCase()}</i></dt><dd>heard as ${t.heard.join(', ')}; ${t.apps.toLowerCase() === 'all apps' ? 'everywhere' : `in ${t.apps}`}</dd></div>`).join('')}</dl><p class="muted sm">and 204 more</p></section>
<section><h2 class="sh">Approved corrections</h2><ul class="cl">${corrections.map((c) => `<li><s>${c.from}</s> → <b>${c.to}</b><span>${c.scope}</span></li>`).join('')}</ul>
<h2 class="sh">App rules</h2><ul class="rl">${appRules.map((r) => `<li><b>${r.app}.</b> ${r.rule}.</li>`).join('')}</ul>
<h2 class="sh">Who owns what</h2><p class="sm"><b>Atlas</b> owns the terms, corrections, app rules and style wording. <b>This PC</b> owns hotkeys, the microphone, the OpenAI key and the style per app.</p></section></div></div>`;

const suggestMain = () => { const s = suggestions[0]; return `<div class="sheet1 sug"><header class="hh"><h1>Suggestions</h1><p class="muted">Words VoiceFlow keeps mishearing. Nothing changes until you answer. Two are waiting.</p></header>
<article class="qa"><p class="kick">${icon('telegram', 'i sm')} Also asked in Telegram; answer in either place.</p>
<h2 class="bigq">VoiceFlow heard “${s.heard}” three times. Did you mean ${s.guess}?</h2>
<ul class="clips">${s.clips.map((c, i) => `<li><a href="#">${icon('play', 'i sm')}</a>${c}<span>${['Kite, today', 'Claude Code, today', 'Kite, Nov 14'][i]}</span></li>`).join('')}</ul>
<div class="acts lg">${btn('Approve as correction', 'pri')}${btn('Hint only')}${btn('No', 'quiet')}</div>
<p class="sm muted expl">A correction rewrites “Versal” to “Vercel” every time. A hint only teaches VoiceFlow the word Vercel. No means it is never asked again.</p></article>
<div class="next"><h2 class="sh">Also waiting</h2><p>“Zefira”, twice in Telegram. Zephyra? <a href="#">Answer</a></p><h2 class="sh">Answered lately</h2><p class="sm muted">Mylo became a correction to Milo on Nov 12. Juno was a no on Nov 9. Kubernetes became a hint on Nov 3.</p></div></div>`; };

const stylesMain = () => `<div class="sheet2"><div class="colm"><h1 class="pt">Styles</h1><p class="muted">One sentence, as each style would paste it. The wording of each style comes from Atlas.</p>
<p class="youSaid">You said: <i>“${esc(styleInput)}”</i></p>
<ol class="spec2">${styles.map((s, i) => `<li class="${i === 0 ? 'on' : ''}"><div class="sn"><b>${s.label}</b>${s.sub ? `<span>for ${s.sub.toLowerCase()}</span>` : ''}${i === 0 ? '<span class="def">default</span>' : ''}</div><div><p class="sx">${esc(s.sample)}</p><p class="sm muted">${s.note}</p></div></li>`).join('')}</ol></div>
<aside class="marg"><div class="mnote"><h3>Style per app, on this PC</h3><ul class="pa">${perApp.map((p) => `<li><span>${p.app}</span><span class="sel sm">${p.style}${icon('down', 'i sm')}</span></li>`).join('')}</ul><p class="sm muted">For one dictation only, press ${k(['Ctrl', 'Shift', 'X'])}</p></div>
<div class="mnote"><p class="stamp sm">${icon('lock', 'i sm')}Style wording: ask Milo to change</p></div></aside></div>`;

const settingsMain = () => `<div class="sheet1"><header class="hh"><h1>Settings</h1><p class="muted">Only what belongs to this PC. Words and styles are in Atlas.</p></header>
<div class="setg"><div>
<section class="sg"><h2 class="sh">Hotkeys</h2><dl class="kv">${hotkeys.map((h) => `<dt>${h.name}</dt><dd>${k(h.keys)}</dd>`).join('')}</dl><a class="lk" href="#">Change a hotkey</a></section>
<section class="sg"><h2 class="sh">Microphone</h2><p><span class="sel">USB microphone (default)${icon('down', 'i sm')}</span> <span class="tl">${wave(90, 18)}</span></p></section>
<section class="sg"><h2 class="sh">OpenAI key</h2><p>Saved in Windows Credential Manager. <a class="lk" href="#">Replace</a></p></section>
<section class="sg"><h2 class="sh">Vocabulary account</h2><p>Signed in as <b>vf-me</b> on your NAS. Synced at 07:12; checks hourly.</p></section>
</div><div>
<section class="sg"><h2 class="sh">Privacy mode</h2>
<p class="rad on"><span class="r"></span><span><b>Strict.</b> If VoiceFlow cannot confirm a field is not a password, it keeps the audio and asks first.</span></p>
<p class="rad"><span class="r"></span><span><b>Best-effort.</b> It blocks only when it actually detects a password field.</span></p>
<p class="sm muted">Always blocked, from Atlas: ${blocked.join(', ')}.</p></section>
<section class="sg"><h2 class="sh">History and audio</h2><p>Keep text <span class="sel sm">forever${icon('down', 'i sm')}</span> and audio <span class="sel sm">30 days${icon('down', 'i sm')}</span> <a class="lk bad" href="#">Delete history</a></p></section>
<section class="sg"><h2 class="sh">Updates</h2><p>VoiceFlow 1.0.0 is up to date (checked at 07:12).</p></section>
<section class="sg"><h2 class="sh">About</h2><p class="sm">Based on Whispering by Braden Wong. Free software under the AGPL-3.0-or-later licence. <a class="lk" href="#">Licence</a> <a class="lk" href="#">Source code</a></p></section>
</div></div></div>`;

const firstRun = () => `<div class="win">${titlebar('Set up VoiceFlow')}<div class="fr"><div class="letter">
<div class="lh">${logo(44, uid('fr'))}<h1>Welcome to VoiceFlow.</h1></div>
<p class="lede">Four short steps, then you can dictate into any app.</p>
<ol class="fsteps">
<li class="done"><b>Microphone.</b> USB microphone; it hears you clearly.</li>
<li class="done"><b>Hotkey.</b> ${k(['Ctrl', 'Space'])} starts and stops a dictation.</li>
<li class="done"><b>Vocabulary.</b> Connected as vf-me; 214 terms arrived from Atlas.</li>
<li class="now"><b>Try it.</b> Click the line below, press ${k(['Ctrl', 'Space'])} and say <i>“Ask Milo to check the Pinewood preview on Vercel.”</i></li>
</ol>
<div class="tryl"><span>Ask Milo to check the <span class="pf">Pinewood</span> preview on <span class="pf">Vercel</span>.</span><span class="caret"></span></div>
<p class="okp">${icon('check', 'i sm')} That worked. Pinewood and Vercel came from your vocabulary.</p>
<div class="acts end">${btn('Back', 'quiet')}${btn('Finish setup', 'pri')}</div></div></div></div>`;

const css = `
.C{--paper:#F6F5F1;--page:#FCFBF8;--ink:#23211D;--muted:#625D55;--rule:#DCD7CC;--faint:#ECE8E0;--teal:#0F766E;--tink:#0D6660;--bad:#A3271B;--warn:#8A5300;--ind:#4643A8;--badbg:#F8E9E4;--indbg:#ECEBF6;
  font-family:'Hanken Grotesk',system-ui,sans-serif;font-size:15px;line-height:1.5;color:var(--ink);background:var(--paper);width:100%;height:100%}
.C.dark{--paper:#1B1A17;--page:#211F1C;--ink:#ECE7DC;--muted:#ACA599;--rule:#3A3731;--faint:#2B2925;--teal:#5FC9BB;--tink:#7AD6C9;--bad:#F09A8C;--warn:#E8B45C;--ind:#B4B2F2;--badbg:#33221E;--indbg:#25243A}
.C .i{width:16px;height:16px;stroke-width:1.6;flex:none}.C .i.sm{width:13px;height:13px}
.C b{font-weight:600}.C .muted{color:var(--muted)}.C .sm{font-size:13.5px}.C .bad{color:var(--bad)}.C .warn{color:var(--warn)}
.C a{color:var(--tink);text-decoration:underline;text-decoration-thickness:1px;text-underline-offset:3px}
.C kbd{font:500 12px/1 'Hanken Grotesk',sans-serif;border:1px solid var(--rule);border-radius:4px;padding:2px 5px;background:var(--page);color:var(--ink)}
.C .keys{display:inline-flex;gap:2px;align-items:center;vertical-align:1px}.C .keys i{font-style:normal;font-size:11px;color:var(--muted)}
.C .win{display:flex;flex-direction:column;height:100%}
.C .tb{height:32px;display:flex;align-items:center;gap:8px;padding-left:12px;font-size:12px;color:var(--muted);flex:none}.C .tb-c{margin-left:auto;display:flex}.C .tb-c i{font-style:normal;width:44px;text-align:center}
.C .body{display:flex;flex:1;min-height:0}
.C .side{width:210px;padding:14px 22px 22px 28px;display:flex;flex-direction:column;flex:none}
.C .word{display:flex;align-items:center;gap:10px;font-family:'Newsreader',serif;font-size:22px;font-weight:500;margin-bottom:34px;letter-spacing:-.01em}
.C .word.sm{font-size:18px;margin:0;gap:8px}
.C .side nav{display:flex;flex-direction:column;gap:2px}
.C .side nav a{color:var(--muted);text-decoration:none;font-size:15px;padding:5px 0 5px 14px;position:relative;display:flex;flex-direction:column;line-height:1.3}
.C .side nav a.on{color:var(--ink);font-weight:600}.C .side nav a.on::before{content:"";position:absolute;left:0;top:7px;bottom:7px;width:2px;background:var(--teal)}
.C .side nav a em{font-style:normal;font-size:12.5px;color:var(--ind);font-weight:500}
.C .colophon{margin-top:auto;font-size:13px;color:var(--muted);line-height:1.6}
.C .st{font-weight:600}.C .st.warn{color:var(--warn)}.C .st.ok{color:var(--tink)}
.C .main{flex:1;min-width:0;background:var(--page);border-left:1px solid var(--rule);overflow:hidden}
.C .sheet2{display:grid;grid-template-columns:minmax(0,640px) 280px;gap:48px;padding:30px 48px 24px 56px}
.C .sheet1{padding:30px 56px 24px}
.C .dateline{font-size:13px;color:var(--muted);margin-bottom:6px}
.C h1{font-family:'Newsreader',serif;font-weight:500;font-size:30px;line-height:1.2;letter-spacing:-.015em}
.C h1 kbd{font-size:15px;vertical-align:4px}
.C .sh{font-family:'Newsreader',serif;font-style:italic;font-weight:400;font-size:18px;color:var(--muted);margin:22px 0 8px;padding-bottom:4px;border-bottom:1px solid var(--rule)}
.C .notice{padding:10px 0 12px;border-bottom:1px solid var(--faint)}.C .notice p{font-size:15px}
.C .notice.nb b{color:var(--bad)}.C .notice.ni b{color:var(--ind)}
.C .acts{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-top:8px}.C .acts.end{justify-content:flex-end}.C .aside{font-size:13px;color:var(--muted)}
.C .btn{display:inline-flex;align-items:center;height:32px;padding:0 13px;border:1px solid var(--rule);border-radius:6px;color:var(--ink);text-decoration:none;font-weight:500;font-size:14px;background:var(--page);white-space:nowrap}
.C .btn.pri{background:var(--teal);border-color:var(--teal);color:#fff}.C.dark .btn.pri{color:#10201E}
.C .btn.quiet{border-color:transparent;color:var(--muted);background:transparent}.C .btn.sm{height:28px;font-size:13px;padding:0 10px}
.C .said{font-family:'Newsreader',serif;font-size:27px;line-height:1.42;letter-spacing:-.005em;font-weight:400;margin:6px 0 4px;max-width:30em}
.C .pf{color:var(--tink);text-decoration:underline;text-decoration-color:var(--teal);text-decoration-thickness:1.5px;text-underline-offset:4px}
.C .pf sup{font-family:'Hanken Grotesk',sans-serif;font-size:11px;font-weight:600;margin-left:1px;text-decoration:none;display:inline-block}
.C .fold{margin-top:16px}.C .fold summary{font-size:14px;color:var(--muted);cursor:pointer}.C .raw{font-family:'Newsreader',serif;font-style:italic;font-size:17px;color:var(--muted);margin-top:6px;max-width:36em}
.C .marg{padding-top:84px;display:flex;flex-direction:column;gap:22px}
.C .mnote{font-size:13.5px;border-top:1px solid var(--rule);padding-top:10px}
.C .mnote h3{font-size:13px;font-weight:600;margin-bottom:6px}
.C .mnote ol{padding-left:18px;display:flex;flex-direction:column;gap:4px}.C .mnote ol li::marker{color:var(--tink);font-weight:600;font-size:12px}
.C .mnote li span{display:block;color:var(--muted);font-size:12.5px}.C s{color:var(--muted)}
.C .mnote dl{display:grid;grid-template-columns:auto 1fr;gap:3px 10px}.C .mnote dt{color:var(--muted)}
.C .sel{display:inline-flex;align-items:center;gap:5px;border-bottom:1px solid var(--ink);padding:1px 2px;font-weight:600}.C .sel.sm{font-size:13.5px}
.C .pick{margin-top:6px}
/* narrow */
.C .ntop{height:50px;display:flex;align-items:center;gap:12px;padding:0 16px;border-bottom:1px solid var(--rule);flex:none}.C .ntop .st{margin-left:auto;font-size:13px}.C .ntop .word+.nbtn{margin-left:auto}
.C .nbtn{display:inline-flex;align-items:center;gap:6px;color:var(--ink);text-decoration:none;font-weight:600;font-size:14px;border:1px solid var(--rule);border-radius:6px;height:32px;padding:0 10px;background:var(--page)}
.C .nm{border-left:0;padding:18px 20px}.C .nh{font-size:24px}.C .nm .said{font-size:21px}.C .nm .sh{margin-top:16px}
.C .meta-l{font-size:13.5px;color:var(--muted);margin:8px 0}
.C .contents .toc{list-style:none;margin:8px 0 28px}.C .toc li{border-bottom:1px solid var(--faint);padding:10px 0;display:flex;align-items:baseline;justify-content:space-between}
.C .toc a{font-family:'Newsreader',serif;font-size:26px;color:var(--ink);text-decoration:none}.C .toc li.on a{color:var(--tink)}.C .toc em{font-style:normal;font-size:13px;color:var(--ind)}
.C .contents .colophon{margin:0 0 6px}.C .contents .btn{margin-top:14px}
/* pill */
.C .desk{position:relative;width:100%;height:100%;background:#E7E4DD;overflow:hidden}
.C .dpage{position:absolute;left:36px;top:36px;width:500px;height:728px;background:#FCFBF8;border:1px solid #DCD7CC;border-radius:6px;padding:30px}.C .dpage.wide{width:860px;height:560px}
.C .dl{height:10px;background:#EEEBE4;border-radius:3px;margin:14px 0;width:82%}.C .dl.s{width:56%}.C .dl.m{width:68%}
.C .spec{position:absolute;left:64px;right:36px;top:40px;display:grid;grid-template-columns:560px 1fr;gap:28px}
.C .spec .sh{margin-top:0;background:transparent}
.C .sprow{display:flex;align-items:center;gap:14px;margin-bottom:14px}.C .spl{width:90px;text-align:right;font-size:13px;color:#625D55;flex:none}
.C .slip{display:inline-flex;align-items:center;gap:12px;height:42px;padding:0 16px 0 10px;background:#FCFBF8;border:1px solid #CFC9BC;border-radius:10px;box-shadow:0 8px 24px rgba(60,50,30,.12);white-space:nowrap;color:#23211D}
.C .slip .t{font-family:'Newsreader',serif;font-style:italic;font-size:18px}.C .slip .g{font-size:12.5px;color:#625D55;display:inline-flex;gap:5px;align-items:center}
.C .tl{color:var(--teal);display:inline-flex}
.C .okm{width:20px;height:20px;border-radius:50%;background:#0F766E;color:#fff;display:grid;place-items:center}.C .okm .i{width:13px;height:13px;stroke-width:2.4}
.C .spnote{font-size:13.5px;color:#625D55;margin:8px 0 0 104px;max-width:380px}
.C .slip.err{display:flex;flex-direction:column;align-items:stretch;height:auto;gap:0;padding:12px 16px 14px;white-space:normal;margin-bottom:12px;border-color:#D8B4AC}
.C .se-h{display:flex;gap:10px;align-items:center}.C .se-h b{font-family:'Newsreader',serif;font-size:19px;font-weight:500;color:#A3271B}.C .se-h .x{margin-left:auto;color:#625D55}
.C .slip.err p{font-size:14px;margin:4px 0 0 32px}.C .where{color:#625D55}.C .slip.err .acts{margin-left:32px}
/* tray */
.C .tpair{position:absolute;right:22px;bottom:60px;display:flex;gap:20px;align-items:flex-end}.C .tcap{font-size:13px;color:#625D55;margin:0 0 6px 2px}
.C .tray{width:330px;background:#FCFBF8;border:1px solid #CFC9BC;border-radius:10px;padding:14px 16px;box-shadow:0 18px 48px rgba(60,50,30,.18)}
.C .tr-h{display:flex;align-items:center;gap:8px;padding-bottom:10px;border-bottom:1px solid var(--rule)}.C .tw{font-family:'Newsreader',serif;font-size:19px;font-weight:500}.C .tr-h .st{margin-left:auto;font-size:13px}
.C .tr-p{padding:10px 0;border-bottom:1px solid var(--rule)}.C .tr-p b{color:var(--bad)}.C .tr-p p{font-size:14px}
.C .tr-l{padding:10px 0;border-bottom:1px solid var(--rule)}.C .tr-l .g{font-size:12.5px;color:var(--muted)}.C .tr-l p{font-family:'Newsreader',serif;font-size:16px;line-height:1.4;margin:2px 0 4px}.C .lk{font-size:14px}
.C .tr-m{list-style:none;padding-top:6px}.C .tr-m li{display:flex;justify-content:space-between;align-items:center;padding:6px 0;font-size:14.5px}.C .tr-m .v{color:var(--tink);font-weight:600;display:flex;align-items:center;gap:3px}.C .tr-m li.q{color:var(--muted)}
.C .taskbar{position:absolute;left:0;right:0;bottom:0;height:46px;background:#F1EFEA;border-top:1px solid #D2CDC2;display:flex;justify-content:flex-end;align-items:center;gap:16px;padding:0 18px}
.C .tk{position:relative;padding:5px;border-radius:6px;background:#E2DED6}.C .tk i{position:absolute;right:1px;bottom:1px;width:9px;height:9px;border-radius:50%;background:#C27A0E;border:2px solid #F1EFEA}.C .tk-t{font-size:12px;text-align:right;line-height:1.3}
/* history */
.C .hh{margin-bottom:14px;max-width:820px}.C .hh p{margin-top:4px}
.C .find{display:flex;gap:24px;align-items:center;border-bottom:1px solid var(--ink);padding:6px 0 8px;max-width:1000px}.C .fi{display:flex;gap:8px;align-items:center;color:var(--muted);flex:1}.C .fl{font-size:14px;color:var(--muted)}.C .badl{color:var(--bad)}
.C .ledger{max-width:1000px}.C .day{font-family:'Newsreader',serif;font-weight:500;font-size:19px;margin:14px 0 2px}
.C .hl{list-style:none}.C .he>div{min-width:0}.C .he{display:grid;grid-template-columns:62px minmax(0,1fr);gap:8px;padding:6px 0;border-bottom:1px solid var(--faint)}
.C .hw{font-size:13.5px;color:var(--muted);padding-top:3px;font-variant-numeric:tabular-nums}
.C .ht{font-family:'Newsreader',serif;font-size:17px;line-height:1.35;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.C .ht b{font-family:'Hanken Grotesk',sans-serif;font-size:14.5px}
.C .hm{font-size:12.5px;color:var(--muted);display:flex;gap:10px;align-items:center}.C .hm .btn{margin-left:6px}
/* vocab */
.C .stamp{display:inline-flex;align-items:center;gap:7px;border:1px solid var(--teal);color:var(--tink);border-radius:4px;padding:4px 10px;font-weight:600;font-size:14px;margin:8px 0 6px}
.C .stamp.sm{font-size:12.5px;margin:0}
.C .vg{display:grid;grid-template-columns:1.1fr 1fr;gap:56px;max-width:1000px}.C .vg .sh{margin-top:6px}.C .vg section .sh~.sh{margin-top:20px}
.C .gloss{display:grid;grid-template-columns:1fr 1fr;gap:10px 28px}.C .gloss dt{font-family:'Newsreader',serif;font-size:18px;font-weight:600}.C .gloss dt i{font-weight:400;font-size:14px;color:var(--muted)}.C .gloss dd{font-size:13.5px;color:var(--muted)}
.C .cl,.C .rl{list-style:none;display:flex;flex-direction:column;gap:5px;font-size:14.5px}.C .cl li{display:flex;gap:6px;align-items:baseline}.C .cl li span{margin-left:auto;font-size:13px;color:var(--muted)}
/* suggestions */
.C .sug{display:grid;grid-template-columns:minmax(0,640px) 280px;gap:0 48px}.C .sug .hh{grid-column:1/-1}
.C .qa{border-top:2px solid var(--ink);padding-top:16px}.C .kick{display:flex;gap:6px;align-items:center;font-size:13.5px;color:var(--muted)}
.C .bigq{font-family:'Newsreader',serif;font-weight:500;font-size:34px;line-height:1.18;letter-spacing:-.015em;margin:10px 0 16px}
.C .clips{list-style:none;border-left:2px solid var(--rule);padding-left:16px;display:flex;flex-direction:column;gap:6px}.C .clips li{font-family:'Newsreader',serif;font-style:italic;font-size:17px;display:flex;gap:10px;align-items:baseline}
.C .clips li span{font-family:'Hanken Grotesk',sans-serif;font-style:normal;font-size:12.5px;color:var(--muted);margin-left:auto}
.C .acts.lg .btn{height:38px;font-size:15px;padding:0 18px}.C .expl{margin-top:12px;max-width:52ch}
.C .next{padding-top:4px}.C .next .sh:first-child{margin-top:0}
/* styles */
.C .pt{margin-bottom:4px}.C .youSaid{margin:14px 0 6px;font-size:14px;color:var(--muted)}.C .youSaid i{font-family:'Newsreader',serif;font-size:17px;color:var(--ink)}
.C .spec2{list-style:none}.C .spec2 li{display:grid;grid-template-columns:120px 1fr;gap:16px;padding:10px 0;border-bottom:1px solid var(--faint)}
.C .spec2 .sn{display:flex;flex-direction:column;font-size:14px}.C .spec2 .sn span{font-size:13px;color:var(--muted)}.C .spec2 .def{color:var(--tink)!important;font-weight:600}
.C .sx{font-family:'Newsreader',serif;font-size:19px;line-height:1.35}.C .spec2 li.on .sn b{color:var(--tink)}.C .spec2 li.on{box-shadow:inset 2px 0 0 var(--teal);padding-left:14px;margin-left:-16px}
.C .pa{list-style:none;display:flex;flex-direction:column;gap:6px;margin-bottom:10px}.C .pa li{display:flex;justify-content:space-between;align-items:center}
/* settings */
.C .setg{display:grid;grid-template-columns:1fr 1fr;gap:0 56px;max-width:1000px}.C .sg .sh{margin-top:10px}.C .sg p{font-size:14.5px;margin-bottom:4px}
.C .kv{display:grid;grid-template-columns:1fr auto;gap:5px 12px;font-size:14.5px;margin-bottom:4px}
.C .rad{display:flex;gap:10px;align-items:flex-start}.C .r{width:14px;height:14px;border:1.5px solid var(--muted);border-radius:50%;margin-top:4px;flex:none}.C .rad.on .r{border:4px solid var(--teal)}
.C .lk.bad{color:var(--bad);margin-left:8px}
/* first run */
.C .fr{flex:1;display:grid;place-items:center;background:var(--paper)}
.C .letter{width:660px;background:var(--page);border:1px solid var(--rule);padding:40px 48px 30px;box-shadow:0 18px 50px rgba(60,50,30,.08)}
.C .lh{display:flex;gap:14px;align-items:center}.C .lh h1{font-size:34px}.C .lede{font-family:'Newsreader',serif;font-size:19px;color:var(--muted);margin:6px 0 18px}
.C .fsteps{padding-left:22px;display:flex;flex-direction:column;gap:8px;font-size:15px}.C .fsteps li::marker{font-family:'Newsreader',serif;color:var(--muted)}
.C .fsteps li.done{color:var(--muted)}.C .fsteps li.done b{color:var(--ink)}.C .fsteps li.now::marker{color:var(--tink);font-weight:600}.C .fsteps i{font-family:'Newsreader',serif;font-size:16.5px}
.C .tryl{margin:18px 0 6px 22px;border-bottom:1.5px solid var(--teal);padding:6px 0;font-family:'Newsreader',serif;font-size:21px}.C .caret{display:inline-block;width:1.5px;height:22px;background:var(--ink);vertical-align:-4px;margin-left:2px}
.C .okp{display:flex;gap:6px;align-items:center;color:var(--tink);font-size:14px;margin-left:22px}
`;

export function render() {
  const homeFrame = (dark) => `<div class="C ${dark ? 'dark' : ''}">${app('home', homeMain())}</div>`;
  const frameHtml = {
    pill: `<div class="C">${pillFrame()}</div>`,
    tray: `<div class="C">${trayFrame()}</div>`,
    home: homeFrame(false),
    'home-narrow': `<div class="C">${homeNarrow()}</div>`,
    'menu-narrow': `<div class="C">${menuNarrow()}</div>`,
    'home-alt': homeFrame(true),
    history: `<div class="C">${app('history', historyMain())}</div>`,
    vocab: `<div class="C">${app('vocab', vocabMain())}</div>`,
    suggest: `<div class="C">${app('suggest', suggestMain())}</div>`,
    styles: `<div class="C">${app('styles', stylesMain())}</div>`,
    settings: `<div class="C">${app('settings', settingsMain())}</div>`,
    firstrun: `<div class="C">${firstRun()}</div>`,
  };
  return shell({
    title: 'VoiceFlow, direction C: Paper Quiet',
    fonts: 'https://fonts.googleapis.com/css2?family=Newsreader:ital,opsz,wght@0,6..72,400;0,6..72,500;0,6..72,600;1,6..72,400;1,6..72,500&family=Hanken+Grotesk:wght@400;500;600;700&display=swap',
    css, frameHtml, canvas: '#D9D5CC',
    intro: 'Text first: warm neutral paper, a serif for what you said, a quiet grotesk for the controls, teal as ink. Corrections are marked like an editor marks a proof, with notes in the margin. Static mockup with demo content; same structure as A and B.',
  });
}
