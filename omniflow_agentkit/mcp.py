"""Minimal JSON-RPC client for OOB OmniFlow MCP."""

from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any
from urllib import error, request


class OmniFlowMcpError(RuntimeError):
    pass


@dataclass
class OmniFlowMcpClient:
    """Tiny dependency-free MCP client.

    Parameters:
        endpoint: Full JSON-RPC URL, for example ``http://127.0.0.1:8765/mcp``.
        token: Optional bearer token for the OOB MCP server.
        timeout: HTTP timeout in seconds.
    """

    endpoint: str
    token: str | None = None
    timeout: float = 30.0

    def rpc(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = {
            "jsonrpc": "2.0",
            "id": "omniflow-agentkit",
            "method": method,
        }
        if params is not None:
            payload["params"] = params
        data = json.dumps(payload).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        req = request.Request(self.endpoint, data=data, headers=headers, method="POST")
        try:
            with request.urlopen(req, timeout=self.timeout) as resp:
                decoded = json.loads(resp.read().decode("utf-8"))
        except error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise OmniFlowMcpError(f"MCP HTTP {exc.code}: {body}") from exc
        except Exception as exc:  # pragma: no cover - exact urllib failures vary by platform
            raise OmniFlowMcpError(f"MCP call failed: {exc}") from exc
        if "error" in decoded:
            raise OmniFlowMcpError(json.dumps(decoded["error"], ensure_ascii=False))
        result = decoded.get("result")
        if not isinstance(result, dict):
            raise OmniFlowMcpError(f"MCP result is not an object: {decoded}")
        return result

    def initialize(self) -> dict[str, Any]:
        return self.rpc("initialize", {"protocolVersion": "2024-11-05"})

    def list_tools(self) -> list[dict[str, Any]]:
        result = self.rpc("tools/list")
        tools = result.get("tools", [])
        return tools if isinstance(tools, list) else []

    def has_direct_omniflow(self) -> bool:
        names = {str(tool.get("name", "")).strip() for tool in self.list_tools()}
        return {
            "oob_function_list",
            "oob_function_get",
            "oob_function_register",
            "oob_function_guard_check",
            "oob_function_run",
            "oob_run_log_list",
            "oob_run_log_get",
            "oob_run_log_convert",
        }.issubset(names)

    def has_canonical_omniflow(self) -> bool:
        names = {str(tool.get("name", "")).strip() for tool in self.list_tools()}
        return {
            "omniflow.recall",
            "omniflow.call_function",
            "omniflow.ingest_run_log",
            "omniflow.explore_replay",
        }.issubset(names)

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}})

    def read_resource(self, uri: str, limit: int = 50) -> dict[str, Any]:
        return self.rpc("resources/read", {"uri": uri, "limit": limit})

    def recall(
        self,
        goal: str,
        *,
        current_package: str = "",
        current_node_id: str = "",
        k: int = 8,
    ) -> dict[str, Any]:
        return self.call_tool(
            "omniflow.recall",
            {
                "goal": goal,
                "current_package": current_package,
                "current_node_id": current_node_id,
                "k": k,
            },
        )

    def call_function(
        self,
        function_id: str,
        arguments: dict[str, Any] | None = None,
        *,
        goal: str = "",
    ) -> dict[str, Any]:
        return self.call_tool(
            "omniflow.call_function",
            {
                "function_id": function_id,
                "arguments": arguments or {},
                "goal": goal,
            },
        )

    def ingest_run_log(
        self,
        run_id: str | None = None,
        *,
        run_log: dict[str, Any] | None = None,
        auto_enrich: bool = True,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"auto_enrich": auto_enrich}
        if run_id:
            payload["run_id"] = run_id
        if run_log is not None:
            payload["run_log"] = run_log
        return self.call_tool("omniflow.ingest_run_log", payload)

    def explore_replay(
        self,
        goal: str,
        *,
        package_name: str = "",
        max_steps: int = 3,
        settle_delay_ms: int = 800,
        stop_text: str = "",
        allow_risky_actions: bool = False,
        function_id: str = "",
        replay: bool = True,
        reset_before_replay: bool = False,
        reset_back_steps: int = 1,
        arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "goal": goal,
            "max_steps": max_steps,
            "settle_delay_ms": settle_delay_ms,
            "allow_risky_actions": allow_risky_actions,
            "replay": replay,
            "reset_before_replay": reset_before_replay,
            "reset_back_steps": reset_back_steps,
            "arguments": arguments or {},
        }
        if package_name:
            payload["package_name"] = package_name
        if stop_text:
            payload["stop_text"] = stop_text
        if function_id:
            payload["function_id"] = function_id
        return self.call_tool("omniflow.explore_replay", payload)

    def list_functions(self, limit: int = 100) -> dict[str, Any]:
        return self.call_tool("oob_function_list", {"limit": limit})

    def get_function(self, function_id: str) -> dict[str, Any]:
        return self.call_tool("oob_function_get", {"functionId": function_id})

    def register_function(self, function_spec: dict[str, Any]) -> dict[str, Any]:
        return self.call_tool("oob_function_register", {"functionSpec": function_spec})

    def guard_check(
        self,
        function_id: str,
        arguments: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return self.call_tool(
            "oob_function_guard_check",
            {"functionId": function_id, "arguments": arguments or {}},
        )

    def run_function(
        self,
        function_id: str,
        arguments: dict[str, Any] | None = None,
        *,
        dry_run: bool = False,
        continue_with_agent: bool = False,
        execution_mode: str = "foreground",
        confirmed: bool = False,
    ) -> dict[str, Any]:
        return self.call_tool(
            "oob_function_run",
            {
                "functionId": function_id,
                "arguments": arguments or {},
                "dryRun": dry_run,
                "continueWithAgent": continue_with_agent,
                "executionMode": execution_mode,
                "confirmed": confirmed,
            },
        )

    def list_run_logs(self, limit: int = 50) -> dict[str, Any]:
        return self.call_tool("oob_run_log_list", {"limit": limit})

    def get_run_log(self, run_id: str) -> dict[str, Any]:
        return self.call_tool("oob_run_log_get", {"runId": run_id})

    def convert_run_log(
        self,
        run_id: str,
        *,
        register: bool = True,
        function_id: str = "",
        name: str = "",
        description: str = "",
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"runId": run_id, "register": register}
        if function_id:
            payload["functionId"] = function_id
        if name:
            payload["name"] = name
        if description:
            payload["description"] = description
        return self.call_tool("oob_run_log_convert", payload)
