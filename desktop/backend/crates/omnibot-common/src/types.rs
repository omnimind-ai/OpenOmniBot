use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WorkspaceDescriptor {
    pub id: String,
    pub name: String,
    pub root_path: String,
    pub current_cwd: String,
    pub uri_root: String,
    pub is_default: bool,
}

impl WorkspaceDescriptor {
    pub fn default_for(root_path: String) -> Self {
        Self {
            id: "default".into(),
            name: "Default Workspace".into(),
            root_path: root_path.clone(),
            current_cwd: root_path.clone(),
            uri_root: format!("file://{}", root_path),
            is_default: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ConversationMode {
    #[serde(rename = "normal")]
    Normal,
    #[serde(rename = "chat_only")]
    ChatOnly,
    #[serde(rename = "openclaw")]
    Openclaw,
    #[serde(rename = "subagent")]
    Subagent,
    #[serde(rename = "codex")]
    Codex,
}

impl ConversationMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Normal => "normal",
            Self::ChatOnly => "chat_only",
            Self::Openclaw => "openclaw",
            Self::Subagent => "subagent",
            Self::Codex => "codex",
        }
    }
    pub fn parse(s: &str) -> Self {
        match s {
            "chat_only" => Self::ChatOnly,
            "openclaw" => Self::Openclaw,
            "subagent" => Self::Subagent,
            "codex" => Self::Codex,
            _ => Self::Normal,
        }
    }
}

pub fn now_unix_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
