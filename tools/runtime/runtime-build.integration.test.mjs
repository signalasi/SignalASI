import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { verify, X509Certificate } from 'node:crypto';
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  catalogSigningPayload,
  manifestSigningPayload,
} from './runtime-signing.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const jar = process.env.JAVA_HOME
  ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar')
  : 'jar';

function commandAvailable(command, args) {
  return spawnSync(command, args, { encoding: 'utf8' }).status === 0;
}

function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${command} failed: ${(result.stderr || result.stdout || '').trim()}`);
  }
  return result.stdout;
}

test('pack and catalog builders produce mutually verified release artifacts', {
  skip: !commandAvailable('openssl', ['version']) || !commandAvailable(jar, ['--version']),
}, () => {
  const temporary = mkdtempSync(join(tmpdir(), 'signalasi-runtime-release-test-'));
  try {
    const key = join(temporary, 'runtime-key.pem');
    const certificatePath = join(temporary, 'runtime-cert.pem');
    run('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
      '-keyout', key,
      '-out', certificatePath,
      '-subj', '/CN=SignalASI Runtime Test',
      '-days', '1',
    ], temporary);
    const certificate = new X509Certificate(readFileSync(certificatePath));
    const trustAnchors = join(temporary, 'trust-anchors.json');
    writeFileSync(trustAnchors, JSON.stringify({
      format_version: 1,
      certificates: [certificate.raw.toString('base64')],
    }));

    const image = join(temporary, 'linux-base.img');
    writeFileSync(image, Buffer.alloc(1024, 0x5a));
    const config = join(temporary, 'linux-base.json');
    writeFileSync(config, JSON.stringify({
      id: 'linux-base',
      version: '1.0.0',
      architecture: 'arm64-v8a',
      image: './linux-base.img',
      image_file: 'linux-base.img',
      capabilities: ['shell.execute'],
      dependencies: [],
      license: 'Apache-2.0',
      minimum_host_version_code: 60,
      guest_api_version: 1,
      release_notes: 'Integration test runtime',
    }));

    const release = join(temporary, 'release');
    mkdirSync(release);
    const archive = join(release, 'linux-base-1.0.0-arm64-v8a.sarpack');
    run(process.execPath, [
      join(root, 'tools/runtime/build-runtime-pack.mjs'),
      '--config', config,
      '--output', archive,
      '--certificate', certificatePath,
      '--key', key,
      '--jar', jar,
    ]);
    const extracted = join(temporary, 'extracted');
    mkdirSync(extracted);
    run(jar, ['--extract', '--file', archive], extracted);
    const manifest = JSON.parse(readFileSync(join(extracted, 'manifest.json'), 'utf8'));
    assert.equal(manifest.id, 'linux-base');
    assert.equal(verify(
      'sha256',
      manifestSigningPayload(manifest),
      certificate.publicKey,
      Buffer.from(manifest.signature, 'base64'),
    ), true);

    const catalogPath = join(release, 'android-runtime-catalog-v1.json');
    run(process.execPath, [
      join(root, 'tools/runtime/build-runtime-catalog.mjs'),
      '--entries', release,
      '--output', catalogPath,
      '--base-url', 'https://downloads.example/runtime-1.0.0/',
      '--version', '1.0.0',
      '--expires-days', '30',
      '--certificate', certificatePath,
      '--key', key,
      '--trust-anchors', trustAnchors,
    ]);
    const catalog = JSON.parse(readFileSync(catalogPath, 'utf8'));
    assert.equal(catalog.entries.length, 1);
    assert.equal(catalog.entries[0].pack_id, 'linux-base');
    assert.equal(verify(
      'sha256',
      catalogSigningPayload(catalog),
      certificate.publicKey,
      Buffer.from(catalog.signature, 'base64'),
    ), true);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});

test('pack builder rejects a signed metadata plan with missing runtime capabilities', () => {
  const temporary = mkdtempSync(join(tmpdir(), 'signalasi-runtime-capability-test-'));
  try {
    writeFileSync(join(temporary, 'ffmpeg.img'), Buffer.alloc(32));
    const config = join(temporary, 'ffmpeg.json');
    writeFileSync(config, JSON.stringify({
      id: 'ffmpeg',
      version: '1.0.0',
      architecture: 'arm64-v8a',
      image: './ffmpeg.img',
      capabilities: [],
      dependencies: ['linux-base'],
      license: 'GPL-2.0-or-later',
    }));
    const result = spawnSync(process.execPath, [
      join(root, 'tools/runtime/build-runtime-pack.mjs'),
      '--config', config,
      '--output', join(temporary, 'ffmpeg.sarpack'),
      '--certificate', join(temporary, 'unused-cert.pem'),
      '--key', join(temporary, 'unused-key.pem'),
    ], { cwd: root, encoding: 'utf8' });

    assert.notEqual(result.status, 0);
    assert.match(`${result.stderr}${result.stdout}`, /capabilities are incomplete/);
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
});
