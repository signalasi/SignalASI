import { X509Certificate, createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: { certificate: { type: 'string' } },
  strict: true,
});
if (!values.certificate) throw new Error('--certificate is required');

const certificate = new X509Certificate(readFileSync(resolve(values.certificate)));
const keyId = createHash('sha256').update(certificate.raw).digest('hex');
process.stdout.write(`${JSON.stringify({
  key_id: keyId,
  certificate: certificate.raw.toString('base64'),
}, null, 2)}\n`);
