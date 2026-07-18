import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import {
  collectAndroidElfBundle,
  parseDynamicSection,
  validateAarch64ElfHeader,
} from './android-elf-bundle.mjs';

const HEADER = `
  Class:                             ELF64
  Type:                              DYN (Position-Independent Executable file)
  Machine:                           AArch64
`;

test('dynamic section parser extracts dependencies and search paths', () => {
  const value = `
  0x0000000000000001 (NEEDED) Shared library: [libglib-2.0.so]
  0x000000000000001d (RUNPATH) Library runpath: [$ORIGIN]
  `;
  assert.deepEqual(parseDynamicSection(value), {
    needed: ['libglib-2.0.so'],
    searchPaths: ['$ORIGIN'],
  });
  assert.doesNotThrow(() => validateAarch64ElfHeader(HEADER));
  assert.throws(() => validateAarch64ElfHeader(HEADER.replace('AArch64', 'X86-64')), /AArch64/);
});

test('bundle collector follows non-system dependencies exactly once', () => {
  const root = mkdtempSync(join(tmpdir(), 'signalasi-elf-bundle-test-'));
  const libraries = join(root, 'lib');
  const output = join(root, 'output');
  mkdirSync(libraries);
  const entry = join(root, 'qemu-system-aarch64');
  const glib = join(libraries, 'libglib-2.0.so');
  writeFileSync(entry, 'qemu');
  writeFileSync(glib, 'glib');
  const commandRunner = (command, arguments_) => {
    if (command === 'patchelf') return '';
    if (arguments_[0] === '--file-header') return HEADER;
    const name = arguments_.at(-1).split(/[\\/]/).at(-1);
    return name === 'libsignalasi_qemu.so'
      ? '(NEEDED) Shared library: [libglib-2.0.so]\n(NEEDED) Shared library: [libc.so]\n(RUNPATH) Library runpath: [$ORIGIN]\n'
      : '(NEEDED) Shared library: [libc.so]\n(RUNPATH) Library runpath: [$ORIGIN]\n';
  };
  try {
    const manifest = collectAndroidElfBundle({
      entryFile: entry,
      outputDirectory: output,
      libraryDirectories: [libraries],
      commandRunner,
    });
    assert.deepEqual(manifest.files.map((file) => file.name), [
      'libglib-2.0.so',
      'libsignalasi_qemu.so',
    ]);
    assert.equal(readFileSync(join(output, 'libsignalasi_qemu.so'), 'utf8'), 'qemu');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('bundle collector rejects a dependency symlink that escapes its root', (context) => {
  if (process.platform === 'win32') {
    context.skip('Windows symlink creation requires host policy support');
    return;
  }
  const root = mkdtempSync(join(tmpdir(), 'signalasi-elf-bundle-link-test-'));
  const libraries = join(root, 'lib');
  const outside = join(root, 'outside');
  const output = join(root, 'output');
  mkdirSync(libraries);
  mkdirSync(outside);
  const entry = join(root, 'qemu-system-aarch64');
  const escaped = join(outside, 'libescape.so');
  writeFileSync(entry, 'qemu');
  writeFileSync(escaped, 'escape');
  symlinkSync(escaped, join(libraries, 'libescape.so'));
  try {
    assert.throws(() => collectAndroidElfBundle({
      entryFile: entry,
      outputDirectory: output,
      libraryDirectories: [libraries],
      commandRunner: (command, arguments_) => {
        if (command === 'patchelf') return '';
        if (arguments_[0] === '--file-header') return HEADER;
        return '(NEEDED) Shared library: [libescape.so]\n(RUNPATH) Library runpath: [$ORIGIN]\n';
      },
    }), /escapes its library root/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
