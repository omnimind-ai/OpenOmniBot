use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;
use omnibot_mcp::{RemoteMcpClient, RemoteMcpConfigStore};

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

/// One handler instance bridges to the configured MCP servers.
/// `tool_names()` is dynamic at runtime — orchestrator queries handler's known tools via the catalog,
/// not the static slice — but we still keep a placeholder name to satisfy the trait.
pub struct McpToolHandler {
    pub client: Arc<RemoteMcpClient>,
    pub config_store: RemoteMcpConfigStore,
}

impl McpToolHandler {
    pub fn new(client: Arc<RemoteMcpClient>, config_store: RemoteMcpConfigStore) -> Self {
        Self {
            client,
            config_store,
        }
    }
}

#[async_trait]
impl ToolHandler for McpToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["mcp_call"]
    }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let (server_id, tool, arguments) = if let Some(dynamic) = parse_dynamic_mcp_name(tool_name)
        {
            (dynamic.0, dynamic.1, args)
        } else {
            let server_id = args
                .get("server_id")
                .and_then(|v| v.as_str())
                .ok_or_else(|| omnibot_common::AppError::invalid("mcp_call.server_id"))?
                .to_string();
            let tool = args
                .get("tool")
                .and_then(|v| v.as_str())
                .ok_or_else(|| omnibot_common::AppError::invalid("mcp_call.tool"))?
                .to_string();
            let arguments = args
                .get("arguments")
                .cloned()
                .unwrap_or(serde_json::json!({}));
            (server_id, tool, arguments)
        };

        let cfg = self
            .config_store
            .list()?
            .into_iter()
            .find(|c| c.id == server_id)
            .ok_or_else(|| omnibot_common::AppError::NotFound(format!("mcp server {server_id}")))?;
        // Initialize lazily on each call; cheap if session already cached.
        let _ = self.client.initialize(&cfg).await;
        match self.client.call_tool(&cfg, &tool, arguments).await {
            Ok(result) => Ok(ToolExecutionResult {
                success: result.success,
                summary: result.summary_text.clone(),
                stdout: result.summary_text,
                structured: result.raw_result_json,
                error: None,
                artifacts: vec![],
            }),
            Err(e) => Ok(ToolExecutionResult::err(format!("mcp call failed: {e}"))),
        }
    }
}

fn parse_dynamic_mcp_name(tool_name: &str) -> Option<(String, String)> {
    let rest = tool_name.strip_prefix("mcp__")?;
    let (server_id, tool) = rest.split_once("__")?;
    if server_id.is_empty() || tool.is_empty() {
        return None;
    }
    Some((server_id.to_string(), tool.to_string()))
}
