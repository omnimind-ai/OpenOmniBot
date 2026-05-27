use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

/// Bridges the Android CacheDataEvent channel which mostly wrapped MMKV / Room queries.
/// On desktop we route the KV-style operations to our JSON KV store and DB-style ones to sqlx.
pub async fn route(
    method: &str,
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    let kv = &session.state.kv;
    match method {
        "doMMKVEncodeString" | "doMMKVEncodeBool" | "doMMKVEncodeInt" | "doMMKVEncodeDouble"
        | "doMMKVEncode" => {
            let key = args.get("key").and_then(|v| v.as_str()).unwrap_or("");
            let value = args
                .get("value")
                .cloned()
                .unwrap_or(serde_json::Value::Null);
            kv.put_raw(key, &value)?;
            Ok(serde_json::json!({"ok": true}))
        }
        "doMMKVDecodeString" | "doMMKVDecodeBoole" | "doMMKVDecodeBool" | "doMMKVDecodeInt"
        | "doMMKVDecodeDouble" | "doMMKVDecode" => {
            let key = args.get("key").and_then(|v| v.as_str()).unwrap_or("");
            Ok(kv.get_raw(key)?.unwrap_or_else(|| {
                args.get("defaultValue")
                    .cloned()
                    .unwrap_or(serde_json::Value::Null)
            }))
        }
        "doMMKVRemove" => {
            let key = args.get("key").and_then(|v| v.as_str()).unwrap_or("");
            kv.delete(key)?;
            Ok(serde_json::json!({"ok": true}))
        }
        "getAppIcon"
        | "getAppIcons"
        | "getAppIconByPackageName"
        | "getAppIconsByPackageNames"
        | "getAllStudyRecords"
        | "getExecutionRecords"
        | "getAllFavoriteRecords"
        | "getFavoriteRecordsByType"
        | "getFavoriteRecordsByTitle"
        | "getFavoriteRecordCountByType"
        | "getAllExecutionRecords"
        | "getExecutionRecordsByAppName"
        | "getExecutionRecordCountByAppName"
        | "getExecutionRecordsByTitle"
        | "getExecutionRecordCountByTitle"
        | "getTaskExecutionInfos"
        | "getExecutionRecordsByNodeAndSuggestionId"
        | "getCacheSuggestions" => {
            // App-icon/study-record APIs are Android-only; return empty payload.
            Ok(serde_json::json!([]))
        }
        "getFavoriteRecordById" | "getMessageById" => Ok(serde_json::Value::Null),
        "insertAppIcon"
        | "insertFavoriteRecord"
        | "deleteFavoriteRecordById"
        | "updateFavoriteRecordTitle"
        | "insertExecutionRecord"
        | "updateExecutionRecordTitle"
        | "deleteExecutionRecordById"
        | "deleteExecutionRecordByNodeAndSuggestionId"
        | "insertMessage"
        | "updateMessage"
        | "deleteMessageById"
        | "deleteAllMessages"
        | "cacheSuggestions" => Ok(serde_json::json!(false)),
        "getMessagesByPage" => Ok(serde_json::json!({
            "messages": [],
            "total": 0,
        })),
        _ => Ok(serde_json::Value::Null),
    }
}
