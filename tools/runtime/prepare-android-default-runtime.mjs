import { parseArgs } from 'node:util';
import { prepareAndroidDefaultRuntime } from './android-default-runtime.mjs';

const { values } = parseArgs({
  options: {
    'asset-root': { type: 'string' },
    'jni-root': { type: 'string' },
    certificate: { type: 'string' },
    pack: { type: 'string', multiple: true, default: [] },
  },
  strict: true,
});
for (const name of ['asset-root', 'jni-root', 'certificate']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

const index = await prepareAndroidDefaultRuntime({
  assetRoot: values['asset-root'],
  jniRoot: values['jni-root'],
  certificatePath: values.certificate,
  packArchives: values.pack,
});
process.stdout.write(`${JSON.stringify(index, null, 2)}\n`);
