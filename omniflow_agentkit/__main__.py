"""Command-line entrypoint for OmniFlow Agent Kit."""

from __future__ import annotations

import argparse
import json
import sys

from .kit import OmniFlowAgentKit
from .mcp import OmniFlowMcpClient
from .openai_skill_runner import OpenAISkillRunner
from .repo_probe import RepoProbe


def _json_object(value: str) -> dict[str, object]:
    decoded = json.loads(value)
    if not isinstance(decoded, dict):
        raise argparse.ArgumentTypeError("value must be a JSON object")
    return decoded


def _add_mcp_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--mcp-url", required=True, help="OOB MCP JSON-RPC endpoint")
    parser.add_argument("--token", help="Optional OOB MCP bearer token")
    parser.add_argument("--timeout", type=float, default=30.0, help="HTTP timeout in seconds")


def _mcp_client(args: argparse.Namespace) -> OmniFlowMcpClient:
    return OmniFlowMcpClient(endpoint=args.mcp_url, token=args.token, timeout=args.timeout)


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

    recall = sub.add_parser("mcp-recall", help="Recall reusable Functions through the canonical OmniFlow tool")
    _add_mcp_args(recall)
    recall.add_argument("goal")
    recall.add_argument("--current-package", default="")
    recall.add_argument("--current-node-id", default="")
    recall.add_argument("-k", type=int, default=8)

    call_function = sub.add_parser("mcp-call-function", help="Call one Function through the canonical OmniFlow tool")
    _add_mcp_args(call_function)
    call_function.add_argument("function_id")
    call_function.add_argument("--args-json", type=_json_object, default={})
    call_function.add_argument("--goal", default="")

    ingest_runlog = sub.add_parser("mcp-ingest-runlog", help="Ingest a RunLog through the canonical OmniFlow tool")
    _add_mcp_args(ingest_runlog)
    ingest_runlog.add_argument("run_id")
    ingest_runlog.add_argument("--no-auto-enrich", action="store_true")

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

    if args.cmd == "mcp-recall":
        print(
            json.dumps(
                _mcp_client(args).recall(
                    args.goal,
                    current_package=args.current_package,
                    current_node_id=args.current_node_id,
                    k=args.k,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    if args.cmd == "mcp-call-function":
        print(
            json.dumps(
                _mcp_client(args).call_function(
                    args.function_id,
                    args.args_json,
                    goal=args.goal,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    if args.cmd == "mcp-ingest-runlog":
        print(
            json.dumps(
                _mcp_client(args).ingest_run_log(
                    args.run_id,
                    auto_enrich=not args.no_auto_enrich,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
