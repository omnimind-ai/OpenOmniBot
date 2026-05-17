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
        required = {
            "oob_function_list",
            "oob_function_get",
            "oob_function_guard_check",
            "oob_function_run",
        }
        return required.issubset(names)

    def call_tool(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}})

    def read_resource(self, uri: str, limit: int = 50) -> dict[str, Any]:
        return self.rpc("resources/read", {"uri": uri, "limit": limit})

    def list_functions(self, limit: int = 100) -> dict[str, Any]:
        return self.call_tool("oob_function_list", {"limit": limit})

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
    ) -> dict[str, Any]:
        return self.call_tool(
            "oob_function_run",
            {
                "functionId": function_id,
                "arguments": arguments or {},
                "dryRun": dry_run,
                "continueWithAgent": continue_with_agent,
            },
        )
