const { spawn } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const { acquireSignalasiLock } = require("./smoke-lock");

const root = path.resolve(__dirname, "..");
const electronCli = path.join(root, ".electron-runtime", "node_modules", "electron", "cli.js");
const screenshotDir = path.join(root, "ui-smoke");
const screenshots = [
  path.join(screenshotDir, "desktop-overview.png"),
  path.join(screenshotDir, "desktop-language-en.png"),
  path.join(screenshotDir, "desktop-language-zh.png"),
  path.join(screenshotDir, "desktop-setup-guide.png"),
  path.join(screenshotDir, "desktop-status-matrix.png"),
  path.join(screenshotDir, "desktop-agents.png")
];

if (!fs.existsSync(electronCli)) {
  throw new Error(`Electron CLI missing: ${electronCli}`);
}

const releaseLock = acquireSignalasiLock("smoke:ui");

fs.rmSync(screenshotDir, { recursive: true, force: true });
fs.mkdirSync(screenshotDir, { recursive: true });

const child = spawn(process.execPath, [electronCli, "."], {
  cwd: root,
  windowsHide: true,
  stdio: "inherit",
  env: {
    ...process.env,
    SIGNALASI_UI_SMOKE: "1",
    SIGNALASI_UI_SMOKE_DIR: screenshotDir
  }
});

child.on("exit", (code) => {
  releaseLock();
  if (code !== 0) {
    process.exit(code || 1);
    return;
  }
  for (const screenshotPath of screenshots) {
    if (!fs.existsSync(screenshotPath) || fs.statSync(screenshotPath).size < 1000) {
      console.error(`[ui-smoke] screenshot missing or too small: ${screenshotPath}`);
      process.exit(1);
      return;
    }
  }
  console.log(`[ui-smoke] OK: ${screenshots.join(", ")}`);
});

child.on("error", (error) => {
  releaseLock();
  console.error(`[ui-smoke] failed to start Electron: ${error.stack || error.message || error}`);
  process.exit(1);
});
