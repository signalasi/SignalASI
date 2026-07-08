# Security Policy

SignalASI is designed around explicit trust, local-first control, and encrypted communication.

## Reporting Vulnerabilities

Do not disclose security issues publicly before a fix is available. Open a private security advisory or contact the maintainers through the configured GitHub security channel.

## Security Model

- Devices establish trust through QR pairing and fingerprint confirmation.
- Agent communication is routed through SignalASI Link.
- Local agents and models are treated as separate contacts with explicit capabilities.
- Execution logs must not persist full prompt text.

## Sensitive Files

Never commit local identities, pairing states, private keys, API keys, databases, logs, packaged binaries, or exported backups.

