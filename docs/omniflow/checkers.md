# Runtime Checkers

Checkers handle unstable UI conditions that should not be part of the 100%
main path.

## Supported Rules

```text
overlay_blocking + dismiss + pre_transfer
permission_dialog + allow + pre_transfer
keyboard_obscuring + hide_keyboard + pre_action
package_mismatch + open_app + pre_transfer
```

Examples:

```json
{
  "id": "dismiss_optional_overlay_before_action",
  "phase": "pre_transfer",
  "condition": "overlay_blocking",
  "action": "dismiss",
  "enabled": true,
  "params": {}
}
```

## Conversion Rule

Recorded steps such as closing an ad, dismissing a coupon, granting a permission,
or hiding the keyboard should usually become checker rules instead of main-path
actions. The original step should keep an annotation explaining why it became a
checker candidate.

## Runtime Files

- Rule model: `OmniflowCheckerRule.kt`
- Rule execution: `OmniflowStepExecutor.kt`
- Function patching: `OobOmniFlowToolkitService.kt`
