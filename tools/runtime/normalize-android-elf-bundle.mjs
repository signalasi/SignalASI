import { parseArgs } from 'node:util';
import { readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { normalizeAndroidElfBundle } from './android-elf-normalizer.mjs';
import { writeBundleManifest } from './android-elf-bundle.mjs';

const { values } = parseArgs({
  options: {
    'jni-root': { type: 'string' },
    'asset-root': { type: 'string' },
    readelf: { type: 'string', default: 'llvm-readelf' },
    patchelf: { type: 'string', default: 'patchelf' },
  },
  strict: true,
});
for (const name of ['jni-root', 'asset-root']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

const jniRoot = resolve(values['jni-root']);
const assetRoot = resolve(values['asset-root']);
const manifestPath = join(jniRoot, 'signalasi-qemu-bundle.json');
const assetManifestPath = join(assetRoot, 'runtime', 'qemu', 'bundle.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const normalized = normalizeAndroidElfBundle({
  libraryDirectory: join(jniRoot, 'arm64-v8a'),
  manifest,
  readelf: values.readelf,
  patchelf: values.patchelf,
});
writeBundleManifest(manifestPath, normalized);
writeBundleManifest(assetManifestPath, normalized);
process.stdout.write(`${manifestPath}\n${assetManifestPath}\n`);
