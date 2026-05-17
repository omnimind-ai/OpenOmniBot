"""Command-line entrypoint for OmniFlow Agent Kit."""

from __future__ import annotations

import argparse
import json
import os
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
    default_url = os.environ.get("OMNIFLOW_MCP_URL") or os.environ.get("OOB_MCP_URL")
    default_token = os.environ.get("OMNIFLOW_MCP_TOKEN") or os.environ.get("OOB_MCP_TOKEN")
    parser.add_argument(
        "--mcp-url",
        default=default_url,
        required=default_url is None,
        help="Real OOB MCP JSON-RPC endpoint, or OMNIFLOW_MCP_URL/OOB_MCP_URL",
    )
    parser.add_argument(
        "--token",
        default=default_token,
        help="Optional real OOB MCP bearer token, or OMNIFLOW_MCP_TOKEN/OOB_MCP_TOKEN",
    )
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

    explore_replay = sub.add_parser("mcp-explore-replay", help="Run OOB-native OmniFlow explore -> register -> optional replay")
    _add_mcp_args(explore_replay)
    explore_replay.add_argument("goal")
    explore_replay.add_argument("--package-name", default="")
    explore_replay.add_argument("--max-steps", type=int, default=3)
    explore_replay.add_argument("--settle-delay-ms", type=int, default=800)
    explore_replay.add_argument("--stop-text", default="")
    explore_replay.add_argument("--allow-risky-actions", action="store_true")
    explore_replay.add_argument("--function-id", default="")
    explore_replay.add_argument("--no-replay", action="store_true")
    explore_replay.add_argument("--reset-before-replay", action="store_true")
    explore_replay.add_argument("--reset-back-steps", type=int, default=1)
    explore_replay.add_argument("--args-json", type=_json_object, default={})

    list_functions = sub.add_parser("mcp-list-functions", help="List direct OOB Functions")
    _add_mcp_args(list_functions)
    list_functions.add_argument("--limit", type=int, default=100)

    get_function = sub.add_parser("mcp-get-function", help="Read one direct OOB Function")
    _add_mcp_args(get_function)
    get_function.add_argument("function_id")

    register_function = sub.add_parser("mcp-register-function", help="Register one direct OOB Function spec")
    _add_mcp_args(register_function)
    register_function.add_argument("--spec-json", type=_json_object, required=True)

    guard_check = sub.add_parser("mcp-guard-check", help="Run direct OOB Function guard preflight")
    _add_mcp_args(guard_check)
    guard_check.add_argument("function_id")
    guard_check.add_argument("--args-json", type=_json_object, default={})

    run_function = sub.add_parser("mcp-run-function", help="Run one direct OOB Function")
    _add_mcp_args(run_function)
    run_function.add_argument("function_id")
    run_function.add_argument("--args-json", type=_json_object, default={})
    run_function.add_argument("--dry-run", action="store_true")
    run_function.add_argument("--continue-with-agent", action="store_true")
    run_function.add_argument("--execution-mode", default="foreground")
    run_function.add_argument("--confirmed", action="store_true")

    list_runlogs = sub.add_parser("mcp-list-runlogs", help="List OOB RunLogs")
    _add_mcp_args(list_runlogs)
    list_runlogs.add_argument("--limit", type=int, default=50)

    get_runlog = sub.add_parser("mcp-get-runlog", help="Read one OOB RunLog")
    _add_mcp_args(get_runlog)
    get_runlog.add_argument("run_id")

    convert_runlog = sub.add_parser("mcp-convert-runlog", help="Convert one OOB RunLog to a Function")
    _add_mcp_args(convert_runlog)
    convert_runlog.add_argument("run_id")
    convert_runlog.add_argument("--no-register", action="store_true")
    convert_runlog.add_argument("--function-id", default="")
    convert_runlog.add_argument("--name", default="")
    convert_runlog.add_argument("--description", default="")

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

    if args.cmd == "mcp-explore-replay":
        print(
            json.dumps(
                _mcp_client(args).explore_replay(
                    args.goal,
                    package_name=args.package_name,
                    max_steps=args.max_steps,
                    settle_delay_ms=args.settle_delay_ms,
                    stop_text=args.stop_text,
                    allow_risky_actions=args.allow_risky_actions,
                    function_id=args.function_id,
                    replay=not args.no_replay,
                    reset_before_replay=args.reset_before_replay,
                    reset_back_steps=args.reset_back_steps,
                    arguments=args.args_json,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    if args.cmd == "mcp-list-functions":
        print(json.dumps(_mcp_client(args).list_functions(limit=args.limit), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-get-function":
        print(json.dumps(_mcp_client(args).get_function(args.function_id), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-register-function":
        print(json.dumps(_mcp_client(args).register_function(args.spec_json), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-guard-check":
        print(
            json.dumps(
                _mcp_client(args).guard_check(args.function_id, args.args_json),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    if args.cmd == "mcp-run-function":
        print(
            json.dumps(
                _mcp_client(args).run_function(
                    args.function_id,
                    args.args_json,
                    dry_run=args.dry_run,
                    continue_with_agent=args.continue_with_agent,
                    execution_mode=args.execution_mode,
                    confirmed=args.confirmed,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    if args.cmd == "mcp-list-runlogs":
        print(json.dumps(_mcp_client(args).list_run_logs(limit=args.limit), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-get-runlog":
        print(json.dumps(_mcp_client(args).get_run_log(args.run_id), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-convert-runlog":
        print(
            json.dumps(
                _mcp_client(args).convert_run_log(
                    args.run_id,
                    register=not args.no_register,
                    function_id=args.function_id,
                    name=args.name,
                    description=args.description,
                ),
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
