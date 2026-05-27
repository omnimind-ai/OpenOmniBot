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

    pub fn handlers(&self) -> &[Arc<dyn ToolHandler>] { &self.handler_list }
    pub fn supports(&self, tool_name: &str) -> bool { self.handlers_by_name.contains_key(tool_name) }
    pub fn concurrency_hint(&self, tool_name: &str) -> ToolConcurrencyHint {
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
            .cloned()
            .ok_or_else(|| AppError::Tool { tool: tool_name.into(), message: "unknown tool".into() })?;
        handler.execute(tool_call_id, tool_name, arguments, env, sink).await
    }
}

#[async_trait]
pub trait DefaultToolHandlers: Send + Sync {}
