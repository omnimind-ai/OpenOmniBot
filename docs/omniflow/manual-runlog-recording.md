# Manual RunLog Recording Policy

Manual RunLog recording must be loss-intolerant. Missing one user action is
worse than blocking the recording, so every replayable action must satisfy this
contract before it is written to a RunLog.

## Replayable Action Sources

Allowed replayable backends:

- `overlay_touch`
- `overlay_touch_text_input`
- `device_getevent`
- `device_getevent_text_input`

Forbidden replayable backend:

- `accessibility_event`

Accessibility events are evidence only. They may update page state, XML
snapshots, text/focus diagnostics, screenshots, and audit counters, but they
must not create replayable `click`, `long_press`, `swipe`, or `input_text`
steps by themselves.

## Required Per-Action Evidence

Every recorded action must have:

- a real touch or device input source from the allowed backend list
- a non-empty before XML snapshot captured before that action
- concrete action coordinates or text-input anchor metadata
- action timing metadata where the source can provide it
- after evidence when available

If the recorder cannot capture before XML, cannot execute the concrete gesture,
or cannot append the action after execution, it must report recording failure
for that action instead of silently writing a partial or A11-derived step.

## Overlay Recording Flow

The product overlay is the preferred manual capture path:

1. The overlay intercepts the user's touch.
2. The recorder captures the before XML baseline.
3. The overlay temporarily unlocks pass-through.
4. The recorder replays the concrete gesture through the device.
5. The recorder waits for the action append to complete.
6. The UI shows success only when `executed == true` and `recorded == true`.

When execution succeeds but recording fails, the UI must show a recording
failure and keep the session locked/controlled. It must not count that action as
captured.

Text input follows the same rule. A text change can become replayable only when
it is grounded by a real touch/input anchor. Accessibility text events alone are
diagnostics.

## Debug And Script Validation

`scripts/oob-record-human-run.sh` audits manual recording artifacts with these
hard checks:

- `manual_recording.a11_replay_actions_enabled == false`
- no action uses `recording_backend = accessibility_event`
- every action backend is in the allowed backend list
- every action has before XML
- debug overlay gesture validation records only overlay-touch backends
- debug overlay gesture validation reports `guarantees_no_missing_clicks = true`

Use:

```bash
scripts/oob-device-manual-trace-validation.sh --device <serial>
```

This wrapper runs one debug overlay click and one debug overlay swipe, then
fails if either operation executes without being recorded or if the artifact
contains an A11-only replay action.

## Artifact Expectations

The audit file is:

```text
<artifact_dir>/audit/recording_audit.json
```

Important fields:

- `schema_version = "oob.manual_recording_audit.v2"`
- `manual_recording.a11_replay_actions_enabled`
- `recording_backend_counts`
- `missing_before_xml_steps`
- `a11_backend_steps`
- `unexpected_backend_steps`
- `debug_overlay_non_overlay_backend_steps`

An empty list for the error step fields is required for acceptance.
