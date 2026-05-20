# Artifact Contract Reference

This reference defines the minimum project artifact expected by
ProjectArtifactBench/PABench tasks. The base contract is adapter-neutral; the
current repository uses the `oob_workbench` adapter.

## Project JSON

`project.json` must expose stable API/tool ids. Each API should include:

- `toolId` or `apiId`
- `displayName`
- `inputSchema`
- `outputSchema`
- `run.use`

Native collection actions should use:

- `native.collection.create`
- `native.collection.update`
- `native.collection.archive`
- `native.collection.list`
- `native.collection.get`

Agent actions should use `run.use = "agent"` and declare non-empty
`run.capabilities`.

## HTML Runtime Bridge

The HTML should bind to Workbench runtime APIs:

- `window.oob.getProject(...)` to load current state.
- `window.oob.callApi('<api-id>', inputs)` for mutations/actions.
- `window.oob.onProjectUpdated(...)` to refresh after state changes.

Use stable `data-oob-id` attributes on important controls and containers so UI
tests can target them.

## Documentation

`PROJECT_CONTEXT.md` should include:

- current API ids and purpose
- data layout
- known constraints
- update history

`PROJECT_SOUL.md` should include:

- product intent
- business rules
- what must not regress during updates

## Export Metadata

Export manifests must use the active adapter's canonical metadata. For the
current `oob_workbench` adapter, that metadata is:

```json
{
  "source": "oob-project",
  "skills": [
    {
      "skillId": "oob-project",
      "source": "oob-project",
      "path": "skills/oob-project/SKILL.md"
    }
  ]
}
```

Retired skill ids are forbidden:

- `oob-project-distiller`
- `oob-project-designer`
- `oob-native-workbench`
