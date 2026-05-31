# Runtime Checkers

Checkers handle unstable UI conditions that should not be part of the 100%
main path.

## Supported Rules

```text
ad_blocking + dismiss + pre_transfer
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

`ad_blocking` is a built-in global checker. It identifies ads from the current
accessibility XML by combining signals, not by one keyword alone:

- explicit ad words in text/resource/class: `广告`, `推广`, `sponsored`,
  `advert`, `splash`, `interstitial`
- dismiss controls: `跳过`, `跳过 3`, `skip`, `close ad`, `关闭广告`
- ad SDK/resource hints: `skip_ad`, `close_ad`, `tt_splash_skip`, `ksad_skip`,
  `gdt_skip`
- geometry: small enabled clickable control near the top-right of a splash or
  full-screen surface

Plain `关闭`/`x` is not enough unless the page also has an ad cue. This keeps
normal dialogs from being dismissed accidentally.

## Conversion Rule

Recorded steps such as closing an ad, dismissing a coupon, granting a permission,
or hiding the keyboard should usually become checker rules instead of main-path
actions. The original step should keep an annotation explaining why it became a
checker candidate.

## Runtime Files

- Rule model: `OmniflowCheckerRule.kt`
- Rule execution: `OmniflowStepExecutor.kt`
- Function patching: `OobOmniFlowToolkitService.kt`
