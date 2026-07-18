import { createHash, createPrivateKey, sign, verify, X509Certificate } from 'node:crypto';
import { createReadStream, readFileSync, renameSync, writeFileSync } from 'node:fs';

export function lengthPrefixed(values) {
  return values.map((value) => {
    const text = String(value);
    return `${Buffer.byteLength(text, 'utf8')}:${text}`;
  }).join('');
}

export function manifestSigningPayload(manifest) {
  return Buffer.from(lengthPrefixed([
    manifest.format_version,
    manifest.id,
    manifest.version,
    manifest.architecture,
    manifest.image_file,
    manifest.image_sha256.toLowerCase(),
    [...manifest.capabilities].sort().join(','),
    [...manifest.dependencies].sort().join(','),
    manifest.installed_size_bytes,
    manifest.archive_size_bytes,
    manifest.minimum_host_version_code,
    manifest.guest_api_version,
    manifest.license,
    manifest.signature_key_id.toLowerCase(),
  ]), 'utf8');
}

export function catalogEntryCanonicalValue(entry) {
  return lengthPrefixed([
    entry.pack_id,
    entry.version,
    entry.architecture,
    entry.download_url,
    entry.archive_sha256.toLowerCase(),
    entry.archive_size_bytes,
    entry.installed_size_bytes,
    [...entry.dependencies].sort().join(','),
    entry.license,
    entry.minimum_host_version_code,
    entry.guest_api_version,
    entry.release_notes ?? '',
  ]);
}

export function catalogSigningPayload(catalog) {
  const entries = [...catalog.entries].sort((left, right) =>
    left.pack_id.localeCompare(right.pack_id) ||
    left.architecture.localeCompare(right.architecture) ||
    left.version.localeCompare(right.version));
  const text = [
    catalog.format_version,
    catalog.catalog_version,
    catalog.generated_at_millis,
    catalog.expires_at_millis,
  ].join('\n') + '\n' +
    entries.map((entry) => `${catalogEntryCanonicalValue(entry)}\n`).join('') +
    catalog.signature_key_id.toLowerCase();
  return Buffer.from(text, 'utf8');
}

export function signingIdentity(certificatePath, privateKeyPath) {
  const certificate = new X509Certificate(readFileSync(certificatePath));
  const privateKey = createPrivateKey(readFileSync(privateKeyPath));
  if (!['rsa', 'ec'].includes(privateKey.asymmetricKeyType)) {
    throw new Error(`Unsupported runtime signing key type: ${privateKey.asymmetricKeyType}`);
  }
  const keyId = createHash('sha256').update(certificate.raw).digest('hex');
  return { certificate, privateKey, keyId };
}

export function signPayload(payload, identity) {
  const algorithm = identity.privateKey.asymmetricKeyType === 'ed25519' ? null : 'sha256';
  const signature = sign(algorithm, payload, identity.privateKey);
  if (!verify(algorithm, payload, identity.certificate.publicKey, signature)) {
    throw new Error('The private key does not match the signing certificate');
  }
  return signature.toString('base64');
}

export async function sha256File(filePath) {
  const digest = createHash('sha256');
  for await (const chunk of createReadStream(filePath)) digest.update(chunk);
  return digest.digest('hex');
}

export function writeJsonAtomic(filePath, value) {
  const temporary = `${filePath}.tmp-${process.pid}`;
  writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  renameSync(temporary, filePath);
}

export function requireString(value, name, pattern = null) {
  if (typeof value !== 'string' || value.length === 0 || (pattern && !pattern.test(value))) {
    throw new Error(`${name} is invalid`);
  }
  return value;
}

export function requireInteger(value, name, minimum, maximum) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} is invalid`);
  }
  return value;
}

export const PACK_IDS = new Set([
  'linux-base', 'python-uv', 'node-js', 'go', 'rust', 'cpp', 'java', 'ffmpeg',
]);
export const PACK_REQUIRED_CAPABILITIES = new Map([
  ['linux-base', ['shell.execute']],
  ['python-uv', ['python.execute', 'uv.sync']],
  ['node-js', ['javascript.execute', 'typescript.execute']],
  ['go', ['go.execute']],
  ['rust', ['rust.execute']],
  ['cpp', ['c.execute', 'cpp.execute']],
  ['java', ['java.execute']],
  ['ffmpeg', ['ffmpeg.execute', 'ffprobe.inspect']],
]);
export const VERSION_PATTERN = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?$/;
export const ARCHITECTURE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
export const CAPABILITY_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
export const SHA256_PATTERN = /^[a-f0-9]{64}$/;
export const MAX_ARCHIVE_BYTES = 6 * 1024 * 1024 * 1024;
export const MAX_INSTALLED_BYTES = 12 * 1024 * 1024 * 1024;
