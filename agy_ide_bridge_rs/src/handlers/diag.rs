use axum::{
    extract::{Query, State},
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};
use crate::AppState;

const HUB_LOG_TAIL_BYTES: u64 = 4 * 1024 * 1024; // 4 MB
const HUB_LOG_LINE_MAX_LEN: usize = 1500;
const HUB_LOG_DEFAULT_TAKE: usize = 30;

#[derive(Debug, Deserialize)]
pub struct HubDiagQuery {
    pub voice: Option<usize>,
}

#[derive(Debug, Serialize, Clone)]
pub struct HubStatus {
    pub running: bool,
    pub external: bool,
    pub status: String,
    pub last_error: String,
    pub last_exit_error: String,
    pub started_at_ms: i64,
    pub last_exit_at_ms: i64,
    pub crash_lines: Vec<String>,
    pub recent_output: Vec<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct HubVoiceLogFile {
    pub file: String,
    pub modified_ms: i64,
    pub lines: Vec<String>,
}

#[derive(Debug, Serialize)]
pub struct HubDiagResponse {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hub: Option<HubStatus>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub log_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub log_error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub voice_log: Option<Vec<HubVoiceLogFile>>,
}

/// GET /api/agy/hub/diag?voice=N
///
/// Returns hub process status and tail of voice call log lines from cli-*.log files
/// for in-depth Antigravity voice call diagnostics.
pub async fn agy_hub_diag_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<HubDiagQuery>,
) -> (StatusCode, Json<HubDiagResponse>) {
    let take = query.voice.unwrap_or(HUB_LOG_DEFAULT_TAKE).clamp(1, 200);

    // Check if hub is reachable on localhost:hub_port
    let hub_running = tokio::net::TcpStream::connect(("127.0.0.1", state.hub_port))
        .await
        .is_ok();

    let started_at_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64 - state.start_time.elapsed().as_millis() as i64)
        .unwrap_or(0);

    let hub_status = HubStatus {
        running: hub_running,
        external: true,
        status: if hub_running { "running".to_string() } else { "stopped".to_string() },
        last_error: String::new(),
        last_exit_error: String::new(),
        started_at_ms,
        last_exit_at_ms: 0,
        crash_lines: Vec::new(),
        recent_output: Vec::new(),
    };

    // Locate app data directory and logs
    let app_data_dir = find_app_data_dir(&state.workspace_dir);
    let log_dir = app_data_dir.join("log");

    let mut log_error = None;
    let mut voice_log = None;

    if log_dir.exists() {
        match read_hub_voice_log_lines(&log_dir, 2, take) {
            Ok(files) => voice_log = Some(files),
            Err(e) => log_error = Some(e.to_string()),
        }
    } else {
        voice_log = Some(Vec::new());
    }

    let resp = HubDiagResponse {
        success: true,
        hub: Some(hub_status),
        log_dir: Some(log_dir.to_string_lossy().to_string()),
        log_error,
        voice_log,
    };

    (StatusCode::OK, Json(resp))
}

fn find_app_data_dir(workspace: &Path) -> PathBuf {
    if let Ok(dir) = std::env::var("AGY_APP_DATA_DIR") {
        let p = PathBuf::from(dir);
        if p.exists() {
            return p;
        }
    }

    if let Ok(home) = std::env::var("HOME") {
        let p = PathBuf::from(home).join(".gemini/antigravity-cli");
        if p.exists() {
            return p;
        }
    }

    let candidates = [
        "/data/user/0/com.antigem/files",
        "/data/user/0/com.example.gemini/files",
        "/data/data/com.termux/files/home/.gemini/antigravity-cli",
    ];

    for c in &candidates {
        let p = PathBuf::from(c);
        if p.exists() {
            return p;
        }
    }

    workspace.to_path_buf()
}

fn read_hub_voice_log_lines(
    dir: &Path,
    max_files: usize,
    take: usize,
) -> Result<Vec<HubVoiceLogFile>, std::io::Error> {
    let mut entries: Vec<(PathBuf, String, i64)> = Vec::new();

    if let Ok(read_dir) = std::fs::read_dir(dir) {
        for entry in read_dir.flatten() {
            let path = entry.path();
            if path.is_file() {
                let file_name = path
                    .file_name()
                    .and_then(|n| n.to_str())
                    .map(|s| s.to_string());

                if let Some(name) = file_name {
                    if name.starts_with("cli-") && name.ends_with(".log") {
                        if let Ok(meta) = entry.metadata() {
                            let mod_ms = meta
                                .modified()
                                .ok()
                                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                                .map(|d| d.as_millis() as i64)
                                .unwrap_or(0);
                            entries.push((path, name, mod_ms));
                        }
                    }
                }
            }
        }
    }

    // Sort descending by modification time
    entries.sort_by(|a, b| b.2.cmp(&a.2));
    entries.truncate(max_files);

    let mut out = Vec::new();
    for (path, file_name, mod_ms) in entries {
        let lines = match voice_lines_of(&path, take) {
            Ok(l) => l,
            Err(e) => vec![format!("(could not read: {})", e)],
        };
        out.push(HubVoiceLogFile {
            file: file_name,
            modified_ms: mod_ms,
            lines,
        });
    }

    Ok(out)
}

fn voice_lines_of(path: &Path, take: usize) -> Result<Vec<String>, std::io::Error> {
    let mut f = File::open(path)?;
    let meta = f.metadata()?;
    let len = meta.len();

    let start = if len > HUB_LOG_TAIL_BYTES {
        len - HUB_LOG_TAIL_BYTES
    } else {
        0
    };

    if start > 0 {
        f.seek(SeekFrom::Start(start))?;
    }

    let mut buf = String::new();
    f.read_to_string(&mut buf)?;

    let mut lines = Vec::new();
    let split_lines: Vec<&str> = buf.lines().collect();

    // Skip the first potentially sliced line if we seeked
    let iter = if start > 0 && split_lines.len() > 1 {
        &split_lines[1..]
    } else {
        &split_lines[..]
    };

    for raw in iter {
        let line = raw.trim_end_matches('\r');
        if line.contains("[voice") || line.contains("/voice/") {
            let truncated = if line.chars().count() > HUB_LOG_LINE_MAX_LEN {
                let mut s: String = line.chars().take(HUB_LOG_LINE_MAX_LEN).collect();
                s.push('…');
                s
            } else {
                line.to_string()
            };
            lines.push(truncated);
        }
    }

    if lines.len() > take {
        lines = lines[lines.len() - take..].to_vec();
    }

    Ok(lines)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_voice_lines_of_filtering() {
        let dir = std::env::temp_dir().join(format!("test_voice_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let log_path = dir.join("cli-test.log");
        let mut f = File::create(&log_path).unwrap();
        writeln!(f, "2026-11-10T01:00:00Z [info] server starting").unwrap();
        writeln!(f, "2026-11-10T01:00:01Z [voice-direct] /voice/ws connected session=123").unwrap();
        writeln!(f, "2026-11-10T01:00:02Z [debug] other debug logs").unwrap();
        writeln!(f, "2026-11-10T01:00:03Z [voice-rap] voice frame received seq=1").unwrap();
        writeln!(f, "2026-11-10T01:00:04Z [voice-direct] /voice/ws closed code=1000").unwrap();

        let lines = voice_lines_of(&log_path, 10).unwrap();
        assert_eq!(lines.len(), 3);
        assert!(lines[0].contains("connected"));
        assert!(lines[1].contains("frame received"));
        assert!(lines[2].contains("closed"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn test_read_hub_voice_log_lines() {
        let dir = std::env::temp_dir().join(format!("test_voice_logs_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let log1 = dir.join("cli-1.log");
        let mut f1 = File::create(&log1).unwrap();
        writeln!(f1, "2026-11-10 [voice] sample 1").unwrap();

        let log2 = dir.join("cli-2.log");
        let mut f2 = File::create(&log2).unwrap();
        writeln!(f2, "2026-11-10 [voice] sample 2").unwrap();

        let res = read_hub_voice_log_lines(&dir, 2, 5).unwrap();
        assert_eq!(res.len(), 2);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
