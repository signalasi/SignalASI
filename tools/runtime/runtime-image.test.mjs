import assert from 'node:assert/strict';
import {
  chmodSync,
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import test from 'node:test';
import { buildRuntimeImage, validateRuntimeImageSource } from './runtime-image.mjs';

function fixture(packId = 'python-uv') {
  const root = mkdtempSync(join(tmpdir(), 'signalasi-runtime-image-test-'));
  const source = join(root, 'source');
  mkdirSync(join(source, 'bin'), { recursive: true });
  const names = {
    'python-uv': ['python3', 'uv'],
    'node-js': ['node', 'tsx'],
    'browser-automation': ['signalasi-browser', 'playwright'],
    ffmpeg: ['ffmpeg', 'ffprobe'],
  }[packId];
  for (const name of names) {
    const path = join(source, 'bin', name);
    writeFileSync(path, '#!/bin/sh\nexit 0\n', 'utf8');
    chmodSync(path, 0o755);
  }
  return { root, source };
}

test('runtime image validation requires every pack entrypoint', () => {
  const { root, source } = fixture();
  try {
    rmSync(join(source, 'bin', 'uv'));
    assert.throws(
      () => validateRuntimeImageSource('python-uv', '1.0.0', source),
      /entrypoint/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runtime image validation accepts a contained symbolic link', (context) => {
  if (process.platform === 'win32') {
    context.skip('Windows symlink creation requires host policy support');
    return;
  }
  const { root, source } = fixture('node-js');
  try {
    mkdirSync(join(source, 'lib'), { recursive: true });
    writeFileSync(join(source, 'lib', 'npm.js'), 'export {};\n', 'utf8');
    symlinkSync('../lib/npm.js', join(source, 'bin', 'npm'));
    assert.doesNotThrow(
      () => validateRuntimeImageSource('node-js', '24.18.0', source, [], 'linux'),
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runtime image validation rejects a symbolic link outside the image', (context) => {
  if (process.platform === 'win32') {
    context.skip('Windows symlink creation requires host policy support');
    return;
  }
  const { root, source } = fixture('node-js');
  try {
    const outside = join(root, 'outside.js');
    writeFileSync(outside, 'export {};\n', 'utf8');
    symlinkSync(outside, join(source, 'bin', 'outside'));
    assert.throws(
      () => validateRuntimeImageSource('node-js', '24.18.0', source, [], 'linux'),
      /escapes its source root/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runtime image builder emits a matching descriptor and signing config', () => {
  const { root, source } = fixture('ffmpeg');
  const output = join(root, 'ffmpeg.img');
  let descriptor;
  try {
    const result = buildRuntimeImage({
      packId: 'ffmpeg',
      version: '8.0.1',
      sourceRoot: source,
      outputPath: output,
      license: 'GPL-2.0-or-later',
      platform: 'win32',
      squashfsBuilder: (stagedRoot, stagedImage) => {
        descriptor = JSON.parse(readFileSync(join(stagedRoot, 'signalasi-pack.json'), 'utf8'));
        copyFileSync(join(stagedRoot, 'bin', 'ffmpeg'), stagedImage);
      },
    });

    assert.deepEqual(descriptor.capabilities, ['ffmpeg.execute', 'ffprobe.inspect']);
    assert.equal(result.config.id, 'ffmpeg');
    assert.deepEqual(result.config.dependencies, ['linux-base']);
    assert.equal(JSON.parse(readFileSync(`${output}.config.json`, 'utf8')).version, '8.0.1');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runtime image builder normalizes and validates explicit dependencies', () => {
  const { root, source } = fixture('ffmpeg');
  try {
    const result = buildRuntimeImage({
      packId: 'ffmpeg',
      version: '8.0.1',
      sourceRoot: source,
      outputPath: join(root, 'ffmpeg.img'),
      license: 'GPL-2.0-or-later',
      dependencies: ['cpp', 'linux-base', 'cpp'],
      platform: 'win32',
      squashfsBuilder: (stagedRoot, stagedImage) => {
        copyFileSync(join(stagedRoot, 'bin', 'ffmpeg'), stagedImage);
      },
    });
    assert.deepEqual(result.config.dependencies, ['cpp', 'linux-base']);
    assert.throws(
      () => buildRuntimeImage({
        packId: 'ffmpeg',
        version: '8.0.1',
        sourceRoot: source,
        outputPath: join(root, 'invalid.img'),
        license: 'GPL-2.0-or-later',
        dependencies: ['ffmpeg'],
        platform: 'win32',
        squashfsBuilder: () => {},
      }),
      /dependency/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('browser automation pack requires its launcher and Playwright CLI', () => {
  const { root, source } = fixture('browser-automation');
  try {
    const result = buildRuntimeImage({
      packId: 'browser-automation',
      version: '1.61.0',
      sourceRoot: source,
      outputPath: join(root, 'browser.img'),
      license: 'Apache-2.0 AND BSD-3-Clause',
      dependencies: ['node-js'],
      platform: 'win32',
      squashfsBuilder: (stagedRoot, stagedImage) => {
        copyFileSync(join(stagedRoot, 'bin', 'signalasi-browser'), stagedImage);
      },
    });
    assert.deepEqual(result.config.capabilities, ['browser.automation.execute']);
    assert.deepEqual(result.config.dependencies, ['linux-base', 'node-js']);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
