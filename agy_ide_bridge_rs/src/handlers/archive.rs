use axum::{
    body::Bytes,
    extract::{Path as AxumPath, State},
    http::{header, HeaderMap, StatusCode},
    response::IntoResponse,
    Json,
};
use chrono::Utc;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs::{self, File},
    io::{BufRead, BufReader, Cursor, Read, Write},
    path::{Path, PathBuf},
    sync::Arc,
};
use walkdir::WalkDir;
use zip::{write::SimpleFileOptions, CompressionMethod, ZipArchive, ZipWriter};

use crate::{models::ConversationSummary, AppState};

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
pub struct BackupReq {
    pub output: Option<String>,
    pub password: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct RestoreReq {
    pub file: String,
    #[serde(rename = "targetDir")]
    pub target_dir: Option<String>,
}

#[allow(dead_code)]
#[derive(Debug, Deserialize)]
pub struct BackupProjectsReq {
    pub output: Option<String>,
    pub paths: Option<Vec<String>>,
    pub mode: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct BackupResult {
    pub success: bool,
    pub path: String,
    #[serde(rename = "totalFiles")]
    pub total_files: usize,
    #[serde(rename = "totalBytes")]
    pub total_bytes: u64,
}

pub fn home_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    PathBuf::from(home)
}

pub fn gemini_dir() -> PathBuf {
    home_dir().join(".gemini")
}

pub fn brain_dir() -> PathBuf {
    gemini_dir().join("antigravity-cli/brain")
}

pub fn default_backup_dir() -> PathBuf {
    let sdcard = Path::new("/sdcard/Download/Antigem/backups");
    if sdcard.exists() || fs::create_dir_all(sdcard).is_ok() {
        sdcard.to_path_buf()
    } else {
        home_dir().join("backups")
    }
}

fn sanitize_title(title: &str) -> String {
    let re = Regex::new(r"[^a-zA-Z0-9._-]").unwrap();
    let sanitized = re.replace_all(title, "_").to_string();
    let trimmed = sanitized.trim_matches('_');
    if trimmed.is_empty() {
        "conversation".to_string()
    } else if trimmed.len() > 40 {
        trimmed[..40].to_string()
    } else {
        trimmed.to_string()
    }
}

fn extract_prompt_title(p: &Path, conv_id: &str) -> String {
    let title_file = p.join("custom_title.txt");
    if let Ok(t) = fs::read_to_string(&title_file) {
        let trimmed = t.trim().to_string();
        if !trimmed.is_empty() {
            return trimmed;
        }
    }

    let transcript_path = p.join(".system_generated/logs/transcript.jsonl");
    if let Ok(file) = File::open(&transcript_path) {
        let reader = BufReader::new(file);
        let user_req_re = Regex::new(r"(?s)<USER_REQUEST>(.*?)</USER_REQUEST>").unwrap();
        let all_tags_re = Regex::new(r"<[^>]+>").unwrap();

        for line in reader.lines().flatten() {
            if let Ok(step) = serde_json::from_str::<Value>(&line) {
                if step["type"] == "USER_INPUT" {
                    if let Some(content) = step["content"].as_str() {
                        let prompt = if let Some(caps) = user_req_re.captures(content) {
                            caps.get(1).map(|m| m.as_str().trim()).unwrap_or("")
                        } else {
                            ""
                        };
                        let clean = if prompt.is_empty() {
                            all_tags_re.replace_all(content, "").trim().to_string()
                        } else {
                            prompt.to_string()
                        };

                        if !clean.is_empty() {
                            return if clean.len() > 36 {
                                format!("{}...", &clean[..36])
                            } else {
                                clean
                            };
                        }
                    }
                }
            }
        }
    }

    conv_id.to_string()
}

pub fn run_backup_chats(output: Option<String>) -> Result<BackupResult, String> {
    let source = gemini_dir();
    if !source.exists() {
        return Err("Source ~/.gemini directory does not exist".to_string());
    }

    let timestamp = Utc::now().format("%Y-%m-%d-%H-%M-%S").to_string();
    let default_name = format!("agy_chats_backup_{timestamp}.zip");
    let target_path = output
        .map(PathBuf::from)
        .unwrap_or_else(|| default_backup_dir().join(default_name));

    if let Some(parent) = target_path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let out_file = File::create(&target_path).map_err(|e| format!("Cannot create file: {e}"))?;
    let mut zip = ZipWriter::new(out_file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let mut count = 0;
    let mut total_bytes = 0;

    for entry in WalkDir::new(&source).into_iter().flatten() {
        let p = entry.path();
        if p.is_file() {
            let rel = match p.strip_prefix(&home_dir()) {
                Ok(r) => r.to_string_lossy().replace('\\', "/"),
                Err(_) => continue,
            };

            if rel.ends_with(".sock")
                || rel.contains("/cache/")
                || rel.contains("/.cache/")
                || rel.contains("/log/")
                || rel.ends_with(".log")
                || rel.contains("/crashes/")
                || rel.contains("/implicit/")
                || rel.starts_with(".gemini/bin/")
            {
                continue;
            }

            if zip.start_file(&rel, options).is_ok() {
                if let Ok(data) = fs::read(p) {
                    total_bytes += data.len() as u64;
                    let _ = zip.write_all(&data);
                    count += 1;
                }
            }
        }
    }

    zip.finish().map_err(|e| format!("Zip finalize error: {e}"))?;

    Ok(BackupResult {
        success: true,
        path: target_path.to_string_lossy().to_string(),
        total_files: count,
        total_bytes,
    })
}

pub fn run_restore_chats(file_path: &str, target_dir: Option<String>) -> Result<usize, String> {
    let p = Path::new(file_path);
    if !p.exists() {
        return Err(format!("Backup file not found: {file_path}"));
    }

    let file = File::open(p).map_err(|e| e.to_string())?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("Invalid zip: {e}"))?;

    let target_root = target_dir.map(PathBuf::from).unwrap_or_else(home_dir);
    let mut count = 0;

    for i in 0..archive.len() {
        if let Ok(mut f) = archive.by_index(i) {
            let name = f.name().to_string();
            let dest = target_root.join(&name);

            if f.is_dir() {
                let _ = fs::create_dir_all(&dest);
            } else {
                if let Some(p) = dest.parent() {
                    let _ = fs::create_dir_all(p);
                }
                if let Ok(mut out_f) = File::create(&dest) {
                    let _ = std::io::copy(&mut f, &mut out_f);
                    count += 1;
                }
            }
        }
    }

    Ok(count)
}

pub fn run_backup_projects(
    workspace_dir: &Path,
    output: Option<String>,
    paths: Option<Vec<String>>,
) -> Result<BackupResult, String> {
    let timestamp = Utc::now().format("%Y-%m-%d-%H-%M-%S").to_string();
    let default_name = format!("projects_backup_{timestamp}.zip");
    let target_path = output
        .map(PathBuf::from)
        .unwrap_or_else(|| default_backup_dir().join(default_name));

    if let Some(parent) = target_path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let out_file = File::create(&target_path).map_err(|e| format!("Cannot create file: {e}"))?;
    let mut zip = ZipWriter::new(out_file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let project_paths: Vec<PathBuf> = if let Some(p_list) = paths {
        p_list.into_iter().map(PathBuf::from).collect()
    } else {
        let mut list = Vec::new();
        if let Ok(entries) = fs::read_dir(workspace_dir) {
            for entry in entries.flatten() {
                if entry.path().is_dir() {
                    list.push(entry.path());
                }
            }
        }
        list
    };

    let mut count = 0;
    let mut total_bytes = 0;

    for proj in &project_paths {
        let proj_name = proj.file_name().and_then(|n| n.to_str()).unwrap_or("project");
        for entry in WalkDir::new(proj).into_iter().flatten() {
            let p = entry.path();
            if p.is_file() {
                if let Ok(rel) = p.strip_prefix(proj) {
                    let zip_path = format!("projects/{}/{}", proj_name, rel.to_string_lossy().replace('\\', "/"));
                    if zip.start_file(&zip_path, options).is_ok() {
                        if let Ok(data) = fs::read(p) {
                            total_bytes += data.len() as u64;
                            let _ = zip.write_all(&data);
                            count += 1;
                        }
                    }
                }
            }
        }
    }

    zip.finish().map_err(|e| format!("Zip error: {e}"))?;

    Ok(BackupResult {
        success: true,
        path: target_path.to_string_lossy().to_string(),
        total_files: count,
        total_bytes,
    })
}

pub fn run_restore_projects(file_path: &str, target_workspace: &Path) -> Result<usize, String> {
    let p = Path::new(file_path);
    if !p.exists() {
        return Err(format!("Archive not found: {file_path}"));
    }

    let file = File::open(p).map_err(|e| e.to_string())?;
    let mut archive = ZipArchive::new(file).map_err(|e| format!("Invalid zip: {e}"))?;

    let mut count = 0;
    for i in 0..archive.len() {
        if let Ok(mut f) = archive.by_index(i) {
            let name = f.name().to_string();
            let rel = name.trim_start_matches("projects/").trim_start_matches('/');
            if !rel.is_empty() {
                let dest = target_workspace.join(rel);
                if f.is_dir() {
                    let _ = fs::create_dir_all(&dest);
                } else {
                    if let Some(p) = dest.parent() {
                        let _ = fs::create_dir_all(p);
                    }
                    if let Ok(mut out_f) = File::create(&dest) {
                        let _ = std::io::copy(&mut f, &mut out_f);
                        count += 1;
                    }
                }
            }
        }
    }

    Ok(count)
}

pub async fn export_conversation_archive_handler(
    AxumPath(id): AxumPath<String>,
) -> impl IntoResponse {
    let conv_dir = brain_dir().join(&id);
    if !conv_dir.exists() {
        return (
            StatusCode::NOT_FOUND,
            HeaderMap::new(),
            Bytes::from(json!({ "error": "Conversation not found" }).to_string()),
        );
    }

    let title = extract_prompt_title(&conv_dir, &id);
    let sanitized_title = sanitize_title(&title);
    let timestamp = Utc::now().format("%Y%m%d_%H%M%S").to_string();
    let filename = format!("{}_{}.antigem", sanitized_title, timestamp);

    let manifest = json!({
        "version": 1,
        "format": "antigem_chat_archive",
        "conversation_id": id,
        "title": title,
        "exported_at": Utc::now().to_rfc3339(),
    });

    let mut buf = Cursor::new(Vec::new());
    let mut zip = ZipWriter::new(&mut buf);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let _ = zip.start_file("manifest.json", options);
    let _ = zip.write_all(manifest.to_string().as_bytes());

    for entry in WalkDir::new(&conv_dir).into_iter().flatten() {
        let p = entry.path();
        if p.is_file() {
            if let Ok(rel) = p.strip_prefix(&brain_dir()) {
                let zip_path = format!("brain/{}", rel.to_string_lossy().replace('\\', "/"));
                if zip.start_file(zip_path, options).is_ok() {
                    if let Ok(data) = fs::read(p) {
                        let _ = zip.write_all(&data);
                    }
                }
            }
        }
    }

    let _ = zip.finish();
    let bytes = buf.into_inner();

    let mut headers = HeaderMap::new();
    headers.insert(header::CONTENT_TYPE, "application/octet-stream".parse().unwrap());
    headers.insert(
        header::CONTENT_DISPOSITION,
        format!("attachment; filename=\"{}\"", filename).parse().unwrap(),
    );
    headers.insert("X-Conversation-Id", id.parse().unwrap());
    headers.insert("X-Conversation-Title", title.parse().unwrap());

    (StatusCode::OK, headers, Bytes::from(bytes))
}

pub async fn restore_conversation_archive_handler(body: Bytes) -> impl IntoResponse {
    let reader = Cursor::new(body);
    let mut archive = match ZipArchive::new(reader) {
        Ok(a) => a,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(json!({ "error": format!("Invalid zip archive: {e}") })),
            );
        }
    };

    let mut manifest_val: Option<Value> = None;
    if let Ok(mut manifest_file) = archive.by_name("manifest.json") {
        let mut s = String::new();
        if manifest_file.read_to_string(&mut s).is_ok() {
            manifest_val = serde_json::from_str(&s).ok();
        }
    }

    let conv_id = manifest_val
        .as_ref()
        .and_then(|m| m["conversation_id"].as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| uuid_like_id());

    let title = manifest_val
        .as_ref()
        .and_then(|m| m["title"].as_str())
        .unwrap_or("Restored Chat")
        .to_string();

    let target_brain_dir = brain_dir().join(&conv_id);
    let _ = fs::create_dir_all(&target_brain_dir);

    let mut restored_files = 0;
    for i in 0..archive.len() {
        if let Ok(mut file) = archive.by_index(i) {
            let name = file.name().to_string();
            if name.starts_with("brain/") {
                let rel = name.trim_start_matches("brain/").trim_start_matches(&conv_id).trim_start_matches('/');
                if !rel.is_empty() {
                    let out_path = target_brain_dir.join(rel);
                    if file.is_dir() {
                        let _ = fs::create_dir_all(&out_path);
                    } else {
                        if let Some(p) = out_path.parent() {
                            let _ = fs::create_dir_all(p);
                        }
                        if let Ok(mut out_f) = File::create(&out_path) {
                            let _ = std::io::copy(&mut file, &mut out_f);
                            restored_files += 1;
                        }
                    }
                }
            }
        }
    }

    let _ = fs::write(target_brain_dir.join("custom_title.txt"), &title);

    let summary = ConversationSummary {
        id: conv_id.clone(),
        title,
        created_at: Utc::now().to_rfc3339(),
        steps_count: restored_files.max(1),
        is_running: false,
        last_activity: Some(Utc::now().to_rfc3339()),
    };

    (
        StatusCode::OK,
        Json(json!({
            "success": true,
            "conversation": summary,
            "restoredFiles": restored_files,
        })),
    )
}

fn uuid_like_id() -> String {
    use std::time::SystemTime;
    let now = SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    format!("chat_{now:x}")
}

pub async fn backup_chats_handler(Json(req): Json<BackupReq>) -> impl IntoResponse {
    match run_backup_chats(req.output) {
        Ok(res) => (StatusCode::OK, Json(json!(res))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e }))),
    }
}

pub async fn restore_chats_handler(Json(req): Json<RestoreReq>) -> impl IntoResponse {
    match run_restore_chats(&req.file, req.target_dir) {
        Ok(count) => (
            StatusCode::OK,
            Json(json!({ "success": true, "restoredFiles": count })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e }))),
    }
}

pub async fn backup_projects_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<BackupProjectsReq>,
) -> impl IntoResponse {
    match run_backup_projects(&state.workspace_dir, req.output, req.paths) {
        Ok(res) => (StatusCode::OK, Json(json!(res))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e }))),
    }
}

pub async fn restore_projects_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RestoreReq>,
) -> impl IntoResponse {
    let target = req
        .target_dir
        .map(PathBuf::from)
        .unwrap_or_else(|| state.workspace_dir.clone());

    match run_restore_projects(&req.file, &target) {
        Ok(count) => (
            StatusCode::OK,
            Json(json!({
                "success": true,
                "restoredFiles": count,
                "target": target.to_string_lossy(),
            })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e }))),
    }
}
