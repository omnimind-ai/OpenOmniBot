use std::sync::Arc;

use async_trait::async_trait;
use omnibot_tools::{ToolEvent, ToolEventSink, ToolExecutionResult};

use crate::stream_event::AgentStreamEvent;

#[async_trait]
pub trait AgentCallback: Send + Sync {
    async fn on_thinking_start(&self);
    async fn on_thinking_update(&self, text: &str);
    async fn on_chat_message(&self, content: &str, is_final: bool);
    async fn on_tool_call_start(&self, tool_name: &str, args: &serde_json::Value);
    async fn on_tool_call_progress(&self, event: ToolEvent);
    async fn on_tool_call_complete(&self, tool_name: &str, result: &ToolExecutionResult);
    async fn on_prompt_token_usage_changed(&self, latest: i64, threshold: Option<i64>);
    async fn on_context_compaction_state(&self, compacting: bool, latest: Option<i64>, threshold: Option<i64>);
    async fn on_stream_event(&self, event: AgentStreamEvent);
    async fn on_error(&self, message: &str);
    async fn on_complete(&self, summary: &str);
}

#[derive(Clone)]
pub struct ToolEventSinkOverCallback {
    inner: Arc<dyn AgentCallback>,
}
impl ToolEventSinkOverCallback {
    pub fn new(inner: Arc<dyn AgentCallback>) -> Arc<dyn ToolEventSink> {
        Arc::new(Self { inner })
    }
}

#[async_trait]
impl ToolEventSink for ToolEventSinkOverCallback {
    async fn emit(&self, event: ToolEvent) {
        self.inner.on_tool_call_progress(event).await;
    }
}
