# whisper.cpp Android Runtime

- Upstream: https://github.com/ggml-org/whisper.cpp
- Tag: `v1.9.1`
- Commit: `f049fff95a089aa9969deb009cdd4892b3e74916`
- License: MIT (`whispercpp/LICENSE`)
- Bundled model: `ggml-tiny.bin`
- Model source: https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin
- Model size: `77691713` bytes
- Model SHA-256: `BE07E048E1E599AD46341C8D2A135645097A538221678B7ACDD1B1919C6E1B21`

The source tree under `whispercpp/` is vendored from the exact upstream tag above.
SignalASI builds only the `arm64-v8a` runtime and packages the tiny model as an
uncompressed Android asset.

One upstream source comment was translated to English to satisfy the repository
language guard. Runtime code is unchanged.
