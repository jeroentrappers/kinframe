// Composes the App Store screenshots: a headline in the brand's type over the simulator capture.
//   node compose.mjs        reads out/raw/<device>-<lang>-<n>.png, writes ../../fastlane/screenshots/<locale>/
// Sizes are Apple's: iPhone 6.9" 1320×2868, iPad 13" 2064×2752, iPhone Duo inner display 2853×2007.
import { chromium } from 'playwright';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const fonts = join(here, '../../../desktop/assets/fonts');
const out = join(here, '../../fastlane/screenshots');

const devices = {
  iphone: { w: 1320, h: 2868, name: 'iPhone 6.9', shot: 0.84, radius: 64, title: 112, sub: 52, top: 190 },
  ipad: { w: 2064, h: 2752, name: 'iPad 13', shot: 0.8, radius: 40, title: 120, sub: 56, top: 170 },
  duo: { w: 2853, h: 2007, name: 'iPhone Duo', shot: 0.74, gap: 56, radius: 44, title: 104, sub: 50, top: 110 },
};
const locales = { en: 'en-US', nl: 'nl-NL' };

// One per capture (capture.sh order): history, frames, compose, send detail, frame photos.
const copy = {
  en: [
    ['See the smile', 'Hearts and replies from the frame come right back'],
    ['Every frame in the family', 'See which ones are online at a glance'],
    ['Sent in seconds', 'Pick photos, add a caption, choose the frames'],
    ['Know when it arrives', 'Delivered, seen and loved'],
    ['You decide what shows', 'Albums, hiding and a trash for every frame'],
  ],
  nl: [
    ['Zie de glimlach', 'Hartjes en antwoorden van het kader komen meteen terug'],
    ['Alle kaders van de familie', 'Zie in één oogopslag welke online zijn'],
    ['In een paar seconden verstuurd', 'Kies foto’s, schrijf iets, kies de kaders'],
    ['Weet wanneer het aankomt', 'Afgeleverd, bekeken en geliefd'],
    ['Jij kiest wat er verschijnt', 'Albums, verbergen en een prullenbak per kader'],
  ],
};

const font = (f) => `data:font/ttf;base64,${readFileSync(join(fonts, f)).toString('base64')}`;

function page(d, title, sub, png) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
@font-face { font-family: Fraunces; src: url(${font('Fraunces-SemiBold.ttf')}); }
@font-face { font-family: Inter; font-weight: 400; src: url(${font('Inter-Regular.ttf')}); }
html, body { margin: 0; width: ${d.w}px; height: ${d.h}px; overflow: hidden; }
body { background: radial-gradient(120% 70% at 20% 0%, #f7e4d8 0%, #f8f3ec 55%, #f3ebe0 100%);
  display: flex; flex-direction: column; align-items: center; font-family: Inter, sans-serif; }
h1 { font-family: Fraunces, serif; font-weight: 600; color: #2a221d; font-size: ${d.title}px; line-height: 1.05;
  letter-spacing: -0.01em; margin: ${d.top}px 80px 0; text-align: center; }
p { color: #675a50; font-size: ${d.sub}px; line-height: 1.3; margin: 28px 120px 0; text-align: center; }
.shot { margin-top: ${d.gap ?? 80}px; width: ${Math.round(d.w * d.shot)}px; border-radius: ${d.radius}px; overflow: hidden;
  box-shadow: 0 40px 120px rgba(124, 52, 18, 0.22), 0 0 0 2px rgba(42, 34, 29, 0.08); }
.shot img { display: block; width: 100%; }
</style></head><body><h1>${title}</h1><p>${sub}</p>
<div class="shot"><img src="data:image/png;base64,${readFileSync(png).toString('base64')}"></div></body></html>`;
}

const browser = await chromium.launch();
for (const [dev, d] of Object.entries(devices)) {
  const ctx = await browser.newContext({ viewport: { width: d.w, height: d.h }, deviceScaleFactor: 1 });
  const tab = await ctx.newPage();
  for (const [lang, locale] of Object.entries(locales)) {
    mkdirSync(join(out, locale), { recursive: true });
    for (let n = 1; n <= copy[lang].length; n++) {
      const raw = join(here, 'out/raw', `${dev}-${lang}-${n}.png`);
      if (!existsSync(raw)) { console.warn('missing', raw); continue; }
      const [title, sub] = copy[lang][n - 1];
      await tab.setContent(page(d, title, sub, raw), { waitUntil: 'load' });
      await tab.evaluate(() => document.fonts.ready);
      const file = join(out, locale, `${n}_${d.name}_${n}.png`);
      await tab.screenshot({ path: file, clip: { x: 0, y: 0, width: d.w, height: d.h } });
      console.log(file);
    }
  }
  await ctx.close();
}
await browser.close();
