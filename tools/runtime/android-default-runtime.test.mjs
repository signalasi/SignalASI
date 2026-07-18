import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { validateDefaultEntries } from './android-default-runtime.mjs';

const entry = (packId, dependencies = []) => ({
  pack_id: packId,
  version: '1.0.0',
  architecture: 'arm64-v8a',
  archive_sha256: 'a'.repeat(64),
  archive_size_bytes: 1_024,
  installed_size_bytes: 2_048,
  dependencies,
  asset_path: `runtime/bootstrap/${packId}-1.0.0-arm64-v8a.sarpack`,
});

test('default Android runtime requires base Linux and Python with uv', () => {
  const entries = [entry('linux-base'), entry('python-uv', ['linux-base'])];
  assert.equal(validateDefaultEntries(entries), entries);
});

test('default Android runtime rejects missing or misordered dependencies', () => {
  assert.throws(() => validateDefaultEntries([entry('linux-base')]), /linux-base and python-uv/);
  assert.throws(() => validateDefaultEntries([
    entry('linux-base', ['python-uv']),
    entry('python-uv', ['linux-base']),
  ]), /linux-base must not depend/);
  assert.throws(() => validateDefaultEntries([
    entry('linux-base'),
    entry('python-uv'),
  ]), /python-uv must depend/);
});

test('runtime launcher leaves fortify configuration to the Buildroot toolchain', () => {
  const makefile = readFileSync(new URL(
    '../../apps/android/runtime/buildroot-external/package/signalasi-runtime-launcher/signalasi-runtime-launcher.mk',
    import.meta.url,
  ), 'utf8');
  assert.doesNotMatch(makefile, /_FORTIFY_SOURCE/);
});
