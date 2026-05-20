# OOB Project Benchmark

This is the first small benchmark suite for the `oob-project` mode. It turns
known Project-mode failure patterns into repeatable cases with machine-readable
oracles.

The first version intentionally stays lightweight:

- JSON case files under `cases/`.
- Reusable fixtures under `fixtures/`.
- A dependency-free runner, `run_benchmark.py`.
- Programmatic checks for schema, fixtures, generated contract baseline output,
  static candidate artifacts, forbidden paths, and forbidden substrings.

It is not a full Android or browser benchmark yet. The next step is to connect
these cases to Workbench runtime execution and Playwright UI checks.

## Run

Validate the benchmark definitions and run builder baselines for creation cases:

```bash
python3 benchmarks/oob_project/run_benchmark.py
```

Validate candidate outputs for the same five cases:

```bash
python3 benchmarks/oob_project/run_benchmark.py --candidate-root /path/to/results
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
python3 benchmarks/oob_project/run_benchmark.py --jsonl
```

## Case Coverage

The initial five cases cover:

- Project creation from a compact CRUD contract.
- Incremental update without breaking existing APIs.
- Agent-backed Project creation with declared capabilities.
- Export metadata using the canonical `oob-project` skill.
- Safety boundaries for forbidden repository mutations.

Primary scoring should remain programmatic. LLM judging can be added later for
subjective UI quality, but it should not replace schema/runtime/UI checks.
