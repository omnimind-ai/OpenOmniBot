"""Load and package OmniFlow docs/skills for external agents."""

from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class OmniFlowAgentKit:
    """File-backed OmniFlow agent kit.

    The class intentionally has no runtime dependency on Android or OOB app
    internals. It packages the skill and docs so a GUI agent or host project can
    receive one prompt bundle and start using the available OmniFlow surface.
    """

    root: Path | None = None

    def __post_init__(self) -> None:
        if self.root is None:
            object.__setattr__(self, "root", Path(__file__).resolve().parents[1])

    @property
    def omniflow_dir(self) -> Path:
        repo_docs = self.root / "docs" / "omniflow"
        if repo_docs.is_dir():
            return repo_docs
        return Path(__file__).resolve().parent / "assets" / "omniflow"

    def read_text(self, relative_path: str) -> str:
        path = self.omniflow_dir / relative_path
        if not path.is_file():
            raise FileNotFoundError(f"OmniFlow kit file not found: {path}")
        return path.read_text(encoding="utf-8")

    def skill(self) -> str:
        return self.read_text("skills/guiagent-omniflow/SKILL.md")

    def mcp_contract(self) -> str:
        return self.read_text("MCP_CONTRACT.md")

    def function_spec(self) -> str:
        return self.read_text("FUNCTION_SPEC.md")

    def playbook(self) -> str:
        return self.read_text("GUI_AGENT_PLAYBOOK.md")

    def acceptance(self) -> str:
        return self.read_text("ACCEPTANCE.md")

    def sample_function(self) -> dict[str, Any]:
        raw = self.read_text("samples/open-settings-function.json")
        value = json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("Sample Function JSON must be an object")
        return value

    def package(self, include_docs: bool = True) -> dict[str, Any]:
        """Return a JSON-serializable package for external agents."""

        payload: dict[str, Any] = {
            "name": "omniflow-agent-kit",
            "version": "0.1",
            "skill": self.skill(),
            "sample_function": self.sample_function(),
            "activation_tools": [
                "omniflow.recall",
                "omniflow.call_function",
                "omniflow.ingest_run_log",
                "omniflow.explore_replay",
                "oob_function_list",
                "oob_function_get",
                "oob_function_register",
                "oob_function_guard_check",
                "oob_function_run",
                "oob_run_log_list",
                "oob_run_log_get",
                "oob_run_log_convert",
            ],
            "guard_decisions": ["allow", "needs_agent", "needs_confirmation", "block"],
        }
        if include_docs:
            payload["docs"] = {
                "mcp_contract": self.mcp_contract(),
                "function_spec": self.function_spec(),
                "playbook": self.playbook(),
                "acceptance": self.acceptance(),
            }
        return payload

    def agent_prompt(self, task: str, repo_summary: str | None = None) -> str:
        """Build a compact prompt for an external GUI agent."""

        sections = [
            "You are using the OmniFlow Agent Kit.",
            "Read and follow the skill exactly.",
            "",
            self.skill(),
        ]
        if repo_summary:
            sections += ["", "Repository probe summary:", repo_summary.strip()]
        sections += [
            "",
            "User task:",
            task.strip(),
            "",
            "Return: mode selected, Function id if any, guard decision, execution result, and audit/run id if available.",
        ]
        return "\n".join(sections).strip() + "\n"
