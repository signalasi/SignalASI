import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  catalogSigningPayload,
  lengthPrefixed,
  manifestSigningPayload,
} from './runtime-signing.mjs';

test('length prefixes count UTF-8 bytes', () => {
  assert.equal(lengthPrefixed(['SignalASI', '\u00e9']), '9:SignalASI2:\u00e9');
});

test('manifest signing payload is stable across set ordering', () => {
  const manifest = {
    format_version: 1,
    id: 'python-uv',
    version: '1.0.0',
    architecture: 'arm64-v8a',
    image_file: 'python.img',
    image_sha256: 'A'.repeat(64),
    capabilities: ['python.execute', 'uv.sync'],
    dependencies: ['linux-base'],
    installed_size_bytes: 2048,
    archive_size_bytes: 1024,
    minimum_host_version_code: 1,
    guest_api_version: 1,
    license: 'Apache-2.0',
    signature_key_id: 'B'.repeat(64),
  };
  const reordered = {
    ...manifest,
    capabilities: [...manifest.capabilities].reverse(),
    dependencies: [...manifest.dependencies].reverse(),
  };
  assert.deepEqual(manifestSigningPayload(manifest), manifestSigningPayload(reordered));
  assert.match(manifestSigningPayload(manifest).toString('utf8'), /^1:1/);
});

test('manifest canonical payload matches the Android contract vector', () => {
  const manifest = {
    format_version: 1,
    id: 'linux-base',
    version: '1.0.0',
    architecture: 'arm64-v8a',
    image_file: 'linux.img',
    image_sha256: 'a'.repeat(64),
    capabilities: ['shell.execute'],
    dependencies: [],
    installed_size_bytes: 2048,
    archive_size_bytes: 1024,
    minimum_host_version_code: 60,
    guest_api_version: 1,
    license: 'Apache-2.0',
    signature_key_id: 'b'.repeat(64),
  };
  assert.equal(
    createHash('sha256').update(manifestSigningPayload(manifest)).digest('hex'),
    'dfca26a4c789efa795be3bcc56c45615a6be67857a43adb5458410cbdb2be3f3',
  );
});

test('catalog signing payload is stable across entry ordering', () => {
  const entry = (packId) => ({
    pack_id: packId,
    version: '1.0.0',
    architecture: 'arm64-v8a',
    download_url: `https://downloads.example/${packId}.sarpack`,
    archive_sha256: 'c'.repeat(64),
    archive_size_bytes: 1024,
    installed_size_bytes: 2048,
    dependencies: packId === 'python-uv' ? ['linux-base'] : [],
    license: 'Apache-2.0',
    minimum_host_version_code: 1,
    guest_api_version: 1,
    release_notes: '',
  });
  const catalog = {
    format_version: 1,
    catalog_version: '1.0.0',
    generated_at_millis: 1000,
    expires_at_millis: 2000,
    entries: [entry('python-uv'), entry('linux-base')],
    signature_key_id: 'd'.repeat(64),
  };
  assert.deepEqual(
    catalogSigningPayload(catalog),
    catalogSigningPayload({ ...catalog, entries: [...catalog.entries].reverse() }),
  );
});
