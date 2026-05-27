use omnibot_llm::{ChatCompletionTool, ToolFunctionSchema};

/// Static OpenAI-compatible JSON Schemas for the desktop tool catalog.
/// MCP tools are appended dynamically at runtime.
pub fn static_tool_definitions() -> Vec<ChatCompletionTool> {
    vec![
        tool("file_read", "Read a UTF-8 text file from the workspace.", serde_json::json!({
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"]
        })),
        tool("file_write", "Create or overwrite a file with given content.", serde_json::json!({
            "type": "object",
            "properties": {"path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"]
        })),
        tool("file_edit", "In-place edit: replace `old_string` with `new_string`. Set replace_all=true to replace all matches.", serde_json::json!({
            "type": "object",
            "properties": {"path": {"type": "string"}, "old_string": {"type": "string"}, "new_string": {"type": "string"}, "replace_all": {"type": "boolean"}},
            "required": ["path", "old_string", "new_string"]
        })),
        tool("file_list", "List entries of a directory.", serde_json::json!({
            "type": "object",
            "properties": {"path": {"type": "string"}}
        })),
        tool("file_glob", "Find files matching a glob pattern.", serde_json::json!({
            "type": "object",
            "properties": {"pattern": {"type": "string"}, "path": {"type": "string"}},
            "required": ["pattern"]
        })),
        tool("file_grep", "Search file contents for a substring.", serde_json::json!({
            "type": "object",
            "properties": {"pattern": {"type": "string"}, "path": {"type": "string"}},
            "required": ["pattern"]
        })),
        tool("file_stat", "Get file metadata.", serde_json::json!({
            "type": "object",
            "properties": {"path": {"type": "string"}},
            "required": ["path"]
        })),
        tool("terminal_execute", "Run a shell command. Returns stdout, exit code.", serde_json::json!({
            "type": "object",
            "properties": {"command": {"type": "string"}, "cwd": {"type": "string"}, "timeout_ms": {"type": "integer"}},
            "required": ["command"]
        })),
        tool("workspace_memory_load", "Load a workspace memory note by name.", serde_json::json!({
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"]
        })),
        tool("workspace_memory_save", "Save a workspace memory note.", serde_json::json!({
            "type": "object",
            "properties": {"name": {"type": "string"}, "content": {"type": "string"}},
            "required": ["name", "content"]
        })),
        tool("skill_list", "List installed workspace skills.", serde_json::json!({"type": "object", "properties": {}})),
        tool("skill_read", "Read a skill's body markdown.", serde_json::json!({
            "type": "object",
            "properties": {"name": {"type": "string"}},
            "required": ["name"]
        })),
        tool("mcp_call", "Invoke a tool on a configured remote MCP server.", serde_json::json!({
            "type": "object",
            "properties": {
                "server_id": {"type": "string"},
                "tool": {"type": "string"},
                "arguments": {"type": "object"}
            },
            "required": ["server_id", "tool"]
        })),
        tool("context_stop_conversation", "Signal the user to stop the current conversation.", serde_json::json!({"type": "object", "properties": {}})),
    ]
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
