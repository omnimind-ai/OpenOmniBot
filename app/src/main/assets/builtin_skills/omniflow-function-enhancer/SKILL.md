---
name: omniflow-function-enhancer
description: Enhance or repair an existing OmniFlow reusable Function created from a RunLog. Use for 增强, update_function, Function 纠错, "应该点 A 而不是点 B", runtime parameters, agent_reuse metadata, cleanup/noise annotations, background enhancement status, and explicit enhanced unchanged partial failed reporting.
---

# OmniFlow Function Updater

This is a compatibility entry for old Function-enhancement triggers. The
canonical layered skill is `omniflow`.

For current behavior, use:

- `omniflow/references/function-enhancement.md` for enhancement, repair,
  structural insert/delete, and status contract.
- `omniflow/references/canonical-actions.md` for step labels, noise, duplicate
  actions, and merge candidates.
- `omniflow/references/checkers.md` for ads, popups, permission nudges, and
  optional checker metadata.
- `omniflow/references/runlog-evidence.md` when a `run_id` is available and the
  Function should learn from success/failure evidence.

The runtime prompt still uses the shared contract in
`references/runtime-contract.md`. Keep that file, the Flutter asset
`ui/assets/execution_history/omniflow_function_enhancer_contract.md`, and the
Dart constant `kOmniFlowFunctionEnhancerContract` in sync.

Do not add new enhancement rules here. Add or update the owning OmniFlow
reference instead.
