use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use globset::{Glob, GlobSetBuilder};
use omnibot_common::{AppError, AppResult};
use walkdir::WalkDir;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

pub struct FileToolHandler;

impl FileToolHandler {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl ToolHandler for FileToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["file_read", "file_write", "file_edit", "file_glob", "file_grep", "file_stat", "file_list"]
    }
    fn concurrency_hint(&self) -> ToolConcurrencyHint { ToolConcurrencyHint::ParallelSafe }

    async fn execute(
        &self,
        _tool_call_id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let workspace_root = PathBuf::from(&env.workspace.current_cwd);
        match tool_name {
            "file_read" => file_read(&workspace_root, &args).await,
            "file_write" => file_write(&workspace_root, &args).await,
            "file_edit" => file_edit(&workspace_root, &args).await,
            "file_list" => file_list(&workspace_root, &args).await,
            "file_glob" => file_glob(&workspace_root, &args).await,
            "file_grep" => file_grep(&workspace_root, &args).await,
            "file_stat" => file_stat(&workspace_root, &args).await,
            _ => Err(AppError::Tool { tool: tool_name.into(), message: "unsupported".into() }),
        }
    }
}

fn resolve(root: &Path, p: &str) -> PathBuf {
    let path = Path::new(p);
    if path.is_absolute() { path.to_path_buf() } else { root.join(path) }
}

async fn file_read(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_read.path"))?;
    let full = resolve(root, path);
    let content = tokio::fs::read_to_string(&full).await
        .map_err(|e| AppError::Tool { tool: "file_read".into(), message: format!("{e} (path={})", full.display()) })?;
    let preview: String = content.chars().take(2000).collect();
    Ok(ToolExecutionResult {
        success: true,
        summary: format!("read {} ({} bytes)", path, content.len()),
        stdout: preview.clone(),
        structured: serde_json::json!({"path": path, "content": content, "bytes": content.len()}),
        error: None,
        artifacts: vec![],
    })
}

async fn file_write(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_write.path"))?;
    let content = args.get("content").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_write.content"))?;
    let full = resolve(root, path);
    if let Some(parent) = full.parent() { tokio::fs::create_dir_all(parent).await?; }
    tokio::fs::write(&full, content).await
        .map_err(|e| AppError::Tool { tool: "file_write".into(), message: e.to_string() })?;
    Ok(ToolExecutionResult::ok(
        format!("wrote {} ({} bytes)", path, content.len()),
        serde_json::json!({"path": path, "bytes": content.len()}),
    ))
}

async fn file_edit(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_edit.path"))?;
    let old = args.get("old_string").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_edit.old_string"))?;
    let new = args.get("new_string").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_edit.new_string"))?;
    let replace_all = args.get("replace_all").and_then(|p| p.as_bool()).unwrap_or(false);
    let full = resolve(root, path);
    let content = tokio::fs::read_to_string(&full).await
        .map_err(|e| AppError::Tool { tool: "file_edit".into(), message: e.to_string() })?;
    let new_content = if replace_all { content.replace(old, new) }
        else {
            let count = content.matches(old).count();
            if count != 1 {
                return Ok(ToolExecutionResult::err(format!("found {count} occurrences; need exactly 1, or pass replace_all=true")));
            }
            content.replacen(old, new, 1)
        };
    tokio::fs::write(&full, &new_content).await
        .map_err(|e| AppError::Tool { tool: "file_edit".into(), message: e.to_string() })?;
    Ok(ToolExecutionResult::ok(
        format!("edited {}", path),
        serde_json::json!({"path": path, "bytes": new_content.len()}),
    ))
}

async fn file_list(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let full = resolve(root, path);
    let mut entries: Vec<serde_json::Value> = vec![];
    let mut dir = tokio::fs::read_dir(&full).await
        .map_err(|e| AppError::Tool { tool: "file_list".into(), message: e.to_string() })?;
    while let Ok(Some(entry)) = dir.next_entry().await {
        let m = entry.metadata().await.ok();
        let name = entry.file_name().to_string_lossy().into_owned();
        let is_dir = m.as_ref().map(|m| m.is_dir()).unwrap_or(false);
        entries.push(serde_json::json!({
            "name": name,
            "is_dir": is_dir,
            "size": m.as_ref().map(|m| m.len()).unwrap_or(0),
        }));
    }
    Ok(ToolExecutionResult::ok(
        format!("{} entries in {}", entries.len(), path),
        serde_json::json!({"path": path, "entries": entries}),
    ))
}

async fn file_glob(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let pattern = args.get("pattern").and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_glob.pattern"))?;
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let base = resolve(root, path);
    let mut builder = GlobSetBuilder::new();
    builder.add(Glob::new(pattern).map_err(|e| AppError::invalid(format!("bad glob: {e}")))?);
    let set = builder.build().map_err(|e| AppError::invalid(format!("glob build: {e}")))?;
    let mut hits = vec![];
    for entry in WalkDir::new(&base).max_depth(8).into_iter().filter_map(|e| e.ok()) {
        let rel = entry.path().strip_prefix(&base).unwrap_or(entry.path());
        if set.is_match(rel.to_string_lossy().as_ref()) {
            hits.push(entry.path().display().to_string());
        }
        if hits.len() >= 200 { break; }
    }
    Ok(ToolExecutionResult::ok(
        format!("{} match(es) for '{}'", hits.len(), pattern),
        serde_json::json!({"matches": hits, "pattern": pattern}),
    ))
}

async fn file_grep(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let pattern = args.get("pattern").and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_grep.pattern"))?;
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let base = resolve(root, path);
    let regex = regex_lite_compile(pattern)?;
    let mut hits = vec![];
    let mut scanned = 0usize;
    for entry in WalkDir::new(&base).max_depth(8).into_iter().filter_map(|e| e.ok()) {
        if !entry.file_type().is_file() { continue; }
        scanned += 1;
        if scanned > 5000 { break; }
        if let Ok(text) = tokio::fs::read_to_string(entry.path()).await {
            for (idx, line) in text.lines().enumerate() {
                if regex.find(line).is_some() {
                    hits.push(serde_json::json!({
                        "path": entry.path().display().to_string(),
                        "line": idx + 1,
                        "text": line.chars().take(200).collect::<String>(),
                    }));
                    if hits.len() >= 200 { break; }
                }
            }
        }
        if hits.len() >= 200 { break; }
    }
    Ok(ToolExecutionResult::ok(
        format!("{} match(es) for '{}'", hits.len(), pattern),
        serde_json::json!({"hits": hits, "pattern": pattern}),
    ))
}

async fn file_stat(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).ok_or_else(|| AppError::invalid("file_stat.path"))?;
    let full = resolve(root, path);
    let m = tokio::fs::metadata(&full).await
        .map_err(|e| AppError::Tool { tool: "file_stat".into(), message: e.to_string() })?;
    let modified_ms = m.modified().ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64);
    Ok(ToolExecutionResult::ok(
        format!("stat {}", path),
        serde_json::json!({
            "path": path,
            "is_dir": m.is_dir(),
            "is_file": m.is_file(),
            "size": m.len(),
            "modified_ms": modified_ms,
        }),
    ))
}

// Minimal regex compile via std-only substring; we don't depend on regex crate to keep dep-tree small.
// Pattern: if it contains regex metacharacters, fall back to substring match.
struct RegexLite { needle: String }
impl RegexLite {
    fn find(&self, hay: &str) -> Option<usize> { hay.find(&self.needle) }
}
fn regex_lite_compile(p: &str) -> AppResult<RegexLite> { Ok(RegexLite { needle: p.to_string() }) }
