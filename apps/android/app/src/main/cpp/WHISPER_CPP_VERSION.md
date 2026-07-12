# whisper.cpp Android Runtime

- Upstream: https://github.com/ggml-org/whisper.cpp
- Tag: `v1.9.1`
- Commit: `f049fff95a089aa9969deb009cdd4892b3e74916`
- License: MIT (`whispercpp/LICENSE`)
- Model: `ggml-base.bin`
- Model source: https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin
- Model size: `147951465` bytes
- Model SHA-256: `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`

The source tree under `whispercpp/` is vendored from the exact upstream tag above.
SignalASI builds only the `arm64-v8a` runtime and packages the base model as an
uncompressed Android asset.

One upstream source comment was translated to English to satisfy the repository
language guard. Runtime code is unchanged.
