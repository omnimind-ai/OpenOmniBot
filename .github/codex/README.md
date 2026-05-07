# Codex Bot Runner Notes

The Codex bot workflow is `.github/workflows/codex-bot.yml`.

Authentication can be provided in either of these ways:

- Preferred for this self-hosted runner: configure Codex CLI for the runner user, currently `cicd`.
- Optional fallback: set the repository secret `OPENAI_API_KEY`; the workflow passes it to `openai/codex-action` when present.

Optional repository variables:

- `CODEX_RUNNER_USER`: runner account used by `openai/codex-action`; defaults to `cicd`.
- `CODEX_MODEL`: override the Codex model.
- `CODEX_REASONING_EFFORT`: override reasoning effort.

If `cicd` has passwordless sudo, consider using a dedicated low-privilege account such as `cicd-codex` and setting `CODEX_RUNNER_USER=cicd-codex`.
