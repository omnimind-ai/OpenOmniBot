use std::path::PathBuf;

use omnibot_common::{AppResult, WorkspaceDescriptor};

#[derive(Clone, Debug)]
pub struct WorkspaceStore {
    root: PathBuf,
}

impl WorkspaceStore {
    pub fn new(root: PathBuf) -> Self {
        let _ = std::fs::create_dir_all(&root);
        Self { root }
    }

    pub fn default(&self) -> AppResult<WorkspaceDescriptor> {
        let path = self.root.join("default");
        std::fs::create_dir_all(path.join(".omnibot/memory"))?;
        std::fs::create_dir_all(path.join(".omnibot/skills"))?;
        Ok(WorkspaceDescriptor::default_for(path.to_string_lossy().into()))
    }

    pub fn root(&self) -> &PathBuf { &self.root }
}
