"""Minimal JSON-RPC client for OOB OmniFlow MCP."""

from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any
from urllib import request, error


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
        canonical = {
            "omniflow.recall",
            "omniflow.call_function",
        }
        required = {
            "oob_function_list",
            "oob_function_get",
            "oob_function_guard_check",
            "oob_function_run",
        }
        return canonical.issubset(names) or required.issubset(names)

    def has_canonical_omniflow(self) -> bool:
        names = {str(tool.get("name", "")).strip() for tool in self.list_tools()}
        return {"omniflow.recall", "omniflow.call_function"}.issubset(names)

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

    def list_functions(self, limit: int = 100) -> dict[str, Any]:
        return self.call_tool("oob_function_list", {"limit": limit})

    def register_function(self, function_spec: dict[str, Any], source: str = "mcp") -> dict[str, Any]:
        return self.call_tool(
            "oob_function_register",
            {"functionSpec": function_spec, "source": source},
        )

    def guard_check(self, function_id: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
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
        execution_mode: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "functionId": function_id,
            "arguments": arguments or {},
            "dryRun": dry_run,
            "continueWithAgent": continue_with_agent,
        }
        if execution_mode:
            payload["executionMode"] = execution_mode
        return self.call_tool("oob_function_run", payload)

    def list_run_logs(self, limit: int = 50) -> dict[str, Any]:
        return self.call_tool("oob_run_log_list", {"limit": limit})

    def get_run_log(self, run_id: str) -> dict[str, Any]:
        return self.call_tool("oob_run_log_get", {"runId": run_id})

    def convert_run_log(
        self,
        run_id: str,
        *,
        register: bool = False,
        function_id: str | None = None,
        name: str | None = None,
        description: str | None = None,
    ) -> dict[str, Any]:
        payload: dict[str, Any] = {"runId": run_id, "register": register}
        if function_id:
            payload["functionId"] = function_id
        if name:
            payload["name"] = name
        if description:
            payload["description"] = description
        return self.call_tool("oob_run_log_convert", payload)
