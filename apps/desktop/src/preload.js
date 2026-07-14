const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("signalasi", {
  startBackend: () => ipcRenderer.invoke("backend:start"),
  backendStatus: () => ipcRenderer.invoke("backend:status"),
  getRuntimeDiagnostics: () => ipcRenderer.invoke("runtime:diagnostics"),
  getPairingStatus: () => ipcRenderer.invoke("pairing:status"),
  clearPairing: (clientRouteId = "") => ipcRenderer.invoke("pairing:clear", clientRouteId),
  detectAgents: () => ipcRenderer.invoke("agents:detect"),
  getAgentDiagnostics: () => ipcRenderer.invoke("agents:diagnostics"),
  getAgentExecutionLog: (limit) => ipcRenderer.invoke("agents:execution-log", limit),
  getAgentTasks: (limit) => ipcRenderer.invoke("agents:tasks", limit),
  runAgentSelfTest: (options) => ipcRenderer.invoke("agents:self-test", options),
  getAgentConfig: () => ipcRenderer.invoke("agents:config:get"),
  saveAgentConfig: (config) => ipcRenderer.invoke("agents:config:save", config),
  testAgent: (agentId, prompt) => ipcRenderer.invoke("agents:test", agentId, prompt),
  sendMobileTest: (contactId, content) => ipcRenderer.invoke("mobile:test-message", contactId, content),
  syncMobileStatus: () => ipcRenderer.invoke("mobile:sync-status"),
  loadLocale: (language) => ipcRenderer.invoke("i18n:load", language),
  pairingUrl: () => ipcRenderer.invoke("pairing:url"),
  openExternal: (url) => ipcRenderer.invoke("open:external", url),
  copyText: (text) => ipcRenderer.invoke("clipboard:write", text)
});
