// Rainbow Books :: product image pipeline
//
// Reads the untouched originals from assets/originals and writes responsive
// derivatives into the media storage directory. Originals are never modified.
//
//   node optimise-images.mjs [--out DIR] [--crop-watermark]
//
// --crop-watermark trims the bottom 15% of the frame, where the source
// photographs carry a small sparkle mark. It is OFF by default: cropping a
// product photograph is an editorial decision, not one this script should make
// silently. Inspect the output before enabling it.

import sharp from 'sharp';
import { mkdir, readdir, writeFile } from 'node:fs/promises';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const ORIGINALS = join(ROOT, 'assets', 'originals');

const args = process.argv.slice(2);
const outFlag = args.indexOf('--out');
const OUT = resolve(outFlag >= 0 ? args[outFlag + 1] : join(ROOT, 'media'));
const CROP_WATERMARK = args.includes('--crop-watermark');

// fraction of height trimmed from the bottom when --crop-watermark is on
const WATERMARK_STRIP = 0.15;

const WIDTHS = [480, 800, 1280, 1920];

// each source file belongs to one product; the a/b suffix is the view
const PRODUCTS = {
  '1': { sku: 'KV-BH-01', dir: 'kv-bh-01' },
  '2': { sku: 'KV-BH-02', dir: 'kv-bh-02' },
  '3': { sku: 'KV-BH-03', dir: 'kv-bh-03' },
  '4': { sku: 'KV-BH-04', dir: 'kv-bh-04' },
  '5': { sku: 'KV-BH-05', dir: 'kv-bh-05' },
};

const FORMATS = [
  { ext: 'avif', apply: (p) => p.avif({ quality: 55, effort: 5 }) },
  { ext: 'webp', apply: (p) => p.webp({ quality: 78 }) },
  { ext: 'jpg',  apply: (p) => p.jpeg({ quality: 82, mozjpeg: true }) },
];

const manifest = [];

async function processOne(file) {
  const match = /^(\d)([ab])\.png$/i.exec(file);
  if (!match) {
    console.warn(`  skipped ${file} - name does not match <digit><a|b>.png`);
    return;
  }

  const [, productKey, view] = match;
  const product = PRODUCTS[productKey];
  if (!product) {
    console.warn(`  skipped ${file} - no product mapped to "${productKey}"`);
    return;
  }

  const srcPath = join(ORIGINALS, file);
  const targetDir = join(OUT, 'products', product.dir);
  await mkdir(targetDir, { recursive: true });

  const base = sharp(srcPath, { failOn: 'error' });
  const meta = await base.metadata();

  let cropTop = 0;
  let cropHeight = meta.height;
  if (CROP_WATERMARK) {
    cropHeight = Math.round(meta.height * (1 - WATERMARK_STRIP));
  }

  const storageKey = `products/${product.dir}/${view}`;
  const entry = {
    sku: product.sku,
    view,
    storageKey,
    source: file,
    sourceWidth: meta.width,
    sourceHeight: meta.height,
    cropped: CROP_WATERMARK,
    derivatives: [],
  };

  // clamp rather than skip: a requested width above the source becomes the
  // source width, so the largest tier is always the full native resolution
  const widths = [...new Set(WIDTHS.map((w) => Math.min(w, meta.width)))].sort((a, b) => a - b);

  for (const width of widths) {
    for (const { ext, apply } of FORMATS) {
      const pipeline = sharp(srcPath)
        .extract({ left: 0, top: cropTop, width: meta.width, height: cropHeight })
        .resize({ width, withoutEnlargement: true });

      const outFile = join(targetDir, `${view}-${width}.${ext}`);
      const info = await apply(pipeline).toFile(outFile);
      entry.derivatives.push({ width, ext, bytes: info.size, height: info.height });
    }
  }

  // the widest webp carries the intrinsic dimensions recorded in the database
  const widest = entry.derivatives
    .filter((d) => d.ext === 'webp')
    .sort((a, b) => b.width - a.width)[0];
  entry.width = widest?.width ?? null;
  entry.height = widest?.height ?? null;

  manifest.push(entry);

  const total = entry.derivatives.reduce((sum, d) => sum + d.bytes, 0);
  console.log(
    `  ${file} -> ${product.sku} ${view}  ${entry.derivatives.length} files, ` +
    `${(total / 1024 / 1024).toFixed(2)} MB total`
  );
}

async function main() {
  console.log(`originals : ${ORIGINALS}`);
  console.log(`output    : ${OUT}`);
  console.log(`watermark : ${CROP_WATERMARK ? `cropping bottom ${WATERMARK_STRIP * 100}%` : 'left intact (use --crop-watermark to trim)'}`);
  console.log('');

  const files = (await readdir(ORIGINALS)).filter((f) => /\.png$/i.test(f)).sort();
  if (files.length === 0) {
    console.error('no .png files found in assets/originals');
    process.exit(1);
  }

  for (const file of files) {
    await processOne(file);
  }

  manifest.sort((a, b) => a.sku.localeCompare(b.sku) || a.view.localeCompare(b.view));
  await writeFile(join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2));

  const bytes = manifest.flatMap((m) => m.derivatives).reduce((s, d) => s + d.bytes, 0);
  console.log('');
  console.log(`${manifest.length} images -> ${manifest.flatMap((m) => m.derivatives).length} derivatives, ${(bytes / 1024 / 1024).toFixed(1)} MB`);
  console.log(`manifest written to ${join(OUT, 'manifest.json')}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
