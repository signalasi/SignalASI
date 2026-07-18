import { parseArgs } from 'node:util';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, normalize, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import {
  ARCHITECTURE_PATTERN,
  CAPABILITY_PATTERN,
  MAX_ARCHIVE_BYTES,
  MAX_INSTALLED_BYTES,
  PACK_IDS,
  PACK_REQUIRED_CAPABILITIES,
  VERSION_PATTERN,
  manifestSigningPayload,
  requireInteger,
  requireString,
  sha256File,
  signPayload,
  signingIdentity,
  writeJsonAtomic,
} from './runtime-signing.mjs';

const { values } = parseArgs({
  options: {
    config: { type: 'string' },
    output: { type: 'string' },
    certificate: { type: 'string' },
    key: { type: 'string' },
    jar: { type: 'string' },
  },
  strict: true,
});

for (const name of ['config', 'output', 'certificate', 'key']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

const configPath = resolve(values.config);
const outputPath = resolve(values.output);
const config = JSON.parse(readFileSync(configPath, 'utf8'));
const imagePath = resolve(dirname(configPath), requireString(config.image, 'image'));
if (!existsSync(imagePath) || !statSync(imagePath).isFile()) throw new Error('Runtime image does not exist');

const imageFile = requireString(config.image_file ?? basename(imagePath), 'image_file');
const normalizedImageFile = normalize(imageFile).replaceAll('\\', '/');
if (isAbsolute(imageFile) || normalizedImageFile === '..' || normalizedImageFile === '.' ||
    normalizedImageFile.startsWith('../') || normalizedImageFile.includes('/../') ||
    !/^[A-Za-z0-9._/-]+$/.test(normalizedImageFile)) {
  throw new Error('image_file must remain inside the runtime pack');
}
const packId = requireString(config.id, 'id');
if (!PACK_IDS.has(packId)) throw new Error('id is not a supported runtime pack');
const capabilities = Array.isArray(config.capabilities) ? [...new Set(config.capabilities)] : [];
const dependencies = Array.isArray(config.dependencies) ? [...new Set(config.dependencies)] : [];
if (capabilities.some((value) => typeof value !== 'string' || !CAPABILITY_PATTERN.test(value))) {
  throw new Error('capabilities are invalid');
}
const missingCapabilities = (PACK_REQUIRED_CAPABILITIES.get(packId) || [])
  .filter((value) => !capabilities.includes(value));
if (missingCapabilities.length > 0) {
  throw new Error(`capabilities are incomplete: ${missingCapabilities.sort().join(', ')}`);
}
if (dependencies.some((value) => !PACK_IDS.has(value) || value === packId)) {
  throw new Error('dependencies are invalid');
}

const imageBytes = statSync(imagePath).size;
const declaredInstalledBytes = config.installed_size_bytes ?? imageBytes + 1024 * 1024;
const declaredArchiveBytes = config.archive_size_bytes ?? Math.min(MAX_ARCHIVE_BYTES, imageBytes + 64 * 1024 * 1024);
if (declaredInstalledBytes < imageBytes || declaredArchiveBytes < imageBytes) {
  throw new Error('Declared pack sizes must cover the runtime image');
}
requireInteger(declaredInstalledBytes, 'installed_size_bytes', 1, MAX_INSTALLED_BYTES);
requireInteger(declaredArchiveBytes, 'archive_size_bytes', 1, MAX_ARCHIVE_BYTES);

const identity = signingIdentity(resolve(values.certificate), resolve(values.key));
const manifest = {
  format_version: 1,
  id: packId,
  version: requireString(config.version, 'version', VERSION_PATTERN),
  architecture: requireString(config.architecture, 'architecture', ARCHITECTURE_PATTERN),
  image_file: normalizedImageFile,
  image_sha256: await sha256File(imagePath),
  capabilities: capabilities.sort(),
  dependencies: dependencies.sort(),
  installed_size_bytes: declaredInstalledBytes,
  archive_size_bytes: declaredArchiveBytes,
  minimum_host_version_code: requireInteger(config.minimum_host_version_code ?? 1, 'minimum_host_version_code', 1, Number.MAX_SAFE_INTEGER),
  guest_api_version: requireInteger(config.guest_api_version ?? 1, 'guest_api_version', 1, 65535),
  license: requireString(config.license, 'license'),
  signature_key_id: identity.keyId,
  signature: '',
};
if (manifest.license.length > 256) throw new Error('license is too long');
manifest.signature = signPayload(manifestSigningPayload(manifest), identity);

mkdirSync(dirname(outputPath), { recursive: true });
const staging = mkdtempSync(join(tmpdir(), 'signalasi-runtime-pack-'));
try {
  const stagedImage = join(staging, normalizedImageFile);
  mkdirSync(dirname(stagedImage), { recursive: true });
  copyFileSync(imagePath, stagedImage);
  writeJsonAtomic(join(staging, 'manifest.json'), manifest);
  const jarExecutable = values.jar || (process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'jar.exe' : 'jar')
    : 'jar');
  const result = spawnSync(jarExecutable, [
    '--create', '--file', outputPath, '--no-manifest',
    '-C', staging, 'manifest.json',
    '-C', staging, normalizedImageFile,
  ], { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`jar failed: ${(result.stderr || result.stdout || '').trim()}`);
  }
} finally {
  rmSync(staging, { recursive: true, force: true });
}

const archiveBytes = statSync(outputPath).size;
if (archiveBytes > MAX_ARCHIVE_BYTES || archiveBytes > declaredArchiveBytes) {
  rmSync(outputPath, { force: true });
  throw new Error('Runtime archive exceeds its signed size declaration');
}
const metadata = {
  pack_id: manifest.id,
  version: manifest.version,
  architecture: manifest.architecture,
  archive_file_name: basename(outputPath),
  archive_sha256: await sha256File(outputPath),
  archive_size_bytes: archiveBytes,
  installed_size_bytes: manifest.installed_size_bytes,
  dependencies: manifest.dependencies,
  license: manifest.license,
  minimum_host_version_code: manifest.minimum_host_version_code,
  guest_api_version: manifest.guest_api_version,
  release_notes: typeof config.release_notes === 'string' ? config.release_notes : '',
};
if (metadata.release_notes.length > 8192 || /[\r\n]/.test(metadata.release_notes)) {
  throw new Error('release_notes must be a single bounded line');
}
writeJsonAtomic(`${outputPath}.metadata.json`, metadata);
process.stdout.write(`${relative(process.cwd(), outputPath)}\n`);
