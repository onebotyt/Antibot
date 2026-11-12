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

use axum::extract::Path as AxumPath;
use crate::handlers::cli_runner::{resolve_cli_binary, spawn_cli_job, CliJobState};
use std::sync::{Arc, OnceLock, RwLock};

#[allow(dead_code)]
pub type ClaudeCliJob = CliJobState;

pub struct ClaudeManager {
    pub active_job: Arc<RwLock<Option<CliJobState>>>,
}

impl Default for ClaudeManager {
    fn default() -> Self {
        Self {
            active_job: Arc::new(RwLock::new(None)),
        }
    }
}

static CLAUDE_MGR: OnceLock<ClaudeManager> = OnceLock::new();

pub fn get_claude_mgr() -> &'static ClaudeManager {
    CLAUDE_MGR.get_or_init(ClaudeManager::default)
}

fn locate_claude_bin() -> Option<String> {
    resolve_cli_binary("claude")
}

fn read_claude_auth() -> ClaudeAuthStatus {
    let mut candidate_homes = Vec::new();
    if let Ok(h) = std::env::var("HOME") {
        candidate_homes.push(PathBuf::from(h));
    }
    candidate_homes.push(PathBuf::from("/data/data/com.antibot/files/home"));
    candidate_homes.push(PathBuf::from("/data/user/0/com.antibot/files/home"));
    candidate_homes.push(PathBuf::from("/data/data/com.termux/files/home"));
    candidate_homes.push(PathBuf::from("/data/user/0/com.termux/files/home"));

    for home in candidate_homes {
        let config_path = home.join(".claude.json");
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
    }

    ClaudeAuthStatus {
        logged_in: false,
        auth_method: None,
        email: None,
        subscription_type: None,
    }
}

pub async fn claude_cli_job_handler() -> Json<serde_json::Value> {
    let mgr = get_claude_mgr();
    let job = mgr.active_job.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
}

pub async fn claude_cli_start_handler(
    AxumPath(action): AxumPath<String>,
) -> Json<serde_json::Value> {
    let mgr = get_claude_mgr();
    let script = match action.as_str() {
        "update" => "pnpm update -g @anthropic-ai/claude-code || npm update -g @anthropic-ai/claude-code".to_string(),
        _ => "pnpm add -g @anthropic-ai/claude-code || npm install -g @anthropic-ai/claude-code".to_string(),
    };

    spawn_cli_job("Claude Code".to_string(), action.clone(), script, mgr.active_job.clone());

    let job = mgr.active_job.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
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
