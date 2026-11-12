use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    response::IntoResponse,
};
use futures_util::{SinkExt, StreamExt};
use serde_json::json;
use std::sync::Arc;
use tracing::warn;

use crate::AppState;

pub async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    ws.on_upgrade(|socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: Arc<AppState>) {
    let (mut sender, mut receiver) = socket.split();
    let mut rx = state.tx.subscribe();

    // Send initial connected greeting
    let greeting = json!({
        "type": "connected",
        "version": "1.0.64",
        "engine": "rust",
    });
    if let Err(e) = sender.send(Message::Text(greeting.to_string())).await {
        warn!("Failed to send WS greeting: {}", e);
        return;
    }

    // Send initial hub status so client receives immediate status rather than timing out
    let hub_status = json!({
        "type": "hub_status",
        "status": "stopped",
        "port": state.hub_port.to_string(),
        "csrf_token": "",
        "logs": []
    });
    let _ = sender.send(Message::Text(hub_status.to_string())).await;

    let mut send_task = tokio::spawn(async move {
        while let Ok(msg) = rx.recv().await {
            if sender.send(Message::Text(msg)).await.is_err() {
                break;
            }
        }
    });

    let mut recv_task = tokio::spawn(async move {
        while let Some(Ok(msg)) = receiver.next().await {
            match msg {
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
