import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { basename, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

export const ANDROID_SYSTEM_LIBRARIES = new Set([
  'libandroid.so',
  'libc.so',
  'libdl.so',
  'liblog.so',
  'libm.so',
]);

const LIBRARY_NAME_PATTERN = /^[A-Za-z0-9_+.-]+\.so(?:\.[0-9]+)*$/;

export function parseDynamicSection(value) {
  const needed = [];
  const searchPaths = [];
  for (const line of String(value).split(/\r?\n/)) {
    const neededMatch = line.match(/\(NEEDED\).*Shared library: \[([^\]]+)\]/);
    if (neededMatch) needed.push(neededMatch[1]);
    const pathMatch = line.match(/\((?:RPATH|RUNPATH)\).*Library (?:rpath|runpath): \[([^\]]*)\]/);
    if (pathMatch) searchPaths.push(...pathMatch[1].split(':').filter(Boolean));
  }
  return {
    needed: [...new Set(needed)].sort(),
    searchPaths: [...new Set(searchPaths)].sort(),
  };
}

export function validateAarch64ElfHeader(value) {
  const header = String(value);
  if (!/Class:\s+ELF64/.test(header)) throw new Error('runtime ELF is not 64-bit');
  if (!/Machine:\s+AArch64/.test(header)) throw new Error('runtime ELF is not AArch64');
  if (!/Type:\s+(?:DYN|EXEC)/.test(header)) throw new Error('runtime ELF is not executable');
}

export function collectAndroidElfBundle({
  entryFile,
  outputDirectory,
  libraryDirectories,
  readelf = 'llvm-readelf',
  patchelf = 'patchelf',
  commandRunner = runCommand,
  entryName = 'libsignalasi_qemu.so',
  metadata = {},
}) {
  const entry = realpathSync(resolve(entryFile));
  if (!statSync(entry).isFile()) throw new Error('QEMU entry ELF is unavailable');
  if (!LIBRARY_NAME_PATTERN.test(entryName)) throw new Error('QEMU bundle entry name is invalid');
  const roots = libraryDirectories.map((directory) => realpathSync(resolve(directory)));
  const output = resolve(outputDirectory);
  mkdirSync(output, { recursive: true });

  const queue = [{ name: entryName, source: entry }];
  const bundled = new Map();
  while (queue.length > 0) {
    const current = queue.shift();
    if (bundled.has(current.name)) continue;
    if (!LIBRARY_NAME_PATTERN.test(current.name)) {
      throw new Error(`unsafe Android ELF dependency name: ${current.name}`);
    }
    const destination = join(output, current.name);
    copyFileSync(current.source, destination);
    chmodSync(destination, 0o755);
    commandRunner(patchelf, ['--set-rpath', '$ORIGIN', destination]);
    validateAarch64ElfHeader(commandRunner(readelf, ['--file-header', destination]));
    const dynamic = parseDynamicSection(commandRunner(readelf, ['--dynamic', '--wide', destination]));
    if (dynamic.searchPaths.some((path) => path !== '$ORIGIN')) {
      throw new Error(`runtime ELF contains an unsafe search path: ${current.name}`);
    }
    bundled.set(current.name, {
      name: current.name,
      sha256: sha256File(destination),
      size_bytes: statSync(destination).size,
      dependencies: dynamic.needed,
    });
    for (const dependency of dynamic.needed) {
      if (ANDROID_SYSTEM_LIBRARIES.has(dependency) || bundled.has(dependency)) continue;
      if (!LIBRARY_NAME_PATTERN.test(dependency)) {
        throw new Error(`unsafe Android ELF dependency name: ${dependency}`);
      }
      const source = findLibrary(dependency, roots);
      if (!source) throw new Error(`Android ELF dependency is unavailable: ${dependency}`);
      queue.push({ name: dependency, source });
    }
  }

  const manifest = {
    format_version: 1,
    architecture: 'arm64-v8a',
    entry_file: entryName,
    files: [...bundled.values()].sort((left, right) => left.name.localeCompare(right.name)),
    ...metadata,
  };
  return manifest;
}

export function writeBundleManifest(path, manifest) {
  writeFileSync(resolve(path), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}

function findLibrary(name, roots) {
  for (const root of roots) {
    const candidate = join(root, name);
    if (!existsSync(candidate)) continue;
    const resolved = realpathSync(candidate);
    const pathFromRoot = relative(root, resolved);
    if (pathFromRoot === '..' || pathFromRoot.startsWith(`..${sep}`) || isAbsolute(pathFromRoot)) {
      throw new Error(`Android ELF dependency escapes its library root: ${name}`);
    }
    if (!statSync(resolved).isFile()) continue;
    return resolved;
  }
  return null;
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
