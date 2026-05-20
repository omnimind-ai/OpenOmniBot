# OOB Workbench Adapter

`oob_workbench` is the first ProjectArtifactBench adapter. It grounds the
general benchmark in this repository's Workbench Project runtime.

## Why This Adapter Exists

OOB Workbench already has:

- a canonical Project skill (`oob-project`)
- a deterministic ProjectContract builder
- portable Workbench runtime scripts
- export metadata
- known regression patterns around retired Project skill ids

This makes it useful as a reference adapter while keeping PABench itself
runtime-neutral.

## Builder Baseline

Contract-backed create cases can use:

```text
app/src/main/assets/builtin_skills/oob-project/scripts/build_project_from_contract.py
```

The benchmark runner invokes it when a case has `adapter = "oob_workbench"` and
`input.contract_fixture`.

## Artifact Layout

Candidate outputs should use:

```text
<case-id>/
  project.json
  frontend/html/index.html
  PROJECT_CONTEXT.md
  PROJECT_SOUL.md
  export_manifest.json      # export cases only
```

## Adapter-Specific Oracle Examples

OOB Workbench checks include:

- APIs are exposed through `project.json`.
- HTML calls `window.oob.callApi(...)`.
- HTML registers `window.oob.onProjectUpdated(...)`.
- Export metadata uses canonical `oob-project`.
- Retired skill ids such as `oob-project-distiller`,
  `oob-project-designer`, and `oob-native-workbench` do not appear.
- Safety cases do not create `.github/**`, `AGENTS.md`, keystores, `.env`, or
  signing files.

## Planned Checker Layers

The next adapter implementation should add:

- `validate_project.py` contract checks.
- `workbench_runtime.py` API/state execution checks.
- Playwright UI checks with a mocked `window.oob` bridge.
- Export/import roundtrip checks.
- Repository-level collateral damage checks.
