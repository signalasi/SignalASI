import { parseArgs } from 'node:util';
import { verifyAndroidDefaultRuntime } from './android-default-runtime.mjs';

const { values } = parseArgs({
  options: {
    'asset-root': { type: 'string' },
    'jni-root': { type: 'string' },
  },
  strict: true,
});
for (const name of ['asset-root', 'jni-root']) {
  if (!values[name]) throw new Error(`--${name} is required`);
}

await verifyAndroidDefaultRuntime({
  assetRoot: values['asset-root'],
  jniRoot: values['jni-root'],
});
process.stdout.write('Android default runtime bundle verified\n');
