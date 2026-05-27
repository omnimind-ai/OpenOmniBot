use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use chrono::Utc;
use omnibot_common::AppResult;
use walkdir::WalkDir;

use crate::types::{AgentExecutionEnvironment, ToolEventSink, ToolExecutionResult, ToolHandler};

pub struct MemoryToolHandler;
impl MemoryToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ToolHandler for MemoryToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &[
            "workspace_memory_load",
            "workspace_memory_save",
            "memory_search",
            "memory_write_daily",
            "memory_upsert_longterm",
            "memory_rollup_day",
            "memory_load",
        ]
    }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let mem_dir = memory_dir(env);
        let _ = std::fs::create_dir_all(&mem_dir);
        match tool_name {
            "workspace_memory_load" => {
                let key = args
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("default");
                load_note(&mem_dir, key).await
            }
            "workspace_memory_save" => {
                let key = args
                    .get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("default");
                let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
                save_note(&mem_dir, key, content, false).await
            }
            "memory_load" => {
                let slug = args
                    .get("slug")
                    .or_else(|| args.get("name"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("longterm");
                load_note(&mem_dir, slug).await
            }
            "memory_write_daily" => write_daily(&mem_dir, &args).await,
            "memory_upsert_longterm" => upsert_longterm(&mem_dir, &args).await,
            "memory_search" => search_memory(&mem_dir, &args).await,
            "memory_rollup_day" => rollup_day(&mem_dir, &args).await,
            _ => Ok(ToolExecutionResult::err("unknown memory tool")),
        }
    }
}

fn memory_dir(env: &AgentExecutionEnvironment) -> PathBuf {
    PathBuf::from(&env.workspace.current_cwd).join(".omnibot/memory")
}

fn safe_slug(raw: &str) -> String {
    let trimmed = raw.trim().trim_end_matches(".md");
    let safe: String = trimmed
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '_'
            }
        })
        .collect();
    if safe.is_empty() {
        "default".into()
    } else {
        safe
    }
}

fn note_path(mem_dir: &Path, slug: &str) -> PathBuf {
    mem_dir.join(format!("{}.md", safe_slug(slug)))
}

async fn load_note(mem_dir: &Path, key: &str) -> AppResult<ToolExecutionResult> {
    let slug = safe_slug(key);
    let path = note_path(mem_dir, &slug);
    let text = tokio::fs::read_to_string(&path).await.unwrap_or_default();
    Ok(ToolExecutionResult::ok(
        format!("loaded memory '{slug}' ({} bytes)", text.len()),
        serde_json::json!({"slug": slug, "name": slug, "content": text}),
    ))
}

async fn save_note(
    mem_dir: &Path,
    key: &str,
    content: &str,
    append: bool,
) -> AppResult<ToolExecutionResult> {
    let slug = safe_slug(key);
    let path = note_path(mem_dir, &slug);
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let final_content = if append {
        let mut existing = tokio::fs::read_to_string(&path).await.unwrap_or_default();
        if !existing.ends_with('\n') && !existing.is_empty() {
            existing.push('\n');
        }
        existing.push_str(content);
        if !existing.ends_with('\n') {
            existing.push('\n');
        }
        existing
    } else {
        content.to_string()
    };
    tokio::fs::write(&path, &final_content).await?;
    Ok(ToolExecutionResult::ok(
        format!("saved memory '{slug}'"),
        serde_json::json!({"slug": slug, "bytes": final_content.len(), "path": path.display().to_string()}),
    ))
}

async fn write_daily(mem_dir: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let content = args
        .get("content")
        .or_else(|| args.get("text"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let date = args
        .get("date")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| Utc::now().format("%Y-%m-%d").to_string());
    let entry = format!("- {} {}\n", Utc::now().format("%H:%M"), content.trim());
    save_note(mem_dir, &format!("daily/{date}"), &entry, true).await
}

async fn upsert_longterm(
    mem_dir: &Path,
    args: &serde_json::Value,
) -> AppResult<ToolExecutionResult> {
    let slug = args
        .get("slug")
        .or_else(|| args.get("name"))
        .or_else(|| args.get("title"))
        .and_then(|v| v.as_str())
        .unwrap_or("longterm");
    let title = args.get("title").and_then(|v| v.as_str()).unwrap_or(slug);
    let content = args
        .get("content")
        .or_else(|| args.get("text"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let body = if content.trim_start().starts_with('#') {
        content.to_string()
    } else {
        format!("# {title}\n\n{content}\n")
    };
    save_note(
        mem_dir,
        &format!("longterm/{}", safe_slug(slug)),
        &body,
        false,
    )
    .await
}

async fn search_memory(mem_dir: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let query = args
        .get("query")
        .or_else(|| args.get("keyword"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let max_results = args
        .get("limit")
        .or_else(|| args.get("maxResults"))
        .and_then(|v| v.as_u64())
        .unwrap_or(20)
        .min(100) as usize;
    let needle = query.to_lowercase();
    let mut hits = vec![];
    for entry in WalkDir::new(mem_dir)
        .max_depth(4)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        if !entry.file_type().is_file()
            || entry.path().extension().and_then(|e| e.to_str()) != Some("md")
        {
            continue;
        }
        let text = tokio::fs::read_to_string(entry.path())
            .await
            .unwrap_or_default();
        let lower = text.to_lowercase();
        if needle.is_empty() || lower.contains(&needle) {
            let slug = entry
                .path()
                .strip_prefix(mem_dir)
                .unwrap_or(entry.path())
                .to_string_lossy()
                .trim_end_matches(".md")
                .to_string();
            let snippet = snippet_for(&text, &needle);
            hits.push(serde_json::json!({
                "slug": slug,
                "path": entry.path().display().to_string(),
                "snippet": snippet,
                "bytes": text.len(),
            }));
            if hits.len() >= max_results {
                break;
            }
        }
    }
    Ok(ToolExecutionResult::ok(
        format!("{} memory hit(s)", hits.len()),
        serde_json::json!({"query": query, "hits": hits}),
    ))
}

async fn rollup_day(mem_dir: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let date = args
        .get("date")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| Utc::now().format("%Y-%m-%d").to_string());
    let daily_path = note_path(mem_dir, &format!("daily/{date}"));
    let daily = tokio::fs::read_to_string(&daily_path)
        .await
        .unwrap_or_default();
    let bullets: Vec<&str> = daily
        .lines()
        .filter(|line| !line.trim().is_empty())
        .take(50)
        .collect();
    let summary = if bullets.is_empty() {
        format!("# Daily rollup {date}\n\nNo daily memory entries were recorded.\n")
    } else {
        format!("# Daily rollup {date}\n\n{}\n", bullets.join("\n"))
    };
    save_note(mem_dir, &format!("rollups/{date}"), &summary, false).await
}

fn snippet_for(text: &str, needle: &str) -> String {
    if needle.is_empty() {
        return text.chars().take(300).collect();
    }
    let lower = text.to_lowercase();
    let idx = lower.find(needle).unwrap_or(0);
    let start = text[..idx.min(text.len())]
        .chars()
        .count()
        .saturating_sub(80);
    text.chars().skip(start).take(300).collect()
}
