# OOB Device Validation 2026-05-25

Status: Passed on `emulator-5554` and `emulator-5556`
Last Updated: 2026-05-25

This report records live-device validation for OOB online VLM, RunLog capture,
RunLog convert, offline replay, UDEG/page-match recall, segment Function reuse,
and manual takeover trace recording. Raw artifacts from this run are under
`/tmp/oob-device-tests-20260525/`.

## Device Matrix

| Device | Result | Notes |
| --- | --- | --- |
| `emulator-5554` | Pass | Clock was corrected before VLM tests because TLS failed with a 2023 device date. |
| `emulator-5556` | Pass | No clock correction needed. |
| `10AE3609LG0052Y` | Not claimed | APK install failed with `INSTALL_FAILED_ABORTED: User rejected permissions`; current app stayed old and OOB accessibility was unavailable. |

## Online VLM -> RunLog -> Convert -> Offline Replay

| Device | Goal | Online RunLog | Cards | Online Tokens | VLM Step Tokens | Convert Function | Offline Replay | Replay Duration |
| --- | --- | --- | ---: | ---: | --- | --- | --- | ---: |
| `5554` | Open Display settings | `3cac6073-15c5-4204-8718-c31b21c22095` | 3 | 31,038 | scroll 7,191; click 10,492; finished 13,355 | `debug_3cac6073_15c5_4204_8718_c31b21c22095` | `omniflow_run_1779720778865_1` | 7,105 ms |
| `5556` | Open Network & internet | `63686bd5-8352-4810-9c23-e117bf9ac4a8` | 2 | 17,670 | click 7,368; finished 10,302 | `debug_63686bd5_8352_4810_9c23_e117bf9ac4a8` | `omniflow_run_1779720838178_1` | 4,768 ms |

Offline replay step durations:

| Device | Steps |
| --- | --- |
| `5554` | `open_app` 2,535 ms; `scroll` 2,012 ms; `click` 1,554 ms; `finished` 1,003 ms |
| `5556` | `open_app` 2,319 ms; `click` 1,445 ms; `finished` 1,003 ms |

## UDEG Recall And Segment Function

| Device | Result | Decision | Reason | Total Duration | Recall Phase Timing | Nested Function |
| --- | --- | --- | --- | ---: | --- | --- |
| `5554` | Pass | `recall` | `udeg_node_skill_context_recall` | 7,478 ms | parse 0; package 22; page 0; page_match 60; rank_functions 13; segment_match 25 ms | `debug_open_settings_segment`, tools `[open_app]` |
| `5556` | Pass | `segment_hit` | `page_vector_segment_function_hit` | 7,854 ms | parse 0; package 4; page 0; page_match 661; rank_functions 1,546; segment_match 376 ms | `debug_open_settings_segment`, tools `[open_app]` |

End-to-end phase timing:

| Device | Phase Timing |
| --- | --- |
| `5554` | wait_accessibility 0; observe_before 518; register_child 437; register_parent 2; load_child 1; recall 126; parent_run 5,098; post_run_settle 1,202; observe_after 61 ms |
| `5556` | wait_accessibility 0; observe_before 131; register_child 536; register_parent 2; load_child 1; recall 2,621; parent_run 3,216; post_run_settle 1,203; observe_after 135 ms |

## Online VLM With Recall Guidance

| Device | Run ID | Route | Tokens | Calls |
| --- | --- | --- | ---: | ---: |
| `5554` | `7e7ece8d-b424-4dea-aad0-f25ff4c7e89e` | `vlm_with_omniflow_recall:segment_recall` | 47,737 | 3 |
| `5556` | `d686b44d-6940-44c5-b1d2-5cf0e1d299cb` | `vlm_with_omniflow_recall:segment_recall` | 26,051 | 2 |

This validates that online VLM loaded recall context but still executed through
the live VLM loop instead of silently short-circuiting into direct local replay.

## Manual Takeover Trace Recording

| Device | Result | Actions | Token Usage | Duration | Recorder Phase Timing | Event Probe |
| --- | --- | --- | ---: | ---: | --- | --- |
| `5554` | Pass | `click`, `swipe` | 0 | 9,630 ms | wait_accessibility 368; start_recorder 206; recording_window 9,009; stop_recorder 2 ms | clicked 1; scrolled 1; window_content_changed 9; window_state_changed 1 |
| `5556` | Pass | `click`, `swipe` | 0 | 9,242 ms | wait_accessibility 46; start_recorder 155; recording_window 9,015; stop_recorder 1 ms | clicked 1; scrolled 4; window_content_changed 11; window_state_changed 1 |

Manual action durations after timestamp normalization:

| Device | Action Durations |
| --- | --- |
| `5554` | click 1,782 ms; swipe 1,479 ms |
| `5556` | click 1,285 ms; swipe 969 ms |

The manual recorder now converts `AccessibilityEvent.eventTime` from uptime to
wall-clock time before computing `duration_ms`. It also uses `scrollDeltaX/Y`
and a conservative direction fallback when a valid `TYPE_VIEW_SCROLLED` event
does not expose scroll index or absolute scroll offsets.

## Commands Used

```bash
./gradlew :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart --build-cache -q
bash scripts/prepare-oob-device.sh --device emulator-5554 --skip-build --no-launch
bash scripts/prepare-oob-device.sh --device emulator-5556 --skip-build --no-launch

bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --goal '当前在设置首页。打开显示设置页面，看到显示相关页面后调用 finished。不要重复点击同一位置，不要循环返回。' --max-steps 8 --timeout 300
bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5556 --goal '当前在设置首页。打开网络和互联网设置页面，看到 Network & internet 页面后调用 finished。不要重复点击同一位置，不要循环返回。' --max-steps 8 --timeout 300

bash scripts/oob-device-segment-validation.sh --device emulator-5554 --goal 'Open Settings from recalled reusable segment' --timeout 90
bash scripts/oob-device-segment-validation.sh --device emulator-5556 --goal 'Open Settings from recalled reusable segment' --timeout 90

bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5554 --goal '当前在设置首页。打开显示设置页面，看到显示相关页面后调用 finished。不要重复点击同一位置，不要循环返回。' --max-steps 8 --timeout 240 --expect-recall
bash scripts/demo-vlm-runlog-e2e.sh --device emulator-5556 --goal '当前在设置首页。打开网络和互联网设置页面，看到 Network & internet 页面后调用 finished。不要重复点击同一位置，不要循环返回。' --max-steps 8 --timeout 240 --expect-recall

bash scripts/oob-device-manual-trace-validation.sh --device emulator-5554 --duration-ms 9000 --timeout 30
bash scripts/oob-device-manual-trace-validation.sh --device emulator-5556 --duration-ms 9000 --timeout 30

./gradlew :app:testDevelopStandardDebugUnitTest \
  --tests 'cn.com.omnimind.bot.runlog.*' \
  --tests 'cn.com.omnimind.bot.vlm.*' \
  --tests 'cn.com.omnimind.bot.manager.AssistsCoreManagerOobReusableFunctionPayloadTest' \
  -Ptarget=lib/main_standard.dart --build-cache
```

## Verification Notes

- `:app:testDevelopStandardDebugUnitTest` focused run passed in 43 seconds.
- `:app:assembleDevelopStandardDebug` passed after the manual recorder changes.
- Token usage is recorded for online VLM calls. Offline replay and manual trace
  are model-free and record `token_usage_total = 0`.
- Internal timing keys such as `duration_ms`, `started_at_ms`,
  `finished_at_ms`, and `phase_ms` are retained in result payloads and tests,
  but should stay hidden from normal user-facing UI.
