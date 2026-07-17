import { parseArgs } from 'node:util';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import {
  ARCHITECTURE_PATTERN,
  PACK_IDS,
  SHA256_PATTERN,
  VERSION_PATTERN,
  catalogSigningPayload,
  requireInteger,
  requireString,
  sha256File,
  signPayload,
  signingIdentity,
  writeJsonAtomic,
} from './runtime-signing.mjs';

const { values } = parseArgs({
  options: {
    entries: { type: 'string' },
    output: { type: 'string' },
    'base-url': { type: 'string' },
    version: { type: 'string' },
    'expires-days': { type: 'string', default: '30' },
    certificate: { type: 'string' },
    key: { type: 'string' },
    'trust-anchors': { type: 'string' },
  },
  strict: true,
});
for (const name of ['entries', 'output', 'base-url', 'version', 'certificate', 'key', 'trust-anchors']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

const entriesDirectory = resolve(values.entries);
const baseUrl = new URL(values['base-url']);
if (baseUrl.protocol !== 'https:') throw new Error('--base-url must use HTTPS');
if (!baseUrl.pathname.endsWith('/')) baseUrl.pathname += '/';
const metadataFiles = readdirSync(entriesDirectory)
  .filter((name) => name.endsWith('.sarpack.metadata.json'))
  .sort();
if (metadataFiles.length === 0) throw new Error('No runtime pack metadata files were found');

const entries = [];
for (const name of metadataFiles) {
  const value = JSON.parse(readFileSync(join(entriesDirectory, name), 'utf8'));
  const packId = requireString(value.pack_id, 'pack_id');
  if (!PACK_IDS.has(packId)) throw new Error(`Unsupported runtime pack: ${packId}`);
  const archiveFileName = basename(requireString(value.archive_file_name, 'archive_file_name'));
  const archivePath = join(entriesDirectory, archiveFileName);
  if (!existsSync(archivePath) || !statSync(archivePath).isFile()) {
    throw new Error(`Runtime archive is missing: ${archiveFileName}`);
  }
  const dependencies = Array.isArray(value.dependencies) ? value.dependencies : [];
  if (dependencies.some((dependency) => !PACK_IDS.has(dependency) || dependency === packId)) {
    throw new Error(`Invalid dependencies for ${packId}`);
  }
  const archiveBytes = statSync(archivePath).size;
  const archiveDigest = await sha256File(archivePath);
  if (archiveBytes !== value.archive_size_bytes || archiveDigest !== value.archive_sha256) {
    throw new Error(`Runtime archive changed after metadata generation: ${archiveFileName}`);
  }
  const releaseNotes = typeof value.release_notes === 'string' ? value.release_notes : '';
  if (releaseNotes.length > 8192 || /[\r\n]/.test(releaseNotes)) {
    throw new Error(`Invalid release notes for ${packId}`);
  }
  const entry = {
    pack_id: packId,
    version: requireString(value.version, 'version', VERSION_PATTERN),
    architecture: requireString(value.architecture, 'architecture', ARCHITECTURE_PATTERN),
    download_url: new URL(encodeURIComponent(archiveFileName), baseUrl).toString(),
    archive_sha256: requireString(value.archive_sha256, 'archive_sha256', SHA256_PATTERN),
    archive_size_bytes: requireInteger(value.archive_size_bytes, 'archive_size_bytes', 1, 6 * 1024 * 1024 * 1024),
    installed_size_bytes: requireInteger(value.installed_size_bytes, 'installed_size_bytes', 1, 12 * 1024 * 1024 * 1024),
    dependencies: [...new Set(dependencies)].sort(),
    license: requireString(value.license, 'license'),
    minimum_host_version_code: requireInteger(value.minimum_host_version_code, 'minimum_host_version_code', 1, Number.MAX_SAFE_INTEGER),
    guest_api_version: requireInteger(value.guest_api_version, 'guest_api_version', 1, 65535),
    release_notes: releaseNotes,
  };
  if (entry.license.length > 256) throw new Error(`License is too long for ${packId}`);
  entries.push(entry);
}

const identities = new Set();
for (const entry of entries) {
  const identity = `${entry.pack_id}|${entry.architecture}`;
  if (identities.has(identity)) throw new Error(`Duplicate runtime pack entry: ${identity}`);
  identities.add(identity);
  for (const dependency of entry.dependencies) {
    if (!entries.some((candidate) => candidate.pack_id === dependency && candidate.architecture === entry.architecture)) {
      throw new Error(`Missing ${dependency} dependency for ${entry.pack_id} (${entry.architecture})`);
    }
  }
}
for (const architecture of new Set(entries.map((entry) => entry.architecture))) {
  const byId = new Map(entries
    .filter((entry) => entry.architecture === architecture)
    .map((entry) => [entry.pack_id, entry]));
  const visiting = new Set();
  const visited = new Set();
  const visit = (packId) => {
    if (visited.has(packId)) return;
    if (visiting.has(packId)) throw new Error(`Runtime dependency cycle for ${architecture}`);
    visiting.add(packId);
    for (const dependency of byId.get(packId).dependencies) visit(dependency);
    visiting.delete(packId);
    visited.add(packId);
  };
  for (const packId of byId.keys()) visit(packId);
}

const expiresDays = Number.parseInt(values['expires-days'], 10);
requireInteger(expiresDays, 'expires-days', 1, 365);
const generatedAt = Date.now();
const identity = signingIdentity(resolve(values.certificate), resolve(values.key));
const trustAnchors = JSON.parse(readFileSync(resolve(values['trust-anchors']), 'utf8'));
if (trustAnchors.format_version !== 1 || !Array.isArray(trustAnchors.certificates) ||
    !trustAnchors.certificates.includes(identity.certificate.raw.toString('base64'))) {
  throw new Error('The catalog signing certificate is not embedded in the supplied app trust anchors');
}
const catalog = {
  format_version: 1,
  catalog_version: requireString(values.version, 'version', VERSION_PATTERN),
  generated_at_millis: generatedAt,
  expires_at_millis: generatedAt + expiresDays * 24 * 60 * 60 * 1000,
  entries,
  signature_key_id: identity.keyId,
  signature: '',
};
catalog.signature = signPayload(catalogSigningPayload(catalog), identity);
writeJsonAtomic(resolve(values.output), catalog);
process.stdout.write(`${resolve(values.output)}\n`);
