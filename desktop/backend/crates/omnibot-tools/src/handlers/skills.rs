use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::AppResult;
use walkdir::WalkDir;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct SkillsToolHandler;
impl SkillsToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[derive(Debug, Clone)]
struct SkillHit {
    id: String,
    name: String,
    path: PathBuf,
    source: &'static str,
}

#[async_trait]
impl ToolHandler for SkillsToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["skill_list", "skill_read", "skills_list", "skills_read"]
    }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let workspace_dir = PathBuf::from(&env.workspace.current_cwd).join(".omnibot/skills");
        let _ = std::fs::create_dir_all(&workspace_dir);
        match tool_name {
            "skill_list" | "skills_list" => {
                let items: Vec<serde_json::Value> = collect_skills(&workspace_dir)
                    .into_iter()
                    .map(|hit| {
                        serde_json::json!({
                            "id": hit.id,
                            "name": hit.name,
                            "path": hit.path.display().to_string(),
                            "source": hit.source,
                        })
                    })
                    .collect();
                Ok(ToolExecutionResult::ok(
                    format!("{} skill(s)", items.len()),
                    serde_json::json!({"skills": items}),
                ))
            }
            "skill_read" | "skills_read" => {
                let name = args
                    .get("name")
                    .or_else(|| args.get("id"))
                    .or_else(|| args.get("skill"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                let hits = collect_skills(&workspace_dir);
                let selected = find_skill(&hits, name);
                match selected {
                    Some(hit) => {
                        let text = tokio::fs::read_to_string(&hit.path)
                            .await
                            .unwrap_or_default();
                        Ok(ToolExecutionResult::ok(
                            format!("read skill {}", hit.id),
                            serde_json::json!({
                                "id": hit.id,
                                "name": hit.name,
                                "path": hit.path.display().to_string(),
                                "source": hit.source,
                                "content": text,
                            }),
                        ))
                    }
                    None => Ok(ToolExecutionResult::err(format!("skill not found: {name}"))),
                }
            }
            _ => Ok(ToolExecutionResult::err("unknown skills tool")),
        }
    }
}

fn collect_skills(workspace_dir: &Path) -> Vec<SkillHit> {
    let mut out = vec![];
    collect_from_dir(workspace_dir, "workspace", &mut out);
    if let Some(dir) = builtin_skills_dir() {
        collect_from_dir(&dir, "builtin", &mut out);
    }
    out.sort_by(|a, b| a.id.cmp(&b.id).then(a.source.cmp(b.source)));
    out.dedup_by(|a, b| a.id == b.id && a.source == b.source);
    out
}

fn collect_from_dir(dir: &Path, source: &'static str, out: &mut Vec<SkillHit>) {
    if !dir.exists() {
        return;
    }
    for e in WalkDir::new(dir)
        .max_depth(3)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        if e.file_type().is_dir() {
            continue;
        }
        let file_name = e.file_name().to_string_lossy();
        if file_name != "SKILL.md" && !file_name.ends_with(".md") {
            continue;
        }
        let id = if file_name == "SKILL.md" {
            e.path()
                .parent()
                .and_then(|p| p.file_name())
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "skill".into())
        } else {
            file_name.trim_end_matches(".md").to_string()
        };
        out.push(SkillHit {
            id: id.clone(),
            name: id,
            path: e.path().to_path_buf(),
            source,
        });
    }
}

fn find_skill<'a>(hits: &'a [SkillHit], needle: &str) -> Option<&'a SkillHit> {
    let normalized = needle.trim().trim_end_matches(".md");
    hits.iter().find(|hit| {
        hit.id == normalized
            || hit.name == normalized
            || hit.path.display().to_string() == needle
            || hit
                .path
                .parent()
                .map(|p| p.display().to_string() == needle)
                .unwrap_or(false)
    })
}

fn builtin_skills_dir() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("OMNIBOT_BUILTIN_SKILLS_DIR") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p);
        }
    }
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    for ancestor in manifest.ancestors() {
        let candidate = ancestor.join("app/src/main/assets/builtin_skills");
        if candidate.exists() {
            return Some(candidate);
        }
    }
    None
}
