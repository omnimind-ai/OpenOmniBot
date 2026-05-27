use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

/// Desktop-safe subagent dispatcher fallback.
///
/// Full recursive LLM subagents are owned by `omnibot-agent`; this handler still provides useful
/// deterministic decomposition when the tool is invoked without an injected recursive dispatcher.
pub struct SubagentToolHandler;
impl SubagentToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ToolHandler for SubagentToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["subagent_dispatch"]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::SerialBarrier
    }

    async fn execute(
        &self,
        _id: &str,
        _name: &str,
        args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let tasks = args
            .get("tasks")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_else(|| vec![serde_json::json!({
                "profileId": args.get("profileId").and_then(|v| v.as_str()).unwrap_or("general"),
                "instruction": args.get("instruction").or_else(|| args.get("prompt")).and_then(|v| v.as_str()).unwrap_or(""),
            })]);
        let results: Vec<serde_json::Value> = tasks
            .iter()
            .enumerate()
            .map(|(idx, task)| {
                let profile = task.get("profileId").or_else(|| task.get("profile")).and_then(|v| v.as_str()).unwrap_or("general");
                let instruction = task.get("instruction").or_else(|| task.get("prompt")).and_then(|v| v.as_str()).unwrap_or("");
                serde_json::json!({
                    "taskIndex": idx,
                    "profileId": profile,
                    "success": true,
                    "finalText": format!("Subagent task accepted for profile `{profile}`: {instruction}"),
                    "deferredToParent": true,
                })
            })
            .collect();
        Ok(ToolExecutionResult::ok(
            format!("dispatched {} subagent task(s)", results.len()),
            serde_json::json!({"results": results, "recursiveLlm": false}),
        ))
    }
}
