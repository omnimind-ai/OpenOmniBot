---
name: skill-creator
description: Guide for creating and updating effective Omnibot skills. Use when the user wants to add a new skill, create skill, improve skill, update skill, 扩展 skill, 创建 skill, 更新 skill, 写 skill, improve an existing skill for the built-in Alpine workspace, tool workflows, or reusable domain knowledge.
---

# Skill Creator

Use this skill when the task is to create, refine, or maintain an Omnibot skill.

Omnibot skills live inside the workspace at `.omnibot/skills/<skill-id>/` and are discovered by the agent through the `SKILL.md` frontmatter plus any bundled `scripts/`, `references/`, or `assets/`.

## Core Rules

1. Keep the frontmatter precise.
2. Keep the body short and procedural.
3. Put detailed reference material into `references/` instead of bloating `SKILL.md`.
4. Prefer reusable scripts or templates when the same steps will be repeated.
5. Design for Omnibot's real runtime: Alpine shell, workspace files, built-in tools, and Android automation.

## Skill Shape

Every skill needs a `SKILL.md` file.

Recommended layout:

```text
skill-id/
├── SKILL.md
├── scripts/
├── references/
└── assets/
```

Use only the folders that are actually needed.

## Frontmatter

Use YAML frontmatter with only these fields:

```yaml
---
name: my-skill
description: What the skill does and when Omnibot should use it.
---
```

Write the description as the trigger contract:

- State the task type clearly.
- State the signals that should trigger the skill.
- Mention important environments or tools when they matter.

Bad description:

```text
Helpful utility for many tasks.
```

Good description:

```text
Create or update deployment runbooks for Omnibot workspaces. Use when the user asks to document Alpine setup, workspace paths, scheduled runs, or recovery steps for on-device agents.
```

## Body Guidance

Write the body as instructions for another Omnibot agent.

Prefer:

- imperative steps
- short heuristics
- references to concrete files or directories
- explicit failure handling

Avoid:

- long product overviews
- repeated basics the model already knows
- user-facing marketing text
- duplicate content that belongs in `references/`

## Resource Choices

Use `scripts/` when:

- a flow is fragile
- the same code would otherwise be rewritten often
- deterministic output matters

Use `references/` when:

- the skill needs schemas, API notes, policy text, or domain documentation
- only part of the material is needed per task

Use `assets/` when:

- the skill needs templates, starter projects, icons, or example files that should be copied or edited

## Omnibot-Specific Advice

- Assume the skill will run inside Omnibot's built-in Alpine environment.
- Prefer workspace-relative guidance that matches `.omnibot/skills`, `.omnibot/memory`, and `/workspace`.
- If a workflow depends on Android UI automation, state when to use automation tools versus terminal tools.
- If a workflow depends on generated artifacts, say where they should be written in the workspace.
- If a workflow should never use destructive commands, say so explicitly.

## Creation Workflow

1. Understand the repeated task the skill should help with.
2. Collect a few realistic user requests that should trigger it.
3. Decide which knowledge belongs in `SKILL.md` and which belongs in bundled resources.
4. Create the skill directory under `.omnibot/skills/<skill-id>/`.
5. Write `SKILL.md` with a strong description and compact body.
6. Add any needed `scripts/`, `references/`, or `assets/`.
7. Re-read the result and cut anything that is obvious, duplicated, or too generic.

## Naming Rules

- Use lowercase letters, digits, and hyphens only.
- Keep the id short and descriptive.
- Prefer action-oriented names such as `skill-creator`, `calendar-helper`, or `workspace-audit`.

## Review Checklist

Before finishing, verify:

- the skill id matches the folder name
- `SKILL.md` exists
- the description explains when to use the skill
- the body tells the agent what to do next
- bundled resources are referenced only when needed
- no extra documentation files were added without a clear reason

## Editing Existing Skills

When updating a skill:

1. Preserve the trigger intent unless the user explicitly wants to change it.
2. Tighten the description if the skill is triggering too broadly or too narrowly.
3. Move bulky details out of `SKILL.md` when the file starts becoming hard to scan.
4. Keep examples realistic and aligned with Omnibot's workspace and Android runtime.

## Output Expectation

When asked to create or revise a skill, produce the actual skill files in the workspace rather than only describing them.
