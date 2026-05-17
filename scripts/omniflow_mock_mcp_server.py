#!/usr/bin/env python3
"""Tiny OOB OmniFlow MCP fixture for external acceptance tests."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, HTTPServer
import argparse
import json
from pathlib import Path
from typing import Any


FUNCTION_ID = "open_settings_demo"
RUNLOG_ID = "runlog_install_demo"
INSTALL_FUNCTION_ID = "install_sample_apk_demo"


def open_settings_function() -> dict[str, Any]:
    return {
        "function_id": FUNCTION_ID,
        "name": "Open Android Settings",
        "guard_summary": "Deterministic local UI replay",
        "risk_level": "low",
    }


def install_function() -> dict[str, Any]:
    return {
        "function_id": INSTALL_FUNCTION_ID,
        "name": "Install sample APK",
        "guard_summary": "Pre-approved mock background install",
        "risk_level": "medium",
    }


class MockOmniFlowMcpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    registered_functions: dict[str, dict[str, Any]] = {FUNCTION_ID: open_settings_function()}

    def do_POST(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        payload = json.loads(self.rfile.read(length).decode("utf-8"))
        result = self._result(payload.get("method"), payload.get("params", {}))
        body = json.dumps({"jsonrpc": "2.0", "id": payload.get("id"), "result": result}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return

    def _result(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        if method == "initialize":
            return {"protocolVersion": "2024-11-05", "serverInfo": {"name": "mock-oob-omniflow"}}
        if method == "tools/list":
            return {
                "tools": [
                    {"name": "omniflow.recall"},
                    {"name": "omniflow.call_function"},
                    {"name": "omniflow.ingest_run_log"},
                    {"name": "oob_function_list"},
                    {"name": "oob_function_get"},
                    {"name": "oob_function_register"},
                    {"name": "oob_function_guard_check"},
                    {"name": "oob_function_run"},
                    {"name": "oob_run_log_list"},
                    {"name": "oob_run_log_get"},
                    {"name": "oob_run_log_convert"},
                ]
            }
        if method != "tools/call":
            return {}

        name = params.get("name")
        arguments = params.get("arguments", {})
        if name == "omniflow.recall":
            candidates = list(self.registered_functions.values())
            hit = candidates[0] if candidates else None
            return {
                "success": True,
                "decision": "hit" if hit else "miss",
                "hit": {
                    "function_id": hit["function_id"],
                    "inputSchema": {"type": "object", "properties": {}, "required": []},
                } if hit else None,
                "candidates": [] if hit else candidates,
                "count": len(candidates),
                "reason": "mock_recall",
            }
        if name == "omniflow.call_function":
            function_id = arguments.get("function_id", FUNCTION_ID)
            return {
                "success": True,
                "fallback": False,
                "error": None,
                "run_id": "mock-run-open-settings-demo",
                "function_id": function_id,
                "actions_executed": 2,
                "control": {"postcondition": "passed", "fallback_reason": ""},
            }
        if name == "omniflow.ingest_run_log":
            function_id = INSTALL_FUNCTION_ID
            self.registered_functions[function_id] = install_function()
            return {
                "accepted": True,
                "success": True,
                "function_id": function_id,
                "status": "created",
                "reason": "",
            }
        if name == "oob_function_list":
            return {
                "functions": list(self.registered_functions.values())
            }
        if name == "oob_function_get":
            function_id = arguments.get("functionId", FUNCTION_ID)
            return {
                "function_id": function_id,
                "schema_version": "oob.reusable_function.v1",
                "steps": self._steps_for_function(function_id),
            }
        if name == "oob_function_register":
            spec = arguments.get("functionSpec", {})
            function_id = spec.get("function_id", "registered_from_mcp")
            self.registered_functions[function_id] = {
                "function_id": function_id,
                "name": spec.get("name", function_id),
                "guard_summary": "Registered through mock MCP",
                "risk_level": "low",
            }
            return {
                "success": True,
                "registered": True,
                "function_id": function_id,
                "source": arguments.get("source", "mcp"),
            }
        if name == "oob_function_guard_check":
            function_id = arguments.get("functionId", FUNCTION_ID)
            return {
                "function_id": function_id,
                "decision": "allow",
                "risk_level": "medium" if function_id == INSTALL_FUNCTION_ID else "low",
                "reason": "Mock fixture allows this pre-approved Function.",
            }
        if name == "oob_function_run":
            function_id = arguments.get("functionId", FUNCTION_ID)
            execution_mode = arguments.get("executionMode", "foreground")
            if function_id == INSTALL_FUNCTION_ID and execution_mode == "background":
                return {
                    "success": True,
                    "function_id": function_id,
                    "runner": "mock_oob_background_worker",
                    "guard_decision": "allow",
                    "execution_mode": "background",
                    "background": True,
                    "status": "queued",
                    "step_results": [
                        {"index": 0, "type": "package.install", "status": "queued"},
                    ],
                    "run_id": "mock-bg-install-run",
                }
            return {
                "success": True,
                "function_id": function_id,
                "runner": "mock_oob_mcp",
                "guard_decision": "allow",
                "execution_mode": execution_mode,
                "step_results": [
                    {"index": 0, "type": "open_app", "status": "ok"},
                    {"index": 1, "type": "wait", "status": "ok"},
                ],
                "run_id": "mock-run-open-settings-demo",
            }
        if name == "oob_run_log_list":
            return {
                "run_logs": [
                    {
                        "run_id": RUNLOG_ID,
                        "name": "Install sample APK",
                        "status": "success",
                        "convertible": True,
                    }
                ]
            }
        if name == "oob_run_log_get":
            return {
                "run_id": arguments.get("runId", RUNLOG_ID),
                "status": "success",
                "timeline": [
                    {"type": "package.install", "apk": "sample.apk"},
                ],
            }
        if name == "oob_run_log_convert":
            function_id = arguments.get("functionId", INSTALL_FUNCTION_ID)
            spec = {
                "schema_version": "oob.reusable_function.v1",
                "function_id": function_id,
                "name": arguments.get("name", "Install sample APK"),
                "description": arguments.get("description", "Install a sample APK in the background."),
                "parameters": [],
                "execution": {
                    "kind": "tool_sequence",
                    "steps": [{"type": "package.install", "apk": "sample.apk"}],
                },
            }
            registered = bool(arguments.get("register", False))
            if registered:
                self.registered_functions[function_id] = install_function()
            return {
                "success": True,
                "registered": registered,
                "run_id": arguments.get("runId", RUNLOG_ID),
                "function_id": function_id,
                "function_spec": spec,
            }
        return {}

    def _steps_for_function(self, function_id: str) -> list[dict[str, Any]]:
        if function_id == INSTALL_FUNCTION_ID:
            return [{"type": "package.install", "apk": "sample.apk"}]
        return [{"type": "open_app", "package": "com.android.settings"}, {"type": "wait", "ms": 500}]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", required=True)
    args = parser.parse_args()

    server = HTTPServer((args.host, args.port), MockOmniFlowMcpHandler)
    port_file = Path(args.port_file)
    port_file.parent.mkdir(parents=True, exist_ok=True)
    port_file.write_text(f"http://{args.host}:{server.server_port}/mcp\n", encoding="utf-8")
    try:
        server.serve_forever()
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
