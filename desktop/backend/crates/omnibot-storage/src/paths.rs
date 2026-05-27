use std::path::{Path, PathBuf};

use directories::ProjectDirs;
use omnibot_common::AppResult;

#[derive(Clone, Debug)]
pub struct AppPaths {
    pub data_dir: PathBuf,
    pub config_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub log_dir: PathBuf,
}

impl AppPaths {
    pub fn resolve(override_data_dir: Option<&Path>) -> AppResult<Self> {
        let dirs = ProjectDirs::from("com", "Omnimind", "OmnibotApp")
            .ok_or_else(|| omnibot_common::AppError::backend("cannot resolve project dirs"))?;
        let data_dir = override_data_dir
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| dirs.data_dir().to_path_buf());
        let config_dir = dirs.config_dir().to_path_buf();
        let cache_dir = dirs.cache_dir().to_path_buf();
        // macOS Logs convention: ~/Library/Logs/OmnibotApp; on other platforms fall back inside data_dir.
        let log_dir = if cfg!(target_os = "macos") {
            let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
            PathBuf::from(home).join("Library/Logs/OmnibotApp")
        } else {
            data_dir.join("logs")
        };
        for p in [&data_dir, &config_dir, &cache_dir, &log_dir] {
            std::fs::create_dir_all(p)?;
        }
        Ok(Self { data_dir, config_dir, cache_dir, log_dir })
    }

    pub fn db_path(&self) -> PathBuf {
        let dir = self.data_dir.join("db");
        let _ = std::fs::create_dir_all(&dir);
        dir.join("omnibot.sqlite3")
    }
    pub fn kv_dir(&self) -> PathBuf {
        let p = self.data_dir.join("kv");
        let _ = std::fs::create_dir_all(&p);
        p
    }
    pub fn workspaces_dir(&self) -> PathBuf {
        let p = self.data_dir.join("workspaces");
        let _ = std::fs::create_dir_all(&p);
        p
    }
    pub fn default_workspace_dir(&self) -> PathBuf {
        let p = self.workspaces_dir().join("default");
        let _ = std::fs::create_dir_all(p.join(".omnibot/memory"));
        let _ = std::fs::create_dir_all(p.join(".omnibot/skills"));
        p
    }
    pub fn ai_request_log_dir(&self) -> PathBuf {
        let p = self.log_dir.join("ai_requests");
        let _ = std::fs::create_dir_all(&p);
        p
    }
}
