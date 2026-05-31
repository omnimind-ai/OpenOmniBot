# OmniFlow Function Spec

Canonical schema version:

```text
oob.reusable_function.v1
```

Function specs are portable execution assets. They can come from RunLog
conversion, manual registration, SDK calls, MCP registration, or future
OmniFlow matching.

## Minimal Shape

```json
{
  "schema_version": "oob.reusable_function.v1",
  "function_id": "settings_click_path_demo",
  "name": "Settings Click Path Demo",
  "description": "Open Android Settings and replay a deterministic path with multiple click steps.",
  "parameters": [],
  "source": {
    "kind": "manual",
    "created_by": "guiagent"
  },
  "execution": {
    "kind": "tool_sequence",
    "runner": "oob_tool_sequence",
    "entrypoint": "execute",
    "steps": [
      {
        "id": "step_1",
        "index": 0,
        "title": "Open Settings",
        "executor": "omniflow",
        "omniflow_action": "open_app",
        "args": {
          "package_name": "com.android.settings"
        }
      },
      {
        "id": "step_2",
        "index": 1,
        "title": "Tap Network & internet",
        "executor": "omniflow",
        "omniflow_action": "click",
        "args": {
          "x": 180,
          "y": 420,
          "target": "Network & internet"
        }
      }
    ],
    "step_count": 7
  }
}
```

## Parameters

Parameters bind caller arguments into step args.

```json
{
  "name": "message",
  "type": "string",
  "required": true,
  "description": "Message text to type.",
  "bindings": [
    "$.execution.steps[2].args.content",
    "$.execution.steps[2].agent_call.args.original_args.content"
  ]
}
```

Rules:

- Bind into every place that will be used by runtime or fallback prompt.
- Keep bindings aligned after skipping wrapper RunLog cards.
- Do not let AI normalization turn data-flow tools into direct replay steps.

## Executors

### `executor=omniflow`

Deterministic local replay. Allowed actions:

```text
click
long_press
input_text
swipe
open_app
press_key
finished
```

Legacy names such as `tap`, `type`, `scroll`, `press_back`, `press_home`,
`hot_key`, and `done` may be accepted at ingestion time, but newly written
Function steps should use the canonical action vocabulary documented in
`canonical-actions.md`. `wait` is not a main-path action; represent waits as
checker delays or cleanup metadata.

Coordinate actions may include:

```json
{
  "coordinate_hook": "omniflow",
  "source_context": {
    "src_ctx": {
      "page": "<hierarchy>...</hierarchy>"
    },
    "action": {
      "tool": "click",
      "x": 540,
      "y": 400
    }
  }
}
```

### `executor=agent`

Live planning or perception. Use for:

```text
vlm_task
image_picker
screen_capture
android_privileged_action_screenshot
browser_use
web_search
memory_search
memory_recall
memory_query
oob_agent_run
omniflow.recall
omniflow.ingest_run_log
workbench_api_list
```

Agent steps must include a fallback prompt:

```json
{
  "executor": "agent",
  "tool": "browser_use",
  "callable_tool": "oob.agent.run",
  "scriptable": false,
  "agent_call": {
    "tool": "oob.agent.run",
    "args": {
      "prompt": "Re-plan this browser step from the current screen.",
      "original_tool": "browser_use",
      "original_args": {}
    },
    "reason": "data_flow_tool_requires_live_context"
  }
}
```

### `executor=tool`

Direct tool calls are allowed only inside a live Agent runtime with a router.
External MCP direct execution should treat these as `needs_agent` unless the
host explicitly supports safe tool delegation.

## Guard Metadata

Function descriptors may cache a summary:

```json
{
  "guard": {
    "risk_level": "low",
    "requires_confirmation": false,
    "capabilities": ["local_ui_replay"]
  }
}
```

Cached metadata is advisory. The runner must still call live guard policy with
materialized arguments before execution.
