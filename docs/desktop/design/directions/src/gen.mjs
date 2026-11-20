// Writes the mockups: node gen.mjs [a|b|c ...]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(here, '..', 'mockups');
fs.mkdirSync(out, { recursive: true });
const names = { a: 'a-calm-teal.html', b: 'b-night-console.html', c: 'c-paper-quiet.html' };
const want = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(names);
for (const k of want) {
  const mod = await import(pathToFileURL(path.join(here, `${k}.mjs`)).href + `?t=${Date.now()}`);
  const html = mod.render();
  fs.writeFileSync(path.join(out, names[k]), html);
  console.log(`${names[k]} ${Math.round(html.length / 1024)} KB`);
}
