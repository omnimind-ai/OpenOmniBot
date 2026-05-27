use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;
use walkdir::WalkDir;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct SkillsToolHandler;
impl SkillsToolHandler { pub fn new() -> Self { Self } }

#[async_trait]
impl ToolHandler for SkillsToolHandler {
    fn tool_names(&self) -> &[&'static str] { &["skill_list", "skill_read"] }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let skills_dir = PathBuf::from(&env.workspace.current_cwd).join(".omnibot/skills");
        let _ = std::fs::create_dir_all(&skills_dir);
        match tool_name {
            "skill_list" => {
                let mut items = vec![];
                for e in WalkDir::new(&skills_dir).max_depth(2).into_iter().filter_map(|e| e.ok()) {
                    if e.file_type().is_dir() { continue; }
                    if e.file_name().to_string_lossy().ends_with(".md") {
                        items.push(serde_json::json!({
                            "path": e.path().display().to_string(),
                            "name": e.file_name().to_string_lossy(),
                        }));
                    }
                }
                Ok(ToolExecutionResult::ok(
                    format!("{} skill(s)", items.len()),
                    serde_json::json!({"skills": items}),
                ))
            }
            "skill_read" => {
                let name = args.get("name").and_then(|v| v.as_str()).unwrap_or("");
                let path = skills_dir.join(format!("{name}.md"));
                let text = tokio::fs::read_to_string(&path).await.unwrap_or_default();
                Ok(ToolExecutionResult::ok(
                    format!("read skill {name}"),
                    serde_json::json!({"name": name, "content": text}),
                ))
            }
            _ => Ok(ToolExecutionResult::err("unknown skills tool")),
        }
    }
}
