"""Probe external GUI-agent repositories for OmniFlow integration readiness."""

from __future__ import annotations

from dataclasses import dataclass, asdict
import configparser
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class RepoProbeReport:
    path: str
    project_name: str
    detected_stack: list[str]
    has_readme: bool
    has_python: bool
    has_node: bool
    has_flutter: bool
    has_android: bool
    has_mcp_terms: bool
    has_agent_terms: bool
    recommended_mode: str
    integration_entrypoint: str
    notes: list[str]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def summary(self) -> str:
        notes = "; ".join(self.notes[:4])
        return (
            f"{self.project_name}: stack={','.join(self.detected_stack) or 'unknown'}, "
            f"mode={self.recommended_mode}, entrypoint={self.integration_entrypoint}. {notes}"
        )


class RepoProbe:
    """Static compatibility probe for external projects."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).resolve()

    def run(self) -> RepoProbeReport:
        if not self.path.is_dir():
            raise FileNotFoundError(f"Repository path not found: {self.path}")

        files = {p.name.lower() for p in self.path.iterdir()}
        all_files = list(self.path.rglob("*"))
        names = {p.name.lower() for p in all_files if p.is_file()}
        rel_text = "\n".join(str(p.relative_to(self.path)).lower() for p in all_files[:5000])
        readme_text = self._read_first_readme()
        haystack = f"{rel_text}\n{readme_text.lower()}"

        has_python = (
            "pyproject.toml" in files
            or "setup.py" in files
            or "requirements.txt" in files
            or "environment.yml" in files
            or any(p.suffix == ".py" for p in all_files if p.is_file())
        )
        has_node = "package.json" in files
        has_flutter = "pubspec.yaml" in files or "flutter" in haystack or "dart" in haystack
        has_android = "build.gradle" in files or "build.gradle.kts" in files or "androidmanifest.xml" in names
        has_mcp_terms = "mcp" in haystack or "model context protocol" in haystack
        has_agent_terms = any(term in haystack for term in ("agent", "gui agent", "mobile agent", "androidworld"))

        stack = []
        if has_python:
            stack.append("python")
        if has_node:
            stack.append("node")
        if has_flutter:
            stack.append("flutter")
        if has_android:
            stack.append("android")
        if has_mcp_terms:
            stack.append("mcp")

        mode, entrypoint, notes = self._recommend(
            has_python=has_python,
            has_node=has_node,
            has_flutter=has_flutter,
            has_android=has_android,
            has_mcp_terms=has_mcp_terms,
            has_agent_terms=has_agent_terms,
            haystack=haystack,
        )

        return RepoProbeReport(
            path=str(self.path),
            project_name=self._project_name(),
            detected_stack=stack,
            has_readme=bool(readme_text),
            has_python=has_python,
            has_node=has_node,
            has_flutter=has_flutter,
            has_android=has_android,
            has_mcp_terms=has_mcp_terms,
            has_agent_terms=has_agent_terms,
            recommended_mode=mode,
            integration_entrypoint=entrypoint,
            notes=notes,
        )

    def _read_first_readme(self) -> str:
        for name in ("README.md", "README.rst", "readme.md", "Readme.md"):
            path = self.path / name
            if path.is_file():
                return path.read_text(encoding="utf-8", errors="replace")[:50000]
        return ""

    def _project_name(self) -> str:
        config_path = self.path / ".git" / "config"
        if config_path.is_file():
            parser = configparser.ConfigParser()
            parser.read(config_path)
            section = 'remote "origin"'
            if parser.has_option(section, "url"):
                url = parser.get(section, "url").rstrip("/")
                name = url.rsplit("/", 1)[-1].removesuffix(".git")
                if name:
                    return name
        return self.path.name

    def _recommend(
        self,
        *,
        has_python: bool,
        has_node: bool,
        has_flutter: bool,
        has_android: bool,
        has_mcp_terms: bool,
        has_agent_terms: bool,
        haystack: str,
    ) -> tuple[str, str, list[str]]:
        notes: list[str] = []
        if has_mcp_terms:
            notes.append("Project already mentions MCP; prefer Direct MCP mode.")
            return "direct_mcp", "OmniFlowMcpClient", notes
        if has_python and has_agent_terms:
            notes.append("Python agent project; inject OmniFlowAgentKit.skill() into the agent system prompt.")
            notes.append("Use OmniFlowMcpClient when OOB MCP is reachable.")
            return "python_skill_plus_mcp", "OmniFlowAgentKit.agent_prompt", notes
        if has_node and has_agent_terms:
            notes.append("Node agent project; export the kit package JSON and pass skill/docs to the agent.")
            return "json_skill_pack", "python -m omniflow_agentkit pack", notes
        if has_flutter or has_android:
            notes.append("Mobile app project; use GUI bridge mode unless it embeds an agent runtime.")
            return "gui_bridge", "skills/guiagent-omniflow/SKILL.md", notes
        if "openai" in haystack or "api_key" in haystack:
            notes.append("Project appears to call an LLM API; use the skill prompt as system/developer context.")
            return "skill_prompt", "OmniFlowAgentKit.agent_prompt", notes
        notes.append("No native agent surface detected; use OmniFlow kit as external controller instructions.")
        return "external_controller", "docs/omniflow/GUI_AGENT_PLAYBOOK.md", notes
