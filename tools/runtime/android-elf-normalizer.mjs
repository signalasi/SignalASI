import { createHash } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  readFileSync,
  renameSync,
  statSync,
} from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import {
  ANDROID_ELF_PAGE_SIZE,
  parseDynamicSection,
  validateAarch64ElfHeader,
  validateAndroidProgramHeaders,
} from './android-elf-bundle.mjs';

const APK_LIBRARY_PATTERN = /^lib[A-Za-z0-9_+.-]+\.so$/;
const VERSIONED_LIBRARY_PATTERN = /^(lib[A-Za-z0-9_+.-]+?)\.so((?:\.[0-9]+)+)$/;

export function androidApkLibraryName(name) {
  if (APK_LIBRARY_PATTERN.test(name)) return name;
  const match = String(name).match(VERSIONED_LIBRARY_PATTERN);
  if (!match) throw new Error(`Android ELF library name cannot be packaged: ${name}`);
  return `${match[1]}${match[2].replaceAll('.', '_')}.so`;
}

export function normalizeAndroidElfBundle({
  libraryDirectory,
  manifest,
  readelf = 'llvm-readelf',
  patchelf = 'patchelf',
  commandRunner = runCommand,
}) {
  if (!manifest || !Array.isArray(manifest.files) || manifest.files.length === 0) {
    throw new Error('SignalASI QEMU bundle manifest is invalid');
  }
  const directory = resolve(libraryDirectory);
  const names = new Map(manifest.files.map((file) => [file.name, androidApkLibraryName(file.name)]));
  if (new Set(names.values()).size !== names.size) {
    throw new Error('Android ELF library normalization produced a name collision');
  }

  for (const [sourceName, packagedName] of names) {
    const source = join(directory, sourceName);
    if (!existsSync(source) || !statSync(source).isFile()) {
      throw new Error(`SignalASI QEMU library is missing: ${sourceName}`);
    }
    if (sourceName === packagedName) continue;
    const destination = join(directory, packagedName);
    if (existsSync(destination)) {
      throw new Error(`SignalASI QEMU normalized library already exists: ${packagedName}`);
    }
    renameSync(source, destination);
  }

  const files = [];
  for (const file of manifest.files) {
    const packagedName = names.get(file.name);
    const path = join(directory, packagedName);
    chmodSync(path, 0o755);
    let before = parseDynamicSection(commandRunner(readelf, ['--dynamic', '--wide', path]));
    if (before.searchPaths.length !== 1 || before.searchPaths[0] !== '$ORIGIN') {
      commandRunner(patchelf, [
        '--page-size', String(ANDROID_ELF_PAGE_SIZE),
        '--set-rpath', '$ORIGIN', path,
      ]);
      before = parseDynamicSection(commandRunner(readelf, ['--dynamic', '--wide', path]));
    }
    for (const dependency of before.needed) {
      if (!VERSIONED_LIBRARY_PATTERN.test(dependency)) continue;
      const packagedDependency = names.get(dependency);
      if (!packagedDependency) {
        throw new Error(`Versioned Android ELF dependency is not bundled: ${dependency}`);
      }
      commandRunner(patchelf, [
        '--page-size', String(ANDROID_ELF_PAGE_SIZE),
        '--replace-needed', dependency, packagedDependency, path,
      ]);
    }
    if (file.name !== packagedName) {
      commandRunner(patchelf, [
        '--page-size', String(ANDROID_ELF_PAGE_SIZE),
        '--set-soname', packagedName, path,
      ]);
    }
    validateAarch64ElfHeader(commandRunner(readelf, ['--file-header', path]));
    validateAndroidProgramHeaders(commandRunner(readelf, ['--program-headers', '--wide', path]));
    const dynamic = parseDynamicSection(commandRunner(readelf, ['--dynamic', '--wide', path]));
    if (dynamic.searchPaths.some((value) => value !== '$ORIGIN')) {
      throw new Error(`runtime ELF contains an unsafe search path: ${packagedName}`);
    }
    if (dynamic.needed.some((dependency) => VERSIONED_LIBRARY_PATTERN.test(dependency))) {
      throw new Error(`runtime ELF still references a versioned Android library: ${packagedName}`);
    }
    files.push({
      name: packagedName,
      sha256: sha256File(path),
      size_bytes: statSync(path).size,
      dependencies: dynamic.needed,
    });
  }

  return {
    ...manifest,
    entry_file: names.get(manifest.entry_file) || androidApkLibraryName(manifest.entry_file),
    files: files.sort((left, right) => left.name.localeCompare(right.name)),
  };
}

function runCommand(command, arguments_) {
  const result = spawnSync(command, arguments_, { encoding: 'utf8' });
  if (result.status !== 0) {
    throw new Error(`${basename(command)} failed: ${(result.stderr || result.stdout || '').trim()}`);
  }
  return result.stdout;
}

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}
