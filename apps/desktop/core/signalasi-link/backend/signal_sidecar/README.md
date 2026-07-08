# Hermes Signal Sidecar

Local JVM service that owns the PC-side Signal Protocol session state.

It uses the official `org.signal:libsignal-client` package. Python calls this service over
`127.0.0.1` and MQTT only carries encrypted Signal envelopes.

Endpoints:

- `GET /health`
- `GET /bundle`
- `POST /decrypt`
- `POST /encrypt`

This is the PC-side foundation. The next step is wiring Android to the same bundle/envelope
format and making `mqtt_bridge.py` publish encrypted replies.

