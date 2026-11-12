use axum::Json;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClaudeAuthStatus {
    #[serde(rename = "loggedIn")]
    pub logged_in: bool,
    #[serde(skip_serializing_if = "Option::is_none", rename = "authMethod")]
    pub auth_method: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub email: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "subscriptionType")]
    pub subscription_type: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClaudeCliStatus {
    pub success: bool,
    pub installed: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bin_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auth: Option<ClaudeAuthStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auth_error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClaudeInfoResponse {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub info: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub default_mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClaudeSessionListResponse {
    pub sessions: Vec<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClaudeProvidersResponse {
    pub success: bool,
    pub providers: Vec<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub settings_endpoint: Option<String>,
    pub settings_conflicts: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BridgeSimpleResponse {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

fn locate_claude_bin() -> Option<String> {
    let candidates = [
        "/data/data/com.termux/files/usr/bin/claude",
        "/data/data/com.termux/files/home/.local/bin/claude",
        "/data/data/com.termux/files/home/.npm-global/bin/claude",
        "/usr/local/bin/claude",
        "/usr/bin/claude",
    ];

    for path in &candidates {
        if PathBuf::from(path).exists() {
            return Some(path.to_string());
        }
    }

    if let Ok(path_var) = std::env::var("PATH") {
        for dir in path_var.split(':') {
            let p = PathBuf::from(dir).join("claude");
            if p.exists() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }

    None
}

fn read_claude_auth() -> ClaudeAuthStatus {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/user/0/com.termux/files/home".to_string());
    let config_path = PathBuf::from(&home).join(".claude.json");
    if config_path.exists() {
        if let Ok(content) = std::fs::read_to_string(&config_path) {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
                let oauth_email = json.get("oauthAccount").and_then(|a| a.get("email")).and_then(|e| e.as_str());
                let has_key = json.get("primaryApiKey").is_some() || json.get("apiKey").is_some() || json.get("oauthAccount").is_some();
                let sub_type = json.get("oauthAccount").and_then(|a| a.get("subscriptionType")).and_then(|s| s.as_str());

                if has_key || oauth_email.is_some() {
                    return ClaudeAuthStatus {
                        logged_in: true,
                        auth_method: Some("claude_oauth".to_string()),
                        email: oauth_email.map(|s| s.to_string()),
                        subscription_type: sub_type.map(|s| s.to_string()),
                    };
                }
            }
        }
    }

    ClaudeAuthStatus {
        logged_in: false,
        auth_method: None,
        email: None,
        subscription_type: None,
    }
}

pub async fn claude_status_handler() -> Json<ClaudeCliStatus> {
    let bin = locate_claude_bin();
    let auth = read_claude_auth();

    if let Some(bin_path) = bin {
        // Run bin_path --version to verify if the wrapper actually points to an installed binary
        let out = tokio::process::Command::new(&bin_path)
            .arg("--version")
            .output()
            .await;

        match out {
            Ok(output) if output.status.success() => {
                let version_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
                Json(ClaudeCliStatus {
                    success: true,
                    installed: true,
                    version: Some(if version_str.is_empty() { "1.0.0".to_string() } else { version_str }),
                    bin_path: Some(bin_path),
                    auth: Some(auth),
                    auth_error: None,
                    error: None,
                })
            }
            _ => {
                // The launcher exited with non-zero or error: not installed
                Json(ClaudeCliStatus {
                    success: true,
                    installed: false,
                    version: None,
                    bin_path: Some(bin_path),
                    auth: Some(auth),
                    auth_error: None,
                    error: None,
                })
            }
        }
    } else {
        Json(ClaudeCliStatus {
            success: true,
            installed: false,
            version: None,
            bin_path: None,
            auth: Some(auth),
            auth_error: None,
            error: None,
        })
    }
}

pub async fn claude_info_handler() -> Json<ClaudeInfoResponse> {
    Json(ClaudeInfoResponse {
        success: true,
        info: None,
        default_mode: Some("auto".to_string()),
        error: None,
    })
}

pub async fn claude_sessions_handler() -> Json<ClaudeSessionListResponse> {
    Json(ClaudeSessionListResponse {
        sessions: Vec::new(),
    })
}

pub async fn claude_providers_handler() -> Json<ClaudeProvidersResponse> {
    Json(ClaudeProvidersResponse {
        success: true,
        providers: Vec::new(),
        settings_endpoint: None,
        settings_conflicts: Vec::new(),
        error: None,
    })
}

pub async fn claude_usage_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "success": true,
        "usage": null
    }))
}

pub async fn claude_logout_handler() -> Json<BridgeSimpleResponse> {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/user/0/com.termux/files/home".to_string());
    let config_path = PathBuf::from(&home).join(".claude.json");
    if config_path.exists() {
        if let Ok(mut json) = serde_json::from_str::<serde_json::Value>(&std::fs::read_to_string(&config_path).unwrap_or_default()) {
            if let Some(obj) = json.as_object_mut() {
                obj.remove("oauthAccount");
                obj.remove("primaryApiKey");
                obj.remove("apiKey");
                let _ = std::fs::write(&config_path, serde_json::to_string_pretty(&json).unwrap_or_default());
            }
        }
    }

    Json(BridgeSimpleResponse {
        success: true,
        error: None,
    })
}
