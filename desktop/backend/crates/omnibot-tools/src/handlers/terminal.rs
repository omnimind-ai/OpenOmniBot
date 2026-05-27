use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use omnibot_common::{AppError, AppResult, now_unix_ms};
use parking_lot::Mutex;
use tokio::io::{AsyncBufReadExt, BufReader};
use uuid::Uuid;

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEvent, ToolEventSink, ToolExecutionResult,
    ToolHandler,
};

const DEFAULT_TIMEOUT_MS: u64 = 30_000;
const MAX_OUTPUT_CHARS: usize = 120_000;
const PWD_MARKER: &str = "__OMNIBOT_TERMINAL_PWD__";

#[derive(Debug, Clone)]
struct TerminalSession {
    id: String,
    cwd: String,
    created_at_ms: i64,
    updated_at_ms: i64,
    last_command: Option<String>,
    last_output: String,
    last_exit_code: Option<i32>,
}

#[derive(Clone, Default)]
pub struct TerminalSessionManager {
    sessions: Arc<Mutex<HashMap<String, TerminalSession>>>,
}

impl TerminalSessionManager {
    pub fn start(&self, cwd: String) -> serde_json::Value {
        let id = format!("term_{}", Uuid::new_v4().simple());
        let now = now_unix_ms();
        let session = TerminalSession {
            id: id.clone(),
            cwd: cwd.clone(),
            created_at_ms: now,
            updated_at_ms: now,
            last_command: None,
            last_output: String::new(),
            last_exit_code: None,
        };
        self.sessions.lock().insert(id.clone(), session);
        serde_json::json!({
            "sessionId": id,
            "cwd": cwd,
            "createdAtMs": now,
        })
    }

    fn get(&self, id: &str) -> Option<TerminalSession> {
        self.sessions.lock().get(id).cloned()
    }

    fn update(&self, session: TerminalSession) {
        self.sessions.lock().insert(session.id.clone(), session);
    }

    fn stop(&self, id: &str) -> bool {
        self.sessions.lock().remove(id).is_some()
    }
}

/// One-shot terminal execution plus lightweight persistent terminal sessions.
///
/// Desktop sessions intentionally run non-interactive commands in a remembered cwd instead of
/// exposing a raw TTY to the model. This preserves workspace state and output history while keeping
/// the API deterministic for the Flutter transcript.
pub struct TerminalToolHandler {
    sessions: TerminalSessionManager,
}

impl TerminalToolHandler {
    pub fn new() -> Self {
        Self {
            sessions: TerminalSessionManager::default(),
        }
    }
}

#[async_trait]
impl ToolHandler for TerminalToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &[
            "terminal_execute",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop",
        ]
    }

    fn concurrency_hint(&self) -> ToolConcurrencyHint {
        ToolConcurrencyHint::SerialBarrier
    }

    async fn execute(
        &self,
        tool_call_id: &str,
        tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        match tool_name {
            "terminal_execute" => execute_one_shot(tool_call_id, tool_name, &args, env, sink).await,
            "terminal_session_start" => self.session_start(&args, env).await,
            "terminal_session_exec" => self.session_exec(tool_call_id, &args, env, sink).await,
            "terminal_session_read" => self.session_read(&args).await,
            "terminal_session_stop" => self.session_stop(&args).await,
            _ => Err(AppError::Tool {
                tool: tool_name.into(),
                message: "unsupported".into(),
            }),
        }
    }
}

impl TerminalToolHandler {
    async fn session_start(
        &self,
        args: &serde_json::Value,
        env: &AgentExecutionEnvironment,
    ) -> AppResult<ToolExecutionResult> {
        let cwd_arg = args
            .get("cwd")
            .and_then(|c| c.as_str())
            .unwrap_or(env.workspace.current_cwd.as_str());
        let cwd = resolve_cwd(&env.workspace.current_cwd, cwd_arg)?
            .display()
            .to_string();
        let data = self.sessions.start(cwd.clone());
        Ok(ToolExecutionResult::ok(
            format!(
                "started terminal session {}",
                data.get("sessionId").and_then(|v| v.as_str()).unwrap_or("")
            ),
            data,
        ))
    }

    async fn session_exec(
        &self,
        tool_call_id: &str,
        args: &serde_json::Value,
        env: &AgentExecutionEnvironment,
        sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let session_id = args
            .get("sessionId")
            .or_else(|| args.get("session_id"))
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::invalid("terminal_session_exec.sessionId"))?;
        let command = args
            .get("command")
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::invalid("terminal_session_exec.command"))?;
        let timeout_ms = timeout_ms(args);
        let mut session = self
            .sessions
            .get(session_id)
            .ok_or_else(|| AppError::NotFound(format!("terminal session {session_id}")))?;
        let result = execute_shell(
            tool_call_id,
            "terminal_session_exec",
            command,
            &session.cwd,
            timeout_ms,
            env,
            sink,
            true,
        )
        .await?;
        session.updated_at_ms = now_unix_ms();
        session.last_command = Some(command.to_string());
        session.last_output = result.stdout.clone();
        session.last_exit_code = result
            .structured
            .get("exit_code")
            .and_then(|v| v.as_i64())
            .map(|v| v as i32);
        if let Some(cwd) = result.structured.get("cwd").and_then(|v| v.as_str()) {
            session.cwd = cwd.to_string();
        }
        self.sessions.update(session.clone());
        Ok(ToolExecutionResult {
            structured: merge_structured(
                result.structured,
                serde_json::json!({
                    "sessionId": session_id,
                    "createdAtMs": session.created_at_ms,
                    "updatedAtMs": session.updated_at_ms,
                }),
            ),
            ..result
        })
    }

    async fn session_read(&self, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
        let session_id = args
            .get("sessionId")
            .or_else(|| args.get("session_id"))
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::invalid("terminal_session_read.sessionId"))?;
        let session = self
            .sessions
            .get(session_id)
            .ok_or_else(|| AppError::NotFound(format!("terminal session {session_id}")))?;
        let max_chars = args
            .get("max_chars")
            .or_else(|| args.get("maxChars"))
            .and_then(|v| v.as_u64())
            .unwrap_or(20_000)
            .min(MAX_OUTPUT_CHARS as u64) as usize;
        let text = tail_chars(&session.last_output, max_chars);
        Ok(ToolExecutionResult {
            success: true,
            summary: format!("read terminal session {session_id}"),
            stdout: text.clone(),
            structured: serde_json::json!({
                "sessionId": session_id,
                "cwd": session.cwd,
                "lastCommand": session.last_command,
                "lastExitCode": session.last_exit_code,
                "output": text,
                "createdAtMs": session.created_at_ms,
                "updatedAtMs": session.updated_at_ms,
            }),
            error: None,
            artifacts: vec![],
        })
    }

    async fn session_stop(&self, args: &serde_json::Value) -> AppResult<ToolExecutionResult> {
        let session_id = args
            .get("sessionId")
            .or_else(|| args.get("session_id"))
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::invalid("terminal_session_stop.sessionId"))?;
        let stopped = self.sessions.stop(session_id);
        Ok(ToolExecutionResult::ok(
            if stopped {
                format!("stopped terminal session {session_id}")
            } else {
                format!("terminal session {session_id} was not running")
            },
            serde_json::json!({"sessionId": session_id, "stopped": stopped}),
        ))
    }
}

async fn execute_one_shot(
    tool_call_id: &str,
    tool_name: &str,
    args: &serde_json::Value,
    env: &AgentExecutionEnvironment,
    sink: Arc<dyn ToolEventSink>,
) -> AppResult<ToolExecutionResult> {
    let command = args
        .get("command")
        .and_then(|c| c.as_str())
        .ok_or_else(|| AppError::invalid("terminal_execute.command"))?;
    let cwd_arg = args
        .get("cwd")
        .and_then(|c| c.as_str())
        .unwrap_or(env.workspace.current_cwd.as_str());
    let cwd = resolve_cwd(&env.workspace.current_cwd, cwd_arg)?;
    execute_shell(
        tool_call_id,
        tool_name,
        command,
        &cwd.display().to_string(),
        timeout_ms(args),
        env,
        sink,
        false,
    )
    .await
}

async fn execute_shell(
    tool_call_id: &str,
    tool_name: &str,
    command: &str,
    cwd: &str,
    timeout_ms: u64,
    env: &AgentExecutionEnvironment,
    sink: Arc<dyn ToolEventSink>,
    capture_pwd: bool,
) -> AppResult<ToolExecutionResult> {
    let wrapped = if capture_pwd {
        if cfg!(target_os = "windows") {
            format!("{command}\r\ncd")
        } else {
            format!("{command}\nprintf '\\n{PWD_MARKER}%s\\n' \"$PWD\"")
        }
    } else {
        command.to_string()
    };
    let (shell, args_v): (&str, Vec<&str>) = if cfg!(target_os = "windows") {
        ("cmd.exe", vec!["/C", wrapped.as_str()])
    } else {
        ("/bin/sh", vec!["-lc", wrapped.as_str()])
    };

    let mut cmd = tokio::process::Command::new(shell);
    cmd.args(args_v)
        .current_dir(cwd)
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped());
    for (k, v) in &env.terminal_env {
        cmd.env(k, v);
    }

    let mut child = cmd.spawn().map_err(|e| AppError::Tool {
        tool: tool_name.into(),
        message: format!("spawn: {e}"),
    })?;

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let mut stdout_reader = BufReader::new(stdout).lines();
    let mut stderr_reader = BufReader::new(stderr).lines();

    let task_id = env.agent_run_id.clone();
    let sink2 = sink.clone();
    let call_id = tool_call_id.to_string();
    let tool_name_owned = tool_name.to_string();
    let stream_handle = tokio::spawn(async move {
        let mut combined_inner = String::new();
        loop {
            tokio::select! {
                line = stdout_reader.next_line() => {
                    match line { Ok(Some(l)) => {
                        combined_inner.push_str(&l); combined_inner.push('\n');
                        sink2.emit(ToolEvent {
                            task_id: task_id.clone(),
                            tool_call_id: call_id.clone(),
                            tool_name: tool_name_owned.clone(),
                            kind: "progress".into(),
                            status: "running".into(),
                            args_json: String::new(),
                            progress: l.clone(),
                            partial_text: l,
                            result_preview_json: String::new(),
                        }).await;
                    }, _ => break,
                    }
                }
                line = stderr_reader.next_line() => {
                    match line { Ok(Some(l)) => {
                        let rendered = format!("[stderr] {l}");
                        combined_inner.push_str(&rendered); combined_inner.push('\n');
                        sink2.emit(ToolEvent {
                            task_id: task_id.clone(),
                            tool_call_id: call_id.clone(),
                            tool_name: tool_name_owned.clone(),
                            kind: "progress".into(),
                            status: "running".into(),
                            args_json: String::new(),
                            progress: rendered.clone(),
                            partial_text: rendered,
                            result_preview_json: String::new(),
                        }).await;
                    }, _ => break,
                    }
                }
            }
        }
        combined_inner
    });

    let exit = tokio::time::timeout(Duration::from_millis(timeout_ms), child.wait()).await;
    match exit {
        Ok(Ok(status)) => {
            let mut combined = stream_handle.await.unwrap_or_default();
            let cwd_after = if capture_pwd {
                extract_pwd(&mut combined).unwrap_or_else(|| cwd.to_string())
            } else {
                cwd.to_string()
            };
            combined = tail_chars(&combined, MAX_OUTPUT_CHARS);
            Ok(ToolExecutionResult {
                success: status.success(),
                summary: format!("exit {}", status.code().unwrap_or(-1)),
                stdout: combined.clone(),
                structured: serde_json::json!({
                    "exit_code": status.code(),
                    "exitCode": status.code(),
                    "stdout": combined,
                    "cwd": cwd_after,
                    "timedOut": false,
                }),
                error: if status.success() {
                    None
                } else {
                    Some(format!("exit {}", status.code().unwrap_or(-1)))
                },
                artifacts: vec![],
            })
        }
        Ok(Err(e)) => Ok(ToolExecutionResult::err(format!("wait: {e}"))),
        Err(_) => {
            let _ = child.start_kill();
            Ok(ToolExecutionResult {
                success: false,
                summary: format!("timed out after {timeout_ms}ms"),
                stdout: stream_handle.await.unwrap_or_default(),
                structured: serde_json::json!({"timedOut": true, "timeoutMs": timeout_ms}),
                error: Some(format!("timed out after {timeout_ms}ms")),
                artifacts: vec![],
            })
        }
    }
}

fn timeout_ms(args: &serde_json::Value) -> u64 {
    args.get("timeout_ms")
        .or_else(|| args.get("timeoutMs"))
        .and_then(|c| c.as_u64())
        .or_else(|| {
            args.get("timeoutSeconds")
                .and_then(|c| c.as_u64())
                .map(|s| s * 1_000)
        })
        .unwrap_or(DEFAULT_TIMEOUT_MS)
        .min(10 * 60 * 1_000)
}

fn resolve_cwd(root: &str, cwd: &str) -> AppResult<PathBuf> {
    let root_path = PathBuf::from(root)
        .canonicalize()
        .unwrap_or_else(|_| normalize(PathBuf::from(root)));
    let candidate = Path::new(cwd);
    let full = normalize(if candidate.is_absolute() {
        candidate.to_path_buf()
    } else {
        root_path.join(candidate)
    });
    if !full.starts_with(&root_path) {
        return Err(AppError::Tool {
            tool: "terminal".into(),
            message: format!("cwd escapes workspace: {cwd}"),
        });
    }
    Ok(full)
}

fn normalize(path: PathBuf) -> PathBuf {
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

fn tail_chars(s: &str, max_chars: usize) -> String {
    let total = s.chars().count();
    if total <= max_chars {
        return s.to_string();
    }
    s.chars().skip(total - max_chars).collect()
}

fn extract_pwd(output: &mut String) -> Option<String> {
    let mut cwd = None;
    let mut kept = Vec::new();
    for line in output.lines() {
        if let Some(rest) = line.strip_prefix(PWD_MARKER) {
            cwd = Some(rest.to_string());
        } else {
            kept.push(line.to_string());
        }
    }
    *output = kept.join("\n");
    if !output.is_empty() {
        output.push('\n');
    }
    cwd
}

fn merge_structured(mut base: serde_json::Value, extra: serde_json::Value) -> serde_json::Value {
    if let (Some(base), Some(extra)) = (base.as_object_mut(), extra.as_object()) {
        for (k, v) in extra {
            base.insert(k.clone(), v.clone());
        }
    }
    base
}
