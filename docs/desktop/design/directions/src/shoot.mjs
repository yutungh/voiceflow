// Screenshots every frame of each mockup at its own size: node shoot.mjs [a|b|c ...]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const names = { a: 'a-calm-teal.html', b: 'b-night-console.html', c: 'c-paper-quiet.html' };
const want = process.argv.slice(2).length ? process.argv.slice(2) : Object.keys(names);
const browser = await chromium.launch({ headless: true, channel: 'msedge' });
try {
  for (const k of want) {
    const file = path.join(here, '..', 'mockups', names[k]);
    const dir = path.join(here, '..', 'shots', k);
    fs.mkdirSync(dir, { recursive: true });
    const page = await browser.newPage({ viewport: { width: 1360, height: 900 }, deviceScaleFactor: 1 });
    await page.goto(pathToFileURL(file).href, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    const frames = await page.$$('.frame[data-shot]');
    for (const f of frames) {
      const id = await f.getAttribute('data-shot');
      await f.screenshot({ path: path.join(dir, `${id}.png`) });
    }
    console.log(`${k}: ${frames.length} shots in ${dir}`);
    await page.close();
  }
} finally { await browser.close(); }
