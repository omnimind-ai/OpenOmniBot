use omnibot_common::AppResult;
use omnibot_storage::KvStore;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RemoteMcpServerConfig {
    pub id: String,
    pub name: String,
    pub endpoint_url: String,
    #[serde(default)]
    pub bearer_token: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub protocol_hint: Option<String>, // "json-rpc" | "sse"
    #[serde(default)]
    pub headers: std::collections::HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct RemoteMcpConfigBundle {
    #[serde(default)]
    pub servers: Vec<RemoteMcpServerConfig>,
}

const KEY: &str = "remote_mcp_configs";

#[derive(Clone)]
pub struct RemoteMcpConfigStore {
    kv: KvStore,
}

impl RemoteMcpConfigStore {
    pub fn new(kv: KvStore) -> Self { Self { kv } }

    pub fn list(&self) -> AppResult<Vec<RemoteMcpServerConfig>> {
        Ok(self.kv.get::<RemoteMcpConfigBundle>(KEY)?.unwrap_or_default().servers)
    }
    pub fn upsert(&self, server: RemoteMcpServerConfig) -> AppResult<Vec<RemoteMcpServerConfig>> {
        let mut bundle: RemoteMcpConfigBundle = self.kv.get(KEY)?.unwrap_or_default();
        if let Some(existing) = bundle.servers.iter_mut().find(|s| s.id == server.id) {
            *existing = server;
        } else {
            bundle.servers.push(server);
        }
        self.kv.put(KEY, &bundle)?;
        Ok(bundle.servers)
    }
    pub fn delete(&self, id: &str) -> AppResult<Vec<RemoteMcpServerConfig>> {
        let mut bundle: RemoteMcpConfigBundle = self.kv.get(KEY)?.unwrap_or_default();
        bundle.servers.retain(|s| s.id != id);
        self.kv.put(KEY, &bundle)?;
        Ok(bundle.servers)
    }
}
