# PABench Checker Guide

ProjectArtifactBench uses layered checkers. The goal is to evaluate whether a
candidate is a runnable, maintainable project artifact, not whether it merely
contains plausible text.

## How to Run

Self-check case definitions and adapter baselines:

```bash
python3 benchmarks/project_artifact/run_benchmark.py
```

Evaluate candidate artifacts:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --candidate-root /path/to/candidates
```

Candidates must be laid out by case id:

```text
/path/to/candidates/
  create_habit_checkin_crud/
    project.json
    frontend/html/index.html
    PROJECT_CONTEXT.md
    PROJECT_SOUL.md
```

Use JSONL for dashboards or CI:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --candidate-root /path/to/candidates --jsonl
```

Debug one case:

```bash
python3 benchmarks/project_artifact/run_benchmark.py \
  --case create_habit_checkin_crud \
  --candidate-root /path/to/candidates
```

## Checker Layers

### 1. Case Readiness

Metrics:

- `case_schema_valid`
- `fixture_paths_valid`
- `builder_baseline_valid`
- `case_ready`

These checks validate benchmark authoring quality. They do not score an
external candidate.

### 2. Static Candidate Checks

Metrics:

- `candidate_available`
- `candidate_required_files_present`
- `candidate_required_api_ids_present`
- `candidate_required_html_bindings_present`
- `candidate_required_json_values_match`
- `candidate_forbidden_paths_absent`
- `candidate_forbidden_substrings_absent`

These checks verify explicit oracle requirements such as required files, API
ids, HTML bridge calls, JSON pointer values, forbidden paths, and retired
adapter identifiers.

### 3. Adapter Contract Check

Metric:

- `adapter_contract_valid`

For `oob_workbench`, this checks:

- `project.json` is a JSON object.
- project id and name exist.
- APIs/tools are registered.
- API ids use `<entity>.<verb>` format.
- API ids are unique.
- `run.use` is present and supported.
- input/output schemas are objects when present.
- HTML `window.oob.callApi(...)` ids are registered.
- agent APIs declare non-empty capabilities.
- required docs are present and non-trivial.

### 4. Runtime Smoke Check

Metric:

- `runtime_execution_pass`

For `oob_workbench`, the current zero-dependency runtime smoke simulates
`native.collection.*` APIs using their schemas:

- create inserts an item.
- update modifies an existing item.
- archive marks an item archived.
- list/get are checked for basic state consistency.

This is intentionally a smoke check. The next adapter layer should call the real
portable runtime (`workbench_runtime.py`) for stronger execution guarantees.

### 5. UI Smoke Check

Metric:

- `ui_interaction_pass`

For `oob_workbench`, the current UI smoke checks:

- HTML exists and has a visible container.
- stable `data-oob-id` attributes exist.
- `window.oob.getProject(...)` is used.
- `window.oob.onProjectUpdated(...)` is registered.
- inline non-module scripts parse with Node.js when Node is available.

The next layer should use Playwright with a mocked `window.oob` bridge to click
controls and verify state changes.

### 6. Export/Migration Check

Metric:

- `export_roundtrip_pass`

For `oob_workbench`, the current structural export check verifies:

- `export_manifest.json` exists for export/migration cases.
- canonical `oob-project` metadata is used.
- retired skill ids do not appear.
- manifest entries reference present files, excluding packaged skill paths.

The next layer should export to an archive, import into a clean workspace, and
run post-import contract/runtime checks.

### 7. Safety and Collateral Damage Check

Metric:

- `collateral_damage_free`

This check scans candidate artifacts for:

- symlinks that escape the candidate root
- private-key blocks
- secret-looking assignments
- release signing variables

It complements case-specific forbidden path and forbidden substring checks.

## Primary Score

The primary metric is:

```text
case_resolved = all applicable candidate checker groups pass
```

`case_ready` is only a benchmark authoring self-check. A case can be ready even
when no candidate has been evaluated.

## Interpreting Failures

- Contract failure: fix project metadata, API ids, schemas, docs, or HTML/API
  consistency.
- Runtime failure: fix API schemas and native collection action wiring.
- UI failure: ensure the HTML is not a static mock and uses the runtime bridge.
- Export failure: fix manifest source, skill metadata, and packaged entries.
- Safety failure: remove forbidden files, credentials, escaping symlinks, or
  unrelated repository mutations.

## Current Limits

The checker is already richer than substring-only evaluation, but it is still a
phase-one detector. Stronger paper-grade evaluation should add:

- real adapter runtime execution
- Playwright UI interaction tests
- export/import roundtrip
- hidden oracle cases
- repeated runs for nondeterministic agents
