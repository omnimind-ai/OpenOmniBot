# ProjectArtifactBench

ProjectArtifactBench, or PABench, evaluates whether LLM agents can turn
natural-language requirements into runnable, maintainable, and verifiable
software project artifacts under executable contracts.

The benchmark is runtime-agnostic by design. `oob_workbench` is the first
adapter, because this repository already has an OOB Workbench runtime, Project
builder, export metadata, and Project-mode regressions to test. Future adapters
can add their own artifact schema and runtime checks without changing the
benchmark objective.

The first version intentionally stays lightweight:

- JSON case files under `cases/`.
- Reusable fixtures under `fixtures/`.
- Adapter documentation under `adapters/`.
- A dependency-free runner, `run_benchmark.py`.
- Programmatic checks for schema, fixtures, generated contract baseline output,
  static candidate artifacts, forbidden paths, and forbidden substrings.

It is not a full Android or browser benchmark yet. The next step is to connect
these cases to Workbench runtime execution and Playwright UI checks.

## Abstraction

PABench has three layers:

- **Core schema**: task prompt, input fixtures, capabilities, oracle groups, and
  metric output.
- **Adapter protocol**: runtime-specific artifact layout and checker plugins.
- **Reference adapter**: `oob_workbench`, the first executable adapter in this
  repository.

The OOB details are deliberately adapter-specific. The benchmark question is
broader: can an agent synthesize and evolve runnable software project artifacts
under executable contracts?

## Run

Validate the benchmark definitions and run builder baselines for creation cases:

```bash
python3 benchmarks/project_artifact/run_benchmark.py
```

Validate candidate outputs for the same five cases:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --candidate-root /path/to/results
```

The candidate root should contain one directory per case id, for example:

```text
/path/to/results/
  create_habit_checkin_crud/
    project.json
    frontend/html/index.html
    PROJECT_CONTEXT.md
  update_counter_preserves_behavior/
    project.json
    frontend/html/index.html
    PROJECT_CONTEXT.md
```

Use JSONL output for CI ingestion:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --jsonl
```

Run only one adapter:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --adapter oob_workbench
```

The default run reports whether cases are `case_ready`: schemas, fixtures, and
contract-backed builder baselines are valid. A run with `--candidate-root`
reports `case_resolved`, the real benchmark pass/fail metric for candidate
artifacts.

## Skill Template

`skills/project-artifact-engineering/SKILL.md` is the agent-facing template for
PABench tasks. It defines the expected workflow for create, update,
agent-backed, export/migration, and safety cases. Use it as the fixed harness
skill in controlled model comparisons, or as the reference template in
agent-system comparisons.

## Metrics

`metrics.json` defines the primary and supporting metrics. The primary
benchmark metric is `case_resolved` when evaluating candidates. Supporting
metrics identify whether failures come from artifact completeness, API contract,
runtime grounding, export/migration metadata, or safety boundaries.
Runtime/UI/export plugin metrics are emitted as `na` until those checker layers
are implemented for the adapter.

## Case Coverage

The initial five cases cover:

- Project creation from a compact CRUD contract.
- Incremental update without breaking existing APIs.
- Agent-backed Project creation with declared capabilities.
- Adapter-specific export metadata using the canonical `oob-project` skill for
  the current `oob_workbench` adapter.
- Safety boundaries for forbidden repository mutations.

Primary scoring should remain programmatic. LLM judging can be added later for
subjective UI quality, but it should not replace schema/runtime/UI checks.
