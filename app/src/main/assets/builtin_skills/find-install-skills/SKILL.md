---
name: find-install-skills
description: Find and install relevant Omnibot skills. Use when the user asks "找个 skill", "有没有这个功能的 skill", "find a skill for X", "is there a skill for X", or wants to extend the agent with an installable workflow.
---

# Find Skills

> **CRITICAL: NEVER use `npx skills add` to install skills.**
> Always use the bundled git-clone-based installer script (`install_with_skills_cli.sh`) for installation.
> `npx skills find` is allowed **only for searching**. Installation MUST go through the script.

Use this skill to discover, compare, and install skills for Omnibot.

This version is adapted from the open `vercel-labs/skills` idea, but the workflow is aligned with Omnibot's phone runtime, `.omnibot/skills` workspace, built-in skill store, and agent tools.

## Local First

Before searching the internet:

1. Use `skills_list` to see what is already installed or bundled.
2. If a likely match exists but its full body is not loaded this turn, use `skills_read`.
3. Prefer reusing an installed skill over recommending a duplicate.

## When To Use

Use this skill when the user:

- asks how to extend Omnibot with a new capability
- asks "找个 skill" or "有没有这个功能的 skill"
- asks "find a skill for X" or "is there a skill for X"
- wants a reusable workflow for a domain such as testing, frontend, deployment, docs, or automation
- shares a skill repository link and wants help evaluating or installing it

## Discovery Workflow

1. Identify the domain and the concrete task.
2. On the phone agent, search for candidate skills only by running a terminal command.
3. Use `terminal_execute` to run `npx skills find <query>`.
4. Search with concrete keywords such as `react performance`, `playwright e2e`, `pr review`, `android automation`, or `changelog`.

Do not use browser search, repository browsing, or `skills.sh` as the primary search path for the phone agent. Skill discovery on phone should happen through the command line with `npx skills find`.

The phone runtime usually installs `npm` and can run `npx skills`, so `npx skills find` should be the default and only search path for phone skill discovery. Do not use `npx skills add` for installation. Use the bundled git-clone-based installer script for the final install step.

## Quality Checks

Do not recommend a skill from a search snippet alone. Verify:

- the source repository or publisher is trustworthy
- the repository looks maintained
- install count or adoption looks reasonable when that signal is available
- the skill directory really contains `SKILL.md`
- the workflow fits Omnibot's runtime and does not depend on unavailable tooling without warning

## How To Present Options

When you find a candidate, tell the user:

1. the skill name
2. the source repo or path
3. what problem it solves
4. why it looks trustworthy
5. whether it appears compatible with Omnibot
6. whether you can install it now or should confirm first

Keep the list short. Usually give the best 1 to 3 options.

## Installation Guidance

> **NEVER use `npx skills add`.** Always use the bundled `install_with_skills_cli.sh` script.

Only install a skill after the user confirms.

- **Do NOT use `npx skills add` under any circumstances.** Use the bundled installer script instead.
- If the skill is already bundled or installed, point the user to the existing skill instead of duplicating it.
- If the user provides a GitHub skill path, install that exact skill directory into `.omnibot/skills/<skill-id>/`.
- Preserve the full skill layout: `SKILL.md` plus any `scripts/`, `references/`, `assets/`, or `evals/`.
- Do not overwrite an existing skill directory without confirmation.
- After installation, verify it appears in `skills_list`.

## Skills CLI 

- use `terminal_execute` to run raw `npx skills find ...` **only for searching** candidate skills
- for the phone agent, do not use browser search or manual website browsing as the discovery path
- **NEVER use `npx skills add` for installation** — this is strictly forbidden; always use the bundled git-clone-based installer script
- Omnibot does not read `.agents/skills` as its primary runtime skill root
- the bundled script clones the GitHub repository directly and copies skill folders into `.omnibot/skills`

Use the script like this:

```bash
sh <scriptsDir>/install_with_skills_cli.sh claude-office-skills/skills@excel-automation
sh <scriptsDir>/install_with_skills_cli.sh vercel-labs/skills --skill find-skills
sh <scriptsDir>/install_with_skills_cli.sh https://github.com/vercel-labs/agent-skills --skill frontend-design
```

The script does all of the following:

1. parses the source into a GitHub URL and an optional skill name filter
2. runs `git clone --depth 1` to fetch the repository into a temporary directory
3. scans for skill directories (folders containing `SKILL.md`)
4. copies matched skills into Omnibot's `.omnibot/skills/`
5. fails fast if the target skill already exists

Summary:

- search: only through `terminal_execute` running `npx skills find ...`
- install into Omnibot: use `sh <scriptsDir>/install_with_skills_cli.sh ...`
- if you already know the exact package, prefer the one-line form like `owner/repo@skill`

## When No Good Match Exists

1. Say that no strong skill match was found.
2. Offer to help with the task directly.
3. If the workflow is likely to repeat, suggest creating a custom skill with `skill-creator`.
