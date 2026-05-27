use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct MemoryToolHandler;
impl MemoryToolHandler { pub fn new() -> Self { Self } }

#[async_trait]
impl ToolHandler for MemoryToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["workspace_memory_load", "workspace_memory_save"]
    }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let mem_dir = PathBuf::from(&env.workspace.current_cwd).join(".omnibot/memory");
        let _ = std::fs::create_dir_all(&mem_dir);
        match tool_name {
            "workspace_memory_load" => {
                let key = args.get("name").and_then(|v| v.as_str()).unwrap_or("default");
                let path = mem_dir.join(format!("{key}.md"));
                let text = tokio::fs::read_to_string(&path).await.unwrap_or_default();
                Ok(ToolExecutionResult::ok(
                    format!("loaded memory '{key}' ({} bytes)", text.len()),
                    serde_json::json!({"name": key, "content": text}),
                ))
            }
            "workspace_memory_save" => {
                let key = args.get("name").and_then(|v| v.as_str()).unwrap_or("default");
                let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
                let path = mem_dir.join(format!("{key}.md"));
                tokio::fs::write(&path, content).await.ok();
                Ok(ToolExecutionResult::ok(
                    format!("saved memory '{key}'"),
                    serde_json::json!({"name": key, "bytes": content.len()}),
                ))
            }
            _ => Ok(ToolExecutionResult::err("unknown memory tool")),
        }
    }
}
