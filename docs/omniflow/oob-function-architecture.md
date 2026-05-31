# OOB Function Architecture

This document records ownership boundaries for the RunLog to Function pipeline.

## Ownership

RunLog owns:

```text
raw event capture
run storage
manual recording artifacts
conversion entrypoint
```

OOB Function owns:

```text
Function spec shape
canonical actions
step annotations
update_function
runtime checkers
guard check
execution
UDEG indexing and recall
```

VLM/Agent owns:

```text
live perception fallback
ambiguous target repair
non-deterministic tool delegation
```

## Runtime Flow

```text
RunLog events
-> Function conversion
-> OobActionCodec canonicalization
-> cleanup and annotation
-> checker rule materialization
-> Function store
-> guard check
-> OmniflowStepExecutor
```

`update_function` uses the same canonicalization rules as initial enhancement.
It should not patch arbitrary JSON fields directly without revalidating actions,
roles, and checker rules.

## Refactor Rule

When logic asks "what action is this?" or "what role does this step have?", it
must call the shared codec/classifier instead of adding a local `when` block.
