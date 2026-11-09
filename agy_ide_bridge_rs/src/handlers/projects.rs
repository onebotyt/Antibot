use axum::{
    extract::State,
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde_json::json;
use std::{
    collections::HashSet,
    fs,
    path::{Path, PathBuf},
    sync::Arc,
};

use crate::{
    models::{AddProjectReq, CreateProjectReq, ProjectSummary},
    AppState,
};

fn saved_projects_file() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    PathBuf::from(&home).join(".gemini/antigravity-cli/saved_projects.json")
}

fn load_saved_projects() -> Vec<ProjectSummary> {
    let p = saved_projects_file();
    if let Ok(content) = fs::read_to_string(p) {
        if let Ok(list) = serde_json::from_str::<Vec<ProjectSummary>>(&content) {
            return list;
        }
    }
    Vec::new()
}

fn save_saved_projects(projects: &[ProjectSummary]) -> std::io::Result<()> {
    let p = saved_projects_file();
    if let Some(parent) = p.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let data = serde_json::to_string_pretty(projects)?;
    fs::write(p, data)
}

pub async fn projects_handler(State(_state): State<Arc<AppState>>) -> impl IntoResponse {
    let mut projects = Vec::new();
    let mut seen = HashSet::new();

    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let default_dir = Path::new(&home).join(".gemini/antigravity-cli/scratch");

    if default_dir.exists() {
        if let Ok(entries) = fs::read_dir(&default_dir) {
            for entry in entries.flatten() {
                let p = entry.path();
                if p.is_dir() {
                    let path_str = p.to_string_lossy().to_string();
                    seen.insert(path_str.clone());
                    projects.push(ProjectSummary {
                        name: entry.file_name().to_string_lossy().to_string(),
                        path: path_str,
                        is_custom: Some(false),
                    });
                }
            }
        }
    }

    // Add saved custom projects
    for saved in load_saved_projects() {
        if !seen.contains(&saved.path) {
            seen.insert(saved.path.clone());
            projects.push(saved);
        }
    }

    (StatusCode::OK, Json(json!({ "projects": projects })))
}

pub async fn projects_add_handler(Json(req): Json<AddProjectReq>) -> impl IntoResponse {
    let path = req.path.trim().to_string();
    if path.is_empty() {
        return (StatusCode::BAD_REQUEST, Json(json!({ "error": "path is required" })));
    }

    let mut saved = load_saved_projects();
    if !saved.iter().any(|p| p.path == path) {
        let name = if req.name.is_empty() {
            Path::new(&path).file_name().and_then(|n| n.to_str()).unwrap_or("Project").to_string()
        } else {
            req.name
        };
        saved.push(ProjectSummary {
            name,
            path: path.clone(),
            is_custom: Some(true),
        });
        let _ = save_saved_projects(&saved);
    }

    (StatusCode::OK, Json(json!({ "success": true, "path": path })))
}

pub async fn projects_remove_handler(Json(req): Json<AddProjectReq>) -> impl IntoResponse {
    let path = req.path.trim().to_string();
    let mut saved = load_saved_projects();
    let len_before = saved.len();
    saved.retain(|p| p.path != path);

    if saved.len() < len_before {
        let _ = save_saved_projects(&saved);
    }

    (StatusCode::OK, Json(json!({ "success": true, "path": path })))
}

pub async fn create_project_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<CreateProjectReq>,
) -> impl IntoResponse {
    let target_dir = match req.path {
        Some(p) if !p.is_empty() => PathBuf::from(p),
        _ => state.workspace_dir.join(&req.name),
    };

    if let Err(e) = fs::create_dir_all(&target_dir) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({ "error": e.to_string() })),
        );
    }

    // Initialize simple template if requested
    if let Some(tpl) = req.template {
        match tpl.as_str() {
            "python" => {
                let _ = fs::write(target_dir.join("main.py"), "print('Hello from AntiBot!')\n");
            }
            "node" => {
                let pkg = json!({
                    "name": req.name,
                    "version": "1.0.0",
                    "main": "index.js",
                    "scripts": { "start": "node index.js" }
                });
                let _ = fs::write(target_dir.join("package.json"), pkg.to_string());
                let _ = fs::write(target_dir.join("index.js"), "console.log('Hello from AntiBot!');\n");
            }
            "rust" => {
                let cargo = format!(
                    "[package]\nname = \"{}\"\nversion = \"0.1.0\"\nedition = \"2021\"\n\n[dependencies]\n",
                    req.name
                );
                let _ = fs::create_dir_all(target_dir.join("src"));
                let _ = fs::write(target_dir.join("Cargo.toml"), cargo);
                let _ = fs::write(target_dir.join("src/main.rs"), "fn main() {\n    println!(\"Hello from AntiBot!\");\n}\n");
            }
            _ => {}
        }
    }

    (
        StatusCode::OK,
        Json(json!({
            "success": true,
            "name": req.name,
            "path": target_dir.to_string_lossy(),
        })),
    )
}
