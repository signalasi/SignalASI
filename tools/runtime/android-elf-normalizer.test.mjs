import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import {
  androidApkLibraryName,
  normalizeAndroidElfBundle,
} from './android-elf-normalizer.mjs';

const HEADER = `
  Class:                             ELF64
  Type:                              DYN (Shared object file)
  Machine:                           AArch64
`;

test('Android APK library names preserve identity without version suffixes', () => {
  assert.equal(androidApkLibraryName('libz.so.1'), 'libz_1.so');
  assert.equal(androidApkLibraryName('libglib-2.0.so.0'), 'libglib-2.0_0.so');
  assert.equal(androidApkLibraryName('libsignalasi_qemu.so'), 'libsignalasi_qemu.so');
  assert.throws(() => androidApkLibraryName('qemu-system-aarch64'), /cannot be packaged/);
});

test('normalizer rewrites versioned dependencies into APK-packaged libraries', () => {
  const root = mkdtempSync(join(tmpdir(), 'signalasi-elf-normalizer-test-'));
  const libraries = join(root, 'arm64-v8a');
  mkdirSync(libraries);
  writeFileSync(join(libraries, 'libsignalasi_qemu.so'), 'qemu');
  writeFileSync(join(libraries, 'libz.so.1'), 'zlib');
  const dependencies = new Map([
    ['libsignalasi_qemu.so', ['libc.so', 'libz.so.1']],
    ['libz_1.so', ['libc.so']],
  ]);
  const calls = [];
  const commandRunner = (command, arguments_) => {
    calls.push([command, ...arguments_]);
    const name = arguments_.at(-1).split(/[\\/]/).at(-1);
    if (command === 'patchelf' && arguments_[0] === '--replace-needed') {
      dependencies.set(name, dependencies.get(name).map((value) =>
        value === arguments_[1] ? arguments_[2] : value));
      return '';
    }
    if (command === 'patchelf') return '';
    if (arguments_[0] === '--file-header') return HEADER;
    return `${dependencies.get(name).map((value) =>
      `(NEEDED) Shared library: [${value}]`).join('\n')}\n(RUNPATH) Library runpath: [$ORIGIN]\n`;
  };
  try {
    const manifest = normalizeAndroidElfBundle({
      libraryDirectory: libraries,
      manifest: {
        format_version: 1,
        architecture: 'arm64-v8a',
        entry_file: 'libsignalasi_qemu.so',
        files: [
          { name: 'libsignalasi_qemu.so' },
          { name: 'libz.so.1' },
        ],
      },
      commandRunner,
    });
    assert.deepEqual(manifest.files.map((file) => file.name), [
      'libsignalasi_qemu.so',
      'libz_1.so',
    ]);
    assert.deepEqual(
      manifest.files.find((file) => file.name === 'libsignalasi_qemu.so').dependencies,
      ['libc.so', 'libz_1.so'],
    );
    assert.equal(readFileSync(join(libraries, 'libz_1.so'), 'utf8'), 'zlib');
    assert.ok(calls.some((call) => call[1] === '--set-soname' && call[2] === 'libz_1.so'));
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
