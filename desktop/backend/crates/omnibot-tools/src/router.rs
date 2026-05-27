use std::collections::HashMap;
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::{AppError, AppResult};

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

#[derive(Clone)]
pub struct ToolRouter {
    handlers_by_name: Arc<HashMap<String, Arc<dyn ToolHandler>>>,
    handler_list: Arc<Vec<Arc<dyn ToolHandler>>>,
}

impl ToolRouter {
    pub fn new(handlers: Vec<Arc<dyn ToolHandler>>) -> Self {
        let mut map: HashMap<String, Arc<dyn ToolHandler>> = HashMap::new();
        for h in &handlers {
            for name in h.tool_names() {
                map.insert((*name).to_string(), h.clone());
            }
        }
        Self {
            handlers_by_name: Arc::new(map),
            handler_list: Arc::new(handlers),
        }
    }

    pub fn handlers(&self) -> &[Arc<dyn ToolHandler>] {
        &self.handler_list
    }
    pub fn supports(&self, tool_name: &str) -> bool {
        self.handlers_by_name.contains_key(tool_name)
    }
    pub fn concurrency_hint(&self, tool_name: &str) -> ToolConcurrencyHint {
        if matches!(
            tool_name,
            "file_read"
                | "file_list"
                | "file_search"
                | "file_grep"
                | "file_glob"
                | "file_stat"
                | "context_apps_query"
                | "memory_search"
                | "memory_load"
                | "workspace_memory_load"
                | "skills_list"
                | "skills_read"
                | "skill_list"
                | "skill_read"
        ) {
            return ToolConcurrencyHint::ParallelSafe;
        }
        if self.handlers_by_name.contains_key(tool_name) {
            return ToolConcurrencyHint::SerialBarrier;
        }
        if tool_name.starts_with("mcp__") {
            return ToolConcurrencyHint::SerialBarrier;
        }
        self.handlers_by_name
            .get(tool_name)
            .map(|h| h.concurrency_hint())
            .unwrap_or(ToolConcurrencyHint::ParallelSafe)
    }

    pub async fn execute(
        &self,
        tool_call_id: &str,
        tool_name: &str,
        arguments: serde_json::Value,
        env: &AgentExecutionEnvironment,
        sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let handler = self
            .handlers_by_name
            .get(tool_name)
            .or_else(|| {
                if tool_name.starts_with("mcp__") {
                    self.handlers_by_name.get("mcp_call")
                } else {
                    None
                }
            })
            .cloned()
            .ok_or_else(|| AppError::Tool {
                tool: tool_name.into(),
                message: "unknown tool".into(),
            })?;
        handler
            .execute(tool_call_id, tool_name, arguments, env, sink)
            .await
    }
}

#[async_trait]
pub trait DefaultToolHandlers: Send + Sync {}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::handlers::FileToolHandler;

    #[test]
    fn concurrency_policy_keeps_file_reads_parallel_and_writes_serial() {
        let router = ToolRouter::new(vec![Arc::new(FileToolHandler::new())]);
        assert_eq!(router.concurrency_hint("file_read"), ToolConcurrencyHint::ParallelSafe);
        assert_eq!(router.concurrency_hint("file_search"), ToolConcurrencyHint::ParallelSafe);
        assert_eq!(router.concurrency_hint("file_write"), ToolConcurrencyHint::SerialBarrier);
        assert_eq!(router.concurrency_hint("file_move"), ToolConcurrencyHint::SerialBarrier);
    }
}
