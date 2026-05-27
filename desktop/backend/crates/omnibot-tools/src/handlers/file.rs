use std::path::{Component, Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use globset::{Glob, GlobSetBuilder};
use omnibot_common::{AppError, AppResult};
use walkdir::WalkDir;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

const DEFAULT_READ_LIMIT_CHARS: usize = 200_000;
const DEFAULT_MAX_RESULTS: usize = 200;
const MAX_WALK_FILES: usize = 10_000;

pub struct FileToolHandler;

impl FileToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ToolHandler for FileToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &[
            "file_read",
            "file_write",
            "file_edit",
            "file_glob",
            "file_grep",
            "file_search",
            "file_stat",
            "file_list",
            "file_move",
        ]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::ParallelSafe
    }

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
            "file_grep" | "file_search" => file_search(&workspace_root, tool_name, &args).await,
            "file_stat" => file_stat(&workspace_root, &args).await,
            "file_move" => file_move(&workspace_root, &args).await,
            _ => Err(AppError::Tool {
                tool: tool_name.into(),
                message: "unsupported".into(),
            }),
        }
    }
}

fn normalized(path: PathBuf) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

fn workspace_root(root: &Path) -> PathBuf {
    root.canonicalize()
        .unwrap_or_else(|_| normalized(root.to_path_buf()))
}

fn resolve_under_root(root: &Path, p: &str) -> AppResult<PathBuf> {
    let root = workspace_root(root);
    let candidate = Path::new(p);
    let joined = if candidate.is_absolute() {
        candidate.to_path_buf()
    } else {
        root.join(candidate)
    };
    let full = normalized(joined);
    if !full.starts_with(&root) {
        return Err(AppError::Tool {
            tool: "file".into(),
            message: format!("path escapes workspace: {p}"),
        });
    }
    Ok(full)
}

fn display_path(root: &Path, path: &Path) -> String {
    path.strip_prefix(&workspace_root(root))
        .unwrap_or(path)
        .to_string_lossy()
        .to_string()
}

async fn file_read(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args
        .get("path")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_read.path"))?;
    let offset = args.get("offset").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
    let limit = args
        .get("limit")
        .or_else(|| args.get("max_chars"))
        .and_then(|v| v.as_u64())
        .map(|v| v as usize)
        .unwrap_or(DEFAULT_READ_LIMIT_CHARS)
        .min(DEFAULT_READ_LIMIT_CHARS);
    let full = resolve_under_root(root, path)?;
    let content = tokio::fs::read_to_string(&full)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_read".into(),
            message: format!("{e} (path={})", full.display()),
        })?;
    let total_chars = content.chars().count();
    let slice: String = content.chars().skip(offset).take(limit).collect();
    let truncated = offset + slice.chars().count() < total_chars;
    Ok(ToolExecutionResult {
        success: true,
        summary: format!(
            "read {} ({} bytes{})",
            path,
            content.len(),
            if truncated { ", truncated" } else { "" }
        ),
        stdout: slice.clone(),
        structured: serde_json::json!({
            "path": path,
            "content": slice,
            "bytes": content.len(),
            "totalChars": total_chars,
            "offset": offset,
            "limit": limit,
            "truncated": truncated,
        }),
        error: None,
        artifacts: vec![],
    })
}

async fn file_write(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args
        .get("path")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_write.path"))?;
    let content = args
        .get("content")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_write.content"))?;
    let full = resolve_under_root(root, path)?;
    if let Some(parent) = full.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(&full, content)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_write".into(),
            message: e.to_string(),
        })?;
    Ok(ToolExecutionResult::ok(
        format!("wrote {} ({} bytes)", path, content.len()),
        serde_json::json!({"path": path, "bytes": content.len()}),
    ))
}

async fn file_edit(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args
        .get("path")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_edit.path"))?;
    let old = args
        .get("old_string")
        .or_else(|| args.get("oldString"))
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_edit.old_string"))?;
    let new = args
        .get("new_string")
        .or_else(|| args.get("newString"))
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_edit.new_string"))?;
    let replace_all = args
        .get("replace_all")
        .or_else(|| args.get("replaceAll"))
        .and_then(|p| p.as_bool())
        .unwrap_or(false);
    let full = resolve_under_root(root, path)?;
    let content = tokio::fs::read_to_string(&full)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_edit".into(),
            message: e.to_string(),
        })?;
    let new_content = if replace_all {
        content.replace(old, new)
    } else {
        let count = content.matches(old).count();
        if count != 1 {
            return Ok(ToolExecutionResult::err(format!(
                "found {count} occurrences; need exactly 1, or pass replace_all=true"
            )));
        }
        content.replacen(old, new, 1)
    };
    tokio::fs::write(&full, &new_content)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_edit".into(),
            message: e.to_string(),
        })?;
    Ok(ToolExecutionResult::ok(
        format!("edited {}", path),
        serde_json::json!({"path": path, "bytes": new_content.len()}),
    ))
}

async fn file_list(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let limit = args
        .get("limit")
        .and_then(|p| p.as_u64())
        .unwrap_or(500)
        .min(2_000) as usize;
    let full = resolve_under_root(root, path)?;
    let mut entries: Vec<serde_json::Value> = vec![];
    let mut dir = tokio::fs::read_dir(&full)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_list".into(),
            message: e.to_string(),
        })?;
    while let Ok(Some(entry)) = dir.next_entry().await {
        let m = entry.metadata().await.ok();
        let name = entry.file_name().to_string_lossy().into_owned();
        let is_dir = m.as_ref().map(|m| m.is_dir()).unwrap_or(false);
        entries.push(serde_json::json!({
            "name": name,
            "path": display_path(root, &entry.path()),
            "is_dir": is_dir,
            "isDir": is_dir,
            "size": m.as_ref().map(|m| m.len()).unwrap_or(0),
        }));
        if entries.len() >= limit {
            break;
        }
    }
    entries.sort_by_key(|v| {
        (
            !v.get("is_dir").and_then(|b| b.as_bool()).unwrap_or(false),
            v.get("name")
                .and_then(|s| s.as_str())
                .unwrap_or("")
                .to_string(),
        )
    });
    Ok(ToolExecutionResult::ok(
        format!("{} entries in {}", entries.len(), path),
        serde_json::json!({"path": path, "entries": entries}),
    ))
}

async fn file_glob(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let pattern = args
        .get("pattern")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_glob.pattern"))?;
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let max_results = max_results(args);
    let base = resolve_under_root(root, path)?;
    let mut builder = GlobSetBuilder::new();
    builder.add(Glob::new(pattern).map_err(|e| AppError::invalid(format!("bad glob: {e}")))?);
    let set = builder
        .build()
        .map_err(|e| AppError::invalid(format!("glob build: {e}")))?;
    let mut hits = vec![];
    for entry in WalkDir::new(&base)
        .max_depth(12)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        let rel = entry.path().strip_prefix(&base).unwrap_or(entry.path());
        if set.is_match(rel.to_string_lossy().as_ref()) {
            hits.push(display_path(root, entry.path()));
        }
        if hits.len() >= max_results {
            break;
        }
    }
    Ok(ToolExecutionResult::ok(
        format!("{} match(es) for '{}'", hits.len(), pattern),
        serde_json::json!({"matches": hits, "pattern": pattern}),
    ))
}

async fn file_search(
    root: &Path,
    tool_name: &str,
    args: &serde_json::Value,
) -> AppResult<ToolExecutionResult> {
    let pattern = args
        .get("query")
        .or_else(|| args.get("pattern"))
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid(format!("{tool_name}.query")))?;
    let path = args.get("path").and_then(|p| p.as_str()).unwrap_or(".");
    let case_sensitive = args
        .get("case_sensitive")
        .or_else(|| args.get("caseSensitive"))
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let max_results = max_results(args);
    let base = resolve_under_root(root, path)?;
    let needle = if case_sensitive {
        pattern.to_string()
    } else {
        pattern.to_lowercase()
    };
    let mut hits = vec![];
    let mut scanned = 0usize;
    for entry in WalkDir::new(&base)
        .max_depth(12)
        .into_iter()
        .filter_map(|e| e.ok())
    {
        if !entry.file_type().is_file() {
            continue;
        }
        scanned += 1;
        if scanned > MAX_WALK_FILES {
            break;
        }
        if is_probably_binary(entry.path()).await {
            continue;
        }
        if let Ok(text) = tokio::fs::read_to_string(entry.path()).await {
            for (idx, line) in text.lines().enumerate() {
                let hay = if case_sensitive {
                    line.to_string()
                } else {
                    line.to_lowercase()
                };
                if hay.contains(&needle) {
                    hits.push(serde_json::json!({
                        "path": display_path(root, entry.path()),
                        "line": idx + 1,
                        "text": line.chars().take(300).collect::<String>(),
                    }));
                    if hits.len() >= max_results {
                        break;
                    }
                }
            }
        }
        if hits.len() >= max_results {
            break;
        }
    }
    Ok(ToolExecutionResult::ok(
        format!("{} match(es) for '{}'", hits.len(), pattern),
        serde_json::json!({
            "hits": hits,
            "matches": hits,
            "query": pattern,
            "pattern": pattern,
            "caseSensitive": case_sensitive,
            "scannedFiles": scanned,
        }),
    ))
}

async fn is_probably_binary(path: &Path) -> bool {
    match tokio::fs::read(path).await {
        Ok(bytes) => bytes.iter().take(1024).any(|b| *b == 0),
        Err(_) => false,
    }
}

async fn file_stat(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let path = args
        .get("path")
        .and_then(|p| p.as_str())
        .ok_or_else(|| AppError::invalid("file_stat.path"))?;
    let full = resolve_under_root(root, path)?;
    let m = tokio::fs::metadata(&full)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_stat".into(),
            message: e.to_string(),
        })?;
    let modified_ms = m
        .modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64);
    Ok(ToolExecutionResult::ok(
        format!("stat {}", path),
        serde_json::json!({
            "path": path,
            "is_dir": m.is_dir(),
            "isDir": m.is_dir(),
            "is_file": m.is_file(),
            "isFile": m.is_file(),
            "size": m.len(),
            "modified_ms": modified_ms,
            "modifiedMs": modified_ms,
        }),
    ))
}

async fn file_move(root: &Path, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
    let from = args
        .get("from")
        .or_else(|| args.get("source"))
        .or_else(|| args.get("source_path"))
        .or_else(|| args.get("sourcePath"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::invalid("file_move.from"))?;
    let to = args
        .get("to")
        .or_else(|| args.get("destination"))
        .or_else(|| args.get("destination_path"))
        .or_else(|| args.get("destinationPath"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::invalid("file_move.to"))?;
    let overwrite = args
        .get("overwrite")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);
    let from_full = resolve_under_root(root, from)?;
    let to_full = resolve_under_root(root, to)?;
    if !overwrite && tokio::fs::try_exists(&to_full).await.unwrap_or(false) {
        return Ok(ToolExecutionResult::err(format!(
            "destination already exists: {to}"
        )));
    }
    if let Some(parent) = to_full.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    if overwrite && tokio::fs::try_exists(&to_full).await.unwrap_or(false) {
        let meta = tokio::fs::metadata(&to_full).await?;
        if meta.is_dir() {
            tokio::fs::remove_dir_all(&to_full).await?;
        } else {
            tokio::fs::remove_file(&to_full).await?;
        }
    }
    tokio::fs::rename(&from_full, &to_full)
        .await
        .map_err(|e| AppError::Tool {
            tool: "file_move".into(),
            message: e.to_string(),
        })?;
    Ok(ToolExecutionResult::ok(
        format!("moved {from} to {to}"),
        serde_json::json!({"from": from, "to": to, "overwrite": overwrite}),
    ))
}

fn max_results(args: &serde_json::Value) -> usize {
    args.get("max_results")
        .or_else(|| args.get("maxResults"))
        .or_else(|| args.get("limit"))
        .and_then(|v| v.as_u64())
        .map(|v| v as usize)
        .unwrap_or(DEFAULT_MAX_RESULTS)
        .min(1_000)
}
