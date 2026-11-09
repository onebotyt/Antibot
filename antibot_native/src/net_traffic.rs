//! Native Network Traffic Inspector & Bandwidth Monitor in Rust.
//!
//! Provides:
//! - Thread-safe circular buffer for recording HTTP requests/responses.
//! - Real-time network throughput and metrics calculation (upload/download bps).
//! - Active connection tracking, latency measurement, and error counting.

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct HttpTrafficEntry {
    pub id: String,
    pub timestamp: u64,
    pub method: String,
    pub url: String,
    pub status_code: u16,
    pub duration_ms: u64,
    pub bytes_sent: usize,
    pub bytes_received: usize,
    #[serde(default = "default_ip")]
    pub client_ip: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

fn default_ip() -> String {
    "127.0.0.1".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct NetworkMetrics {
    pub total_bytes_sent: u64,
    pub total_bytes_received: u64,
    pub active_connections: usize,
    pub request_count: u64,
    pub response_count: u64,
    pub error_count: u64,
    pub upload_bps: f32,
    pub download_bps: f32,
}

pub struct TrafficInspector {
    history: VecDeque<HttpTrafficEntry>,
    max_history: usize,
    total_bytes_sent: AtomicU64,
    total_bytes_received: AtomicU64,
    active_connections: AtomicUsize,
    request_count: AtomicU64,
    response_count: AtomicU64,
    error_count: AtomicU64,
}

impl Default for TrafficInspector {
    fn default() -> Self {
        Self::new(100)
    }
}

impl TrafficInspector {
    pub fn new(max_history: usize) -> Self {
        Self {
            history: VecDeque::with_capacity(max_history.max(1)),
            max_history: max_history.max(1),
            total_bytes_sent: AtomicU64::new(0),
            total_bytes_received: AtomicU64::new(0),
            active_connections: AtomicUsize::new(0),
            request_count: AtomicU64::new(0),
            response_count: AtomicU64::new(0),
            error_count: AtomicU64::new(0),
        }
    }

    /// Records an HTTP transaction entry.
    pub fn record(&mut self, mut entry: HttpTrafficEntry) {
        if entry.timestamp == 0 {
            entry.timestamp = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_millis() as u64;
        }

        self.total_bytes_sent
            .fetch_add(entry.bytes_sent as u64, Ordering::Relaxed);
        self.total_bytes_received
            .fetch_add(entry.bytes_received as u64, Ordering::Relaxed);
        self.request_count.fetch_add(1, Ordering::Relaxed);
        self.response_count.fetch_add(1, Ordering::Relaxed);

        if entry.status_code >= 400 || entry.error.is_some() {
            self.error_count.fetch_add(1, Ordering::Relaxed);
        }

        if self.history.len() >= self.max_history {
            self.history.pop_front();
        }
        self.history.push_back(entry);
    }

    /// Returns the most recent `limit` recorded entries (newest first).
    pub fn get_recent(&self, limit: usize) -> Vec<HttpTrafficEntry> {
        let n = limit.min(self.history.len());
        self.history.iter().rev().take(n).cloned().collect()
    }

    /// Computes aggregated throughput and error metrics.
    pub fn get_metrics(&self) -> NetworkMetrics {
        let sent = self.total_bytes_sent.load(Ordering::Relaxed);
        let recvd = self.total_bytes_received.load(Ordering::Relaxed);
        let reqs = self.request_count.load(Ordering::Relaxed);
        let resps = self.response_count.load(Ordering::Relaxed);
        let errs = self.error_count.load(Ordering::Relaxed);
        let conns = self.active_connections.load(Ordering::Relaxed);

        // Estimate bandwidth over recent history window
        let mut recent_sent = 0usize;
        let mut recent_recvd = 0usize;
        let mut time_window_ms = 1000u64;

        if let (Some(oldest), Some(newest)) = (self.history.front(), self.history.back()) {
            if newest.timestamp > oldest.timestamp {
                time_window_ms = (newest.timestamp - oldest.timestamp).max(100);
            }
            for e in &self.history {
                recent_sent += e.bytes_sent;
                recent_recvd += e.bytes_received;
            }
        }

        let seconds = (time_window_ms as f32) / 1000.0;
        let upload_bps = if seconds > 0.0 {
            (recent_sent as f32 * 8.0) / seconds
        } else {
            0.0
        };
        let download_bps = if seconds > 0.0 {
            (recent_recvd as f32 * 8.0) / seconds
        } else {
            0.0
        };

        NetworkMetrics {
            total_bytes_sent: sent,
            total_bytes_received: recvd,
            active_connections: conns,
            request_count: reqs,
            response_count: resps,
            error_count: errs,
            upload_bps: (upload_bps * 10.0).round() / 10.0,
            download_bps: (download_bps * 10.0).round() / 10.0,
        }
    }

    pub fn set_active_connections(&self, count: usize) {
        self.active_connections.store(count, Ordering::Relaxed);
    }

    pub fn clear(&mut self) {
        self.history.clear();
        self.total_bytes_sent.store(0, Ordering::Relaxed);
        self.total_bytes_received.store(0, Ordering::Relaxed);
        self.request_count.store(0, Ordering::Relaxed);
        self.response_count.store(0, Ordering::Relaxed);
        self.error_count.store(0, Ordering::Relaxed);
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_traffic_inspector_record_and_metrics() {
        let mut inspector = TrafficInspector::new(5);

        inspector.record(HttpTrafficEntry {
            id: "req-1".to_string(),
            timestamp: 1000,
            method: "POST".to_string(),
            url: "/api/chat".to_string(),
            status_code: 200,
            duration_ms: 45,
            bytes_sent: 512,
            bytes_received: 2048,
            client_ip: "127.0.0.1".to_string(),
            error: None,
        });

        inspector.record(HttpTrafficEntry {
            id: "req-2".to_string(),
            timestamp: 2000,
            method: "GET".to_string(),
            url: "/api/health".to_string(),
            status_code: 500,
            duration_ms: 12,
            bytes_sent: 64,
            bytes_received: 128,
            client_ip: "127.0.0.1".to_string(),
            error: Some("Internal Error".to_string()),
        });

        let metrics = inspector.get_metrics();
        assert_eq!(metrics.request_count, 2);
        assert_eq!(metrics.error_count, 1);
        assert_eq!(metrics.total_bytes_sent, 512 + 64);
        assert_eq!(metrics.total_bytes_received, 2048 + 128);

        let recent = inspector.get_recent(10);
        assert_eq!(recent.len(), 2);
        assert_eq!(recent[0].id, "req-2"); // Newest first
        assert_eq!(recent[1].id, "req-1");
    }

    #[test]
    fn test_traffic_inspector_eviction() {
        let mut inspector = TrafficInspector::new(3);

        for i in 1..=5 {
            inspector.record(HttpTrafficEntry {
                id: format!("req-{}", i),
                timestamp: i * 100,
                method: "GET".to_string(),
                url: format!("/item/{}", i),
                status_code: 200,
                duration_ms: 10,
                bytes_sent: 100,
                bytes_received: 200,
                client_ip: "127.0.0.1".to_string(),
                error: None,
            });
        }

        let recent = inspector.get_recent(10);
        assert_eq!(recent.len(), 3);
        assert_eq!(recent[0].id, "req-5");
        assert_eq!(recent[1].id, "req-4");
        assert_eq!(recent[2].id, "req-3");
    }
}
