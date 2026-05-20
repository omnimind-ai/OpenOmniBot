# Adapter Protocol

ProjectArtifactBench is runtime-agnostic. An adapter turns the generic benchmark
protocol into checks for a concrete project runtime such as OOB Workbench,
React/Vite, Flutter, Electron, or another product builder.

An adapter should define:

- Artifact layout: required files and directories for candidate output.
- Builder baseline: optional deterministic builder for contract fixtures.
- Contract checker: schema and API/tool validation.
- Runtime checker: executable API/state checks.
- UI checker: browser or app interaction checks.
- Export checker: package/import/manifest checks when applicable.
- Safety checker: forbidden paths, forbidden strings, and collateral damage.

The runner currently implements shared static checks plus a phase-one
`oob_workbench` detector for contract validity, native runtime smoke, HTML/UI
smoke, structural export checks, and collateral-damage scanning. Stronger
runtime/UI/export plugins can replace or extend those checks while preserving
the same metric contract.

## Candidate Directory Contract

Every adapter receives candidate output as:

```text
<candidate-root>/
  <case-id>/
    ...
```

The adapter owns the layout under `<case-id>`, but should document the expected
files. OOB Workbench currently expects files such as:

```text
project.json
frontend/html/index.html
PROJECT_CONTEXT.md
PROJECT_SOUL.md
export_manifest.json
```

## Checker Result Contract

Checker plugins should return machine-readable metrics:

```json
{
  "metric_id": {
    "status": "pass",
    "score": 1,
    "failures": []
  }
}
```

Use `status = "na"` and `score = null` when a check is not applicable to a case.
The primary metric remains `case_resolved`, which passes only when every
required checker group passes.
