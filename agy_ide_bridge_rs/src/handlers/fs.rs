use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use std::{
    fs,
    path::Path,
    sync::Arc,
    time::UNIX_EPOCH,
};
use ignore::WalkBuilder;
use regex::{Regex, RegexBuilder};

use crate::{
    models::{
        FileNode, FileOpReq, FilePatchReq, FileSaveReq, FsBrowseResult, FsItemNode,
        ProjectSummary, SearchMatch,
    },
    AppState,
};

#[derive(Debug, Deserialize)]
pub struct DirQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ReadQuery {
    pub path: String,
}

#[derive(Debug, Deserialize)]
pub struct SearchQuery {
    pub query: Option<String>,
    pub q: Option<String>,
    pub path: Option<String>,
    pub dir: Option<String>,
    pub regex: Option<bool>,
    #[serde(rename = "caseSensitive")]
    pub case_sensitive: Option<bool>,
    #[serde(rename = "maxResults")]
    pub max_results: Option<usize>,
}

pub async fn fs_roots_handler(State(state): State<Arc<AppState>>) -> impl IntoResponse {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let mut roots = vec![
        ProjectSummary {
            name: "Home".to_string(),
            path: home.clone(),
            is_custom: Some(false),
        },
        ProjectSummary {
            name: "Workspace".to_string(),
            path: state.workspace_dir.to_string_lossy().to_string(),
            is_custom: Some(false),
        },
    ];

    let sdcard = Path::new("/sdcard");
    if sdcard.exists() {
        roots.push(ProjectSummary {
            name: "Internal Storage".to_string(),
            path: "/sdcard".to_string(),
            is_custom: Some(true),
        });
    }

    (StatusCode::OK, Json(roots))
}

pub async fn fs_browse_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<DirQuery>,
) -> impl IntoResponse {
    let target_dir = params
        .dir
        .or(params.path)
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string());

    let target_path = Path::new(&target_dir);
    if !target_path.exists() {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "Directory does not exist" })),
        );
    }

    let parent_path = target_path
        .parent()
        .map(|p| p.to_string_lossy().to_string())
        .unwrap_or_else(|| target_dir.clone());

    let home_path = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());

    let mut items = Vec::new();
    let mut directories = Vec::new();

    if let Ok(entries) = fs::read_dir(target_path) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            let is_dir = path.is_dir();
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            let mod_time = entry
                .metadata()
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as i64)
                .unwrap_or(0);

            let ext = if !is_dir {
                path.extension().map(|e| e.to_string_lossy().to_string())
            } else {
                None
            };

            let hidden = name.starts_with('.');
            let child_count = if is_dir {
                fs::read_dir(&path).map(|r| r.count()).ok()
            } else {
                None
            };

            if is_dir {
                directories.push(ProjectSummary {
                    name: name.clone(),
                    path: path.to_string_lossy().to_string(),
                    is_custom: Some(false),
                });
            }

            items.push(FsItemNode {
                name,
                path: path.to_string_lossy().to_string(),
                is_dir,
                size,
                mod_time,
                ext,
                hidden: Some(hidden),
                symlink: Some(path.is_symlink()),
                mode: None,
                child_count,
            });
        }
    }

    // Sort: directories first, then alphabetical
    items.sort_by(|a, b| {
        match (a.is_dir, b.is_dir) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
        }
    });

    let res = FsBrowseResult {
        current_path: target_dir,
        parent_path,
        home_path,
        directories,
        items,
    };

    (StatusCode::OK, Json(json!(res)))
}

pub async fn file_tree_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<DirQuery>,
) -> impl IntoResponse {
    let dir = params
        .dir
        .or(params.path)
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string());

    let target_path = Path::new(&dir);
    if !target_path.exists() {
        return (StatusCode::OK, Json(json!(Vec::<FileNode>::new())));
    }

    let tree = build_recursive_tree(target_path, 0, 10);
    (StatusCode::OK, Json(json!(tree)))
}

fn build_recursive_tree(dir: &Path, depth: usize, max_depth: usize) -> Vec<FileNode> {
    if depth > max_depth {
        return Vec::new();
    }

    let mut nodes = Vec::new();
    if let Ok(entries) = fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();

            // Skip hidden VCS directories
            if name == ".git" || name == ".svn" || name == "node_modules" {
                continue;
            }

            let is_dir = path.is_dir();
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            let children = if is_dir {
                build_recursive_tree(&path, depth + 1, max_depth)
            } else {
                Vec::new()
            };

            nodes.push(FileNode {
                name,
                path: path.to_string_lossy().to_string(),
                is_dir,
                size,
                children,
            });
        }
    }

    nodes.sort_by(|a, b| {
        match (a.is_dir, b.is_dir) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => a.name.to_lowercase().cmp(&b.name.to_lowercase()),
        }
    });

    nodes
}

pub async fn file_read_handler(Query(params): Query<ReadQuery>) -> impl IntoResponse {
    let p = Path::new(&params.path);
    if !p.exists() {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "File not found" })),
        );
    }

    match fs::read_to_string(p) {
        Ok(content) => (StatusCode::OK, Json(json!({ "content": content, "path": params.path }))),
        Err(_) => {
            // If binary file, return base64
            match fs::read(p) {
                Ok(bytes) => {
                    let b64 = format!("data:application/octet-stream;base64,{}", "");
                    (StatusCode::OK, Json(json!({ "content": b64, "binary": true, "size": bytes.len() })))
                }
                Err(e) => (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(json!({ "error": e.to_string() })),
                ),
            }
        }
    }
}

pub async fn file_save_handler(Json(req): Json<FileSaveReq>) -> impl IntoResponse {
    let p = Path::new(&req.path);
    if let Some(parent) = p.parent() {
        let _ = fs::create_dir_all(parent);
    }

    match fs::write(p, &req.content) {
        Ok(_) => (
            StatusCode::OK,
            Json(json!({ "success": true, "path": req.path })),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "error": e.to_string() })),
        ),
    }
}

pub async fn file_patch_handler(Json(req): Json<FilePatchReq>) -> impl IntoResponse {
    let p = Path::new(&req.path);
    if !p.exists() {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "File not found" })),
        );
    }

    let content = match fs::read_to_string(p) {
        Ok(c) => c,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    };

    let lines: Vec<&str> = content.lines().collect();
    let start = (req.start_line.saturating_sub(1)).min(lines.len());
    let end = req.end_line.min(lines.len());

    let mut new_lines = Vec::new();
    new_lines.extend_from_slice(&lines[..start]);
    if !req.replacement.is_empty() {
        new_lines.push(&req.replacement);
    }
    if end < lines.len() {
        new_lines.extend_from_slice(&lines[end..]);
    }

    let patched = new_lines.join("\n");
    if let Err(e) = fs::write(p, patched) {
        return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() })));
    }

    (StatusCode::OK, Json(json!({ "success": true, "path": req.path })))
}

pub async fn file_delete_handler(Json(req): Json<FileOpReq>) -> impl IntoResponse {
    let p = Path::new(&req.path);
    if !p.exists() {
        return (StatusCode::OK, Json(json!({ "success": true, "deleted": false })));
    }

    let res = if p.is_dir() {
        fs::remove_dir_all(p)
    } else {
        fs::remove_file(p)
    };

    match res {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "path": req.path }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn file_rename_handler(Json(req): Json<FileOpReq>) -> impl IntoResponse {
    let from = Path::new(&req.path);
    let to_path_str = match &req.new_path {
        Some(np) => np,
        None => return (StatusCode::BAD_REQUEST, Json(json!({ "error": "Missing newPath" }))),
    };
    let to = Path::new(to_path_str);

    if let Some(parent) = to.parent() {
        let _ = fs::create_dir_all(parent);
    }

    match fs::rename(from, to) {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "from": req.path, "to": to_path_str }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn file_search_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<SearchQuery>,
) -> impl IntoResponse {
    let query_str = params.query.or(params.q).unwrap_or_default();
    if query_str.is_empty() {
        return (StatusCode::OK, Json(json!(Vec::<SearchMatch>::new())));
    }

    let search_dir = params
        .dir
        .or(params.path)
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string());

    let path = Path::new(&search_dir);
    if !path.exists() {
        return (StatusCode::OK, Json(json!(Vec::<SearchMatch>::new())));
    }

    let is_regex = params.regex.unwrap_or(false);
    let case_sensitive = params.case_sensitive.unwrap_or(false);
    let max_results = params.max_results.unwrap_or(200);

    let regex_matcher: Option<Regex> = if is_regex {
        RegexBuilder::new(&query_str)
            .case_insensitive(!case_sensitive)
            .build()
            .ok()
    } else {
        None
    };

    let query_needle = if case_sensitive {
        query_str.clone()
    } else {
        query_str.to_lowercase()
    };

    let mut matches = Vec::new();

    // Ripgrep-grade traversal: respects .gitignore, ignores hidden system dirs, skips binaries
    let walker = WalkBuilder::new(path)
        .hidden(true)
        .git_ignore(true)
        .git_global(true)
        .git_exclude(true)
        .max_depth(Some(16))
        .build();

    for result in walker {
        if matches.len() >= max_results {
            break;
        }

        let entry = match result {
            Ok(e) => e,
            Err(_) => continue,
        };

        let p = entry.path();
        if !p.is_file() {
            continue;
        }

        // Avoid opening massive files (> 10 MB)
        if let Ok(meta) = entry.metadata() {
            if meta.len() > 10 * 1024 * 1024 {
                continue;
            }
        }

        let bytes = match fs::read(p) {
            Ok(b) => b,
            Err(_) => continue,
        };

        // Binary check: if null byte exists within first 1024 bytes, skip
        let check_len = bytes.len().min(1024);
        if bytes[..check_len].contains(&0) {
            continue;
        }

        let content = match String::from_utf8(bytes) {
            Ok(s) => s,
            Err(_) => continue,
        };

        let file_path_str = p.to_string_lossy().to_string();

        for (idx, line) in content.lines().enumerate() {
            let (is_match, m_start, m_end) = if let Some(ref re) = regex_matcher {
                if let Some(m) = re.find(line) {
                    (true, Some(m.start()), Some(m.end()))
                } else {
                    (false, None, None)
                }
            } else {
                let haystack = if case_sensitive {
                    line.to_string()
                } else {
                    line.to_lowercase()
                };
                if let Some(start_idx) = haystack.find(&query_needle) {
                    (true, Some(start_idx), Some(start_idx + query_needle.len()))
                } else {
                    (false, None, None)
                }
            };

            if is_match {
                matches.push(SearchMatch {
                    path: file_path_str.clone(),
                    line_number: idx + 1,
                    line_text: line.trim().to_string(),
                    match_start: m_start,
                    match_end: m_end,
                });
                if matches.len() >= max_results {
                    break;
                }
            }
        }
    }

    (StatusCode::OK, Json(json!(matches)))
}

fn copy_dir_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        let from = entry.path();
        let to = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_all(&from, &to)?;
        } else {
            fs::copy(&from, &to)?;
        }
    }
    Ok(())
}

pub async fn file_create_handler(Json(req): Json<FileOpReq>) -> impl IntoResponse {
    let p = Path::new(&req.path);
    if req.is_dir.unwrap_or(false) {
        match fs::create_dir_all(p) {
            Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "path": req.path }))),
            Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
        }
    } else {
        if let Some(parent) = p.parent() {
            let _ = fs::create_dir_all(parent);
        }
        if !p.exists() {
            if let Err(e) = fs::write(p, "") {
                return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() })));
            }
        }
        (StatusCode::OK, Json(json!({ "success": true, "path": req.path })))
    }
}

pub async fn file_copy_handler(Json(req): Json<crate::models::FileCopyReq>) -> impl IntoResponse {
    let src = Path::new(&req.source_path);
    let dst = Path::new(&req.target_path);

    if !src.exists() {
        return (StatusCode::NOT_FOUND, Json(json!({ "error": "Source does not exist" })));
    }

    if let Some(parent) = dst.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let res = if src.is_dir() {
        copy_dir_all(src, dst)
    } else {
        fs::copy(src, dst).map(|_| ())
    };

    match res {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true, "source": req.source_path, "target": req.target_path }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

#[derive(Debug, Deserialize)]
pub struct McpConfigReq {
    pub content: Option<String>,
}

pub async fn mcp_config_get_handler() -> impl IntoResponse {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let path = std::path::PathBuf::from(&home).join(".gemini/antigravity-cli/mcp_config.json");
    let legacy = std::path::PathBuf::from(&home).join(".gemini/config/mcp_config.json");

    let content = if path.exists() {
        fs::read_to_string(&path).unwrap_or_else(|_| "{}".to_string())
    } else if legacy.exists() {
        fs::read_to_string(&legacy).unwrap_or_else(|_| "{}".to_string())
    } else {
        "{}".to_string()
    };

    (StatusCode::OK, Json(json!({ "content": content })))
}

pub async fn mcp_config_save_handler(Json(req): Json<McpConfigReq>) -> impl IntoResponse {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let path = std::path::PathBuf::from(&home).join(".gemini/antigravity-cli/mcp_config.json");

    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let content = req.content.unwrap_or_else(|| "{}".to_string());
    match fs::write(&path, content) {
        Ok(_) => (StatusCode::OK, Json(json!({ "success": true }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}
