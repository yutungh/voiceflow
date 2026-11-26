/**
 * Checks that need no API: the requests match VoiceFlow's own code, the lists
 * are built as designed, and the scorer counts what the README says.
 *   bun lib/selftest.ts
 */
import { constructedTranscript, listOf, SENTENCES, UNIVERSE, WORDS } from './data';
import { speechPayload, speechPayloadFromApp, tidyPrompts, tidySystem, tidyUser } from './prompts';
import { count, designatedRight, neverSaid, tokens, unwrap, wordErrors } from './score';

let failures = 0;
function check(name: string, ok: boolean, detail = '') {
	if (!ok) failures++;
	console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `  (${detail})` : ''}`);
}

// Lists
check('80 words, all different', WORDS.length === 80 && new Set(UNIVERSE.map((w) => w.toLowerCase())).size === 80);
const targets = WORDS.filter((w) => w.role === 'target').map((w) => w.text);
check('10 targets, all in the first 20', targets.length === 10 && targets.every((t) => listOf(20).some((w) => w.text === t)));
for (const n of [20, 40, 50, 80]) {
	const line = speechPayload(n).keywords;
	check(`list ${n}: ${n} words, every target, core 20 last in the same order`, line.length === n && targets.every((t) => line.includes(t)) && line.slice(-20).join() === speechPayload(20).keywords.join());
}
check('list 0 sends nothing', speechPayload(0).prompt === '' && speechPayload(0).keywords.length === 0);
for (const n of [20, 40]) {
	const a = speechPayload(n);
	const b = speechPayloadFromApp(n);
	check(`list ${n} matches buildPrompt exactly`, a.prompt === b.prompt && a.keywords.join('|') === b.keywords.join('|'));
}
check('speech prompt has no hints', !speechPayload(80).prompt.includes('('));

// Script
const listed = new Set(UNIVERSE.map((w) => w.toLowerCase()));
for (const s of SENTENCES) {
	const toks = tokens(s.text);
	const inList = toks.filter((t) => listed.has(t));
	if (s.type === 'target') check(`${s.id} says only its target`, inList.length === 1 && inList[0] === s.word!.toLowerCase());
	else check(`${s.id} says no listed word`, inList.length === 0, inList.join(','));
	if (s.type === 'protected') check(`${s.id} protected word is unlisted and paired`, !listed.has(s.word!.toLowerCase()) && WORDS.some((w) => w.text === s.pair && w.pair === s.word));
}
check('40 sentences: 20 target, 10 protected, 10 control', SENTENCES.length === 40 && SENTENCES.filter((s) => s.type === 'target').length === 20 && SENTENCES.filter((s) => s.type === 'protected').length === 10);
for (const t of targets) check(`${t} is said twice`, SENTENCES.filter((s) => s.word === t).length === 2);
check('10 constructed repair cases', SENTENCES.filter((s) => constructedTranscript(s)).length === 10);
check('constructed case swaps in the sound-alike', constructedTranscript(SENTENCES.find((s) => s.id === 's02')!) === 'Can you ask Beeper to send the slides before the meeting?');

// Tidy-up prompt
const t = tidyPrompts();
check('tidy block lists all 80 words', t.block.split('\n').filter((l) => l.startsWith('- ') && !l.startsWith('- Speech') && !l.startsWith('- Write') && !l.startsWith('- Keep') && !l.startsWith('- Never') && !l.startsWith('- Mixed') && !l.startsWith('- Example')).length === 80);
check('every paired name keeps its heard-as note', ['Bepra', 'Dockra', 'Mirva', 'Harbra', 'Kandrel'].every((w) => new RegExp(`^- ${w} \\(person; speech-to-text wrote "`, 'm').test(t.block)));
check('style is Clean + language + output contract', t.style.startsWith('You are a text-transformation tool') && t.style.includes('Language:') && t.style.includes('Output contract:') && !t.style.includes('${'));
check('system ends with the rules block', tidySystem().trimEnd().endsWith('"the dentist used a drill" stays as it is.'));
check('user message wraps the transcript', tidyUser('abc').includes('<transcript>\nabc\n</transcript>'));

// Scorer
const s10 = SENTENCES.find((s) => s.id === 's10')!; // Beeper, protected
check('right when the word is there', designatedRight(s10, 'I read the reply in Beeper on my laptop.') === true);
check('wrong when it became the listed name', designatedRight(s10, 'I read the reply in Bepra on my laptop.') === false);
check('the listed name counts as never said', JSON.stringify(neverSaid(s10, 'I read the reply in Bepra on my laptop.')) === '{"Bepra":1}');
check('case and punctuation ignored', count(tokens('"kubernetes," she said'), 'Kubernetes') === 1);
check('split word is not the word', count(tokens('tail scale'), 'Tailscale') === 0);
check('word errors', wordErrors(s10, 'I read the reply in Bepra on my laptop').errors === 1);

check('unwrap JSON answer', unwrap('{"final_text": "Hi there."}').text === 'Hi there.' && unwrap('{"final_text": "Hi"}').format === 'wrapped');
check('unwrap {"..."} answer', unwrap('{"Please light the candle."}').text === 'Please light the candle.');
check('control token is empty', unwrap('<|im_sep|>').format === 'empty' && unwrap('  ').format === 'empty');
check('plain answer untouched', unwrap('Plain text.').format === 'ok');

// Tidy-up fixes
const fx = await import('./tidyfix');
check('fix variant base = VoiceFlow prompt', fx.variantSystem({ hints: 'all' }) === tidySystem());
check('everyday rule drops Beeper and candle, keeps Zora Vin', fx.isEverydayForm('Beeper') && fx.isEverydayForm('candle') && !fx.isEverydayForm('Zora Vin'));
for (const w of ['A', 'B', 'C'] as const) check(`wording ${w} replaces both deciding bullets`, !fx.variantSystem({ hints: 'all', wording: w }).includes('it makes little sense where it is, and the listed word fits') && fx.variantSystem({ hints: 'all', wording: w }).includes(fx.WORDINGS[w][0]!));
check('no-everyday variant drops the Beeper hint', !fx.variantSystem({ hints: 'no-everyday' }).includes('wrote "Beeper"') && tidySystem().includes('wrote "Beeper"'));

console.log(failures ? `\n${failures} FAILED` : '\nall checks pass');
process.exit(failures ? 1 : 0);
