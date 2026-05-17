#!/usr/bin/env python3
"""Tiny OOB OmniFlow MCP fixture for external acceptance tests."""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, HTTPServer
import argparse
import json
from pathlib import Path
import re
import time
from typing import Any


FUNCTION_ID = "settings_click_path_demo"
RUNLOG_ID = "runlog_install_demo"
INSTALL_FUNCTION_ID = "install_sample_apk_demo"


def epoch_ms() -> int:
    return int(time.time() * 1000)


def duration_ms(start_ns: int) -> int:
    return max(1, int(round((time.perf_counter_ns() - start_ns) / 1_000_000)))


def safe_step_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_]+", "_", value).strip("_") or "step"


def settings_click_path_function() -> dict[str, Any]:
    return {
        "function_id": FUNCTION_ID,
        "name": "Settings Click Path Demo",
        "schema_version": "oob.reusable_function.v1",
        "guard_summary": "Deterministic local UI replay with multiple click steps",
        "risk_level": "low",
        "execution": {
            "step_count": 7,
            "steps": [
                {"tool": "open_app", "package": "com.android.settings"},
                {"tool": "click", "target": "Network & internet", "x": 180, "y": 420},
                {"tool": "click", "target": "Internet", "x": 160, "y": 520},
                {"tool": "type", "text": "demo wifi"},
                {"tool": "click", "target": "Search result", "x": 290, "y": 610},
                {"tool": "wait", "ms": 500},
                {"tool": "click", "target": "Back", "x": 54, "y": 94},
            ],
        },
    }


def install_function() -> dict[str, Any]:
    return {
        "function_id": INSTALL_FUNCTION_ID,
        "name": "Install sample APK",
        "schema_version": "oob.reusable_function.v1",
        "guard_summary": "Pre-approved mock install",
        "risk_level": "medium",
        "execution": {
            "step_count": 1,
            "steps": [{"tool": "package.install", "apk": "sample.apk"}],
        },
    }


class MockOmniFlowMcpHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    registered_functions: dict[str, dict[str, Any]] = {FUNCTION_ID: settings_click_path_function()}

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
            return self._canonical_call_function(function_id)
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
        return {}

    def _canonical_call_function(self, function_id: str) -> dict[str, Any]:
        started_at_ms = epoch_ms()
        start_ns = time.perf_counter_ns()
        if function_id == INSTALL_FUNCTION_ID:
            step_results = [self._run_step(0, "package.install", "ok", 8)]
            return {
                "success": True,
                "fallback": False,
                "error": None,
                "run_id": "mock-run-install-sample-apk-demo",
                "function_id": function_id,
                "actions_executed": 1,
                "step_results": step_results,
                "timing": self._timing(started_at_ms, start_ns),
                "control": {"postcondition": "passed", "fallback_reason": ""},
            }
        step_results = self._run_settings_click_path()
        return {
            "success": True,
            "fallback": False,
            "error": None,
            "run_id": "mock-run-settings-click-path-demo",
            "function_id": function_id,
            "actions_executed": len(step_results),
            "step_results": step_results,
            "timing": self._timing(started_at_ms, start_ns),
            "control": {"postcondition": "passed", "fallback_reason": ""},
        }

    def _run_settings_click_path(self) -> list[dict[str, Any]]:
        return [
            self._run_step(0, "open_app", "ok", 12),
            self._run_step(1, "click", "ok", 16),
            self._run_step(2, "click", "ok", 21),
            self._run_step(3, "type", "ok", 14),
            self._run_step(4, "click", "ok", 18),
            self._run_step(5, "wait", "ok", 9),
            self._run_step(6, "click", "ok", 24),
        ]

    def _run_step(self, index: int, step_type: str, status: str, simulated_ms: int) -> dict[str, Any]:
        start_ns = time.perf_counter_ns()
        time.sleep(simulated_ms / 1000)
        return {
            "index": index,
            "type": safe_step_name(step_type),
            "status": status,
            "duration_ms": duration_ms(start_ns),
        }

    def _timing(self, started_at_ms: int, start_ns: int) -> dict[str, Any]:
        finished_at_ms = epoch_ms()
        return {
            "source": "mock_mcp_runner",
            "started_at_ms": started_at_ms,
            "finished_at_ms": finished_at_ms,
            "runner_duration_ms": duration_ms(start_ns),
        }


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
