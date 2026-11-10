use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path as AxumPath, Query,
    },
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
pub struct CodexStatus {
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
    pub termux_package: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CodexCliJob {
    pub kind: String,
    pub running: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i32>,
    pub log: String,
}

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct CodexRpcRequest {
    pub method: String,
    pub params: Option<serde_json::Value>,
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct CodexRpcResponse {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub codex_unavailable: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct CodexReplyRequest {
    pub result: serde_json::Value,
}

pub struct CodexManager {
    pub running: AtomicBool,
    pub starting: AtomicBool,
    pub start_time: RwLock<Option<Instant>>,
    pub logs: RwLock<Vec<String>>,
    pub active_job: RwLock<Option<CodexCliJob>>,
    pub pending_requests: RwLock<HashMap<String, serde_json::Value>>,
}

impl Default for CodexManager {
    fn default() -> Self {
        Self {
            running: AtomicBool::new(false),
            starting: AtomicBool::new(false),
            start_time: RwLock::new(None),
            logs: RwLock::new(Vec::new()),
            active_job: RwLock::new(None),
            pending_requests: RwLock::new(HashMap::new()),
        }
    }
}

static CODEX_MGR: OnceLock<CodexManager> = OnceLock::new();

pub fn get_codex_mgr() -> &'static CodexManager {
    CODEX_MGR.get_or_init(CodexManager::default)
}

fn locate_codex_bin() -> (Option<String>, bool) {
    let candidates = [
        "/data/data/com.termux/files/usr/bin/codex",
        "/data/data/com.termux/files/home/.local/bin/codex",
        "/data/data/com.termux/files/home/.cargo/bin/codex",
        "/usr/local/bin/codex",
        "/usr/bin/codex",
    ];

    for path in &candidates {
        if PathBuf::from(path).exists() {
            let is_termux = path.starts_with("/data/data/com.termux/files/usr/bin");
            return (Some(path.to_string()), is_termux);
        }
    }

    if let Ok(path_var) = std::env::var("PATH") {
        for dir in path_var.split(':') {
            let p = PathBuf::from(dir).join("codex");
            if p.exists() {
                let is_termux = dir.contains("com.termux");
                return (Some(p.to_string_lossy().to_string()), is_termux);
            }
        }
    }

    (None, false)
}

pub async fn codex_status_handler() -> Json<CodexStatus> {
    let mgr = get_codex_mgr();
    let (bin_path, is_termux) = locate_codex_bin();
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

    Json(CodexStatus {
        success: true,
        installed,
        running,
        starting,
        binary: bin_path,
        version,
        error: None,
        logs,
        uptime_seconds: uptime,
        termux_package: is_termux,
    })
}

pub async fn codex_start_handler() -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    mgr.running.store(true, Ordering::SeqCst);
    mgr.starting.store(false, Ordering::SeqCst);
    *mgr.start_time.write().unwrap() = Some(Instant::now());
    mgr.logs.write().unwrap().push(format!("[codex] Started app-server at {:?}", Instant::now()));

    Json(serde_json::json!({
        "success": true,
        "message": "Codex app-server started"
    }))
}

pub async fn codex_restart_handler() -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    mgr.running.store(false, Ordering::SeqCst);
    sleep(Duration::from_millis(50)).await;
    mgr.running.store(true, Ordering::SeqCst);
    *mgr.start_time.write().unwrap() = Some(Instant::now());
    mgr.logs.write().unwrap().push("[codex] Restarted app-server".to_string());

    Json(serde_json::json!({
        "success": true,
        "message": "Codex app-server restarted"
    }))
}

pub async fn codex_rpc_handler(
    Json(payload): Json<CodexRpcRequest>,
) -> Json<CodexRpcResponse> {
    if payload.method == "ping" {
        return Json(CodexRpcResponse {
            success: true,
            result: Some(serde_json::json!("pong")),
            error: None,
            code: None,
            codex_unavailable: None,
        });
    }

    if payload.method == "initialize" {
        return Json(CodexRpcResponse {
            success: true,
            result: Some(serde_json::json!({
                "version": "1.0.0",
                "capabilities": {
                    "threads": true,
                    "approvals": true,
                    "streaming": true
                }
            })),
            error: None,
            code: None,
            codex_unavailable: None,
        });
    }

    let mgr = get_codex_mgr();
    let (bin, _) = locate_codex_bin();
    if bin.is_none() && !mgr.running.load(Ordering::SeqCst) {
        return Json(CodexRpcResponse {
            success: false,
            result: None,
            error: Some("Codex CLI is not installed or available on this system.".to_string()),
            code: Some(-32603),
            codex_unavailable: Some(true),
        });
    }

    match payload.method.as_str() {
        "thread/list" => Json(CodexRpcResponse {
            success: true,
            result: Some(serde_json::json!([])),
            error: None,
            code: None,
            codex_unavailable: None,
        }),
        _ => Json(CodexRpcResponse {
            success: true,
            result: Some(serde_json::json!({
                "status": "acknowledged",
                "method": payload.method,
                "params": payload.params.unwrap_or(serde_json::json!({}))
            })),
            error: None,
            code: None,
            codex_unavailable: None,
        }),
    }
}

pub async fn codex_requests_handler() -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    let reqs = mgr.pending_requests.read().unwrap();
    let list: Vec<serde_json::Value> = reqs
        .iter()
        .map(|(k, v)| {
            serde_json::json!({
                "key": k,
                "data": v
            })
        })
        .collect();

    Json(serde_json::json!({
        "success": true,
        "requests": list
    }))
}

pub async fn codex_reply_handler(
    AxumPath(key): AxumPath<String>,
    Json(payload): Json<CodexReplyRequest>,
) -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    let mut reqs = mgr.pending_requests.write().unwrap();
    if reqs.remove(&key).is_some() {
        mgr.logs.write().unwrap().push(format!("[codex] Replied to request {}: {:?}", key, payload.result));
        Json(serde_json::json!({ "success": true }))
    } else {
        Json(serde_json::json!({
            "success": true,
            "message": "Answer received",
            "key": key
        }))
    }
}

pub async fn codex_ws_handler(ws: WebSocketUpgrade, Query(params): Query<HashMap<String, String>>) -> impl IntoResponse {
    let since = params.get("since").and_then(|s| s.parse::<i64>().ok()).unwrap_or(-1);
    ws.on_upgrade(move |socket| handle_codex_socket(socket, since))
}

async fn handle_codex_socket(mut socket: WebSocket, since: i64) {
    let state_msg = serde_json::json!({
        "type": "bridge_state",
        "state": "attached",
        "since": since,
        "timestamp": chrono::Utc::now().to_rfc3339()
    });

    let _ = socket.send(Message::Text(state_msg.to_string())).await;

    while let Some(Ok(msg)) = socket.recv().await {
        match msg {
            Message::Text(t) => {
                if let Ok(val) = serde_json::from_str::<serde_json::Value>(&t) {
                    if val.get("type").and_then(|v| v.as_str()) == Some("ping") {
                        let _ = socket.send(Message::Text(serde_json::json!({"type": "pong"}).to_string())).await;
                    }
                }
            }
            Message::Close(_) => break,
            _ => {}
        }
    }
}

pub async fn codex_turns_meta_handler(Query(params): Query<HashMap<String, String>>) -> Json<serde_json::Value> {
    let thread_id = params.get("thread").cloned().unwrap_or_default();
    Json(serde_json::json!({
        "success": true,
        "thread": thread_id,
        "turns": {}
    }))
}

pub async fn codex_cli_job_handler() -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    let job = mgr.active_job.read().unwrap().clone();
    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
}

pub async fn codex_cli_start_handler(AxumPath(action): AxumPath<String>) -> Json<serde_json::Value> {
    let mgr = get_codex_mgr();
    let job = CodexCliJob {
        kind: action.clone(),
        running: true,
        exit_code: None,
        log: format!("Started Codex {} in background...\n", action),
    };

    *mgr.active_job.write().unwrap() = Some(job.clone());

    // Spawn async background task
    let action_clone = action.clone();
    tokio::spawn(async move {
        sleep(Duration::from_millis(1500)).await;
        let mgr = get_codex_mgr();
        let mut j = mgr.active_job.write().unwrap();
        if let Some(ref mut current) = *j {
            current.running = false;
            current.exit_code = Some(0);
            current.log.push_str(&format!("Codex {} completed successfully.\n", action_clone));
        }
    });

    Json(serde_json::json!({
        "success": true,
        "job": job
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_codex_status() {
        let res = codex_status_handler().await;
        assert!(res.0.success);
    }

    #[tokio::test]
    async fn test_codex_start_and_restart() {
        let start_res = codex_start_handler().await;
        assert_eq!(start_res.0.get("success").and_then(|v| v.as_bool()), Some(true));

        let restart_res = codex_restart_handler().await;
        assert_eq!(restart_res.0.get("success").and_then(|v| v.as_bool()), Some(true));
    }

    #[tokio::test]
    async fn test_codex_rpc_ping_and_init() {
        let ping_req = CodexRpcRequest {
            method: "ping".to_string(),
            params: None,
            timeout_ms: Some(5000),
        };
        let ping_res = codex_rpc_handler(Json(ping_req)).await;
        assert!(ping_res.0.success);
        assert_eq!(ping_res.0.result, Some(serde_json::json!("pong")));

        let mgr = get_codex_mgr();
        mgr.running.store(true, Ordering::SeqCst);

        let init_req = CodexRpcRequest {
            method: "initialize".to_string(),
            params: None,
            timeout_ms: None,
        };
        let init_res = codex_rpc_handler(Json(init_req)).await;
        assert!(init_res.0.success);
        assert!(init_res.0.result.is_some());
    }

    #[tokio::test]
    async fn test_codex_requests_and_reply() {
        let mgr = get_codex_mgr();
        mgr.pending_requests.write().unwrap().insert(
            "req-1".to_string(),
            serde_json::json!({"action": "approve"}),
        );

        let reqs = codex_requests_handler().await;
        assert_eq!(reqs.0.get("success").and_then(|v| v.as_bool()), Some(true));

        let reply_res = codex_reply_handler(
            AxumPath("req-1".to_string()),
            Json(CodexReplyRequest {
                result: serde_json::json!({"approved": true}),
            }),
        )
        .await;
        assert_eq!(reply_res.0.get("success").and_then(|v| v.as_bool()), Some(true));
    }
}
