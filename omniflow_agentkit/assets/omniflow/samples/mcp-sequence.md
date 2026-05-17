# Sample MCP Sequence

Use this only when `tools/list` shows direct OmniFlow tools.

## Register Sample Function

```json
{
  "name": "oob_function_register",
  "arguments": {
    "functionSpec": {
      "schema_version": "oob.reusable_function.v1",
      "function_id": "open_settings_demo",
      "name": "Open Settings",
      "description": "Open Android Settings and wait for one second.",
      "parameters": [],
      "execution": {
        "kind": "tool_sequence",
        "steps": [
          {
            "id": "step_1",
            "index": 0,
            "title": "Open Android Settings",
            "executor": "omniflow",
            "omniflow_action": "open_app",
            "args": {"package_name": "com.android.settings"}
          },
          {
            "id": "step_2",
            "index": 1,
            "title": "Wait",
            "executor": "omniflow",
            "omniflow_action": "wait",
            "args": {"duration_ms": 1000}
          }
        ]
      }
    }
  }
}
```

## Guard Check

```json
{
  "name": "oob_function_guard_check",
  "arguments": {
    "functionId": "open_settings_demo",
    "arguments": {}
  }
}
```

Expected decision: `allow`.

## Run

```json
{
  "name": "oob_function_run",
  "arguments": {
    "functionId": "open_settings_demo",
    "arguments": {},
    "dryRun": false,
    "continueWithAgent": false
  }
}
```

Expected result: the device opens Android Settings and the response includes
`success=true` and `function_id=open_settings_demo`.
