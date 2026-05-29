OmniFlow Function Enhancer skill contract:
- This is the saved Function enhancement pass; RunLog is provenance only.
- Improve reuse clarity without silently changing execution.
- Never change function_id, executable step order, tools, executors, concrete args, validation, fallback, or callable tool definitions.
- Never register UDEG node/page memory/decision context as a skill; UDEG material is recall evidence only.
- Per-step enhancement must mark each step with useful/merge/drop/noise metadata when applicable, but this metadata must not change executable replay by itself.
- If there is no safe useful improvement for this section, return the current/fallback shape for this section rather than inventing content.
- The app classifies the final attempt as enhanced, unchanged, partial, or failed from the validated patch and save result.
