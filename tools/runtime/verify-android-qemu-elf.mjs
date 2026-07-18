import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { parseArgs } from 'node:util';
import {
  parseDynamicSection,
  validateAarch64ElfHeader,
  validateAndroidProgramHeaders,
} from './android-elf-bundle.mjs';

const { values } = parseArgs({
  options: {
    'jni-root': { type: 'string' },
    readelf: { type: 'string', default: 'llvm-readelf' },
  },
  strict: true,
});

if (!values['jni-root']) throw new Error('--jni-root is required');

const jniRoot = resolve(values['jni-root']);
const manifestPath = join(jniRoot, 'signalasi-qemu-bundle.json');
if (!existsSync(manifestPath)) throw new Error('SignalASI QEMU bundle metadata is missing');

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
if (manifest.format_version !== 1 || manifest.architecture !== 'arm64-v8a' ||
    !Array.isArray(manifest.files) || manifest.files.length === 0) {
  throw new Error('SignalASI QEMU bundle metadata is invalid');
}

for (const file of manifest.files) {
  const path = join(jniRoot, 'arm64-v8a', file.name);
  if (!existsSync(path) || statSync(path).size !== file.size_bytes) {
    throw new Error(`SignalASI QEMU library is missing or changed: ${file.name}`);
  }
  const digest = createHash('sha256').update(readFileSync(path)).digest('hex');
  if (digest !== file.sha256) {
    throw new Error(`SignalASI QEMU library digest changed: ${file.name}`);
  }

  validateAarch64ElfHeader(runReadelf(values.readelf, ['--file-header', path]));
  validateAndroidProgramHeaders(runReadelf(values.readelf, ['--program-headers', '--wide', path]));
  const dynamic = parseDynamicSection(runReadelf(values.readelf, ['--dynamic', '--wide', path]));
  if (dynamic.searchPaths.some((entry) => entry !== '$ORIGIN')) {
    throw new Error(`SignalASI QEMU library has an unsafe runpath: ${file.name}`);
  }
  if (dynamic.needed.some((dependency) => /\.so\.\d/.test(dependency))) {
    throw new Error(`SignalASI QEMU library has a versioned dependency: ${file.name}`);
  }
  const expectedDependencies = [...(file.dependencies || [])].sort();
  if (JSON.stringify(dynamic.needed) !== JSON.stringify(expectedDependencies)) {
    throw new Error(`SignalASI QEMU dependency metadata changed: ${file.name}`);
  }
}

process.stdout.write(`Android QEMU ELF bundle verified (${manifest.files.length} files)\n`);

function runReadelf(command, arguments_) {
  const result = spawnSync(command, arguments_, { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${basename(command)} failed: ${(result.stderr || result.stdout || '').trim()}`);
  }
  return result.stdout;
}
