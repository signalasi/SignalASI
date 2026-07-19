import {
  cpSync,
  existsSync,
  lstatSync,
  lutimesSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  CAPABILITY_PATTERN,
  PACK_IDS,
  PACK_REQUIRED_CAPABILITIES,
  VERSION_PATTERN,
} from './runtime-signing.mjs';

export const PACK_ENTRYPOINTS = new Map([
  ['python-uv', ['bin/python3', 'bin/uv']],
  ['node-js', ['bin/node', 'bin/tsx']],
  ['go', ['bin/go']],
  ['rust', ['bin/rustc']],
  ['cpp', ['bin/cc', 'bin/c++']],
  ['java', ['bin/java', 'bin/javac']],
  ['browser-automation', ['bin/signalasi-browser', 'bin/playwright']],
  ['ffmpeg', ['bin/ffmpeg', 'bin/ffprobe']],
]);

const MAX_SOURCE_FILES = 100_000;
const MAX_SOURCE_BYTES = 12 * 1024 * 1024 * 1024;

export function validateRuntimeImageSource(
  packId,
  version,
  sourceRoot,
  extraCapabilities = [],
  platform = process.platform,
) {
  if (!PACK_IDS.has(packId) || packId === 'linux-base' || !PACK_ENTRYPOINTS.has(packId)) {
    throw new Error('pack-id is not a supported toolchain image');
  }
  if (!VERSION_PATTERN.test(version)) throw new Error('version is invalid');
  const capabilities = [...new Set([
    ...PACK_REQUIRED_CAPABILITIES.get(packId),
    ...extraCapabilities,
  ])].sort();
  if (capabilities.some((value) => !CAPABILITY_PATTERN.test(value))) {
    throw new Error('capability is invalid');
  }

  const root = realpathSync(resolve(sourceRoot));
  if (!statSync(root).isDirectory()) throw new Error('source must be a directory');
  let fileCount = 0;
  let totalBytes = 0;
  const visit = (directory) => {
    for (const name of readdirSync(directory).sort()) {
      const path = join(directory, name);
      const metadata = lstatSync(path);
      fileCount += 1;
      if (fileCount > MAX_SOURCE_FILES) throw new Error('runtime image has too many files');
      if (!metadata.isSymbolicLink() && platform !== 'win32' && (metadata.mode & 0o6000) !== 0) {
        throw new Error('setuid and setgid files are forbidden');
      }
      if (!metadata.isSymbolicLink() && platform !== 'win32' && (metadata.mode & 0o002) !== 0) {
        throw new Error('world-writable runtime files are forbidden');
      }
      if (metadata.isSymbolicLink()) {
        const resolved = realpathSync(path);
        const fromRoot = relative(root, resolved);
        if (fromRoot === '..' || fromRoot.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`) || isAbsolute(fromRoot)) {
          throw new Error('runtime image symlink escapes its source root');
        }
      } else if (metadata.isDirectory()) {
        visit(path);
      } else if (metadata.isFile()) {
        totalBytes += metadata.size;
        if (totalBytes > MAX_SOURCE_BYTES) throw new Error('runtime image source is too large');
      } else {
        throw new Error('runtime image contains an unsupported filesystem object');
      }
    }
  };
  visit(root);

  for (const entrypoint of PACK_ENTRYPOINTS.get(packId)) {
    const path = resolve(root, entrypoint);
    const resolvedEntrypoint = (() => {
      try {
        return realpathSync(path);
      } catch {
        throw new Error(`runtime entrypoint is unavailable: ${entrypoint}`);
      }
    })();
    const fromRoot = relative(root, resolvedEntrypoint);
    if (fromRoot === '..' || fromRoot.startsWith(`..${process.platform === 'win32' ? '\\' : '/'}`) || isAbsolute(fromRoot)) {
      throw new Error(`runtime entrypoint escapes the source root: ${entrypoint}`);
    }
    const metadata = statSync(path);
    if (!metadata.isFile() || (platform !== 'win32' && (metadata.mode & 0o111) === 0)) {
      throw new Error(`runtime entrypoint is not executable: ${entrypoint}`);
    }
  }
  return { root, capabilities, fileCount, totalBytes };
}

export function buildRuntimeImage({
  packId,
  version,
  sourceRoot,
  outputPath,
  license,
  architecture = 'arm64-v8a',
  extraCapabilities = [],
  dependencies = [],
  sourceDateEpoch = Number(process.env.SOURCE_DATE_EPOCH || 1781395200),
  mksquashfs = 'mksquashfs',
  platform = process.platform,
  squashfsBuilder,
}) {
  if (platform !== 'linux' && !squashfsBuilder) {
    throw new Error('runtime images must be built on Linux');
  }
  if (!Number.isSafeInteger(sourceDateEpoch) || sourceDateEpoch < 0) {
    throw new Error('SOURCE_DATE_EPOCH is invalid');
  }
  if (typeof license !== 'string' || license.length < 1 || license.length > 256) {
    throw new Error('license is invalid');
  }
  const normalizedDependencies = [...new Set(['linux-base', ...dependencies])].sort();
  if (normalizedDependencies.some((dependency) => !PACK_IDS.has(dependency) || dependency === packId)) {
    throw new Error('runtime image dependency is invalid');
  }
  const validated = validateRuntimeImageSource(packId, version, sourceRoot, extraCapabilities, platform);
  const output = resolve(outputPath);
  mkdirSync(dirname(output), { recursive: true });
  const staging = mkdtempSync(join(dirname(output), '.signalasi-runtime-image-'));
  const stagedRoot = join(staging, 'root');
  const stagedImage = join(staging, 'runtime.squashfs');
  try {
    cpSync(validated.root, stagedRoot, {
      recursive: true,
      dereference: false,
      preserveTimestamps: true,
    });
    const descriptor = {
      format_version: 1,
      id: packId,
      version,
      architecture,
      capabilities: validated.capabilities,
    };
    const descriptorPath = join(stagedRoot, 'signalasi-pack.json');
    writeFileSync(descriptorPath, `${JSON.stringify(descriptor)}\n`, { encoding: 'utf8', mode: 0o644 });
    normalizeTimestamps(stagedRoot, sourceDateEpoch);

    if (squashfsBuilder) {
      squashfsBuilder(stagedRoot, stagedImage, sourceDateEpoch);
    } else {
      const result = spawnSync(mksquashfs, [
        stagedRoot,
        stagedImage,
        '-noappend',
        '-all-root',
        '-no-xattrs',
        '-no-exports',
        '-no-progress',
        '-comp',
        'zstd',
        '-all-time',
        String(sourceDateEpoch),
        '-mkfs-time',
        String(sourceDateEpoch),
      ], { encoding: 'utf8' });
      if (result.status !== 0) {
        throw new Error(`mksquashfs failed: ${(result.stderr || result.stdout || '').trim()}`);
      }
    }
    if (!existsSync(stagedImage) || !statSync(stagedImage).isFile()) {
      throw new Error('mksquashfs did not produce an image');
    }
    rmSync(output, { force: true });
    renameSync(stagedImage, output);
    const config = {
      id: packId,
      version,
      architecture,
      image: basename(output),
      image_file: basename(output),
      capabilities: validated.capabilities,
      dependencies: normalizedDependencies,
      installed_size_bytes: statSync(output).size + 1024 * 1024,
      license,
      minimum_host_version_code: 62,
      guest_api_version: 1,
    };
    writeFileSync(`${output}.config.json`, `${JSON.stringify(config, null, 2)}\n`, 'utf8');
    return { output, config, descriptor };
  } finally {
    rmSync(staging, { recursive: true, force: true });
  }
}

function normalizeTimestamps(root, sourceDateEpoch) {
  const timestamp = new Date(sourceDateEpoch * 1000);
  const visit = (path) => {
    const metadata = lstatSync(path);
    if (metadata.isDirectory()) {
      for (const name of readdirSync(path).sort()) visit(join(path, name));
    }
    if (metadata.isSymbolicLink()) {
      lutimesSync(path, timestamp, timestamp);
    } else {
      utimesSync(path, timestamp, timestamp);
    }
  };
  visit(root);
}
