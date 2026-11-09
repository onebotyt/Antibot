use axum::{
    extract::{Path as AxumPath, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use chrono::Utc;
use serde_json::json;
use std::{process::Command, sync::Arc};
use crate::{models::{HealthResponse, StatusHub, StatusResponse}, AppState};

pub async fn health_handler() -> impl IntoResponse {
    let resp = HealthResponse {
        status: "ok".to_string(),
        timestamp: Utc::now().to_rfc3339(),
        version: "1.0.59".to_string(),
    };
    (StatusCode::OK, Json(resp))
}

pub async fn status_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let uptime = state.start_time.elapsed().as_secs_f64();
    let resp = StatusResponse {
        status: "ok".to_string(),
        uptime,
        timestamp: Utc::now().to_rfc3339(),
        version: "1.0.59".to_string(),
        hub: StatusHub {
            active: false,
            status: "stopped".to_string(),
            port: state.hub_port.to_string(),
            csrf_token: state.security.validate_token(None).then(|| "").unwrap_or("").to_string(),
            address: format!("http://127.0.0.1:{}", state.hub_port),
            error: String::new(),
            logs: vec![],
        },
    };
    (StatusCode::OK, Json(resp))
}

pub async fn server_info_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let resp = json!({
        "status": "ok",
        "engine": "rust",
        "version": "1.0.59",
        "port": state.port,
        "hubPort": state.hub_port,
        "homeDir": home,
        "projectsDir": state.workspace_dir.to_string_lossy(),
        "arch": std::env::consts::ARCH,
        "os": std::env::consts::OS,
    });
    (StatusCode::OK, Json(resp))
}

pub async fn shutdown_handler() -> impl IntoResponse {
    tokio::spawn(async {
        tokio::time::sleep(tokio::time::Duration::from_millis(150)).await;
        std::process::exit(0);
    });
    (StatusCode::OK, Json(json!({
        "success": true,
        "message": "AntiBot Rust daemon is shutting down..."
    })))
}

pub async fn models_handler() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "models": [] })))
}

pub async fn quotas_handler() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({})))
}

pub async fn instances_handler() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "instances": [] })))
}

pub async fn terminate_instance_handler(AxumPath(id): AxumPath<String>) -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "terminated", "conversationId": id })))
}

pub async fn abort_handler() -> impl IntoResponse {
    (StatusCode::OK, Json(json!({ "status": "ok" })))
}

pub async fn agy_cli_handler() -> impl IntoResponse {
    let which = Command::new("which").arg("agy").output();
    let (installed, path) = match which {
        Ok(out) if out.status.success() => {
            let p = String::from_utf8_lossy(&out.stdout).trim().to_string();
            (true, p)
        }
        _ => (false, String::new()),
    };

    let version = if installed {
        Command::new("agy")
            .arg("--version")
            .output()
            .ok()
            .and_then(|o| String::from_utf8(o.stdout).ok())
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "1.0.0".to_string())
    } else {
        String::new()
    };

    (
        StatusCode::OK,
        Json(json!({
            "installed": installed,
            "path": path,
            "version": version,
            "job": null
        })),
    )
}

pub async fn agy_cli_install_handler() -> impl IntoResponse {
    (
        StatusCode::OK,
        Json(json!({
            "success": true,
            "message": "AGY installer triggered"
        })),
    )
}
