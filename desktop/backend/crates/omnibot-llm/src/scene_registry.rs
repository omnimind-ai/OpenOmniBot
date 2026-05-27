use omnibot_common::AppResult;
use omnibot_storage::KvStore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ScenarioBinding {
    pub scene: String,
    pub profile_id: String,
    pub model_id: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SceneRegistryData {
    #[serde(default)]
    pub bindings: Vec<ScenarioBinding>,
}

const KEY: &str = "scene_model_bindings";

#[derive(Clone)]
pub struct SceneModelRegistry {
    kv: KvStore,
}

impl SceneModelRegistry {
    pub fn new(kv: KvStore) -> Self { Self { kv } }

    pub fn list(&self) -> AppResult<Vec<ScenarioBinding>> {
        Ok(self.kv.get::<SceneRegistryData>(KEY)?.unwrap_or_default().bindings)
    }
    pub fn save(&self, binding: ScenarioBinding) -> AppResult<Vec<ScenarioBinding>> {
        let mut data: SceneRegistryData = self.kv.get(KEY)?.unwrap_or_default();
        if let Some(existing) = data.bindings.iter_mut().find(|b| b.scene == binding.scene) {
            *existing = binding;
        } else {
            data.bindings.push(binding);
        }
        self.kv.put(KEY, &data)?;
        Ok(data.bindings)
    }
    pub fn resolve(&self, scene: &str) -> AppResult<Option<ScenarioBinding>> {
        Ok(self.list()?.into_iter().find(|b| b.scene == scene))
    }

    pub fn delete(&self, scene: &str) -> AppResult<Vec<ScenarioBinding>> {
        let mut data: SceneRegistryData = self.kv.get(KEY)?.unwrap_or_default();
        data.bindings.retain(|b| b.scene != scene);
        self.kv.put(KEY, &data)?;
        Ok(data.bindings)
    }
}
