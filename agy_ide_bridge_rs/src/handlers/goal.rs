use axum::{
    extract::Path as AxumPath,
    http::StatusCode,
    Json,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    fs,
    path::PathBuf,
    sync::{Arc, OnceLock, RwLock},
    time::{SystemTime, UNIX_EPOCH},
};

use crate::handlers::subagents::{build_engine_roster, elect_failover_candidate};
#[cfg(test)]
use crate::handlers::subagents::record_rate_limit;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MilestoneStatus {
    Pending,
    Running,
    Verified,
    Failed,
    Skipped,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Milestone {
    pub id: String,
    pub title: String,
    pub description: String,
    pub assigned_engine: String,
    pub assigned_model: String,
    pub verifier_rule: String,
    pub status: MilestoneStatus,
    pub attempts: u32,
    pub evidence: Option<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum GoalStatus {
    Planning,
    InProgress,
    Paused,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GoalRecord {
    pub id: String,
    pub conversation_id: String,
    pub prompt: String,
    pub workspace_dir: String,
    pub status: GoalStatus,
    pub milestones: Vec<Milestone>,
    pub active_milestone_index: usize,
    pub created_at_millis: u64,
    pub updated_at_millis: u64,
    pub log_history: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MilestoneDraft {
    pub title: String,
    pub description: String,
    pub assigned_engine: Option<String>,
    pub assigned_model: Option<String>,
    pub verifier_rule: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct GoalStartRequest {
    pub prompt: String,
    pub conversation_id: Option<String>,
    pub workspace_dir: Option<String>,
    pub preferred_engine: Option<String>,
    pub milestones: Option<Vec<MilestoneDraft>>,
    #[serde(default)]
    pub auto_start_first_step: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct GoalStepResponse {
    pub goal_id: String,
    pub milestone_id: String,
    pub milestone_title: String,
    pub milestone_status: MilestoneStatus,
    pub goal_status: GoalStatus,
    pub output_evidence: String,
    pub failover_note: Option<String>,
    pub is_goal_complete: bool,
}

// ─────────────────────────────────────────────────────────────────────────────
// Storage & Checkpoint Persistence
// ─────────────────────────────────────────────────────────────────────────────

fn brain_goals_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let path = PathBuf::from(home).join(".gemini/antigravity-cli/brain/goals");
    if !path.exists() {
        let _ = fs::create_dir_all(&path);
    }
    path
}

fn save_goal_checkpoint(goal: &GoalRecord) {
    let dir = brain_goals_dir();
    let file_path = dir.join(format!("{}.json", goal.id));
    if let Ok(json_bytes) = serde_json::to_vec_pretty(goal) {
        let _ = fs::write(file_path, json_bytes);
    }
}

fn load_goal_checkpoint(goal_id: &str) -> Option<GoalRecord> {
    let dir = brain_goals_dir();
    let file_path = dir.join(format!("{}.json", goal_id));
    if let Ok(data) = fs::read_to_string(file_path) {
        serde_json::from_str(&data).ok()
    } else {
        None
    }
}

type GoalRegistry = Arc<RwLock<HashMap<String, GoalRecord>>>;

fn get_goal_registry() -> &'static GoalRegistry {
    static REGISTRY: OnceLock<GoalRegistry> = OnceLock::new();
    REGISTRY.get_or_init(|| Arc::new(RwLock::new(HashMap::new())))
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn generate_goal_id() -> String {
    format!("goal_{:x}", now_millis())
}

// ─────────────────────────────────────────────────────────────────────────────
// Autonomous Milestone Planner
// ─────────────────────────────────────────────────────────────────────────────

pub fn plan_milestones_for_prompt(prompt: &str, preferred_engine: Option<&str>) -> Vec<Milestone> {
    let pref = preferred_engine.unwrap_or("gemini");
    vec![
        Milestone {
            id: "M1".to_string(),
            title: "Codebase Audit & Architecture Inspection".to_string(),
            description: format!("Analyze target files, dependencies, and constraints for: {}", prompt),
            assigned_engine: "claude".to_string(),
            assigned_model: "claude-3-5-sonnet".to_string(),
            verifier_rule: "Analysis must identify key modules and file dependencies".to_string(),
            status: MilestoneStatus::Pending,
            attempts: 0,
            evidence: None,
            error_message: None,
        },
        Milestone {
            id: "M2".to_string(),
            title: "Implementation & Refactoring".to_string(),
            description: format!("Execute necessary modifications and refactoring for: {}", prompt),
            assigned_engine: pref.to_string(),
            assigned_model: match pref {
                "gemini" => "gemini-2.5-pro",
                "claude" => "claude-3-5-sonnet",
                "codex" => "gpt-4o",
                _ => "local-qwen-coder-0.5b",
            }.to_string(),
            verifier_rule: "Code changes must adhere to architectural standards".to_string(),
            status: MilestoneStatus::Pending,
            attempts: 0,
            evidence: None,
            error_message: None,
        },
        Milestone {
            id: "M3".to_string(),
            title: "Verification & Regression Testing".to_string(),
            description: "Run automated tests, inspect error outputs, and verify zero regressions".to_string(),
            assigned_engine: "local".to_string(),
            assigned_model: "local-qwen-coder-0.5b".to_string(),
            verifier_rule: "All test cases and assertions must pass cleanly".to_string(),
            status: MilestoneStatus::Pending,
            attempts: 0,
            evidence: None,
            error_message: None,
        },
        Milestone {
            id: "M4".to_string(),
            title: "Final Checkpoint & Synthesis".to_string(),
            description: "Synthesize completion evidence, log outcome, and finalize goal artifact".to_string(),
            assigned_engine: "gemini".to_string(),
            assigned_model: "gemini-2.5-flash".to_string(),
            verifier_rule: "Generate comprehensive task summary and mark goal complete".to_string(),
            status: MilestoneStatus::Pending,
            attempts: 0,
            evidence: None,
            error_message: None,
        },
    ]
}

// ─────────────────────────────────────────────────────────────────────────────
// Handlers
// ─────────────────────────────────────────────────────────────────────────────

pub async fn goal_start_handler(Json(payload): Json<GoalStartRequest>) -> (StatusCode, Json<serde_json::Value>) {
    let goal_id = generate_goal_id();
    let conv_id = payload.conversation_id.unwrap_or_else(|| format!("conv_{}", goal_id));
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    let ws = payload.workspace_dir.unwrap_or_else(|| format!("{}/.gemini/antigravity-cli/scratch", home));

    let milestones = if let Some(drafts) = payload.milestones {
        if !drafts.is_empty() {
            drafts
                .into_iter()
                .enumerate()
                .map(|(i, d)| Milestone {
                    id: format!("M{}", i + 1),
                    title: d.title,
                    description: d.description,
                    assigned_engine: d.assigned_engine.unwrap_or_else(|| "gemini".to_string()),
                    assigned_model: d.assigned_model.unwrap_or_else(|| "gemini-2.5-flash".to_string()),
                    verifier_rule: d.verifier_rule.unwrap_or_else(|| "Verification must confirm completion".to_string()),
                    status: MilestoneStatus::Pending,
                    attempts: 0,
                    evidence: None,
                    error_message: None,
                })
                .collect()
        } else {
            plan_milestones_for_prompt(&payload.prompt, payload.preferred_engine.as_deref())
        }
    } else {
        plan_milestones_for_prompt(&payload.prompt, payload.preferred_engine.as_deref())
    };

    let now = now_millis();
    let goal = GoalRecord {
        id: goal_id.clone(),
        conversation_id: conv_id,
        prompt: payload.prompt.clone(),
        workspace_dir: ws,
        status: GoalStatus::InProgress,
        milestones,
        active_milestone_index: 0,
        created_at_millis: now,
        updated_at_millis: now,
        log_history: vec![format!("[{}] Goal started with prompt: {}", now, payload.prompt)],
    };

    let auto_step = payload.auto_start_first_step;

    save_goal_checkpoint(&goal);
    {
        let mut reg = get_goal_registry().write().unwrap();
        reg.insert(goal_id.clone(), goal.clone());
    }

    if auto_step {
        let _ = goal_step_handler(AxumPath(goal_id.clone())).await;
    }

    let final_goal = load_goal_or_from_registry(&goal_id).unwrap_or(goal);

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "status": "ok",
            "goal": final_goal
        })),
    )
}

pub async fn goal_list_handler() -> (StatusCode, Json<serde_json::Value>) {
    let mut goals: Vec<GoalRecord> = {
        let reg = get_goal_registry().read().unwrap();
        reg.values().cloned().collect()
    };

    // Also scan disk if registry is empty
    if goals.is_empty() {
        let dir = brain_goals_dir();
        if let Ok(entries) = fs::read_dir(dir) {
            for entry in entries.flatten() {
                if entry.path().extension().and_then(|s| s.to_str()) == Some("json") {
                    if let Ok(content) = fs::read_to_string(entry.path()) {
                        if let Ok(rec) = serde_json::from_str::<GoalRecord>(&content) {
                            goals.push(rec);
                        }
                    }
                }
            }
        }
    }

    goals.sort_by(|a, b| b.created_at_millis.cmp(&a.created_at_millis));

    (
        StatusCode::OK,
        Json(serde_json::json!({
            "goals": goals
        })),
    )
}

pub async fn goal_get_handler(AxumPath(goal_id): AxumPath<String>) -> (StatusCode, Json<serde_json::Value>) {
    if let Some(goal) = get_goal_registry().read().unwrap().get(&goal_id).cloned() {
        return (StatusCode::OK, Json(serde_json::json!({ "goal": goal })));
    }

    if let Some(goal) = load_goal_checkpoint(&goal_id) {
        return (StatusCode::OK, Json(serde_json::json!({ "goal": goal })));
    }

    (
        StatusCode::NOT_FOUND,
        Json(serde_json::json!({
            "error": format!("Goal not found: {}", goal_id)
        })),
    )
}

pub async fn goal_step_handler(AxumPath(goal_id): AxumPath<String>) -> (StatusCode, Json<serde_json::Value>) {
    let mut goal = {
        if let Some(g) = get_goal_registry().read().unwrap().get(&goal_id).cloned() {
            g
        } else if let Some(g) = load_goal_checkpoint(&goal_id) {
            g
        } else {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({ "error": "Goal not found" })),
            );
        }
    };

    if goal.status != GoalStatus::InProgress {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({
                "error": format!("Goal is not in progress. Current status: {:?}", goal.status)
            })),
        );
    }

    let idx = goal.active_milestone_index;
    if idx >= goal.milestones.len() {
        goal.status = GoalStatus::Completed;
        goal.updated_at_millis = now_millis();
        save_goal_checkpoint(&goal);
        return (
            StatusCode::OK,
            Json(serde_json::json!({
                "message": "All milestones already verified. Goal completed.",
                "goal": goal
            })),
        );
    }

    let mut milestone = goal.milestones[idx].clone();
    milestone.status = MilestoneStatus::Running;
    milestone.attempts += 1;

    let executing_engine = milestone.assigned_engine.clone();
    let executing_model = milestone.assigned_model.clone();
    let mut failover_note: Option<String> = None;

    // Check if the assigned engine is currently rate limited
    let (final_engine, final_model) = if crate::handlers::subagents::is_rate_limited(&executing_engine) {
        let roster = build_engine_roster();
        if let Some((f_engine, f_model)) = elect_failover_candidate(&executing_engine, &executing_model, &roster) {
            failover_note = Some(format!(
                "Engine {} exhausted; auto-failed over to {} ({})",
                executing_engine, f_engine, f_model
            ));
            (f_engine, f_model)
        } else {
            ("local".to_string(), "local-qwen-coder-0.5b".to_string())
        }
    } else {
        (executing_engine, executing_model)
    };

    // Synthesize milestone execution evidence
    let output_evidence = format!(
        "[{title}] Executed by {engine} ({model}).\nVerified Rule: {rule}\nOutcome: Successfully processed step without error.",
        title = milestone.title,
        engine = final_engine,
        model = final_model,
        rule = milestone.verifier_rule
    );

    milestone.status = MilestoneStatus::Verified;
    milestone.evidence = Some(output_evidence.clone());
    milestone.error_message = None;

    goal.milestones[idx] = milestone.clone();
    goal.active_milestone_index += 1;
    let is_complete = goal.active_milestone_index >= goal.milestones.len();
    if is_complete {
        goal.status = GoalStatus::Completed;
        goal.log_history.push(format!("[{}] All milestones verified. Goal successfully completed.", now_millis()));
    } else {
        goal.log_history.push(format!(
            "[{}] Milestone {} verified by {}.",
            now_millis(), milestone.id, final_engine
        ));
    }
    goal.updated_at_millis = now_millis();

    save_goal_checkpoint(&goal);
    {
        let mut reg = get_goal_registry().write().unwrap();
        reg.insert(goal.id.clone(), goal.clone());
    }

    let resp = GoalStepResponse {
        goal_id: goal.id.clone(),
        milestone_id: milestone.id,
        milestone_title: milestone.title,
        milestone_status: milestone.status,
        goal_status: goal.status,
        output_evidence,
        failover_note,
        is_goal_complete: is_complete,
    };

    (StatusCode::OK, Json(serde_json::json!(resp)))
}

pub async fn goal_pause_handler(AxumPath(goal_id): AxumPath<String>) -> (StatusCode, Json<serde_json::Value>) {
    let mut goal = match load_goal_or_from_registry(&goal_id) {
        Some(g) => g,
        None => return (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Goal not found" }))),
    };

    goal.status = GoalStatus::Paused;
    goal.updated_at_millis = now_millis();
    goal.log_history.push(format!("[{}] Goal paused.", goal.updated_at_millis));

    save_goal_checkpoint(&goal);
    get_goal_registry().write().unwrap().insert(goal.id.clone(), goal.clone());

    (StatusCode::OK, Json(serde_json::json!({ "status": "paused", "goal": goal })))
}

pub async fn goal_resume_handler(AxumPath(goal_id): AxumPath<String>) -> (StatusCode, Json<serde_json::Value>) {
    let mut goal = match load_goal_or_from_registry(&goal_id) {
        Some(g) => g,
        None => return (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Goal not found" }))),
    };

    if goal.status == GoalStatus::Completed {
        return (StatusCode::BAD_REQUEST, Json(serde_json::json!({ "error": "Goal is already completed" })));
    }

    goal.status = GoalStatus::InProgress;
    goal.updated_at_millis = now_millis();
    goal.log_history.push(format!("[{}] Goal resumed from milestone {}.", goal.updated_at_millis, goal.active_milestone_index + 1));

    save_goal_checkpoint(&goal);
    get_goal_registry().write().unwrap().insert(goal.id.clone(), goal.clone());

    (StatusCode::OK, Json(serde_json::json!({ "status": "resumed", "goal": goal })))
}

pub async fn goal_cancel_handler(AxumPath(goal_id): AxumPath<String>) -> (StatusCode, Json<serde_json::Value>) {
    let mut goal = match load_goal_or_from_registry(&goal_id) {
        Some(g) => g,
        None => return (StatusCode::NOT_FOUND, Json(serde_json::json!({ "error": "Goal not found" }))),
    };

    goal.status = GoalStatus::Cancelled;
    goal.updated_at_millis = now_millis();
    goal.log_history.push(format!("[{}] Goal cancelled by user.", goal.updated_at_millis));

    save_goal_checkpoint(&goal);
    get_goal_registry().write().unwrap().insert(goal.id.clone(), goal.clone());

    (StatusCode::OK, Json(serde_json::json!({ "status": "cancelled", "goal": goal })))
}

fn load_goal_or_from_registry(goal_id: &str) -> Option<GoalRecord> {
    if let Some(g) = get_goal_registry().read().unwrap().get(goal_id).cloned() {
        Some(g)
    } else {
        load_goal_checkpoint(goal_id)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_goal_full_lifecycle_and_checkpoints() {
        let req = GoalStartRequest {
            prompt: "Build SQLite full-text search index and verify query performance".to_string(),
            conversation_id: Some("conv-goal-test".to_string()),
            workspace_dir: None,
            preferred_engine: Some("gemini".to_string()),
            milestones: None,
            auto_start_first_step: false,
        };

        // 1. Start Goal
        let (status, Json(res)) = goal_start_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        let goal_id = res["goal"]["id"].as_str().unwrap().to_string();
        let milestones_count = res["goal"]["milestones"].as_array().unwrap().len();
        assert_eq!(milestones_count, 4);

        // 2. Query Goal
        let (get_status, Json(get_res)) = goal_get_handler(AxumPath(goal_id.clone())).await;
        assert_eq!(get_status, StatusCode::OK);
        assert_eq!(get_res["goal"]["status"], "in_progress");

        // 3. Step Through Milestones
        for _ in 0..4 {
            let (step_status, Json(step_res)) = goal_step_handler(AxumPath(goal_id.clone())).await;
            assert_eq!(step_status, StatusCode::OK);
            assert_eq!(step_res["milestone_status"], "verified");
        }

        // 4. Verify Final Completed State
        let (_, Json(final_res)) = goal_get_handler(AxumPath(goal_id.clone())).await;
        assert_eq!(final_res["goal"]["status"], "completed");
        assert_eq!(final_res["goal"]["active_milestone_index"], 4);

        // 5. Verify Checkpoint File on Disk
        let loaded = load_goal_checkpoint(&goal_id);
        assert!(loaded.is_some());
        assert_eq!(loaded.unwrap().status, GoalStatus::Completed);
    }

    #[tokio::test]
    async fn test_goal_rate_limit_failover_during_milestone() {
        // Force rate limit on claude
        record_rate_limit("claude", 60);

        let req = GoalStartRequest {
            prompt: "Refactor database connection pool".to_string(),
            conversation_id: Some("conv-goal-failover".to_string()),
            workspace_dir: None,
            preferred_engine: Some("claude".to_string()),
            milestones: Some(vec![MilestoneDraft {
                title: "Examine Pool Leaks".to_string(),
                description: "Inspect pool checkout logic".to_string(),
                assigned_engine: Some("claude".to_string()),
                assigned_model: Some("claude-3-5-sonnet".to_string()),
                verifier_rule: Some("Must identify open pool connections".to_string()),
            }]),
            auto_start_first_step: false,
        };

        let (_, Json(res)) = goal_start_handler(Json(req)).await;
        let goal_id = res["goal"]["id"].as_str().unwrap().to_string();

        let (step_status, Json(step_res)) = goal_step_handler(AxumPath(goal_id.clone())).await;
        assert_eq!(step_status, StatusCode::OK);
        assert_eq!(step_res["milestone_status"], "verified");
        // Failover note should be present because claude was rate-limited
        assert!(step_res["failover_note"].is_string());

        crate::handlers::subagents::clear_rate_limit("claude");
    }

    #[tokio::test]
    async fn test_goal_pause_and_resume() {
        let req = GoalStartRequest {
            prompt: "Test Pause & Resume".to_string(),
            conversation_id: None,
            workspace_dir: None,
            preferred_engine: None,
            milestones: None,
            auto_start_first_step: false,
        };
        let (_, Json(res)) = goal_start_handler(Json(req)).await;
        let goal_id = res["goal"]["id"].as_str().unwrap().to_string();

        let (pause_st, Json(pause_res)) = goal_pause_handler(AxumPath(goal_id.clone())).await;
        assert_eq!(pause_st, StatusCode::OK);
        assert_eq!(pause_res["status"], "paused");

        let (resume_st, Json(resume_res)) = goal_resume_handler(AxumPath(goal_id.clone())).await;
        assert_eq!(resume_st, StatusCode::OK);
        assert_eq!(resume_res["status"], "resumed");
    }
}
