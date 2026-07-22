const { execFileSync } = require("node:child_process");

function errorText(error) {
  return [
    error?.message,
    error?.stdout?.toString?.(),
    error?.stderr?.toString?.()
  ].filter(Boolean).join("\n");
}

function isTransientAdbError(error) {
  const text = errorText(error).toLowerCase();
  return text.includes("daemon not running") ||
    text.includes("cannot connect to daemon") ||
    text.includes("failed to start daemon") ||
    text.includes("could not read ok from adb server") ||
    text.includes("adb server is out of date") ||
    text.includes("device offline");
}

function sleepSync(milliseconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, milliseconds);
}

function isUiDumpCommand(args) {
  return args[0] === "shell" && args[1] === "uiautomator" && args[2] === "dump";
}

function isUiXmlPull(args) {
  return args[0] === "pull" && /^\/sdcard\/.*\.xml$/i.test(args[1] || "");
}

function isMissingRemoteUiDump(error) {
  const text = errorText(error).toLowerCase();
  return text.includes("failed to stat remote object") || text.includes("no such file or directory");
}

function regenerateUiDump(remotePath, root, log) {
  try {
    execFileSync("adb", ["shell", "input", "keyevent", "KEYCODE_WAKEUP"], {
      cwd: root,
      windowsHide: true,
      stdio: "ignore"
    });
    execFileSync("adb", ["shell", "wm", "dismiss-keyguard"], {
      cwd: root,
      windowsHide: true,
      stdio: "ignore"
    });
    execFileSync("adb", ["shell", "uiautomator", "dump", remotePath], {
      cwd: root,
      windowsHide: true,
      stdio: "ignore"
    });
  } catch (error) {
    log?.(`UI hierarchy regeneration is still waiting: ${errorText(error).split("\n")[0]}`);
  }
}

function restartAdb(log) {
  try {
    execFileSync("adb", ["kill-server"], { encoding: "utf8", windowsHide: true, stdio: ["ignore", "ignore", "ignore"] });
  } catch {
    // Best effort cleanup before start-server.
  }
  try {
    execFileSync("adb", ["start-server"], { encoding: "utf8", windowsHide: true, stdio: ["ignore", "ignore", "ignore"] });
  } catch (error) {
    log?.(`ADB restart failed: ${errorText(error).split("\n")[0]}`);
  }
}

function createAdb(root, log) {
  return function adb(args, options = {}) {
    let lastError = null;
    const uiDump = isUiDumpCommand(args);
    const uiPull = isUiXmlPull(args);
    const maxAttempts = uiDump || uiPull ? 8 : 3;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      try {
        return execFileSync("adb", args, {
          cwd: options.cwd || root,
          encoding: options.encoding || "utf8",
          windowsHide: true,
          input: options.input,
          stdio: options.stdio || ["pipe", "pipe", "pipe"]
        });
      } catch (error) {
        lastError = error;
        if (uiPull && isMissingRemoteUiDump(error) && attempt < maxAttempts - 1) {
          log?.(`UI hierarchy file was not ready; regenerating it (${attempt + 1}/${maxAttempts - 1})`);
          regenerateUiDump(args[1], options.cwd || root, log);
          sleepSync(500);
          continue;
        }
        if (uiDump && attempt < maxAttempts - 1) {
          log?.(`UI hierarchy capture was not ready; retrying (${attempt + 1}/${maxAttempts - 1})`);
          sleepSync(500);
          continue;
        }
        if (!isTransientAdbError(error) || attempt === maxAttempts - 1) break;
        log?.(`ADB transient failure, restarting server and retrying (${attempt + 1}/2)`);
        restartAdb(log);
      }
    }
    throw lastError;
  };
}

module.exports = { createAdb };
