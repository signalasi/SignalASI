(function (root) {
  function errorLabel(error, fallback) {
    const labels = {
      blob_config_revision_conflict: "Relay settings changed; refresh",
      blob_config_identity_mismatch: "Device identity changed; refresh",
      paired_identity_unavailable: "Device is no longer paired",
      blob_new_origin_requires_credential: "Enter a credential for the new Relay",
      relay_requires_https: "Relay requires HTTPS",
      invalid_relay_origin: "Enter an HTTPS origin without a path",
      invalid_identifier: "Credential must contain 64 hexadecimal characters",
      blob_configuration_storage_failed: "Encrypted settings storage unavailable"
    };
    const message = String(error?.message || "");
    return Object.entries(labels).find(([code]) => message.includes(code))?.[1] || fallback;
  }
  function credentialForUpdate(value) {
    return value.trim() || null;
  }

  function createController(document, api, translate) {
    const get = (id) => document.getElementById(id);
    const device = get("blobSettingsDevice");
    if (!device) return null;
    const enabled = get("blobSettingsEnabled");
    const origin = get("blobSettingsOrigin");
    const credential = get("blobSettingsCredential");
    const status = get("blobSettingsStatus");
    const save = get("blobSettingsSave");
    const reload = get("blobSettingsReload");
    let current = null;
    let sequence = 0;
    let signature = "";
    let busy = false;
    const text = (key) => translate(key);
    function controls() {
      save.disabled = busy || !current;
      reload.disabled = busy || !device.value;
      enabled.disabled = busy || !current;
      origin.disabled = busy || !current || !enabled.checked;
      credential.disabled = origin.disabled;
    }
    function render(value) {
      current = value;
      enabled.checked = Boolean(value?.enabled);
      origin.value = value?.origin || "";
      credential.value = "";
      get("blobSettingsCredentialState").textContent = value?.credential_present
        ? text("Credential saved") : text("No credential saved");
      controls();
    }
    function clearSensitive() {
      sequence += 1;
      busy = false;
      render(null);
      status.textContent = "";
    }
    async function refresh() {
      const ticket = ++sequence;
      const route = device.value;
      render(null);
      if (!route) { status.textContent = text("No phone paired"); return; }
      busy = true;
      controls();
      status.textContent = text("Loading");
      try {
        const value = await api.getBlobSettings(route);
        if (ticket !== sequence || route !== device.value) return;
        render(value);
        status.textContent = value.client_opted_in ? text("Device supports Blob transfer") : text("Waiting for device capabilities");
      } catch (error) {
        if (ticket === sequence) status.textContent = text(errorLabel(error, "Unable to load Relay settings"));
      } finally {
        if (ticket === sequence) { busy = false; controls(); }
      }
    }
    function setClients(clients) {
      const rows = clients.filter((item) => item.paired && item.client_route_id);
      const next = JSON.stringify(rows.map((item) => [item.client_route_id, item.display_name, item.identity_fingerprint]));
      if (next === signature) return;
      signature = next;
      const selected = device.value;
      device.replaceChildren(...rows.map((item) => {
        const option = document.createElement("option");
        option.value = item.client_route_id;
        option.textContent = item.display_name || item.device_name || item.client_route_id;
        return option;
      }));
      if (rows.some((item) => item.client_route_id === selected)) device.value = selected;
      clearSensitive();
      if (get("blobSettingsSection").open) refresh();
    }
    get("blobSettingsSection").addEventListener("toggle", () => {
      if (get("blobSettingsSection").open) refresh();
      else clearSensitive();
    });
    device.addEventListener("change", refresh);
    enabled.addEventListener("change", controls);
    reload.addEventListener("click", refresh);
    get("blobSettingsForm").addEventListener("submit", async (event) => {
      event.preventDefault();
      if (!current || busy) return;
      const ticket = ++sequence;
      const route = device.value;
      const payload = { identity_fingerprint: current.identity_fingerprint,
        identity_binding: current.identity_binding,
        expected_revision: current.revision, enabled: enabled.checked,
        origin: origin.value.trim(), provisioning_token: credentialForUpdate(credential.value) };
      credential.value = "";
      busy = true;
      controls();
      status.textContent = text("Saving");
      try {
        const value = await api.saveBlobSettings(route, payload);
        if (ticket !== sequence || route !== device.value) return;
        render(value);
        status.textContent = value.configuration_queued ? text("Configuration queued") : text("Saved; waiting for device");
      } catch (error) {
        if (ticket === sequence) status.textContent = text(errorLabel(error, "Unable to confirm Relay settings; refresh"));
      } finally {
        payload.provisioning_token = null;
        if (ticket === sequence) { busy = false; controls(); }
      }
    });
    api.onSensitiveStateClear?.(clearSensitive);
    api.onSensitiveStateResume?.(() => {
      if (get("blobSettingsSection").open && get("gatewayPanel")?.classList.contains("active")) refresh();
    });
    render(null);
    return { setClients, clearSensitive, refresh };
  }
  if (typeof module !== "undefined") module.exports = { createController, credentialForUpdate, errorLabel };
  if (root.document && root.galaxyssi) {
    root.GalaxySSIBlobSettings = createController(root.document, root.galaxyssi,
      (key) => typeof root.t === "function" ? root.t(key) : key);
  }
})(globalThis);
