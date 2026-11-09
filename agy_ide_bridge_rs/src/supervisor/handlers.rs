use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path as AxumPath, Query, State,
    },
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;
use tracing::warn;

use crate::AppState;
use super::pty::SpawnOptions;

#[derive(Debug, Deserialize)]
pub struct WriteRequest {
    pub data: Option<String>,
    pub command: Option<String>,
    #[serde(rename = "appendEnter")]
    pub append_enter: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct ResizeRequest {
    pub cols: u16,
    pub rows: u16,
}

#[derive(Debug, Deserialize)]
pub struct KillRequest {
    pub signal: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct OutputQuery {
    pub max_bytes: Option<usize>,
}

pub async fn list_sessions_handler(
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    let sessions = state.supervisor.list().await;
    let list: Vec<Value> = sessions.iter().map(|s| s.to_json()).collect();
    (StatusCode::OK, Json(json!({ "sessions": list })))
}

pub async fn spawn_session_handler(
    State(state): State<Arc<AppState>>,
    Json(payload): Json<SpawnOptions>,
) -> impl IntoResponse {
    match state.supervisor.spawn(payload).await {
        Ok(session) => (
            StatusCode::CREATED,
            Json(json!({
                "success": true,
                "session": session.to_json(),
            })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({
                "success": false,
                "error": e.to_string(),
            })),
        ),
    }
}

pub async fn get_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
) -> impl IntoResponse {
    if let Some(session) = state.supervisor.get(&id).await {
        (StatusCode::OK, Json(session.to_json()))
    } else {
        (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": format!("Session '{}' not found", id) })),
        )
    }
}

pub async fn write_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
    Json(payload): Json<WriteRequest>,
) -> impl IntoResponse {
    let session = match state.supervisor.get(&id).await {
        Some(s) => s,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "error": format!("Session '{}' not found", id) })),
            );
        }
    };

    let mut data_to_write = Vec::new();
    if let Some(d) = payload.data {
        data_to_write.extend_from_slice(d.as_bytes());
    } else if let Some(cmd) = payload.command {
        data_to_write.extend_from_slice(cmd.as_bytes());
        if payload.append_enter.unwrap_or(true) {
            data_to_write.push(b'\n');
        }
    }

    match session.write_data(&data_to_write).await {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "success": false, "error": e.to_string() })),
        ),
    }
}

pub async fn resize_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
    Json(payload): Json<ResizeRequest>,
) -> impl IntoResponse {
    let session = match state.supervisor.get(&id).await {
        Some(s) => s,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "error": format!("Session '{}' not found", id) })),
            );
        }
    };

    match session.resize(payload.cols, payload.rows) {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true }))),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "success": false, "error": e.to_string() })),
        ),
    }
}

pub async fn kill_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
    Json(payload): Json<Option<KillRequest>>,
) -> impl IntoResponse {
    let signal = match payload.and_then(|p| p.signal) {
        Some(s) if s.eq_ignore_ascii_case("SIGKILL") => Some(libc::SIGKILL),
        Some(s) if s.eq_ignore_ascii_case("SIGINT") || s.eq_ignore_ascii_case("CTRL+C") => {
            Some(libc::SIGINT)
        }
        Some(s) if s.eq_ignore_ascii_case("SIGHUP") => Some(libc::SIGHUP),
        _ => Some(libc::SIGTERM),
    };

    match state.supervisor.kill(&id, signal).await {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "id": id }))),
        Err(e) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "success": false, "error": e })),
        ),
    }
}

pub async fn close_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
) -> impl IntoResponse {
    match state.supervisor.close(&id).await {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "id": id }))),
        Err(e) => (
            StatusCode::NOT_FOUND,
            Json(json!({ "success": false, "error": e })),
        ),
    }
}

pub async fn output_session_handler(
    State(state): State<Arc<AppState>>,
    AxumPath(id): AxumPath<String>,
    Query(query): Query<OutputQuery>,
) -> impl IntoResponse {
    let session = match state.supervisor.get(&id).await {
        Some(s) => s,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(json!({ "error": format!("Session '{}' not found", id) })),
            );
        }
    };

    let raw = session.get_output_snapshot(query.max_bytes);
    let text = String::from_utf8_lossy(&raw).to_string();
    (
        StatusCode::OK,
        Json(json!({
            "id": session.id,
            "text": text,
            "bytes": raw.len(),
            "isExited": !session.is_alive(),
            "exitCode": *session.exit_code.lock().unwrap(),
        })),
    )
}

// =========================================================================
// WebSocket Streaming Handler for Interactive Real-Time Terminal
// =========================================================================

pub async fn ws_terminal_handler(
    ws: WebSocketUpgrade,
    AxumPath(id): AxumPath<String>,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_terminal_socket(socket, id, state))
}

async fn handle_terminal_socket(socket: WebSocket, id: String, state: Arc<AppState>) {
    let session = match state.supervisor.get(&id).await {
        Some(s) => s,
        None => {
            warn!("WebSocket terminal connection rejected: session '{}' not found", id);
            return;
        }
    };

    let (mut sender, mut receiver) = socket.split();
    let mut rx = session.subscribe();

    // 1. Send initial catch-up snapshot from the ring buffer
    let initial_snapshot = session.get_output_snapshot(Some(64 * 1024));
    if !initial_snapshot.is_empty() {
        if sender.send(Message::Binary(initial_snapshot)).await.is_err() {
            return;
        }
    }

    // 2. Stream outgoing stdout/stderr to WebSocket client
    let mut send_task = tokio::spawn(async move {
        while let Ok(chunk) = rx.recv().await {
            if sender.send(Message::Binary(chunk)).await.is_err() {
                break;
            }
        }
    });

    // 3. Receive incoming stdin / control messages from WebSocket client
    let session_clone = Arc::clone(&session);
    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            match msg {
                Message::Binary(bin) => {
                    let _ = session_clone.write_data(&bin).await;
                }
                Message::Text(txt) => {
                    // Check if JSON control message (e.g. resize, signal)
                    if let Ok(val) = serde_json::from_str::<Value>(&txt) {
                        if let Some(msg_type) = val.get("type").and_then(|t| t.as_str()) {
                            match msg_type {
                                "resize" => {
                                    if let (Some(c), Some(r)) = (
                                        val.get("cols").and_then(|v| v.as_u64()),
                                        val.get("rows").and_then(|v| v.as_u64()),
                                    ) {
                                        let _ = session_clone.resize(c as u16, r as u16);
                                        continue;
                                    }
                                }
                                "signal" => {
                                    let sig_name = val.get("signal").and_then(|v| v.as_str()).unwrap_or("SIGINT");
                                    let sig = if sig_name.eq_ignore_ascii_case("SIGKILL") {
                                        libc::SIGKILL
                                    } else {
                                        libc::SIGINT
                                    };
                                    let _ = session_clone.kill_process_group(Some(sig));
                                    continue;
                                }
                                "input" => {
                                    if let Some(data) = val.get("data").and_then(|v| v.as_str()) {
                                        let _ = session_clone.write_data(data.as_bytes()).await;
                                        continue;
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    // Default to raw text as stdin
                    let _ = session_clone.write_data(txt.as_bytes()).await;
                }
                Message::Close(_) => break,
                Message::Ping(_) => continue,
                _ => {}
            }
        }
    });

    tokio::select! {
        _ = (&mut send_task) => recv_task.abort(),
        _ = (&mut recv_task) => send_task.abort(),
    };
}
