use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct ContextToolHandler;

impl ContextToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ToolHandler for ContextToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &[
            "context_stop_conversation",
            "context_apps_query",
            "vlm_task",
        ]
    }

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
            "context_apps_query" => Ok(ToolExecutionResult::ok(
                "queried desktop applications",
                serde_json::json!({"apps": list_desktop_apps()}),
            )),
            "vlm_task" => Ok(ToolExecutionResult::err(
                "vlm_task is Android screen automation and is not supported on desktop in this phase",
            )),
            _ => Ok(ToolExecutionResult::err("unknown context tool")),
        }
    }
}

fn list_desktop_apps() -> Vec<serde_json::Value> {
    let mut dirs = vec![];
    if cfg!(target_os = "macos") {
        dirs.push(PathBuf::from("/Applications"));
        if let Some(home) = std::env::var_os("HOME") {
            dirs.push(PathBuf::from(home).join("Applications"));
        }
    }
    let mut apps = vec![];
    for dir in dirs {
        if let Ok(read_dir) = std::fs::read_dir(dir) {
            for entry in read_dir.flatten().take(500) {
                let path = entry.path();
                if path.extension().and_then(|e| e.to_str()) == Some("app") {
                    let name = path
                        .file_stem()
                        .map(|s| s.to_string_lossy().to_string())
                        .unwrap_or_default();
                    apps.push(serde_json::json!({
                        "name": name,
                        "path": path.display().to_string(),
                        "platform": std::env::consts::OS,
                    }));
                }
            }
        }
    }
    apps
}
