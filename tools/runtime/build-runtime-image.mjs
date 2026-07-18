import { parseArgs } from 'node:util';
import { buildRuntimeImage } from './runtime-image.mjs';

const { values } = parseArgs({
  options: {
    'pack-id': { type: 'string' },
    version: { type: 'string' },
    source: { type: 'string' },
    output: { type: 'string' },
    license: { type: 'string' },
    architecture: { type: 'string', default: 'arm64-v8a' },
    capability: { type: 'string', multiple: true, default: [] },
    dependency: { type: 'string', multiple: true, default: [] },
    mksquashfs: { type: 'string', default: 'mksquashfs' },
  },
  strict: true,
});

for (const name of ['pack-id', 'version', 'source', 'output', 'license']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

const result = buildRuntimeImage({
  packId: values['pack-id'],
  version: values.version,
  sourceRoot: values.source,
  outputPath: values.output,
  license: values.license,
  architecture: values.architecture,
  extraCapabilities: values.capability,
  dependencies: values.dependency,
  mksquashfs: values.mksquashfs,
});
process.stdout.write(`${result.output}\n${result.output}.config.json\n`);
