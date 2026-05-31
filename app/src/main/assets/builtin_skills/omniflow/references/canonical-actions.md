# Canonical Actions And Cleanup

Use this reference to label steps and remove ambiguous action noise from agent
reasoning.

## Required Step Labels

Every executable step should have:

- `title`: short visible action label.
- `description`: what the action does on screen.
- `cleanup_annotation.action_purpose`: why the action exists in this trajectory.

## Useful Roles

- `required_action`: stable main-path action needed for success.
- `optional_checker`: conditional obstruction handling.
- `success_evidence`: observation or final state proving success.
- `failed_action`: attempted action that failed and explains a problem.
- `duplicate`: repeated equivalent action.
- `noise`: non-useful or non-replayable artifact.

## Default Noise

Mark these as noise unless there is clear evidence they matter:

- Plain `wait` steps without a visible state condition.
- Pure perception wrapper cards replaced by concrete actions.
- Failed cards followed by a successful concrete action.
- Duplicate text input with the same value into the same field.
- Debug-only, logging-only, or provider-wrapper cards.

## Merge Candidates

Mark as merge candidates when:

- Two adjacent steps represent the same user intent.
- A wrapper step is immediately followed by its concrete click/input/swipe.
- Repeated input overwrites the same field with the same value.

Use metadata first. Do not delete executable steps unless the user requested
structural cleanup or the tool confirms deletion is safe.

## Required Actions

Keep actions that:

- Move to a required screen.
- Select the stable target requested by the user.
- Enter required runtime data.
- Submit, save, confirm, or navigate to the success state.

## Success Evidence

Use success evidence to improve `description`, `success_signal`, and
`agent_reuse`. Do not turn observations into executable steps.
