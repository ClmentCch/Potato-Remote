#!/usr/bin/env node
/**
 * Potato Remote — générateur d'identité visuelle.
 * Source unique de vérité : brand/logo-mark.svg (mascotte vectorielle).
 * Usage : node brand/build-brand.mjs   (nécessite `sharp` installé)
 */
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import sharp from 'sharp';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = (p) => join(root, p);
const read = (p) => readFileSync(out(p), 'utf8');

const markSvg = read('brand/logo-mark.svg');
const inner = markSvg
  .replace(/^[\s\S]*?<svg[^>]*>/, '')
  .replace(/<\/svg>\s*$/, '');

const CREAM = '#FFF8EC';
const PECHE = '#F7E7CE';

/** Badge carré arrondi : fond crème + mascotte centrée. */
function badge({ size = 512, radius = 116, padding = 0.86, ring = true } = {}) {
  const offset = (size * (1 - padding)) / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}">
  <rect width="${size}" height="${size}" rx="${radius}" fill="${CREAM}"/>
  ${ring ? `<rect x="${size * 0.035}" y="${size * 0.035}" width="${size * 0.93}" height="${size * 0.93}" rx="${radius * 0.88}" fill="none" stroke="${PECHE}" stroke-width="${Math.max(2, size * 0.012)}"/>` : ''}
  <g transform="translate(${offset} ${offset}) scale(${padding})">${inner}</g>
</svg>`;
}

/** Favicon simplifié : lisible à 16 px (gros yeux, pas de petits détails). */
function faviconMark() {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">
  <defs>
    <linearGradient id="fv" x1="0.2" y1="0" x2="0.8" y2="1">
      <stop offset="0" stop-color="#F7E5BE"/><stop offset="1" stop-color="#D3A76C"/>
    </linearGradient>
  </defs>
  <rect width="64" height="64" rx="15" fill="${CREAM}"/>
  <path d="M 18 22 C 24 17 40 16 47 21 C 54 26 55 37 51 44 C 47 51 38 54 30 53 C 22 52 14 46 13 38 C 12 30 13 26 18 22 Z"
        fill="url(#fv)" stroke="#5A3A22" stroke-width="3.2" stroke-linejoin="round"/>
  <g fill="#3A2418"><circle cx="25" cy="34" r="3.1"/><circle cx="40" cy="33" r="3.1"/></g>
  <path d="M 28 41 C 31 44 36 44 39 40" fill="none" stroke="#3A2418" stroke-width="2.8" stroke-linecap="round"/>
  <path d="M 29 21 C 28 17 31 14 35 13" fill="none" stroke="#6F8F58" stroke-width="2.6" stroke-linecap="round"/>
  <path d="M 35 13 C 39 10 44 11 46 13 C 43 17 38 17 35 13 Z" fill="#8FB077" stroke="#5A3A22" stroke-width="2"/>
  </svg>`;
}

/** Icône adaptative Android : mascotte dans la zone de sécurité (66 %). */
function adaptiveForeground(size = 432) {
  const scale = 0.6;
  const offset = (size - 512 * scale) / 2;
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}">
  <g transform="translate(${offset} ${offset}) scale(${scale})">${inner}</g>
</svg>`;
}

const svgFiles = {
  'brand/logo-badge.svg': badge({ ring: true }),
  'brand/favicon.svg': faviconMark(),
  'web/assets/img/logo-mark.svg': markSvg,
  'web/assets/img/logo-badge.svg': badge({ ring: true }),
  'web/assets/img/favicon.svg': faviconMark(),
};

for (const [path, content] of Object.entries(svgFiles)) {
  mkdirSync(dirname(out(path)), { recursive: true });
  writeFileSync(out(path), content);
}

const png = async (svg, path, width, height = width) => {
  mkdirSync(dirname(out(path)), { recursive: true });
  await sharp(Buffer.from(svg)).resize(width, height, { fit: 'contain' }).png({ compressionLevel: 9 }).toFile(out(path));
  console.log('png  ', path, `${width}x${height}`);
};

// --- Web -------------------------------------------------------------------
await png(badge({ ring: true }), 'web/assets/img/icon-512.png', 512);
await png(badge({ ring: true }), 'web/assets/img/icon-192.png', 192);
await png(badge({ ring: true }), 'web/assets/img/apple-touch-icon.png', 180);
await png(badge({ ring: false }), 'web/assets/img/maskable-512.png', 512);
await png(faviconMark(), 'web/assets/img/favicon-32.png', 32);
await png(faviconMark(), 'web/assets/img/favicon-16.png', 16);

// --- Marque (documentation) -----------------------------------------------
await png(markSvg, 'brand/png/logo-mark-1024.png', 1024);
await png(badge({ ring: true }), 'brand/png/logo-badge-1024.png', 1024);
await png(badge({ ring: false }), 'brand/png/logo-maskable-1024.png', 1024);

// --- Android ---------------------------------------------------------------
const launcher = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [bucket, size] of Object.entries(launcher)) {
  await png(badge({ radius: 110, padding: 0.88, ring: false }), `android/app/src/main/res/mipmap-${bucket}/ic_launcher.png`, size);
  await png(badge({ radius: 256, padding: 0.8, ring: false }), `android/app/src/main/res/mipmap-${bucket}/ic_launcher_round.png`, size);
  await png(adaptiveForeground(432), `android/app/src/main/res/mipmap-${bucket}/ic_launcher_foreground.png`, Math.round(size * (108 / 48)));
}
await png(badge({ radius: 116 }), 'android/app/src/main/res/drawable-nodpi/potato_logo.png', 512);
await png(markSvg, 'android/app/src/main/res/drawable-nodpi/potato_splash.png', 768);

copyFileSync(out('brand/logo-mark.svg'), out('web/assets/img/logo-mark.svg'));
console.log('OK — identité Potato Remote générée.');
