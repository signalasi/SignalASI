const { app, BrowserWindow, clipboard, dialog, ipcMain, Menu, nativeImage, shell, powerMonitor } = require("electron");
const { spawn, spawnSync, execFile } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const os = require("node:os");
const { Readable } = require("node:stream");
const { pipeline } = require("node:stream/promises");
const { preparePeerVoicePlayback } = require("./peer_voice_playback");
const { buildTextContextMenuTemplate } = require("./text_context_menu");

const requestedBackendPort = Number.parseInt(process.env.GALAXYSSI_BACKEND_PORT || "8765", 10);
const BACKEND_PORT = requestedBackendPort >= 1024 && requestedBackendPort <= 65535
  ? requestedBackendPort
  : 8765;
const BACKEND_ORIGIN = `http://127.0.0.1:${BACKEND_PORT}`;
const DESKTOP_TASK_STREAM_URL = `ws://127.0.0.1:${BACKEND_PORT}/ws/desktop/tasks`;
const PAIRING_URL = `${BACKEND_ORIGIN}/galaxyssi/verify`;
const APP_ROOT = path.resolve(__dirname, "..");
const DEV_BACKEND_DIR = path.join(APP_ROOT, "core", "galaxyssi-link", "backend");
const PACKAGED_BACKEND_DIR = path.resolve(APP_ROOT, "..", "galaxyssi-link", "backend");
const BACKEND_DIR = fs.existsSync(DEV_BACKEND_DIR) ? DEV_BACKEND_DIR : PACKAGED_BACKEND_DIR;
const RUNTIME_ROOT = fs.existsSync(DEV_BACKEND_DIR) ? APP_ROOT : path.resolve(APP_ROOT, "..");
const UI_SMOKE = process.env.GALAXYSSI_UI_SMOKE === "1";
if (UI_SMOKE) {
  const smokeUserData = path.join(process.env.GALAXYSSI_STATE_DIR
    || process.env.GALAXYSSI_UI_SMOKE_DIR || path.join(RUNTIME_ROOT, "ui-smoke"), "user-data");
  app.setPath("userData", smokeUserData);
}

let mainWindow;
let backendProcess;
let backendRestartTimer;
let appIsQuitting = false;
let cachedDesktopTaskStreamToken = "";
const peerAttachmentPreviews = new Map();
const PEER_ATTACHMENT_PREVIEW_TTL_MS = 30_000;
const MAX_CLIPBOARD_ATTACHMENT_BYTES = 64 * 1024 * 1024;
const MAX_CLIPBOARD_BATCH_BYTES = 128 * 1024 * 1024;
const COMPOSER_PREVIEW_SIZE = Object.freeze({ width: 224, height: 168 });
const COMPOSER_MIME_OVERRIDES = Object.freeze({
  ".heic": "image/heic",
  ".heif": "image/heif",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".webp": "image/webp",
  ".gif": "image/gif",
  ".pdf": "application/pdf",
  ".zip": "application/zip"
});

function removePeerAttachmentPreview(target) {
  const timer = peerAttachmentPreviews.get(target);
  if (timer) clearTimeout(timer);
  peerAttachmentPreviews.delete(target);
  try { fs.rmSync(target, { force: true }); } catch {}
}

function schedulePeerAttachmentPreviewCleanup(target, delay = PEER_ATTACHMENT_PREVIEW_TTL_MS) {
  const timer = setTimeout(() => removePeerAttachmentPreview(target), delay);
  timer.unref?.();
  peerAttachmentPreviews.set(target, timer);
}

function clearPeerAttachmentPreviews() {
  for (const target of [...peerAttachmentPreviews.keys()]) removePeerAttachmentPreview(target);
}

function clearPeerTemporaryDirectory(name) {
  const directory = path.join(app.getPath("temp"), "GalaxySSI", name);
  try {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) fs.rmSync(target, { recursive: true, force: true });
      else if (entry.isFile() || entry.isSymbolicLink()) fs.rmSync(target, { force: true });
    }
  } catch {}
}

function clearPeerRuntimeFiles() {
  clearPeerAttachmentPreviews();
  clearPeerTemporaryDirectory("peer-attachments");
  clearPeerTemporaryDirectory("peer-voice");
  clearPeerTemporaryDirectory("composer-attachments");
}

function clearRendererSensitiveState() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("sensitive-state:clear");
  }
}

function signalSidecarCandidates() {
  const scriptName = process.platform === "win32" ? "galaxyssi-link-sidecar.bat" : "galaxyssi-link-sidecar";
  const relative = path.join("signal_sidecar", "build", "install", "galaxyssi-link-sidecar", "bin", scriptName);
  const workspace = process.env.GALAXYSSI_WORKSPACE_ROOT
    || path.join(os.homedir(), "GalaxySSI_Workspace", "GalaxySSI");
  return [
    path.join(BACKEND_DIR, relative),
    process.env.GALAXYSSI_LINK_SIDECAR_SCRIPT || "",
    path.join(workspace, "apps", "desktop", "core", "galaxyssi-link", "backend", relative),
    path.join(workspace, "apps", "desktop", "dist", "GalaxySSI Desktop-win-x64", "resources", "galaxyssi-link", "backend", relative),
    process.platform === "win32"
      ? path.join(process.env.LOCALAPPDATA || "", "Programs", "GalaxySSI Desktop", "resources", "galaxyssi-link", "backend", relative)
      : ""
  ].filter(Boolean);
}

function resolveSignalSidecarRuntime() {
  return signalSidecarCandidates().find((candidate) => fs.existsSync(candidate)) || "";
}

const hasSingleInstanceLock = app.requestSingleInstanceLock();
if (!hasSingleInstanceLock) {
  app.quit();
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 864,
    height: 576,
    title: "GalaxySSI Desktop",
    icon: path.join(APP_ROOT, "assets", "galaxyssi-mark.png"),
    backgroundColor: "#f8f9fa",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      backgroundThrottling: !UI_SMOKE
    }
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  mainWindow.webContents.on("context-menu", (event, params) => {
    const template = buildTextContextMenuTemplate(params);
    if (!template.length || mainWindow.isDestroyed()) return;
    event.preventDefault();
    Menu.buildFromTemplate(template).popup({ window: mainWindow });
  });
  mainWindow.on("blur", () => {
    const timer = setTimeout(clearPeerAttachmentPreviews, 1_000);
    timer.unref?.();
  });
  mainWindow.on("focus", () => {
    if (!mainWindow.isDestroyed()) mainWindow.webContents.send("sensitive-state:resume");
  });
  if (UI_SMOKE) {
    mainWindow.webContents.on("console-message", (_event, level, message, line, sourceId) => {
      console.log(`[renderer:${level}] ${message} (${sourceId}:${line})`);
    });
    mainWindow.webContents.once("did-finish-load", runUiSmoke);
  }
}

async function runUiSmoke() {
  const outDir = process.env.GALAXYSSI_UI_SMOKE_DIR || path.join(RUNTIME_ROOT, "ui-smoke");
  const overviewPath = path.join(outDir, "desktop-overview.png");
  const peerImagePath = path.join(outDir, "desktop-peer-image.png");
  const peerImageViewerPath = path.join(outDir, "desktop-peer-image-viewer.png");
  const composerAttachmentsPath = path.join(outDir, "desktop-composer-attachments.png");
  const evolutionTimelinePath = path.join(outDir, "desktop-evolution-timeline.png");
  const languageEnPath = path.join(outDir, "desktop-language-en.png");
  const languageZhPath = path.join(outDir, "desktop-language-zh.png");
  const setupPath = path.join(outDir, "desktop-setup-guide.png");
  const matrixPath = path.join(outDir, "desktop-status-matrix.png");
  const agentsPath = path.join(outDir, "desktop-agents.png");
  const memoryOverviewPath = path.join(outDir, "desktop-memory-overview.png");
  const memoryTimelinePath = path.join(outDir, "desktop-memory-timeline.png");
  const memoryGraphPath = path.join(outDir, "desktop-memory-graph.png");
  const memoryEvidencePath = path.join(outDir, "desktop-memory-evidence.png");
  const memoryInboxPath = path.join(outDir, "desktop-memory-inbox.png");
  const memoryConflictsPath = path.join(outDir, "desktop-memory-conflicts.png");
  const mcpGovernancePath = path.join(outDir, "desktop-mcp-governance.png");
  const mcpImportPath = path.join(outDir, "desktop-mcp-import.png");
  const mcpTaskPath = path.join(outDir, "desktop-mcp-task-transparency.png");
  const capabilitiesPath = path.join(outDir, "desktop-capabilities.png");
  const marketplacePath = path.join(outDir, "desktop-marketplace.png");
  const settingsPath = path.join(outDir, "desktop-settings.png");
  const agentMemoryPath = path.join(outDir, "desktop-agent-memory.png");
  const evolutionV2Path = path.join(outDir, "desktop-evolution-v2.png");
  const runtimePath = path.join(outDir, "desktop-runtimes.png");
  try {
    fs.mkdirSync(outDir, { recursive: true });
    let state;
    for (let attempt = 0; attempt < 60; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      state = await mainWindow.webContents.executeJavaScript(`(() => ({
        app: Boolean(document.querySelector("#agentApp")),
        title: document.querySelector("#conversationTitle")?.textContent || "",
        composer: Boolean(document.querySelector("#promptInput")),
        backend: document.querySelector("#backendBadge")?.textContent || "",
        agents: document.querySelectorAll("#agentContactList .agent-contact").length
      }))()`);
      if (state.app && state.title.trim() && state.composer && state.backend.trim()) break;
    }
    if (!state?.app || !state.title.trim() || !state.composer || !state.backend.trim()) {
      throw new Error(`Desktop Agent workspace did not render: ${JSON.stringify(state)}`);
    }
    const conversationDeleteMenu = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const menuButton = document.querySelector("#conversationListMenuButton");
        const menu = document.querySelector("#conversationListMenu");
        const defaultStyle = getComputedStyle(menuButton);
        const defaultOpacity = defaultStyle.opacity;
        const defaultPointerEvents = defaultStyle.pointerEvents;
        menuButton.focus();
        menuButton.getAnimations().forEach((animation) => animation.finish());
        await new Promise((resolve) => requestAnimationFrame(resolve));
        const focusedStyle = getComputedStyle(menuButton);
        const dotStyle = getComputedStyle(menuButton, "::before");
        const focusedOpacity = focusedStyle.opacity;
        const focusedPointerEvents = focusedStyle.pointerEvents;
        const dotTop = dotStyle.top;
        const dotHeight = dotStyle.height;
        const buttonHeight = focusedStyle.height;
        const newTaskRow = menuButton.closest(".new-task-row");
        const newTaskButton = newTaskRow.querySelector(".new-task-button");
        const newTaskRowRect = newTaskRow.getBoundingClientRect();
        const newTaskButtonRect = newTaskButton.getBoundingClientRect();
        const historyProbe = document.createElement("div");
        historyProbe.className = "history-item-shell";
        historyProbe.innerHTML = '<button class="history-item"><span class="history-title-row"><strong>Probe</strong><time>Now</time></span><span class="history-preview">Preview</span></button><button class="history-more"></button>';
        document.querySelector("#taskHistory").appendChild(historyProbe);
        const historyButton = historyProbe.querySelector(".history-more");
        const historyItem = historyProbe.querySelector(".history-item");
        const historyTime = historyProbe.querySelector("time");
        const historyProbeRect = historyProbe.getBoundingClientRect();
        const historyItemRect = historyItem.getBoundingClientRect();
        const historyTimeRect = historyTime.getBoundingClientRect();
        const historyDefaultStyle = getComputedStyle(historyButton);
        const historyDefaultOpacity = historyDefaultStyle.opacity;
        const historyDefaultPointerEvents = historyDefaultStyle.pointerEvents;
        const historyTimeDefaultOpacity = getComputedStyle(historyTime).opacity;
        historyButton.focus();
        historyButton.getAnimations().forEach((animation) => animation.finish());
        historyTime.getAnimations().forEach((animation) => animation.finish());
        await new Promise((resolve) => requestAnimationFrame(resolve));
        const historyFocusedStyle = getComputedStyle(historyButton);
        const historyDotStyle = getComputedStyle(historyButton, "::before");
        menuButton.click();
        const menuItems = Array.from(menu.querySelectorAll("button"));
        const result = {
          defaultOpacity,
          defaultPointerEvents,
          focusedOpacity,
          focusedPointerEvents,
          dotTop,
          dotHeight,
          buttonHeight,
          newTaskMenuPosition: focusedStyle.position,
          newTaskFullWidthGap: Math.abs(newTaskRowRect.right - newTaskButtonRect.right),
          historyDefaultOpacity,
          historyDefaultPointerEvents,
          historyTimeDefaultOpacity,
          historyTimeFocusedOpacity: getComputedStyle(historyTime).opacity,
          historyMenuPosition: historyFocusedStyle.position,
          historyFullWidthGap: Math.abs(historyProbeRect.right - historyItemRect.right),
          historyDateTrailingGap: Math.abs(historyItemRect.right - historyTimeRect.right),
          historyFocusedOpacity: historyFocusedStyle.opacity,
          historyFocusedPointerEvents: historyFocusedStyle.pointerEvents,
          historyDotTop: historyDotStyle.top,
          historyButtonHeight: historyFocusedStyle.height,
          menuVisible: !menu.hidden,
          menuItemCount: menuItems.length,
          menuLabel: menuItems[0]?.textContent?.trim() || "",
          legacyDeleteAllPresent: Boolean(document.querySelector("#deleteAllConversationsButton"))
        };
        historyProbe.remove();
        menuItems[0]?.click();
        result.selectionVisible = !document.querySelector("#conversationSelectionBar").hidden;
        document.querySelector("#cancelConversationSelectionButton").click();
        return result;
      })()
    `);
    if (conversationDeleteMenu.defaultOpacity !== "0"
        || conversationDeleteMenu.defaultPointerEvents !== "none"
        || Number.parseFloat(conversationDeleteMenu.focusedOpacity) < 0.99
        || conversationDeleteMenu.focusedPointerEvents !== "auto"
        || Math.abs(
          Number.parseFloat(conversationDeleteMenu.dotTop)
            - Number.parseFloat(conversationDeleteMenu.buttonHeight) / 2
        ) > 0.1
        || conversationDeleteMenu.newTaskMenuPosition !== "absolute"
        || conversationDeleteMenu.newTaskFullWidthGap > 0.5
        || conversationDeleteMenu.historyDefaultOpacity !== "0"
        || conversationDeleteMenu.historyDefaultPointerEvents !== "none"
        || conversationDeleteMenu.historyTimeDefaultOpacity !== "1"
        || conversationDeleteMenu.historyTimeFocusedOpacity !== "0"
        || conversationDeleteMenu.historyMenuPosition !== "absolute"
        || conversationDeleteMenu.historyFullWidthGap > 0.5
        || conversationDeleteMenu.historyDateTrailingGap > 11
        || Number.parseFloat(conversationDeleteMenu.historyFocusedOpacity) < 0.99
        || conversationDeleteMenu.historyFocusedPointerEvents !== "auto"
        || Math.abs(
          Number.parseFloat(conversationDeleteMenu.historyDotTop)
            - Number.parseFloat(conversationDeleteMenu.historyButtonHeight) / 2
        ) > 0.1
        || !conversationDeleteMenu.menuVisible
        || conversationDeleteMenu.menuItemCount !== 1
        || !["Select conversations to delete", "\u9009\u62e9\u5220\u6389\u5bf9\u8bdd"].includes(conversationDeleteMenu.menuLabel)
        || conversationDeleteMenu.legacyDeleteAllPresent
        || !conversationDeleteMenu.selectionVisible) {
      throw new Error(`Desktop conversation deletion menu is invalid: ${JSON.stringify(conversationDeleteMenu)}`);
    }
    const bulkConversationDelete = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const originalTasks = state.tasks;
        const originalConversationId = state.currentConversationId;
        const originalEmptyIntent = state.emptyConversationIntent;
        const originalHiddenIds = new Set(state.hiddenEvolutionConversationIds);
        const originalConfirm = window.confirm;
        const now = Date.now();
        state.tasks = ["one", "two"].map((suffix, index) => ({
          task_id: "smoke-delete-" + suffix,
          conversation_id: "evolution:smoke-delete-" + suffix,
          task_kind: "self_evolution",
          prompt: "Smoke conversation " + suffix,
          result: "Completed",
          status: "completed",
          created_at: now + index,
          updated_at: now + index
        }));
        state.hiddenEvolutionConversationIds.clear();
        state.currentConversationId = "evolution:smoke-delete-one";
        state.emptyConversationIntent = false;
        window.confirm = () => true;
        renderHistory();
        setConversationSelectionMode(true);
        document.querySelector("#selectAllConversationsButton").click();
        const selectedBeforeDelete = state.selectedConversationIds.size;
        document.querySelector("#deleteSelectedConversationsButton").click();
        if (state.conversationDeletionPromise) await state.conversationDeletionPromise;
        const result = {
          selectedBeforeDelete,
          deletingCount: state.deletingConversationIds.size,
          selectionMode: state.conversationSelectionMode,
          hiddenCount: ["one", "two"].filter((suffix) =>
            state.hiddenEvolutionConversationIds.has("evolution:smoke-delete-" + suffix)
          ).length,
          visibleConversationCount: unifiedConversationGroups().filter((group) =>
            group.id.startsWith("evolution:smoke-delete-")
          ).length
        };
        window.confirm = originalConfirm;
        state.tasks = originalTasks;
        state.currentConversationId = originalConversationId;
        state.emptyConversationIntent = originalEmptyIntent;
        state.hiddenEvolutionConversationIds = originalHiddenIds;
        persistHiddenEvolutionConversations();
        setConversationSelectionMode(false);
        renderConversation(true);
        return result;
      })()
    `);
    if (bulkConversationDelete.selectedBeforeDelete !== 2
        || bulkConversationDelete.deletingCount !== 0
        || bulkConversationDelete.selectionMode
        || bulkConversationDelete.hiddenCount !== 2
        || bulkConversationDelete.visibleConversationCount !== 0) {
      throw new Error(`Desktop bulk conversation deletion failed: ${JSON.stringify(bulkConversationDelete)}`);
    }
    const peerVoiceInput = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const app = document.querySelector("#agentApp");
        const button = document.querySelector("#voiceButton");
        const prompt = document.querySelector("#promptInput");
        const taskState = document.querySelector("#taskStateText");
        const separator = document.querySelector(".header-separator");
        const statusDot = document.querySelector("#routeStatusDot");
        const route = document.querySelector("#routeText");
        const originalRecognition = window.SpeechRecognition;
        app.classList.add("peer-mode");
        route.textContent = "GalaxySSI Link encrypted";
        window.SpeechRecognition = class {
          start() {
            this.onresult?.({ results: [[{ transcript: "device voice input" }]] });
            this.onend?.();
          }
          stop() { this.onend?.(); }
        };
        prompt.value = "";
        button.click();
        await new Promise((resolve) => setTimeout(resolve, 50));
        const result = {
          display: getComputedStyle(button).display,
          disabled: button.disabled,
          transcript: prompt.value,
          taskStateDisplay: getComputedStyle(taskState).display,
          separatorDisplay: getComputedStyle(separator).display,
          statusDotDisplay: getComputedStyle(statusDot).display,
          statusDotWidth: getComputedStyle(statusDot).width,
          statusDotColor: getComputedStyle(statusDot).backgroundColor,
          route: route.textContent
        };
        app.classList.remove("peer-mode");
        window.SpeechRecognition = originalRecognition;
        prompt.value = "";
        return result;
      })()
    `);
    if (peerVoiceInput.display === "none"
        || peerVoiceInput.disabled
        || peerVoiceInput.transcript !== "device voice input"
        || peerVoiceInput.taskStateDisplay !== "none"
        || peerVoiceInput.separatorDisplay !== "none"
        || peerVoiceInput.statusDotDisplay === "none"
        || Math.abs(Number.parseFloat(peerVoiceInput.statusDotWidth) - 7) > 0.1
        || peerVoiceInput.statusDotColor === "rgba(0, 0, 0, 0)"
        || peerVoiceInput.route !== "GalaxySSI Link encrypted") {
      throw new Error(`Desktop device chat controls are unavailable: ${JSON.stringify(peerVoiceInput)}`);
    }
    const defaultLanguage = await mainWindow.webContents.executeJavaScript(`
      (() => ({
        lang: document.documentElement.lang,
        selected: document.querySelector("#languageSelect")?.value || "",
        title: document.querySelector("#conversationTitle")?.textContent || "",
        system: navigator.language || ""
      }))()
    `);
    const systemUsesChinese = String(defaultLanguage.system || "").toLowerCase().startsWith("zh");
    const expectedDefaultLanguage = systemUsesChinese ? "zh-Hans" : "en";
    const expectedDefaultTitle = systemUsesChinese ? "\u65b0\u5efa\u4efb\u52a1" : "New task";
    if (defaultLanguage.lang !== expectedDefaultLanguage
        || defaultLanguage.selected !== "auto"
        || defaultLanguage.title !== expectedDefaultTitle) {
      throw new Error(`Desktop did not follow the system language by default: ${JSON.stringify(defaultLanguage)}`);
    }
    await captureSmokeScreenshot(languageEnPath);
    const zhLanguage = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const select = document.querySelector("#languageSelect");
        select.value = "zh-CN";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await new Promise((resolve) => setTimeout(resolve, 900));
        return {
          lang: document.documentElement.lang,
          selected: select.value,
          title: document.querySelector("#conversationTitle")?.textContent || ""
        };
      })()
    `);
    if (zhLanguage.lang !== "zh-Hans" || zhLanguage.selected !== "zh-CN" || zhLanguage.title !== "\u65b0\u5efa\u4efb\u52a1") {
      throw new Error(`Desktop Simplified Chinese language switch failed: ${JSON.stringify(zhLanguage)}`);
    }
    await captureSmokeScreenshot(languageZhPath);
    const restoredLanguage = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const select = document.querySelector("#languageSelect");
        select.value = "en";
        select.dispatchEvent(new Event("change", { bubbles: true }));
        await new Promise((resolve) => setTimeout(resolve, 900));
        return {
          lang: document.documentElement.lang,
          selected: select.value,
          title: document.querySelector("#conversationTitle")?.textContent || ""
        };
      })()
    `);
    if (restoredLanguage.lang !== "en" || restoredLanguage.selected !== "en" || restoredLanguage.title !== "New task") {
      throw new Error(`Desktop English language restore failed: ${JSON.stringify(restoredLanguage)}`);
    }
    await captureSmokeScreenshot(overviewPath);
    const peerFixture = await new Promise((resolve, reject) => {
      execFile(findPython(), [
        path.join(BACKEND_DIR, "ui_smoke_fixtures.py"),
        path.join(app.getPath("userData"), "runtime"),
        path.join(__dirname, "renderer", "galaxyssi-mark.png")
      ], {
        cwd: BACKEND_DIR, windowsHide: true, timeout: 30_000,
        env: { ...process.env, GALAXYSSI_UI_SMOKE_DIR: outDir,
          GALAXYSSI_DISABLE_EXTERNAL_SERVICES: "1" }
      }, (error, stdout, stderr) => {
        if (error) { reject(new Error(`UI smoke fixture failed: ${stderr || error.message}`)); return; }
        try { resolve(JSON.parse(stdout)); } catch (parseError) { reject(parseError); }
      });
    });
    const peerImageState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const routeId = ${JSON.stringify(peerFixture.route_id)};
        const messageId = ${JSON.stringify(peerFixture.message_id)};
        state.pairing = {
          ...(state.pairing || {}),
          clients: [{ client_route_id: routeId, display_name: "Galaxy S26 Ultra" }]
        };
        state.activePeerRouteId = routeId;
        await refreshPeerMessages();
        await loadPeerImagePreview(messageId, 0);
        state.renderingSignature = "";
        document.querySelector("#agentApp").classList.add("peer-mode");
        renderHistory();
        renderPeerConversation(true);
        const image = document.querySelector("[data-peer-image-preview]");
        const card = document.querySelector(".peer-image-attachment");
        try { await image?.decode(); } catch {}
        return {
          card: Boolean(card?.classList.contains("loaded")),
          cardWidth: card?.getBoundingClientRect().width || 0,
          cardHeight: card?.getBoundingClientRect().height || 0,
          naturalWidth: image?.naturalWidth || 0,
          naturalHeight: image?.naturalHeight || 0
        };
      })()
    `);
    if (!peerImageState.card
        || peerImageState.cardWidth > 161
        || peerImageState.cardHeight > 181
        || peerImageState.naturalWidth < 1
        || peerImageState.naturalHeight < 1) {
      throw new Error(`Desktop peer image preview did not render: ${JSON.stringify(peerImageState)}`);
    }
    await captureSmokeScreenshot(peerImagePath);
    const peerImageViewerState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        // Exercise the refresh which used to erase the memory-only fixture.
        await refreshPeerMessages();
        const button = document.querySelector("[data-view-peer-image]");
        if (!button) throw new Error("Durable smoke image is missing after refresh");
        button.click();
        const viewer = document.querySelector("#peerImageViewer");
        const image = document.querySelector("#peerImageViewerImage");
        const deadline = Date.now() + 5000;
        while (viewer?.hidden !== false && Date.now() < deadline) {
          await new Promise((resolve) => setTimeout(resolve, 25));
        }
        try { await image?.decode(); } catch {}
        return {
          open: viewer?.hidden === false,
          image: image?.naturalWidth > 0 && image?.naturalHeight > 0,
          save: Boolean(document.querySelector("#savePeerImageButton")),
          close: Boolean(document.querySelector("#closePeerImageViewerButton")),
          messageCount: state.peerMessages.length,
          focused: document.hasFocus()
        };
      })()
    `);
    if (!peerImageViewerState.open || !peerImageViewerState.image || !peerImageViewerState.save || !peerImageViewerState.close) {
      throw new Error(`Desktop peer image viewer did not render: ${JSON.stringify(peerImageViewerState)}`);
    }
    await captureSmokeScreenshot(peerImageViewerPath);
    await mainWindow.webContents.executeJavaScript(`closePeerImageViewer(); newTask();`);
    const composerAttachmentState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const imageBytes = new Uint8Array(await (await fetch("./galaxyssi-mark.png")).arrayBuffer());
        const clipboardData = new DataTransfer();
        clipboardData.items.add(new File([imageBytes], "GalaxySSI-photo.png", { type: "image/png" }));
        clipboardData.items.add(new File(["smoke PDF attachment"], "design-notes.pdf", { type: "application/pdf" }));
        const pasteEvent = new Event("paste", { bubbles: true, cancelable: true });
        Object.defineProperty(pasteEvent, "clipboardData", { value: clipboardData });
        document.querySelector("#promptInput").dispatchEvent(pasteEvent);
        const deadline = Date.now() + 5_000;
        while (state.attachments.length < 2 && Date.now() < deadline) {
          await new Promise((resolve) => setTimeout(resolve, 50));
        }
        const image = document.querySelector(".composer-image-attachment img");
        try { await image?.decode(); } catch {}
        const imageCard = document.querySelector(".composer-image-attachment");
        const fileCard = document.querySelector(".composer-file-attachment");
        return {
          imageWidth: imageCard?.getBoundingClientRect().width || 0,
          imageHeight: imageCard?.getBoundingClientRect().height || 0,
          fileWidth: fileCard?.getBoundingClientRect().width || 0,
          fileHeight: fileCard?.getBoundingClientRect().height || 0,
          imageLoaded: (image?.naturalWidth || 0) > 0,
          pastePrevented: pasteEvent.defaultPrevented,
          attachmentCount: state.attachments.length
        };
      })()
    `);
    if (!composerAttachmentState.pastePrevented
        || composerAttachmentState.attachmentCount !== 2
        || !composerAttachmentState.imageLoaded
        || Math.abs(composerAttachmentState.imageWidth - 116) > 1
        || Math.abs(composerAttachmentState.imageHeight - 88) > 1
        || Math.abs(composerAttachmentState.fileWidth - 238) > 1
        || Math.abs(composerAttachmentState.fileHeight - 64) > 1) {
      throw new Error(`Desktop composer attachment previews did not render: ${JSON.stringify(composerAttachmentState)}`);
    }
    await captureSmokeScreenshot(composerAttachmentsPath);
    await mainWindow.webContents.executeJavaScript(`newTask();`);
    const evolutionTimelineState = await mainWindow.webContents.executeJavaScript(`
      (() => {
        const task = {
          task_id: "smoke-self-evolution",
          task_kind: "self_evolution",
          agent_id: "self-evolution",
          delegate_agent_id: "codex",
          conversation_id: "evolution:smoke-self-evolution",
          source_message_id: "desktop:evolution:smoke-self-evolution",
          prompt: "Improve automatic recovery for interrupted Desktop tasks",
          status: "running",
          evolution_status: "validating",
          current_step: "Quality gate started",
          automatic: true,
          created_at: Date.now() - 12_000,
          started_at: Date.now() - 11_000,
          updated_at: Date.now(),
          events: [
            { title: "Self-evolution task started", status: "completed" },
            { title: "Isolated workspace prepared", detail: "Attempt 1 - evolution/smoke", status: "completed" },
            { title: "Implementation Agent started", detail: "Attempt 1 of 3 - codex", status: "completed" },
            { title: "Quality gate started", detail: "desktop-source-smoke", status: "running" }
          ],
          output_files: [],
          attachments: []
        };
        mergeTaskUpdate(task);
        state.currentConversationId = task.conversation_id;
        state.renderingSignature = "";
        renderHistory();
        renderConversation(true);
        const turn = document.querySelector('[data-task-id="smoke-self-evolution"]');
        return {
          history: document.querySelectorAll('[data-conversation-id="evolution:smoke-self-evolution"]').length,
          turn: Boolean(turn),
          origin: turn?.querySelector(".task-origin")?.textContent || "",
          events: turn?.querySelectorAll(".event-row").length || 0,
          expanded: turn?.querySelector(".run-detail")?.hidden === false,
          executor: turn?.querySelector(".run-summary-copy strong")?.textContent || "",
          executionDetail: turn?.querySelector(".run-summary-copy small")?.textContent || "",
          cancelActions: turn?.querySelectorAll("[data-cancel-task]").length || 0
        };
      })()
    `);
    if (
      evolutionTimelineState.history !== 1
      || !evolutionTimelineState.turn
      || !evolutionTimelineState.origin.trim()
      || evolutionTimelineState.events !== 4
      || !evolutionTimelineState.expanded
      || !evolutionTimelineState.executor.includes("Codex")
      || !evolutionTimelineState.executionDetail.includes("This desktop")
      || !evolutionTimelineState.executionDetail.includes("Quality gate started")
      || evolutionTimelineState.cancelActions !== 1
    ) {
      throw new Error(`Self-evolution timeline did not render in the main output: ${JSON.stringify(evolutionTimelineState)}`);
    }
    await captureSmokeScreenshot(evolutionTimelinePath);
    const mcpTaskState = await mainWindow.webContents.executeJavaScript(`
      (() => {
        const task = {
          task_id: "smoke-mcp-task",
          agent_id: "mcp:smoke-vault",
          conversation_id: "mcp:smoke-task",
          source_message_id: "desktop:smoke-mcp-task",
          prompt: "Search the private release index",
          status: "running",
          current_step: "Smoke Vault · search",
          created_at: Date.now() - 2_000,
          started_at: Date.now() - 1_900,
          updated_at: Date.now(),
          events: [{
            event_id: "mcp-tool:smoke",
            kind: "mcp",
            title: "Smoke Vault · search",
            status: "running",
            metadata: {
              kind: "mcp_tool_call",
              source: "desktop-mcp:smoke-vault",
              risk: "low",
              permissions: ["mcp.data.read", "mcp.network.connect"],
              parameter_preview: { query: "release notes" },
              status: "running"
            }
          }],
          output_files: [],
          attachments: []
        };
        mergeTaskUpdate(task);
        state.currentConversationId = task.conversation_id;
        state.renderingSignature = "";
        renderHistory();
        renderConversation(true);
        document.querySelector('[data-toggle-run="smoke-mcp-task"]')?.click();
        const turn = document.querySelector('[data-task-id="smoke-mcp-task"]');
        return {
          eventCount: turn?.querySelectorAll(".mcp-tool-event").length || 0,
          text: turn?.querySelector(".mcp-tool-event")?.textContent || "",
          code: turn?.querySelector(".mcp-tool-event code")?.textContent || "",
          expanded: turn?.querySelector(".run-detail")?.hidden === false
        };
      })()
    `);
    if (
      mcpTaskState.eventCount !== 1
      || !mcpTaskState.expanded
      || !mcpTaskState.text.includes("desktop-mcp:smoke-vault")
      || !mcpTaskState.text.includes("mcp.data.read")
      || !mcpTaskState.code.includes("release notes")
    ) {
      throw new Error(`MCP task transparency did not render: ${JSON.stringify(mcpTaskState)}`);
    }
    await captureSmokeScreenshot(mcpTaskPath);
    await mainWindow.webContents.executeJavaScript(`
      (() => {
        state.tasks = state.tasks.filter((task) => !["smoke-self-evolution", "smoke-mcp-task"].includes(task.task_id));
        newTask();
      })()
    `);
    const agentsState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        await window.galaxyssi.startBackend();
        document.querySelector('[data-open-panel="agents"]')?.click();
        for (let attempt = 0; attempt < 30; attempt += 1) {
          if (document.querySelectorAll("#agentContactList .agent-contact").length > 0) break;
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
        for (let attempt = 0; attempt < 40; attempt += 1) {
          if (document.querySelectorAll("#agentPerformanceList .performance-agent").length === 4) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        document.querySelector('[data-performance-window="all"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 250));
        const customDetails = document.querySelector("#agentsPanel .drawer-details");
        if (customDetails) customDetails.open = true;
        document.querySelector(".agent-performance-lab")?.scrollIntoView({ block: "start" });
        return {
          open: document.querySelector("#utilityDrawer")?.classList.contains("open") || false,
          active: document.querySelector("#agentsPanel")?.classList.contains("active") || false,
          contacts: document.querySelectorAll("#agentContactList .agent-contact").length,
          performanceRows: document.querySelectorAll("#agentPerformanceList .performance-agent").length,
          performanceWindows: document.querySelectorAll("[data-performance-window]").length,
          performanceWindow: document.querySelector("[data-performance-window].active")?.dataset.performanceWindow || "",
          performanceSummary: document.querySelector("#agentPerformanceSummary")?.textContent || "",
          performanceSuccess: document.querySelector(".performance-overview strong")?.textContent || "",
          customFields: document.querySelectorAll("#agentsPanel .form-stack input").length,
          customTransport: Boolean(document.querySelector("#customAgentTransport")),
          customPoolSize: Boolean(document.querySelector("#customAgentPoolSize")),
          customPrewarm: Boolean(document.querySelector("#customAgentPrewarm")),
          contactText: document.querySelector("#agentContactList")?.textContent || ""
        };
      })()
    `);
    if (
      !agentsState.open
      || !agentsState.active
      || agentsState.contacts < 1
      || agentsState.performanceRows !== 4
      || agentsState.performanceWindows !== 4
      || agentsState.performanceWindow !== "all"
      || !agentsState.performanceSummary.trim()
      || agentsState.performanceSuccess !== "\u2014"
      || agentsState.customFields < 4
      || !agentsState.customTransport
      || !agentsState.customPoolSize
      || !agentsState.customPrewarm
    ) {
      throw new Error(`Agent drawer did not expose contacts and custom Agent setup: ${JSON.stringify(agentsState)}`);
    }
    await captureSmokeScreenshot(agentsPath);
    const capabilitiesState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        document.querySelector('[data-open-panel="capabilities"]')?.click();
        for (let attempt = 0; attempt < 40; attempt += 1) {
          if (document.querySelectorAll("#skillList .capability-item").length >= 4) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        await window.galaxyssi.proposeDesktopMemory({
          content: "Prefer concise verified release summaries",
          kind: "preference",
          importance: 0.8,
          namespace: "user"
        });
        await window.galaxyssi.rememberDesktopMemory({
          content: "The desktop memory runtime is ready",
          kind: "device_state",
          importance: 0.8,
          namespace: "device"
        });
        await window.galaxyssi.rememberDesktopMemory({
          content: "Plan the next desktop memory audit",
          kind: "goal",
          importance: 0.8,
          namespace: "project"
        });
        await window.galaxyssi.proposeDesktopMemory({
          content: "The desktop memory runtime is inaccessible",
          kind: "device_state",
          importance: 0.8,
          namespace: "device"
        });
        const criticRun = await window.galaxyssi.runDesktopMemoryCritic();
        await refreshMemory("");
        document.querySelector('[data-capability-tab="memory"]')?.click();
        document.querySelector('[data-memory-view="overview"]')?.click();
        const overviewState = {
          metrics: document.querySelectorAll(".memory-metric").length,
          health: document.querySelector(".memory-health")?.textContent || "",
          auditAction: Boolean(document.querySelector("[data-run-memory-critic]")),
          criticRun: criticRun?.run?.status || "",
          visualizationTabs: document.querySelectorAll("[data-memory-visualization-view]").length,
          recent: document.querySelectorAll(".memory-evolution-list > div").length
        };
        document.querySelector('[data-memory-visualization-view="timeline"]')?.click();
        overviewState.timelineEvents = document.querySelectorAll(".memory-timeline-event").length;
        document.querySelector('[data-memory-visualization-view="graph"]')?.click();
        overviewState.graphNodes = document.querySelectorAll(".memory-graph-node").length;
        overviewState.graphRelations = document.querySelectorAll(".memory-graph-edges line").length;
        const graphTargets = Array.from(document.querySelectorAll(".memory-graph-node"));
        const graphTargetId = graphTargets.at(1)?.dataset.memoryGraphNode || "";
        graphTargets.at(1)?.dispatchEvent(new MouseEvent("click", { bubbles: true }));
        overviewState.graphSelection = graphTargetId
          && document.querySelector(".memory-graph-node.selected")?.dataset.memoryGraphNode === graphTargetId;
        document.querySelector('[data-memory-visualization-view="evidence"]')?.click();
        overviewState.evidenceChains = document.querySelectorAll(".memory-evidence-index button").length;
        overviewState.evidenceVersions = document.querySelectorAll(".memory-version-chain > div").length;
        const evidenceTargets = Array.from(document.querySelectorAll(".memory-evidence-index button"));
        const evidenceTargetId = evidenceTargets.at(1)?.dataset.memoryEvidenceChain || "";
        evidenceTargets.at(1)?.click();
        overviewState.evidenceSelection = evidenceTargetId
          && document.querySelector(".memory-evidence-index button.active")?.dataset.memoryEvidenceChain === evidenceTargetId;
        document.querySelector('[data-memory-visualization-view="state"]')?.click();
        document.querySelector('[data-memory-view="planned"]')?.click();
        const plannedState = {
          active: document.querySelector('[data-memory-view="planned"]')?.classList.contains("active") || false,
          count: Number(document.querySelector("#memoryPlannedCount")?.textContent || "0"),
          rows: document.querySelectorAll("#memoryList .capability-item").length,
          content: document.querySelector("#memoryList")?.textContent || ""
        };
        document.querySelector('[data-memory-view="inbox"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 250));
        const memoryState = {
          active: document.querySelector("#memoryCapability")?.classList.contains("active") || false,
          views: document.querySelectorAll("[data-memory-view]").length,
          inboxActive: document.querySelector('[data-memory-view="inbox"]')?.classList.contains("active") || false,
          summary: document.querySelector("#memorySummary")?.textContent || "",
          inboxCount: document.querySelector("#memoryInboxCount")?.textContent || "",
          candidates: document.querySelectorAll("#memoryList .memory-candidate").length,
          approveActions: document.querySelectorAll("[data-approve-memory-candidate]").length,
          overviewState,
          plannedState
        };
        window.__galaxyssiMemorySmokeState = memoryState;
        return memoryState;
      })()
    `);
    if (
      !capabilitiesState.active
      || capabilitiesState.views !== 6
      || !capabilitiesState.inboxActive
      || !capabilitiesState.summary.trim()
      || !capabilitiesState.inboxCount.trim()
      || capabilitiesState.candidates !== 1
      || capabilitiesState.approveActions !== 1
      || capabilitiesState.overviewState.metrics !== 4
      || !capabilitiesState.overviewState.health.trim()
      || !capabilitiesState.overviewState.auditAction
      || capabilitiesState.overviewState.criticRun !== "completed"
      || capabilitiesState.overviewState.visualizationTabs !== 4
      || capabilitiesState.overviewState.timelineEvents < 3
      || capabilitiesState.overviewState.graphNodes < 2
      || capabilitiesState.overviewState.graphRelations < 1
      || !capabilitiesState.overviewState.graphSelection
      || capabilitiesState.overviewState.evidenceChains < 2
      || capabilitiesState.overviewState.evidenceVersions < 1
      || !capabilitiesState.overviewState.evidenceSelection
      || capabilitiesState.overviewState.recent < 2
      || !capabilitiesState.plannedState.active
      || capabilitiesState.plannedState.count !== 1
      || capabilitiesState.plannedState.rows !== 1
      || !capabilitiesState.plannedState.content.includes("Plan the next desktop memory audit")
    ) {
      throw new Error(`Memory Inbox did not render: ${JSON.stringify(capabilitiesState)}`);
    }
    await captureSmokeScreenshot(memoryInboxPath);
    const memoryReviewState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const approve = document.querySelector("[data-approve-memory-candidate]");
        if (approve?.dataset.approveMemoryCandidate) {
          await window.galaxyssi.reviewDesktopMemoryCandidate(
            approve.dataset.approveMemoryCandidate,
            "approve"
          );
          await refreshMemory("");
        }
        for (let attempt = 0; attempt < 40; attempt += 1) {
          const inboxCount = Number(document.querySelector("#memoryInboxCount")?.textContent || "-1");
          const currentCount = Number(document.querySelector("#memoryCurrentCount")?.textContent || "0");
          if (inboxCount === 0 && currentCount >= 1) break;
          await new Promise((resolve) => setTimeout(resolve, 100));
        }
        document.querySelector('[data-memory-view="conflicts"]')?.click();
        return {
          inboxCount: Number(document.querySelector("#memoryInboxCount")?.textContent || "-1"),
          currentCount: Number(document.querySelector("#memoryCurrentCount")?.textContent || "0"),
          conflicts: document.querySelectorAll("#memoryList .memory-conflict").length,
          comparisons: document.querySelectorAll("#memoryList .memory-comparison").length,
          conflictActions: document.querySelectorAll("#memoryList [data-approve-memory-candidate]").length
        };
      })()
    `);
    if (
      memoryReviewState.inboxCount !== 0
      || memoryReviewState.currentCount < 2
      || memoryReviewState.conflicts !== 1
      || memoryReviewState.comparisons !== 1
      || memoryReviewState.conflictActions !== 1
    ) {
      throw new Error(`Memory candidate approval did not persist: ${JSON.stringify(memoryReviewState)}`);
    }
    await captureSmokeScreenshot(memoryConflictsPath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector('[data-memory-view="overview"]')?.click()
    `);
    await captureSmokeScreenshot(memoryOverviewPath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector('[data-memory-visualization-view="timeline"]')?.click()
    `);
    await captureSmokeScreenshot(memoryTimelinePath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector('[data-memory-visualization-view="graph"]')?.click()
    `);
    await captureSmokeScreenshot(memoryGraphPath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector('[data-memory-visualization-view="evidence"]')?.click()
    `);
    await captureSmokeScreenshot(memoryEvidencePath);
    await mainWindow.webContents.executeJavaScript(`
      document.querySelector('[data-memory-visualization-view="state"]')?.click()
    `);
    const mcpGovernanceState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        state.mcp = [{
          id: "smoke-vault",
          name: "Smoke Vault",
          transport: "streamable_http",
          endpoint: "https://mcp.example.test/mcp",
          header_env: { Authorization: "GALAXYSSI_SMOKE_MCP_TOKEN" },
          protocol_version: "2025-11-25",
          state: "ready",
          server_name: "Smoke MCP",
          server_version: "1.0",
          tool_ids: ["read_secret_metadata"],
          last_latency_ms: 18,
          default_tool: "read_secret_metadata",
          enabled: true,
          auto_invoke: false,
          permission_mode: "read_only"
        }];
        state.mcpAudit = [{
          audit_id: "smoke-mcp-audit",
          connection_id: "smoke-vault",
          connection_name: "Smoke Vault",
          tool_name: "read_secret_metadata",
          source: "desktop-mcp:smoke-vault",
          risk: "low",
          permissions: ["mcp.data.read", "mcp.secrets.use"],
          decision: "allow",
          status: "succeeded",
          parameter_preview: {
            namespace: "release",
            api_key: "[REDACTED]"
          },
          duration_ms: 18
        }];
        renderMcp();
        document.querySelector('[data-capability-tab="mcp"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 250));
        const auditText = document.querySelector("#mcpAuditList")?.textContent || "";
        const initialAddPolicy = document.querySelector("#mcpPermissionMode")?.value || "";
        document.querySelector("[data-edit-mcp]")?.click();
        return {
          active: document.querySelector("#mcpCapability")?.classList.contains("active") || false,
          policy: document.querySelector("[data-mcp-permission]")?.value || "",
          initialAddPolicy,
          editorPolicy: document.querySelector("#mcpPermissionMode")?.value || "",
          transportText: document.querySelector("#mcpList .capability-item small")?.textContent || "",
          transportOptions: document.querySelectorAll("#mcpTransport option").length,
          endpointAvailable: Boolean(document.querySelector("#mcpEndpoint")),
          editAvailable: Boolean(document.querySelector("[data-edit-mcp]")),
          editorTransport: document.querySelector("#mcpTransport")?.value || "",
          editorEndpoint: document.querySelector("#mcpEndpoint")?.value || "",
          endpointVisible: !document.querySelector("#mcpEndpointField")?.hidden,
          commandHidden: Boolean(document.querySelector("#mcpCommandField")?.hidden),
          idLocked: Boolean(document.querySelector("#mcpId")?.disabled),
          headerMapping: document.querySelector("#mcpHeaderEnv")?.value || "",
          auditRows: document.querySelectorAll("#mcpAuditList .mcp-audit-row").length,
          auditText,
          redacted: auditText.includes("[REDACTED]"),
          leaked: auditText.includes("smoke-secret-value")
        };
      })()
    `);
    if (
      !mcpGovernanceState.active
      || mcpGovernanceState.policy !== "read_only"
      || mcpGovernanceState.initialAddPolicy !== "ask_for_changes"
      || mcpGovernanceState.editorPolicy !== "read_only"
      || mcpGovernanceState.transportOptions !== 2
      || !mcpGovernanceState.endpointAvailable
      || !mcpGovernanceState.editAvailable
      || !mcpGovernanceState.transportText.includes("Streamable HTTP")
      || !mcpGovernanceState.transportText.includes("Smoke MCP")
      || mcpGovernanceState.editorTransport !== "streamable_http"
      || mcpGovernanceState.editorEndpoint !== "https://mcp.example.test/mcp"
      || !mcpGovernanceState.endpointVisible
      || !mcpGovernanceState.commandHidden
      || !mcpGovernanceState.idLocked
      || !mcpGovernanceState.headerMapping.includes("Authorization=GALAXYSSI_SMOKE_MCP_TOKEN")
      || mcpGovernanceState.auditRows !== 1
      || !mcpGovernanceState.redacted
      || mcpGovernanceState.leaked
      || !mcpGovernanceState.auditText.includes("desktop-mcp:smoke-vault")
      || !mcpGovernanceState.auditText.includes("mcp.data.read")
    ) {
      throw new Error(`MCP governance did not render safely: ${JSON.stringify(mcpGovernanceState)}`);
    }
    await captureSmokeScreenshot(mcpGovernancePath);
    const mcpImportState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        document.querySelector("#mcpEditor").open = false;
        state.mcpImport = {
          sources: [
            { source: "claude", path: "C:/Users/smoke/AppData/Roaming/Claude/claude_desktop_config.json", file_name: "claude_desktop_config.json" },
            { source: "codex", path: "C:/Users/smoke/.codex/config.toml", file_name: "config.toml" },
            { source: "openclaw", path: "C:/Users/smoke/.openclaw/openclaw.json", file_name: "openclaw.json" },
            { source: "hermes", path: "C:/Users/smoke/.hermes/config.yaml", file_name: "config.yaml" }
          ],
          fileName: "openclaw.json",
          content: "{mcp:{servers:{docs:{command:'npx'}}}}",
          sourceHint: "openclaw",
          preview: {
            source: "openclaw",
            digest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            candidates: [
              {
                id: "context7",
                name: "Context7",
                source: "openclaw",
                transport: "local_stdio",
                command: "npx -y @upstash/context7-mcp",
                endpoint: "",
                enabled: true,
                permission_mode: "ask_for_changes",
                importable: true,
                conflict: false,
                warnings: [],
                missing_environment: [],
                credential_references: 0
              },
              {
                id: "figma",
                name: "Figma",
                source: "openclaw",
                transport: "streamable_http",
                command: "",
                endpoint: "https://mcp.figma.com/mcp",
                enabled: true,
                permission_mode: "ask_for_changes",
                importable: true,
                conflict: true,
                warnings: ["Set the listed environment variables before testing this connection."],
                missing_environment: ["FIGMA_OAUTH_TOKEN"],
                credential_references: 1
              },
              {
                id: "legacy-sse",
                name: "Legacy SSE",
                source: "openclaw",
                transport: "streamable_http",
                command: "",
                endpoint: "https://legacy.example.test/sse",
                enabled: true,
                permission_mode: "ask_for_changes",
                importable: false,
                conflict: false,
                warnings: ["Legacy SSE transport needs a Streamable HTTP endpoint before it can be enabled."],
                missing_environment: [],
                credential_references: 0
              }
            ]
          }
        };
        renderMcpImporter();
        const importer = document.querySelector("#mcpImporter");
        if (importer) importer.open = true;
        const panel = document.querySelector("#capabilitiesPanel");
        if (panel && importer) panel.scrollTop = Math.max(0, importer.offsetTop - 80);
        await new Promise((resolve) => setTimeout(resolve, 250));
        const text = document.querySelector("#mcpImporter")?.textContent || "";
        return {
          open: Boolean(importer?.open),
          sources: document.querySelectorAll("[data-mcp-import-path]").length,
          candidates: document.querySelectorAll(".mcp-import-candidate").length,
          selectable: document.querySelectorAll("[data-mcp-import-id]:not(:disabled)").length,
          blocked: document.querySelectorAll(".mcp-import-candidate.blocked").length,
          commit: !document.querySelector("#commitMcpImportButton")?.hidden,
          text,
          leaked: text.includes("smoke-secret-value")
        };
      })()
    `);
    if (!mcpImportState.open || mcpImportState.sources !== 4
        || mcpImportState.candidates !== 3 || mcpImportState.selectable !== 2
        || mcpImportState.blocked !== 1 || !mcpImportState.commit
        || !mcpImportState.text.includes("FIGMA_OAUTH_TOKEN")
        || mcpImportState.leaked) {
      throw new Error(`MCP import preview did not render safely: ${JSON.stringify(mcpImportState)}`);
    }
    await captureSmokeScreenshot(mcpImportPath);
    const capabilityCatalogState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        document.querySelector('[data-capability-tab="automation"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 250));
        return {
          active: document.querySelector("#capabilitiesPanel")?.classList.contains("active") || false,
          tabs: document.querySelectorAll("[data-capability-tab]").length,
          marketplace: document.querySelectorAll("#marketplaceList .capability-item").length,
          skills: document.querySelectorAll("#skillList .capability-item").length,
          memory: document.querySelector("#memorySummary")?.textContent || "",
          mcpForm: Boolean(document.querySelector("#mcpCommand")),
          automationActive: document.querySelector("#automationCapability")?.classList.contains("active") || false,
          automationSummary: document.querySelector("#proactiveSummary")?.textContent || "",
          automationEditor: Boolean(document.querySelector("#proactiveCreateDetails"))
        };
      })()
    `);
    if (!capabilityCatalogState.active || capabilityCatalogState.tabs !== 5
        || capabilityCatalogState.marketplace < 1 || capabilityCatalogState.skills < 4
        || !capabilityCatalogState.memory.trim() || !capabilityCatalogState.mcpForm
        || !capabilityCatalogState.automationActive || !capabilityCatalogState.automationSummary.trim()
        || !capabilityCatalogState.automationEditor) {
      throw new Error(`Capabilities drawer did not expose memory, Skills, MCP, and automation: ${JSON.stringify(capabilityCatalogState)}`);
    }
    await captureSmokeScreenshot(capabilitiesPath);
    const marketplaceState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        document.querySelector('[data-capability-tab="marketplace"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 250));
        return {
          active: document.querySelector("#marketplaceCapability")?.classList.contains("active") || false,
          items: document.querySelectorAll("#marketplaceList .capability-item").length,
          filters: document.querySelectorAll("[data-marketplace-kind]").length,
          installActions: document.querySelectorAll("[data-install-marketplace]").length
        };
      })()
    `);
    if (!marketplaceState.active || marketplaceState.items < 1
        || marketplaceState.filters !== 4 || marketplaceState.installActions < 1) {
      throw new Error(`Tool Marketplace did not render installable catalog entries: ${JSON.stringify(marketplaceState)}`);
    }
    await captureSmokeScreenshot(marketplacePath);
    const gatewayControlState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const smokeDesktopControl = {
          authorizations: [
            {
              authorization_id: "smoke-active-authorization",
              app_name: "GalaxySSI Phone",
              app_platform: "android",
              app_identity_fingerprint: "${"a".repeat(64)}",
              access_profile: "desktop_executor",
              granted_at: Date.now() - 3600000,
              last_used_at: Date.now() - 60000,
              updated_at: Date.now(),
              status: "active"
            },
            {
              authorization_id: "smoke-revoked-authorization",
              app_name: "Previous Phone",
              app_platform: "android",
              app_identity_fingerprint: "${"b".repeat(64)}",
              access_profile: "desktop_executor",
              granted_at: Date.now() - 86400000,
              last_used_at: 0,
              updated_at: Date.now() - 1000,
              status: "revoked"
            }
          ],
          recent_receipts: [
            {
              receipt_version: 4,
              receipt_id: "${"c".repeat(64)}",
              task_id: "smoke-task",
              action_id: "smoke-action",
              authorization_id: "smoke-active-authorization",
              tool_id: "desktop.click_xy",
              status: "succeeded",
              summary: "Executed click at 120, 240",
              request_sha256: "${"d".repeat(64)}",
              input_sha256: "${"e".repeat(64)}",
              output_sha256: "${"f".repeat(64)}",
              evidence_sha256: "${"1".repeat(64)}",
              controller_app_instance_id: "galaxyssi:smoke-phone",
              controller_name: "GalaxySSI Phone",
              controller_platform: "android",
              controller_fingerprint: "${"a".repeat(64)}",
              started_at: Date.now() - 800,
              completed_at: Date.now() - 300,
              duration_ms: 500
            }
          ],
          recent_audit: []
        };
        state.desktopControl = smokeDesktopControl;
        renderDesktopControl();
        document.querySelector('[data-open-panel="gateway"]')?.click();
        await new Promise((resolve) => setTimeout(resolve, 500));
        state.desktopControl = smokeDesktopControl;
        renderDesktopControl();
        for (let attempt = 0; attempt < 30; attempt += 1) {
          if (document.querySelector("#desktopControlAuditList")?.children.length > 0) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        const history = document.querySelector(".gateway-access-history");
        if (history) history.open = true;
        const authorizedApps = document.querySelector(".gateway-authorized-apps");
        if (authorizedApps) authorizedApps.open = true;
        const actionReceipt = document.querySelector(".control-receipt-row");
        if (actionReceipt) actionReceipt.open = true;
        return {
          active: document.querySelector("#gatewayPanel")?.classList.contains("active") || false,
          accessHistory: document.querySelector("#desktopControlAuditList")?.children.length || 0,
          authorizedApps: document.querySelectorAll("#authorizedAppList .authorized-app-row").length,
          revokeActions: document.querySelectorAll("#authorizedAppList [data-revoke-authorization]").length,
          actionReceipts: document.querySelectorAll(".control-receipt-row").length,
          receiptDetails: document.querySelectorAll(".control-receipt-details > div").length,
          pairingExecutor: Boolean(document.querySelector("#pairingDesktopExecutorEnabled")),
          computerPanel: Boolean(document.querySelector("#computerPanel")),
          desktopToolList: Boolean(document.querySelector("#desktopToolList"))
        };
      })()
    `);
    if (
      !gatewayControlState.active
      || gatewayControlState.accessHistory < 1
      || gatewayControlState.authorizedApps !== 2
      || gatewayControlState.revokeActions !== 1
      || gatewayControlState.actionReceipts !== 1
      || gatewayControlState.receiptDetails < 10
      || !gatewayControlState.pairingExecutor
      || gatewayControlState.computerPanel
      || gatewayControlState.desktopToolList
    ) {
      throw new Error(`Mobile Gateway did not consolidate Desktop control access: ${JSON.stringify(gatewayControlState)}`);
    }
    await captureSmokeScreenshot(setupPath);
    const settingsState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        if (typeof openPanel === "function") {
          await openPanel("settings");
        } else {
          document.querySelector('[data-open-panel="settings"]')?.click();
        }
        for (let attempt = 0; attempt < 100; attempt += 1) {
          if (
            document.querySelector("#settingsPanel")?.classList.contains("active")
            && document.querySelector("#drawerTitle")?.textContent?.trim()
            && document.querySelector("#utilityDrawer")?.dataset.panelReady === "settings"
            && document.querySelector("#utilityDrawer")?.dataset.panelLoading === "false"
            && document.querySelector("#cloudModelBadge")?.textContent?.trim()
            && document.querySelector("#evolutionV2Shell")
            && document.querySelector("#saveEvolutionScheduleButton")
          ) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        return {
          active: document.querySelector("#settingsPanel")?.classList.contains("active") || false,
          title: document.querySelector("#drawerTitle")?.textContent || "",
          ready: document.querySelector("#utilityDrawer")?.dataset.panelReady || "",
          provider: document.querySelector("#cloudProvider")?.value || "",
          fields: document.querySelectorAll("#settingsPanel .cloud-settings-surface input, #settingsPanel .cloud-settings-surface select").length,
          pricing: {
            input: Boolean(document.querySelector("#cloudInputPrice")),
            output: Boolean(document.querySelector("#cloudOutputPrice")),
            conversion: priceMicrosAsUsd(2500000)
          },
          save: Boolean(document.querySelector("#saveCloudModelButton")),
          test: Boolean(document.querySelector("#testCloudModelButton")),
          badge: document.querySelector("#cloudModelBadge")?.textContent || "",
          evolution: {
            form: Boolean(document.querySelector(".evolution-create")),
            problem: Boolean(document.querySelector("#evolutionProblem")),
            scope: Boolean(document.querySelector("#evolutionScope")),
            acceptance: Boolean(document.querySelector("#evolutionAcceptance")),
            list: Boolean(document.querySelector("#evolutionTaskList"))
          },
          evolutionV2: {
            shell: Boolean(document.querySelector("#evolutionV2Shell")),
            toolbar: Boolean(document.querySelector("#evolutionV2Shell .evolution-v2-toolbar")),
            tabs: document.querySelectorAll("#evolutionV2Shell .evolution-v2-tab").length,
            scheduler: {
              enabled: document.querySelector("#evolutionSchedulerEnabled")?.checked,
              frequency: document.querySelector("#evolutionSchedulerFrequency")?.value || "",
              mode: document.querySelector("#evolutionSchedulerMode")?.value || "",
              parallelLimit: document.querySelector("#evolutionSchedulerParallelLimit")?.value || "",
              save: Boolean(document.querySelector("#saveEvolutionScheduleButton")),
              runNow: Boolean(document.querySelector("#runEvolutionNowButton"))
            },
            stylesheet: Array.from(document.styleSheets).some(
              (sheet) => String(sheet.href || "").endsWith("/evolution-v2-panel.css")
            )
          },
          languagePolicy: [
            document.querySelector("#responseLanguageSelect")?.value || "",
            document.querySelector("#asrLanguageSelect")?.value || "",
            document.querySelector("#ttsLanguageSelect")?.value || ""
          ],
          agentMemory: {
            refresh: Boolean(document.querySelector("#refreshAgentMemoryButton")),
            summary: document.querySelector("#agentMemorySummary")?.textContent || "",
            sessionBudget: document.querySelector("#agentSessionMemoryBudget")?.textContent || "",
            groups: document.querySelectorAll(".agent-memory-groups section").length,
            note: document.querySelector(".agent-memory-note")?.textContent || ""
          },
          secureValidation: validateCloudModelSettings({
            url: "https://api.example.com/v1/chat/completions",
            model: "test-model",
            api_key: "test-key",
            context_window_tokens: 8192,
            max_output_tokens: 1024
          }),
          insecureValidation: validateCloudModelSettings({
            url: "http://api.example.com/v1/chat/completions",
            model: "test-model",
            api_key: "test-key",
            context_window_tokens: 8192,
            max_output_tokens: 1024
          }),
          budgetValidation: validateCloudModelSettings({
            url: "https://api.example.com/v1/chat/completions",
            model: "test-model",
            api_key: "test-key",
            context_window_tokens: 4096,
            max_output_tokens: 4096
          })
        };
      })()
    `);
    if (!settingsState.active || !settingsState.title.trim() || settingsState.ready !== "settings"
        || settingsState.fields < 9 || !settingsState.save || !settingsState.test
        || !settingsState.pricing.input || !settingsState.pricing.output
        || settingsState.pricing.conversion !== "2.5"
        || !settingsState.badge.trim() || settingsState.secureValidation
        || !settingsState.insecureValidation || !settingsState.budgetValidation
        || Object.values(settingsState.evolution).some((value) => !value)
        || !settingsState.evolutionV2.shell || !settingsState.evolutionV2.toolbar
        || settingsState.evolutionV2.tabs !== 7 || !settingsState.evolutionV2.stylesheet
        || settingsState.evolutionV2.scheduler.enabled !== false
        || settingsState.evolutionV2.scheduler.frequency !== "1"
        || settingsState.evolutionV2.scheduler.mode !== "serial"
        || settingsState.evolutionV2.scheduler.parallelLimit !== "2"
        || !settingsState.evolutionV2.scheduler.save
        || !settingsState.evolutionV2.scheduler.runNow
        || !settingsState.agentMemory.refresh
        || !settingsState.agentMemory.summary.trim()
        || !settingsState.agentMemory.sessionBudget.trim()
        || settingsState.agentMemory.groups !== 3
        || !settingsState.agentMemory.note.trim()
        || settingsState.languagePolicy.some((value) => value !== "auto")) {
      throw new Error(`Settings drawer did not expose cloud API configuration: ${JSON.stringify(settingsState)}`);
    }
    await captureSmokeScreenshot(settingsPath);
    const agentMemoryState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const summary = document.querySelector("#agentMemorySummary");
        const group = summary?.closest(".settings-group");
        const panel = document.querySelector("#settingsPanel");
        const sessionBudget = document.querySelector("#agentSessionMemoryBudget");
        if (!summary || !sessionBudget || !group || !panel) {
          return { visible: false, summary: "", sessionBudget: "" };
        }
        panel.style.scrollBehavior = "auto";
        panel.scrollTop = Math.max(
          0,
          panel.scrollTop
            + group.getBoundingClientRect().top
            - panel.getBoundingClientRect().top
            - 12
        );
        await new Promise((resolve) => setTimeout(resolve, 250));
        const groupRect = group.getBoundingClientRect();
        const panelRect = panel.getBoundingClientRect();
        return {
          visible: groupRect.top >= panelRect.top && groupRect.top < panelRect.bottom,
          summary: summary.textContent || "",
          sessionBudget: sessionBudget.textContent || "",
          groups: group.querySelectorAll(".agent-memory-groups section").length
        };
      })()
    `);
    if (!agentMemoryState.visible || !agentMemoryState.summary.trim()
        || !agentMemoryState.sessionBudget.trim() || agentMemoryState.groups !== 3) {
      throw new Error(`Agent memory telemetry did not render in Settings: ${JSON.stringify(agentMemoryState)}`);
    }
    await captureSmokeScreenshot(agentMemoryPath);
    const evolutionViewportState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        const shell = document.querySelector("#evolutionV2Shell");
        const panel = document.querySelector("#settingsPanel");
        if (!shell || !panel) return { visible: false, scrollTop: 0 };
        panel.style.scrollBehavior = "auto";
        const pinEvolutionPanel = () => {
          panel.scrollTop = Math.max(
            0,
            panel.scrollTop
              + shell.getBoundingClientRect().top
              - panel.getBoundingClientRect().top
              - 12
          );
        };
        clearInterval(window.__galaxyssiSmokeEvolutionPin);
        pinEvolutionPanel();
        window.__galaxyssiSmokeEvolutionPin = setInterval(pinEvolutionPanel, 50);
        await new Promise((resolve) => setTimeout(resolve, 300));
        const shellRect = shell.getBoundingClientRect();
        const panelRect = panel.getBoundingClientRect();
        return {
          visible: shellRect.top >= panelRect.top && shellRect.top < panelRect.bottom,
          scrollTop: panel.scrollTop,
          shellTop: shellRect.top,
          panelTop: panelRect.top,
          panelBottom: panelRect.bottom
        };
      })()
    `);
    if (!evolutionViewportState.visible || evolutionViewportState.scrollTop <= 0) {
      throw new Error(`Evolution V2 panel was not visible for UI smoke: ${JSON.stringify(evolutionViewportState)}`);
    }
    await mainWindow.webContents.executeJavaScript(`
      (() => {
        document.querySelector("#evolutionV2SmokePreview")?.remove();
        const shell = document.querySelector("#evolutionV2Shell");
        const drawer = document.querySelector("#utilityDrawer");
        if (!shell || !drawer) return false;
        const preview = shell.cloneNode(true);
        preview.id = "evolutionV2SmokePreview";
        preview.setAttribute("aria-hidden", "true");
        Object.assign(preview.style, {
          position: "fixed",
          zIndex: "32",
          top: "70px",
          right: "0",
          width: drawer.getBoundingClientRect().width + "px",
          height: "calc(100vh - 70px)",
          margin: "0",
          padding: "15px 16px 28px",
          overflowY: "auto",
          boxSizing: "border-box",
          background: "#f7f8f9",
          transform: "translateZ(0)",
          willChange: "transform"
        });
        document.body.append(preview);
        return new Promise((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve(true)));
        });
      })()
    `);
    mainWindow.showInactive();
    await captureSmokeScreenshot(evolutionV2Path, 1_000);
    await mainWindow.webContents.executeJavaScript(`
      clearInterval(window.__galaxyssiSmokeEvolutionPin);
      delete window.__galaxyssiSmokeEvolutionPin;
      document.querySelector("#evolutionV2SmokePreview")?.remove();
    `);
    const runtimeState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        for (let attempt = 0; attempt < 80; attempt += 1) {
          if (document.querySelectorAll("#runtimeManagerList .runtime-row").length >= 10) break;
          await new Promise((resolve) => setTimeout(resolve, 250));
        }
        const target = document.querySelector("#runtimeManagerList");
        const panel = document.querySelector("#settingsPanel");
        if (target && panel) {
          panel.scrollTop = Math.max(0, target.offsetTop - panel.clientHeight / 3);
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
        return {
          rows: document.querySelectorAll("#runtimeManagerList .runtime-row").length,
          summary: document.querySelector("#runtimeManagerSummary")?.textContent || "",
          statuses: Array.from(document.querySelectorAll("#runtimeManagerList .state-badge")).map((node) => node.textContent || ""),
          scrollTop: panel?.scrollTop || 0
        };
      })()
    `);
    if (runtimeState.rows < 10 || !runtimeState.summary.trim() || runtimeState.statuses.length !== runtimeState.rows || runtimeState.scrollTop < 1) {
      throw new Error(`Desktop runtime manager did not render verified inventory: ${JSON.stringify(runtimeState)}`);
    }
    await captureSmokeScreenshot(runtimePath);
    const gatewayState = await mainWindow.webContents.executeJavaScript(`
      (async () => {
        document.querySelector('[data-open-panel="gateway"]')?.click();
        const pairing = document.querySelector("#pairingDetails");
        if (pairing) pairing.open = true;
        for (let attempt = 0; attempt < 60; attempt += 1) {
          const frame = document.querySelector("#pairingFrame");
          const hasFrame = Boolean(frame?.src && frame.complete);
          if (hasFrame) break;
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
        const frame = document.querySelector("#pairingFrame");
        return {
          active: document.querySelector("#gatewayPanel")?.classList.contains("active") || false,
          frame: Boolean(frame?.src && frame.complete),
          frameWidth: Math.round(frame?.getBoundingClientRect().width || 0),
          clients: document.querySelectorAll("#pairedClientList .paired-client").length,
          summary: document.querySelector("#gatewaySummary")?.textContent || ""
        };
      })()
    `);
    if (!gatewayState.active || !gatewayState.frame || gatewayState.frameWidth > 321 || !gatewayState.summary.trim()) {
      throw new Error(`Gateway drawer did not render: ${JSON.stringify(gatewayState)}`);
    }
    await captureSmokeScreenshot(matrixPath);
    console.log(`[ui-smoke] screenshot: ${overviewPath}`);
    console.log(`[ui-smoke] screenshot: ${peerImagePath}`);
    console.log(`[ui-smoke] screenshot: ${peerImageViewerPath}`);
    console.log(`[ui-smoke] screenshot: ${composerAttachmentsPath}`);
    console.log(`[ui-smoke] screenshot: ${evolutionTimelinePath}`);
    console.log(`[ui-smoke] screenshot: ${languageEnPath}`);
    console.log(`[ui-smoke] screenshot: ${languageZhPath}`);
    console.log(`[ui-smoke] screenshot: ${setupPath}`);
    console.log(`[ui-smoke] screenshot: ${matrixPath}`);
    console.log(`[ui-smoke] screenshot: ${agentsPath}`);
    console.log(`[ui-smoke] screenshot: ${memoryOverviewPath}`);
    console.log(`[ui-smoke] screenshot: ${memoryTimelinePath}`);
    console.log(`[ui-smoke] screenshot: ${memoryGraphPath}`);
    console.log(`[ui-smoke] screenshot: ${memoryEvidencePath}`);
    console.log(`[ui-smoke] screenshot: ${memoryInboxPath}`);
    console.log(`[ui-smoke] screenshot: ${memoryConflictsPath}`);
    console.log(`[ui-smoke] screenshot: ${mcpGovernancePath}`);
    console.log(`[ui-smoke] screenshot: ${mcpImportPath}`);
    console.log(`[ui-smoke] screenshot: ${mcpTaskPath}`);
    console.log(`[ui-smoke] screenshot: ${capabilitiesPath}`);
    console.log(`[ui-smoke] screenshot: ${marketplacePath}`);
    console.log(`[ui-smoke] screenshot: ${settingsPath}`);
    console.log(`[ui-smoke] screenshot: ${evolutionV2Path}`);
    console.log(`[ui-smoke] screenshot: ${runtimePath}`);
    app.exit(0);
  } catch (error) {
    console.error(`[ui-smoke] failed: ${error.stack || error.message || error}`);
    app.exit(1);
  }
}

async function captureSmokeScreenshot(target, initialDelayMs = 200) {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const delayMs = attempt === 0 ? initialDelayMs : 500;
    if (delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
    const image = await mainWindow.webContents.capturePage();
    const png = image.toPNG();
    if (png.length >= 1000) {
      fs.writeFileSync(target, png);
      return;
    }
  }
  throw new Error(`UI smoke screenshot was empty: ${target}`);
}

function findPython() {
  const bundledPython = path.join(RUNTIME_ROOT, "python", "venv", "Scripts", "python.exe");
  const candidates = [
    process.env.GALAXYSSI_PYTHON,
    bundledPython,
    path.join(os.homedir(), "AppData", "Local", "hermes", "hermes-agent", "venv", "Scripts", "python.exe"),
    path.join(os.homedir(), "AppData", "Roaming", "uv", "python", "cpython-3.11-windows-x86_64-none", "python.exe"),
    "python"
  ].filter(Boolean);
  return candidates.find((candidate) => candidate === "python" || fs.existsSync(candidate)) || "python";
}

async function backendStatus() {
  try {
    const response = await fetch(`${BACKEND_ORIGIN}/health`, { method: "GET" });
    const payload = response.ok ? await response.json() : null;
    const identityMatches = payload?.protocol === "GalaxySSI Link Protocol"
      && payload?.connector === "GalaxySSI Desktop";
    return {
      running: response.ok && identityMatches,
      messageBridgeConnected: payload?.message_bridge?.connected === true,
      messageBridgeRunning: payload?.message_bridge?.running === true,
      messageBridgeSupervised: payload?.message_bridge?.supervised === true,
      messageBridgeError: payload?.message_bridge?.last_error || "",
      status: response.status,
      identityMatches,
      origin: BACKEND_ORIGIN,
      pairingUrl: PAIRING_URL,
      backendDir: BACKEND_DIR,
      error: response.ok && !identityMatches
        ? `Port ${BACKEND_PORT} is owned by another service.`
        : undefined
    };
  } catch (error) {
    return {
      running: false,
      messageBridgeConnected: false,
      messageBridgeRunning: false,
      messageBridgeSupervised: false,
      status: 0,
      origin: BACKEND_ORIGIN,
      pairingUrl: PAIRING_URL,
      backendDir: BACKEND_DIR,
      error: error.message
    };
  }
}

function desktopTaskStreamToken() {
  if (cachedDesktopTaskStreamToken) return cachedDesktopTaskStreamToken;
  const runtimeDir = path.join(app.getPath("userData"), "runtime");
  const tokenPath = path.join(runtimeDir, "desktop_task_stream_token");
  fs.mkdirSync(runtimeDir, { recursive: true });
  try {
    const existing = fs.readFileSync(tokenPath, "utf8").trim();
    if (/^[A-Za-z0-9_-]{32,128}$/.test(existing)) {
      cachedDesktopTaskStreamToken = existing;
      return existing;
    }
  } catch {
    // Create the token below.
  }
  cachedDesktopTaskStreamToken = crypto.randomBytes(32).toString("base64url");
  fs.writeFileSync(tokenPath, cachedDesktopTaskStreamToken, { encoding: "utf8", mode: 0o600 });
  return cachedDesktopTaskStreamToken;
}

function reclaimLegacyBackendPort() {
  if (process.platform !== "win32") return Promise.resolve({ reclaimed: false });
  const script = `
$owner = Get-NetTCPConnection -LocalPort ${BACKEND_PORT} -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $owner) { exit 3 }
$process = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $owner.OwningProcess) -ErrorAction SilentlyContinue
$combined = ""
$cursor = $process
for ($depth = 0; $cursor -and $depth -lt 8; $depth++) {
  $combined += " " + [string]$cursor.CommandLine
  if (-not $cursor.ParentProcessId) { break }
  $cursor = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $cursor.ParentProcessId) -ErrorAction SilentlyContinue
}
$combined = $combined.ToLowerInvariant().Replace('\\', '/')
$legacy = $combined.Contains('/hermesworkspace/galaxyssi-desktop-win/') -or $combined.Contains('/hermesworkspace/hermeschat/backend')
if (-not $legacy) { exit 2 }
Stop-Process -Id $owner.OwningProcess -Force -ErrorAction Stop
exit 0
`;
  return new Promise((resolve) => {
    execFile("powershell.exe", ["-NoProfile", "-NonInteractive", "-Command", script], { windowsHide: true, timeout: 5000 }, (error) => {
      resolve({ reclaimed: !error, code: error?.code ?? 0 });
    });
  });
}

async function startBackend() {
  let current = await backendStatus();
  if (current.running) return current;
  if (current.status > 0 && current.identityMatches === false) {
    const reclaim = await reclaimLegacyBackendPort();
    if (!reclaim.reclaimed) {
      return { ...current, portConflict: true };
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
    current = await backendStatus();
  }
  if (!fs.existsSync(path.join(BACKEND_DIR, "main.py"))) {
    return { ...current, error: `Backend not found: ${BACKEND_DIR}` };
  }
  if (backendProcess && !backendProcess.killed) {
    for (let attempt = 0; attempt < 12; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 500));
      const status = await backendStatus();
      if (status.running) return status;
    }
    return backendStatus();
  }

  const python = findPython();
  const galaxyssiDataDir = path.join(app.getPath("userData"), "runtime");
  const signalSidecarRuntime = resolveSignalSidecarRuntime();
  fs.mkdirSync(galaxyssiDataDir, { recursive: true });
  const backendLogPath = path.join(app.getPath("userData"), "backend.log");
  const backendLog = fs.openSync(backendLogPath, "a");
  try {
    backendProcess = spawn(python, ["-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", String(BACKEND_PORT)], {
      cwd: BACKEND_DIR,
      env: {
        ...process.env,
        GALAXYSSI_DATA_DIR: galaxyssiDataDir,
        ...(signalSidecarRuntime ? { GALAXYSSI_LINK_SIDECAR_SCRIPT: signalSidecarRuntime } : {}),
        GALAXYSSI_DESKTOP_TASK_STREAM_TOKEN: desktopTaskStreamToken(),
        PYTHONUNBUFFERED: "1"
      },
      windowsHide: true,
      stdio: ["ignore", backendLog, backendLog],
      detached: false
    });
  } catch (error) {
    return { ...current, error: error.message || String(error) };
  }

  backendProcess.on("exit", () => {
    backendProcess = undefined;
    if (appIsQuitting || backendRestartTimer) return;
    backendRestartTimer = setTimeout(async () => {
      backendRestartTimer = undefined;
      const status = await backendStatus();
      if (!status.running && !appIsQuitting) startBackend();
    }, 1500);
  });

  for (let attempt = 0; attempt < 12; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 500));
    const status = await backendStatus();
    if (status.running) return status;
  }
  return backendStatus();
}

function commandExists(command, args = ["--version"]) {
  return new Promise((resolve) => {
    try {
      execFile(command, args, { windowsHide: true, timeout: 2500 }, (error, stdout, stderr) => {
        resolve({
          ok: !error,
          code: error?.code ?? 0,
          output: String(stdout || stderr || error?.message || "").split(/\r?\n/)[0].trim()
        });
      });
    } catch (error) {
      resolve({
        ok: false,
        code: error?.code ?? 1,
        output: error.message || String(error)
      });
    }
  });
}

function runCommand(command, args = [], timeout = 5000) {
  return new Promise((resolve) => {
    execFile(command, args, { windowsHide: true, timeout }, (error, stdout, stderr) => {
      resolve({
        ok: !error,
        code: error?.code ?? 0,
        output: String(stdout || stderr || "").trim()
      });
    });
  });
}

function loadLocale(language) {
  const normalized = language === "en" ? "en" : "zh-CN";
  const localePath = path.join(APP_ROOT, "src", "renderer", "locales", `${normalized}.json`);
  try {
    return JSON.parse(fs.readFileSync(localePath, "utf8"));
  } catch {
    return {};
  }
}

async function runtimeDiagnostics(refresh = false) {
  const python = findPython();
  const pythonVersion = await runCommand(python, ["--version"], 5000);
  const pythonDeps = pythonVersion.ok
    ? await runCommand(python, ["-c", "import edge_tts, fastapi, json5, uvicorn, paho.mqtt.client, sqlalchemy, pydantic, yaml; print('backend deps ok')"], 8000)
    : { ok: false, code: 1, output: "Python not found" };
  const sidecarRuntime = resolveSignalSidecarRuntime();
  const packaged = Boolean(app.isPackaged);
  let managedRuntime = {
    contract_version: "galaxyssi.desktop-runtime/1.0",
    summary: { ready: 0, partial: 0, missing: 0, total: 0 },
    capabilities: [],
    runtimes: [],
    error: ""
  };
  try {
    const status = await startBackend();
    if (!status.running) {
      managedRuntime.error = status.error || "Desktop backend is unavailable";
    } else {
      const response = await fetch(`${BACKEND_ORIGIN}/api/desktop-runtime?refresh=${refresh ? "true" : "false"}`);
      if (!response.ok) throw new Error(`Runtime inventory returned HTTP ${response.status}`);
      managedRuntime = await response.json();
    }
  } catch (error) {
    managedRuntime.error = error.message || String(error);
  }
  return {
    app: {
      packaged,
      appPath: app.getAppPath(),
      resourcesPath: process.resourcesPath,
      backendOrigin: BACKEND_ORIGIN
    },
    backend: {
      dir: BACKEND_DIR,
      exists: fs.existsSync(path.join(BACKEND_DIR, "main.py")),
      sidecarRuntime,
      sidecarExists: Boolean(sidecarRuntime),
      sidecarCandidates: signalSidecarCandidates()
    },
    python: {
      command: python,
      ok: pythonVersion.ok,
      version: pythonVersion.output,
      depsOk: pythonDeps.ok,
      depsOutput: pythonDeps.output
    },
    managedRuntime,
    installHint: "If Python deps are missing, run install-backend-deps.bat from the portable package or pip install -r backend/requirements.txt."
  };
}

async function detectAgents() {
  try {
    const status = await startBackend();
    if (status.running) {
      const [response, profileResponse] = await Promise.all([
        fetch(`${BACKEND_ORIGIN}/api/agents`),
        fetch(`${BACKEND_ORIGIN}/api/provider-profiles`)
      ]);
      if (response.ok && profileResponse.ok) {
        const [agents, profileCatalog] = await Promise.all([
          response.json(),
          profileResponse.json()
        ]);
        const profiles = new Map(
          (profileCatalog.profiles || []).map((profile) => [profile.resource_id, profile])
        );
        return agents.map((agent) => ({
          id: agent.id,
          name: agent.name,
          kind: agent.kind,
          status: agent.status === "ready" ? "detected" : agent.status === "needs_setup" ? "manual" : agent.status,
          detail: agent.detail || agent.note || "",
          pairing: agent.id === "hermes" ? "GalaxySSI Link QR" : "Connector managed",
          provider_profile: profiles.get(agent.id) || null
        }));
      }
    }
  } catch {
    // Fall back to local command probing below.
  }

  const [hermes, codex, claude, ollama] = await Promise.all([
    commandExists("hermes", ["--version"]),
    commandExists("codex", ["--version"]),
    commandExists("claude", ["--version"]),
    commandExists("ollama", ["--version"])
  ]);

  return [
    {
      id: "hermes",
      name: "Hermes Agent",
      kind: "local-cli",
      status: hermes.ok ? "detected" : "missing",
      detail: hermes.output || "Install Hermes CLI or configure a custom command.",
      pairing: "GalaxySSI Link QR"
    },
    {
      id: "codex",
      name: "Codex Agent",
      kind: "local-cli",
      status: codex.ok ? "detected" : "missing",
      detail: codex.output || "Use the GalaxySSI Desktop Connector to wrap Codex as a contact.",
      pairing: "Connector managed"
    },
    {
      id: "claude",
      name: "Claude Code",
      kind: "local-cli",
      status: claude.ok ? "detected" : "missing",
      detail: claude.output || "Install Claude Code CLI or set a custom command later.",
      pairing: "Connector managed"
    },
    {
      id: "local-llm",
      name: "Local LLM",
      kind: "local-model",
      status: ollama.ok ? "detected" : "manual",
      detail: ollama.output || "Ollama not detected. OpenAI-compatible localhost endpoints can be added next.",
      pairing: "Connector managed"
    },
    {
      id: "custom-agent",
      name: "Custom Agent",
      kind: "custom-cli",
      status: "manual",
      detail: "Set any CLI or MCP wrapper command in GalaxySSI Desktop.",
      pairing: "Connector managed"
    }
  ];
}

async function fetchJson(pathname, options = {}) {
  let lastError;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    try {
      const { headers: extraHeaders = {}, ...requestOptions } = options;
      const response = await fetch(`${BACKEND_ORIGIN}${pathname}`, {
        ...requestOptions,
        headers: {
          "Content-Type": "application/json",
          "X-GalaxySSI-Token": desktopTaskStreamToken(),
          ...extraHeaders
        }
      });
      if (!response.ok) {
        const contentType = response.headers.get("content-type") || "";
        const payload = contentType.includes("application/json")
          ? await response.json().catch(() => ({}))
          : {};
        const detail = payload?.detail || payload || {};
        const error = new Error(
          detail.message || detail.error || `HTTP ${response.status}`
        );
        error.status = response.status;
        error.code = detail.code || "";
        error.details = detail.details || {};
        error.retryable = response.status >= 500;
        throw error;
      }
      const contentType = response.headers.get("content-type") || "";
      if (!contentType.includes("application/json")) {
        throw new Error(`Expected JSON from ${pathname}, got ${contentType || "unknown content-type"}`);
      }
      return response.json();
    } catch (error) {
      lastError = error;
      if (attempt === 7 || error.retryable === false) break;
      await startBackend();
      await new Promise((resolve) => setTimeout(resolve, 350));
    }
  }
  throw lastError;
}

async function synthesizeSpeech(payload = {}) {
  const text = String(payload.text || "").trim();
  if (!text) throw new Error("Speech text is empty");
  if (text.length > 20_000) throw new Error("Speech text exceeds 20000 characters");
  await startBackend();
  const response = await fetch(`${BACKEND_ORIGIN}/api/tts/synthesize`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-GalaxySSI-Token": desktopTaskStreamToken()
    },
    body: JSON.stringify({
      text,
      language: String(payload.language || "zh-CN")
    })
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    const detail = body?.detail || body || {};
    throw new Error(detail.message || detail.error || `Microsoft Edge TTS failed (${response.status})`);
  }
  const declaredSize = Number(response.headers.get("content-length") || 0);
  if (declaredSize <= 0 || declaredSize > 24 * 1024 * 1024) {
    throw new Error("Synthesized speech is empty or too large");
  }
  const audio = Buffer.from(await response.arrayBuffer());
  if (audio.length !== declaredSize) {
    audio.fill(0);
    throw new Error("Synthesized speech transfer is incomplete");
  }
  try {
    return {
      ok: true,
      mimeType: String(response.headers.get("content-type") || "audio/mpeg").split(";", 1)[0],
      voice: String(response.headers.get("x-galaxyssi-tts-voice") || "zh-CN-XiaoxiaoNeural"),
      audioBase64: audio.toString("base64")
    };
  } finally {
    audio.fill(0);
  }
}

async function getAgentConfig() {
  await startBackend();
  return fetchJson("/api/agents/config");
}

async function getAcpRuntime() {
  await startBackend();
  return fetchJson("/api/acp-runtime");
}

async function prewarmAcpAgent(agentId) {
  await startBackend();
  return fetchJson(`/api/acp-runtime/${encodeURIComponent(agentId)}/prewarm`, {
    method: "POST"
  });
}

async function restartAcpAgent(agentId) {
  await startBackend();
  return fetchJson(`/api/acp-runtime/${encodeURIComponent(agentId)}/restart`, {
    method: "POST"
  });
}

async function getAgentDiagnostics() {
  await startBackend();
  return fetchJson("/api/agents/diagnostics");
}

async function getLinkTransportDiagnostics() {
  await startBackend();
  return fetchJson("/api/link/transport-diagnostics");
}

async function getAgentExecutionLog(limit = 50) {
  await startBackend();
  return fetchJson(`/api/agents/execution-log?limit=${encodeURIComponent(limit)}`);
}

async function getAgentPerformanceLab(window = "7d") {
  await startBackend();
  return fetchJson(`/api/agents/performance-lab?window=${encodeURIComponent(window)}`);
}

async function getAgentTasks(limit = 100) {
  await startBackend();
  return fetchJson(`/api/agent/tasks?limit=${encodeURIComponent(limit)}`);
}

async function getAgentMemoryTelemetry() {
  await startBackend();
  return fetchJson("/api/agents/memory-telemetry");
}

async function listCommands(root = "") {
  await startBackend();
  const query = root ? `?root=${encodeURIComponent(root)}` : "";
  return fetchJson(`/api/commands${query}`);
}

async function executeCommand(payload = {}) {
  await startBackend();
  return fetchJson("/api/commands/execute", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function getCommandRuns(limit = 50) {
  await startBackend();
  return fetchJson(`/api/commands/runs?limit=${encodeURIComponent(limit)}`);
}

async function getPairingStatus() {
  await startBackend();
  return fetchJson("/api/pairing/status");
}

async function getPairingQr(grantDesktopExecutor = false) {
  await startBackend();
  const pairing = await fetchJson(
    `/api/pairing/qr?desktop_executor=${grantDesktopExecutor ? "true" : "false"}`
  );
  const imageDataUrl = pairing.image_data_url || "";
  if (!imageDataUrl) throw new Error("Pairing QR image was missing from the Desktop response");
  return {
    imageDataUrl,
    fingerprint: pairing.fingerprint || "",
    pairingAccess: pairing.pairing_access || {},
    desktopDevice: pairing.desktop_device || {},
    expiresAt: Number(pairing.expires_at || 0),
    createdAt: Number(pairing.created_at || 0)
  };
}

async function renamePairedClient(clientRouteId, displayName) {
  await startBackend();
  return fetchJson("/api/pairing/rename", {
    method: "POST",
    body: JSON.stringify({
      client_route_id: String(clientRouteId || ""),
      display_name: String(displayName || "")
    })
  });
}

async function clearPairing(clientRouteId = "") {
  await startBackend();
  const query = clientRouteId ? `?client_route_id=${encodeURIComponent(clientRouteId)}` : "";
  return fetchJson(`/api/pairing/clear${query}`, { method: "POST" });
}

async function runAgentSelfTest(options = {}) {
  await startBackend();
  return fetchJson("/api/agents/self-test", {
    method: "POST",
    body: JSON.stringify({
      include_agent_calls: Boolean(options.includeAgentCalls),
      include_mobile_delivery: options.includeMobileDelivery !== false
    })
  });
}

async function saveAgentConfig(config) {
  await startBackend();
  return fetchJson("/api/agents/config", {
    method: "POST",
    body: JSON.stringify(config)
  });
}

async function testAgent(agentId, prompt) {
  await startBackend();
  return fetchJson(`/api/agents/${encodeURIComponent(agentId)}/test`, {
    method: "POST",
    body: JSON.stringify({ prompt: prompt || "hello" })
  });
}

async function sendMobileTestMessage(contactId, content) {
  await startBackend();
  return fetchJson("/api/mobile/test-message", {
    method: "POST",
    body: JSON.stringify({ contact_id: contactId, content: content || `DESKTOP_TEST_${Date.now()}` })
  });
}

async function listPeerMessages(clientRouteId = "", limit = 500) {
  await startBackend();
  return fetchJson(
    `/api/peer/messages?client_route_id=${encodeURIComponent(clientRouteId)}&limit=${encodeURIComponent(limit)}`
  );
}

async function sendPeerMessage(payload = {}) {
  await startBackend();
  const result = await fetchJson("/api/peer/messages", {
    method: "POST",
    body: JSON.stringify({
      client_route_id: String(payload.clientRouteId || ""),
      content: String(payload.content || ""),
      attachments: Array.isArray(payload.attachments) ? payload.attachments : [],
      attachment_metadata: Array.isArray(payload.attachmentMetadata) ? payload.attachmentMetadata : []
    })
  });
  if (!result.ok) throw new Error(result.message || "Could not send message");
  return result;
}

async function sendPeerVoice(payload = {}) {
  const bytes = Buffer.from(payload.audio || []);
  if (!bytes.length || bytes.length > 24 * 1024 * 1024) {
    throw new Error("Voice recording is empty or too large");
  }
  const directory = path.join(app.getPath("temp"), "GalaxySSI", "peer-voice");
  fs.mkdirSync(directory, { recursive: true });
  const token = `${Date.now()}-${crypto.randomUUID()}`;
  const inputExtension = String(payload.mimeType || "").includes("ogg") ? ".ogg" : ".webm";
  const input = path.join(directory, `${token}${inputExtension}`);
  const output = path.join(directory, `voice-${token}.opus`);
  try {
    fs.writeFileSync(input, bytes, { flag: "wx" });
    bytes.fill(0);
    const encoded = spawnSync("ffmpeg", [
      "-hide_banner", "-loglevel", "error", "-y", "-i", input,
      "-af", "highpass=f=75,afftdn=nf=-25,loudnorm=I=-18:TP=-1:LRA=7,alimiter=limit=0.891",
      "-ar", "48000", "-ac", "1", "-c:a", "libopus", "-b:a", "48k",
      "-vbr", "on", "-application", "voip", output
    ], {
      windowsHide: true,
      encoding: "utf8",
      timeout: 60_000,
      maxBuffer: 1024 * 1024
    });
    if (encoded.status !== 0 || !fs.existsSync(output) || fs.statSync(output).size === 0) {
      throw new Error((encoded.stderr || "Could not encode the Opus voice message").trim());
    }
    return await sendPeerMessage({
      clientRouteId: String(payload.clientRouteId || ""),
      content: "",
      attachments: [output],
      attachmentMetadata: [{
        duration_ms: Math.min(60 * 60 * 1000, Math.max(1_000, Number(payload.durationMillis || 0)))
      }]
    });
  } finally {
    bytes.fill(0);
    try { fs.rmSync(input, { force: true }); } catch {}
    try { fs.rmSync(output, { force: true }); } catch {}
  }
}

async function deletePeerConversation(clientRouteId) {
  await startBackend();
  return fetchJson(`/api/peer/conversations/${encodeURIComponent(clientRouteId)}`, {
    method: "DELETE"
  });
}

async function fetchPeerAttachment(messageId, attachmentIndex) {
  await startBackend();
  const endpoint = `${BACKEND_ORIGIN}/api/peer/messages/${encodeURIComponent(messageId)}/attachments/${encodeURIComponent(attachmentIndex)}`;
  const response = await fetch(endpoint, {
    headers: { "X-GalaxySSI-Token": desktopTaskStreamToken() }
  });
  if (!response.ok) throw new Error(`Peer attachment not found (${response.status})`);
  return response;
}

function peerAttachmentFilename(response, attachmentIndex) {
  const disposition = response.headers.get("content-disposition") || "";
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  let decodedName = encodedName || plainName || `attachment-${attachmentIndex}`;
  try {
    decodedName = decodeURIComponent(decodedName);
  } catch {
    // Keep the server-provided fallback when its name is not URI encoded.
  }
  return path.basename(decodedName);
}

async function loadPeerVoice(messageId, attachmentIndex) {
  const response = await fetchPeerAttachment(messageId, attachmentIndex);
  const mimeType = String(response.headers.get("content-type") || "").split(";", 1)[0].trim().toLowerCase();
  if (!mimeType.startsWith("audio/")) throw new Error("Peer attachment is not an audio message");
  const declaredSize = Number(response.headers.get("content-length") || 0);
  if (declaredSize <= 0 || declaredSize > 24 * 1024 * 1024) {
    throw new Error("Voice recording is empty or too large");
  }
  const arrayBuffer = await response.arrayBuffer();
  const bytes = new Uint8Array(arrayBuffer);
  if (bytes.byteLength !== declaredSize) {
    bytes.fill(0);
    throw new Error("Voice recording transfer is incomplete");
  }
  return { ok: true, ...preparePeerVoicePlayback(arrayBuffer, mimeType) };
}

async function loadPeerImage(messageId, attachmentIndex) {
  const response = await fetchPeerAttachment(messageId, attachmentIndex);
  const mimeType = String(response.headers.get("content-type") || "").split(";", 1)[0].trim().toLowerCase();
  if (!mimeType.startsWith("image/")) throw new Error("Peer attachment is not an image");
  const declaredSize = Number(response.headers.get("content-length") || 0);
  if (declaredSize <= 0 || declaredSize > 32 * 1024 * 1024) {
    throw new Error("Image preview is empty or too large");
  }
  const arrayBuffer = await response.arrayBuffer();
  const bytes = new Uint8Array(arrayBuffer);
  if (bytes.byteLength !== declaredSize) {
    bytes.fill(0);
    throw new Error("Image preview transfer is incomplete");
  }
  return {
    ok: true,
    name: peerAttachmentFilename(response, attachmentIndex),
    mimeType,
    arrayBuffer
  };
}

async function savePeerAttachment(messageId, attachmentIndex) {
  const response = await fetchPeerAttachment(messageId, attachmentIndex);
  const name = peerAttachmentFilename(response, attachmentIndex);
  const extension = path.extname(name).replace(/^\./, "");
  const result = await dialog.showSaveDialog(mainWindow, {
    title: "Save attachment",
    defaultPath: path.join(app.getPath("downloads"), name),
    filters: extension
      ? [{ name: "Attachment", extensions: [extension] }, { name: "All files", extensions: ["*"] }]
      : [{ name: "All files", extensions: ["*"] }]
  });
  if (result.canceled || !result.filePath) {
    await response.body?.cancel();
    return { ok: false, canceled: true };
  }
  if (!response.body) throw new Error("Peer attachment response was empty");
  const target = path.resolve(result.filePath);
  const temporary = path.join(
    path.dirname(target),
    `.${path.basename(target)}.${process.pid}-${crypto.randomBytes(6).toString("hex")}.part`
  );
  try {
    await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(temporary, { flags: "wx" }));
    if (fs.existsSync(target)) fs.rmSync(target, { force: true });
    fs.renameSync(temporary, target);
  } catch (error) {
    fs.rmSync(temporary, { force: true });
    throw error;
  }
  return { ok: true, canceled: false, name: path.basename(target) };
}

async function openPeerAttachment(messageId, attachmentIndex) {
  const response = await fetchPeerAttachment(messageId, attachmentIndex);
  const name = peerAttachmentFilename(response, attachmentIndex);
  const directory = path.join(app.getPath("temp"), "GalaxySSI", "peer-attachments");
  fs.mkdirSync(directory, { recursive: true });
  const target = path.join(directory, `${Date.now()}-${name}`);
  try {
    if (!response.body) throw new Error("Peer attachment response was empty");
    await pipeline(Readable.fromWeb(response.body), fs.createWriteStream(target, { flags: "wx" }));
    schedulePeerAttachmentPreviewCleanup(target);
    const error = await shell.openPath(target);
    if (error) throw new Error(error);
  } catch (error) {
    removePeerAttachmentPreview(target);
    throw error;
  }
  return { ok: true };
}

async function syncMobileStatus() {
  await startBackend();
  return fetchJson("/api/agents/sync-mobile-status", { method: "POST" });
}

async function listDesktopTasks(limit = 100) {
  await startBackend();
  return fetchJson(`/api/desktop/tasks?limit=${encodeURIComponent(limit)}`);
}

async function getDesktopTask(taskId) {
  await startBackend();
  return fetchJson(`/api/desktop/tasks/${encodeURIComponent(taskId)}`);
}

async function getDesktopTaskOutput(taskId, offset = 0, limit = 2) {
  await startBackend();
  return fetchJson(
    `/api/desktop/tasks/${encodeURIComponent(taskId)}/output`
      + `?offset=${encodeURIComponent(offset)}&limit=${encodeURIComponent(limit)}`
  );
}

async function startDesktopTask(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop/tasks", {
    method: "POST",
    body: JSON.stringify({
      prompt: String(payload.prompt || ""),
      agent_id: String(payload.agentId || "auto"),
      conversation_id: String(payload.conversationId || ""),
      attachments: Array.isArray(payload.attachments) ? payload.attachments : [],
      execution_mode: String(payload.executionMode || "auto_complete"),
      task_budget: (
        payload.taskBudget
        && typeof payload.taskBudget === "object"
        && !Array.isArray(payload.taskBudget)
      ) ? payload.taskBudget : {},
      response_language: String(payload.responseLanguage || "")
    })
  });
}

async function cancelDesktopTask(taskId) {
  await startBackend();
  return fetchJson(`/api/desktop/tasks/${encodeURIComponent(taskId)}/cancel`, { method: "POST" });
}

async function controlDesktopTask(taskId, action, payload = {}) {
  await startBackend();
  return fetchJson(`/api/desktop/tasks/${encodeURIComponent(taskId)}/${encodeURIComponent(action)}`, {
    method: "POST",
    body: JSON.stringify({
      reason: String(payload.reason || ""),
      lease_seconds: Number(payload.leaseSeconds || 900)
    })
  });
}

async function retryDesktopTask(taskId) {
  await startBackend();
  return fetchJson(`/api/desktop/tasks/${encodeURIComponent(taskId)}/retry`, { method: "POST" });
}

async function recoverDesktopTask(taskId, action, agentId = "") {
  await startBackend();
  return fetchJson(`/api/desktop/tasks/${encodeURIComponent(taskId)}/recover`, {
    method: "POST",
    body: JSON.stringify({
      action: String(action || ""),
      agent_id: String(agentId || "")
    })
  });
}

async function deleteDesktopConversation(conversationId) {
  await startBackend();
  return fetchJson(`/api/desktop/conversations/${encodeURIComponent(conversationId)}`, { method: "DELETE" });
}

async function listEvolutionTasks(limit = 100) {
  await startBackend();
  return fetchJson(`/api/evolution/tasks?limit=${encodeURIComponent(limit)}`);
}

async function getEvolutionTask(taskId) {
  await startBackend();
  return fetchJson(`/api/evolution/tasks/${encodeURIComponent(taskId)}`);
}

async function createEvolutionTask(payload = {}) {
  await startBackend();
  return fetchJson("/api/evolution/tasks", {
    method: "POST",
    body: JSON.stringify({
      problem: String(payload.problem || ""),
      scope: Array.isArray(payload.scope) ? payload.scope : [],
      acceptance: Array.isArray(payload.acceptance) ? payload.acceptance : [],
      reproduction_steps: Array.isArray(payload.reproductionSteps) ? payload.reproductionSteps : [],
      risk_level: String(payload.riskLevel || "medium"),
      max_attempts: Number(payload.maxAttempts || 3),
      agent_id: String(payload.agentId || "auto"),
      start: payload.start !== false
    })
  });
}

async function cancelEvolutionTask(taskId) {
  await startBackend();
  return fetchJson(`/api/evolution/tasks/${encodeURIComponent(taskId)}/cancel`, { method: "POST" });
}

async function rollbackEvolutionTask(taskId) {
  await startBackend();
  return fetchJson(`/api/evolution/tasks/${encodeURIComponent(taskId)}/rollback`, { method: "POST" });
}

async function publishEvolutionTask(taskId, approvalHash) {
  await startBackend();
  return fetchJson(`/api/evolution/tasks/${encodeURIComponent(taskId)}/publish`, {
    method: "POST",
    body: JSON.stringify({ approval_hash: String(approvalHash || ""), base_branch: "main" })
  });
}

async function evolutionV2Request(method = "GET", pathname = "/health", body = null) {
  await startBackend();
  const safeMethod = String(method || "GET").toUpperCase();
  if (!["GET", "POST"].includes(safeMethod)) {
    throw new Error("Invalid evolution V2 API method");
  }
  const cleanPath = String(pathname || "/health");
  if (
    cleanPath.length > 2_048
    || !cleanPath.startsWith("/")
    || cleanPath.includes("..")
    || cleanPath.includes("#")
    || /[\u0000-\u001f\u007f]/.test(cleanPath)
  ) {
    throw new Error("Invalid evolution V2 API path");
  }
  const parsed = new URL(cleanPath, "http://galaxyssi.local");
  const identifier = "[A-Za-z0-9._-]{1,128}";
  const allowedPath = safeMethod === "GET"
    ? new RegExp(
      `^/(?:health|preflight|policy|tasks/${identifier}/metadata|`
      + `research/runs(?:/${identifier})?|roadmaps(?:/${identifier})?|proposals|`
      + "issues|campaigns|audit(?:/verify)?|scheduler|github/checks)$"
    )
    : new RegExp(
      `^/(?:research/runs|roadmaps|proposals/${identifier}/materialize|`
      + `issues/(?:scan|ingest)|campaigns(?:/${identifier}/tick)?|`
      + "scheduler/(?:config|tick))$"
    );
  if (!allowedPath.test(parsed.pathname)) {
    throw new Error("Evolution V2 API route is not allowed");
  }
  const options = { method: safeMethod };
  if (body !== null && body !== undefined && safeMethod !== "GET") {
    options.body = JSON.stringify(body);
  }
  return fetchJson(`/api/evolution/v2${parsed.pathname}${parsed.search}`, options);
}

async function listProactiveTasks(limit = 200) {
  await startBackend();
  return fetchJson(`/api/proactive/tasks?limit=${encodeURIComponent(limit)}`);
}

async function createProactiveTask(payload = {}) {
  await startBackend();
  return fetchJson("/api/proactive/tasks", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function updateProactiveTask(taskId, payload = {}) {
  await startBackend();
  return fetchJson(`/api/proactive/tasks/${encodeURIComponent(taskId)}`, {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function deleteProactiveTask(taskId) {
  await startBackend();
  return fetchJson(`/api/proactive/tasks/${encodeURIComponent(taskId)}`, {
    method: "DELETE"
  });
}

async function triggerProactiveTask(taskId) {
  await startBackend();
  return fetchJson(`/api/proactive/tasks/${encodeURIComponent(taskId)}/trigger`, {
    method: "POST",
    body: JSON.stringify({ cause: { type: "manual", source: "desktop_ui" } })
  });
}

async function listProactiveRuns(taskId = "", limit = 100) {
  await startBackend();
  const query = new URLSearchParams({
    task_id: String(taskId || ""),
    limit: String(limit)
  });
  return fetchJson(`/api/proactive/runs?${query.toString()}`);
}

async function cancelProactiveRun(runId) {
  await startBackend();
  return fetchJson(`/api/proactive/runs/${encodeURIComponent(runId)}/cancel`, {
    method: "POST"
  });
}

async function getDesktopControl() {
  await startBackend();
  return fetchJson("/api/desktop-control");
}

async function revokeDesktopAuthorization(authorizationId) {
  await startBackend();
  return fetchJson(
    `/api/desktop-control/authorizations/${encodeURIComponent(authorizationId)}/revoke`,
    { method: "POST" }
  );
}

async function getDesktopMemory(query = "", limit = 100, status = "active") {
  await startBackend();
  return fetchJson(
    `/api/desktop-memory?query=${encodeURIComponent(query || "")}`
    + `&limit=${encodeURIComponent(limit || 100)}`
    + `&status=${encodeURIComponent(status || "active")}`
  );
}

async function getDesktopMemoryInbox(limit = 100) {
  await startBackend();
  return fetchJson(`/api/desktop-memory/inbox?limit=${encodeURIComponent(limit || 100)}`);
}

async function getDesktopMemoryEvolution(limit = 100) {
  await startBackend();
  return fetchJson(`/api/desktop-memory/evolution?limit=${encodeURIComponent(limit || 100)}`);
}

async function runDesktopMemoryCritic() {
  await startBackend();
  return fetchJson("/api/desktop-memory/critic/run", { method: "POST" });
}

async function getDesktopMemoryVisualization(limit = 100) {
  await startBackend();
  return fetchJson(
    `/api/desktop-memory/visualization?limit=${encodeURIComponent(limit || 100)}`
  );
}

async function proposeDesktopMemory(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop-memory/inbox", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function rememberDesktopMemory(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop-memory", { method: "POST", body: JSON.stringify(payload) });
}

async function forgetDesktopMemory(memoryId) {
  await startBackend();
  return fetchJson(`/api/desktop-memory/${encodeURIComponent(memoryId)}`, { method: "DELETE" });
}

async function reviewDesktopMemoryCandidate(candidateId, action) {
  await startBackend();
  const normalizedAction = action === "approve" ? "approve" : "reject";
  return fetchJson(
    `/api/desktop-memory/inbox/${encodeURIComponent(candidateId)}/${normalizedAction}`,
    { method: "POST" }
  );
}

async function getDesktopSkills() {
  await startBackend();
  return fetchJson("/api/desktop-skills");
}

async function getToolMarketplace() {
  await startBackend();
  return fetchJson("/api/tool-marketplace");
}

async function installToolMarketplaceItem(itemId, configuration = {}, approvedPermissions = []) {
  await startBackend();
  return fetchJson(`/api/tool-marketplace/${encodeURIComponent(itemId)}/install`, {
    method: "POST",
    body: JSON.stringify({
      configuration,
      approved_permissions: Array.isArray(approvedPermissions) ? approvedPermissions : []
    })
  });
}

async function uninstallToolMarketplaceItem(itemId) {
  await startBackend();
  return fetchJson(`/api/tool-marketplace/${encodeURIComponent(itemId)}`, {
    method: "DELETE"
  });
}

async function revokeToolMarketplaceItem(itemId) {
  await startBackend();
  return fetchJson(`/api/tool-marketplace/${encodeURIComponent(itemId)}/revoke`, {
    method: "POST"
  });
}

async function rollbackToolMarketplaceItem(itemId) {
  await startBackend();
  return fetchJson(`/api/tool-marketplace/${encodeURIComponent(itemId)}/rollback`, {
    method: "POST"
  });
}

async function saveDesktopSkill(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop-skills", { method: "POST", body: JSON.stringify(payload) });
}

async function setDesktopSkillEnabled(skillId, enabled) {
  await startBackend();
  return fetchJson(`/api/desktop-skills/${encodeURIComponent(skillId)}/enabled`, {
    method: "POST",
    body: JSON.stringify({ enabled: Boolean(enabled) })
  });
}

async function deleteDesktopSkill(skillId) {
  await startBackend();
  return fetchJson(`/api/desktop-skills/${encodeURIComponent(skillId)}`, { method: "DELETE" });
}

async function getDesktopMcp() {
  await startBackend();
  return fetchJson("/api/desktop-mcp");
}

async function saveDesktopMcp(payload = {}) {
    await startBackend();
    return fetchJson("/api/desktop-mcp", { method: "POST", body: JSON.stringify(payload) });
}

async function getDesktopMcpImportSources() {
  await startBackend();
  return fetchJson("/api/desktop-mcp-import/sources");
}

function readMcpConfigFile(filePath) {
  const resolved = path.resolve(String(filePath || ""));
  const stat = fs.statSync(resolved);
  if (!stat.isFile() || stat.size <= 0 || stat.size > 1_048_576) {
    throw new Error("MCP configuration must be a file smaller than 1 MiB.");
  }
  return {
    fileName: path.basename(resolved),
    baseDirectory: path.dirname(resolved),
    content: fs.readFileSync(resolved, "utf8").replace(/^\uFEFF/, "")
  };
}

async function chooseMcpConfig() {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Import MCP configuration",
    buttonLabel: "Preview",
    properties: ["openFile"],
    filters: [
      { name: "MCP configuration", extensions: ["json", "json5", "toml", "yaml", "yml"] },
      { name: "All files", extensions: ["*"] }
    ]
  });
  if (result.canceled || !result.filePaths[0]) return null;
  return readMcpConfigFile(result.filePaths[0]);
}

async function readDiscoveredMcpConfig(filePath) {
  const discovered = await getDesktopMcpImportSources();
  const requested = path.resolve(String(filePath || "")).toLowerCase();
  const allowed = (discovered.sources || []).some(
    (source) => path.resolve(String(source.path || "")).toLowerCase() === requested
  );
  if (!allowed) throw new Error("MCP configuration source is not available.");
  return readMcpConfigFile(filePath);
}

async function previewDesktopMcpImport(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop-mcp-import/preview", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function commitDesktopMcpImport(payload = {}) {
  await startBackend();
  return fetchJson("/api/desktop-mcp-import/commit", {
    method: "POST",
    body: JSON.stringify(payload)
  });
}

async function probeDesktopMcp(connectionId) {
  await startBackend();
  return fetchJson(`/api/desktop-mcp/${encodeURIComponent(connectionId)}/probe`, { method: "POST" });
}

async function deleteDesktopMcp(connectionId) {
  await startBackend();
  return fetchJson(`/api/desktop-mcp/${encodeURIComponent(connectionId)}`, { method: "DELETE" });
}

async function chooseAttachments() {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Add files to GalaxySSI",
    buttonLabel: "Add",
    properties: ["openFile", "multiSelections"]
  });
  return result.canceled ? [] : result.filePaths;
}

function composerAttachmentMimeType(fileName, declaredType = "") {
  const normalized = String(declaredType || "").trim().toLowerCase();
  if (/^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(normalized)) return normalized;
  return COMPOSER_MIME_OVERRIDES[path.extname(String(fileName || "")).toLowerCase()]
    || "application/octet-stream";
}

function safeComposerAttachmentName(value, mimeType = "") {
  const extension = String(mimeType || "").toLowerCase() === "image/png" ? ".png" : "";
  const fallback = `pasted-attachment${extension}`;
  return (path.basename(String(value || fallback)) || fallback)
    .replace(/[<>:"/\\|?*\x00-\x1f]/g, "_")
    .replace(/[. ]+$/g, "")
    .slice(0, 180) || fallback;
}

function ipcAttachmentBytes(value) {
  if (value instanceof ArrayBuffer) return Buffer.from(value);
  if (ArrayBuffer.isView(value)) return Buffer.from(value.buffer, value.byteOffset, value.byteLength);
  if (Array.isArray(value?.data)) return Buffer.from(value.data);
  return Buffer.alloc(0);
}

async function describeComposerAttachments(filePaths = []) {
  return Promise.all((Array.isArray(filePaths) ? filePaths : []).slice(0, 12).map(async (filePath) => {
    const resolved = path.resolve(String(filePath || ""));
    const stat = fs.statSync(resolved);
    if (!stat.isFile() || stat.size <= 0) throw new Error("Attachment is unavailable");
    const name = path.basename(resolved);
    const mimeType = composerAttachmentMimeType(name);
    let previewDataUrl = "";
    if (mimeType.startsWith("image/")) {
      previewDataUrl = await nativeImage.createThumbnailFromPath(resolved, COMPOSER_PREVIEW_SIZE)
        .then((thumbnail) => thumbnail.isEmpty() ? "" : thumbnail.toDataURL())
        .catch(() => "");
    }
    return { path: resolved, name, mimeType, sizeBytes: stat.size, previewDataUrl };
  }));
}

async function stageClipboardAttachments(items = []) {
  const candidates = (Array.isArray(items) ? items : []).slice(0, 12);
  let batchBytes = 0;
  const staged = [];
  try {
    for (const item of candidates) {
      const bytes = ipcAttachmentBytes(item?.bytes);
      batchBytes += bytes.length;
      if (bytes.length <= 0 || bytes.length > MAX_CLIPBOARD_ATTACHMENT_BYTES || batchBytes > MAX_CLIPBOARD_BATCH_BYTES) {
        throw new Error("Pasted attachment is empty or too large");
      }
      const mimeType = composerAttachmentMimeType(item?.name, item?.mimeType);
      const name = safeComposerAttachmentName(item?.name, mimeType);
      const directory = path.join(
        app.getPath("temp"),
        "GalaxySSI",
        "composer-attachments",
        crypto.randomUUID()
      );
      fs.mkdirSync(directory, { recursive: true });
      const target = path.join(directory, name);
      fs.writeFileSync(target, bytes, { flag: "wx" });
      staged.push(target);
    }
    return describeComposerAttachments(staged);
  } catch (error) {
    releaseComposerAttachments(staged);
    throw error;
  }
}

function releaseComposerAttachments(filePaths = []) {
  const root = path.resolve(app.getPath("temp"), "GalaxySSI", "composer-attachments");
  for (const filePath of Array.isArray(filePaths) ? filePaths : []) {
    const target = path.resolve(String(filePath || ""));
    if (!target.startsWith(`${root}${path.sep}`)) continue;
    try { fs.rmSync(target, { force: true }); } catch {}
    const parent = path.dirname(target);
    if (path.dirname(parent) === root) {
      try { fs.rmSync(parent, { recursive: true, force: true }); } catch {}
    }
  }
  return { ok: true };
}

function resolveTaskPath(taskId, relativePath = "") {
  const safeTaskId = String(taskId || "");
  if (!/^[A-Za-z0-9._-]{1,96}$/.test(safeTaskId)) {
    throw new Error("Invalid task id");
  }
  const tasksRoot = path.resolve(os.homedir(), "GalaxySSI_Workspace", "tasks");
  const taskRoot = path.resolve(tasksRoot, safeTaskId);
  const target = path.resolve(taskRoot, String(relativePath || "."));
  if (target !== taskRoot && !target.startsWith(`${taskRoot}${path.sep}`)) {
    throw new Error("Task artifact escaped its workspace");
  }
  return target;
}

async function openTaskArtifact(taskId, relativePath = "") {
  const target = resolveTaskPath(taskId, relativePath);
  if (!fs.existsSync(target)) throw new Error("Task artifact not found");
  const error = await shell.openPath(target);
  if (error) throw new Error(error);
  return { ok: true, path: target };
}

async function revealTaskWorkspace(taskId) {
  const target = resolveTaskPath(taskId);
  if (!fs.existsSync(target)) throw new Error("Task workspace not found");
  shell.showItemInFolder(target);
  return { ok: true, path: target };
}

ipcMain.handle("app:version", () => app.getVersion());
ipcMain.handle("tts:synthesize", (_event, payload) => synthesizeSpeech(payload));
ipcMain.handle("backend:start", startBackend);
ipcMain.handle("backend:status", backendStatus);
ipcMain.handle("runtime:diagnostics", (_event, refresh = false) => runtimeDiagnostics(Boolean(refresh)));
ipcMain.handle("pairing:status", getPairingStatus);
ipcMain.handle("blob:settings:get", (_event, route) =>
  fetchJson(`/api/blob/settings/${encodeURIComponent(String(route || ""))}`));
ipcMain.handle("blob:settings:save", (_event, route, payload) =>
  fetchJson(`/api/blob/settings/${encodeURIComponent(String(route || ""))}`, {
    method: "PUT", body: JSON.stringify(payload)
  }));
ipcMain.handle("pairing:qr", (_event, grantDesktopExecutor = false) =>
  getPairingQr(Boolean(grantDesktopExecutor)));
ipcMain.handle("pairing:clear", (_event, clientRouteId = "") => clearPairing(clientRouteId));
ipcMain.handle("pairing:rename", (_event, clientRouteId, displayName) =>
  renamePairedClient(clientRouteId, displayName));
ipcMain.handle("agents:detect", detectAgents);
ipcMain.handle("agents:diagnostics", getAgentDiagnostics);
ipcMain.handle("link:transport-diagnostics", getLinkTransportDiagnostics);
ipcMain.handle("agents:execution-log", (_event, limit) => getAgentExecutionLog(limit));
ipcMain.handle("agents:performance-lab", (_event, window) => getAgentPerformanceLab(window));
ipcMain.handle("agents:tasks", (_event, limit) => getAgentTasks(limit));
ipcMain.handle("agents:memory-telemetry", getAgentMemoryTelemetry);
ipcMain.handle("commands:list", (_event, root = "") => listCommands(root));
ipcMain.handle("commands:execute", (_event, payload = {}) => executeCommand(payload));
ipcMain.handle("commands:runs", (_event, limit) => getCommandRuns(limit));
ipcMain.handle("agents:self-test", (_event, options) => runAgentSelfTest(options));
ipcMain.handle("agents:config:get", getAgentConfig);
ipcMain.handle("agents:config:save", (_event, config) => saveAgentConfig(config));
ipcMain.handle("acp-runtime:get", getAcpRuntime);
ipcMain.handle("acp-runtime:prewarm", (_event, agentId) => prewarmAcpAgent(agentId));
ipcMain.handle("acp-runtime:restart", (_event, agentId) => restartAcpAgent(agentId));
ipcMain.handle("agents:test", (_event, agentId, prompt) => testAgent(agentId, prompt));
ipcMain.handle("mobile:test-message", (_event, contactId, content) => sendMobileTestMessage(contactId, content));
ipcMain.handle("mobile:sync-status", syncMobileStatus);
ipcMain.handle("peer-messages:list", (_event, clientRouteId, limit) =>
  listPeerMessages(clientRouteId, limit));
ipcMain.handle("peer-messages:send", (_event, payload) => sendPeerMessage(payload));
ipcMain.handle("peer-voice:send", (_event, payload) => sendPeerVoice(payload));
ipcMain.handle("peer-voice:load", (_event, messageId, attachmentIndex) =>
  loadPeerVoice(messageId, attachmentIndex));
ipcMain.handle("peer-images:load", (_event, messageId, attachmentIndex) =>
  loadPeerImage(messageId, attachmentIndex));
ipcMain.handle("peer-conversations:delete", (_event, clientRouteId) =>
  deletePeerConversation(clientRouteId));
ipcMain.handle("peer-attachments:open", (_event, messageId, attachmentIndex) =>
  openPeerAttachment(messageId, attachmentIndex));
ipcMain.handle("peer-attachments:save", (_event, messageId, attachmentIndex) =>
  savePeerAttachment(messageId, attachmentIndex));
ipcMain.handle("desktop-tasks:list", (_event, limit) => listDesktopTasks(limit));
ipcMain.handle("desktop-tasks:get", (_event, taskId) => getDesktopTask(taskId));
ipcMain.handle("desktop-tasks:output", (_event, taskId, offset, limit) =>
  getDesktopTaskOutput(taskId, offset, limit));
ipcMain.handle("desktop-tasks:stream-config", () => ({
  url: DESKTOP_TASK_STREAM_URL,
  protocols: ["galaxyssi-task-stream", desktopTaskStreamToken()]
}));
ipcMain.handle("desktop-tasks:start", (_event, payload) => startDesktopTask(payload));
ipcMain.handle("desktop-tasks:cancel", (_event, taskId) => cancelDesktopTask(taskId));
ipcMain.handle("desktop-tasks:pause", (_event, taskId, payload = {}) =>
  controlDesktopTask(taskId, "pause", payload));
ipcMain.handle("desktop-tasks:takeover", (_event, taskId, payload = {}) =>
  controlDesktopTask(taskId, "takeover", payload));
ipcMain.handle("desktop-tasks:continue", (_event, taskId, payload = {}) =>
  controlDesktopTask(taskId, "continue", payload));
ipcMain.handle("desktop-tasks:retry", (_event, taskId) => retryDesktopTask(taskId));
ipcMain.handle("desktop-tasks:recover", (_event, taskId, action, agentId = "") =>
  recoverDesktopTask(taskId, action, agentId));
ipcMain.handle("desktop-conversations:delete", (_event, conversationId) => deleteDesktopConversation(conversationId));
ipcMain.handle("evolution-tasks:list", (_event, limit) => listEvolutionTasks(limit));
ipcMain.handle("evolution-tasks:get", (_event, taskId) => getEvolutionTask(taskId));
ipcMain.handle("evolution-tasks:create", (_event, payload) => createEvolutionTask(payload));
ipcMain.handle("evolution-tasks:cancel", (_event, taskId) => cancelEvolutionTask(taskId));
ipcMain.handle("evolution-tasks:rollback", (_event, taskId) => rollbackEvolutionTask(taskId));
ipcMain.handle("evolution-tasks:publish", (_event, taskId, approvalHash) =>
  publishEvolutionTask(taskId, approvalHash));
ipcMain.handle("evolution-v2:request", (_event, method, pathname, body) =>
  evolutionV2Request(method, pathname, body));
ipcMain.handle("proactive-tasks:list", (_event, limit) => listProactiveTasks(limit));
ipcMain.handle("proactive-tasks:create", (_event, payload) => createProactiveTask(payload));
ipcMain.handle("proactive-tasks:update", (_event, taskId, payload) => updateProactiveTask(taskId, payload));
ipcMain.handle("proactive-tasks:delete", (_event, taskId) => deleteProactiveTask(taskId));
ipcMain.handle("proactive-tasks:trigger", (_event, taskId) => triggerProactiveTask(taskId));
ipcMain.handle("proactive-runs:list", (_event, taskId, limit) => listProactiveRuns(taskId, limit));
ipcMain.handle("proactive-runs:cancel", (_event, runId) => cancelProactiveRun(runId));
ipcMain.handle("desktop-control:get", getDesktopControl);
ipcMain.handle("desktop-control:revoke", (_event, authorizationId) =>
  revokeDesktopAuthorization(authorizationId));
ipcMain.handle("desktop-memory:list", (_event, query, limit, status) => getDesktopMemory(query, limit, status));
ipcMain.handle("desktop-memory:inbox", (_event, limit) => getDesktopMemoryInbox(limit));
ipcMain.handle("desktop-memory:evolution", (_event, limit) => getDesktopMemoryEvolution(limit));
ipcMain.handle("desktop-memory:critic-run", runDesktopMemoryCritic);
ipcMain.handle("desktop-memory:visualization", (_event, limit) =>
  getDesktopMemoryVisualization(limit));
ipcMain.handle("desktop-memory:propose", (_event, payload) => proposeDesktopMemory(payload));
ipcMain.handle("desktop-memory:remember", (_event, payload) => rememberDesktopMemory(payload));
ipcMain.handle("desktop-memory:forget", (_event, memoryId) => forgetDesktopMemory(memoryId));
ipcMain.handle("desktop-memory:review", (_event, candidateId, action) =>
  reviewDesktopMemoryCandidate(candidateId, action));
ipcMain.handle("tool-marketplace:list", getToolMarketplace);
ipcMain.handle("tool-marketplace:install", (_event, itemId, configuration, approvedPermissions) =>
  installToolMarketplaceItem(itemId, configuration, approvedPermissions));
ipcMain.handle("tool-marketplace:uninstall", (_event, itemId) =>
  uninstallToolMarketplaceItem(itemId));
ipcMain.handle("tool-marketplace:revoke", (_event, itemId) =>
  revokeToolMarketplaceItem(itemId));
ipcMain.handle("tool-marketplace:rollback", (_event, itemId) =>
  rollbackToolMarketplaceItem(itemId));
ipcMain.handle("desktop-skills:list", getDesktopSkills);
ipcMain.handle("desktop-skills:save", (_event, payload) => saveDesktopSkill(payload));
ipcMain.handle("desktop-skills:enabled", (_event, skillId, enabled) => setDesktopSkillEnabled(skillId, enabled));
ipcMain.handle("desktop-skills:delete", (_event, skillId) => deleteDesktopSkill(skillId));
ipcMain.handle("desktop-mcp:list", getDesktopMcp);
ipcMain.handle("desktop-mcp:save", (_event, payload) => saveDesktopMcp(payload));
ipcMain.handle("desktop-mcp-import:sources", getDesktopMcpImportSources);
ipcMain.handle("desktop-mcp-import:choose", chooseMcpConfig);
ipcMain.handle("desktop-mcp-import:read", (_event, filePath) => readDiscoveredMcpConfig(filePath));
ipcMain.handle("desktop-mcp-import:preview", (_event, payload) => previewDesktopMcpImport(payload));
ipcMain.handle("desktop-mcp-import:commit", (_event, payload) => commitDesktopMcpImport(payload));
ipcMain.handle("desktop-mcp:probe", (_event, connectionId) => probeDesktopMcp(connectionId));
ipcMain.handle("desktop-mcp:delete", (_event, connectionId) => deleteDesktopMcp(connectionId));
ipcMain.handle("files:choose", chooseAttachments);
ipcMain.handle("files:describe", (_event, filePaths) => describeComposerAttachments(filePaths));
ipcMain.handle("files:stage-clipboard", (_event, items) => stageClipboardAttachments(items));
ipcMain.handle("files:release-staged", (_event, filePaths) => releaseComposerAttachments(filePaths));
ipcMain.handle("task-artifact:open", (_event, taskId, relativePath) => openTaskArtifact(taskId, relativePath));
ipcMain.handle("task-workspace:reveal", (_event, taskId) => revealTaskWorkspace(taskId));
ipcMain.handle("i18n:load", (_event, language) => loadLocale(language));
ipcMain.handle("pairing:url", () => PAIRING_URL);
ipcMain.handle("open:external", (_event, url) => shell.openExternal(url));
ipcMain.handle("clipboard:write", (_event, text) => {
  clipboard.writeText(String(text || ""));
  return { ok: true };
});

app.whenReady().then(async () => {
  if (!hasSingleInstanceLock) return;
  powerMonitor.on("lock-screen", () => {
    clearRendererSensitiveState();
    clearPeerRuntimeFiles();
  });
  powerMonitor.on("suspend", () => {
    clearRendererSensitiveState();
    clearPeerRuntimeFiles();
  });
  powerMonitor.on("unlock-screen", () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("sensitive-state:resume");
    }
  });
  clearPeerRuntimeFiles();
  createWindow();
  startBackend();
});

app.on("second-instance", () => {
  if (!mainWindow) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

app.on("before-quit", () => {
  appIsQuitting = true;
  clearPeerRuntimeFiles();
  if (backendRestartTimer) {
    clearTimeout(backendRestartTimer);
    backendRestartTimer = undefined;
  }
  if (backendProcess && !backendProcess.killed) {
    if (process.platform === "win32" && backendProcess.pid) {
      spawnSync("taskkill", ["/pid", String(backendProcess.pid), "/T", "/F"], {
        windowsHide: true,
        stdio: "ignore"
      });
    } else {
      backendProcess.kill();
    }
  }
});
