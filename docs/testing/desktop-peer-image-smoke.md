# Desktop peer-image smoke reliability

The peer-image UI smoke uses a durable message and a real imported PNG in its
isolated backend profile. It exercises the regular preview API and refreshes
messages before opening the viewer. Renderer-only fixtures were vulnerable to
normal periodic refresh replacing them with the empty database result.

Fixture setup is a local CLI, not a production HTTP endpoint. It requires the
explicit UI-smoke flag, disabled external services, and the exact isolated runtime
directory. Repeated setup reuses the same message. Production refresh, focus,
privacy cleanup, and image preview behavior remain enabled during the test.

When provided, `GALAXYSSI_STATE_DIR` holds the smoke Electron profile; screenshot
output remains in `GALAXYSSI_UI_SMOKE_DIR`. This avoids nesting runtime attachment
paths under a long checkout or packaged-output directory.

Attachment staging now uses one random basename instead of appending another
random suffix to the complete destination name. Staging still uses exclusive
creation, SHA-256 and length validation, fsync, same-directory atomic replace,
and cleanup on failure. This prevents the temporary filename alone from pushing
an otherwise valid Windows destination past legacy path limits; it does not
claim support for arbitrarily long destination paths.

## Verification

- `python -m unittest test_ui_smoke_fixtures test_peer_attachment_storage -v`
  from the backend directory: 9 tests, including durable refresh data, isolation
  refusal, separate profile paths, long paths, and failed-write cleanup.
- `node apps/desktop/scripts/smoke-ui.js`: real preview and full-screen image
  viewer rendering, with screenshots and the normal Desktop UI smoke checks.
- Windows packaged smoke remains a separate CI check; source smoke alone is not
  evidence that a packaged executable has passed.
