const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "..", "..");
const hanPattern = /[\u3400-\u9fff]/;

const ignoredDirs = new Set([
  ".git",
  ".gradle",
  ".gradle-dist",
  ".kotlin",
  "build",
  "dist",
  "node_modules",
  "out",
  "release",
  "__pycache__",
  "venv",
  ".venv"
]);

const ignoredExtensions = new Set([
  ".png",
  ".jpg",
  ".jpeg",
  ".gif",
  ".webp",
  ".ico",
  ".apk",
  ".aab",
  ".aar",
  ".jar",
  ".onnx",
  ".tflite",
  ".db",
  ".sqlite"
]);

function normalize(value) {
  return value.split(path.sep).join("/");
}

function isAllowedChineseFile(file) {
  const rel = normalize(path.relative(root, file));
  return (
    rel === "apps/android/app/src/main/res/values-zh-rCN/strings.xml" ||
    rel === "apps/desktop/src/renderer/locales/zh-CN.json" ||
    /apps\/ios\/.*\/zh-Hans\.lproj\/Localizable\.strings$/.test(rel)
  );
}

function walk(dir, findings) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!ignoredDirs.has(entry.name)) walk(full, findings);
      continue;
    }
    if (ignoredExtensions.has(path.extname(entry.name).toLowerCase())) continue;
    if (isAllowedChineseFile(full)) continue;
    const text = fs.readFileSync(full, "utf8");
    if (!hanPattern.test(text)) continue;
    const lines = text.split(/\r?\n/);
    lines.forEach((line, index) => {
      if (hanPattern.test(line)) {
        findings.push(`${normalize(path.relative(root, full))}:${index + 1}`);
      }
    });
  }
}

const findings = [];
walk(root, findings);

if (findings.length) {
  console.error("Chinese text is only allowed in i18n resource files:");
  for (const finding of findings) console.error(`  ${finding}`);
  process.exit(1);
}

console.log("Chinese text guard OK");
