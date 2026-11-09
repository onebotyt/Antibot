use axum::{extract::State, http::StatusCode, response::IntoResponse, Json};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::Arc;

use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct McpCallReq {
    pub server: String,
    pub tool: String,
    #[serde(default)]
    pub arguments: Value,
}

#[derive(Debug, Deserialize)]
pub struct McpServerActionReq {
    pub server: String,
}

#[derive(Debug, Deserialize)]
pub struct McpToggleReq {
    pub server: String,
    pub enabled: bool,
}

/// GET /api/mcp/servers - Lists all configured MCP servers with status and stats.
pub async fn mcp_list_servers_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let servers = state.mcp_supervisor.list_servers();
    let total = servers.len();
    (
        StatusCode::OK,
        Json(json!({
            "servers": servers,
            "total": total
        })),
    )
}

/// GET /api/mcp/tools - Lists all discovered tools across all enabled servers.
pub async fn mcp_list_tools_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let tools = state.mcp_supervisor.list_all_tools();
    let total = tools.len();
    (
        StatusCode::OK,
        Json(json!({
            "tools": tools,
            "total": total
        })),
    )
}

/// POST /api/mcp/call - Executes an MCP tool via JSON-RPC 2.0 streaming stdio.
pub async fn mcp_call_tool_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<McpCallReq>,
) -> impl IntoResponse {
    match state
        .mcp_supervisor
        .call_tool(&req.server, &req.tool, req.arguments)
    {
        Ok(result) => (
            StatusCode::OK,
            Json(json!({
                "success": true,
                "server": req.server,
                "tool": req.tool,
                "result": result
            })),
        ),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(json!({
                "success": false,
                "server": req.server,
                "tool": req.tool,
                "error": e
            })),
        ),
    }
}

/// POST /api/mcp/restart - Restarts an MCP server child process.
pub async fn mcp_restart_server_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<McpServerActionReq>,
) -> impl IntoResponse {
    match state.mcp_supervisor.restart_server(&req.server) {
        Ok(pid) => (
            StatusCode::OK,
            Json(json!({
                "success": true,
                "server": req.server,
                "pid": pid,
                "message": format!("Server '{}' restarted with PID {}", req.server, pid)
            })),
        ),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(json!({
                "success": false,
                "server": req.server,
                "error": e
            })),
        ),
    }
}

/// POST /api/mcp/toggle - Enables or disables an MCP server in mcp_config.json.
pub async fn mcp_toggle_server_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<McpToggleReq>,
) -> impl IntoResponse {
    match state.mcp_supervisor.toggle_server(&req.server, req.enabled) {
        Ok(_) => (
            StatusCode::OK,
            Json(json!({
                "success": true,
                "server": req.server,
                "enabled": req.enabled
            })),
        ),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(json!({
                "success": false,
                "server": req.server,
                "error": e
            })),
        ),
    }
}
