use omnibot_common::AppResult;
use omnibot_storage::KvStore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModelProviderProfile {
    pub id: String,
    pub display_name: String,
    pub provider_kind: String, // "openai" | "anthropic" | "deepseek" | "qwen" | "custom"
    pub base_url: String,      // chat completions endpoint
    pub api_key: String,
    pub model_id: String,
    #[serde(default)]
    pub extra_headers: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ModelProviderConfig {
    #[serde(default)]
    pub profiles: Vec<ModelProviderProfile>,
    /// Profile id currently being edited in UI.
    #[serde(default)]
    pub editing_id: Option<String>,
}

const KEY: &str = "model_provider_config";

#[derive(Clone)]
pub struct ModelProviderConfigStore {
    kv: KvStore,
}

impl ModelProviderConfigStore {
    pub fn new(kv: KvStore) -> Self { Self { kv } }

    pub fn load(&self) -> AppResult<ModelProviderConfig> {
        Ok(self.kv.get::<ModelProviderConfig>(KEY)?.unwrap_or_default())
    }
    pub fn save(&self, cfg: &ModelProviderConfig) -> AppResult<()> {
        self.kv.put(KEY, cfg)
    }
    pub fn upsert(&self, profile: ModelProviderProfile) -> AppResult<ModelProviderConfig> {
        let mut cfg = self.load()?;
        match cfg.profiles.iter_mut().find(|p| p.id == profile.id) {
            Some(existing) => *existing = profile,
            None => cfg.profiles.push(profile),
        }
        self.save(&cfg)?;
        Ok(cfg)
    }
    pub fn delete(&self, id: &str) -> AppResult<ModelProviderConfig> {
        let mut cfg = self.load()?;
        cfg.profiles.retain(|p| p.id != id);
        if cfg.editing_id.as_deref() == Some(id) { cfg.editing_id = None; }
        self.save(&cfg)?;
        Ok(cfg)
    }
    pub fn set_editing(&self, id: Option<String>) -> AppResult<ModelProviderConfig> {
        let mut cfg = self.load()?;
        cfg.editing_id = id;
        self.save(&cfg)?;
        Ok(cfg)
    }
    pub fn find(&self, id: &str) -> AppResult<Option<ModelProviderProfile>> {
        Ok(self.load()?.profiles.into_iter().find(|p| p.id == id))
    }
}
