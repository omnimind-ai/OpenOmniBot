use omnibot_llm::{ChatCompletionTool, ToolFunctionSchema};

/// Static OpenAI-compatible JSON Schemas for the desktop tool catalog.
/// Tool names intentionally match Android Agent names where the desktop backend can provide a
/// safe equivalent. Legacy aliases stay registered in handlers but are not the primary prompt
/// surface unless needed for compatibility.
pub fn static_tool_definitions() -> Vec<ChatCompletionTool> {
    vec![
        tool(
            "file_read",
            "Read a UTF-8 text file from the workspace with optional offset/limit.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "path": {"type": "string"},
                    "offset": {"type": "integer"},
                    "limit": {"type": "integer"}
                },
                "required": ["path"]
            }),
        ),
        tool(
            "file_write",
            "Create or overwrite a workspace file.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "path": {"type": "string"}, "content": {"type": "string"}},
                "required": ["path", "content"]
            }),
        ),
        tool(
            "file_edit",
            "Replace text in a workspace file. Use replace_all only when all occurrences should change.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "path": {"type": "string"},
                    "old_string": {"type": "string"},
                    "new_string": {"type": "string"},
                    "replace_all": {"type": "boolean"}
                },
                "required": ["path", "old_string", "new_string"]
            }),
        ),
        tool(
            "file_list",
            "List entries of a workspace directory.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "path": {"type": "string"}, "limit": {"type": "integer"}}
            }),
        ),
        tool(
            "file_search",
            "Search workspace file contents for text.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "query": {"type": "string"},
                    "path": {"type": "string"},
                    "case_sensitive": {"type": "boolean"},
                    "max_results": {"type": "integer"}
                },
                "required": ["query"]
            }),
        ),
        tool(
            "file_glob",
            "Find workspace files matching a glob pattern.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "pattern": {"type": "string"}, "path": {"type": "string"}, "max_results": {"type": "integer"}},
                "required": ["pattern"]
            }),
        ),
        tool(
            "file_stat",
            "Get workspace file metadata.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "path": {"type": "string"}},
                "required": ["path"]
            }),
        ),
        tool(
            "file_move",
            "Move or rename a workspace file or directory.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "from": {"type": "string"},
                    "to": {"type": "string"},
                    "overwrite": {"type": "boolean"}
                },
                "required": ["from", "to"]
            }),
        ),
        tool(
            "terminal_execute",
            "Run a one-shot non-interactive shell command in the workspace.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "command": {"type": "string"}, "cwd": {"type": "string"}, "timeout_ms": {"type": "integer"}, "timeoutSeconds": {"type": "integer"}},
                "required": ["command"]
            }),
        ),
        tool(
            "terminal_session_start",
            "Start a reusable desktop terminal session when cwd/output state must be preserved.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "cwd": {"type": "string"}}
            }),
        ),
        tool(
            "terminal_session_exec",
            "Run a command in an existing terminal session.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "sessionId": {"type": "string"}, "command": {"type": "string"}, "timeout_ms": {"type": "integer"}, "timeoutSeconds": {"type": "integer"}},
                "required": ["sessionId", "command"]
            }),
        ),
        tool(
            "terminal_session_read",
            "Read the latest output from a terminal session.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "sessionId": {"type": "string"}, "max_chars": {"type": "integer"}},
                "required": ["sessionId"]
            }),
        ),
        tool(
            "terminal_session_stop",
            "Stop and forget a terminal session.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "sessionId": {"type": "string"}},
                "required": ["sessionId"]
            }),
        ),
        tool(
            "browser_use",
            "Use the internal desktop browser for navigation, extraction, interaction, screenshots, and tab control.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "action": {"type": "string", "enum": ["navigate", "get_text", "get_readable", "screenshot", "read_image", "find_elements", "click", "type", "hover", "scroll", "get_page_info", "get_backbone", "execute_js", "fetch", "new_tab", "select_tab", "close_tab", "go_back", "go_forward", "press_key", "wait_for_selector", "get_cookies", "set_user_agent"]},
                    "url": {"type": "string"},
                    "selector": {"type": "string"},
                    "text": {"type": "string"},
                    "script": {"type": "string"},
                    "key": {"type": "string"},
                    "tabId": {"type": "integer"},
                    "x": {"type": "number"},
                    "y": {"type": "number"},
                    "deltaY": {"type": "number"},
                    "timeoutMs": {"type": "integer"}
                },
                "required": ["action"]
            }),
        ),
        tool(
            "memory_search",
            "Search workspace daily, long-term, and rollup memory notes.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "query": {"type": "string"}, "limit": {"type": "integer"}}
            }),
        ),
        tool(
            "memory_write_daily",
            "Append a short-term daily memory entry.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "content": {"type": "string"}, "date": {"type": "string"}},
                "required": ["content"]
            }),
        ),
        tool(
            "memory_upsert_longterm",
            "Create or update a long-term workspace memory note.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "slug": {"type": "string"}, "title": {"type": "string"}, "content": {"type": "string"}},
                "required": ["content"]
            }),
        ),
        tool(
            "memory_rollup_day",
            "Create a deterministic daily memory rollup note.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "date": {"type": "string"}}
            }),
        ),
        tool(
            "memory_load",
            "Load a full memory note by slug.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "slug": {"type": "string"}},
                "required": ["slug"]
            }),
        ),
        tool(
            "skills_list",
            "List installed workspace and bundled skills.",
            serde_json::json!({"type": "object", "properties": {"tool_title": {"type": "string"}}}),
        ),
        tool(
            "skills_read",
            "Read a skill's SKILL.md/body markdown.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "name": {"type": "string"}},
                "required": ["name"]
            }),
        ),
        tool(
            "schedule_task_create",
            "Persist a scheduled desktop automation task. It fires only while the app backend is running in this phase.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "title": {"type": "string"},
                    "prompt": {"type": "string"},
                    "rrule": {"type": "string"},
                    "enabled": {"type": "boolean"},
                    "targetKind": {"type": "string"}
                },
                "required": ["prompt"]
            }),
        ),
        tool(
            "schedule_task_list",
            "List persisted desktop scheduled automation tasks.",
            serde_json::json!({"type": "object", "properties": {"tool_title": {"type": "string"}}}),
        ),
        tool(
            "schedule_task_update",
            "Patch a persisted scheduled task by id.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "id": {"type": "string"}, "patch": {"type": "object"}},
                "required": ["id"]
            }),
        ),
        tool(
            "schedule_task_delete",
            "Delete a persisted scheduled task by id.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "id": {"type": "string"}},
                "required": ["id"]
            }),
        ),
        tool(
            "image_generate",
            "Generate an image through a configured OpenAI-compatible image endpoint.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "prompt": {"type": "string"}, "size": {"type": "string"}, "model": {"type": "string"}, "n": {"type": "integer"}},
                "required": ["prompt"]
            }),
        ),
        tool(
            "context_apps_query",
            "List installed desktop applications where supported.",
            serde_json::json!({"type": "object", "properties": {"tool_title": {"type": "string"}}}),
        ),
        tool(
            "subagent_dispatch",
            "Dispatch one or more constrained subagent research/decomposition tasks.",
            serde_json::json!({
                "type": "object",
                "properties": {"tool_title": {"type": "string"}, "profileId": {"type": "string"}, "instruction": {"type": "string"}, "tasks": {"type": "array", "items": {"type": "object"}}}
            }),
        ),
        tool(
            "mcp_call",
            "Invoke a tool on a configured remote MCP server.",
            serde_json::json!({
                "type": "object",
                "properties": {
                    "tool_title": {"type": "string"},
                    "server_id": {"type": "string"},
                    "tool": {"type": "string"},
                    "arguments": {"type": "object"}
                },
                "required": ["server_id", "tool"]
            }),
        ),
        tool(
            "context_stop_conversation",
            "Signal the user to stop the current conversation.",
            serde_json::json!({"type": "object", "properties": {"tool_title": {"type": "string"}}}),
        ),
    ]
}

pub async fn dynamic_mcp_tool_definitions(
    client: &omnibot_mcp::RemoteMcpClient,
    config_store: &omnibot_mcp::RemoteMcpConfigStore,
) -> Vec<ChatCompletionTool> {
    let mut out = vec![];
    let servers = match config_store.list() {
        Ok(list) => list,
        Err(e) => {
            tracing::warn!(error=%e, "failed to load MCP configs for tool catalog");
            return out;
        }
    };
    for cfg in servers.into_iter().filter(|cfg| cfg.enabled) {
        let _ = client.initialize(&cfg).await;
        let Ok(tools) = client.list_tools(&cfg).await else {
            continue;
        };
        for descriptor in tools {
            if !is_tool_name_safe(&descriptor.server_id)
                || !is_tool_name_safe(&descriptor.tool_name)
            {
                continue;
            }
            let name = format!("mcp__{}__{}", descriptor.server_id, descriptor.tool_name);
            if name.len() > 64 {
                continue;
            }
            out.push(tool(
                &name,
                &format!(
                    "Remote MCP tool `{}` from server `{}`. {}",
                    descriptor.tool_name,
                    descriptor.server_name,
                    descriptor.description.unwrap_or_default()
                ),
                descriptor.input_schema,
            ));
        }
    }
    out
}

fn tool(name: &str, desc: &str, params: serde_json::Value) -> ChatCompletionTool {
    ChatCompletionTool {
        tool_type: "function".into(),
        function: ToolFunctionSchema {
            name: name.into(),
            description: Some(desc.into()),
            parameters: params,
        },
    }
}

fn is_tool_name_safe(name: &str) -> bool {
    !name.is_empty()
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn static_catalog_contains_desktop_core_parity_tools() {
        let names: std::collections::HashSet<String> = static_tool_definitions()
            .into_iter()
            .map(|tool| tool.function.name)
            .collect();
        for expected in [
            "file_search",
            "file_move",
            "terminal_session_start",
            "terminal_session_exec",
            "terminal_session_read",
            "terminal_session_stop",
            "browser_use",
            "memory_search",
            "memory_write_daily",
            "memory_upsert_longterm",
            "memory_rollup_day",
            "memory_load",
            "skills_list",
            "skills_read",
            "schedule_task_create",
            "schedule_task_list",
            "schedule_task_update",
            "schedule_task_delete",
            "subagent_dispatch",
            "mcp_call",
        ] {
            assert!(names.contains(expected), "missing tool {expected}");
        }
        assert!(
            !names.contains("vlm_task"),
            "Android VLM should not be exposed on desktop"
        );
    }

    #[test]
    fn dynamic_mcp_name_safety_matches_openai_tool_name_subset() {
        assert!(is_tool_name_safe("server_1"));
        assert!(is_tool_name_safe("tool-name"));
        assert!(!is_tool_name_safe("bad.name"));
        assert!(!is_tool_name_safe("bad/tool"));
    }
}
