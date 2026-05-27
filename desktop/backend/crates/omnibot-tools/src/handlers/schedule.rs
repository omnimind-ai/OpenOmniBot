use std::path::{Path, PathBuf};
use std::sync::Arc;

use async_trait::async_trait;
use omnibot_common::{AppError, AppResult, now_unix_ms};
use uuid::Uuid;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEventSink, ToolExecutionResult, ToolHandler,
};

pub struct ScheduleToolHandler;
impl ScheduleToolHandler {
    pub fn new() -> Self {
        Self
    }
}

#[async_trait]
impl ToolHandler for ScheduleToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &[
            "schedule_task_create",
            "schedule_task_list",
            "schedule_task_update",
            "schedule_task_delete",
        ]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::SerialBarrier
    }

    async fn execute(
        &self,
        _id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        _sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let store = schedule_path(env);
        match tool_name {
            "schedule_task_create" => create_task(&store, args).await,
            "schedule_task_list" => list_tasks(&store).await,
            "schedule_task_update" => update_task(&store, args).await,
            "schedule_task_delete" => delete_task(&store, args).await,
            _ => Ok(ToolExecutionResult::err("unknown schedule tool")),
        }
    }
}

fn schedule_path(env: &AgentExecutionEnvironment) -> PathBuf {
    PathBuf::from(&env.workspace.current_cwd).join(".omnibot/scheduled_tasks.json")
}

async fn load(path: &Path) -> AppResult<Vec<serde_json::Value>> {
    if !path.exists() {
        return Ok(vec![]);
    }
    let text = tokio::fs::read_to_string(path).await?;
    Ok(serde_json::from_str::<Vec<serde_json::Value>>(&text).unwrap_or_default())
}

async fn save(path: &Path, tasks: &[serde_json::Value]) -> AppResult<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(path, serde_json::to_vec_pretty(tasks)?).await?;
    Ok(())
}

async fn create_task(path: &Path, mut args: serde_json::Value) -> AppResult<ToolExecutionResult> {
    let now = now_unix_ms();
    let id = args
        .get("id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("sched_{}", Uuid::new_v4().simple()));
    if let Some(obj) = args.as_object_mut() {
        let title = obj
            .get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("Scheduled task")
            .to_string();
        obj.insert("id".into(), serde_json::Value::String(id.clone()));
        obj.entry("title")
            .or_insert_with(|| serde_json::Value::String(title));
        obj.entry("enabled")
            .or_insert(serde_json::Value::Bool(true));
        obj.entry("createdAtMs").or_insert(serde_json::json!(now));
        obj.insert("updatedAtMs".into(), serde_json::json!(now));
        obj.entry("runtime").or_insert(serde_json::json!({
            "desktop": true,
            "firesOnlyWhileAppRunning": true,
        }));
    }
    let mut tasks = load(path).await?;
    tasks.retain(|task| task.get("id").and_then(|v| v.as_str()) != Some(id.as_str()));
    tasks.push(args.clone());
    save(path, &tasks).await?;
    Ok(ToolExecutionResult::ok(
        format!("created scheduled task {id}"),
        serde_json::json!({"task": args, "firesOnlyWhileAppRunning": true}),
    ))
}

async fn list_tasks(path: &Path) -> AppResult<ToolExecutionResult> {
    let tasks = load(path).await?;
    Ok(ToolExecutionResult::ok(
        format!("{} scheduled task(s)", tasks.len()),
        serde_json::json!({"tasks": tasks, "firesOnlyWhileAppRunning": true}),
    ))
}

async fn update_task(path: &Path, args: serde_json::Value) -> AppResult<ToolExecutionResult> {
    let id = args
        .get("id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::invalid("schedule_task_update.id"))?;
    let patch = args.get("patch").cloned().unwrap_or_else(|| args.clone());
    let mut tasks = load(path).await?;
    let mut updated = None;
    for task in &mut tasks {
        if task.get("id").and_then(|v| v.as_str()) == Some(id) {
            merge(task, &patch);
            if let Some(obj) = task.as_object_mut() {
                obj.insert("updatedAtMs".into(), serde_json::json!(now_unix_ms()));
            }
            updated = Some(task.clone());
            break;
        }
    }
    let updated = updated.ok_or_else(|| AppError::NotFound(format!("scheduled task {id}")))?;
    save(path, &tasks).await?;
    Ok(ToolExecutionResult::ok(
        format!("updated scheduled task {id}"),
        serde_json::json!({"task": updated}),
    ))
}

async fn delete_task(path: &Path, args: serde_json::Value) -> AppResult<ToolExecutionResult> {
    let id = args
        .get("id")
        .or_else(|| args.get("taskId"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| AppError::invalid("schedule_task_delete.id"))?;
    let mut tasks = load(path).await?;
    let before = tasks.len();
    tasks.retain(|task| task.get("id").and_then(|v| v.as_str()) != Some(id));
    save(path, &tasks).await?;
    Ok(ToolExecutionResult::ok(
        format!("deleted scheduled task {id}"),
        serde_json::json!({"id": id, "deleted": tasks.len() != before}),
    ))
}

fn merge(base: &mut serde_json::Value, patch: &serde_json::Value) {
    if let (Some(base), Some(patch)) = (base.as_object_mut(), patch.as_object()) {
        for (k, v) in patch {
            if k == "id" {
                continue;
            }
            base.insert(k.clone(), v.clone());
        }
    }
}
