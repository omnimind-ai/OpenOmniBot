use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

/// Stub: dispatch to provider's image API. Wired in M2/M3 once provider routing is finalized.
pub struct ImageGenerationToolHandler;
impl ImageGenerationToolHandler { pub fn new() -> Self { Self } }

#[async_trait]
impl ToolHandler for ImageGenerationToolHandler {
    fn tool_names(&self) -> &[&'static str] { &["image_generation"] }
    async fn execute(
        &self,
        _id: &str,
        _name: &str,
        _args: serde_json::Value,
        _env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        Ok(ToolExecutionResult::err("image_generation not yet implemented on desktop"))
    }
}
