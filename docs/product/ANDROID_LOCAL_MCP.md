# Android Local MCP Packages

SignalASI Android can install an MCP stdio server as a reviewed local package and run it inside the app-managed Linux sandbox. This path is intended for offline tools, private data transformations, and device-local integrations that do not need an external Desktop process.

## Trust boundary

- A local MCP package is untrusted code.
- Package files stay in app-private storage and execute only inside the bounded on-device Linux workspace.
- The Android host still owns tool discovery, schema validation, risk classification, permission checks, confirmation, receipts, and result rendering.
- Installing a package never grants Android permissions or expands the native tool catalog by itself.
- Package credentials remain in encrypted Android storage. Manifest environment templates receive only explicitly named values at process launch.
- Direct guest networking is disabled. A network-backed integration must use Streamable HTTP or a declarative HTTP package so Android can enforce endpoint and credential policy.

## Package format

The package is a ZIP-compatible archive containing `mcp.json` and a bounded `runtime/` source tree. Supported server runtimes are shell, Python, JavaScript, and TypeScript. Python is also required for the host-owned stdio bridge.

```json
{
  "format_version": 1,
  "id": "example.local_tools",
  "version": "1.0.0",
  "name": "Local tools",
  "description": "Offline tools for a private workspace",
  "transport": {
    "type": "local_stdio",
    "runtime": "python",
    "entrypoint": "runtime/server.py",
    "arguments": ["--stdio"],
    "environment": {
      "ACCESS_TOKEN": "{{auth.access_token}}"
    },
    "allowed_network_domains": [],
    "timeout_ms": 60000
  },
  "authentication": [
    {
      "method": "bearer_token"
    }
  ],
  "tools": []
}
```

The server must speak newline-delimited MCP JSON-RPC on stdin/stdout. SignalASI starts a fresh bounded server process for discovery or a tool call, performs the MCP initialization handshake, executes one operation, captures the structured result, and terminates the process. Server log text is not interpreted as a tool result.

## Runtime requirements

- `linux-base` and `python-uv` must be ready.
- Credential templates require `linux-base` 1.1.0 or newer and the negotiated
  `runtime.secret_environment` Guest capability. Older Guests fail before process launch.
- JavaScript servers also require `node-js`.
- TypeScript servers also require `node-js` with `tsx` support.
- The entrypoint must be a relative file under `runtime/`.
- Runtime files are restricted to reviewed text/source formats and package size limits.
- HTTP authentication exchanges are not accepted for local stdio packages. A local server can receive encrypted, user-entered values through environment templates, while remote login and refresh flows use the existing host-mediated HTTP transports.

## Lifecycle

1. Android inspects archive paths, limits, manifest integrity, transport, runtime, authentication, and entrypoint.
2. The user reviews the package and explicitly installs it.
3. Source files are committed atomically to app-private package storage.
4. Each invocation materializes a clean runtime tree in a package-specific Agent workspace.
5. The Linux guest runs the host-owned bridge and package server with bounded time, memory, process, output, and disk limits.
6. Android parses only the final structured bridge envelope, records normal MCP/native-tool provenance, and removes the invocation control file.

This transport complements, rather than replaces, Streamable HTTP, declarative HTTP, paired Desktop MCP, native Android tools, and Skills.

Real-device coverage lives in `AgentMcpLocalRuntimeDeviceTest`. It separately verifies the plain
stdio path and the credential-bearing path so a legacy Guest cannot be mistaken for a secure pass.
