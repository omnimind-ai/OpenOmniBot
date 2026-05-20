# Checker Guide for Agents

Use this reference when a PABench run reports metric failures. The goal is to
repair the candidate artifact, not to edit benchmark oracle files.

## Primary Rule

Optimize for `case_resolved`, but repair the specific failing metric. Do not
hardcode oracle text or create fake files just to satisfy a string check. The
artifact must remain runnable and maintainable.

## Metric Repair Map

`candidate_required_files_present`
: Create the missing artifact files under the case output directory. For
  `oob_workbench`, typical files are `project.json`,
  `frontend/html/index.html`, `PROJECT_CONTEXT.md`, and `PROJECT_SOUL.md`.

`candidate_required_api_ids_present`
: Add or restore the required API ids in `project.json`. Preserve old ids during
  update tasks.

`candidate_required_html_bindings_present`
: Bind UI controls to the runtime bridge. For `oob_workbench`, use
  `window.oob.callApi(...)`, `window.oob.getProject(...)`, and
  `window.oob.onProjectUpdated(...)`.

`adapter_contract_valid`
: Fix project metadata, duplicate API ids, invalid `run.use` values, schemas,
  agent capabilities, and unregistered `callApi` references.

`runtime_execution_pass`
: Fix native collection action schemas and writeback behavior. Ensure create,
  update, archive, list, and get actions can operate on coherent item state.

`ui_interaction_pass`
: Make the UI real enough to interact with. Avoid static mock data. Add stable
  `data-oob-id` attributes and valid inline JavaScript.

`export_roundtrip_pass`
: Fix export metadata. For `oob_workbench`, use canonical `oob-project`
  metadata and avoid retired skill ids.

`collateral_damage_free`
: Remove credentials, private keys, release signing variables, escaping
  symlinks, and unrelated repository changes.

`candidate_forbidden_paths_absent`
: Delete forbidden files from the candidate output. Do not modify `.github/**`,
  `AGENTS.md`, signing files, keystores, `.env`, or release credentials.

`candidate_forbidden_substrings_absent`
: Remove retired adapter names or sensitive strings from generated files.

## Repair Workflow

1. Read the failing metric names.
2. Open only the candidate artifact files for the failing case.
3. Fix contract and runtime errors before UI polish.
4. Preserve existing APIs and behavior in update/debug tasks.
5. Re-run:

```bash
python3 benchmarks/project_artifact/run_benchmark.py --candidate-root <candidate-root>
```

6. Stop when `case_resolved` passes or when a true oracle ambiguity should be
   reported to the benchmark maintainer.
