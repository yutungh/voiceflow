// node b31-run.mjs [frameId ...] : writes ../voiceflow-b3-1.html and screenshots every frame in light and dark to ../shots/<theme>/
// Needs Playwright with Microsoft Edge: `npm i playwright`, or set PLAYWRIGHT_MODULE to the path of an installed playwright/index.mjs.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath, pathToFileURL } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.join(here, '..');
const mod = await import(pathToFileURL(path.join(here, 'b31.mjs')).href + `?t=${Date.now()}`);
const html = mod.render(); const file = path.join(outDir, 'voiceflow-b3-1.html'); fs.writeFileSync(file, html);
console.log(`${file} ${Math.round(html.length / 1024)} KB`);
const only = process.argv.slice(2);
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE ? pathToFileURL(process.env.PLAYWRIGHT_MODULE).href : 'playwright');
const browser = await chromium.launch({ headless: true, channel: 'msedge' });
try {
  for (const theme of ['light', 'dark']) {
    const dir = path.join(outDir, 'shots', theme); fs.mkdirSync(dir, { recursive: true });
    const page = await browser.newPage({ viewport: { width: 1360, height: 900 }, deviceScaleFactor: 1 });
    await page.goto(pathToFileURL(file).href + `?theme=${theme}`, { waitUntil: 'networkidle' });
    await page.addStyleTag({ content: '.sheet-bar{display:none!important}' });
    await page.evaluate(() => document.fonts.ready);
    let c = 0;
    for (const f of await page.$$('.frame[data-shot]')) { const id = await f.getAttribute('data-shot'); if (only.length && !only.includes(id)) continue; await f.screenshot({ path: path.join(dir, `${id}.png`) }); c++; }
    const issues = await page.evaluate(() => [...document.querySelectorAll('.frame')].flatMap((fr) => { const r = fr.getBoundingClientRect(); return [...fr.querySelectorAll('.btn,.chip,.card,.pill,.tray,.sel,.search,.prob,.cmd,.mini,.next,.frbox,.toast')].filter((e) => { const b = e.getBoundingClientRect(); return b.width && (b.right > r.right + 1 || b.bottom > r.bottom + 1 || e.scrollWidth > e.clientWidth + 2); }).slice(0, 5).map((e) => `${fr.dataset.shot}: ${e.className.baseVal ?? e.className} "${e.textContent.trim().slice(0, 36)}" ${e.scrollWidth > e.clientWidth + 2 ? 'overflows itself' : 'past frame edge'}`); }));
    console.log(`${theme}: ${c} shots`); if (issues.length) console.log(' ' + issues.join('\n '));
    await page.close();
  }
} finally { await browser.close(); }
