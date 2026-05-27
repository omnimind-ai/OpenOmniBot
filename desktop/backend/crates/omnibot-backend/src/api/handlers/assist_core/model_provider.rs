use std::sync::Arc;

use omnibot_common::AppResult;
use omnibot_llm::{ModelProviderProfile, ScenarioBinding};
use uuid::Uuid;

use crate::api::ws::WsSession;

pub async fn get_config(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let cfg = session.state.model_providers.load()?;
    let selected = cfg
        .editing_id
        .as_deref()
        .and_then(|id| cfg.profiles.iter().find(|p| p.id == id))
        .or_else(|| cfg.profiles.first())
        .cloned()
        .unwrap_or_default();
    Ok(profile_to_dart(selected))
}

pub async fn save_config(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    save_profile(args, session).await
}

pub async fn list_profiles(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let cfg = session.state.model_providers.load()?;
    Ok(serde_json::json!({
        "profiles": cfg.profiles.into_iter().map(profile_to_dart).collect::<Vec<_>>(),
        "editingProfileId": cfg.editing_id.unwrap_or_default(),
    }))
}

pub async fn save_profile(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|v| !v.trim().is_empty())
        .map(|v| v.trim().to_string())
        .unwrap_or_else(|| format!("profile-{}", Uuid::new_v4()));
    let mut existing = session.state.model_providers.find(&id)?.unwrap_or_default();
    existing.id = id.clone();
    existing.display_name = args
        .get("name")
        .or_else(|| args.get("displayName"))
        .and_then(|v| v.as_str())
        .unwrap_or(&existing.display_name)
        .to_string();
    existing.base_url = args
        .get("baseUrl")
        .or_else(|| args.get("base_url"))
        .and_then(|v| v.as_str())
        .unwrap_or(&existing.base_url)
        .to_string();
    existing.api_key = args
        .get("apiKey")
        .or_else(|| args.get("api_key"))
        .and_then(|v| v.as_str())
        .unwrap_or(&existing.api_key)
        .to_string();
    existing.provider_kind = args
        .get("protocolType")
        .or_else(|| args.get("providerKind"))
        .and_then(|v| v.as_str())
        .unwrap_or_else(|| {
            if existing.provider_kind.is_empty() {
                "openai_compatible"
            } else {
                existing.provider_kind.as_str()
            }
        })
        .to_string();
    existing.model_id = args
        .get("modelId")
        .or_else(|| args.get("model_id"))
        .and_then(|v| v.as_str())
        .unwrap_or(&existing.model_id)
        .to_string();
    session.state.model_providers.upsert(existing.clone())?;
    session.state.model_providers.set_editing(Some(id))?;
    Ok(profile_to_dart(existing))
}

pub async fn delete_profile(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args
        .get("profileId")
        .or_else(|| args.get("id"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let cfg = session.state.model_providers.delete(id)?;
    Ok(serde_json::json!({
        "profiles": cfg.profiles.into_iter().map(profile_to_dart).collect::<Vec<_>>(),
        "editingProfileId": cfg.editing_id.unwrap_or_default(),
    }))
}

pub async fn set_editing(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let id = args
        .get("profileId")
        .or_else(|| args.get("id"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    let cfg = session.state.model_providers.set_editing(id)?;
    let selected = cfg
        .editing_id
        .as_deref()
        .and_then(|id| cfg.profiles.iter().find(|p| p.id == id))
        .or_else(|| cfg.profiles.first())
        .cloned()
        .unwrap_or_default();
    Ok(profile_to_dart(selected))
}

/// Fetch available models for the given profile. Tries OpenAI-compatible `/models` endpoint.
pub async fn fetch_models(args: serde_json::Value, _session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let base_url = args.get("baseUrl").and_then(|v| v.as_str()).unwrap_or("");
    let api_key = args.get("apiKey").and_then(|v| v.as_str()).unwrap_or("");
    // Build the /models URL from base completion endpoint heuristically.
    let models_url = if base_url.contains("/chat/completions") {
        base_url.replace("/chat/completions", "/models")
    } else if base_url.ends_with('/') {
        format!("{base_url}models")
    } else {
        format!("{base_url}/models")
    };
    let mut req = reqwest::Client::new().get(&models_url);
    if !api_key.is_empty() { req = req.bearer_auth(api_key); }
    match req.send().await {
        Ok(r) => {
            let status = r.status();
            match r.json::<serde_json::Value>().await {
                Ok(json) => Ok(serde_json::json!({ "ok": status.is_success(), "data": json })),
                Err(e) => Ok(serde_json::json!({ "ok": false, "error": e.to_string() })),
            }
        }
        Err(e) => Ok(serde_json::json!({ "ok": false, "error": e.to_string() })),
    }
}

pub async fn check_availability(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    // Probe the chat completion endpoint with a minimal request.
    let id = args.get("profileId").and_then(|v| v.as_str()).unwrap_or("");
    if let Some(profile) = session.state.model_providers.find(id)? {
        let body = serde_json::json!({
            "model": profile.model_id,
            "messages": [{"role": "user", "content": "ping"}],
            "max_tokens": 1,
        });
        let mut req = reqwest::Client::new().post(&profile.base_url)
            .header("content-type", "application/json")
            .json(&body);
        if !profile.api_key.is_empty() { req = req.bearer_auth(&profile.api_key); }
        match req.send().await {
            Ok(r) => {
                let status = r.status();
                Ok(serde_json::json!({
                    "available": status.is_success(),
                    "code": status.as_u16(),
                    "message": if status.is_success() { String::from("ok") } else { r.text().await.unwrap_or_default() }
                }))
            }
            Err(e) => Ok(serde_json::json!({"available": false, "code": null, "message": e.to_string()})),
        }
    } else {
        Ok(serde_json::json!({"available": false, "code": null, "message": "profile not found"}))
    }
}

pub async fn list_scene_bindings(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    Ok(serde_json::Value::Array(
        session
            .state
            .scene_registry
            .list()?
            .into_iter()
            .map(binding_to_dart)
            .collect(),
    ))
}

pub async fn save_scene_binding(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let binding = binding_from_dart(&args);
    let updated = session.state.scene_registry.save(binding)?;
    Ok(serde_json::Value::Array(updated.into_iter().map(binding_to_dart).collect()))
}

pub async fn clear_scene_binding(args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let scene = args.get("sceneId").and_then(|v| v.as_str()).unwrap_or("");
    let updated = session.state.scene_registry.delete(scene)?;
    Ok(serde_json::Value::Array(updated.into_iter().map(binding_to_dart).collect()))
}

pub async fn list_scene_overrides(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!([]))
}

pub async fn save_scene_override(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!([]))
}

pub async fn clear_scene_override(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!([]))
}

pub async fn get_scene_voice_config(
    _args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(serde_json::json!({
        "autoPlay": false,
        "voiceId": "default_zh",
        "stylePreset": "默认",
        "customStyle": "",
    }))
}

pub async fn save_scene_voice_config(
    args: serde_json::Value,
    _session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    Ok(args)
}

pub async fn scene_catalog(_args: serde_json::Value, session: Arc<WsSession>) -> AppResult<serde_json::Value> {
    let cfg = session.state.model_providers.load()?;
    let default_profile = cfg.profiles.first();
    let default_profile_id = default_profile.map(|p| p.id.clone()).unwrap_or_default();
    let default_profile_name = default_profile
        .map(|p| p.display_name.clone())
        .unwrap_or_default();
    let default_model = default_profile.map(|p| p.model_id.clone()).unwrap_or_default();
    Ok(serde_json::json!([
        scene_item("agent_main", "Agent Main", &default_profile_id, &default_profile_name, &default_model),
        scene_item("agent_compaction", "Context Compaction", &default_profile_id, &default_profile_name, &default_model),
        scene_item("chat", "Chat Only", &default_profile_id, &default_profile_name, &default_model),
        scene_item("subagent", "Subagent", &default_profile_id, &default_profile_name, &default_model),
    ]))
}

fn profile_to_dart(profile: ModelProviderProfile) -> serde_json::Value {
    let configured = !profile.api_key.is_empty() && !profile.base_url.is_empty();
    serde_json::json!({
        "id": profile.id,
        "name": if profile.display_name.is_empty() { "Provider" } else { profile.display_name.as_str() },
        "baseUrl": profile.base_url,
        "apiKey": profile.api_key,
        "sourceType": if profile.provider_kind.is_empty() { "custom" } else { profile.provider_kind.as_str() },
        "protocolType": if profile.provider_kind.is_empty() { "openai_compatible" } else { profile.provider_kind.as_str() },
        "readOnly": false,
        "ready": configured,
        "configured": configured,
        "statusText": if configured { "Configured" } else { "" },
    })
}

fn binding_from_dart(value: &serde_json::Value) -> ScenarioBinding {
    ScenarioBinding {
        scene: value
            .get("sceneId")
            .or_else(|| value.get("scene"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        profile_id: value
            .get("providerProfileId")
            .or_else(|| value.get("profileId"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        model_id: value
            .get("modelId")
            .or_else(|| value.get("model"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
    }
}

fn binding_to_dart(binding: ScenarioBinding) -> serde_json::Value {
    serde_json::json!({
        "sceneId": binding.scene,
        "providerProfileId": binding.profile_id,
        "modelId": binding.model_id,
    })
}

fn scene_item(
    scene_id: &str,
    description: &str,
    default_profile_id: &str,
    default_profile_name: &str,
    default_model: &str,
) -> serde_json::Value {
    serde_json::json!({
        "sceneId": scene_id,
        "description": description,
        "defaultModel": default_model,
        "effectiveModel": default_model,
        "effectiveProviderProfileId": default_profile_id,
        "effectiveProviderProfileName": default_profile_name,
        "boundProviderProfileId": "",
        "boundProviderProfileName": "",
        "transport": "openai_compatible",
        "configSource": "desktop",
        "overrideApplied": false,
        "overrideModel": "",
        "providerConfigured": !default_profile_id.is_empty(),
        "bindingExists": false,
        "bindingProfileMissing": false,
    })
}
