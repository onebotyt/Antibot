use axum::{
    extract::Query,
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use std::time::Instant;
use tokio::net::TcpStream;
use tokio::time::{timeout, Duration};
use antibot_native::net_traffic::{HttpTrafficEntry, NetworkMetrics, TrafficInspector};

static TRAFFIC_INSPECTOR: Mutex<Option<TrafficInspector>> = Mutex::new(None);

#[derive(Debug, Deserialize)]
pub struct TrafficQueryParams {
    #[serde(default = "default_limit")]
    pub limit: usize,
}

fn default_limit() -> usize {
    50
}

#[derive(Debug, Serialize)]
pub struct StatusResponse {
    pub status: String,
}

#[derive(Debug, Deserialize)]
pub struct ProxyTestRequest {
    #[serde(default = "default_target_host")]
    pub host: String,
    #[serde(default = "default_target_port")]
    pub port: u16,
    #[serde(default = "default_timeout_ms")]
    pub timeout_ms: u64,
}

fn default_target_host() -> String {
    "127.0.0.1".to_string()
}

fn default_target_port() -> u16 {
    80
}

fn default_timeout_ms() -> u64 {
    2000
}

#[derive(Debug, Serialize)]
pub struct ProxyTestResponse {
    pub success: bool,
    pub latency_ms: u64,
    pub address: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// GET /api/network/traffic
///
/// Returns recent HTTP transactions captured by the traffic inspector.
pub async fn network_traffic_handler(
    Query(params): Query<TrafficQueryParams>,
) -> (StatusCode, Json<Vec<HttpTrafficEntry>>) {
    let lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let entries = match &*lock {
        Some(inspector) => inspector.get_recent(params.limit),
        None => Vec::new(),
    };
    (StatusCode::OK, Json(entries))
}

/// POST /api/network/record
///
/// Records an HTTP transaction entry into the traffic inspector.
pub async fn network_record_handler(
    Json(entry): Json<HttpTrafficEntry>,
) -> (StatusCode, Json<StatusResponse>) {
    let mut lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let inspector = lock.get_or_insert_with(TrafficInspector::default);
    inspector.record(entry);
    (
        StatusCode::OK,
        Json(StatusResponse {
            status: "ok".to_string(),
        }),
    )
}

/// GET /api/network/stats
///
/// Returns real-time network throughput and traffic metrics.
pub async fn network_stats_handler() -> (StatusCode, Json<NetworkMetrics>) {
    let lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let metrics = match &*lock {
        Some(inspector) => inspector.get_metrics(),
        None => TrafficInspector::default().get_metrics(),
    };
    (StatusCode::OK, Json(metrics))
}

/// POST /api/network/clear
///
/// Clears captured traffic history and resets metric counters.
pub async fn network_clear_handler() -> (StatusCode, Json<StatusResponse>) {
    let mut lock = TRAFFIC_INSPECTOR.lock().unwrap();
    if let Some(inspector) = lock.as_mut() {
        inspector.clear();
    }
    (
        StatusCode::OK,
        Json(StatusResponse {
            status: "ok".to_string(),
        }),
    )
}

/// POST /api/network/proxy/test
///
/// Tests connectivity, DNS resolution, and TCP handshake latency to an upstream proxy or endpoint.
pub async fn network_proxy_test_handler(
    Json(payload): Json<ProxyTestRequest>,
) -> (StatusCode, Json<ProxyTestResponse>) {
    let addr = format!("{}:{}", payload.host, payload.port);
    let start = Instant::now();
    let timeout_dur = Duration::from_millis(payload.timeout_ms.max(100));

    match timeout(timeout_dur, TcpStream::connect(&addr)).await {
        Ok(Ok(_stream)) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            (
                StatusCode::OK,
                Json(ProxyTestResponse {
                    success: true,
                    latency_ms,
                    address: addr,
                    error: None,
                }),
            )
        }
        Ok(Err(e)) => {
            let latency_ms = start.elapsed().as_millis() as u64;
            (
                StatusCode::OK,
                Json(ProxyTestResponse {
                    success: false,
                    latency_ms,
                    address: addr,
                    error: Some(e.to_string()),
                }),
            )
        }
        Err(_) => (
            StatusCode::OK,
            Json(ProxyTestResponse {
                success: false,
                latency_ms: payload.timeout_ms,
                address: addr,
                error: Some("Connection timed out".to_string()),
            }),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_network_handlers_lifecycle() {
        // 1. Record entry
        let entry = HttpTrafficEntry {
            id: "tx-test-1".to_string(),
            timestamp: 12345678,
            method: "POST".to_string(),
            url: "/api/chat/completions".to_string(),
            status_code: 200,
            duration_ms: 35,
            bytes_sent: 1024,
            bytes_received: 4096,
            client_ip: "127.0.0.1".to_string(),
            error: None,
        };

        let (status, Json(rec_res)) = network_record_handler(Json(entry)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(rec_res.status, "ok");

        // 2. Query traffic
        let (status, Json(traffic)) = network_traffic_handler(Query(TrafficQueryParams { limit: 10 })).await;
        assert_eq!(status, StatusCode::OK);
        assert!(!traffic.is_empty());
        assert_eq!(traffic[0].id, "tx-test-1");

        // 3. Stats
        let (status, Json(stats)) = network_stats_handler().await;
        assert_eq!(status, StatusCode::OK);
        assert!(stats.request_count >= 1);
        assert!(stats.total_bytes_sent >= 1024);

        // 4. Clear
        let (status, Json(clr_res)) = network_clear_handler().await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(clr_res.status, "ok");

        let (status, Json(empty_traffic)) = network_traffic_handler(Query(TrafficQueryParams { limit: 10 })).await;
        assert_eq!(status, StatusCode::OK);
        assert!(empty_traffic.is_empty());
    }

    #[tokio::test]
    async fn test_network_proxy_test_timeout() {
        let req = ProxyTestRequest {
            host: "192.0.2.1".to_string(), // RFC 5737 TEST-NET-1 (unroutable)
            port: 9999,
            timeout_ms: 100,
        };
        let (status, Json(resp)) = network_proxy_test_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(!resp.success);
        assert!(resp.error.is_some());
    }
}
