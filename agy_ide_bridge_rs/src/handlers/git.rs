use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use serde::Deserialize;
use serde_json::json;
use std::{process::Command, sync::Arc};

use crate::{
    models::{
        GitActionReq, GitActionResult, GitBranchInfo, GitConfig, GitFileStatus, GitSetConfigReq,
    },
    AppState,
};

#[derive(Debug, Deserialize)]
pub struct GitQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
}

fn resolve_repo_dir(state: &AppState, q: &GitQuery) -> String {
    q.project
        .as_ref()
        .or(q.dir.as_ref())
        .or(q.path.as_ref())
        .cloned()
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string())
}

fn resolve_post_dir(state: &AppState, project: Option<&str>) -> String {
    project
        .filter(|p| !p.trim().is_empty())
        .map(|p| p.to_string())
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string())
}

pub async fn git_status_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_repo_dir(&state, &params);

    let is_repo = Command::new("git")
        .args(["rev-parse", "--is-inside-work-tree"])
        .current_dir(&repo_dir)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);

    if !is_repo {
        return (
            StatusCode::OK,
            Json(json!({
                "isGitRepo": false,
                "isRepo": false,
                "branch": "HEAD",
                "stagedFiles": Vec::<GitFileStatus>::new(),
                "unstagedFiles": Vec::<GitFileStatus>::new(),
                "untrackedFiles": Vec::<GitFileStatus>::new(),
                "staged": Vec::<String>::new(),
                "modified": Vec::<String>::new(),
                "untracked": Vec::<String>::new(),
                "clean": true
            })),
        );
    }

    let branch = Command::new("git")
        .args(["branch", "--show-current"])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "HEAD".to_string());

    let status_output = Command::new("git")
        .args(["status", "--porcelain"])
        .current_dir(&repo_dir)
        .output();

    let mut staged_files = Vec::new();
    let mut unstaged_files = Vec::new();
    let mut untracked_files = Vec::new();
    let mut staged = Vec::new();
    let mut modified = Vec::new();
    let mut untracked = Vec::new();

    if let Ok(out) = status_output {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for line in text.lines() {
                if line.len() < 3 {
                    continue;
                }
                let index_status = line.chars().next().unwrap_or(' ');
                let work_status = line.chars().nth(1).unwrap_or(' ');
                let file_path = line[3..].trim().to_string();

                if index_status != ' ' && index_status != '?' {
                    staged.push(file_path.clone());
                    staged_files.push(GitFileStatus {
                        path: file_path.clone(),
                        old_path: None,
                        status: index_status.to_string(),
                        staged: true,
                    });
                }
                if work_status == 'M' || work_status == 'D' {
                    modified.push(file_path.clone());
                    unstaged_files.push(GitFileStatus {
                        path: file_path.clone(),
                        old_path: None,
                        status: work_status.to_string(),
                        staged: false,
                    });
                } else if index_status == '?' {
                    untracked.push(file_path.clone());
                    untracked_files.push(GitFileStatus {
                        path: file_path,
                        old_path: None,
                        status: "U".to_string(),
                        staged: false,
                    });
                }
            }
        }
    }

    let clean = staged.is_empty() && modified.is_empty() && untracked.is_empty();

    (
        StatusCode::OK,
        Json(json!({
            "isGitRepo": true,
            "isRepo": true,
            "branch": branch,
            "stagedFiles": staged_files,
            "unstagedFiles": unstaged_files,
            "untrackedFiles": untracked_files,
            "staged": staged,
            "modified": modified,
            "untracked": untracked,
            "clean": clean
        })),
    )
}

pub async fn git_branches_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_repo_dir(&state, &params);

    let output = Command::new("git")
        .args(["branch", "-a"])
        .current_dir(&repo_dir)
        .output();

    let mut branches = Vec::new();
    let mut branch_infos = Vec::new();
    let mut current = "main".to_string();

    if let Ok(out) = output {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for line in text.lines() {
                let trimmed = line.trim();
                let is_current = trimmed.starts_with('*');
                let name = trimmed.trim_start_matches('*').trim().to_string();
                if name.is_empty() || name.contains("->") {
                    continue;
                }
                let is_remote = name.starts_with("remotes/");
                let clean_name = if is_remote {
                    name.trim_start_matches("remotes/").to_string()
                } else {
                    name.clone()
                };

                if is_current {
                    current = clean_name.clone();
                }

                branches.push(clean_name.clone());
                branch_infos.push(GitBranchInfo {
                    name: clean_name,
                    is_current,
                    is_remote,
                });
            }
        }
    }

    (
        StatusCode::OK,
        Json(json!({
            "current": current,
            "branches": branch_infos,
            "names": branches
        })),
    )
}

pub async fn git_diff_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_repo_dir(&state, &params);

    let output = Command::new("git")
        .args(["diff"])
        .current_dir(&repo_dir)
        .output();

    let diff = output
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .unwrap_or_default();

    (StatusCode::OK, Json(json!({ "diff": diff })))
}

pub async fn git_log_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_repo_dir(&state, &params);

    let output = Command::new("git")
        .args(["log", "-n", "30", "--pretty=format:%H%x09%h%x09%an%x09%ad%x09%s", "--date=short"])
        .current_dir(&repo_dir)
        .output();

    let mut commits = Vec::new();
    if let Ok(out) = output {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for line in text.lines() {
                let parts: Vec<&str> = line.split('\t').collect();
                if parts.len() >= 5 {
                    commits.push(json!({
                        "hash": parts[0],
                        "shortHash": parts[1],
                        "author": parts[2],
                        "date": parts[3],
                        "message": parts[4],
                        "subject": parts[4]
                    }));
                }
            }
        }
    }

    (StatusCode::OK, Json(json!({ "commits": commits })))
}

pub async fn git_init_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let res = Command::new("git")
        .args(["init"])
        .current_dir(&repo_dir)
        .output();

    match res {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(GitActionResult {
                success: true,
                output: Some("Initialized empty Git repository".to_string()),
                error: None,
            }),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(String::from_utf8_lossy(&out.stderr).to_string()),
            }),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(e.to_string()),
            }),
        ),
    }
}

pub async fn git_clone_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let url = match req.url {
        Some(u) if !u.trim().is_empty() => u,
        _ => return (StatusCode::BAD_REQUEST, Json(GitActionResult {
            success: false,
            error: Some("Clone URL is required".to_string()),
            output: None,
        })),
    };

    let target_dir = resolve_post_dir(&state, req.project.as_deref());
    let res = Command::new("git")
        .args(["clone", &url, &target_dir])
        .output();

    match res {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(GitActionResult {
                success: true,
                output: Some("Cloned successfully".to_string()),
                error: None,
            }),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(String::from_utf8_lossy(&out.stderr).to_string()),
            }),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(e.to_string()),
            }),
        ),
    }
}

pub async fn git_stage_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let mut cmd = Command::new("git");
    cmd.arg("add").current_dir(&repo_dir);

    if let Some(paths) = req.paths {
        if paths.is_empty() {
            cmd.arg(".");
        } else {
            for p in paths {
                cmd.arg(p);
            }
        }
    } else {
        cmd.arg(".");
    }

    match cmd.output() {
        Ok(out) if out.status.success() => (StatusCode::OK, Json(json!({ "success": true }))),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_unstage_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let mut cmd = Command::new("git");
    cmd.args(["restore", "--staged"]).current_dir(&repo_dir);

    if let Some(paths) = req.paths {
        if paths.is_empty() {
            cmd.arg(".");
        } else {
            for p in paths {
                cmd.arg(p);
            }
        }
    } else {
        cmd.arg(".");
    }

    match cmd.output() {
        Ok(out) if out.status.success() => (StatusCode::OK, Json(json!({ "success": true }))),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_discard_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let mut cmd = Command::new("git");
    cmd.args(["checkout", "--"]).current_dir(&repo_dir);

    if let Some(paths) = req.paths {
        if paths.is_empty() {
            cmd.arg(".");
        } else {
            for p in paths {
                cmd.arg(p);
            }
        }
    } else {
        cmd.arg(".");
    }

    match cmd.output() {
        Ok(out) if out.status.success() => (StatusCode::OK, Json(json!({ "success": true }))),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_commit_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let msg = req.message.unwrap_or_else(|| "Update".to_string());

    let res = Command::new("git")
        .args(["commit", "-m", &msg])
        .current_dir(&repo_dir)
        .output();

    match res {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(GitActionResult {
                success: true,
                output: Some(String::from_utf8_lossy(&out.stdout).to_string()),
                error: None,
            }),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(String::from_utf8_lossy(&out.stderr).to_string()),
            }),
        ),
        Err(e) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(GitActionResult {
                success: false,
                output: None,
                error: Some(e.to_string()),
            }),
        ),
    }
}

pub async fn git_push_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let res = Command::new("git")
        .args(["push"])
        .current_dir(&repo_dir)
        .output();

    match res {
        Ok(out) if out.status.success() => (StatusCode::OK, Json(json!({ "success": true }))),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_pull_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let res = Command::new("git")
        .args(["pull"])
        .current_dir(&repo_dir)
        .output();

    match res {
        Ok(out) if out.status.success() => (StatusCode::OK, Json(json!({ "success": true }))),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_checkout_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let branch = match req.branch {
        Some(b) if !b.is_empty() => b,
        _ => return (StatusCode::BAD_REQUEST, Json(json!({ "error": "Branch is required" }))),
    };

    let mut cmd = Command::new("git");
    cmd.current_dir(&repo_dir);
    if req.create.unwrap_or(false) {
        cmd.args(["checkout", "-b", &branch]);
    } else {
        cmd.args(["checkout", &branch]);
    }

    match cmd.output() {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(json!({ "success": true, "branch": branch })),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn git_config_get_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_repo_dir(&state, &params);

    let name = Command::new("git")
        .args(["config", "user.name"])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .unwrap_or_default();

    let email = Command::new("git")
        .args(["config", "user.email"])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .unwrap_or_default();

    let remote = Command::new("git")
        .args(["config", "remote.origin.url"])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());

    (
        StatusCode::OK,
        Json(GitConfig {
            user_name: name,
            user_email: email,
            remote_origin: remote,
        }),
    )
}

pub async fn git_config_set_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitSetConfigReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());

    if let Some(name) = req.user_name {
        let _ = Command::new("git")
            .args(["config", "user.name", &name])
            .current_dir(&repo_dir)
            .output();
    }
    if let Some(email) = req.user_email {
        let _ = Command::new("git")
            .args(["config", "user.email", &email])
            .current_dir(&repo_dir)
            .output();
    }

    (
        StatusCode::OK,
        Json(GitActionResult {
            success: true,
            output: Some("Git configuration updated".to_string()),
            error: None,
        }),
    )
}
