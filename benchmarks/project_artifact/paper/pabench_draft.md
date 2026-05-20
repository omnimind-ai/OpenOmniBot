# ProjectArtifactBench: Evaluating LLM Agents for Natural-Language-to-Runnable-Project Engineering

**Status:** working draft, not submission-ready  
**Target venue fit:** benchmark/evaluation track, workshop first  
**Current implementation:** seed benchmark with 5 `oob_workbench` reference-adapter cases

## Abstract

LLM agents are increasingly expected to do more than answer questions, call APIs, or fix existing repository issues. In many product settings, users describe a desired tool or workflow in natural language and expect the agent to synthesize a runnable project artifact, connect it to a runtime, preserve it through future updates, export it, and avoid unsafe collateral changes. Existing benchmarks primarily evaluate issue resolution, web/mobile environment control, or API-mediated task completion, but they do not directly measure the lifecycle of creating and maintaining executable software artifacts from requirements.

We propose **ProjectArtifactBench (PABench)**, a benchmark framework for evaluating whether LLM agents can synthesize and evolve runnable software project artifacts under executable contracts. A PABench task provides a natural-language instruction, optional initial project fixture, adapter specification, and hidden or public oracle. An agent must produce a candidate artifact, which is evaluated through layered checkers for artifact completeness, API/schema consistency, runtime grounding, UI behavior, export/import validity, regression preservation, and safety/collateral damage. We introduce an adapter protocol that separates benchmark core from product-specific runtimes, with `oob_workbench` as the first reference adapter. Initial seed cases cover project creation, incremental update, agent-backed actions, export metadata, and safety boundaries. This draft argues for the benchmark's necessity, defines its evaluation protocol, and outlines the experimental program required to establish it as a general LLM-agent benchmark.

## 1. Introduction

Current software-agent benchmarks have made important progress in evaluating whether agents can repair existing code, operate web environments, manipulate Android apps, or complete API tasks. However, a growing class of agent products asks a different question: can an agent take a user's high-level requirement and turn it into a runnable, maintainable software artifact?

This task is not equivalent to generating a single file or passing a unit test. A runnable application artifact contains multiple coupled components:

- product intent and behavioral constraints
- data model and state transitions
- API/tool definitions
- user interface
- runtime binding and persistence
- documentation for future maintenance
- export/import metadata
- safety boundaries and forbidden mutations

Agents often fail at these boundaries. They may generate plausible UI that is not connected to any runtime, rename existing APIs during an update, break old behavior while adding a new feature, export invalid metadata, reintroduce deprecated runtime identifiers, or modify unsafe repository files to satisfy an instruction. These failures are common in product engineering but are not the primary target of existing benchmarks.

PABench frames this as **natural-language-to-runnable-project engineering**. The unit of evaluation is not an answer or a patch alone, but a complete project artifact that must satisfy executable contracts across its lifecycle.

## 2. Research Question

The central research question is:

> Can LLM agents synthesize and evolve runnable software project artifacts from natural-language requirements under executable contracts?

We decompose this into five subquestions:

1. **Creation:** Can agents generate a complete project artifact from a requirement?
2. **Grounding:** Is the generated UI/API connected to an executable runtime rather than static mock data?
3. **Maintenance:** Can agents update an existing artifact without breaking old APIs, data conventions, and behavior?
4. **Portability:** Can artifacts be exported, migrated, and reloaded without losing validity?
5. **Safety:** Can agents avoid forbidden files, credentials, permission changes, and unrelated collateral damage?

## 3. Why Existing Benchmarks Are Insufficient

PABench is closest in spirit to benchmarks that use executable or state-based evaluation, but it targets a different unit of work.

| Benchmark | Evaluated Unit | Typical Output | Oracle | Gap PABench Targets |
|---|---|---|---|---|
| SWE-bench | Real GitHub issue fixing | Repository patch | Tests pass | Assumes an existing software project and focuses on bug-fix patches, not artifact creation and lifecycle maintenance. |
| AppWorld | API/app-world task completion | Code/API actions that mutate app state | State-based tests | Evaluates action correctness inside existing app APIs, not synthesis of a new project artifact. |
| WebArena | Web task execution | Browser actions | Functional environment state | Evaluates using software through a browser, not generating software artifacts. |
| AndroidWorld | Android task execution | Android UI actions | Programmatic task checks | Evaluates mobile environment control, not artifact creation and maintenance. |
| Design2Code-like tasks | Frontend generation from visual spec | HTML/CSS/UI code | Visual or structural similarity | Focuses on visual generation, not runtime, persistence, export, and incremental evolution. |

The missing capability is **lifecycle-level software artifact engineering**: producing and maintaining a coherent app/project artifact whose UI, data, APIs, runtime, docs, export metadata, and safety boundaries remain consistent.

## 4. Benchmark Design

### 4.1 Task Format

Each case is a JSON object:

```json
{
  "schema_version": "project_artifact.case.v1",
  "adapter": "oob_workbench",
  "id": "create_habit_checkin_crud",
  "category": "create",
  "capabilities": [
    "requirement_extraction",
    "project_contract_generation",
    "runtime_binding"
  ],
  "instruction": "Create a mobile-first habit check-in app...",
  "input": {
    "mode": "create",
    "contract_fixture": "../fixtures/contracts/habit_checkin.json"
  },
  "oracle": {
    "static": {
      "required_api_ids": ["habit.create", "habit.checkIn"],
      "forbidden_path_globs": [".github/**", "AGENTS.md"]
    }
  }
}
```

The public instruction is what the evaluated agent receives. The oracle may be public for development cases and hidden for test cases.

### 4.2 Artifact Format

The benchmark core does not require one universal project layout. Instead, each adapter declares its artifact contract. For the current `oob_workbench` adapter, a candidate artifact is expected to contain files such as:

```text
<case-id>/
  project.json
  frontend/html/index.html
  PROJECT_CONTEXT.md
  PROJECT_SOUL.md
  export_manifest.json      # export cases only
```

Other adapters could target React/Vite, Flutter, Electron, or workflow-builder runtimes.

### 4.3 Adapter Protocol

An adapter implements runtime-specific validation:

- artifact layout
- optional builder baseline
- contract checker
- runtime checker
- UI checker
- export/import checker
- safety/collateral-damage checker

The benchmark core defines case schema and metric reporting. The adapter provides executable semantics.

## 5. Checker Design

Checker quality is the central technical challenge. PABench should not rely primarily on LLM judging or superficial string matching. We propose layered checker groups:

### 5.1 Static Checker

Checks file presence, forbidden paths, forbidden strings, JSON pointer assertions, and simple API/HTML presence. This layer is cheap and catches obvious artifact failures.

### 5.2 Contract Checker

Checks that artifact metadata and runtime contracts are semantically coherent:

- schema validity
- stable API/tool ids
- input/output schemas
- legal executor/runtime bindings
- HTML calls only registered APIs
- docs record data layout and update history

### 5.3 Runtime Checker

Executes adapter-defined actions and checks resulting state. For `oob_workbench`, this can use portable Workbench runtime scripts to create a project, call APIs such as `habit.create` or `habit.checkIn`, and verify persisted item state.

### 5.4 UI Checker

Runs browser or app automation. For HTML artifacts, a Playwright checker can inject a mocked runtime bridge, click controls, submit forms, and verify DOM/state changes.

### 5.5 Regression Checker

For update/debug cases, checks that newly requested behavior is added while old APIs, controls, and state conventions still work. This is analogous to the distinction between bug-fixing and non-regression tests in repository repair benchmarks.

### 5.6 Export/Import Checker

Checks packaging metadata, import into a clean workspace, and post-import contract/runtime validity.

### 5.7 Safety Checker

Checks forbidden file modifications, unsafe credential strings, permission/workflow changes, and unrelated collateral damage.

## 6. Metrics

Primary metric:

```text
case_resolved = 1 if all required checker groups pass, else 0
```

Supporting metrics:

- `case_ready`: benchmark case schema, fixtures, and optional baseline are valid
- `candidate_required_files_present`
- `candidate_required_api_ids_present`
- `candidate_required_html_bindings_present`
- `adapter_contract_valid`
- `runtime_execution_pass`
- `ui_interaction_pass`
- `export_roundtrip_pass`
- `collateral_damage_free`
- `candidate_forbidden_paths_absent`
- `candidate_forbidden_substrings_absent`

Aggregate metrics:

```text
ProjectResolved@1 = mean(case_resolved)
ContractPassRate = mean(adapter_contract_valid)
RuntimePassRate = mean(runtime_execution_pass)
UIPassRate = mean(ui_interaction_pass)
SafetyViolationRate = 1 - mean(collateral_damage_free)
```

We also report wall-clock time, number of repair attempts, token usage, and cost where available.

## 7. Fair Comparison Protocol

Prompt quality is a legitimate confound. PABench should therefore report multiple tracks:

### 7.1 Model-Controlled Track

Fixed harness prompt, fixed tool access, fixed budget, and fixed adapter. Only the model changes. This track compares base model capability under the same scaffold.

### 7.2 Agent-System Track

Each system may use its own prompts, planner, repair loop, validators, and tools, subject to a fixed wall-clock/token/cost budget. This track evaluates realistic agent products.

### 7.3 Method-Ablation Track

Fixed model and fixed tasks, varying only one method component such as prompt, validator, self-repair, or planner. This track supports internal engineering and scientific ablation.

All tracks must report tool access, model version, system prompt or prompt hash, maximum turns, timeout, repair attempts, and whether the public development set was used for tuning.

## 8. Current Implementation

The repository currently implements a seed version:

- 5 cases under `benchmarks/project_artifact/cases`
- `project_artifact.case.v1` schema
- dependency-free runner
- `oob_workbench` reference adapter documentation
- agent-facing `project-artifact-engineering` skill template
- metric-level JSONL output
- builder baseline checks for contract-backed creation cases

The current seed cases cover:

1. CRUD project creation
2. incremental update preserving old behavior
3. agent-backed project action
4. canonical export metadata
5. safety boundaries for forbidden repository mutation

Current status:

```text
python3 benchmarks/project_artifact/run_benchmark.py
Summary: 5/5 cases ready
```

This means the benchmark definitions and builder baselines are valid. It does **not** yet mean a full candidate artifact evaluation has been run across external agents.

## 9. Experimental Plan

To make PABench paper-ready, we need the following experiments.

### 9.1 Dataset Expansion

Target at least 100-300 cases:

- create: 50-100
- update: 50-100
- debug/regression: 30-50
- export/migration: 20-30
- safety/collateral damage: 20-30

Cases should mix real product regressions, synthetic stress tests, and human-authored natural-language requirements.

### 9.2 Checker Strengthening

Implement adapter plugins:

- contract checker using adapter schema validation
- runtime checker using executable APIs
- UI checker using browser/app automation
- export/import checker
- repository collateral-damage checker

### 9.3 Baselines

Evaluate multiple models and systems:

- fixed-prompt model baselines
- coding-agent baselines
- self-repair variants
- validator-assisted variants
- adapter-specific vs adapter-agnostic prompts

### 9.4 Analysis

Report:

- overall pass rate
- per-category pass rate
- per-checker pass rate
- static-vs-runtime gap
- update regression failures
- safety violations
- cost/time tradeoffs
- qualitative failure taxonomy

Expected useful analysis: a model may pass static checks while failing runtime/UI checks, demonstrating why layered checkers are necessary.

## 10. Threats to Validity

**Product specificity.** The current reference adapter is OOB Workbench. This is useful for a concrete executable environment but may appear product-specific. Mitigation: define an adapter protocol and add non-OOB adapters.

**Prompt sensitivity.** Agent performance depends on prompting and scaffolding. Mitigation: separate model-controlled, agent-system, and method-ablation tracks.

**Checker gaming.** Public static oracle checks may be hardcoded. Mitigation: keep hidden test cases, prefer runtime/UI state checks, and use private oracle details for leaderboard evaluation.

**Static overestimation.** Static checks can overstate artifact quality. Mitigation: make runtime, UI, and export roundtrip checks required for the main score.

**Dataset representativeness.** Seed cases are too small. Mitigation: expand from real failures and diverse app categories, and report data provenance.

**Reproducibility.** Agent execution can be nondeterministic. Mitigation: fixed seeds where possible, repeated runs, fixed timeouts, exact tool/model versions, and archived candidate artifacts.

## 11. Related Work

SWE-bench evaluates whether language models can resolve real GitHub issues by generating patches that pass tests. It is the closest methodological precedent for executable software-agent evaluation, but its task unit is issue repair in existing repositories rather than artifact synthesis and lifecycle maintenance.

AppWorld evaluates interactive coding agents in a controllable world of apps and APIs with state-based tests. PABench similarly values executable state-based checks, but moves the target from completing tasks inside existing apps to generating and maintaining project artifacts.

WebArena evaluates autonomous agents in realistic web environments through functional correctness. AndroidWorld evaluates agents on Android tasks with programmatic setup and success checks. Both evaluate agents using software environments, while PABench evaluates agents producing software artifacts for later use.

Design-to-code and web-generation benchmarks evaluate frontend generation and visual fidelity. PABench treats UI as one layer of an artifact contract, alongside data model, API, runtime, persistence, export, and safety.

## 12. Conclusion

PABench targets a capability that is increasingly important for software agents: transforming natural-language requirements into runnable, maintainable, and verifiable project artifacts. Its central contribution is not a single product-specific test suite, but an evaluation protocol for full-lifecycle project artifact engineering. The current implementation is a seed benchmark with an `oob_workbench` reference adapter. To become a publishable benchmark, the next work is to strengthen checkers, expand the case set, add non-OOB adapters, and run broad model/agent baselines.

## References

- Jimenez et al., **SWE-bench: Can Language Models Resolve Real-World GitHub Issues?** arXiv:2310.06770. <https://arxiv.org/abs/2310.06770>
- Trivedi et al., **AppWorld: A Controllable World of Apps and People for Benchmarking Interactive Coding Agents.** arXiv:2407.18901. <https://arxiv.org/abs/2407.18901>
- Zhou et al., **WebArena: A Realistic Web Environment for Building Autonomous Agents.** arXiv:2307.13854. <https://arxiv.org/abs/2307.13854>
- Rawles et al., **AndroidWorld: A Dynamic Benchmarking Environment for Autonomous Agents.** arXiv:2405.14573. <https://arxiv.org/abs/2405.14573>

## TODO Before Submission

- Add verified BibTeX via a citation tool rather than manually typed entries.
- Add runtime/UI/export checker implementations.
- Expand to 100+ cases.
- Run at least 5-10 model/agent baselines.
- Add failure taxonomy with examples.
- Add non-OOB adapter or explain why OOB Workbench is sufficiently general for the first release.
- Decide target: workshop, benchmark track, or systems/tooling venue.
