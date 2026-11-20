// Reads the fetched Phosphor SVGs (MIT, github.com/phosphor-icons/core) and writes phosphor.json: { name: { regular, fill, duotone } }
// holding only whitelisted shape elements and attributes (the files are treated as untrusted data).
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const src = process.env.PHOSPHOR_DIR; // a checkout of github.com/phosphor-icons/core assets (folders regular/, fill/, duotone/)
if (!src) { console.log('set PHOSPHOR_DIR to the Phosphor assets folder'); process.exit(2); }
const TAGS = ['path', 'circle', 'rect', 'line', 'polyline', 'polygon', 'ellipse'];
const ATTRS = ['d', 'opacity', 'cx', 'cy', 'r', 'rx', 'ry', 'x', 'y', 'width', 'height', 'points', 'x1', 'y1', 'x2', 'y2'];
const clean = (svg) => [...svg.matchAll(/<(\w+)\s([^>]*?)\/?>/g)].filter((m) => TAGS.includes(m[1])).map((m) => {
  const attrs = [...m[2].matchAll(/([\w-]+)="([^"]*)"/g)].filter((a) => ATTRS.includes(a[1]) && /^[\d\s.,\-a-zA-Z]*$/.test(a[2])).map((a) => `${a[1]}="${a[2]}"`).join(' ');
  return `<${m[1]} ${attrs}/>`;
}).join('');
const out = {};
for (const f of fs.readdirSync(path.join(src, 'regular'))) {
  const n = f.replace(/\.svg$/, '');
  out[n] = { regular: clean(fs.readFileSync(path.join(src, 'regular', f), 'utf8')), fill: clean(fs.readFileSync(path.join(src, 'fill', `${n}-fill.svg`), 'utf8')), duotone: clean(fs.readFileSync(path.join(src, 'duotone', `${n}-duotone.svg`), 'utf8')) };
}
fs.writeFileSync(path.join(here, 'phosphor.json'), JSON.stringify(out));
console.log(`${Object.keys(out).length} icons, ${Math.round(JSON.stringify(out).length / 1024)} KB`);
