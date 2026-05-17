# Agent Stream Pipeline

How OmniBot renders a live AI agent run — from raw LLM tokens to grouped activity cards in the UI.

---

## Overview

Most agent UIs do the simplest thing: one tool call → one card. That works at demo scale. At real scale — a browser session with 30 sub-actions, a terminal job that retries twice, a VLM pipeline that takes 3 shots — it produces a wall of noise the user can't parse.

The OmniBot stream pipeline is designed around one principle: **the UI should represent what the agent is *doing*, not what the agent is *calling*.**

```
LLM tokens
    │
    ▼
[AgentLlmStreamAccumulator]      — strips inline markup (<tool_call>, <function=...>)
    │                              keeps visible text clean as tokens arrive
    │
    ▼
[AgentStreamEventBatcher]        — Choreographer vsync-aligned flush
    │                              collapses O(token-rate) events → ≤60 IPC calls/sec
    │                              coalesces same-frame text/thinking snapshots
    │
    ▼  MethodChannel (batched)
    │
    ▼
[AgentStreamReducer]             — pure state machine, no side effects
    │                              tracks phase, thinking rounds, active tool IDs
    │
    ▼
[AgentActivityCompactor]         — display-only, non-destructive
    │                              groups consecutive same-kind tool calls
    │                              into typed Activity items
    ▼
Activity cards  /  Message bubbles  /  Thinking cards
```

---

## Design Principles

### 0. Stream events use one trace envelope

Native emits `schema_version=oob.agent_event.v1` on every agent stream event.
The legacy `kind`, `taskId`, `seq`, and `createdAt` fields remain for current UI
compatibility, while the envelope adds `trace_id/run_id`, `span_id`,
`parent_span_id`, `channel`, `event`, `timestamp_ms`, and `status`.

Treat UI messages, activity cards, and RunLog timelines as projections of this
same event stream. Do not add a second event contract for a new view.

### 1. Each layer has exactly one job

| Layer | Responsibility | Does NOT do |
|---|---|---|
| Accumulator | Parse raw bytes into structured events | Know about UI state |
| Batcher | Rate-limit IPC | Understand event semantics |
| Reducer | Track stream state machine | Touch the widget tree |
| Compactor | Group events for display | Mutate or delete messages |
| Cards | Render one activity | Know about stream state |

Violations of this split are bugs, not features.

### 2. The compactor is display-only

`compactAgentProcessItems` never deletes or mutates underlying `ChatMessageModel` records. Those are the source of truth for RunLog, replay, and history. The compactor only decides *how to present* the existing data.

This means you can always reconstruct the raw event sequence from the message list, regardless of how the UI groups them.

### 3. Batching preserves terminal correctness

The batcher may replace older same-frame `text_snapshot`,
`thinking_snapshot`, and non-terminal `tool_progress` payloads for the same
entry. Terminal output deltas, tool completion, errors, permission blocks, and
run completion are not coalesced. Terminal states call `flushNow()` so the UI
drops running indicators without waiting for another frame.

### 4. Copy-on-modify in the reducer

The reducer allocates new collections only in the branch that actually modifies them. For terminal events (`completed`, `error`, `clarifyRequired`, `permissionRequired`) the previous thinking/tool maps are reused by reference — only `activeToolEntryIds` is replaced with `const <String>{}`.

This keeps the reducer fast at high event rates without sacrificing immutability.

### 5. Memoize at the boundary where rebuild pressure is highest

`AgentActivityCompactor` is a stateful class (not a pure function) specifically so it can be owned by the `State` that triggers the most rebuilds. Since the message list is append-only during a run, the cache check is O(1): compare `length` and `last.id`. Hits are free; misses pay the full O(n) only when new messages arrive.

### 6. One enum drives the whole grouping contract

`AgentToolActivityKind` is the single point of truth for "what counts as the same activity." Adding a new tool type means adding one enum value and filling the corresponding switch arms — the batching, state tracking, and rendering all follow automatically.

---

## Extending the Pipeline

### Adding a new tool kind

Scenario: you want `calendar_*` tools to group into a **Calendar activity** instead of rendering as isolated raw cards.

**Step 1 — declare the kind**

```dart
// ui/lib/features/home/pages/chat/utils/agent_activity_compactor.dart
enum AgentToolActivityKind {
  browser,
  research,
  terminal,
  workspace,
  workbench,
  mcp,
  calendar,  // ← add here
}
```

**Step 2 — route tool names to the kind**

```dart
AgentToolActivityKind? _resolveActivityKind(Map<String, dynamic> cardData) {
  final toolType = (cardData['toolType'] ?? '').toString().trim();
  final toolName = (cardData['toolName'] ?? '').toString().trim();
  // ...existing entries...
  if (toolType == 'calendar' ||
      toolName.startsWith('calendar_') ||
      toolName == 'alarm_reminder_create') {
    return AgentToolActivityKind.calendar;
  }
  return null;
}
```

**Step 3 — define the grouping key**

Tools with the same `_activityKey` get merged into one card. Calendar operations in the same task should usually merge (they're all part of one scheduling action).

```dart
case AgentToolActivityKind.calendar:
  return '$taskId|calendar';
```

**Step 4 — extract action and target for step display**

```dart
// _resolveAction
case AgentToolActivityKind.calendar:
  return toolName.startsWith('calendar_')
      ? toolName.substring('calendar_'.length)  // "event_create" etc.
      : toolName;

// _resolveTarget
case AgentToolActivityKind.calendar:
  return _firstNonBlank([
    args['title'],
    args['startDate'],
    cardData['summary'],
    cardData['progress'],
  ]);

// _actionKey (used for retry detection)
case AgentToolActivityKind.calendar:
  return _normalizeKey('$action|$target');

// _activityKindLabel
case AgentToolActivityKind.calendar:
  return 'Calendar activity';
```

**Step 5 — add a renderer**

In `AgentToolActivityCard` (or its factory), add a branch for `AgentToolActivityKind.calendar` that picks the right icon, color, and step-list format. The data (`activity.steps`, `activity.status`, `activity.stepCount`) is already populated by the compactor.

That's the complete extension. The batcher, reducer, and memoization cache require no changes.

---

### Adding a new stream event kind

Scenario: the backend starts emitting a `human_in_loop` event when the agent needs user confirmation.

**Step 1 — add to the event enum**

```dart
// ui/lib/models/agent_stream_event.dart
enum AgentStreamEventKind {
  // ...existing...
  humanInLoop('human_in_loop'),
  ;
  const AgentStreamEventKind(this.value);
  final String value;
}
```

**Step 2 — handle it in the reducer**

Decide which `AgentStreamPhase` this maps to. For human-in-loop, `clarify` is the right fit — it's already handled by `ChatPage` to show a confirmation UI.

```dart
// ui/lib/services/agent_stream_reducer.dart
case AgentStreamEventKind.humanInLoop:
  phase = AgentStreamPhase.clarify;
  thinkingStage = 4;
  isDeepThinking = false;
  clearActiveThinkingEntryId = true;
  activeThinkingEntryId = null;
  activeToolEntryIds = const <String>{};
  break;
```

The reducer only updates state machine fields. Any payload the event carries (e.g., a confirmation prompt) lives in `event.raw` and is read by the UI layer that reacts to `AgentStreamPhase.clarify`.

---

### Adding a new card type (non-tool)

Scenario: the agent emits a `decision_summary` card showing its reasoning before a major action.

This doesn't touch the stream pipeline at all. Cards are a separate channel:

1. The backend includes `{ "type": "decision_summary", ... }` in the event payload.
2. `extractAgentStreamUiCards` in `agent_stream_meta.dart` picks it up generically.
3. `CardWidgetFactory` maps `type → Widget`. Add your card widget there.

No changes to compactor, reducer, or batcher.

---

## Why not just use a generic event bus?

Three reasons:

**Correctness under reorder.** The reducer's `event.seq <= previousState.lastSeq` guard makes late or duplicate events safe to drop. A generic bus has no such contract.

**Predictable render cost.** The batcher's vsync alignment gives a hard upper bound on IPC calls per second regardless of LLM speed. An unbounded bus turns a fast model into UI jank.

**Auditable grouping.** Because the compactor is a pure transformation of the persisted message list, the grouping logic can be unit-tested without any Flutter infrastructure. See `agent_activity_compactor_test.dart` for examples. A bus-based approach would require simulating the full event timeline in every test.

---

## Files

| File | Role |
|---|---|
| `app/.../AgentLlmStreamAccumulator.kt` | Token-level parsing, inline markup stripping |
| `app/.../AgentStreamEventBatcher.kt` | Vsync-aligned IPC batching |
| `ui/lib/services/agent_stream_reducer.dart` | Stream state machine |
| `ui/lib/services/agent_stream_meta.dart` | Card ID resolution, UI card extraction |
| `ui/lib/features/.../agent_activity_compactor.dart` | Display-layer grouping + memoization |
| `ui/lib/features/.../agent_tool_activity_card.dart` | Activity card renderer |
| `ui/lib/features/.../tool_activity_utils.dart` | Tool card identity, stale placeholder detection |
| `ui/test/.../agent_activity_compactor_test.dart` | Compactor unit tests |
| `ui/test/services/agent_stream_reducer_test.dart` | Reducer unit tests |
