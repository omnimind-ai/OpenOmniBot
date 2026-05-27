use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

/// Hook for the subagent dispatcher. The actual recursion is implemented in `omnibot-agent`'s
/// `SubagentDispatcher`. Tool registration here just makes the LLM see the `subagent_dispatch` tool;
/// invocation routes back to a `SubagentRunner` injected by the backend at runtime.
pub struct SubagentToolHandler;
impl SubagentToolHandler { pub fn new() -> Self { Self } }

#[async_trait]
impl ToolHandler for SubagentToolHandler {
    fn tool_names(&self) -> &[&'static str] { &["subagent_dispatch"] }

    async fn execute(
        &self,
        _id: &str,
        _name: &str,
        _args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        // The orchestrator intercepts `subagent_dispatch` before calling this handler.
        // If we end up here, treat it as a configuration error.
        Ok(ToolExecutionResult::err("subagent_dispatch must be handled by orchestrator-level dispatcher"))
    }
}
