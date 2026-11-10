use axum::{
    body::Body,
    extract::Path as AxumPath,
    http::{HeaderMap, HeaderName, HeaderValue, Method, Response, StatusCode},
    response::IntoResponse,
    Json,
};
use chrono::Utc;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    net::SocketAddr,
    sync::atomic::{AtomicU64, Ordering},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    sync::RwLock,
};

/// Shared telemetry and state for cloud preview reverse proxy.
pub struct CloudProxyState {
    pub total_requests: AtomicU64,
    pub total_bytes: AtomicU64,
    pub routes: RwLock<HashMap<u16, CloudRouteConfig>>,
}

impl Default for CloudProxyState {
    fn default() -> Self {
        Self {
            total_requests: AtomicU64::new(0),
            total_bytes: AtomicU64::new(0),
            routes: RwLock::new(HashMap::new()),
        }
    }
}

// lazy_static_or_init
static CLOUD_STATE: std::sync::OnceLock<CloudProxyState> = std::sync::OnceLock::new();

pub fn get_cloud_state() -> &'static CloudProxyState {
    CLOUD_STATE.get_or_init(CloudProxyState::default)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CloudRouteConfig {
    pub port: u16,
    pub target_host: String,
    pub target_port: u16,
    pub description: String,
    pub rewrite_cors: bool,
    pub strip_frame_restrictions: bool,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct CloudStatusResponse {
    pub status: String,
    pub version: String,
    pub total_requests_proxied: u64,
    pub total_bytes_proxied: u64,
    pub active_routes: Vec<CloudRouteConfig>,
    pub timestamp: String,
}

#[derive(Debug, Deserialize)]
pub struct RegisterRouteRequest {
    pub port: u16,
    pub target_host: Option<String>,
    pub target_port: Option<u16>,
    pub description: Option<String>,
    pub rewrite_cors: Option<bool>,
    pub strip_frame_restrictions: Option<bool>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ScanPortsRequest {
    pub ports: Option<Vec<u16>>,
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ScanPortsResponse {
    pub active_ports: Vec<u16>,
    pub scanned_ports: usize,
    pub duration_ms: u64,
    pub timestamp: String,
}

#[derive(Debug, Deserialize)]
pub struct ProxyExecuteRequest {
    pub target_port: u16,
    pub path: Option<String>,
    pub method: Option<String>,
    pub headers: Option<HashMap<String, String>>,
    pub body: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ProxyExecuteResponse {
    pub status_code: u16,
    pub headers: HashMap<String, String>,
    pub body: String,
    pub duration_ms: u64,
}

/// GET /api/cloud/status
pub async fn cloud_status_handler() -> impl IntoResponse {
    let state = get_cloud_state();
    let routes = state.routes.read().await;
    let active_routes: Vec<CloudRouteConfig> = routes.values().cloned().collect();

    let resp = CloudStatusResponse {
        status: "ok".to_string(),
        version: "2.3.0".to_string(),
        total_requests_proxied: state.total_requests.load(Ordering::Relaxed),
        total_bytes_proxied: state.total_bytes.load(Ordering::Relaxed),
        active_routes,
        timestamp: Utc::now().to_rfc3339(),
    };
    (StatusCode::OK, Json(resp))
}

/// POST /api/cloud/preview/register
pub async fn cloud_register_route_handler(
    Json(payload): Json<RegisterRouteRequest>,
) -> impl IntoResponse {
    let state = get_cloud_state();
    let route = CloudRouteConfig {
        port: payload.port,
        target_host: payload.target_host.unwrap_or_else(|| "127.0.0.1".to_string()),
        target_port: payload.target_port.unwrap_or(payload.port),
        description: payload.description.unwrap_or_else(|| format!("Cloud preview port {}", payload.port)),
        rewrite_cors: payload.rewrite_cors.unwrap_or(true),
        strip_frame_restrictions: payload.strip_frame_restrictions.unwrap_or(true),
    };

    let mut routes = state.routes.write().await;
    routes.insert(payload.port, route.clone());

    (StatusCode::OK, Json(serde_json::json!({
        "status": "ok",
        "route": route
    })))
}

/// GET /api/cloud/preview/routes
pub async fn cloud_routes_handler() -> impl IntoResponse {
    let state = get_cloud_state();
    let routes = state.routes.read().await;
    let list: Vec<CloudRouteConfig> = routes.values().cloned().collect();
    (StatusCode::OK, Json(serde_json::json!({
        "status": "ok",
        "routes": list
    })))
}

/// POST /api/cloud/ports/scan
/// Scans common or specified ports to detect active local/cloud preview servers.
pub async fn cloud_scan_ports_handler(
    Json(payload): Json<ScanPortsRequest>,
) -> impl IntoResponse {
    let start = std::time::Instant::now();
    let default_ports = vec![3000, 5173, 8080, 8000, 4200, 5000, 8888, 9000];
    let ports = payload.ports.unwrap_or(default_ports);
    let timeout = Duration::from_millis(payload.timeout_ms.unwrap_or(150));

    let mut active = Vec::new();
    let total_scanned = ports.len();

    for port in ports {
        let addr = format!("127.0.0.1:{}", port);
        if let Ok(socket_addr) = addr.parse::<SocketAddr>() {
            if tokio::time::timeout(timeout, TcpStream::connect(socket_addr)).await.is_ok() {
                active.push(port);
            }
        }
    }

    let resp = ScanPortsResponse {
        active_ports: active,
        scanned_ports: total_scanned,
        duration_ms: start.elapsed().as_millis() as u64,
        timestamp: Utc::now().to_rfc3339(),
    };
    (StatusCode::OK, Json(resp))
}

/// Low-level raw HTTP request to an upstream port using async Tokio TCP stream.
pub async fn send_raw_http_request(
    host: &str,
    port: u16,
    method: &str,
    path: &str,
    headers: &HashMap<String, String>,
    body: Option<&[u8]>,
    timeout: Duration,
) -> Result<(u16, HashMap<String, String>, Vec<u8>), String> {
    let addr = format!("{}:{}", host, port);
    let stream = tokio::time::timeout(timeout, TcpStream::connect(&addr))
        .await
        .map_err(|_| format!("Connection to {} timed out after {}ms", addr, timeout.as_millis()))?
        .map_err(|e| format!("Failed to connect to {}: {}", addr, e))?;

    let (mut reader, mut writer) = stream.into_split();

    let mut req_str = format!("{} {} HTTP/1.1\r\nHost: {}:{}\r\n", method, path, host, port);
    for (k, v) in headers {
        let lk = k.to_lowercase();
        if lk != "host" && lk != "connection" && lk != "content-length" {
            req_str.push_str(&format!("{}: {}\r\n", k, v));
        }
    }

    let body_bytes = body.unwrap_or(&[]);
    req_str.push_str(&format!("Content-Length: {}\r\nConnection: close\r\n\r\n", body_bytes.len()));

    writer.write_all(req_str.as_bytes()).await.map_err(|e| e.to_string())?;
    if !body_bytes.is_empty() {
        writer.write_all(body_bytes).await.map_err(|e| e.to_string())?;
    }
    writer.flush().await.map_err(|e| e.to_string())?;

    let mut resp_bytes = Vec::new();
    tokio::time::timeout(timeout, reader.read_to_end(&mut resp_bytes))
        .await
        .map_err(|_| "Timed out reading response".to_string())?
        .map_err(|e| e.to_string())?;

    // Parse HTTP response
    let (status, resp_headers, body_offset) = parse_http_response(&resp_bytes)?;
    let resp_body = resp_bytes[body_offset..].to_vec();

    Ok((status, resp_headers, resp_body))
}

fn parse_http_response(bytes: &[u8]) -> Result<(u16, HashMap<String, String>, usize), String> {
    let header_end = bytes
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| "Malformed HTTP response (no header boundary)".to_string())?;

    let header_str = String::from_utf8_lossy(&bytes[..header_end]);
    let mut lines = header_str.lines();

    let status_line = lines.next().ok_or_else(|| "Missing status line".to_string())?;
    let parts: Vec<&str> = status_line.splitn(3, ' ').collect();
    if parts.len() < 2 {
        return Err("Invalid status line".to_string());
    }
    let status = parts[1].parse::<u16>().map_err(|_| "Invalid status code".to_string())?;

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((k, v)) = line.split_once(':') {
            headers.insert(k.trim().to_lowercase(), v.trim().to_string());
        }
    }

    Ok((status, headers, header_end + 4))
}

/// POST /api/cloud/preview/proxy
pub async fn cloud_preview_proxy_handler(
    Json(payload): Json<ProxyExecuteRequest>,
) -> impl IntoResponse {
    let start = std::time::Instant::now();
    let state = get_cloud_state();
    state.total_requests.fetch_add(1, Ordering::Relaxed);

    let path = payload.path.unwrap_or_else(|| "/".to_string());
    let method = payload.method.unwrap_or_else(|| "GET".to_string());
    let req_headers = payload.headers.unwrap_or_default();
    let body_bytes = payload.body.as_ref().map(|s| s.as_bytes());

    let (host, port) = {
        let routes = state.routes.read().await;
        if let Some(r) = routes.get(&payload.target_port) {
            (r.target_host.clone(), r.target_port)
        } else {
            ("127.0.0.1".to_string(), payload.target_port)
        }
    };

    match send_raw_http_request(
        &host,
        port,
        &method,
        &path,
        &req_headers,
        body_bytes,
        Duration::from_secs(10),
    )
    .await
    {
        Ok((status, mut headers, body)) => {
            state.total_bytes.fetch_add(body.len() as u64, Ordering::Relaxed);
            // Rewrite CORS & Sandbox headers
            headers.insert("access-control-allow-origin".to_string(), "*".to_string());
            headers.insert(
                "access-control-allow-methods".to_string(),
                "GET, POST, PUT, DELETE, OPTIONS, PATCH".to_string(),
            );
            headers.remove("x-frame-options");
            headers.remove("content-security-policy");

            let body_str = String::from_utf8_lossy(&body).to_string();
            let resp = ProxyExecuteResponse {
                status_code: status,
                headers,
                body: body_str,
                duration_ms: start.elapsed().as_millis() as u64,
            };
            (StatusCode::OK, Json(serde_json::to_value(resp).unwrap()))
        }
        Err(err) => {
            let error_json = serde_json::json!({
                "status": "error",
                "error": err,
                "duration_ms": start.elapsed().as_millis() as u64
            });
            (StatusCode::BAD_GATEWAY, Json(error_json))
        }
    }
}

/// Fallback / live reverse proxy handler:
/// Handles GET/POST/PUT/DELETE/OPTIONS to `/api/cloud/preview/:port` and `/api/cloud/preview/:port/*path`.
pub async fn cloud_reverse_proxy_gateway(
    AxumPath(params): AxumPath<HashMap<String, String>>,
    method: Method,
    headers: HeaderMap,
    req_body: Body,
) -> impl IntoResponse {
    let state = get_cloud_state();
    state.total_requests.fetch_add(1, Ordering::Relaxed);

    let port: u16 = match params.get("port").and_then(|p| p.parse().ok()) {
        Some(p) => p,
        None => {
            return Response::builder()
                .status(StatusCode::BAD_REQUEST)
                .body(Body::from("Missing or invalid port parameter"))
                .unwrap();
        }
    };

    let subpath = params.get("path").map(|p| format!("/{}", p.trim_start_matches('/'))).unwrap_or_else(|| "/".to_string());

    let (host, target_port) = {
        let routes = state.routes.read().await;
        if let Some(r) = routes.get(&port) {
            (r.target_host.clone(), r.target_port)
        } else {
            ("127.0.0.1".to_string(), port)
        }
    };

    let body_bytes = match axum::body::to_bytes(req_body, 16 * 1024 * 1024).await {
        Ok(b) => b.to_vec(),
        Err(_) => Vec::new(),
    };

    let mut forward_headers = HashMap::new();
    for (name, val) in headers.iter() {
        if let Ok(v) = val.to_str() {
            forward_headers.insert(name.as_str().to_string(), v.to_string());
        }
    }

    match send_raw_http_request(
        &host,
        target_port,
        method.as_str(),
        &subpath,
        &forward_headers,
        if body_bytes.is_empty() { None } else { Some(&body_bytes) },
        Duration::from_secs(12),
    )
    .await
    {
        Ok((status_code, mut resp_headers, resp_body)) => {
            state.total_bytes.fetch_add(resp_body.len() as u64, Ordering::Relaxed);

            let mut builder = Response::builder().status(status_code);

            // Inject CORS and allow framing
            resp_headers.insert("access-control-allow-origin".to_string(), "*".to_string());
            resp_headers.insert(
                "access-control-allow-methods".to_string(),
                "GET, POST, PUT, DELETE, OPTIONS, PATCH, HEAD".to_string(),
            );
            resp_headers.remove("x-frame-options");
            resp_headers.remove("content-security-policy");

            for (k, v) in resp_headers {
                if let (Ok(hname), Ok(hval)) = (HeaderName::from_bytes(k.as_bytes()), HeaderValue::from_str(&v)) {
                    builder = builder.header(hname, hval);
                }
            }

            builder.body(Body::from(resp_body)).unwrap()
        }
        Err(err) => {
            let error_html = format!(
                "<!DOCTYPE html><html><body style=\"font-family:sans-serif;padding:24px;color:#333;\">\
                <h2>Could not reach cloud preview port {}</h2>\
                <p>{}</p>\
                <p>Ensure the dev server is running and listening on 0.0.0.0 or 127.0.0.1.</p>\
                </body></html>",
                port, err
            );
            Response::builder()
                .status(StatusCode::BAD_GATEWAY)
                .header("Content-Type", "text/html; charset=utf-8")
                .header("Access-Control-Allow-Origin", "*")
                .body(Body::from(error_html))
                .unwrap()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_http_response() {
        let raw = b"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\nHello";
        let (status, headers, offset) = parse_http_response(raw).expect("parse ok");
        assert_eq!(status, 200);
        assert_eq!(headers.get("content-type").map(|s| s.as_str()), Some("text/plain"));
        assert_eq!(&raw[offset..], b"Hello");
    }

    #[test]
    fn test_parse_http_error_response() {
        let raw = b"HTTP/1.1 404 Not Found\r\nServer: Mock\r\n\r\nNot Found";
        let (status, headers, offset) = parse_http_response(raw).expect("parse ok");
        assert_eq!(status, 404);
        assert_eq!(headers.get("server").map(|s| s.as_str()), Some("Mock"));
        assert_eq!(&raw[offset..], b"Not Found");
    }

    #[tokio::test]
    async fn test_cloud_state_registration() {
        let state = get_cloud_state();
        let mut routes = state.routes.write().await;
        routes.insert(
            5173,
            CloudRouteConfig {
                port: 5173,
                target_host: "127.0.0.1".to_string(),
                target_port: 5173,
                description: "Test Vite".to_string(),
                rewrite_cors: true,
                strip_frame_restrictions: true,
            },
        );
        assert!(routes.contains_key(&5173));
    }
}
