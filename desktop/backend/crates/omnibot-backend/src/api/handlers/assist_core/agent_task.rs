use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use async_trait::async_trait;
use omnibot_agent::{
    AgentCallback, AgentOrchestrator, AgentRunInput, AgentStreamEvent,
};
use omnibot_common::{AppError, AppResult, now_unix_ms};
use omnibot_db::{AgentEntryRepo, ConversationRepo, TokenUsageRepo};
use omnibot_llm::{
    AgentLlmClient, ChatCompletionMessage, ChatCompletionRequest, LlmEndpoint, ModelProviderProfile,
};
use omnibot_tools::{AgentExecutionEnvironment, ToolEvent, ToolExecutionResult};
use uuid::Uuid;

use crate::api::ws::WsSession;
use crate::state::ActiveTaskHandle;

/// Resolve a model profile from args.modelProfileId or fallback to first profile.
fn resolve_profile(session: &WsSession, args: &serde_json::Value) -> AppResult<ModelProviderProfile> {
    let id = args.get("modelProfileId").and_then(|v| v.as_str()).map(|s| s.to_string());
    let cfg = session.state.model_providers.load()?;
    if let Some(id) = id {
        cfg.profiles.into_iter().find(|p| p.id == id)
            .ok_or_else(|| AppError::NotFound(format!("model profile {id}")))
    } else {
        cfg.profiles.into_iter().next()
            .ok_or_else(|| AppError::InvalidArgument("no model profile configured; set one in settings first".into()))
    }
}

pub async fn create_chat_task(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    spawn_task(args, session, false).await
}

pub async fn create_agent_task(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    spawn_task(args, session, true).await
}

async fn spawn_task(
    args: serde_json::Value,
    session: Arc<WsSession>,
    enable_tools: bool,
) -> AppResult<serde_json::Value> {
    let task_id = Uuid::new_v4().to_string();
    let conversation_id = args.get("conversationId").and_then(|v| v.as_i64());
    let mode = args.get("mode").and_then(|v| v.as_str()).unwrap_or("normal").to_string();
    let query = args.get("query").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let profile = resolve_profile(&session, &args)?;
    let workspace = session.state.workspaces.default()?;

    // Build messages from prior agent entries + new user query.
    let mut messages: Vec<ChatCompletionMessage> = vec![ChatCompletionMessage::system(
        omnibot_agent::build_system_prompt(&workspace, &mode),
    )];
    if let Some(cid) = conversation_id {
        let entries = AgentEntryRepo::list(session.state.db.pool(), cid, &mode).await?;
        for e in entries {
            let payload: serde_json::Value = serde_json::from_str(&e.payload_json).unwrap_or(serde_json::Value::Null);
            if let Some(role) = payload.get("role").and_then(|v| v.as_str()) {
                let content = payload.get("content").cloned().unwrap_or(serde_json::Value::Null);
                messages.push(ChatCompletionMessage {
                    role: role.into(),
                    content: Some(content),
                    name: None,
                    tool_call_id: None,
                    tool_calls: vec![],
                    reasoning_content: None,
                });
            }
        }
    }
    messages.push(ChatCompletionMessage::user(query.clone()));

    // Persist the user message (if we have a conversation).
    if let Some(cid) = conversation_id {
        let _ = AgentEntryRepo::upsert(
            session.state.db.pool(), cid, &mode,
            &format!("user_{}", now_unix_ms()),
            "user", "complete", &truncate(&query, 200),
            &serde_json::json!({"role": "user", "content": query}),
        ).await?;
        let _ = ConversationRepo::touch(session.state.db.pool(), cid, Some(&truncate(&query, 120)), 1).await;
    }

    // Register active task with cancellation handle.
    let cancel = Arc::new(tokio::sync::Notify::new());
    let cancelled = Arc::new(AtomicBool::new(false));
    session.state.active_tasks.insert(task_id.clone(), ActiveTaskHandle {
        cancel: cancel.clone(),
        cancelled: cancelled.clone(),
        conversation_id,
        started_at_ms: now_unix_ms(),
    });

    let env = AgentExecutionEnvironment {
        agent_run_id: task_id.clone(),
        user_message: query.clone(),
        workspace,
        conversation_mode: mode.clone(),
        reasoning_effort: args.get("reasoningEffort").and_then(|v| v.as_str()).map(|s| s.into()),
        terminal_env: std::collections::HashMap::new(),
    };

    let endpoint = LlmEndpoint {
        url: profile.base_url.clone(),
        api_key: if profile.api_key.is_empty() { None } else { Some(profile.api_key.clone()) },
        headers: profile.extra_headers.clone(),
    };

    let session_for_task = session.clone();
    let model = profile.model_id.clone();
    let task_id_response = task_id.clone();
    let cb = Arc::new(WsAgentCallback {
        session: session_for_task.clone(),
        task_id: task_id.clone(),
        _mode: mode.clone(),
        conversation_id,
    });

    tokio::spawn(async move {
        let started_at = std::time::Instant::now();
        let result = if enable_tools {
            let orchestrator = AgentOrchestrator::new(
                session_for_task.state.llm_client.clone(),
                session_for_task.state.tool_router.clone(),
                endpoint.clone(),
                model.clone(),
            )
            .with_mcp_catalog(
                session_for_task.state.mcp_client.clone(),
                session_for_task.state.mcp_config.clone(),
            );
            orchestrator.run(AgentRunInput {
                task_id: task_id.clone(),
                callback: cb.clone(),
                initial_messages: messages.clone(),
                environment: env.clone(),
                max_rounds: 16,
                conversation_id,
                cancel: Some(cancel.clone()),
                cancel_flag: Some(cancelled.clone()),
            }).await
        } else {
            // Chat-only path: single LLM stream_turn, no tool catalog.
            let request = ChatCompletionRequest {
                model: model.clone(),
                messages: messages.clone(),
                temperature: Some(0.7),
                top_p: None,
                max_tokens: None,
                stream: Some(true),
                stream_options: Some(omnibot_llm::StreamOptions { include_usage: true }),
                tools: None,
                tool_choice: None,
                parallel_tool_calls: None,
                extras: Default::default(),
            };
            let seq = std::sync::atomic::AtomicI64::new(0);
            let chat_sink: Arc<dyn omnibot_llm::client::StreamSink> = Arc::new(ChatOnlySink {
                cb: cb.clone(),
                task_id: task_id.clone(),
                mode: mode.clone(),
                seq,
            });
            match session_for_task.state.llm_client.stream_turn(request, &endpoint, chat_sink).await {
                Ok(t) => {
                    let final_text = t.message.text_content();
                    cb.on_chat_message(&final_text, true).await;
                    cb.on_stream_event(AgentStreamEvent::chat_message(
                        &task_id, &mode, 0, final_text.clone(), true,
                    )).await;
                    cb.on_stream_event(AgentStreamEvent::finish(&task_id, &mode, 1)).await;
                    cb.on_complete(&final_text).await;
                    if let (Some(cid), Some(usage)) = (conversation_id, t.usage) {
                        let _ = TokenUsageRepo::record(
                            session_for_task.state.db.pool(), cid, &model,
                            usage.prompt_tokens, usage.completion_tokens,
                            usage.reasoning_tokens.unwrap_or(0), 0,
                            usage.cached_tokens.unwrap_or(0),
                        ).await;
                    }
                    if let Some(cid) = conversation_id {
                        let _ = AgentEntryRepo::upsert(
                            session_for_task.state.db.pool(), cid, &mode,
                            &format!("assistant_{}", now_unix_ms()),
                            "assistant", "complete", &truncate(&final_text, 200),
                            &serde_json::json!({"role": "assistant", "content": final_text}),
                        ).await;
                    }
                    omnibot_agent::AgentResult::Finished { rounds: 1, last_message: final_text }
                }
                Err(e) => {
                    cb.on_error(&e.to_string()).await;
                    cb.on_stream_event(AgentStreamEvent::error(&task_id, &mode, 0, e.to_string())).await;
                    omnibot_agent::AgentResult::Error { message: e.to_string() }
                }
            }
        };
        tracing::info!(?result, elapsed_ms=%started_at.elapsed().as_millis(), "agent task finished");
        session_for_task.state.active_tasks.remove(&task_id);
        // Also push a top-level finish to AssistCore method invoke so Dart can wrap up.
        let payload = serde_json::json!({"taskId": task_id, "ok": matches!(result, omnibot_agent::AgentResult::Finished {..})});
        session_for_task.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onTaskFinish",
            payload,
        ).await;
    });

    Ok(serde_json::json!({"taskId": task_id_response, "ok": true}))
}

pub async fn cancel(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let task_id = args.get("taskId").and_then(|v| v.as_str()).unwrap_or("");
    if let Some((_, handle)) = session.state.active_tasks.remove(task_id) {
        handle.cancelled.store(true, Ordering::SeqCst);
        handle.cancel.notify_waiters();
    }
    Ok(serde_json::json!({"ok": true}))
}

fn truncate(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

// ---- Callbacks ----------------------------------------------------------------------------------

struct WsAgentCallback {
    session: Arc<WsSession>,
    task_id: String,
    _mode: String,
    conversation_id: Option<i64>,
}

impl WsAgentCallback {
    fn enrich(&self, mut ev: AgentStreamEvent) -> AgentStreamEvent {
        ev.conversation_id = self.conversation_id;
        ev
    }
}

#[async_trait]
impl AgentCallback for WsAgentCallback {
    async fn on_thinking_start(&self) {}
    async fn on_thinking_update(&self, text: &str) {
        let _ = text;
    }
    async fn on_chat_message(&self, content: &str, is_final: bool) {
        let _ = (content, is_final);
    }
    async fn on_tool_call_start(&self, tool_name: &str, args: &serde_json::Value) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onAgentToolUpdate",
            serde_json::json!({
                "taskId": self.task_id,
                "toolName": tool_name,
                "kind": "start",
                "args": args,
            }),
        ).await;
    }
    async fn on_tool_call_progress(&self, event: ToolEvent) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onAgentToolUpdate",
            serde_json::to_value(event).unwrap_or(serde_json::Value::Null),
        ).await;
    }
    async fn on_tool_call_complete(&self, tool_name: &str, result: &ToolExecutionResult) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onAgentToolUpdate",
            serde_json::json!({
                "taskId": self.task_id,
                "toolName": tool_name,
                "kind": "complete",
                "result": result.to_payload(),
            }),
        ).await;
    }
    async fn on_prompt_token_usage_changed(&self, latest: i64, threshold: Option<i64>) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onPromptTokenUsageChanged",
            serde_json::json!({
                "taskId": self.task_id,
                "latestPromptTokens": latest,
                "promptTokenThreshold": threshold,
            }),
        ).await;
    }
    async fn on_context_compaction_state(&self, compacting: bool, latest: Option<i64>, threshold: Option<i64>) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onContextCompactionStateChanged",
            serde_json::json!({
                "taskId": self.task_id,
                "isCompacting": compacting,
                "latestPromptTokens": latest,
                "promptTokenThreshold": threshold,
            }),
        ).await;
    }
    async fn on_stream_event(&self, event: AgentStreamEvent) {
        let payload = serde_json::to_value(self.enrich(event)).unwrap_or(serde_json::Value::Null);
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onAgentStreamEvent",
            payload,
        ).await;
    }
    async fn on_error(&self, message: &str) {
        self.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onTaskError",
            serde_json::json!({"taskId": self.task_id, "error": message}),
        ).await;
    }
    async fn on_complete(&self, summary: &str) {
        let _ = summary;
    }
}

struct ChatOnlySink {
    cb: Arc<WsAgentCallback>,
    task_id: String,
    mode: String,
    seq: std::sync::atomic::AtomicI64,
}

#[async_trait]
impl omnibot_llm::client::StreamSink for ChatOnlySink {
    async fn on_content_delta(&self, full: &str, _delta: &str) {
        let n = self.seq.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        self.cb.on_stream_event(AgentStreamEvent::chat_message(
            &self.task_id, &self.mode, n, full.to_string(), false,
        )).await;
        // Also fire a per-delta chat task message for legacy listeners.
        self.cb.session.invoke_method(
            "cn.com.omnimind.bot/AssistCoreEvent",
            "onChatTaskMessage",
            serde_json::json!({"taskId": self.task_id, "content": full, "type": "delta"}),
        ).await;
    }
    async fn on_reasoning_snapshot(&self, full: &str) {
        let n = self.seq.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        self.cb.on_stream_event(AgentStreamEvent::reasoning(
            &self.task_id, &self.mode, n, full.to_string(),
        )).await;
    }
}
