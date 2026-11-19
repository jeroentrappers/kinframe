// Composes the Play Store images: the App Store headlines in the brand's type over the emulator captures, plus the
// feature graphic and the 512 px icon.
//   node compose.mjs   reads out/raw/<device>-<lang>-<n>.png (capture.sh),
//                      writes ../../fastlane/metadata/android/<locale>/images/
// Uses Playwright from ios/tools/storeshots (npm ci there) and the fonts in desktop/assets/fonts.
// Play wants no alpha in screenshots and the feature graphic: those are rendered as JPEG and stored as PNG (sips).
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ios = join(here, '../../../ios/tools/storeshots');
const { chromium } = createRequire(join(ios, 'package.json'))('playwright');
const fonts = join(here, '../../../desktop/assets/fonts');
const out = join(here, '../../fastlane/metadata/android');

// Phone 9:16 and a 16:10 landscape tablet. The capture is cropped at the bottom like the App Store set.
const devices = {
  phone: { dir: 'phoneScreenshots', w: 1080, h: 1920, shot: 0.8, radius: 44, title: 84, sub: 36, top: 120, gap: 64 },
  tablet: { dir: 'tenInchScreenshots', w: 2560, h: 1600, shot: 0.7, radius: 32, title: 96, sub: 46, top: 90, gap: 56 },
};
const locales = { en: 'en-US', nl: 'nl-NL' };

// The App Store headlines (ios/tools/storeshots/compose.mjs), one per capture (capture.sh order): history, frames, compose, send detail, frame photos.
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

// The App Store subtitle, for the feature graphic.
const tagline = { en: 'Your photos, on their frame', nl: 'Jouw foto’s, op hun kader' };

const font = (f) => `data:font/ttf;base64,${readFileSync(join(fonts, f)).toString('base64')}`;
const png = (file) => `data:image/png;base64,${readFileSync(file).toString('base64')}`;
const jpg = (file) => `data:image/jpeg;base64,${readFileSync(file).toString('base64')}`;
const fontFaces = `
@font-face { font-family: Fraunces; src: url(${font('Fraunces-SemiBold.ttf')}); }
@font-face { font-family: Inter; font-weight: 400; src: url(${font('Inter-Regular.ttf')}); }`;
const background = 'radial-gradient(120% 70% at 20% 0%, #f7e4d8 0%, #f8f3ec 55%, #f3ebe0 100%)';

// The web logo (web/src/lib/components/Logo.svelte).
const logo = (size) => `<svg width="${size}" height="${size}" viewBox="0 0 64 64"><rect x="4" y="8" width="56" height="48" rx="12" fill="#a7471e"/>
<rect x="11" y="15" width="42" height="34" rx="6" fill="#fbf1e6"/><circle cx="41" cy="26" r="5" fill="#e7a23a"/>
<path d="M11 43 23 31l9 9 6-6 15 15H17a6 6 0 0 1-6-6z" fill="#4f7a5a"/></svg>`;

function screenshot(d, title, sub, raw) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}
html, body { margin: 0; width: ${d.w}px; height: ${d.h}px; overflow: hidden; }
body { background: ${background}; display: flex; flex-direction: column; align-items: center; font-family: Inter, sans-serif; }
h1 { font-family: Fraunces, serif; font-weight: 600; color: #2a221d; font-size: ${d.title}px; line-height: 1.05;
  letter-spacing: -0.01em; margin: ${d.top}px 60px 0; text-align: center; }
p { color: #675a50; font-size: ${d.sub}px; line-height: 1.3; margin: 20px 60px 0; text-align: center; text-wrap: balance; }
.shot { margin-top: ${d.gap}px; width: ${Math.round(d.w * d.shot)}px; border-radius: ${d.radius}px; overflow: hidden; flex: none;
  box-shadow: 0 30px 90px rgba(124, 52, 18, 0.22), 0 0 0 2px rgba(42, 34, 29, 0.08); }
.shot img { display: block; width: 100%; }
</style></head><body><h1>${title}</h1><p>${sub}</p>
<div class="shot"><img src="${png(raw)}"></div></body></html>`;
}

function featureGraphic(lang) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}
html, body { margin: 0; width: 1024px; height: 500px; overflow: hidden; }
body { background: ${background}; font-family: Inter, sans-serif; position: relative; }
.brand { position: absolute; left: 64px; top: 0; bottom: 0; display: flex; flex-direction: column; justify-content: center; }
.mark { display: flex; align-items: center; gap: 16px; }
.word { font-family: Fraunces, serif; font-weight: 600; font-size: 80px; letter-spacing: -0.02em; color: #2a221d; }
.tag { margin-top: 18px; color: #675a50; font-size: 32px; max-width: 460px; line-height: 1.25; }
.frame { position: absolute; right: 48px; top: 110px; width: 330px; height: 260px; border-radius: 28px; background: #a7471e;
  box-shadow: 0 30px 80px rgba(124, 52, 18, 0.28); transform: rotate(3deg); }
.frame img { position: absolute; inset: 16px; width: calc(100% - 32px); height: calc(100% - 32px); object-fit: cover; border-radius: 14px; }
.back { right: 116px; top: 128px; transform: rotate(-7deg); background: #4f7a5a; opacity: 0.9; }
</style></head><body>
<div class="frame back"><img src="${jpg(join(ios, 'photos/sunset.jpg'))}"></div>
<div class="frame"><img src="${jpg(join(ios, 'photos/tulip-field.jpg'))}"></div>
<div class="brand"><div class="mark">${logo(84)}<span class="word">kinframe</span></div><div class="tag">${tagline[lang]}</div></div>
</body></html>`;
}

// The launcher icon (res/mipmap-anydpi/ic_launcher.xml): the accent background with the foreground vector, full bleed;
// Play applies its own mask. Encoded by a canvas: Play asks for a 32-bit (RGBA) PNG.
const iconSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108"><rect width="108" height="108" fill="#A7471E"/>
<path fill="#FFFFFF" d="M34,34h40a6,6 0 0 1 6,6v24a6,6 0 0 1 -6,6h-40a6,6 0 0 1 -6,-6v-24a6,6 0 0 1 6,-6z"/>
<path fill="#FFFFFF" d="M48,70h12l4,8h-20z"/>
<path fill="#A7471E" d="M54,60c-0.6,-0.5 -8,-5 -8,-10a4.5,4.5 0 0 1 8,-2.8a4.5,4.5 0 0 1 8,2.8c0,5 -7.4,9.5 -8,10z"/></svg>`;

async function writeIcon(tab, file) {
  const data = await tab.evaluate(async (svg) => {
    const img = new Image();
    img.src = `data:image/svg+xml;base64,${btoa(svg)}`;
    await img.decode();
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 512;
    canvas.getContext('2d').drawImage(img, 0, 0, 512, 512);
    return canvas.toDataURL('image/png').split(',')[1];
  }, iconSvg);
  writeFileSync(file, Buffer.from(data, 'base64'));
  console.log(file);
}

/** Renders the page to an opaque PNG (JPEG first, so there is no alpha channel). */
async function opaque(tab, html, w, h, file) {
  await tab.setContent(html, { waitUntil: 'load' });
  await tab.evaluate(() => document.fonts.ready);
  const tmp = `${file}.jpg`;
  await tab.screenshot({ path: tmp, type: 'jpeg', quality: 95, clip: { x: 0, y: 0, width: w, height: h } });
  execFileSync('sips', ['-s', 'format', 'png', tmp, '--out', file], { stdio: 'ignore' });
  rmSync(tmp);
  console.log(file);
}

const browser = await chromium.launch();
for (const [dev, d] of Object.entries(devices)) {
  const ctx = await browser.newContext({ viewport: { width: d.w, height: d.h }, deviceScaleFactor: 1 });
  const tab = await ctx.newPage();
  for (const [lang, locale] of Object.entries(locales)) {
    const dir = join(out, locale, 'images', d.dir);
    for (let n = 1; n <= copy[lang].length; n++) {
      const raw = join(here, 'out/raw', `${dev}-${lang}-${n}.png`);
      if (!existsSync(raw)) { console.warn('missing', raw); continue; }
      mkdirSync(dir, { recursive: true });
      const [title, sub] = copy[lang][n - 1];
      await opaque(tab, screenshot(d, title, sub, raw), d.w, d.h, join(dir, `${n}.png`));
    }
  }
  await ctx.close();
}

const ctx = await browser.newContext({ viewport: { width: 1024, height: 500 }, deviceScaleFactor: 1 });
const tab = await ctx.newPage();
for (const [lang, locale] of Object.entries(locales)) {
  const dir = join(out, locale, 'images');
  mkdirSync(dir, { recursive: true });
  await opaque(tab, featureGraphic(lang), 1024, 500, join(dir, 'featureGraphic.png'));
  await writeIcon(tab, join(dir, 'icon.png'));
}
await browser.close();
