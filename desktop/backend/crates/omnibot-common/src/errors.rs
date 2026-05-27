use serde::{Deserialize, Serialize};
use thiserror::Error;

pub type AppResult<T> = std::result::Result<T, AppError>;

#[derive(Debug, Error)]
pub enum AppError {
    #[error("not implemented: {0}")]
    NotImplemented(String),
    #[error("invalid argument: {0}")]
    InvalidArgument(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("serde: {0}")]
    Json(#[from] serde_json::Error),
    #[error("backend: {0}")]
    Backend(String),
    #[error("llm: {0}")]
    Llm(String),
    #[error("mcp: {0}")]
    Mcp(String),
    #[error("tool '{tool}' failed: {message}")]
    Tool { tool: String, message: String },
}

impl AppError {
    pub fn backend(msg: impl Into<String>) -> Self {
        Self::Backend(msg.into())
    }
    pub fn llm(msg: impl Into<String>) -> Self {
        Self::Llm(msg.into())
    }
    pub fn invalid(msg: impl Into<String>) -> Self {
        Self::InvalidArgument(msg.into())
    }
    pub fn method_not_implemented(method: impl Into<String>) -> Self {
        Self::NotImplemented(method.into())
    }

    pub fn code(&self) -> &'static str {
        match self {
            AppError::NotImplemented(_) => "NOT_IMPLEMENTED",
            AppError::InvalidArgument(_) => "INVALID_ARGUMENT",
            AppError::NotFound(_) => "NOT_FOUND",
            AppError::Io(_) => "IO_ERROR",
            AppError::Json(_) => "JSON_ERROR",
            AppError::Backend(_) => "BACKEND_ERROR",
            AppError::Llm(_) => "LLM_ERROR",
            AppError::Mcp(_) => "MCP_ERROR",
            AppError::Tool { .. } => "TOOL_ERROR",
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ErrorPayload {
    pub code: String,
    pub message: String,
}

impl From<&AppError> for ErrorPayload {
    fn from(e: &AppError) -> Self {
        Self { code: e.code().to_string(), message: e.to_string() }
    }
}
