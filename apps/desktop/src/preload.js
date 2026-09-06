const { contextBridge, ipcRenderer, webUtils } = require("electron");

contextBridge.exposeInMainWorld("galaxyssi", {
  getAppVersion: () => ipcRenderer.invoke("app:version"),
  synthesizeSpeech: (payload) => ipcRenderer.invoke("tts:synthesize", payload),
  startBackend: () => ipcRenderer.invoke("backend:start"),
  backendStatus: () => ipcRenderer.invoke("backend:status"),
  getRuntimeDiagnostics: (refresh = false) => ipcRenderer.invoke("runtime:diagnostics", refresh),
  getPairingStatus: () => ipcRenderer.invoke("pairing:status"),
  getBlobSettings: (route) => ipcRenderer.invoke("blob:settings:get", route),
  saveBlobSettings: (route, payload) => ipcRenderer.invoke("blob:settings:save", route, payload),
  getPairingQr: (grantDesktopExecutor = false) =>
    ipcRenderer.invoke("pairing:qr", Boolean(grantDesktopExecutor)),
  clearPairing: (clientRouteId = "") => ipcRenderer.invoke("pairing:clear", clientRouteId),
  renamePairedClient: (clientRouteId, displayName) =>
    ipcRenderer.invoke("pairing:rename", clientRouteId, displayName),
  detectAgents: () => ipcRenderer.invoke("agents:detect"),
  getAgentDiagnostics: () => ipcRenderer.invoke("agents:diagnostics"),
  getLinkTransportDiagnostics: () => ipcRenderer.invoke("link:transport-diagnostics"),
  getAgentExecutionLog: (limit) => ipcRenderer.invoke("agents:execution-log", limit),
  getAgentPerformanceLab: (window = "7d") =>
    ipcRenderer.invoke("agents:performance-lab", window),
  getAgentTasks: (limit) => ipcRenderer.invoke("agents:tasks", limit),
  getAgentMemoryTelemetry: () => ipcRenderer.invoke("agents:memory-telemetry"),
  listCommands: (root) => ipcRenderer.invoke("commands:list", root),
  executeCommand: (payload) => ipcRenderer.invoke("commands:execute", payload),
  getCommandRuns: (limit) => ipcRenderer.invoke("commands:runs", limit),
  runAgentSelfTest: (options) => ipcRenderer.invoke("agents:self-test", options),
  getAgentConfig: () => ipcRenderer.invoke("agents:config:get"),
  saveAgentConfig: (config) => ipcRenderer.invoke("agents:config:save", config),
  getAcpRuntime: () => ipcRenderer.invoke("acp-runtime:get"),
  prewarmAcpAgent: (agentId) => ipcRenderer.invoke("acp-runtime:prewarm", agentId),
  restartAcpAgent: (agentId) => ipcRenderer.invoke("acp-runtime:restart", agentId),
  testAgent: (agentId, prompt) => ipcRenderer.invoke("agents:test", agentId, prompt),
  sendMobileTest: (contactId, content) => ipcRenderer.invoke("mobile:test-message", contactId, content),
  syncMobileStatus: () => ipcRenderer.invoke("mobile:sync-status"),
  listPeerMessages: (clientRouteId = "", limit = 500) =>
    ipcRenderer.invoke("peer-messages:list", clientRouteId, limit),
  sendPeerMessage: (payload) => ipcRenderer.invoke("peer-messages:send", payload),
  sendPeerVoice: (payload) => ipcRenderer.invoke("peer-voice:send", payload),
  deletePeerConversation: (clientRouteId) => ipcRenderer.invoke("peer-conversations:delete", clientRouteId),
  openPeerAttachment: (messageId, attachmentIndex) =>
    ipcRenderer.invoke("peer-attachments:open", messageId, attachmentIndex),
  loadPeerVoice: (messageId, attachmentIndex) =>
    ipcRenderer.invoke("peer-voice:load", messageId, attachmentIndex),
  loadPeerImage: (messageId, attachmentIndex) =>
    ipcRenderer.invoke("peer-images:load", messageId, attachmentIndex),
  savePeerAttachment: (messageId, attachmentIndex) =>
    ipcRenderer.invoke("peer-attachments:save", messageId, attachmentIndex),
  onSensitiveStateClear: (callback) => {
    const listener = () => callback();
    ipcRenderer.on("sensitive-state:clear", listener);
    return () => ipcRenderer.removeListener("sensitive-state:clear", listener);
  },
  onSensitiveStateResume: (callback) => {
    const listener = () => callback();
    ipcRenderer.on("sensitive-state:resume", listener);
    return () => ipcRenderer.removeListener("sensitive-state:resume", listener);
  },
  listDesktopTasks: (limit) => ipcRenderer.invoke("desktop-tasks:list", limit),
  getDesktopTask: (taskId) => ipcRenderer.invoke("desktop-tasks:get", taskId),
  getDesktopTaskOutput: (taskId, offset = 0, limit = 2) =>
    ipcRenderer.invoke("desktop-tasks:output", taskId, offset, limit),
  desktopTaskStreamConfig: () => ipcRenderer.invoke("desktop-tasks:stream-config"),
  startDesktopTask: (payload) => ipcRenderer.invoke("desktop-tasks:start", payload),
  cancelDesktopTask: (taskId) => ipcRenderer.invoke("desktop-tasks:cancel", taskId),
  pauseDesktopTask: (taskId, payload = {}) =>
    ipcRenderer.invoke("desktop-tasks:pause", taskId, payload),
  takeOverDesktopTask: (taskId, payload = {}) =>
    ipcRenderer.invoke("desktop-tasks:takeover", taskId, payload),
  continueDesktopTask: (taskId, payload = {}) =>
    ipcRenderer.invoke("desktop-tasks:continue", taskId, payload),
  retryDesktopTask: (taskId) => ipcRenderer.invoke("desktop-tasks:retry", taskId),
  recoverDesktopTask: (taskId, action, agentId = "") =>
    ipcRenderer.invoke("desktop-tasks:recover", taskId, action, agentId),
  deleteDesktopConversation: (conversationId) => ipcRenderer.invoke("desktop-conversations:delete", conversationId),
  listEvolutionTasks: (limit) => ipcRenderer.invoke("evolution-tasks:list", limit),
  getEvolutionTask: (taskId) => ipcRenderer.invoke("evolution-tasks:get", taskId),
  createEvolutionTask: (payload) => ipcRenderer.invoke("evolution-tasks:create", payload),
  cancelEvolutionTask: (taskId) => ipcRenderer.invoke("evolution-tasks:cancel", taskId),
  rollbackEvolutionTask: (taskId) => ipcRenderer.invoke("evolution-tasks:rollback", taskId),
  publishEvolutionTask: (taskId, approvalHash) =>
    ipcRenderer.invoke("evolution-tasks:publish", taskId, approvalHash),
  evolutionV2Request: (method, pathname, body = null) =>
    ipcRenderer.invoke("evolution-v2:request", method, pathname, body),
  listProactiveTasks: (limit) => ipcRenderer.invoke("proactive-tasks:list", limit),
  createProactiveTask: (payload) => ipcRenderer.invoke("proactive-tasks:create", payload),
  updateProactiveTask: (taskId, payload) =>
    ipcRenderer.invoke("proactive-tasks:update", taskId, payload),
  deleteProactiveTask: (taskId) => ipcRenderer.invoke("proactive-tasks:delete", taskId),
  triggerProactiveTask: (taskId) => ipcRenderer.invoke("proactive-tasks:trigger", taskId),
  listProactiveRuns: (taskId, limit) =>
    ipcRenderer.invoke("proactive-runs:list", taskId, limit),
  cancelProactiveRun: (runId) => ipcRenderer.invoke("proactive-runs:cancel", runId),
  getDesktopControl: () => ipcRenderer.invoke("desktop-control:get"),
  revokeDesktopAuthorization: (authorizationId) =>
    ipcRenderer.invoke("desktop-control:revoke", authorizationId),
  getDesktopMemory: (query, limit, status) =>
    ipcRenderer.invoke("desktop-memory:list", query, limit, status),
  getDesktopMemoryInbox: (limit) => ipcRenderer.invoke("desktop-memory:inbox", limit),
  getDesktopMemoryEvolution: (limit) => ipcRenderer.invoke("desktop-memory:evolution", limit),
  runDesktopMemoryCritic: () => ipcRenderer.invoke("desktop-memory:critic-run"),
  getDesktopMemoryVisualization: (limit) =>
    ipcRenderer.invoke("desktop-memory:visualization", limit),
  proposeDesktopMemory: (payload) => ipcRenderer.invoke("desktop-memory:propose", payload),
  rememberDesktopMemory: (payload) => ipcRenderer.invoke("desktop-memory:remember", payload),
  forgetDesktopMemory: (memoryId) => ipcRenderer.invoke("desktop-memory:forget", memoryId),
  reviewDesktopMemoryCandidate: (candidateId, action) =>
    ipcRenderer.invoke("desktop-memory:review", candidateId, action),
  getToolMarketplace: () => ipcRenderer.invoke("tool-marketplace:list"),
  installToolMarketplaceItem: (itemId, configuration = {}, approvedPermissions = []) =>
    ipcRenderer.invoke("tool-marketplace:install", itemId, configuration, approvedPermissions),
  uninstallToolMarketplaceItem: (itemId) =>
    ipcRenderer.invoke("tool-marketplace:uninstall", itemId),
  revokeToolMarketplaceItem: (itemId) =>
    ipcRenderer.invoke("tool-marketplace:revoke", itemId),
  rollbackToolMarketplaceItem: (itemId) =>
    ipcRenderer.invoke("tool-marketplace:rollback", itemId),
  getDesktopSkills: () => ipcRenderer.invoke("desktop-skills:list"),
  saveDesktopSkill: (payload) => ipcRenderer.invoke("desktop-skills:save", payload),
  setDesktopSkillEnabled: (skillId, enabled) => ipcRenderer.invoke("desktop-skills:enabled", skillId, enabled),
  deleteDesktopSkill: (skillId) => ipcRenderer.invoke("desktop-skills:delete", skillId),
  getDesktopMcp: () => ipcRenderer.invoke("desktop-mcp:list"),
  saveDesktopMcp: (payload) => ipcRenderer.invoke("desktop-mcp:save", payload),
  getDesktopMcpImportSources: () => ipcRenderer.invoke("desktop-mcp-import:sources"),
  chooseMcpConfig: () => ipcRenderer.invoke("desktop-mcp-import:choose"),
  readDiscoveredMcpConfig: (filePath) => ipcRenderer.invoke("desktop-mcp-import:read", filePath),
  previewDesktopMcpImport: (payload) => ipcRenderer.invoke("desktop-mcp-import:preview", payload),
  commitDesktopMcpImport: (payload) => ipcRenderer.invoke("desktop-mcp-import:commit", payload),
  probeDesktopMcp: (connectionId) => ipcRenderer.invoke("desktop-mcp:probe", connectionId),
  deleteDesktopMcp: (connectionId) => ipcRenderer.invoke("desktop-mcp:delete", connectionId),
  chooseAttachments: () => ipcRenderer.invoke("files:choose"),
  describeAttachments: (filePaths) => ipcRenderer.invoke("files:describe", filePaths),
  clipboardFilePath: (file) => webUtils.getPathForFile(file),
  stageClipboardAttachments: (items) => ipcRenderer.invoke("files:stage-clipboard", items),
  releaseStagedAttachments: (filePaths) => ipcRenderer.invoke("files:release-staged", filePaths),
  openTaskArtifact: (taskId, relativePath) => ipcRenderer.invoke("task-artifact:open", taskId, relativePath),
  revealTaskWorkspace: (taskId) => ipcRenderer.invoke("task-workspace:reveal", taskId),
  loadLocale: (language) => ipcRenderer.invoke("i18n:load", language),
  pairingUrl: () => ipcRenderer.invoke("pairing:url"),
  openExternal: (url) => ipcRenderer.invoke("open:external", url),
  copyText: (text) => ipcRenderer.invoke("clipboard:write", text)
});
