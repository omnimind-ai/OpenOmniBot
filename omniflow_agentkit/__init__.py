"""OmniFlow Agent Kit.

Small, dependency-free helpers for packaging OmniFlow skills/docs, calling an
OOB MCP server, and checking whether an external GUI-agent repository can use
the OmniFlow skill workflow.
"""

from .kit import OmniFlowAgentKit
from .mcp import OmniFlowMcpClient, OmniFlowMcpError
from .repo_probe import RepoProbe, RepoProbeReport

__all__ = [
    "OmniFlowAgentKit",
    "OmniFlowMcpClient",
    "OmniFlowMcpError",
    "RepoProbe",
    "RepoProbeReport",
]
