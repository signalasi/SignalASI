import { parseArgs } from 'node:util';
import { collectAndroidElfBundle, writeBundleManifest } from './android-elf-bundle.mjs';

const { values } = parseArgs({
  options: {
    entry: { type: 'string' },
    output: { type: 'string' },
    manifest: { type: 'string' },
    'library-dir': { type: 'string', multiple: true, default: [] },
    readelf: { type: 'string', default: 'llvm-readelf' },
    patchelf: { type: 'string', default: 'patchelf' },
    'qemu-version': { type: 'string', default: '10.2.1' },
    'qemu-source-sha256': { type: 'string', default: '' },
    'builder-commit': { type: 'string', default: '' },
    'builder-archive-sha256': { type: 'string', default: '' },
  },
  strict: true,
});

for (const name of ['entry', 'output', 'manifest']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}
if (values['library-dir'].length === 0) throw new Error('--library-dir is required');

const manifest = collectAndroidElfBundle({
  entryFile: values.entry,
  outputDirectory: values.output,
  libraryDirectories: values['library-dir'],
  readelf: values.readelf,
  patchelf: values.patchelf,
  metadata: {
    qemu_version: values['qemu-version'],
    qemu_source_sha256: values['qemu-source-sha256'],
    android_builder_commit: values['builder-commit'],
    android_builder_archive_sha256: values['builder-archive-sha256'],
  },
});
writeBundleManifest(values.manifest, manifest);
process.stdout.write(`${values.output}\n${values.manifest}\n`);
