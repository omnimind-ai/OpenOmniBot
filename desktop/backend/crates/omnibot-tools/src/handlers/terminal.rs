use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use omnibot_common::{AppError, AppResult};
use tokio::io::{AsyncBufReadExt, BufReader};

use crate::types::{
    AgentExecutionEnvironment, ToolConcurrencyHint, ToolEvent, ToolEventSink, ToolExecutionResult, ToolHandler,
};

/// One-shot terminal execution. Streaming session API will live behind a separate `terminal_session_*` tool
/// once portable-pty session storage is wired in.
pub struct TerminalToolHandler;

impl TerminalToolHandler {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl ToolHandler for TerminalToolHandler {
    fn tool_names(&self) -> &[&'static str] {
        &["terminal_execute"]
    }
    fn concurrency_hint(&self) -> ToolConcurrencyHint { ToolConcurrencyHint::SerialBarrier }

    async fn execute(
        &self,
        tool_call_id: &str,
        _tool_name: &str,
        args: serde_json::Value,
        env: &AgentExecutionEnvironment,
        sink: Arc<dyn ToolEventSink>,
    ) -> AppResult<ToolExecutionResult> {
        let command = args.get("command").and_then(|c| c.as_str())
            .ok_or_else(|| AppError::invalid("terminal_execute.command"))?;
        let cwd_arg = args.get("cwd").and_then(|c| c.as_str()).unwrap_or(env.workspace.current_cwd.as_str());
        let timeout_ms = args.get("timeout_ms").and_then(|c| c.as_u64()).unwrap_or(30_000);

        let (shell, args_v): (&str, Vec<&str>) = if cfg!(target_os = "windows") {
            ("cmd.exe", vec!["/C", command])
        } else {
            ("/bin/sh", vec!["-lc", command])
        };

        let mut cmd = tokio::process::Command::new(shell);
        cmd.args(args_v).current_dir(cwd_arg)
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::piped());
        for (k, v) in &env.terminal_env { cmd.env(k, v); }

        let mut child = cmd.spawn()
            .map_err(|e| AppError::Tool { tool: "terminal_execute".into(), message: format!("spawn: {e}") })?;

        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let mut stdout_reader = BufReader::new(stdout).lines();
        let mut stderr_reader = BufReader::new(stderr).lines();

        let mut combined = String::new();
        let task_id = env.agent_run_id.clone();
        let sink2 = sink.clone();
        let call_id = tool_call_id.to_string();
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
                                tool_name: "terminal_execute".into(),
                                kind: "progress".into(),
                                status: "running".into(),
                                args_json: String::new(),
                                progress: l.clone(),
                                partial_text: l.clone(),
                                result_preview_json: String::new(),
                            }).await;
                        }, _ => break,
                        }
                    }
                    line = stderr_reader.next_line() => {
                        match line { Ok(Some(l)) => {
                            combined_inner.push_str("[stderr] ");
                            combined_inner.push_str(&l); combined_inner.push('\n');
                            sink2.emit(ToolEvent {
                                task_id: task_id.clone(),
                                tool_call_id: call_id.clone(),
                                tool_name: "terminal_execute".into(),
                                kind: "progress".into(),
                                status: "running".into(),
                                args_json: String::new(),
                                progress: format!("[stderr] {l}"),
                                partial_text: l.clone(),
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
                if let Ok(out) = stream_handle.await { combined = out; }
                Ok(ToolExecutionResult {
                    success: status.success(),
                    summary: format!("exit {}", status.code().unwrap_or(-1)),
                    stdout: combined.clone(),
                    structured: serde_json::json!({
                        "exit_code": status.code(),
                        "stdout": combined,
                    }),
                    error: if status.success() { None } else { Some(format!("exit {}", status.code().unwrap_or(-1))) },
                    artifacts: vec![],
                })
            }
            Ok(Err(e)) => Ok(ToolExecutionResult::err(format!("wait: {e}"))),
            Err(_) => {
                let _ = child.start_kill();
                Ok(ToolExecutionResult::err(format!("timed out after {timeout_ms}ms")))
            }
        }
    }
}
