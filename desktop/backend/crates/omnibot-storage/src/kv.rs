use std::path::PathBuf;

use omnibot_common::AppResult;
use parking_lot::RwLock;
use serde::{Serialize, de::DeserializeOwned};

/// JSON file-backed key-value store, one file per `key`. Replaces Android MMKV.
#[derive(Clone, Debug)]
pub struct KvStore {
    dir: PathBuf,
    cache: std::sync::Arc<RwLock<std::collections::HashMap<String, serde_json::Value>>>,
}

impl KvStore {
    pub fn open(dir: PathBuf) -> AppResult<Self> {
        std::fs::create_dir_all(&dir)?;
        Ok(Self { dir, cache: std::sync::Arc::new(RwLock::new(Default::default())) })
    }

    fn file_for(&self, key: &str) -> PathBuf {
        let safe: String = key.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' || c == '.' { c } else { '_' }).collect();
        self.dir.join(format!("{}.json", safe))
    }

    pub fn get_raw(&self, key: &str) -> AppResult<Option<serde_json::Value>> {
        if let Some(v) = self.cache.read().get(key) {
            return Ok(Some(v.clone()));
        }
        let path = self.file_for(key);
        if !path.exists() {
            return Ok(None);
        }
        let bytes = std::fs::read(&path)?;
        let v: serde_json::Value = serde_json::from_slice(&bytes)?;
        self.cache.write().insert(key.into(), v.clone());
        Ok(Some(v))
    }

    pub fn put_raw(&self, key: &str, value: &serde_json::Value) -> AppResult<()> {
        let path = self.file_for(key);
        let tmp = path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(value)?)?;
        std::fs::rename(&tmp, &path)?;
        self.cache.write().insert(key.into(), value.clone());
        Ok(())
    }

    pub fn delete(&self, key: &str) -> AppResult<()> {
        let path = self.file_for(key);
        if path.exists() { std::fs::remove_file(&path)?; }
        self.cache.write().remove(key);
        Ok(())
    }

    pub fn get<T: DeserializeOwned>(&self, key: &str) -> AppResult<Option<T>> {
        match self.get_raw(key)? {
            Some(v) => Ok(Some(serde_json::from_value(v)?)),
            None => Ok(None),
        }
    }
    pub fn put<T: Serialize>(&self, key: &str, value: &T) -> AppResult<()> {
        self.put_raw(key, &serde_json::to_value(value)?)
    }
}
