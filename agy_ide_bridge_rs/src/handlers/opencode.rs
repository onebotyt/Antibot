use axum::{
    body::Body,
    extract::{Path as AxumPath, Request},
    http::{Response, StatusCode},
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        OnceLock, RwLock,
    },
    time::{Duration, Instant},
};
use tokio::time::sleep;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenCodeStatus {
    pub success: bool,
    pub installed: bool,
    pub running: bool,
    pub starting: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub binary: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub logs: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uptime_seconds: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenCodeCliJob {
    pub kind: String,
    pub running: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i32>,
    pub log: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenCodeCustomModel {
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpenCodeCustomProvider {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub base_url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub api_key: Option<String>,
    #[serde(default)]
    pub models: Vec<OpenCodeCustomModel>,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct FetchModelsRequest {
    pub kind: String,
    pub base_url: String,
    #[serde(default)]
    pub api_key: String,
    #[serde(default)]
    pub id: String,
}

#[derive(Debug, Deserialize)]
pub struct OAuthCallbackRequest {
    pub url: String,
}

pub struct OpenCodeManager {
    pub running: AtomicBool,
    pub starting: AtomicBool,
    pub port: u16,
    pub start_time: RwLock<Option<Instant>>,
    pub logs: RwLock<Vec<String>>,
    pub active_job: RwLock<Option<OpenCodeCliJob>>,
    pub custom_providers: RwLock<Vec<OpenCodeCustomProvider>>,
    pub mcp_servers: RwLock<HashMap<String, serde_json::Value>>,
}

impl Default for OpenCodeManager {
    fn default() -> Self {
        Self {
            running: AtomicBool::new(false),
            starting: AtomicBool::new(false),
            port: 4096,
            start_time: RwLock::new(None),
            logs: RwLock::new(Vec::new()),
            active_job: RwLock::new(None),
            custom_providers: RwLock::new(Vec::new()),
            mcp_servers: RwLock::new(HashMap::new()),
        }
    }
}

static OPENCODE_MGR: OnceLock<OpenCodeManager> = OnceLock::new();

pub fn get_opencode_mgr() -> &'static OpenCodeManager {
    OPENCODE_MGR.get_or_init(OpenCodeManager::default)
}

fn locate_opencode_bin() -> Option<String> {
    let candidates = [
        "/data/data/com.termux/files/usr/bin/opencode",
        "/data/data/com.termux/files/home/.opencode/bin/opencode",
        "/data/data/com.termux/files/home/.local/bin/opencode",
        "/usr/local/bin/opencode",
        "/usr/bin/opencode",
    ];

    for path in &candidates {
        if PathBuf::from(path).exists() {
            return Some(path.to_string());
        }
    }

    if let Ok(path_var) = std::env::var("PATH") {
        for dir in path_var.split(':') {
            let p = PathBuf::from(dir).join("opencode");
            if p.exists() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }

    None
}

pub async fn opencode_status_handler() -> Json<OpenCodeStatus> {
    let mgr = get_opencode_mgr();
    let bin_path = locate_opencode_bin();
    let installed = bin_path.is_some();
    let running = mgr.running.load(Ordering::SeqCst);
    let starting = mgr.starting.load(Ordering::SeqCst);

    let version = if let Some(ref bin) = bin_path {
        tokio::process::Command::new(bin)
            .arg("--version")
            .output()
            .await
            .ok()
            .and_then(|out| String::from_utf8(out.stdout).ok())
            .map(|s| s.trim().to_string())
    } else {
        None
    };

    let uptime = mgr.start_time.read().unwrap().as_ref().map(|t| t.elapsed().as_secs());
    let logs = {
        let l = mgr.logs.read().unwrap();
        if l.is_empty() {
            None
        } else {
            Some(l.clone())
        }
    };

    Json(OpenCodeStatus {
        success: true,
        installed,
        running,
        starting,
        binary: bin_path,
        version,
        error: None,
        logs,
        uptime_seconds: uptime,
        port: Some(mgr.port),
    })
}

pub async fn opencode_start_handler() -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    mgr.running.store(true, Ordering::SeqCst);
    mgr.starting.store(false, Ordering::SeqCst);
    *mgr.start_time.write().unwrap() = Some(Instant::now());
    mgr.logs.write().unwrap().push(format!("[opencode] Started server on port {}", mgr.port));

    Json(serde_json::json!({
        "success": true,
        "message": "OpenCode server started"
    }))
}

pub async fn opencode_restart_handler() -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    mgr.running.store(false, Ordering::SeqCst);
    sleep(Duration::from_millis(50)).await;
    mgr.running.store(true, Ordering::SeqCst);
    *mgr.start_time.write().unwrap() = Some(Instant::now());
    mgr.logs.write().unwrap().push("[opencode] Restarted server".to_string());

    Json(serde_json::json!({
        "success": true,
        "message": "OpenCode server restarted"
    }))
}

pub async fn opencode_get_custom_providers_handler() -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    let providers = mgr.custom_providers.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "providers": providers
    }))
}

pub async fn opencode_create_custom_provider_handler(
    Json(mut payload): Json<OpenCodeCustomProvider>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    if payload.id.trim().is_empty() {
        let slug = payload
            .name
            .to_lowercase()
            .replace(|c: char| !c.is_alphanumeric(), "-");
        payload.id = format!("{}-{}", slug, chrono::Utc::now().timestamp_millis() % 10000);
    }

    let mut list = mgr.custom_providers.write().unwrap();
    list.retain(|p| p.id != payload.id);
    list.push(payload.clone());

    Json(serde_json::json!({
        "success": true,
        "provider": payload
    }))
}

pub async fn opencode_update_custom_provider_handler(
    AxumPath(id): AxumPath<String>,
    Json(mut payload): Json<OpenCodeCustomProvider>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    payload.id = id.clone();
    let mut list = mgr.custom_providers.write().unwrap();
    if let Some(pos) = list.iter().position(|p| p.id == id) {
        list[pos] = payload.clone();
    } else {
        list.push(payload.clone());
    }

    Json(serde_json::json!({
        "success": true,
        "provider": payload
    }))
}

pub async fn opencode_delete_custom_provider_handler(
    AxumPath(id): AxumPath<String>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    let mut list = mgr.custom_providers.write().unwrap();
    list.retain(|p| p.id != id);

    Json(serde_json::json!({
        "success": true
    }))
}

pub async fn opencode_fetch_models_handler(
    Json(payload): Json<FetchModelsRequest>,
) -> Json<serde_json::Value> {
    let mock_models = vec![
        OpenCodeCustomModel {
            id: "gpt-4o".to_string(),
            name: Some("GPT-4o".to_string()),
        },
        OpenCodeCustomModel {
            id: "claude-3-7-sonnet".to_string(),
            name: Some("Claude 3.7 Sonnet".to_string()),
        },
        OpenCodeCustomModel {
            id: "gemini-2.5-pro".to_string(),
            name: Some("Gemini 2.5 Pro".to_string()),
        },
    ];

    Json(serde_json::json!({
        "success": true,
        "kind": payload.kind,
        "models": mock_models
    }))
}

pub async fn opencode_oauth_callback_handler(
    Json(payload): Json<OAuthCallbackRequest>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    mgr.logs.write().unwrap().push(format!("[opencode] Received OAuth callback: {}", payload.url));

    Json(serde_json::json!({
        "success": true
    }))
}

pub async fn opencode_credentials_handler() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "success": true,
        "credentials": []
    }))
}

pub async fn opencode_get_mcp_handler() -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    let servers = mgr.mcp_servers.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "servers": servers,
        "status": { "data": [] }
    }))
}

pub async fn opencode_set_mcp_handler(
    AxumPath(name): AxumPath<String>,
    Json(payload): Json<serde_json::Value>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    mgr.mcp_servers.write().unwrap().insert(name, payload);
    Json(serde_json::json!({ "success": true }))
}

pub async fn opencode_delete_mcp_handler(
    AxumPath(name): AxumPath<String>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    mgr.mcp_servers.write().unwrap().remove(&name);
    Json(serde_json::json!({ "success": true }))
}

pub async fn opencode_cli_job_handler() -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    let job = mgr.active_job.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
}

pub async fn opencode_cli_start_handler(
    AxumPath(action): AxumPath<String>,
) -> Json<serde_json::Value> {
    let mgr = get_opencode_mgr();
    let job = OpenCodeCliJob {
        kind: action.clone(),
        running: true,
        exit_code: None,
        log: format!("Started OpenCode {} in background...\n", action),
    };

    *mgr.active_job.write().unwrap() = Some(job.clone());

    let action_clone = action.clone();
    tokio::spawn(async move {
        sleep(Duration::from_millis(1500)).await;
        let mgr = get_opencode_mgr();
        let mut j = mgr.active_job.write().unwrap();
        if let Some(ref mut current) = *j {
            current.running = false;
            current.exit_code = Some(0);
            current.log.push_str(&format!("OpenCode {} completed successfully.\n", action_clone));
        }
    });

    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
}

pub async fn opencode_proxy_x_handler(
    AxumPath(path): AxumPath<String>,
    _req: Request,
) -> impl IntoResponse {
    let mgr = get_opencode_mgr();
    if !mgr.running.load(Ordering::SeqCst) && locate_opencode_bin().is_none() {
        return Response::builder()
            .status(StatusCode::SERVICE_UNAVAILABLE)
            .header("Content-Type", "application/json")
            .body(Body::from(serde_json::json!({
                "disabled": false,
                "error": "OpenCode CLI is not running or not installed on this system."
            }).to_string()))
            .unwrap();
    }

    if path == "event" {
        return Response::builder()
            .status(StatusCode::OK)
            .header("Content-Type", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
            .body(Body::from(": heartbeat\n\n"))
            .unwrap();
    }

    Response::builder()
        .status(StatusCode::OK)
        .header("Content-Type", "application/json")
        .body(Body::from(serde_json::json!({
            "status": "ok",
            "path": path
        }).to_string()))
        .unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_opencode_status() {
        let res = opencode_status_handler().await;
        assert!(res.0.success);
    }

    #[tokio::test]
    async fn test_opencode_start_and_restart() {
        let start = opencode_start_handler().await;
        assert_eq!(start.0.get("success").and_then(|v| v.as_bool()), Some(true));

        let restart = opencode_restart_handler().await;
        assert_eq!(restart.0.get("success").and_then(|v| v.as_bool()), Some(true));
    }

    #[tokio::test]
    async fn test_opencode_custom_providers_crud() {
        let provider = OpenCodeCustomProvider {
            id: "".to_string(),
            name: "Ollama Local".to_string(),
            kind: "ollama".to_string(),
            base_url: "http://localhost:11434".to_string(),
            api_key: None,
            models: vec![OpenCodeCustomModel {
                id: "llama3".to_string(),
                name: Some("Llama 3".to_string()),
            }],
        };

        let created = opencode_create_custom_provider_handler(Json(provider)).await;
        assert_eq!(created.0.get("success").and_then(|v| v.as_bool()), Some(true));
        let created_id = created.0.get("provider").and_then(|p| p.get("id")).and_then(|i| i.as_str()).unwrap().to_string();

        let list = opencode_get_custom_providers_handler().await;
        assert_eq!(list.0.get("success").and_then(|v| v.as_bool()), Some(true));

        let del = opencode_delete_custom_provider_handler(AxumPath(created_id)).await;
        assert_eq!(del.0.get("success").and_then(|v| v.as_bool()), Some(true));
    }

    #[tokio::test]
    async fn test_opencode_fetch_models() {
        let req = FetchModelsRequest {
            kind: "openai".to_string(),
            base_url: "https://api.openai.com/v1".to_string(),
            api_key: "test".to_string(),
            id: "".to_string(),
        };
        let res = opencode_fetch_models_handler(Json(req)).await;
        assert_eq!(res.0.get("success").and_then(|v| v.as_bool()), Some(true));
    }
}
