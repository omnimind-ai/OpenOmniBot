use std::path::PathBuf;
use std::sync::Arc;

use omnibot_common::{AppError, AppResult, now_unix_ms};

use crate::api::ws::WsSession;

pub mod agent_task;
pub mod conversation;
pub mod memory;
pub mod model_provider;
pub mod runtime_log;
pub mod skill;

pub async fn route(
    method: &str,
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        // --- Chat & Agent tasks ---
        "createChatTask" => agent_task::create_chat_task(args, session).await,
        "createAgentTask" => agent_task::create_agent_task(args, session).await,
        "cancelChatTask" | "cancelTask" | "cancelRunningTask" => {
            agent_task::cancel(args, session).await
        }

        // --- Conversation CRUD ---
        "getConversations" => conversation::list(args, session).await,
        "getConversation" => conversation::get(args, session).await,
        "createConversation" => conversation::create(args, session).await,
        "updateConversation" => conversation::update(args, session).await,
        "updateConversationTitle" => conversation::update_title(args, session).await,
        "updateConversationPromptTokenThreshold" => {
            conversation::update_prompt_token_threshold(args, session).await
        }
        "deleteConversation" => conversation::delete(args, session).await,
        "archiveConversation" => conversation::archive(args, session).await,
        "pinConversation" => conversation::pin(args, session).await,
        "getConversationMessages" => conversation::messages(args, session).await,
        "replaceConversationMessages" => conversation::replace_messages(args, session).await,
        "clearConversationMessages" => conversation::clear_messages(args, session).await,
        "upsertConversationUiCard" => conversation::upsert_ui_card(args, session).await,

        // --- Model provider profiles ---
        "getModelProviderConfig" => model_provider::get_config(args, session).await,
        "saveModelProviderConfig" => model_provider::save_config(args, session).await,
        "listModelProviderProfiles" => model_provider::list_profiles(args, session).await,
        "saveModelProviderProfile" => model_provider::save_profile(args, session).await,
        "deleteModelProviderProfile" => model_provider::delete_profile(args, session).await,
        "setEditingModelProviderProfile" => model_provider::set_editing(args, session).await,
        "fetchProviderModels" => model_provider::fetch_models(args, session).await,
        "checkModelAvailability" => model_provider::check_availability(args, session).await,
        "getSceneModelBindings" => model_provider::list_scene_bindings(args, session).await,
        "saveSceneModelBinding" => model_provider::save_scene_binding(args, session).await,
        "clearSceneModelBinding" => model_provider::clear_scene_binding(args, session).await,
        "getSceneModelOverrides" => model_provider::list_scene_overrides(args, session).await,
        "saveSceneModelOverride" => model_provider::save_scene_override(args, session).await,
        "clearSceneModelOverride" => model_provider::clear_scene_override(args, session).await,
        "getSceneVoiceConfig" => model_provider::get_scene_voice_config(args, session).await,
        "saveSceneVoiceConfig" => model_provider::save_scene_voice_config(args, session).await,
        "getSceneModelCatalog" => model_provider::scene_catalog(args, session).await,

        // --- Skills (workspace skill management) ---
        "listAgentSkills" | "agentSkillList" => skill::list(args, session).await,
        "installAgentSkill" | "agentSkillInstall" => skill::install(args, session).await,
        "deleteAgentSkill" | "agentSkillDelete" => skill::delete(args, session).await,
        "setAgentSkillEnabled" => skill::set_enabled(args, session).await,

        // --- Workspace memory ---
        "getWorkspaceSoul" | "loadWorkspaceMemory" => memory::load_soul(args, session).await,
        "saveWorkspaceSoul" | "saveWorkspaceMemory" => memory::save_soul(args, session).await,
        "getWorkspaceChatPrompt" => memory::load_named("chat_prompt.md", args, session).await,
        "saveWorkspaceChatPrompt" => memory::save_named("chat_prompt.md", args, session).await,
        "getWorkspaceLongMemory" => memory::load_named("long_memory.md", args, session).await,
        "saveWorkspaceLongMemory" => memory::save_named("long_memory.md", args, session).await,
        "getWorkspaceShortMemories" => memory::short_memories(args, session).await,
        "getWorkspaceMemoryEmbeddingConfig" => memory::embedding_config(args, session).await,
        "saveWorkspaceMemoryEmbeddingConfig" => memory::save_embedding_config(args, session).await,
        "getWorkspaceMemoryRollupStatus" => memory::rollup_status(args, session).await,
        "saveWorkspaceMemoryRollupEnabled" => memory::save_rollup_enabled(args, session).await,
        "runWorkspaceMemoryRollupNow" => memory::run_rollup_now(args, session).await,

        // --- Logs ---
        "listRecentAiRequestLogs" => runtime_log::list_ai_request_logs(args, session).await,
        "listRuntimeLogs" => runtime_log::list_runtime_logs(args, session).await,

        // --- Misc stubs ---
        "getInstalledApplications" => Ok(serde_json::json!(list_desktop_apps())),
        "setCurrentConversationId" | "setVisibleChatConversation" => {
            Ok(serde_json::json!("SUCCESS"))
        }
        "notifySummarySheetReady" => Ok(serde_json::json!("SUCCESS")),
        "isCompanionTaskRunning" => Ok(serde_json::json!(false)),
        "completeConversation" => Ok(serde_json::json!("SUCCESS")),
        "generateConversationSummary" => Ok(serde_json::json!("")),
        "syncWorkspaceScheduledTasks" => sync_workspace_scheduled_tasks(args, session).await,
        "upsertWorkspaceScheduledTask" => upsert_workspace_scheduled_task(args, session).await,
        "deleteWorkspaceScheduledTask" => delete_workspace_scheduled_task(args, session).await,
        "showScheduledTaskReminder" | "hideScheduledTaskReminder" => {
            Ok(serde_json::json!({"ok": true}))
        }
        "createCompanionTask" | "createVLMOperationTask" | "createLearningTask" => Err(
            AppError::method_not_implemented(format!("assist_core.{method} (desktop)")),
        ),
        other => Err(AppError::method_not_implemented(format!(
            "assist_core.{other}"
        ))),
    }
}

pub async fn subscribe(sub_id: &str, args: serde_json::Value, session: Arc<WsSession>) {
    let kind = args
        .get("kind")
        .and_then(|v| v.as_str())
        .unwrap_or("agent_stream")
        .to_string();
    // Single global subscription for stream events; we just register and let the agent task push events
    // through `WsSession::invoke_method("...","onAgentStreamEvent", payload)` directly. Subscriptions on
    // this channel are mainly used by Dart to opt-in to backend-initiated method calls; we don't fan out
    // here. Keep the subscription handle alive so `event_cancel` can clean up.
    session.subscriptions.insert(
        sub_id.to_string(),
        crate::api::ws::SubscriptionHandle {
            cancel: tokio::sync::Notify::new(),
            channel: kind,
        },
    );
}

async fn schedule_store_path(session: &WsSession) -> AppResult<PathBuf> {
    Ok(
        PathBuf::from(session.state.workspaces.default()?.current_cwd)
            .join(".omnibot/scheduled_tasks.json"),
    )
}

async fn read_scheduled_tasks(path: &PathBuf) -> AppResult<Vec<serde_json::Value>> {
    if !path.exists() {
        return Ok(vec![]);
    }
    let text = tokio::fs::read_to_string(path).await?;
    Ok(serde_json::from_str(&text).unwrap_or_default())
}

async fn write_scheduled_tasks(path: &PathBuf, tasks: &[serde_json::Value]) -> AppResult<()> {
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(path, serde_json::to_vec_pretty(tasks)?).await?;
    Ok(())
}

async fn sync_workspace_scheduled_tasks(
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let tasks = args
        .get("tasks")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default();
    let path = schedule_store_path(&session).await?;
    write_scheduled_tasks(&path, &tasks).await?;
    Ok(serde_json::json!({"ok": true, "count": tasks.len(), "firesOnlyWhileAppRunning": true}))
}

async fn upsert_workspace_scheduled_task(
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let mut task = args.get("task").cloned().unwrap_or(args);
    let id = task
        .get("id")
        .or_else(|| task.get("taskId"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| format!("sched_{}", uuid::Uuid::new_v4().simple()));
    if let Some(obj) = task.as_object_mut() {
        obj.insert("id".into(), serde_json::Value::String(id.clone()));
        obj.insert("updatedAtMs".into(), serde_json::json!(now_unix_ms()));
        obj.entry("createdAtMs")
            .or_insert(serde_json::json!(now_unix_ms()));
        obj.entry("runtime").or_insert(serde_json::json!({
            "desktop": true,
            "firesOnlyWhileAppRunning": true,
        }));
    }
    let path = schedule_store_path(&session).await?;
    let mut tasks = read_scheduled_tasks(&path).await?;
    tasks.retain(|existing| existing.get("id").and_then(|v| v.as_str()) != Some(id.as_str()));
    tasks.push(task.clone());
    write_scheduled_tasks(&path, &tasks).await?;
    Ok(serde_json::json!({"ok": true, "task": task, "firesOnlyWhileAppRunning": true}))
}

async fn delete_workspace_scheduled_task(
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let id = args
        .get("id")
        .or_else(|| args.get("taskId"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let path = schedule_store_path(&session).await?;
    let mut tasks = read_scheduled_tasks(&path).await?;
    let before = tasks.len();
    tasks.retain(|existing| existing.get("id").and_then(|v| v.as_str()) != Some(id));
    write_scheduled_tasks(&path, &tasks).await?;
    Ok(serde_json::json!({"deleted": before != tasks.len(), "id": id}))
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
                    apps.push(serde_json::json!({
                        "name": path.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default(),
                        "path": path.display().to_string(),
                        "platform": std::env::consts::OS,
                    }));
                }
            }
        }
    }
    apps
}
