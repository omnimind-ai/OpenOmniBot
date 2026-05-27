use async_trait::async_trait;
use omnibot_common::{AppError, AppResult, WorkspaceDescriptor};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// Snapshot of execution context available to all tool handlers.
#[derive(Clone, Debug)]
pub struct AgentExecutionEnvironment {
    pub agent_run_id: String,
    pub user_message: String,
    pub workspace: WorkspaceDescriptor,
    pub conversation_mode: String,
    pub reasoning_effort: Option<String>,
    pub terminal_env: std::collections::HashMap<String, String>,
}

#[derive(Clone, Debug, Default)]
pub struct ToolExecutionResult {
    pub success: bool,
    pub summary: String,
    pub stdout: String,
    pub structured: serde_json::Value,
    pub error: Option<String>,
    pub artifacts: Vec<serde_json::Value>,
}

impl ToolExecutionResult {
    pub fn ok(summary: impl Into<String>, structured: serde_json::Value) -> Self {
        Self {
            success: true,
            summary: summary.into(),
            stdout: String::new(),
            structured,
            error: None,
            artifacts: vec![],
        }
    }
    pub fn err(message: impl Into<String>) -> Self {
        let m = message.into();
        Self {
            success: false,
            summary: m.clone(),
            stdout: String::new(),
            structured: serde_json::Value::Null,
            error: Some(m),
            artifacts: vec![],
        }
    }

    pub fn to_payload(&self) -> serde_json::Value {
        serde_json::json!({
            "ok": self.success,
            "success": self.success,
            "summary": self.summary,
            "stdout": self.stdout,
            "data": self.structured,
            "structured": self.structured,
            "error": self.error,
            "artifacts": self.artifacts,
        })
    }
}

/// Hint used by the orchestrator's batching policy.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ToolConcurrencyHint {
    /// Safe to run in parallel with other PARALLEL_SAFE tools.
    ParallelSafe,
    /// Must run alone in a serial barrier (e.g. terminal session mutations).
    SerialBarrier,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolEvent {
    pub task_id: String,
    pub tool_call_id: String,
    pub tool_name: String,
    pub kind: String,   // "start" | "progress" | "complete" | "partial"
    pub status: String, // "pending" | "running" | "succeeded" | "failed"
    pub args_json: String,
    pub progress: String,
    pub partial_text: String,
    pub result_preview_json: String,
}

#[async_trait]
pub trait ToolEventSink: Send + Sync {
    async fn emit(&self, event: ToolEvent);
}

#[async_trait]
pub trait ToolHandler: Send + Sync {
    fn tool_names(&self) -> &[&'static str];
    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::ParallelSafe
    }
    async fn execute(
        &self,
        tool_call_id: &str,
        tool_name: &str,
        arguments: serde_json::Value,
        env: &AgentExecutionEnvironment,
        sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult>;
}

pub fn require_string<'a>(v: &'a serde_json::Value, field: &str) -> AppResult<&'a str> {
    v.get(field)
        .and_then(|x| x.as_str())
        .ok_or_else(|| AppError::invalid(format!("missing string field '{field}'")))
}
