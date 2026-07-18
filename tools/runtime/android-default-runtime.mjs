import { X509Certificate, createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { sha256File } from './runtime-signing.mjs';

export const DEFAULT_RUNTIME_PACK_IDS = Object.freeze(['linux-base', 'python-uv']);
export const DEFAULT_RUNTIME_INDEX = 'runtime/bootstrap/index.json';
export const DEFAULT_RUNTIME_TRUST_ANCHORS = 'runtime/bootstrap/trust-anchors.json';

const GENERATED_MARKER = '.signalasi-generated';
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const VERSION_PATTERN = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$/;

export async function prepareAndroidDefaultRuntime({
  assetRoot,
  jniRoot,
  certificatePath,
  packArchives,
}) {
  const assets = resolve(assetRoot);
  const jni = resolve(jniRoot);
  const bootstrap = join(assets, 'runtime', 'bootstrap');
  verifyQemuBundle(jni, assets);
  const entries = await loadPackEntries(packArchives);
  validateDefaultEntries(entries);
  resetGeneratedDirectory(bootstrap);

  for (const entry of entries) {
    const fileName = `${entry.pack_id}-${entry.version}-arm64-v8a.sarpack`;
    copyFileSync(entry.source_archive, join(bootstrap, fileName));
    entry.asset_path = `runtime/bootstrap/${fileName}`;
    delete entry.source_archive;
  }

  const certificate = new X509Certificate(readFileSync(resolve(certificatePath)));
  const keyId = createHash('sha256').update(certificate.raw).digest('hex');
  const index = {
    format_version: 1,
    architecture: 'arm64-v8a',
    packs: entries,
  };
  writeJson(join(bootstrap, 'index.json'), index);
  writeJson(join(bootstrap, 'trust-anchors.json'), {
    format_version: 1,
    certificates: [certificate.raw.toString('base64')],
    key_ids: [keyId],
  });
  await verifyAndroidDefaultRuntime({ assetRoot: assets, jniRoot: jni });
  return index;
}

export async function verifyAndroidDefaultRuntime({ assetRoot, jniRoot }) {
  const assets = resolve(assetRoot);
  const jni = resolve(jniRoot);
  verifyQemuBundle(jni, assets);
  const indexPath = join(assets, DEFAULT_RUNTIME_INDEX);
  if (!existsSync(indexPath)) throw new Error('Default runtime index is missing');
  const index = JSON.parse(readFileSync(indexPath, 'utf8'));
  if (index.format_version !== 1 || index.architecture !== 'arm64-v8a' || !Array.isArray(index.packs)) {
    throw new Error('Default runtime index is invalid');
  }
  validateDefaultEntries(index.packs);
  for (const entry of index.packs) {
    const archive = join(assets, entry.asset_path);
    if (!existsSync(archive) || !statSync(archive).isFile()) {
      throw new Error(`Bundled runtime archive is missing: ${entry.pack_id}`);
    }
    if (statSync(archive).size !== entry.archive_size_bytes) {
      throw new Error(`Bundled runtime archive size changed: ${entry.pack_id}`);
    }
    if (await sha256File(archive) !== entry.archive_sha256) {
      throw new Error(`Bundled runtime archive digest changed: ${entry.pack_id}`);
    }
  }
  const anchorsPath = join(assets, DEFAULT_RUNTIME_TRUST_ANCHORS);
  const anchors = JSON.parse(readFileSync(anchorsPath, 'utf8'));
  if (anchors.format_version !== 1 || !Array.isArray(anchors.certificates) || anchors.certificates.length < 1) {
    throw new Error('Bundled runtime trust anchor is missing');
  }
  return index;
}

export function validateDefaultEntries(entries) {
  if (!Array.isArray(entries) || entries.length !== DEFAULT_RUNTIME_PACK_IDS.length) {
    throw new Error('Default runtime must contain linux-base and python-uv');
  }
  const byId = new Map(entries.map((entry) => [entry.pack_id, entry]));
  if (byId.size !== entries.length || DEFAULT_RUNTIME_PACK_IDS.some((id) => !byId.has(id))) {
    throw new Error('Default runtime pack set is incomplete');
  }
  for (const entry of entries) {
    if (!VERSION_PATTERN.test(entry.version) || entry.architecture !== 'arm64-v8a') {
      throw new Error(`Default runtime metadata is incompatible: ${entry.pack_id}`);
    }
    if (!SHA256_PATTERN.test(entry.archive_sha256) || !Number.isSafeInteger(entry.archive_size_bytes) ||
        entry.archive_size_bytes <= 0 || !Number.isSafeInteger(entry.installed_size_bytes) ||
        entry.installed_size_bytes <= 0 || !Array.isArray(entry.dependencies)) {
      throw new Error(`Default runtime metadata is incomplete: ${entry.pack_id}`);
    }
    if (entry.asset_path && !/^runtime\/bootstrap\/[A-Za-z0-9._+-]+\.sarpack$/.test(entry.asset_path)) {
      throw new Error(`Default runtime asset path is invalid: ${entry.pack_id}`);
    }
  }
  if (byId.get('linux-base').dependencies.length !== 0) {
    throw new Error('linux-base must not depend on another runtime pack');
  }
  if (!byId.get('python-uv').dependencies.includes('linux-base')) {
    throw new Error('python-uv must depend on linux-base');
  }
  return entries;
}

async function loadPackEntries(packArchives) {
  if (!Array.isArray(packArchives)) throw new Error('Runtime pack archives are required');
  const entries = [];
  for (const archiveValue of packArchives) {
    const archive = resolve(archiveValue);
    const metadataPath = `${archive}.metadata.json`;
    if (!existsSync(archive) || !existsSync(metadataPath)) {
      throw new Error(`Runtime pack or metadata is missing: ${basename(archive)}`);
    }
    const metadata = JSON.parse(readFileSync(metadataPath, 'utf8'));
    const size = statSync(archive).size;
    const digest = await sha256File(archive);
    if (metadata.archive_size_bytes !== size || metadata.archive_sha256 !== digest) {
      throw new Error(`Runtime pack metadata does not match the archive: ${metadata.pack_id || basename(archive)}`);
    }
    entries.push({
      pack_id: metadata.pack_id,
      version: metadata.version,
      architecture: metadata.architecture || 'arm64-v8a',
      archive_sha256: digest,
      archive_size_bytes: size,
      installed_size_bytes: metadata.installed_size_bytes,
      dependencies: metadata.dependencies || [],
      source_archive: archive,
    });
  }
  if (entries.length !== DEFAULT_RUNTIME_PACK_IDS.length ||
      new Set(entries.map((entry) => entry.pack_id)).size !== entries.length ||
      entries.some((entry) => !DEFAULT_RUNTIME_PACK_IDS.includes(entry.pack_id))) {
    throw new Error('Exactly linux-base and python-uv archives are required');
  }
  return DEFAULT_RUNTIME_PACK_IDS.map((id) => entries.find((entry) => entry.pack_id === id) || { pack_id: id });
}

function verifyQemuBundle(jniRoot, assetRoot) {
  const manifestPath = join(jniRoot, 'signalasi-qemu-bundle.json');
  const qemuAssetManifest = join(assetRoot, 'runtime', 'qemu', 'bundle.json');
  if (!existsSync(manifestPath) || !existsSync(qemuAssetManifest)) {
    throw new Error('SignalASI QEMU bundle metadata is missing');
  }
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const assetManifest = JSON.parse(readFileSync(qemuAssetManifest, 'utf8'));
  if (JSON.stringify(assetManifest) !== JSON.stringify(manifest)) {
    throw new Error('SignalASI QEMU APK metadata does not match the native bundle');
  }
  if (manifest.format_version !== 1 || manifest.architecture !== 'arm64-v8a' ||
      manifest.entry_file !== 'libsignalasi_qemu.so' || !Array.isArray(manifest.files)) {
    throw new Error('SignalASI QEMU bundle metadata is invalid');
  }
  const libraryDirectory = join(jniRoot, 'arm64-v8a');
  for (const file of manifest.files) {
    const path = join(libraryDirectory, file.name);
    if (!existsSync(path) || statSync(path).size !== file.size_bytes) {
      throw new Error(`SignalASI QEMU library is missing or changed: ${file.name}`);
    }
    const digest = createHash('sha256').update(readFileSync(path)).digest('hex');
    if (digest !== file.sha256) throw new Error(`SignalASI QEMU library digest changed: ${file.name}`);
  }
  if (!existsSync(join(assetRoot, 'runtime', 'qemu', 'NOTICE.md'))) {
    throw new Error('SignalASI QEMU redistribution notice is missing');
  }
}

function resetGeneratedDirectory(directory) {
  if (existsSync(directory)) {
    if (!existsSync(join(directory, GENERATED_MARKER))) {
      throw new Error(`Refusing to replace an unmarked generated directory: ${directory}`);
    }
    rmSync(directory, { recursive: true, force: true });
  }
  mkdirSync(directory, { recursive: true });
  writeFileSync(join(directory, GENERATED_MARKER), '', 'utf8');
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}
