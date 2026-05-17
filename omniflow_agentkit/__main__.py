"""Command-line entrypoint for OmniFlow Agent Kit."""

from __future__ import annotations

import argparse
import json
import sys

from .kit import OmniFlowAgentKit
from .repo_probe import RepoProbe
from .openai_skill_runner import OpenAISkillRunner


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m omniflow_agentkit")
    sub = parser.add_subparsers(dest="cmd", required=True)

    pack = sub.add_parser("pack", help="Print the OmniFlow agent package as JSON")
    pack.add_argument("--no-docs", action="store_true", help="Only include skill and activation metadata")

    prompt = sub.add_parser("prompt", help="Build an agent prompt for a task")
    prompt.add_argument("task")
    prompt.add_argument("--repo", help="Optional repository path to probe and summarize")

    probe = sub.add_parser("probe-repo", help="Probe one repository")
    probe.add_argument("path")

    smoke = sub.add_parser("openai-smoke", help="Run optional OpenAI skill smoke test")
    smoke.add_argument("task")
    smoke.add_argument("--repo")

    args = parser.parse_args(argv)
    kit = OmniFlowAgentKit()

    if args.cmd == "pack":
        print(json.dumps(kit.package(include_docs=not args.no_docs), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "probe-repo":
        report = RepoProbe(args.path).run()
        print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "prompt":
        summary = RepoProbe(args.repo).run().summary() if args.repo else None
        print(kit.agent_prompt(args.task, repo_summary=summary), end="")
        return 0

    if args.cmd == "openai-smoke":
        runner = OpenAISkillRunner()
        if not runner.available():
            print("OPENAI_API_KEY is not configured; skipping OpenAI smoke test.", file=sys.stderr)
            return 2
        summary = RepoProbe(args.repo).run().summary() if args.repo else None
        print(runner.run(kit.agent_prompt(args.task, repo_summary=summary)))
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
