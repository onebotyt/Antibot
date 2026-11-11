use axum::{http::StatusCode, Json};
use serde::{Deserialize, Serialize};
use std::path::Path;
use antibot_native::hw_telemetry::collect_hardware_telemetry;
use antibot_native::model_runtime::{inspect_gguf_file, validate_model_runtime};

#[derive(Debug, Deserialize)]
pub struct ModelInspectRequest {
    pub file_path: String,
}

#[derive(Debug, Deserialize)]
pub struct ModelValidateRequest {
    pub file_path: String,
    #[serde(default = "default_context_length")]
    pub context_length: usize,
}

fn default_context_length() -> usize {
    2048
}

/// POST /api/models/gguf/inspect
///
/// Inspects a GGUF model binary and extracts architecture, layer count, context window, and quantization metadata.
pub async fn models_gguf_inspect_handler(
    Json(payload): Json<ModelInspectRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    let path = Path::new(&payload.file_path);
    match inspect_gguf_file(path) {
        Ok(meta) => (StatusCode::OK, Json(serde_json::to_value(meta).unwrap())),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": e })),
        ),
    }
}

/// POST /api/models/runtime/validate
///
/// Evaluates memory feasibility for offline LLM execution against device available RAM and core topology.
pub async fn models_runtime_validate_handler(
    Json(payload): Json<ModelValidateRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    let path = Path::new(&payload.file_path);
    let meta = match inspect_gguf_file(path) {
        Ok(m) => m,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({
                    "feasible": false,
                    "warning": e
                })),
            );
        }
    };

    let telem = collect_hardware_telemetry(None);
    let validation = validate_model_runtime(
        &meta,
        payload.context_length,
        telem.memory.available_mb,
        telem.online_cores_count,
    );

    (
        StatusCode::OK,
        Json(serde_json::to_value(validation).unwrap()),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Smart Model Router  — POST /api/models/route
//
// Ports the Kotlin SmartModelRouter.route() / resolveCandidateModel() logic
// to Rust so a single authoritative routing decision is made server-side and
// all agents (AGY, Local, Claude, Codex, OpenCode) simply ask the bridge.
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize, Clone)]
pub struct AvailableModel {
    pub id: String,
    #[serde(rename = "displayName", default)]
    pub display_name: String,
    /// "GEMINI" | "CLAUDE" | "LOCAL" | other
    #[serde(default)]
    pub family: String,
}

#[derive(Debug, Deserialize, Clone)]
pub struct ModelQuota {
    #[serde(rename = "modelId")]
    pub model_id: String,
    /// 0–100; 0 means exhausted
    pub percentage: i32,
}

/// User-configurable task-to-model mapping (mirrors Kotlin LlmManagerConfig).
#[derive(Debug, Deserialize, Clone)]
pub struct LlmManagerConfig {
    #[serde(rename = "architectureModel", default = "default_arch_model")]
    pub architecture_model: String,
    #[serde(rename = "codingModel", default = "default_coding_model")]
    pub coding_model: String,
    #[serde(rename = "fastQaModel", default = "default_fast_qa_model")]
    pub fast_qa_model: String,
    #[serde(rename = "shellModel", default = "default_shell_model")]
    pub shell_model: String,
    #[serde(rename = "fallbackModel", default = "default_fallback_model")]
    pub fallback_model: String,
    #[serde(rename = "preferredCli", default = "default_preferred_cli")]
    pub preferred_cli: String,
}

fn default_arch_model() -> String     { "claude".into() }
fn default_coding_model() -> String   { "claude".into() }
fn default_fast_qa_model() -> String  { "gemini".into() }
fn default_shell_model() -> String    { "local-qwen-coder-0.5b".into() }
fn default_fallback_model() -> String { "gemini".into() }
fn default_preferred_cli() -> String  { "auto".into() }

#[derive(Debug, Deserialize)]
pub struct ModelRouteRequest {
    pub prompt: String,
    #[allow(dead_code)]
    #[serde(rename = "hasAttachments", default)]
    pub has_attachments: bool,  // reserved for future attachment-aware routing
    #[serde(rename = "isOnline", default = "bool_true")]
    pub is_online: bool,
    #[serde(rename = "availableModels", default)]
    pub available_models: Vec<AvailableModel>,
    #[serde(default)]
    pub quotas: Vec<ModelQuota>,
    #[serde(rename = "currentSelectedModelId", default = "default_auto")]
    pub current_selected_model_id: String,
    #[serde(default)]
    pub config: Option<LlmManagerConfig>,
}

fn bool_true() -> bool   { true }
fn default_auto() -> String { "auto".into() }

/// 5 Supported Multi-CLI Agents
#[derive(Debug, Serialize, Clone, Copy, PartialEq, Eq)]
pub enum AgentKind {
    #[serde(rename = "LOCAL")]
    Local,
    #[serde(rename = "AGY")]
    Agy,
    #[serde(rename = "CLAUDE")]
    Claude,
    #[serde(rename = "CODEX")]
    Codex,
    #[serde(rename = "OPENCODE")]
    OpenCode,
}

#[derive(Debug, Serialize)]
pub struct ModelRouteResponse {
    #[serde(rename = "targetModelId")]
    pub target_model_id: String,
    #[serde(rename = "targetAgent")]
    pub target_agent: AgentKind,
    #[serde(rename = "modelDisplayName")]
    pub model_display_name: String,
    /// e.g. "CODING" | "FAST_QA" | "LOCAL_SHELL" | …
    pub reason: String,
    pub explanation: String,
    #[serde(rename = "managerBrain")]
    pub manager_brain: String,
}

// ── helpers ──────────────────────────────────────────────────────────────────

fn is_auto(model_id: &str) -> bool {
    let lower = model_id.trim().to_lowercase();
    lower.is_empty()
        || lower == "auto"
        || lower == "smart-router"
        || lower == "auto-manager"
}

fn is_local_model(model_id: &str) -> bool {
    model_id.starts_with("local-") || model_id == "local-model-only" || model_id == "cli:local"
}

fn resolve_agent_for_target(target_id: &str, preferred_cli: &str) -> AgentKind {
    let lower = target_id.to_lowercase();
    if lower == "cli:local" || is_local_model(&lower) {
        return AgentKind::Local;
    }
    if lower == "cli:claude" {
        return AgentKind::Claude;
    }
    if lower == "cli:codex" {
        return AgentKind::Codex;
    }
    if lower == "cli:opencode" {
        return AgentKind::OpenCode;
    }
    if lower == "cli:agy" {
        return AgentKind::Agy;
    }

    match preferred_cli.trim().to_uppercase().as_str() {
        "LOCAL" => AgentKind::Local,
        "CLAUDE" => AgentKind::Claude,
        "CODEX" => AgentKind::Codex,
        "OPENCODE" => AgentKind::OpenCode,
        "AGY" => AgentKind::Agy,
        _ => {
            if lower.contains("claude") {
                AgentKind::Claude
            } else if lower.contains("codex") || lower.contains("gpt") {
                AgentKind::Codex
            } else {
                AgentKind::Agy
            }
        }
    }
}

#[allow(dead_code)]
fn short_pill(model_id: &str, display_name: &str) -> String {
    if is_local_model(model_id) {
        "Auto ➔ Qwen".into()
    } else if model_id.to_lowercase().contains("claude") || model_id == "cli:claude" {
        "Auto ➔ Claude".into()
    } else if model_id.to_lowercase().contains("codex") || model_id == "cli:codex" {
        "Auto ➔ Codex".into()
    } else if model_id.to_lowercase().contains("opencode") || model_id == "cli:opencode" {
        "Auto ➔ OpenCode".into()
    } else if model_id.to_lowercase().contains("flash") {
        "Auto ➔ Flash".into()
    } else if model_id.to_lowercase().contains("pro") {
        "Auto ➔ Gemini Pro".into()
    } else {
        format!("Auto ➔ {}", display_name)
    }
}

/// Mirrors Kotlin SmartModelRouter.resolveCandidateModel().
fn resolve_candidate(
    pattern: &str,
    available: &[AvailableModel],
    quotas: &[ModelQuota],
) -> Option<AvailableModel> {
    if pattern.trim().is_empty() {
        return None;
    }
    let lower = pattern.to_lowercase();

    // Check CLI aliases
    if lower.starts_with("cli:") {
        let display = match lower.as_str() {
            "cli:claude" => "Claude Code CLI",
            "cli:codex" => "Codex CLI",
            "cli:opencode" => "OpenCode CLI",
            "cli:local" => "Local Qwen CLI",
            _ => "Antigravity CLI",
        };
        return Some(AvailableModel {
            id: pattern.to_string(),
            display_name: display.to_string(),
            family: if lower == "cli:claude" { "CLAUDE".into() } else { "GEMINI".into() },
        });
    }

    // 1. Exact ID match
    let m = available.iter().find(|m| m.id.to_lowercase() == lower)
        .or_else(|| {
            // 2. Keyword / family match
            match lower.as_str() {
                "claude" => available.iter().find(|m| m.family.to_uppercase() == "CLAUDE")
                    .or_else(|| available.iter().find(|m| m.id.to_lowercase().contains("claude"))),
                "gemini" | "gemini-flash" | "flash" =>
                    available.iter().find(|m| m.id.to_lowercase().contains("flash") || m.display_name.to_lowercase().contains("flash"))
                    .or_else(|| available.iter().find(|m| m.family.to_uppercase() == "GEMINI")),
                "gemini-pro" | "pro" =>
                    available.iter().find(|m| m.id.to_lowercase().contains("pro") || m.display_name.to_lowercase().contains("pro")),
                _ =>
                    available.iter().find(|m| m.id.to_lowercase().contains(&lower) || m.display_name.to_lowercase().contains(&lower)),
            }
        });

    m.cloned().and_then(|model| {
        // Check quota
        let exhausted = quotas.iter().any(|q| q.model_id == model.id && q.percentage <= 0);
        if exhausted { None } else { Some(model) }
    })
}

fn local_qwen() -> ModelRouteResponse {
    ModelRouteResponse {
        target_model_id: "local-qwen-coder-0.5b".into(),
        target_agent: AgentKind::Local,
        model_display_name: "Qwen 2.5 Coder 0.5B".into(),
        reason: "LOCAL_SHELL".into(),
        explanation: "Running on Local Qwen 2.5 Coder (100% On-Device Brain)".into(),
        manager_brain: "Qwen 2.5 Coder 0.5B (On-Device Neural Engine)".into(),
    }
}

// ── handler ──────────────────────────────────────────────────────────────────

/// POST /api/models/route
///
/// Stateless Smart Model Router powered by Qwen 2.5 Coder 0.5B On-Device Brain.
/// Accepts prompt, available models, quotas, and config to select and switch between
/// 5 CLI managers (Antigravity, Local Qwen, Claude, Codex, OpenCode).
pub async fn models_route_handler(
    Json(req): Json<ModelRouteRequest>,
) -> (StatusCode, Json<ModelRouteResponse>) {
    let config = req.config.unwrap_or_default();
    let available = &req.available_models;
    let quotas = &req.quotas;
    let brain = "Qwen 2.5 Coder 0.5B (On-Device Neural Engine)";

    // Step 1 — Manual override: user locked onto a specific model or CLI
    if !is_auto(&req.current_selected_model_id) {
        let mid = &req.current_selected_model_id;
        let is_local = is_local_model(mid);
        let display = if is_local {
            "Qwen 2.5 Coder 0.5B".into()
        } else if mid.starts_with("cli:") {
            match mid.as_str() {
                "cli:claude" => "Claude Code CLI",
                "cli:codex" => "Codex CLI",
                "cli:opencode" => "OpenCode CLI",
                "cli:local" => "Local Qwen CLI",
                _ => "Antigravity CLI",
            }.into()
        } else {
            available.iter()
                .find(|m| &m.id == mid)
                .map(|m| m.display_name.clone())
                .unwrap_or_else(|| mid.clone())
        };
        let agent = resolve_agent_for_target(mid, &config.preferred_cli);
        return (StatusCode::OK, Json(ModelRouteResponse {
            target_model_id: mid.clone(),
            target_agent: agent,
            model_display_name: display,
            reason: "MANUAL".into(),
            explanation: "Manually selected model / CLI manager".into(),
            manager_brain: brain.into(),
        }));
    }

    // Step 2 — Offline fallback
    if !req.is_online {
        return (StatusCode::OK, Json(ModelRouteResponse {
            target_model_id: "local-qwen-coder-0.5b".into(),
            target_agent: AgentKind::Local,
            model_display_name: "Qwen 2.5 Coder 0.5B".into(),
            reason: "LOCAL_OFFLINE".into(),
            explanation: "Network offline · Running 100% on-device neural engine".into(),
            manager_brain: brain.into(),
        }));
    }

    let clean = req.prompt.trim().to_lowercase();

    // Step 3 — Shell / Terminal / Port / Workspace commands
    let is_terminal_cmd = ["$", "bash ", "sh ", "npm ", "pnpm ", "yarn ", "git ",
        "node ", "python ", "curl ", "ls ", "cat ", "grep ", "mkdir ", "rm "]
        .iter().any(|p| clean.starts_with(p));
    let is_port_process = ["check port", "kill port", "check ports", "kill process", "free port"]
        .iter().any(|k| clean.contains(k))
        || regex_contains_port(&clean);
    let is_workspace_inspect = ["grep search", "search files", "list files",
        "inbuilt browser", "preview html", "preview website"]
        .iter().any(|k| clean.contains(k));

    if is_terminal_cmd || is_port_process || is_workspace_inspect {
        let shell_is_local = config.shell_model.to_lowercase().starts_with("local") || config.shell_model == "cli:local";
        if shell_is_local {
            let reason = if is_port_process { "LOCAL_PORT_PROCESS" }
                else if is_workspace_inspect { "LOCAL_WORKSPACE" }
                else { "LOCAL_SHELL" };
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: "local-qwen-coder-0.5b".into(),
                target_agent: AgentKind::Local,
                model_display_name: "Qwen 2.5 Coder 0.5B".into(),
                reason: reason.into(),
                explanation: "Executed locally on device via Qwen Brain (0 tokens, instant bash/port execution)".into(),
                manager_brain: brain.into(),
            }));
        } else if let Some(m) = resolve_candidate(&config.shell_model, available, quotas) {
            let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: m.id.clone(),
                target_agent: agent,
                model_display_name: m.display_name.clone(),
                reason: "LOCAL_SHELL".into(),
                explanation: format!("Executed via configured shell manager: {}", m.display_name),
                manager_brain: brain.into(),
            }));
        }
    }

    // Shared fallback model (used by several steps below)
    let fallback = resolve_candidate(&config.fallback_model, available, quotas)
        .or_else(|| resolve_candidate("flash", available, quotas))
        .or_else(|| resolve_candidate("gemini", available, quotas));

    // Step 4 — Security audit / deep architecture
    let is_security = ["security audit", "owasp", "vulnerability", "pentest",
        "penetration test", "sql injection", "xss", "csrf", "hardening"]
        .iter().any(|k| clean.contains(k));
    let is_arch_deep = is_security || ["architecture", "architect", "design pattern",
        "system design", "plan", "step by step"]
        .iter().any(|k| clean.contains(k));

    if is_arch_deep {
        if let Some(m) = resolve_candidate(&config.architecture_model, available, quotas) {
            let reason = if is_security { "CYBER_SECURITY" } else { "DEEP_REASONING" };
            let explanation = if is_security {
                format!("Security Audit · Routed by Qwen Brain to {}", m.display_name)
            } else {
                format!("Architectural & Deep Logic · Routed by Qwen Brain to {}", m.display_name)
            };
            let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: m.id.clone(),
                target_agent: agent,
                model_display_name: m.display_name.clone(),
                reason: reason.into(),
                explanation,
                manager_brain: brain.into(),
            }));
        } else if let Some(m) = fallback.as_ref() {
            let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: m.id.clone(),
                target_agent: agent,
                model_display_name: m.display_name.clone(),
                reason: "QUOTA_FALLBACK".into(),
                explanation: format!("Primary architecture model quota exhausted · Auto-switched to {}", m.display_name),
                manager_brain: brain.into(),
            }));
        }
    }

    // Step 5 — Complex coding / debugging
    let newlines = req.prompt.chars().filter(|&c| c == '\n').count();
    let is_coding = ["refactor", "traceback", "stack trace", "nullpointerexception",
        "deadlock", "memory leak", "compile error", "syntax error", "function", "class"]
        .iter().any(|k| clean.contains(k))
        || req.prompt.len() > 600
        || newlines > 12;

    if is_coding {
        if let Some(m) = resolve_candidate(&config.coding_model, available, quotas) {
            let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: m.id.clone(),
                target_agent: agent,
                model_display_name: m.display_name.clone(),
                reason: "CODING".into(),
                explanation: format!("Coding & Debugging · Routed by Qwen Brain to {}", m.display_name),
                manager_brain: brain.into(),
            }));
        } else if let Some(m) = fallback.as_ref() {
            let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
            return (StatusCode::OK, Json(ModelRouteResponse {
                target_model_id: m.id.clone(),
                target_agent: agent,
                model_display_name: m.display_name.clone(),
                reason: "QUOTA_FALLBACK".into(),
                explanation: format!("Coding model limit reached · Auto-switched to {}", m.display_name),
                manager_brain: brain.into(),
            }));
        }
    }

    // Step 6 — Fast Q&A
    if let Some(m) = resolve_candidate(&config.fast_qa_model, available, quotas) {
        let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
        return (StatusCode::OK, Json(ModelRouteResponse {
            target_model_id: m.id.clone(),
            target_agent: agent,
            model_display_name: m.display_name.clone(),
            reason: "FAST_QA".into(),
            explanation: format!("Fast Q&A · Routed by Qwen Brain to {}", m.display_name),
            manager_brain: brain.into(),
        }));
    }

    // Step 7 — General cloud fallback
    if let Some(m) = fallback {
        let agent = resolve_agent_for_target(&m.id, &config.preferred_cli);
        return (StatusCode::OK, Json(ModelRouteResponse {
            target_model_id: m.id.clone(),
            target_agent: agent,
            model_display_name: m.display_name.clone(),
            reason: "QUOTA_FALLBACK".into(),
            explanation: format!("Routed by Qwen Brain to fallback: {}", m.display_name),
            manager_brain: brain.into(),
        }));
    }

    // Step 8 — Ultimate on-device fallback
    (StatusCode::OK, Json(local_qwen()))
}

impl Default for LlmManagerConfig {
    fn default() -> Self {
        Self {
            architecture_model: default_arch_model(),
            coding_model: default_coding_model(),
            fast_qa_model: default_fast_qa_model(),
            shell_model: default_shell_model(),
            fallback_model: default_fallback_model(),
            preferred_cli: default_preferred_cli(),
        }
    }
}


/// Quick heuristic: does the text contain "port <number>" pattern?
fn regex_contains_port(text: &str) -> bool {
    let bytes = text.as_bytes();
    let port_prefix = b"port ";
    if let Some(pos) = bytes.windows(port_prefix.len()).position(|w| w == port_prefix) {
        let after = &bytes[pos + port_prefix.len()..];
        let digits: usize = after.iter().take(5).take_while(|b| b.is_ascii_digit()).count();
        return digits >= 2;
    }
    false
}


#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::path::PathBuf;

    struct CleanupGuard(PathBuf);
    impl Drop for CleanupGuard {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn write_test_gguf_file() -> (PathBuf, CleanupGuard) {
        let path = std::env::temp_dir().join(format!("test_model_{}.gguf", std::process::id()));
        let mut file = File::create(&path).unwrap();

        // Magic "GGUF"
        file.write_all(&antibot_native::model_runtime::GGUF_MAGIC)
            .unwrap();
        // Version 3
        file.write_all(&3u32.to_le_bytes()).unwrap();
        // Tensor count
        file.write_all(&64u64.to_le_bytes()).unwrap();
        // KV count
        file.write_all(&2u64.to_le_bytes()).unwrap();

        // KV 1: general.architecture = "llama"
        let key1 = "general.architecture";
        file.write_all(&(key1.len() as u64).to_le_bytes()).unwrap();
        file.write_all(key1.as_bytes()).unwrap();
        file.write_all(&8u32.to_le_bytes()).unwrap(); // String type
        let val1 = "llama";
        file.write_all(&(val1.len() as u64).to_le_bytes()).unwrap();
        file.write_all(val1.as_bytes()).unwrap();

        // KV 2: llama.context_length = 4096
        let key2 = "llama.context_length";
        file.write_all(&(key2.len() as u64).to_le_bytes()).unwrap();
        file.write_all(key2.as_bytes()).unwrap();
        file.write_all(&4u32.to_le_bytes()).unwrap(); // Uint32 type
        file.write_all(&4096u32.to_le_bytes()).unwrap();

        file.flush().unwrap();
        let guard = CleanupGuard(path.clone());
        (path, guard)
    }

    #[tokio::test]
    async fn test_models_handlers_synthetic_gguf() {
        let (path, _guard) = write_test_gguf_file();
        let path_str = path.to_str().unwrap().to_string();

        // 1. Test inspect handler
        let (status, Json(val)) =
            models_gguf_inspect_handler(Json(ModelInspectRequest { file_path: path_str.clone() })).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(val["magic"], "GGUF");
        assert_eq!(val["architecture"], "llama");
        assert_eq!(val["context_length"], 4096);

        // 2. Test validate handler
        let (status, Json(val_res)) = models_runtime_validate_handler(Json(ModelValidateRequest {
            file_path: path_str,
            context_length: 2048,
        }))
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(val_res["recommended_threads"].as_u64().unwrap() >= 1);
    }

    // ── SmartModelRouter tests ────────────────────────────────────────────────

    fn claude() -> AvailableModel {
        AvailableModel { id: "claude-3-5-sonnet".into(), display_name: "Claude 3.5 Sonnet".into(), family: "CLAUDE".into() }
    }
    fn flash() -> AvailableModel {
        AvailableModel { id: "gemini-flash".into(), display_name: "Gemini Flash".into(), family: "GEMINI".into() }
    }
    fn models() -> Vec<AvailableModel> { vec![claude(), flash()] }

    fn make_req(prompt: &str, online: bool) -> ModelRouteRequest {
        ModelRouteRequest {
            prompt: prompt.into(),
            has_attachments: false,
            is_online: online,
            available_models: models(),
            quotas: vec![],
            current_selected_model_id: "auto".into(),
            config: None,
        }
    }

    #[tokio::test]
    async fn test_route_offline_fallback() {
        let (status, Json(res)) = models_route_handler(Json(make_req("hello", false))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "LOCAL_OFFLINE");
        assert!(matches!(res.target_agent, AgentKind::Local));
    }

    #[tokio::test]
    async fn test_route_manual_override() {
        let mut req = make_req("hello", true);
        req.current_selected_model_id = "gemini-flash".into();
        let (status, Json(res)) = models_route_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "MANUAL");
        assert_eq!(res.target_model_id, "gemini-flash");
    }

    #[tokio::test]
    async fn test_route_terminal_command_local() {
        let (status, Json(res)) = models_route_handler(Json(make_req("$ ls -la", true))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "LOCAL_SHELL");
        assert!(matches!(res.target_agent, AgentKind::Local));
    }

    #[tokio::test]
    async fn test_route_port_heuristic() {
        let (status, Json(res)) = models_route_handler(Json(make_req("check port 3000", true))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "LOCAL_PORT_PROCESS");
    }

    #[tokio::test]
    async fn test_route_security_audit() {
        let (status, Json(res)) = models_route_handler(Json(make_req("do a security audit and owasp check", true))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "CYBER_SECURITY");
        assert_eq!(res.target_model_id, "claude-3-5-sonnet");
    }

    #[tokio::test]
    async fn test_route_coding() {
        let (status, Json(res)) = models_route_handler(Json(make_req("refactor this function", true))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "CODING");
        assert_eq!(res.target_model_id, "claude-3-5-sonnet");
    }

    #[tokio::test]
    async fn test_route_fast_qa() {
        let (status, Json(res)) = models_route_handler(Json(make_req("what time is it", true))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.reason, "FAST_QA");
        assert_eq!(res.target_model_id, "gemini-flash");
    }

    #[test]
    fn test_regex_contains_port() {
        assert!(regex_contains_port("check port 3000"));
        assert!(regex_contains_port("port 80 is blocked"));
        assert!(!regex_contains_port("no port here"));
        assert!(!regex_contains_port("port 1")); // only 1 digit, need >= 2
    }
}
