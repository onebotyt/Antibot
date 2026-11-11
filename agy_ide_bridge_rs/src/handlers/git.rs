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
        ApplyPatchReq, ApplyPatchResponse, ComputeDiffReq, DiffFile, DiffHunk, DiffLine,
        DiffResponse, GitActionReq, GitActionResult, GitBranchInfo, GitCommitDetailsResponse,
        GitCommitFileChange, GitCommitRef, GitConfig, GitFileStatus, GitGraphCommit,
        GitGraphSegment, GitSetConfigReq, ResolveConflictReq, ResolveConflictResponse,
        ThreeWayMergeReq, ThreeWayMergeResponse,
    },
    AppState,
};
use antibot_native::diff_patch::{self, ConflictStrategy};

#[derive(Debug, Deserialize)]
pub struct GitQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct GitLogQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
    pub limit: Option<usize>,
    pub skip: Option<usize>,
    pub branch: Option<String>,
    pub all: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct GitCommitDetailsQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
    pub hash: String,
}

#[derive(Debug, Deserialize)]
pub struct GitCommitDiffQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
    pub hash: String,
    pub file: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct GitCommitContentQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
    pub hash: String,
    pub file: String,
}

#[derive(Debug, Deserialize)]
pub struct GitFileLogQuery {
    pub dir: Option<String>,
    pub path: Option<String>,
    pub project: Option<String>,
    pub file: String,
    pub limit: Option<usize>,
}

fn resolve_dir(state: &AppState, project: Option<&str>, dir: Option<&str>, path: Option<&str>) -> String {
    project
        .or(dir)
        .or(path)
        .map(|s| s.to_string())
        .unwrap_or_else(|| state.workspace_dir.to_string_lossy().to_string())
}

fn resolve_repo_dir(state: &AppState, q: &GitQuery) -> String {
    resolve_dir(state, q.project.as_deref(), q.dir.as_deref(), q.path.as_deref())
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

    let (ahead, behind) = Command::new("git")
        .args(["rev-list", "--left-right", "--count", "@{u}...HEAD"])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| {
            let parts: Vec<&str> = s.split_whitespace().collect();
            let b = parts.first().and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
            let a = parts.get(1).and_then(|v| v.parse::<usize>().ok()).unwrap_or(0);
            (a, b)
        })
        .unwrap_or((0, 0));

    (
        StatusCode::OK,
        Json(json!({
            "isGitRepo": true,
            "isRepo": true,
            "branch": branch,
            "ahead": ahead,
            "behind": behind,
            "unpushedCount": ahead,
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

    let files = parse_git_diff(&diff);

    (
        StatusCode::OK,
        Json(json!({
            "diff": diff,
            "files": files
        })),
    )
}

pub fn parse_git_diff(raw_diff: &str) -> Vec<DiffFile> {
    let mut files = Vec::new();
    let mut current_file: Option<DiffFile> = None;
    let mut current_hunk: Option<DiffHunk> = None;

    let mut old_line_num = 0;
    let mut new_line_num = 0;

    let hunk_re = regex::Regex::new(r"@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@").unwrap();

    for line in raw_diff.lines() {
        if line.starts_with("diff --git ") {
            if let Some(hunk) = current_hunk.take() {
                if let Some(ref mut f) = current_file {
                    f.hunks.push(hunk);
                }
            }
            if let Some(f) = current_file.take() {
                files.push(f);
            }
            let parts: Vec<&str> = line.split_whitespace().collect();
            let path = parts.get(3).unwrap_or(&"").trim_start_matches("b/").to_string();
            let old_path = parts.get(2).map(|p| p.trim_start_matches("a/").to_string());
            current_file = Some(DiffFile {
                path,
                old_path,
                status: "modified".to_string(),
                hunks: Vec::new(),
            });
        } else if line.starts_with("@@ ") {
            if let Some(hunk) = current_hunk.take() {
                if let Some(ref mut f) = current_file {
                    f.hunks.push(hunk);
                }
            }
            if let Some(caps) = hunk_re.captures(line) {
                old_line_num = caps.get(1).and_then(|m| m.as_str().parse::<usize>().ok()).unwrap_or(1);
                new_line_num = caps.get(2).and_then(|m| m.as_str().parse::<usize>().ok()).unwrap_or(1);
            }
            current_hunk = Some(DiffHunk {
                header: line.to_string(),
                lines: Vec::new(),
            });
        } else if let Some(ref mut hunk) = current_hunk {
            if line.starts_with('+') && !line.starts_with("+++") {
                hunk.lines.push(DiffLine {
                    line_type: "insert".to_string(),
                    old_line: None,
                    new_line: Some(new_line_num),
                    text: line[1..].to_string(),
                });
                new_line_num += 1;
            } else if line.starts_with('-') && !line.starts_with("---") {
                hunk.lines.push(DiffLine {
                    line_type: "delete".to_string(),
                    old_line: Some(old_line_num),
                    new_line: None,
                    text: line[1..].to_string(),
                });
                old_line_num += 1;
            } else if line.starts_with(' ') {
                hunk.lines.push(DiffLine {
                    line_type: "equal".to_string(),
                    old_line: Some(old_line_num),
                    new_line: Some(new_line_num),
                    text: line[1..].to_string(),
                });
                old_line_num += 1;
                new_line_num += 1;
            }
        }
    }

    if let Some(hunk) = current_hunk {
        if let Some(ref mut f) = current_file {
            f.hunks.push(hunk);
        }
    }
    if let Some(f) = current_file {
        files.push(f);
    }

    files
}

pub async fn compute_diff_handler(
    Json(req): Json<ComputeDiffReq>,
) -> impl IntoResponse {
    let text_diff = similar::TextDiff::from_lines(&req.old_text, &req.new_text);
    let mut hunks = Vec::new();

    for hunk in text_diff.unified_diff().context_radius(3).iter_hunks() {
        let header = format!("{}", hunk.header());
        let mut lines = Vec::new();
        for change in hunk.iter_changes() {
            let (line_type, old_line, new_line) = match change.tag() {
                similar::ChangeTag::Equal => (
                    "equal",
                    change.old_index().map(|i| i + 1),
                    change.new_index().map(|i| i + 1),
                ),
                similar::ChangeTag::Delete => ("delete", change.old_index().map(|i| i + 1), None),
                similar::ChangeTag::Insert => ("insert", None, change.new_index().map(|i| i + 1)),
            };
            lines.push(DiffLine {
                line_type: line_type.to_string(),
                old_line,
                new_line,
                text: change.value().trim_end_matches(['\r', '\n']).to_string(),
            });
        }
        hunks.push(DiffHunk { header, lines });
    }

    let filename = req.filename.unwrap_or_else(|| "file".to_string());
    let diff_file = DiffFile {
        path: filename.clone(),
        old_path: Some(filename.clone()),
        status: "modified".to_string(),
        hunks,
    };

    let raw_diff = text_diff
        .unified_diff()
        .context_radius(3)
        .header(&format!("a/{}", filename), &format!("b/{}", filename))
        .to_string();

    (
        StatusCode::OK,
        Json(DiffResponse {
            diff: raw_diff,
            files: vec![diff_file],
        }),
    )
}

pub async fn apply_diff_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<ApplyPatchReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let (target_path, original_content) = if let Some(ref rel_path) = req.path {
        let full_path = std::path::Path::new(&repo_dir).join(rel_path);
        let content = match std::fs::read_to_string(&full_path) {
            Ok(c) => c,
            Err(e) => {
                return (
                    StatusCode::BAD_REQUEST,
                    Json(ApplyPatchResponse {
                        success: false,
                        patched_text: None,
                        error: Some(format!("Failed to read target file {}: {}", rel_path, e)),
                        hunk_index: None,
                    }),
                );
            }
        };
        (Some(full_path), content)
    } else {
        (None, String::new())
    };

    let hunks = diff_patch::parse_unified_diff(&req.patch);
    if hunks.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(ApplyPatchResponse {
                success: false,
                patched_text: None,
                error: Some("No valid hunks found in patch".to_string()),
                hunk_index: None,
            }),
        );
    }

    match diff_patch::apply_patch(&original_content, &hunks, req.reverse) {
        Ok(patched) => {
            if !req.dry_run {
                if let Some(ref p) = target_path {
                    if let Some(parent) = p.parent() {
                        let _ = std::fs::create_dir_all(parent);
                    }
                    if let Err(e) = std::fs::write(p, &patched) {
                        return (
                            StatusCode::INTERNAL_SERVER_ERROR,
                            Json(ApplyPatchResponse {
                                success: false,
                                patched_text: Some(patched),
                                error: Some(format!("Failed to write patched file: {}", e)),
                                hunk_index: None,
                            }),
                        );
                    }
                }
            }
            (
                StatusCode::OK,
                Json(ApplyPatchResponse {
                    success: true,
                    patched_text: Some(patched),
                    error: None,
                    hunk_index: None,
                }),
            )
        }
        Err(err) => (
            StatusCode::OK,
            Json(ApplyPatchResponse {
                success: false,
                patched_text: None,
                error: Some(err.message),
                hunk_index: Some(err.hunk_index),
            }),
        ),
    }
}

pub async fn git_merge_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<ThreeWayMergeReq>,
) -> impl IntoResponse {
    let result = diff_patch::three_way_merge(&req.base, &req.ours, &req.theirs);
    if let Some(ref rel_path) = req.path {
        if !result.has_conflicts {
            let repo_dir = state.workspace_dir.to_string_lossy().to_string();
            let full_path = std::path::Path::new(&repo_dir).join(rel_path);
            if let Some(parent) = full_path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let _ = std::fs::write(full_path, &result.merged);
        }
    }

    (
        StatusCode::OK,
        Json(ThreeWayMergeResponse {
            success: !result.has_conflicts,
            merged: result.merged,
            has_conflicts: result.has_conflicts,
            conflict_count: result.conflict_count,
        }),
    )
}

pub async fn git_conflict_resolve_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<ResolveConflictReq>,
) -> impl IntoResponse {
    let strategy = match req.strategy.to_lowercase().as_str() {
        "theirs" => ConflictStrategy::Theirs,
        "both" => ConflictStrategy::Both,
        _ => ConflictStrategy::Ours,
    };

    let resolved = diff_patch::resolve_conflicts(&req.text, strategy);
    if let Some(ref rel_path) = req.path {
        let repo_dir = state.workspace_dir.to_string_lossy().to_string();
        let full_path = std::path::Path::new(&repo_dir).join(rel_path);
        let _ = std::fs::write(full_path, &resolved);
    }

    (
        StatusCode::OK,
        Json(ResolveConflictResponse {
            success: true,
            resolved,
        }),
    )
}


#[derive(Debug, Clone)]
#[allow(dead_code)]
pub struct RawCommit {
    pub hash: String,
    pub short_hash: String,
    pub author: String,
    pub date: String,
    pub subject: String,
    pub body: String,
    pub parents: Vec<String>,
    pub refs: Vec<GitCommitRef>,
    pub is_unpushed: bool,
}

pub fn parse_commit_refs(ref_str: &str) -> Vec<GitCommitRef> {
    let mut refs = Vec::new();
    if ref_str.trim().is_empty() {
        return refs;
    }
    for part in ref_str.split(',') {
        let trimmed = part.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Some(rest) = trimmed.strip_prefix("HEAD -> ") {
            refs.push(GitCommitRef {
                name: rest.trim().to_string(),
                is_head: true,
                is_remote: false,
                is_tag: false,
            });
        } else if let Some(rest) = trimmed.strip_prefix("tag: ") {
            refs.push(GitCommitRef {
                name: rest.trim().to_string(),
                is_head: false,
                is_remote: false,
                is_tag: true,
            });
        } else if trimmed.starts_with("origin/") || trimmed.starts_with("remotes/") {
            let name = trimmed.trim_start_matches("remotes/").to_string();
            refs.push(GitCommitRef {
                name,
                is_head: false,
                is_remote: true,
                is_tag: false,
            });
        } else {
            refs.push(GitCommitRef {
                name: trimmed.to_string(),
                is_head: false,
                is_remote: false,
                is_tag: false,
            });
        }
    }
    refs
}

pub fn compute_dag_layout(raw_commits: Vec<RawCommit>) -> Vec<GitGraphCommit> {
    let mut lanes: Vec<Option<String>> = Vec::new();
    let mut graph_commits = Vec::with_capacity(raw_commits.len());

    for commit in raw_commits {
        // 1. Locate if commit is already expected in an existing lane
        let my_lane = lanes.iter().position(|l| l.as_ref() == Some(&commit.hash));
        let col = match my_lane {
            Some(idx) => {
                lanes[idx] = None;
                idx
            }
            None => {
                let free_idx = lanes.iter().position(|l| l.is_none()).unwrap_or(lanes.len());
                if free_idx >= lanes.len() {
                    lanes.resize(free_idx + 1, None);
                }
                free_idx
            }
        };

        let color_index = col % 6;
        let mut segments = Vec::new();
        let is_merge = commit.parents.len() > 1;

        // 2. Connect to parents
        if let Some(p0) = commit.parents.first() {
            let existing_p0 = lanes.iter().position(|l| l.as_ref() == Some(p0));
            match existing_p0 {
                Some(p0_col) => {
                    segments.push(GitGraphSegment {
                        from_col: col,
                        to_col: p0_col,
                        segment_type: if p0_col == col { "direct".to_string() } else { "merge".to_string() },
                        color_index,
                    });
                }
                None => {
                    if col >= lanes.len() {
                        lanes.resize(col + 1, None);
                    }
                    lanes[col] = Some(p0.clone());
                    segments.push(GitGraphSegment {
                        from_col: col,
                        to_col: col,
                        segment_type: "direct".to_string(),
                        color_index,
                    });
                }
            }
        }

        for p in commit.parents.iter().skip(1) {
            let existing_p = lanes.iter().position(|l| l.as_ref() == Some(p));
            match existing_p {
                Some(p_col) => {
                    segments.push(GitGraphSegment {
                        from_col: col,
                        to_col: p_col,
                        segment_type: "merge".to_string(),
                        color_index: p_col % 6,
                    });
                }
                None => {
                    let new_col = lanes.iter().position(|l| l.is_none()).unwrap_or(lanes.len());
                    if new_col >= lanes.len() {
                        lanes.resize(new_col + 1, None);
                    }
                    lanes[new_col] = Some(p.clone());
                    segments.push(GitGraphSegment {
                        from_col: col,
                        to_col: new_col,
                        segment_type: "branch".to_string(),
                        color_index: new_col % 6,
                    });
                }
            }
        }

        // 3. Pass-through segments for active lanes that are not current col
        for (idx, slot) in lanes.iter().enumerate() {
            if slot.is_some() && idx != col {
                let already_connected = segments.iter().any(|s| s.to_col == idx && s.from_col == col);
                if !already_connected {
                    segments.push(GitGraphSegment {
                        from_col: idx,
                        to_col: idx,
                        segment_type: "pass_through".to_string(),
                        color_index: idx % 6,
                    });
                }
            }
        }

        // 4. Trim trailing None
        while lanes.last().map(|s| s.is_none()).unwrap_or(false) {
            lanes.pop();
        }

        graph_commits.push(GitGraphCommit {
            hash: commit.hash,
            short_hash: commit.short_hash,
            author: commit.author,
            date: commit.date,
            message: commit.subject.clone(),
            subject: commit.subject,
            parents: commit.parents,
            is_unpushed: commit.is_unpushed,
            is_merge,
            graph_column: col,
            color_index,
            refs: commit.refs,
            segments,
        });
    }

    graph_commits
}

pub async fn git_log_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitLogQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_dir(&state, params.project.as_deref(), params.dir.as_deref(), params.path.as_deref());
    let limit = params.limit.unwrap_or(30).clamp(1, 200);
    let skip = params.skip.unwrap_or(0);

    let mut args = vec![
        "log".to_string(),
        format!("-n{}", limit),
        format!("--skip={}", skip),
        "--topo-order".to_string(),
        "--date=short".to_string(),
        "--pretty=format:%H\x1f%h\x1f%an\x1f%ad\x1f%s\x1f%P\x1f%D\x1f%B\x1e".to_string(),
    ];

    if params.all.unwrap_or(false) {
        args.push("--all".to_string());
    } else if let Some(ref b) = params.branch {
        if !b.trim().is_empty() {
            args.push(b.clone());
        }
    }

    let output = Command::new("git")
        .args(&args)
        .current_dir(&repo_dir)
        .output();

    let unpushed_hashes: std::collections::HashSet<String> = {
        let out = Command::new("git")
            .args(["rev-list", "@{u}..HEAD"])
            .current_dir(&repo_dir)
            .output()
            .or_else(|_| {
                Command::new("git")
                    .args(["rev-list", "origin/main..HEAD"])
                    .current_dir(&repo_dir)
                    .output()
            });
        if let Ok(o) = out {
            if o.status.success() {
                String::from_utf8_lossy(&o.stdout)
                    .lines()
                    .map(|l| l.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect()
            } else {
                std::collections::HashSet::new()
            }
        } else {
            std::collections::HashSet::new()
        }
    };

    let mut raw_commits = Vec::new();

    if let Ok(out) = output {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for record in text.split('\x1e') {
                let trimmed_rec = record.trim();
                if trimmed_rec.is_empty() {
                    continue;
                }
                let parts: Vec<&str> = trimmed_rec.split('\x1f').collect();
                if parts.len() >= 6 {
                    let hash = parts[0].trim().to_string();
                    let short_hash = parts[1].trim().to_string();
                    let author = parts[2].trim().to_string();
                    let date = parts[3].trim().to_string();
                    let subject = parts[4].trim().to_string();
                    let parents = parts[5]
                        .split_whitespace()
                        .map(|s| s.trim().to_string())
                        .filter(|s| !s.is_empty())
                        .collect();
                    let ref_str = parts.get(6).unwrap_or(&"");
                    let refs = parse_commit_refs(ref_str);
                    let body = parts.get(7).map(|s| s.trim().to_string()).unwrap_or_else(|| subject.clone());
                    let is_unpushed = unpushed_hashes.contains(&hash);

                    raw_commits.push(RawCommit {
                        hash,
                        short_hash,
                        author,
                        date,
                        subject,
                        body,
                        parents,
                        refs,
                        is_unpushed,
                    });
                }
            }
        }
    }

    let graph_commits = compute_dag_layout(raw_commits);

    (StatusCode::OK, Json(json!({ "commits": graph_commits })))
}

pub async fn git_commit_details_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitCommitDetailsQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_dir(&state, params.project.as_deref(), params.dir.as_deref(), params.path.as_deref());
    let hash = params.hash.trim().to_string();

    let meta_out = Command::new("git")
        .args(["show", "-s", "--pretty=format:%H\x1f%h\x1f%an\x1f%ad\x1f%s\x1f%B", "--date=short", &hash])
        .current_dir(&repo_dir)
        .output();

    let mut full_hash = hash.clone();
    let mut short_hash = hash.chars().take(7).collect::<String>();
    let mut author = String::new();
    let mut date = String::new();
    let mut subject = String::new();
    let mut body = String::new();

    if let Ok(out) = meta_out {
        if let Ok(text) = String::from_utf8(out.stdout) {
            let parts: Vec<&str> = text.split('\x1f').collect();
            if parts.len() >= 5 {
                full_hash = parts[0].trim().to_string();
                short_hash = parts[1].trim().to_string();
                author = parts[2].trim().to_string();
                date = parts[3].trim().to_string();
                subject = parts[4].trim().to_string();
                body = parts.get(5).map(|s| s.trim().to_string()).unwrap_or_else(|| subject.clone());
            }
        }
    }

    let status_out = Command::new("git")
        .args(["diff-tree", "--no-commit-id", "--name-status", "-r", &full_hash])
        .current_dir(&repo_dir)
        .output();

    let mut file_statuses: std::collections::HashMap<String, (String, Option<String>)> = std::collections::HashMap::new();
    if let Ok(out) = status_out {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for line in text.lines() {
                let parts: Vec<&str> = line.split('\t').collect();
                if parts.len() >= 2 {
                    let st = parts[0].trim();
                    let st_code = st.chars().next().unwrap_or('M').to_string();
                    if st.starts_with('R') && parts.len() >= 3 {
                        let old_p = parts[1].trim().to_string();
                        let new_p = parts[2].trim().to_string();
                        file_statuses.insert(new_p, (st_code, Some(old_p)));
                    } else {
                        let path = parts[1].trim().to_string();
                        file_statuses.insert(path, (st_code, None));
                    }
                }
            }
        }
    }

    let numstat_out = Command::new("git")
        .args(["show", "--numstat", "--pretty=", &full_hash])
        .current_dir(&repo_dir)
        .output();

    let mut changed_files = Vec::new();
    let mut seen_files = std::collections::HashSet::new();

    if let Ok(out) = numstat_out {
        if let Ok(text) = String::from_utf8(out.stdout) {
            for line in text.lines() {
                let parts: Vec<&str> = line.split('\t').collect();
                if parts.len() >= 3 {
                    let adds = parts[0].trim().parse::<usize>().unwrap_or(0);
                    let dels = parts[1].trim().parse::<usize>().unwrap_or(0);
                    let raw_path = parts[2].trim().to_string();

                    let (status, old_path) = file_statuses
                        .get(&raw_path)
                        .cloned()
                        .unwrap_or_else(|| ("M".to_string(), None));

                    seen_files.insert(raw_path.clone());
                    changed_files.push(GitCommitFileChange {
                        path: raw_path,
                        status,
                        old_path,
                        additions: adds,
                        deletions: dels,
                    });
                }
            }
        }
    }

    for (p, (st, old_p)) in file_statuses {
        if !seen_files.contains(&p) {
            changed_files.push(GitCommitFileChange {
                path: p,
                status: st,
                old_path: old_p,
                additions: 0,
                deletions: 0,
            });
        }
    }

    let stats = Command::new("git")
        .args(["show", "--shortstat", "--pretty=", &full_hash])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .map(|s| s.trim().to_string())
        .unwrap_or_default();

    (
        StatusCode::OK,
        Json(GitCommitDetailsResponse {
            hash: full_hash,
            short_hash,
            author,
            date,
            subject,
            body: body.clone(),
            message: body,
            changed_files,
            stats,
        }),
    )
}

pub async fn git_commit_diff_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitCommitDiffQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_dir(&state, params.project.as_deref(), params.dir.as_deref(), params.path.as_deref());
    let hash = params.hash.trim().to_string();

    let mut cmd = Command::new("git");
    cmd.args(["show", "--pretty="]);
    cmd.arg(&hash);
    if let Some(ref f) = params.file {
        if !f.trim().is_empty() {
            cmd.arg("--");
            cmd.arg(f.trim());
        }
    }
    cmd.current_dir(&repo_dir);

    let diff = cmd
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .unwrap_or_default();

    (
        StatusCode::OK,
        Json(json!({
            "diff": diff,
            "hash": hash,
            "file": params.file.unwrap_or_default()
        })),
    )
}

pub async fn git_commit_content_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitCommitContentQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_dir(&state, params.project.as_deref(), params.dir.as_deref(), params.path.as_deref());
    let hash = params.hash.trim();
    let file = params.file.trim();
    let spec = format!("{}:{}", hash, file);

    let content = Command::new("git")
        .args(["show", &spec])
        .current_dir(&repo_dir)
        .output()
        .ok()
        .and_then(|o| String::from_utf8(o.stdout).ok())
        .unwrap_or_default();

    (
        StatusCode::OK,
        Json(json!({
            "content": content,
            "hash": hash,
            "file": file
        })),
    )
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

pub async fn git_rollback_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let hash = match req.hash {
        Some(h) if !h.trim().is_empty() => h,
        _ => return (StatusCode::BAD_REQUEST, Json(json!({ "success": false, "error": "Commit hash is required" }))),
    };

    let mode = if req.hard.unwrap_or(true) { "--hard" } else { "--mixed" };
    let mut cmd = Command::new("git");
    cmd.current_dir(&repo_dir).args(["reset", mode, &hash]);

    match cmd.output() {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(json!({ "success": true, "output": String::from_utf8_lossy(&out.stdout) })),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "success": false, "error": e.to_string() }))),
    }
}

pub async fn git_restore_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let hash = match req.hash {
        Some(h) if !h.trim().is_empty() => h,
        _ => return (StatusCode::BAD_REQUEST, Json(json!({ "success": false, "error": "Commit hash is required" }))),
    };
    let file = match req.file {
        Some(f) if !f.trim().is_empty() => f,
        _ => return (StatusCode::BAD_REQUEST, Json(json!({ "success": false, "error": "File path is required" }))),
    };

    let mut cmd = Command::new("git");
    cmd.current_dir(&repo_dir).args(["checkout", &hash, "--", &file]);

    match cmd.output() {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(json!({ "success": true, "output": String::from_utf8_lossy(&out.stdout) })),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "success": false, "error": e.to_string() }))),
    }
}

pub async fn git_revert_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let hash = match req.hash {
        Some(h) if !h.trim().is_empty() => h,
        _ => return (StatusCode::BAD_REQUEST, Json(json!({ "success": false, "error": "Commit hash is required" }))),
    };

    let mut cmd = Command::new("git");
    cmd.current_dir(&repo_dir).args(["revert", "--no-edit", &hash]);

    match cmd.output() {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(json!({ "success": true, "output": String::from_utf8_lossy(&out.stdout) })),
        ),
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "success": false, "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "success": false, "error": e.to_string() }))),
    }
}

pub async fn git_checkpoint_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<GitActionReq>,
) -> impl IntoResponse {
    let repo_dir = resolve_post_dir(&state, req.project.as_deref());
    let msg = req.message.filter(|m| !m.trim().is_empty()).unwrap_or_else(|| "Local checkpoint".to_string());

    let is_repo = Command::new("git")
        .args(["rev-parse", "--is-inside-work-tree"])
        .current_dir(&repo_dir)
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false);
    if !is_repo {
        let _ = Command::new("git").args(["init"]).current_dir(&repo_dir).output();
        let _ = Command::new("git").args(["config", "user.name", "AntiBot Local"]).current_dir(&repo_dir).output();
        let _ = Command::new("git").args(["config", "user.email", "antibot@local"]).current_dir(&repo_dir).output();
    }

    let _ = Command::new("git").args(["add", "-A"]).current_dir(&repo_dir).output();
    let res = Command::new("git").args(["commit", "-m", &msg]).current_dir(&repo_dir).output();

    match res {
        Ok(out) if out.status.success() => (
            StatusCode::OK,
            Json(json!({ "success": true, "output": "Checkpoint saved successfully" })),
        ),
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr);
            let stdout = String::from_utf8_lossy(&out.stdout);
            if stderr.contains("nothing to commit") || stdout.contains("nothing to commit") {
                (StatusCode::OK, Json(json!({ "success": true, "output": "Working tree clean, no changes to save" })))
            } else {
                (StatusCode::OK, Json(json!({ "success": false, "error": if stderr.is_empty() { stdout } else { stderr } })))
            }
        }
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "success": false, "error": e.to_string() }))),
    }
}

pub async fn git_file_log_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<GitFileLogQuery>,
) -> impl IntoResponse {
    let repo_dir = resolve_dir(&state, params.project.as_deref(), params.dir.as_deref(), params.path.as_deref());
    let limit = params.limit.unwrap_or(25);

    let res = Command::new("git")
        .args([
            "log",
            &format!("-n{}", limit),
            "--follow",
            "--format=commit %H%x00%h%x00%an%x00%ad%x00%s",
            "--date=short",
            "--",
            &params.file,
        ])
        .current_dir(&repo_dir)
        .output();

    match res {
        Ok(out) if out.status.success() => {
            let text = String::from_utf8_lossy(&out.stdout);
            let mut commits = Vec::new();
            for line in text.lines() {
                let trimmed = line.trim();
                if let Some(rest) = trimmed.strip_prefix("commit ") {
                    let parts: Vec<&str> = rest.split('\0').collect();
                    if parts.len() >= 5 {
                        commits.push(json!({
                            "hash": parts[0],
                            "shortHash": parts[1],
                            "author": parts[2],
                            "date": parts[3],
                            "message": parts[4]
                        }));
                    }
                }
            }
            (StatusCode::OK, Json(json!({ "commits": commits })))
        }
        Ok(out) => (
            StatusCode::OK,
            Json(json!({ "commits": [], "error": String::from_utf8_lossy(&out.stderr) })),
        ),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "commits": [], "error": e.to_string() }))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_commit_refs() {
        let refs = parse_commit_refs("HEAD -> main, origin/main, tag: v1.0.60, feature/auth");
        assert_eq!(refs.len(), 4);
        assert_eq!(refs[0].name, "main");
        assert!(refs[0].is_head);
        assert!(!refs[0].is_remote);
        assert!(!refs[0].is_tag);

        assert_eq!(refs[1].name, "origin/main");
        assert!(!refs[1].is_head);
        assert!(refs[1].is_remote);

        assert_eq!(refs[2].name, "v1.0.60");
        assert!(refs[2].is_tag);

        assert_eq!(refs[3].name, "feature/auth");
        assert!(!refs[3].is_head);
        assert!(!refs[3].is_remote);
    }

    #[test]
    fn test_compute_dag_layout_linear() {
        let c2 = RawCommit {
            hash: "c2".to_string(),
            short_hash: "c2".to_string(),
            author: "dev".to_string(),
            date: "2026-11-09".to_string(),
            subject: "Second commit".to_string(),
            body: "Second commit".to_string(),
            parents: vec!["c1".to_string()],
            refs: vec![],
            is_unpushed: false,
        };
        let c1 = RawCommit {
            hash: "c1".to_string(),
            short_hash: "c1".to_string(),
            author: "dev".to_string(),
            date: "2026-11-08".to_string(),
            subject: "First commit".to_string(),
            body: "First commit".to_string(),
            parents: vec![],
            refs: vec![],
            is_unpushed: false,
        };

        let graph = compute_dag_layout(vec![c2, c1]);
        assert_eq!(graph.len(), 2);
        assert_eq!(graph[0].graph_column, 0);
        assert_eq!(graph[1].graph_column, 0);
        assert_eq!(graph[0].segments.len(), 1);
        assert_eq!(graph[0].segments[0].segment_type, "direct");
    }

    #[test]
    fn test_compute_dag_layout_merge() {
        let merge = RawCommit {
            hash: "m1".to_string(),
            short_hash: "m1".to_string(),
            author: "dev".to_string(),
            date: "2026-11-09".to_string(),
            subject: "Merge branch 'feat'".to_string(),
            body: "Merge branch 'feat'".to_string(),
            parents: vec!["c2".to_string(), "b1".to_string()],
            refs: vec![],
            is_unpushed: false,
        };
        let c2 = RawCommit {
            hash: "c2".to_string(),
            short_hash: "c2".to_string(),
            author: "dev".to_string(),
            date: "2026-11-09".to_string(),
            subject: "Main commit".to_string(),
            body: "Main commit".to_string(),
            parents: vec!["c0".to_string()],
            refs: vec![],
            is_unpushed: false,
        };
        let b1 = RawCommit {
            hash: "b1".to_string(),
            short_hash: "b1".to_string(),
            author: "dev".to_string(),
            date: "2026-11-09".to_string(),
            subject: "Feat commit".to_string(),
            body: "Feat commit".to_string(),
            parents: vec!["c0".to_string()],
            refs: vec![],
            is_unpushed: false,
        };

        let graph = compute_dag_layout(vec![merge, c2, b1]);
        assert_eq!(graph.len(), 3);
        assert_eq!(graph[0].graph_column, 0);
        assert!(graph[0].is_merge);
        // Merge should have 2 segments: direct (0->0) and branch (0->1)
        assert_eq!(graph[0].segments.len(), 2);
        assert_eq!(graph[1].graph_column, 0);
        // b1 is on lane 1
        assert_eq!(graph[2].graph_column, 1);
    }

    #[test]
    fn test_apply_diff_patch_flow() {
        let base = "fn main() {\n    println!(\"hello\");\n}\n";
        let diff = "--- a/main.rs\n+++ b/main.rs\n@@ -1,3 +1,3 @@\n fn main() {\n-    println!(\"hello\");\n+    println!(\"world\");\n }\n";
        let hunks = antibot_native::diff_patch::parse_unified_diff(diff);
        let patched = antibot_native::diff_patch::apply_patch(base, &hunks, false).unwrap();
        assert!(patched.contains("world"));
        assert!(!patched.contains("hello"));
    }

    #[test]
    fn test_three_way_merge_flow() {
        let base = "alpha\nbeta\ngamma\n";
        let ours = "alpha-1\nbeta\ngamma\n";
        let theirs = "alpha\nbeta\ngamma-2\n";
        let res = antibot_native::diff_patch::three_way_merge(base, ours, theirs);
        assert!(!res.has_conflicts);
        assert!(res.merged.contains("alpha-1"));
        assert!(res.merged.contains("gamma-2"));
    }

    #[test]
    fn test_conflict_resolve_flow() {
        let text = "<<<<<<< ours\nfeatureA\n=======\nfeatureB\n>>>>>>> theirs\n";
        let ours_resolved = antibot_native::diff_patch::resolve_conflicts(text, antibot_native::diff_patch::ConflictStrategy::Ours);
        assert_eq!(ours_resolved.trim(), "featureA");

        let theirs_resolved = antibot_native::diff_patch::resolve_conflicts(text, antibot_native::diff_patch::ConflictStrategy::Theirs);
        assert_eq!(theirs_resolved.trim(), "featureB");
    }
}


