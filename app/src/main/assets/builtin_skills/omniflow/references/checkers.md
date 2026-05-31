# Checkers

Use this reference for conditional obstacles such as ads, popups, permission
dialogs, skip buttons, banners, coupons, and keyboards.

## Core Rule

Conditional obstruction handling is an optional checker. It is not part of the
guaranteed happy path unless the user explicitly requests a structural repair.

## How To Identify Ads Or Optional Obstructions

Treat a step as optional checker evidence when it:

- Clicks text like `跳过`, `关闭`, `稍后`, `取消`, `知道了`, `不再提示`, or a close
  icon.
- Dismisses content that is visually above or blocking the intended target.
- Appears in one RunLog but is not required in another successful RunLog for the
  same Function.
- Has no durable business meaning after it disappears.
- Is followed by the same main-path action that would have worked without the
  obstruction.

Do not use these signals alone to delete the action. Convert it into checker
metadata or evidence.

## Supported Runtime Checker Rules

Use only supported runtime checker types:

- `overlay_blocking` + `dismiss` + `pre_transfer`
- `permission_dialog` + `allow` + `pre_transfer`
- `keyboard_obscuring` + `hide_keyboard` + `pre_action`
- `package_mismatch` + `open_app` + `pre_transfer`

Do not invent checker conditions, scripts, selectors, or model calls.

## Patch Pattern

```json
{
  "steps": [
    {
      "index": 2,
      "title": "关闭可选广告弹窗",
      "description": "如果广告弹窗遮挡主路径，关闭它以继续后续操作。",
      "action_purpose": "处理可能出现的条件性遮挡物，不属于稳定主路径。",
      "importance": "optional",
      "cleanup_action": "optional_checker",
      "cleanup_reason": "广告弹窗不一定每次出现。",
      "optional_condition": "仅当广告弹窗实际遮挡目标区域时执行。"
    }
  ],
  "metadata": {
    "checker_rules": [
      {
        "id": "dismiss_optional_overlay_before_action",
        "phase": "pre_transfer",
        "condition": "overlay_blocking",
        "action": "dismiss",
        "enabled": true,
        "params": {}
      }
    ]
  },
  "agent_reuse": {
    "checker_assets": [
      {
        "checker_id": "dismiss_optional_overlay_before_action",
        "step_index": 2,
        "reason": "由录制中的关闭广告/弹窗动作提炼成条件 checker。"
      }
    ]
  }
}
```
