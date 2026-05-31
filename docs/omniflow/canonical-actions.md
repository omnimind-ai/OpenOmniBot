# Canonical Actions

OOB Function runtime has one compact action vocabulary. Conversion,
enhancement, `update_function`, UDEG indexing, and execution should all parse
steps through `OobActionCodec`.

## Main Path Actions

```text
click
long_press
input_text
swipe
open_app
press_key
finished
```

These are the only deterministic local replay actions that should be written
into new Function steps.

## Legacy Aliases

Legacy aliases are compatibility input only. They should not be written by new
code.

```text
tap, click_at, click_element -> click
type, type_text, set_text -> input_text
scroll, scroll_down, scroll_up, scroll_left, scroll_right -> swipe
back, press_back, press_back_button -> press_key { key: back }
home, press_home, press_home_button -> press_key { key: home }
hot_key, key_event -> press_key
launch_app, openapp -> open_app
finish, done, complete -> finished
```

## Non-Actions

The following should not become main-path Function actions:

```text
wait
vlm_task
screenshot
screen_capture
assistant_response
status_update
get_state
```

`wait` can be represented as checker delay or cleanup metadata. Perception-only
steps should either be dropped as wrappers when a concrete action exists, or
kept as agent fallback steps when live perception is required.

## Code Owners

- Parser and args summary: `OobActionCodec.kt`
- Runtime executor: `OmniflowStepExecutor.kt`
- UDEG edge indexing: `OobUdegNodeStore.kt`
- Replay alignment: `PendingActionStack.kt`

Do not add new action aliases outside `OobActionCodec`.
