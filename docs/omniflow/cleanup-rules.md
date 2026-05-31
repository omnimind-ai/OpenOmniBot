# Cleanup Rules

RunLog conversion and offline enhancement should remove deterministic noise and
merge duplicates before a Function is reused.

## Drop

```text
wait-only cards
assistant/status/get_state cards
failed cards without a state transition
perception wrappers replaced by a concrete action
```

## Merge

```text
same-target repeated input_text -> keep final text action
same-target repeated click with no state change -> keep one click
multiple wrapper cards around one recorded UI action -> keep the UI action
```

## Convert To Checker

```text
close ad or coupon overlay -> overlay_blocking + dismiss
permission allow -> permission_dialog + allow
hide keyboard -> keyboard_obscuring + hide_keyboard
return to expected app -> package_mismatch + open_app
```

## Annotation Requirement

Every retained executable step should explain:

```text
title
description or action_purpose
importance
cleanup_action
cleanup_reason
```

Noise and merge decisions should be visible in metadata or enhancement results
so the frontend and agent can show what changed.
