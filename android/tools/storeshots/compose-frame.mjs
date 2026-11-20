// Composes the frame app's Play Store images (docs/play-frame.md), in the family listing's style (compose.mjs): a
// headline in the brand's type over each screen, plus the feature graphic and the 512 px icon.
//   ./gradlew :frame:testGithubDebugUnitTest --tests app.kinframe.frame.StoreScreenshotTest
//                      renders the screens to frame/build/screenshots/store/<ten|seven>-<lang>-<scene>.png
//   node compose-frame.mjs   writes ../../fastlane/frame-metadata/android/<locale>/images/
// Uses Playwright from ios/tools/storeshots (npm ci there) and the fonts in desktop/assets/fonts. Play wants no alpha in
// screenshots and the feature graphic: those are flattened with ImageMagick (`magick`), or on a Mac without it
// rendered as JPEG and stored as PNG (sips).
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const ios = join(here, '../../../ios/tools/storeshots');
const { chromium } = createRequire(join(ios, 'package.json'))('playwright');
const fonts = join(here, '../../../desktop/assets/fonts');
const raw = join(here, '../../frame/build/screenshots/store');
const out = join(here, '../../fastlane/frame-metadata/android');

// 16:9 landscape canvases; the tablet's whole screen is shown (the caption and the clock sit at its bottom). `scenes`:
// the StoreScreenshotTest captures, in listing order (the forecast sheet needs the 10-inch width).
const devices = {
  ten: { dir: 'tenInchScreenshots', w: 2560, h: 1440, shotH: 1040, radius: 36, title: 96, sub: 46, top: 84, gap: 52,
    scenes: ['photo', 'menu', 'pair', 'forecast', 'code'] },
  seven: { dir: 'sevenInchScreenshots', w: 1920, h: 1080, shotH: 780, radius: 28, title: 72, sub: 35, top: 63, gap: 39,
    scenes: ['photo', 'menu', 'pair', 'code', 'celebrate'] },
};
const locales = { en: 'en-US', nl: 'nl-NL' };

// The headline and subtitle per scene.
const copy = {
  en: {
    photo: ['The family’s photos, all day', 'Sent from a phone, on the frame a few seconds later'],
    menu: ['A tap says thank you', 'Send a heart back, or hide a photo'],
    pair: ['Two at a time', 'Portrait photos side by side, with who sent them'],
    forecast: ['Clock and weather at a glance', 'Tap the weather for the forecast'],
    code: ['Ready in minutes', 'Enter the frame’s code in the kinframe app and it’s yours'],
    celebrate: ['Connected, and ready for photos', 'Invite the family, and everyone can send'],
  },
  nl: {
    photo: ['De foto’s van de familie, de hele dag', 'Verstuurd vanaf een telefoon, een paar seconden later op de fotolijst'],
    menu: ['Met een tik zeg je dankjewel', 'Stuur een hartje terug, of verberg een foto'],
    pair: ['Twee tegelijk', 'Staande foto’s naast elkaar, met wie ze stuurde'],
    forecast: ['Klok en weer in één oogopslag', 'Tik op het weer voor de voorspelling'],
    code: ['In een paar minuten klaar', 'Vul de code van de fotolijst in de kinframe-app in en hij is van jou'],
    celebrate: ['Verbonden, en klaar voor foto’s', 'Nodig de familie uit, en iedereen kan foto’s sturen'],
  },
};

// The listing's promise, for the feature graphic.
const tagline = { en: 'Your old tablet, the family’s photo frame', nl: 'Je oude tablet, de fotolijst van de familie' };

const font = (f) => `data:font/ttf;base64,${readFileSync(join(fonts, f)).toString('base64')}`;
const png = (file) => `data:image/png;base64,${readFileSync(file).toString('base64')}`;
const fontFaces = `
@font-face { font-family: Fraunces; src: url(${font('Fraunces-SemiBold.ttf')}); }
@font-face { font-family: Inter; font-weight: 400; src: url(${font('Inter-Regular.ttf')}); }`;
const background = 'radial-gradient(120% 70% at 20% 0%, #f7e4d8 0%, #f8f3ec 55%, #f3ebe0 100%)';

// The web logo (web/src/lib/components/Logo.svelte), as on the family listing.
const logo = (size) => `<svg width="${size}" height="${size}" viewBox="0 0 64 64"><rect x="4" y="8" width="56" height="48" rx="12" fill="#a7471e"/>
<rect x="11" y="15" width="42" height="34" rx="6" fill="#fbf1e6"/><circle cx="41" cy="26" r="5" fill="#e7a23a"/>
<path d="M11 43 23 31l9 9 6-6 15 15H17a6 6 0 0 1-6-6z" fill="#4f7a5a"/></svg>`;

function screenshot(d, title, sub, file) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}
html, body { margin: 0; width: ${d.w}px; height: ${d.h}px; overflow: hidden; }
body { background: ${background}; display: flex; flex-direction: column; align-items: center; font-family: Inter, sans-serif; }
h1 { font-family: Fraunces, serif; font-weight: 600; color: #2a221d; font-size: ${d.title}px; line-height: 1.05;
  letter-spacing: -0.01em; margin: ${d.top}px 60px 0; text-align: center; }
p { color: #675a50; font-size: ${d.sub}px; line-height: 1.3; margin: ${Math.round(d.sub * 0.4)}px 60px 0; text-align: center; }
.shot { margin-top: ${d.gap}px; height: ${d.shotH}px; border-radius: ${d.radius}px; overflow: hidden; flex: none;
  box-shadow: 0 30px 90px rgba(124, 52, 18, 0.22), 0 0 0 2px rgba(42, 34, 29, 0.08); }
.shot img { display: block; height: 100%; }
</style></head><body><h1>${title}</h1><p>${sub}</p>
<div class="shot"><img src="${png(file)}"></div></body></html>`;
}

// The brand on the left, the frame app's own first screen on a tablet on the right.
function featureGraphic(lang) {
  return `<!doctype html><html><head><meta charset="utf-8"><style>${fontFaces}
html, body { margin: 0; width: 1024px; height: 500px; overflow: hidden; }
body { background: ${background}; font-family: Inter, sans-serif; position: relative; }
.brand { position: absolute; left: 64px; top: 0; bottom: 0; display: flex; flex-direction: column; justify-content: center; }
.mark { display: flex; align-items: center; gap: 16px; }
.word { font-family: Fraunces, serif; font-weight: 600; font-size: 72px; letter-spacing: -0.02em; color: #2a221d; }
.tag { margin-top: 18px; color: #675a50; font-size: 30px; max-width: 380px; line-height: 1.25; }
.tablet { position: absolute; right: 36px; top: 112px; padding: 14px; border-radius: 30px; background: #1c1714;
  box-shadow: 0 30px 80px rgba(124, 52, 18, 0.30); transform: rotate(-3deg); }
.tablet img { display: block; width: 416px; height: 260px; border-radius: 12px; }
</style></head><body>
<div class="tablet"><img src="${png(join(raw, `ten-${lang}-photo.png`))}"></div>
<div class="brand"><div class="mark">${logo(76)}<span class="word">kinframe</span></div><div class="tag">${tagline[lang]}</div></div>
</body></html>`;
}

// The frame's launcher icon (frame/src/main/res/mipmap-anydpi-v26/ic_launcher.xml): the kin_accent background with
// drawable/ic_launcher_fg.xml on top, full bleed; Play applies its own mask. Encoded by a canvas: Play asks for a 32-bit
// (RGBA) PNG.
const iconSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108"><rect width="108" height="108" fill="#C0623E"/>
<path fill="#FFFFFF" d="M34,36h40a6,6 0 0 1 6,6v24a6,6 0 0 1 -6,6h-40a6,6 0 0 1 -6,-6v-24a6,6 0 0 1 6,-6z"/>
<path fill="#C0623E" d="M54,60c-0.6,-0.5 -8,-5 -8,-10a4.5,4.5 0 0 1 8,-2.8a4.5,4.5 0 0 1 8,2.8c0,5 -7.4,9.5 -8,10z"/></svg>`;

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

const has = (cmd) => { try { execFileSync('which', [cmd], { stdio: 'ignore' }); return true; } catch { return false; } };
const magick = has('magick');

/** Renders the page to an opaque PNG (no alpha channel). */
async function opaque(tab, html, w, h, file) {
  await tab.setContent(html, { waitUntil: 'load' });
  await tab.evaluate(() => document.fonts.ready);
  const clip = { x: 0, y: 0, width: w, height: h };
  if (magick) {
    const tmp = `${file}.tmp.png`;
    await tab.screenshot({ path: tmp, type: 'png', clip });
    execFileSync('magick', [tmp, '-alpha', 'off', `PNG24:${file}`]);
    rmSync(tmp);
  } else {
    const tmp = `${file}.jpg`;
    await tab.screenshot({ path: tmp, type: 'jpeg', quality: 95, clip });
    execFileSync('sips', ['-s', 'format', 'png', tmp, '--out', file], { stdio: 'ignore' });
    rmSync(tmp);
  }
  console.log(file);
}

const browser = await chromium.launch();
for (const [dev, d] of Object.entries(devices)) {
  const ctx = await browser.newContext({ viewport: { width: d.w, height: d.h }, deviceScaleFactor: 1 });
  const tab = await ctx.newPage();
  for (const [lang, locale] of Object.entries(locales)) {
    const dir = join(out, locale, 'images', d.dir);
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    for (const [i, scene] of d.scenes.entries()) {
      const file = join(raw, `${dev}-${lang}-${scene}.png`);
      if (!existsSync(file)) throw new Error(`missing ${file}: run StoreScreenshotTest first`);
      const [title, sub] = copy[lang][scene];
      await opaque(tab, screenshot(d, title, sub, file), d.w, d.h, join(dir, `${i + 1}.png`));
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
