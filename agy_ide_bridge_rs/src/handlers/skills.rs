use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    fs,
    path::{Path, PathBuf},
    sync::Arc,
};

use crate::AppState;

#[derive(Debug, Deserialize)]
pub struct SkillsSearchQuery {
    pub q: Option<String>,
    pub category: Option<String>,
    pub risk: Option<String>,
    pub limit: Option<usize>,
}

#[derive(Debug, Deserialize)]
pub struct SkillsGetQuery {
    pub id: String,
}

#[derive(Debug, Deserialize)]
pub struct SkillsInstalledQuery {
    pub target: Option<String>,
    pub project_path: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct SkillInstallReq {
    pub id: String,
    pub target: Option<String>,
    pub project_path: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct SkillMeta {
    pub id: String,
    pub name: String,
    pub category: String,
    pub description: String,
    pub risk: String,
    #[serde(default)]
    pub tags: Vec<String>,
}

fn get_skills_base_dir() -> Option<PathBuf> {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let candidates = vec![
        PathBuf::from(&home).join(".agentic-awesome-skills"),
        PathBuf::from("/data/user/0/com.termux/files/home/.agentic-awesome-skills"),
        PathBuf::from("/data/data/com.termux/files/home/.agentic-awesome-skills"),
    ];
    for c in candidates {
        if c.join("skills").is_dir() {
            return Some(c);
        }
    }
    None
}

pub async fn skills_search_handler(
    State(_state): State<Arc<AppState>>,
    Query(query): Query<SkillsSearchQuery>,
) -> impl IntoResponse {
    let base_dir = match get_skills_base_dir() {
        Some(d) => d,
        None => return Json(json!({"count": 0, "skills": []})),
    };

    let index_file = base_dir.join("skills_index.json");
    let content = match fs::read_to_string(index_file) {
        Ok(c) => c,
        Err(_) => return Json(json!({"count": 0, "skills": []})),
    };

    let all_skills: Vec<SkillMeta> = match serde_json::from_str(&content) {
        Ok(s) => s,
        Err(_) => return Json(json!({"count": 0, "skills": []})),
    };

    let q = query.q.unwrap_or_default().trim().to_lowercase();
    let cat = query.category.unwrap_or_default().trim().to_lowercase();
    let risk = query.risk.unwrap_or_default().trim().to_lowercase();
    let limit = query.limit.unwrap_or(30);

    let q_tokens: Vec<&str> = q.split_whitespace().collect();
    let mut matched = Vec::new();

    for s in all_skills {
        if !cat.is_empty() && cat != "all" && s.category.to_lowercase() != cat {
            continue;
        }
        if !risk.is_empty() && risk != "all" && s.risk.to_lowercase() != risk {
            continue;
        }

        if !q_tokens.is_empty() {
            let target_text = format!(
                "{} {} {} {} {}",
                s.id,
                s.name,
                s.description,
                s.category,
                s.tags.join(" ")
            )
            .to_lowercase();

            let mut matches = true;
            for tok in &q_tokens {
                if !target_text.contains(tok) {
                    matches = false;
                    break;
                }
            }
            if !matches {
                continue;
            }
        }

        matched.push(s);
        if matched.len() >= limit {
            break;
        }
    }

    Json(json!({
        "count": matched.len(),
        "skills": matched
    }))
}

pub async fn skills_get_handler(
    State(_state): State<Arc<AppState>>,
    Query(query): Query<SkillsGetQuery>,
) -> impl IntoResponse {
    let base_dir = match get_skills_base_dir() {
        Some(d) => d,
        None => return (StatusCode::NOT_FOUND, Json(json!({"error": "skills base directory not found"}))),
    };

    let skill_file = base_dir.join("skills").join(&query.id).join("SKILL.md");
    match fs::read_to_string(skill_file) {
        Ok(content) => (StatusCode::OK, Json(json!({"id": query.id, "content": content}))),
        Err(_) => (StatusCode::NOT_FOUND, Json(json!({"error": "skill not found"}))),
    }
}

pub async fn skills_bundles_handler(State(_state): State<Arc<AppState>>) -> impl IntoResponse {
    let base_dir = match get_skills_base_dir() {
        Some(d) => d,
        None => return Json(json!({"count": 0, "bundles": []})),
    };

    let plugins_dir = base_dir.join("plugins");
    let mut bundles = Vec::new();

    if let Ok(entries) = fs::read_dir(plugins_dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() {
                let name = entry.file_name().to_string_lossy().to_string();
                if name.starts_with("agentic-bundle-") {
                    let raw_name = name.trim_start_matches("agentic-bundle-").to_string();
                    let display_name = raw_name.replace('-', " ");
                    let mut skills = Vec::new();
                    let skills_sub = p.join("skills");
                    if let Ok(sub_entries) = fs::read_dir(skills_sub) {
                        for se in sub_entries.flatten() {
                            if se.path().is_dir() {
                                skills.push(se.file_name().to_string_lossy().to_string());
                            }
                        }
                    }
                    bundles.push(json!({
                        "name": raw_name,
                        "displayName": display_name,
                        "skillCount": skills.len(),
                        "skills": skills
                    }));
                }
            }
        }
    }

    Json(json!({
        "count": bundles.len(),
        "bundles": bundles
    }))
}

pub async fn skills_install_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SkillInstallReq>,
) -> impl IntoResponse {
    let base_dir = match get_skills_base_dir() {
        Some(d) => d,
        None => return (StatusCode::BAD_REQUEST, Json(json!({"error": "skills base directory not found"}))),
    };

    let src_skill_dir = base_dir.join("skills").join(&req.id);
    if !src_skill_dir.is_dir() {
        return (StatusCode::BAD_REQUEST, Json(json!({"error": "skill not found in catalog"})));
    }

    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let target = req.target.unwrap_or_else(|| "project".to_string()).to_lowercase();

    let mut target_dirs: Vec<PathBuf> = Vec::new();

    match target.as_str() {
        "gemini" => target_dirs.push(PathBuf::from(&home).join(".gemini/config/skills")),
        "codex" => target_dirs.push(PathBuf::from(&home).join(".codex/skills")),
        "claude" => target_dirs.push(PathBuf::from(&home).join(".claude/skills")),
        "opencode" => target_dirs.push(PathBuf::from(&home).join(".opencode/skills")),
        "local" | "antibot" => target_dirs.push(PathBuf::from(&home).join(".antibot/skills")),
        "universal" => {
            target_dirs.push(PathBuf::from(&home).join(".antibot/skills"));
            target_dirs.push(PathBuf::from(&home).join(".gemini/config/skills"));
            target_dirs.push(PathBuf::from(&home).join(".claude/skills"));
            target_dirs.push(PathBuf::from(&home).join(".codex/skills"));
            target_dirs.push(PathBuf::from(&home).join(".opencode/skills"));
            let proj_path = req
                .project_path
                .as_deref()
                .filter(|p| !p.trim().is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| state.workspace_dir.clone());
            target_dirs.push(proj_path.join(".agents/skills"));
        }
        _ => {
            let proj_path = req
                .project_path
                .as_deref()
                .filter(|p| !p.trim().is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| state.workspace_dir.clone());
            target_dirs.push(proj_path.join(".agents/skills"));
        }
    };

    let mut installed_paths: Vec<String> = Vec::new();
    for tdir in &target_dirs {
        let dest = tdir.join(&req.id);
        let _ = fs::create_dir_all(tdir);
        if copy_dir_all(&src_skill_dir, &dest).is_ok() {
            installed_paths.push(dest.to_string_lossy().to_string());
        }
    }

    if !installed_paths.is_empty() {
        (
            StatusCode::OK,
            Json(json!({
                "success": true,
                "installedPath": installed_paths[0],
                "installedPaths": installed_paths,
                "message": format!("Skill '{}' installed successfully", req.id)
            })),
        )
    } else {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(json!({
                "success": false,
                "error": "Failed to copy skill files to any destination"
            })),
        )
    }
}

fn copy_dir_all(src: &Path, dst: &Path) -> std::io::Result<()> {
    fs::create_dir_all(dst)?;
    for entry in fs::read_dir(src)? {
        let entry = entry?;
        let ty = entry.file_type()?;
        if ty.is_dir() {
            copy_dir_all(&entry.path(), &dst.join(entry.file_name()))?;
        } else {
            fs::copy(entry.path(), dst.join(entry.file_name()))?;
        }
    }
    Ok(())
}

pub async fn skills_installed_handler(
    State(state): State<Arc<AppState>>,
    Query(query): Query<SkillsInstalledQuery>,
) -> impl IntoResponse {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let target = query.target.unwrap_or_else(|| "project".to_string()).to_lowercase();

    let target_dir = match target.as_str() {
        "gemini" => PathBuf::from(&home).join(".gemini/config/skills"),
        "codex" => PathBuf::from(&home).join(".codex/skills"),
        "claude" => PathBuf::from(&home).join(".claude/skills"),
        "opencode" => PathBuf::from(&home).join(".opencode/skills"),
        _ => {
            let proj_path = query
                .project_path
                .filter(|p| !p.trim().is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| state.workspace_dir.clone());
            proj_path.join(".agents/skills")
        }
    };

    let mut installed = Vec::new();
    if let Ok(entries) = fs::read_dir(&target_dir) {
        for entry in entries.flatten() {
            let p = entry.path();
            if p.is_dir() && p.join("SKILL.md").exists() {
                installed.push(entry.file_name().to_string_lossy().to_string());
            }
        }
    }
    installed.sort();

    Json(json!({
        "target": target,
        "path": target_dir.to_string_lossy().to_string(),
        "count": installed.len(),
        "skills": installed
    }))
}
