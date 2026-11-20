import fs from 'node:fs';
const L = (h) => { h = h.replace('#', ''); const c = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4)); return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]; };
const cr = (a, b) => { const x = L(a), y = L(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
const css = fs.readFileSync(process.argv[2], 'utf8');
const blk = (s) => { const i = css.indexOf(s); return css.slice(i, css.indexOf('}', i)); };
const v = (b) => Object.fromEntries([...b.matchAll(/--([\w-]+):(#[0-9A-Fa-f]{6})/g)].map((x) => [x[1], x[2]]));
const light = v(blk('.B{')), dark = { ...light, ...v(blk('html[data-theme="dark"] .B{')) };
const text = [['foreground', 'background'], ['foreground', 'surface'], ['foreground', 'rail'], ['muted-foreground', 'surface'], ['muted-foreground', 'rail'], ['muted-foreground', 'background'], ['muted-foreground', 'surface-raised'], ['subtle-foreground', 'surface'], ['subtle-foreground', 'background'], ['subtle-foreground', 'rail'], ['subtle-foreground', 'surface-raised'], ['foreground', 'accent-tint'], ['accent-ink', 'accent-tint'], ['accent-ink', 'accent-tint-hover'], ['accent-ink', 'surface'], ['accent', 'surface'], ['danger', 'surface'], ['warning', 'surface'], ['success', 'surface'], ['info', 'surface'], ['tile-acc-fg', 'tile-acc'], ['tile-bad-fg', 'tile-bad'], ['tile-warn-fg', 'tile-warn'], ['tile-ind-fg', 'tile-ind'], ['tile-ok-fg', 'tile-ok'], ['success', 'tile-ok'], ['kbd-fg', 'kbd-bg'], ['statusbar-fg', 'statusbar'], ['statusbar-ok', 'statusbar'], ['statusbar-warn', 'statusbar']];
const nontext = [['accent-tint-border', 'surface'], ['accent-tint-border', 'background'], ['accent', 'surface'], ['danger', 'surface'], ['warning', 'surface'], ['info', 'surface']];
const out = {};
for (const [k, t] of [['light', light], ['dark', dark]]) {
  const tr = text.map(([a, b]) => [`${a} on ${b}`, +cr(t[a], t[b]).toFixed(2)]); const nr = nontext.map(([a, b]) => [`${a} on ${b}`, +cr(t[a], t[b]).toFixed(2)]);
  out[k] = { text: tr, nontext: nr };
  console.log(`${k}: text pairs ${tr.length}, below 4.5: ${tr.filter((x) => x[1] < 4.5).map((x) => x.join(' ')).join('; ') || 'none'}; lowest ${tr.sort((a, b) => a[1] - b[1]).slice(0, 2).map((x) => x.join(' ')).join('; ')}`);
  console.log(`${k}: edges/icons (need 3:1): ${nr.map((x) => x.join(' ')).join('; ')}`);
}
if (process.argv[3]) fs.writeFileSync(process.argv[3], JSON.stringify(out, null, 1));
