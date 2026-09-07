# Programmatic Video And 240p Delivery

## Product Decision

All video creation requests use the coding-agent workflow: storyboard, animation
code, actual rendering, frame review, correction, media verification and delivery.
The words "AI", "native", or a provider name do not select a different generator.
There is no video-model adapter, API key configuration, local model download, or
cloud video fallback in this implementation. The selected coding Agent may still
use its own model subscription/API; this does not make the entire system offline.

Ordinary questions, video analysis, playback, requests for thumbnails/scripts only,
quoted examples, and negated creation requests do not start rendering. A creation
request must still be recognized; this is not a mandatory keyword or command.

## Execution

1. The selected Desktop coding Agent/model produces a validated JSON storyboard.
   Plans cover 2..120 seconds, default 32, with 1..16 contiguous scenes. Unsupported
   requirements return a clarification rather than silently shortening the clip.
2. The same Agent uses its existing terminal/file tools to write a reproducible
   `.video-generation/render.py`, discover local libraries/fonts, and render
   `.video-generation/source.mp4` with Python/Pillow/NumPy or available tools and
   FFmpeg. Automatic software/model installation is not authorized by this path.
3. Desktop independently extracts midpoint frames for every scene plus samples at
   15%, 50% and 85%. Identical sampled frames are rejected. A read-only image review
   checks content, text, layout and consistency with the storyboard. A failed review
   allows one correction pass. Inability to view images is not an approval.
4. FFprobe bounds-checks real media, and FFmpeg fully decodes the source. FFmpeg
   creates a separate H.264/yuv420p MP4, short edge <=240, no upscaling, <=24fps,
   240kbps video target and 24kbps mono AAC when source audio exists. Faststart and
   metadata removal are enabled. Encoder/container overhead is not an exact ceiling.
5. Full derivative decode must pass before atomic publication to
   `outputs/video-240p.mp4`. A textual claim of completion is never sufficient.
6. Existing task artifacts, Signal/MQTT encryption, SHA-256, acknowledged chunks,
   retry and Android VideoView playback are reused. Videos are never inline base64.
   "Verified and ready" is not the same as "received by phone".

## Scope And Limits

- Explainers, circuits, algorithms, data visualization, stylized scenes, product
  presentations, character motion and camera movement all use programmatic rendering.
  This does not promise photorealistic people, complex physical realism or native
  video-model quality. Explicit requirements beyond available tools need clarification.
- Science checks are requested in planning/render/review, but AI visual review and
  successful decoding are not a formal proof of factual accuracy or video quality.
- The lightweight pipeline currently accepts text-only storyboards. Reference
  attachments fail explicitly rather than being silently ignored.
- A Desktop coding Agent with terminal, file and image-viewing tools is required.
  Text-only cloud/local model connectors cannot independently run this pipeline.
- Narration requires an existing TTS tool. Music/tones are not labelled narration.
  Source audio is retained. Labels should be authored for legibility at 240p.
- Task workspace isolation, existing access profile, cancellation and task budgets
  still apply. Video requests require a current server-issued Desktop Executor
  grant, checked again between operations. Plan/review are read-only; Codex rendering
  uses workspace-write, not an unrestricted shell. Video intent uses artifact-task
  watchdog limits rather than the shorter chat-task limit.
- On Windows, the video child command explicitly selects the supported elevated
  sandbox backend. This preserves workspace-write when user configuration is ignored;
  it does not disable the sandbox or modify global Codex settings. The owned render
  directory inherits Modify access for the current host user's SID so files created
  by the sandbox user remain readable by Desktop. No Everyone grant or recursive
  ACL rewrite is performed.
- Checkpoints bind Agent/model/request. Verified source/output hashes avoid rerender
  on retry. Failed visual-review feedback is retained for explicit bounded retries.
  Media and scripts follow task retention. Encrypted job metadata remains;
  attachment bytes do not acquire local AES again. No contact-image compression changes.
- No Android UI change is necessary. Only SM-T575 is authorized for device testing.

## Tests

```text
python -m pytest test_programmatic_video.py test_agent_cli_execution.py test_agent_execution_harness.py test_rich_output.py test_artifact_delivery.py -q
```

Tests cover intent variants, no model-name routing, negative requests, storyboard
bounds, actual-file requirement, bounded review retries, full-decode failures,
checkpoint reuse/binding, cancellation, selected-model preservation, read-only
versus rendering stages, SHA-256 artifacts and real FFmpeg derivative encoding.
Set `GALAXYSSI_STATE_DIR` to a fresh temporary directory before starting the test
process. Some legacy tests initialize runtime singletons; never let an offline
regression process use the running Desktop's state directory. A second runtime
store can incorrectly recover an active production run as interrupted.

The existing `tools/testing/native-video-probe` name is a historical test fixture
name, not a native-model integration. It tests artifact assembly and actual Android
playback with an explicitly synthetic clip. It does not prove a live MQTT roundtrip.
Run it only on ADB serial `R52R90282TY` (SM-T575), never another connected device.

2026-09-07: expanded desktop suite: 138 tests and 67 subtests passed. Repository
checks passed. SM-T575 artifact assembly/playback/seek probe passed in 4.783 seconds
using the existing synthetic fixture and existing installed App. No production
Desktop replacement or main-App APK installation was performed.

The first live GPT-6 probe failed with `video_render_no_output` because ignoring
user configuration also removed the Windows sandbox backend selection, causing an
effective read-only run. Explicit sandbox selection and scoped host ACL inheritance
fixed this: a real Codex file-write / Desktop file-read probe passed. A subsequent
live run produced a real 32-second Chinese chip animation. Independent review
rejected misleading gate wiring and later an overlapping gate label; unapproved
media was not published. Full reviewed generation and MQTT delivery are separate
acceptance stages; do not report the fixture test as successful content generation.
The obsolete `--ask-for-approval untrusted` planning flag was also discovered in
this run and replaced with read-only sandbox plus supported noninteractive config.

Follow-up live acceptance on 2026-09-07: the resumed real Codex pipeline completed
after visual corrections. Final output: 32 seconds, 426x240, 24fps, H.264/yuv420p,
927,842 bytes, no audio as explicitly requested by the probe. Both source and final
MP4 passed full decode, and the independent visual reviewer approved the actual
frames and animation script. SHA-256:
`462c77050fc88402fc645e0621f82cd1bba34339f9fcac873701057ea9ed0017`.
This was not a first-attempt quality pass. The bounded first run failed review;
the resumed run corrected the content and passed. The expanded regression suite
passed 147 tests and 67 subtests, and repository checks passed.

The phone probe uses these exact final bytes without a second transcode, split
into four application artifact chunks. This fixture injection tests the actual
Android artifact consumer, not delivery across a public MQTT broker. Production
Desktop has not been replaced with this worktree during the isolated render test.

SM-T575 follow-up: installed App 1.0.27 (871), test
`NativeVideoPlaybackDeviceTest.fragmented240pVideoPlaysInTheExistingAgentOutputView`
passed in 5.332 seconds using the exact approved MP4 above. Verified corrupt-chunk
rejection, four chunks in reverse order, duplicate handling, full-file SHA-256,
content URI, actual VideoView playback advancement and seek. A screenshot captured
the Chinese transistor scene on the device. Only the test APK was replaced; no
other phone, App data, pairing grant or production Desktop runtime was modified.

## Production MQTT Acceptance

On 2026-09-07, Desktop 1.0.31 was launched from this worktree using the existing
production data and pairing grants. The TLS MQTT connection and subscriptions
became ready. No main-App reinstall or authorization change was necessary.

The live path exposed a separate Codex MQTT app-server dispatch that bypassed the
verified video runner. Video creation now uses the generic task runner and its
scoped execution permissions; normal Codex chat and plan-only requests retain the
app-server path. Regression coverage explicitly checks all three routing cases.
The combined desktop regression suite passed 187 tests and 67 subtests. Repository
and Desktop checks passed.

`ProgrammaticVideoMqttDeviceTest.realPhoneRequestReturnsPlayableVideoOverMqtt`
is a separate real-device test, not the earlier artifact fixture. On SM-T575 only,
it uses an existing available paired Codex target and the App's normal submission
path. It waits for the actual returned video artifact, checks its SHA-256, then
checks VideoView playback advancement and seeking. It creates a private test
conversation and writes a device report and screenshot; it never injects video
bytes, changes pairing grants, or overrides the selected target's model.

The first live 8-second binary-counter request reached Desktop and returned real
progress but failed independent review: state digits lost contrast during color
transitions. No video was published. Its 20-minute device test consequently failed;
this is not a successful MQTT acceptance. An external runtime-store initialization
also marked the still-running runtime interrupted, masking the review error with
a stale event sequence conflict. Subsequent offline regressions use an isolated
state directory. Rendering instructions now require high-contrast transition text,
240p preview inspection before full rendering, and lower-cost source rendering
for simple explainers. Review criteria and bounded correction limits are unchanged.

The second live request completed reviewed generation in 610.922 seconds, including
one correction for mismatched binary labels during transitions. However, generic
artifact finalization bundled the private rendering directory into a project ZIP,
so the phone received an archive rather than a playable video. The device test was
stopped after diagnosing that failure; its forced-stop instrumentation result is
not a spontaneous App crash. This round also does not count as end-to-end success.
The `.video-generation` directory is now excluded from project candidate discovery,
APK discovery and recursive archive packaging. A regression checks finalization,
not only raw artifact enumeration: only `outputs/video-240p.mp4` may be selected,
and private intermediate files must not cause a project archive to be published.

The third live round passed on SM-T575 with installed App 1.0.27 (871), production
Desktop 1.0.31 and its configured Codex model `gpt-5.6-sol`. The phone originated
the request through the normal App path and the existing TLS MQTT relationship;
no fixture bytes or result injection were used. Desktop completed in 311.688
seconds; instrumentation completed in 343.884 seconds with `OK (1 test)`.
The actual received MP4 is 8 seconds, 426x240, H.264, 154,155 bytes, without audio
as requested. Device SHA-256 matched the Desktop artifact metadata:
`a4be41da6c18aa03c1f732f656f0ad1816f38728f035519f219d3913ad401104`.
VideoView playback advancement and seeking passed. After instrumentation, the
normal conversation page was reopened and its real video card was tapped and
visually checked, rather than relying only on the temporary test player.

The approved third round did not need a correction pass. This is one successful
end-to-end smoke case after two failed integration rounds, not a reliability or
content-quality percentage. Short programmatic videos still take minutes to plan,
render and review. Final regression: 187 tests and 67 subtests passed; video-specific
coverage, Desktop checks and the test APK build passed. Device evidence is stored
outside version control; only SM-T575 was operated by this task. Task workspace
cleanup after acknowledged handoff remains governed by the existing retention policy.

## Reference

The user supplied a ChatGPT web execution transcript: Python frame rendering,
preview inspection, FFmpeg encoding, FFprobe and full-decode verification. This
implementation follows that production method, not its environment-specific tool
names or claims about a previously generated file.
