# Sample MCP Sequence

Use this when `tools/list` shows the OmniFlow Function tools.

## Recall

```json
{
  "name": "omniflow.recall",
  "arguments": {
    "goal": "open Android Settings and click through the demo path",
    "current_package": "com.android.settings",
    "k": 8
  }
}
```

Expected result: `decision=hit` with `function_id=settings_click_path_demo`, or a
ranked candidate list that the agent can select from.

## Call Function

```json
{
  "name": "omniflow.call_function",
  "arguments": {
    "function_id": "settings_click_path_demo",
    "arguments": {},
    "goal": "open Android Settings and click through the demo path"
  }
}
```

Expected result: the response includes `success=true`,
`function_id=settings_click_path_demo`, `run_id`,
`timing.runner_duration_ms`, and 7 `step_results`, including 4 `click` steps.

## Write Back A RunLog

```json
{
  "name": "omniflow.ingest_run_log",
  "arguments": {
    "run_id": "runlog_install_demo",
    "auto_enrich": true
  }
}
```

Expected result: the host registers or updates a reusable Function and returns
`accepted=true` with the resulting `function_id`.

## Call Ingested Function

```json
{
  "name": "omniflow.call_function",
  "arguments": {
    "function_id": "install_sample_apk_demo",
    "arguments": {},
    "goal": "run the ingested install sample Function"
  }
}
```

Expected result: the response includes `success=true`,
`function_id=install_sample_apk_demo`, and a replay `run_id`.
