# Real Device VLM Test Notes

Date: 2026-05-27
Device: `10AE3609LG0052Y` (`vivo V2309A`)
Package: `cn.com.omnimind.bot.debug`

## Scope

Only tested and recorded issues on a real device. No business-code fix was made in this pass.

## Test Entry

Debug receiver:

```bash
adb -s 10AE3609LG0052Y shell am broadcast \
  -n cn.com.omnimind.bot.debug/cn.com.omnimind.bot.debug.DebugVlmRunLogReceiver \
  -a cn.com.omnimind.bot.debug.RUN_VLM_RUNLOG \
  --es goal "打开设置并停留在设置首页" \
  --es packageName com.android.settings \
  --ei maxSteps 1 \
  --el timeoutMs 90000 \
  --ez register false \
  --ez disableOmniFlowRecall true
```

Result file:

```bash
adb -s 10AE3609LG0052Y shell run-as cn.com.omnimind.bot.debug \
  cat files/debug-vlm-runlog-result.json
```

## Findings

| ID | Finding | Evidence | Impact | Next Fix |
| --- | --- | --- | --- | --- |
| RD-VLM-001 | If Accessibility is disabled, the debug VLM receiver does not produce `debug-vlm-runlog-result.json` within 120s. | Before enabling: `settings get secure enabled_accessibility_services -> null`, `accessibility_enabled -> 0`; broadcast returned `result=0`, but no result file after 120s. | Test harness looks hung instead of returning a clear missing-permission result. | Make `DebugVlmRunLogReceiver` fail fast when Accessibility is not enabled/bound, and always write a result JSON. |
| RD-VLM-002 | After Accessibility is enabled, the task exits early because the real device is locked. | Result JSON status is `SCREEN_LOCKED`, message says the device is locked or screen is off. | VLM does not reach model call/action execution on locked devices. | This behavior is acceptable, but test harness should pre-check and report lock state before starting VLM. |
| RD-VLM-003 | ADB wake/swipe did not unlock this vivo device. | `dumpsys window`: `showing=true`, `mInputRestricted=true`, `isKeyguardShowing=true`, `mDreamingLockscreen=true` after `KEYCODE_WAKEUP`, `wm dismiss-keyguard`, and swipe. | Real-device VLM test cannot proceed without manual unlock or a test device without secure keyguard. | Require manual unlock before real-device VLM test, or add a dedicated test-device setup step. |

## Captured Result

```json
{
  "success": false,
  "goal": "打开设置并停留在设置首页",
  "packageName": "com.android.settings",
  "prelaunch": true,
  "startFromCurrent": false,
  "skipGoHome": false,
  "disable_omniflow_recall": true,
  "wait_timeout_ms": 90000,
  "step_skill_guidance_chars": 0,
  "outcome": {
    "taskId": "e20c1a83-5a24-485b-a64a-ace4e71aa09c",
    "goal": "打开设置并停留在设置首页",
    "status": "SCREEN_LOCKED",
    "message": "设备当前处于锁屏或熄屏状态，VLM 任务暂时无法开始。请先让用户解锁手机，然后重新继续任务。",
    "needSummary": false,
    "summaryUnavailable": false,
    "recentActivity": [
      "[SYSTEM] Screen locked, waiting for unlock..."
    ],
    "executionRoute": "",
    "missingPermissions": []
  },
  "direct_recall_completed": false,
  "run_id": "e20c1a83-5a24-485b-a64a-ace4e71aa09c",
  "runlog_found": false,
  "runlog_card_count": 0,
  "token_usage": {},
  "token_usage_by_step": [],
  "token_usage_by_call": []
}
```

## Current Status

Real VLM action execution was not verified in this pass because the device stayed locked. The next test should start from an unlocked screen with Accessibility enabled.

## Retest 2026-05-27 01:xx

Rechecked the same real device before running another VLM request.

| Check | Result |
| --- | --- |
| Device connected | `10AE3609LG0052Y device usb:1-1 product:PD2309 model:V2309A` |
| Accessibility service | Enabled: `cn.com.omnimind.bot.debug/com.google.android.accessibility.selecttospeak.SelectToSpeakService`; `accessibility_enabled=1` |
| Keyguard state | Still locked: `showing=true`, `mInputRestricted=true`, `mDreamingLockscreen=true`, `isKeyguardShowing=true` |

Decision: did not rerun the VLM model path while the device is locked, because it would only reproduce `SCREEN_LOCKED` and would not test real action execution. The next meaningful run requires manually unlocking the device first.
