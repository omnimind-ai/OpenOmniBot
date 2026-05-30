# OOB RunLog -> UDEG -> Key Function Research

Status: Research conclusion / implementation design
Last Updated: 2026-05-31

This note records the current research conclusion for turning OOB RunLogs into
UDEG graph evidence and reusable key Functions. It is intentionally
implementation-oriented: the goal is to decide what OOB should build next, not
to mirror the external OmniFlow Python stack one-to-one.

## Executive Summary

- OOB v1 should keep `Function` as the only product-facing reusable asset and
  let the enhancement agent add simple `key_action` annotations to the saved
  Function. Do not introduce a separate key Function store or split every
  action into a callable Function for the first version.
- A RunLog should be treated as lossless graph evidence, not as only one
  monolithic reusable Function.
- Every replayable action in a RunLog can become a raw action edge in the UDEG
  evidence graph.
- Callable Functions should be promoted conservatively from that evidence. The
  core callable asset should be the key Function: a semantic segment that
  carries task effect.
- Non-key actions should default to navigation/reach evidence. They do not need
  precise semantic classification and should not pollute the Function store.
- Navigation correctness should come from UI graph state matching, page vectors,
  source context, and action transfer. It should not rely on natural-language
  semantic recognition.
- Direct replay remains useful inside a matched corridor, but it is not the
  primary truth source. When the live state is unknown or ambiguous, OOB should
  abstain with recovery/fallback instead of blindly replaying.

Recommended OOB design:

```text
RunLog
  -> one Function asset
  -> enhancement agent adds key_action/action_role metadata after save
  -> replay can later choose a safe entry boundary from src_ctx/dst_ctx
  -> non-key route steps are skippable only when later state evidence matches
```

The broader raw-edge/key-Function graph design remains a research direction,
but it is not the first OOB implementation step.

## Paper Alignment

The OmniFlow paper already supports this direction. The relevant claims are:

- Successful `run_logs` are organized into UDEG nodes and Function edges.
- A Function is a replayable tool API synthesized from a successful task
  segment, not necessarily a full trajectory.
- UDEG node matching recalls Function segments around the current state.
- Most repeated GUI tasks contain a small number of key Functions that determine
  the task result; surrounding steps mainly move the device into a valid
  execution position or recover from runtime variation.
- Flow executes a recalled Function through one observe-check-transfer-act
  stack. It reaches the executable position, executes key semantic actions, and
  performs local recovery when needed.
- The paper should not foreground dynamic programming or step-index mechanics.
  Those are implementation details. The paper-level point is trace
  factorization into executable semantic Function edges.

Paper wording that matches the intended OOB design:

> OmniFlow does not treat every recorded action as equally reusable. Accepted
> run logs are written back as UDEG evidence: all actions preserve source and
> destination state evidence, while only task-carrying key segments are promoted
> into callable Function edges. Navigation-only prefixes remain routing evidence
> that Flow can skip, reconstruct, or recover from when the current state already
> satisfies the next key Function's executable position.

## External OmniFlow Code Findings

The local Python implementation has useful pieces, but its current import path
does not fully implement the proposed OOB design.

- `src/integrations/utg_api/_import.py`
  - Imports one canonical run log into one Function-like asset.
  - Preserves concrete actions, including expanded `call_function` actions.
  - Updates source run ids when a run reused an existing Function.
  - Writes imported Functions back into the runtime/compiler cache.
  - It does not by default split one RunLog into multiple key Functions during
    import.

- `src/utg/assets/function_store.py`
  - `import_function(...)` is explicitly one RunLog to one Function.
  - `split_function(...)` can ask an LLM to split a long Function into shorter
    semantic units, but that is a separate operation and is not the core import
    path.

- `src/utg/runtime/utg_runtime.py`
  - `run_function(...)` supports global and node-scoped Function lookup.
  - It enforces a Function start-node precondition before execution.
  - If the current node differs from the Function start node, it can try a
    route-only bridge to satisfy the precondition.
  - It does not automatically infer that the current state is midway through a
    Function and skip arbitrary prefixes.

- `src/utg/execution/executor.py`
  - Executes a Function through an observe-before-action loop.
  - Supports recursive `call_function`.
  - Checks nested `call_function` node preconditions before executing child
    Functions.
  - Aborts on missing child Functions, recursion cycles, or unsatisfied node
    preconditions.

- `src/integrations/utg_api/_merge.py`
  - Supports compose-style merge using `call_function`.
  - Bridges gaps between child Functions through shortest route-only paths when
    node endpoints are known.

Useful ideas to borrow:

- Node precondition before executing a Function.
- `call_function` as the programmable composition primitive.
- Compose merge as cached Function-path materialization.
- Conservative abort/fallback when a precondition or child Function is missing.

Ideas not to copy directly:

- LLM-only split as the default Function extraction mechanism.
- One RunLog to one Function as the only write-back artifact.
- Prefix skipping based only on step index.

## OOB Current Capability Map

OOB already has a substantial native foundation.

- `OobRunLogReplayService.convertRunLog(...)`
  - Loads a successful `InternalRunLogRecord`.
  - Compiles it into one `oob.reusable_function.v1` spec via
    `RunLogReusableFunctionCompiler.compile(...)`.
  - Registers that spec into Workspace, local registry, and UDEG when requested.

- `RunLogReusableFunctionCompiler`
  - Converts RunLog cards into replayable steps.
  - Infers deterministic `input_text` parameters.
  - Preserves `source_context` when cards provide `src_ctx/dst_ctx`.
  - Emits a full tool sequence as one Function spec.

- `OobUdegNodeStore.upsertFunction(...)`
  - Extracts a Function source page.
  - Encodes it into an OOB-native PageVectorSet.
  - Merges or creates a page node.
  - Attaches the Function summary to that node.
  - Builds a node skill-like decision context for online VLM/tool decisions.

- `OobUdegNodeStore.upsertFunctionSegmentNodes(...)`
  - Reads each step's `src_ctx/dst_ctx`.
  - Encodes those pages into nodes.
  - Attaches Function segment summaries to the matched nodes.
  - This is already close to a suffix-recall mechanism, but it is still tied to
    the parent Function and `start_step_index`.

- `OobOmniFlowToolkitService.recall(...)`
  - Fresh reads or accepts current XML/package.
  - Page-matches the current screen to UDEG nodes.
  - Ranks only Functions/segments attached to the matched node.
  - Does not globally scan all Functions as the primary decision path.

- `OobFunctionToolHandler`
  - Runs materialized Functions locally.
  - Supports nested `call_function` steps and explicit tool cards.
  - Fails locally without VLM fallback when fallback is disabled.
  - Supports a `go_to_node` graph step in tests.

- `OmniflowStepExecutor`
  - Executes primitive OOB/OmniFlow steps.
  - Uses current page reads and action-transfer/checker diagnostics.
  - Returns structured failure instead of silently inventing fallback behavior.

Main gaps:

- OOB does not yet store every RunLog action as a durable raw action edge.
- OOB does not yet extract durable key Functions from a single RunLog.
- Current segment summaries are suffix hints, not first-class key Functions.
- Current Function registration still treats the full trace as the primary
  reusable Function.
- Current UDEG edges export function/segment references, but not a complete
  raw action-edge graph with endpoint metadata.
- There is no current key Function progress model: "next key Function",
  "corridor to next key", and "effect already completed" are not implemented.

## Candidate Design Comparison

| Design | Description | Reuse | Correctness | Store noise | UI clarity | Complexity | Paper fit |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Baseline full Function | RunLog -> one full Function | Low-mid | Medium; can over-replay prefixes | Low | Simple | Existing | Partial |
| Split-all | Every action becomes callable Function | High theoretical | Low; too many weak actions | Very high | Poor | High | Poor |
| Proposed key Functions | Every action becomes raw edge; only key actions become callable Functions | High practical | High if conservative | Low-mid | Good | Medium | Strong |
| Hybrid full + key | Preserve full Function fallback plus key Functions | Highest | High if key path strict | Medium | Good if UI filters | Medium-high | Strong |

Recommended long-term design: Proposed key Functions with an optional
full/composite fallback artifact. Product v1 should stop earlier: keep the full
Function asset and mark key actions inside it, so future state-aligned replay can
skip rigid prefixes without changing the user-facing Function model.

## Recommended Data Model

### Raw Action Edge

Each replayable action from a RunLog becomes evidence:

```json
{
  "schema_version": "oob.udeg.raw_action_edge.v1",
  "edge_id": "edge_<run_id>_<step_index>",
  "source_run_id": "run_123",
  "step_index": 3,
  "from_node_id": "node_before",
  "to_node_id": "node_after",
  "action": "click",
  "args": {},
  "source_context": {
    "src_ctx": {},
    "dst_ctx": {}
  },
  "target_evidence": {},
  "effect_observation": {},
  "source": "runlog_action_edge"
}
```

This edge is not a callable Function. It is evidence for navigation, transfer,
recovery, and key Function extraction.

### Key Function

Only semantic task-carrying segments become callable:

```json
{
  "schema_version": "oob.reusable_function.v1",
  "function_id": "add_contact__save_contact",
  "function_kind": "key_semantic",
  "key_function": true,
  "parent_run_id": "run_123",
  "parent_function_id": "add_contact_full",
  "key_action_indices": [6],
  "reach_action_indices": [4, 5],
  "from_node_id": "node_contact_form_filled",
  "to_node_id": "node_contact_saved",
  "precondition_signature": {},
  "effect_signature": {},
  "effect_summary": "Save the contact",
  "execution": {
    "kind": "tool_sequence",
    "steps": []
  }
}
```

### Navigation Evidence

Navigation should stay attached to the key Function it serves:

```json
{
  "next_key_function_id": "add_contact__save_contact",
  "corridor_node_ids": ["node_form", "node_form_scrolled"],
  "reach_action_edge_ids": ["edge_run_123_4", "edge_run_123_5"]
}
```

Navigation evidence is not shown as a normal Function and should not be used as
task-completion proof.

## Key Function Extraction Policy

Use semantic recognition only to identify key Functions, not to drive
navigation.

High-confidence key signals:

- `input_text` into a task-relevant field.
- Toggle/select changes with visible selected/checked state.
- Save/submit/send/create/delete/confirm actions.
- Business record appears or disappears in `dst_ctx`.
- Terminal completion state appears after the action.
- A previously incomplete form or setting becomes complete.

Default non-key:

- `open_app`
- back/home
- pure scroll
- tab/menu/list navigation
- wait/loading
- repeated accessibility noise
- dialog dismissal and keyboard hide

Non-key actions are still stored losslessly as raw action edges. They are simply
not promoted to callable Functions unless explicitly needed later.

Conservative rule:

- If key classification is uncertain, keep the action as raw edge evidence.
- It is acceptable to miss some key Function extraction initially; future
  successful RunLogs can add better key Functions.
- It is not acceptable to expose a weak action as a callable semantic Function
  that can be directly executed.

## Runtime Semantics

Execution should focus on the next unfinished key Function.

```text
next_key = first unfinished key Function
```

Before executing:

- If current state matches `next_key.precondition`, execute the key Function.
- If current state is inside `next_key`'s navigation corridor, use raw action
  evidence or graph navigation to reach the precondition.
- If current state matches `next_key.effect_signature`, mark it completed and
  move to the next key Function.
- If current state is unknown or ambiguous, return recovery/fallback instead of
  replaying blindly.

Skipping non-key work:

- Non-key Functions/actions are skipped only when the current state already
  satisfies the next key Function's precondition.
- Do not skip across a key Function unless its effect is structurally proven.
- Do not treat matching a later page as proof that an earlier key Function
  succeeded.

Direct replay:

- Valid inside a matched corridor when the source page and live page have strong
  evidence and action transfer produces a strong target.
- Invalid as a global truth source when current state is unknown, off-corridor,
  or ambiguous.

## Proposed OOB Implementation Steps

1. Add a raw action-edge builder for compiled RunLog steps.
   - Input: `RunLogReusableFunctionCompiler` steps with `source_context`.
   - Output: `oob.udeg.raw_action_edge.v1` records.
   - Persist under `OobUdegNodeStore` or a small adjacent store.

2. Extend UDEG node store with raw edge adjacency.
   - Save outgoing/incoming raw edges per node.
   - Keep raw edges out of normal Function recall payloads by default.

3. Add a key Function extractor.
   - Deterministic v1: classify obvious semantic actions using action type,
     target text, target role, `src_ctx/dst_ctx` state change, and terminal
     evidence.
   - Optional AI enhancement can produce labels later, but deterministic
     evidence remains the execution gate.

4. Register extracted key Functions.
   - Use existing `registerFunctionSpec(...)`.
   - Mark `function_kind=key_semantic`, `key_function=true`.
   - Store `parent_run_id`, `key_action_indices`, `reach_action_indices`,
     `from_node_id`, `to_node_id`, and `effect_signature`.

5. Preserve full trace separately.
   - Keep full RunLog available for audit and full replay.
   - Optionally create a full/composite Function only when explicitly requested
     or when needed for backward compatibility.

6. Add key-aware recall metadata.
   - Current node recall returns key Functions attached to the node.
   - Navigation evidence can be included only in debug/internal payloads or as a
     bounded "reach evidence" summary.

7. Add key-aware execution diagnostics.
   - `next_key_function_id`
   - `matched_precondition`
   - `matched_effect`
   - `used_navigation_edge_ids`
   - `skipped_non_key_count`
   - `fallback_reason`

## Core Module Impact

- `RunLogReusableFunctionCompiler`
  - Keep existing full Function compilation.
  - Expose or preserve enough structured per-step context for raw edge
    extraction.

- `OobRunLogReplayService`
  - Add an enhanced conversion path:
    `convertRunLog -> full spec -> raw edges -> key Function specs -> register`.
  - Return counts for raw edges and key Functions.

- `OobUdegNodeStore`
  - Store raw action edges and adjacency.
  - Continue storing node page vectors and skill context.
  - Attach key Functions as normal callable Function summaries.

- `OobOmniFlowToolkitService`
  - Add or extend tool payloads for enhanced conversion diagnostics.
  - Keep normal recall focused on node-attached callable Functions.

- `OobFunctionToolHandler`
  - Reuse existing runner.
  - No separate executor should be introduced.
  - Future key-aware execution can be implemented as pre-run planning plus the
    same materialized runner.

- UI
  - Show key Functions as "复用指令".
  - Show raw edges only in details/debug views.
  - Avoid listing every action as a user-facing Function.

## Test Matrix

### Unit

- Successful RunLog with 7 replayable actions emits 7 raw action edges.
- Pure navigation actions are not registered as key Functions.
- `input_text`, toggle, save/submit actions are promoted when they have
  reliable source/destination state evidence.
- Ambiguous or missing `source_context` actions remain raw evidence only.
- Key Function specs contain `parent_run_id`, `key_action_indices`,
  `reach_action_indices`, `from_node_id`, and `to_node_id`.
- Key Function registration attaches it to the correct UDEG node.
- Normal recall excludes raw edges from user-facing Function candidates.

### Integration

- Convert a real RunLog into raw edges plus key Functions.
- From a screen already matching the next key Function precondition, execute
  only that key Function and skip non-key navigation.
- From a screen in the corridor to the next key Function, execute or transfer
  the needed raw navigation edges, then execute the key Function.
- From a screen matching a key Function effect, mark it complete and move to the
  next key Function.
- From an unknown page, return recovery/fallback; do not blindly replay.
- Existing full Function replay remains available when explicitly requested.

### Regression

- Existing `convertRunLog(register=true)` behavior remains compatible unless the
  enhanced path is explicitly enabled or product policy switches over.
- Existing nested `call_function` tests keep passing.
- Existing UDEG page-match recall tests keep passing.
- Direct replay failures still return structured errors and do not silently call
  VLM when fallback is disabled.

## Final Recommendation

Adopt a staged path that keeps the paper and product surface simple:

1. Stage 1: convert RunLog to a normal Function, then let the enhancement agent
   add simple `key_action` metadata to the saved Function. Keep replay behavior
   unchanged.
2. Stage 2: add an entry resolver that uses current page evidence plus
   `src_ctx/dst_ctx` to start a Function from the earliest safe step.
3. Stage 3: only if needed, persist raw action edges and promote durable key
   Functions as a deeper UDEG graph optimization.

This gives OOB the immediate fix direction for rigid replay without introducing
multiple executor paths or over-relying on semantic recognition. The runtime
remains grounded in UDEG page match, action transfer, and conservative fallback.
