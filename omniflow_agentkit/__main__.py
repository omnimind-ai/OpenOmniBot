"""Command-line entrypoint for OmniFlow Agent Kit."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
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


def _read_json_file(path: str) -> dict[str, object]:
    decoded = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(decoded, dict):
        raise argparse.ArgumentTypeError("JSON file must contain an object")
    return decoded


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

    list_functions = sub.add_parser("mcp-list-functions", help="List existing OOB Functions over MCP")
    _add_mcp_args(list_functions)
    list_functions.add_argument("--limit", type=int, default=100)

    register_function = sub.add_parser("mcp-register-function", help="Register or update an OOB Function over MCP")
    _add_mcp_args(register_function)
    register_function.add_argument("--function-json", required=True, help="Path to a Function spec JSON file")
    register_function.add_argument("--source", default="mcp")

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

    guard_check = sub.add_parser("mcp-guard-check", help="Guard-check an existing OOB Function over MCP")
    _add_mcp_args(guard_check)
    guard_check.add_argument("function_id")
    guard_check.add_argument("--args-json", type=_json_object, default={})

    run_function = sub.add_parser("mcp-run-function", help="Guard-check and run an existing OOB Function over MCP")
    _add_mcp_args(run_function)
    run_function.add_argument("function_id")
    run_function.add_argument("--args-json", type=_json_object, default={})
    run_function.add_argument("--dry-run", action="store_true")
    run_function.add_argument("--continue-with-agent", action="store_true")
    run_function.add_argument("--execution-mode", choices=("foreground", "background"))
    run_function.add_argument("--background", action="store_true", help="Shortcut for --execution-mode background")
    run_function.add_argument("--no-preflight", action="store_true", help="Call oob_function_run without a separate guard check")

    list_runlogs = sub.add_parser("mcp-list-runlogs", help="List OOB RunLogs over MCP")
    _add_mcp_args(list_runlogs)
    list_runlogs.add_argument("--limit", type=int, default=50)

    get_runlog = sub.add_parser("mcp-get-runlog", help="Read one OOB RunLog over MCP")
    _add_mcp_args(get_runlog)
    get_runlog.add_argument("run_id")

    convert_runlog = sub.add_parser("mcp-convert-runlog", help="Convert a RunLog to a Function over MCP")
    _add_mcp_args(convert_runlog)
    convert_runlog.add_argument("run_id")
    convert_runlog.add_argument("--register", action="store_true")
    convert_runlog.add_argument("--function-id")
    convert_runlog.add_argument("--name")
    convert_runlog.add_argument("--description")

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

    if args.cmd == "mcp-list-functions":
        print(json.dumps(_mcp_client(args).list_functions(limit=args.limit), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-register-function":
        function_spec = _read_json_file(args.function_json)
        print(json.dumps(_mcp_client(args).register_function(function_spec, source=args.source), ensure_ascii=False, indent=2))
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

    if args.cmd == "mcp-guard-check":
        print(json.dumps(_mcp_client(args).guard_check(args.function_id, args.args_json), ensure_ascii=False, indent=2))
        return 0

    if args.cmd == "mcp-run-function":
        client = _mcp_client(args)
        execution_mode = "background" if args.background else args.execution_mode
        preflight = None if args.no_preflight else client.guard_check(args.function_id, args.args_json)
        if preflight is not None and preflight.get("decision") != "allow":
            print(
                json.dumps(
                    {
                        "success": False,
                        "function_id": args.function_id,
                        "guard_decision": preflight.get("decision"),
                        "preflight": preflight,
                        "run_skipped": True,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 3
        result = client.run_function(
            args.function_id,
            args.args_json,
            dry_run=args.dry_run,
            continue_with_agent=args.continue_with_agent,
            execution_mode=execution_mode,
        )
        print(
            json.dumps(
                {
                    "success": bool(result.get("success", True)),
                    "function_id": args.function_id,
                    "guard_decision": preflight.get("decision") if preflight else result.get("guard_decision"),
                    "execution_mode": execution_mode or result.get("execution_mode"),
                    "preflight": preflight,
                    "result": result,
                },
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
                    register=args.register,
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
