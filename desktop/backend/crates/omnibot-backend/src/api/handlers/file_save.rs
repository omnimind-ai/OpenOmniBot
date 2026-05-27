use std::sync::Arc;

use omnibot_common::AppResult;

use crate::api::ws::WsSession;

/// Desktop file save: Dart side already has `file_picker` for save dialogs, so this channel
/// mostly acts as a passthrough for default-directory + open-with-system actions.
pub async fn route(
    method: &str,
    args: serde_json::Value,
    session: Arc<WsSession>,
) -> AppResult<serde_json::Value> {
    match method {
        "saveFileWithSystemDialog" => {
            // Dart side picks the destination; backend just persists bytes if given.
            let path = args.get("path").and_then(|v| v.as_str());
            let bytes_b64 = args.get("contentBase64").and_then(|v| v.as_str());
            if let (Some(p), Some(b)) = (path, bytes_b64) {
                let bytes = decode_b64(b)?;
                tokio::fs::write(p, bytes).await.map_err(|e| omnibot_common::AppError::backend(e.to_string()))?;
            }
            Ok(serde_json::json!({"ok": true}))
        }
        "openFile" => {
            let path = args.get("path").and_then(|v| v.as_str()).unwrap_or("");
            open_with_system(path);
            Ok(serde_json::json!({"ok": true}))
        }
        "shareFile" => {
            // Desktop has no share-sheet equivalent; reveal in Finder/Explorer.
            let path = args.get("path").and_then(|v| v.as_str()).unwrap_or("");
            reveal_in_file_manager(path);
            Ok(serde_json::json!({"ok": true}))
        }
        "getWorkspaceRoot" => Ok(serde_json::json!({
            "path": session.state.workspaces.default()?.current_cwd,
        })),
        _ => Ok(serde_json::Value::Null),
    }
}

fn decode_b64(s: &str) -> AppResult<Vec<u8>> {
    // Minimal base64 decode without an extra dep; tolerate padding variations.
    let cleaned: String = s.chars().filter(|c| !c.is_whitespace()).collect();
    let mut buf = Vec::with_capacity(cleaned.len() * 3 / 4);
    let table = b64_table();
    let bytes = cleaned.as_bytes();
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    for &b in bytes {
        if b == b'=' { break; }
        let v = table[b as usize] as u32;
        if v == 0xFF { continue; }
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            buf.push((acc >> bits) as u8);
        }
    }
    Ok(buf)
}

fn b64_table() -> [u8; 256] {
    let mut t = [0xFF_u8; 256];
    let alpha = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    for (i, &c) in alpha.iter().enumerate() { t[c as usize] = i as u8; }
    t
}

fn open_with_system(path: &str) {
    #[cfg(target_os = "macos")] { let _ = std::process::Command::new("open").arg(path).spawn(); }
    #[cfg(target_os = "windows")] { let _ = std::process::Command::new("cmd").args(["/C", "start", "", path]).spawn(); }
    #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))] { let _ = std::process::Command::new("xdg-open").arg(path).spawn(); }
}

fn reveal_in_file_manager(path: &str) {
    #[cfg(target_os = "macos")] { let _ = std::process::Command::new("open").args(["-R", path]).spawn(); }
    #[cfg(target_os = "windows")] { let _ = std::process::Command::new("explorer").args(["/select,", path]).spawn(); }
    #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))] { let _ = std::process::Command::new("xdg-open").arg(path).spawn(); }
}
