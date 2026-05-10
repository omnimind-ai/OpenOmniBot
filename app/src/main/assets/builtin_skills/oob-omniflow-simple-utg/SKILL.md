---
name: oob-omniflow-simple-utg
description: Use OOB-local OmniFlow Simple UTG when a project workflow should reuse GUI/VLM execution traces as local functions. Records VLM GUI run logs, compiles simple replayable actions, and executes provider-first with local replay fallback.
---

# OOB OmniFlow Simple UTG

Use this skill for Project-mode GUI workflows that should get cheaper or faster after one successful VLM run.

## Scope

- Record OOB VLM GUI task run logs.
- Convert supported GUI actions into local Simple UTG functions.
- Execute provider-first through the OmniFlow-compatible local provider.
- Fall back to local replay when the provider is unavailable or misses.

## Supported Actions

- `open_app`
- `click`
- `long_press`
- `input_text`
- `swipe`
- `press_key`
- `wait`
- `finished`

Do not use this skill for all-tool traces, MCP replay, VLM extractor compilation, or cross-user sharing. Those are outside the v1 Simple UTG boundary.

## Execution Policy

1. Prefer provider matching and controlled execution through `127.0.0.1:9417`.
2. If provider execution misses or fails, replay the local Simple UTG function in order.
3. Keep function state project-scoped to `oob-simple-utg`.
4. Treat screenshots and XML as runtime context, not as shareable assets in this v1 implementation.
