use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct ContextToolHandler;

impl ContextToolHandler {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl ToolHandler for ContextToolHandler {
    fn tool_names(&self) -> &[&'static str] { &["context_stop_conversation"] }

    async fn execute(
        &self,
        _tool_call_id: &str,
        tool_name: &str,
        _args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        match tool_name {
            "context_stop_conversation" => Ok(ToolExecutionResult {
                success: true,
                summary: "conversation stop requested".into(),
                stdout: String::new(),
                structured: serde_json::json!({"action": "stop_conversation"}),
                error: None,
                artifacts: vec![],
            }),
            _ => Ok(ToolExecutionResult::err("unknown context tool")),
        }
    }
}
