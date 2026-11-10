use axum::{
    routing::{any, get, post, put},
    Router,
};
use clap::{Parser, Subcommand};
use std::{
    net::SocketAddr,
    path::PathBuf,
    sync::Arc,
    time::Instant,
};
use tokio::sync::broadcast;
use tower_http::cors::{Any, CorsLayer};
use tower_http::services::ServeDir;
use tracing::{info, Level};
use tracing_subscriber::FmtSubscriber;

mod handlers;
mod models;
mod mcp;
mod security;
mod supervisor;
mod watcher;
mod ws;

use security::SecurityManager;

#[derive(Subcommand, Debug)]
pub enum Commands {
    /// Backup all AGY chats and credentials to a compressed zip archive
    #[command(name = "backup-chats")]
    BackupChats {
        #[arg(short, long)]
        output: Option<String>,
    },
    /// Restore all AGY chats from an archive
    #[command(name = "restore-chats")]
    RestoreChats {
        #[arg(short, long)]
        file: String,
        #[arg(short, long)]
        target: Option<String>,
    },
    /// Backup workspace projects to a zip archive
    #[command(name = "backup-projects")]
    BackupProjects {
        #[arg(short, long)]
        output: Option<String>,
    },
    /// Restore workspace projects from a zip archive
    #[command(name = "restore-projects")]
    RestoreProjects {
        #[arg(short, long)]
        file: String,
        #[arg(short, long)]
        target: Option<String>,
    },
}

#[derive(Parser, Debug)]
#[command(author, version = "1.0.64", about = "AntiBot High-Performance Rust IDE Bridge & Daemon")]
pub struct Args {
    #[command(subcommand)]
    pub command: Option<Commands>,

    #[arg(short = 'p', long, default_value_t = 1234)]
    pub port: u16,

    #[arg(long = "hub-port", default_value_t = 1235)]
    pub hub_port: u16,

    #[arg(short = 't', long, default_value = "")]
    pub token: String,

    #[arg(short = 'f', long, default_value_t = false)]
    pub force: bool,

    #[arg(long, default_value = "127.0.0.1")]
    pub bind: String,

    #[arg(short = 'd', long)]
    pub dir: Option<String>,

    #[arg(long)]
    pub bin: Option<String>,

    #[arg(long = "tz-offset", default_value_t = 0)]
    pub tz_offset: i32,

    #[arg(long = "no-hub", default_value_t = false)]
    pub no_hub: bool,

    #[arg(long = "no-claude", default_value_t = false)]
    pub no_claude: bool,
}

pub struct AppState {
    pub port: u16,
    pub hub_port: u16,
    pub workspace_dir: PathBuf,
    pub security: SecurityManager,
    pub start_time: Instant,
    pub tx: broadcast::Sender<String>,
    pub supervisor: Arc<supervisor::PtySupervisor>,
    pub watcher: Arc<watcher::FsWatcher>,
    pub mcp_supervisor: Arc<mcp::McpSupervisor>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());

    // Handle CLI subcommands if specified
    if let Some(cmd) = args.command {
        match cmd {
            Commands::BackupChats { output } => {
                println!("📦 Starting AntiBot Native Rust Chat Backup...");
                match handlers::archive::run_backup_chats(output) {
                    Ok(res) => {
                        println!("✅ Chat backup completed successfully!");
                        println!("📁 Archive: {}", res.path);
                        println!("📄 Files: {} ({} bytes)", res.total_files, res.total_bytes);
                        return Ok(());
                    }
                    Err(e) => {
                        eprintln!("❌ Backup failed: {}", e);
                        std::process::exit(1);
                    }
                }
            }
            Commands::RestoreChats { file, target } => {
                println!("📦 Restoring AntiBot Chats from: {}", file);
                match handlers::archive::run_restore_chats(&file, target) {
                    Ok(count) => {
                        println!("✅ Restored {} files successfully!", count);
                        return Ok(());
                    }
                    Err(e) => {
                        eprintln!("❌ Restore failed: {}", e);
                        std::process::exit(1);
                    }
                }
            }
            Commands::BackupProjects { output } => {
                println!("📦 Backing up workspace projects...");
                let ws = PathBuf::from(&home).join(".gemini/antigravity-cli/scratch");
                match handlers::archive::run_backup_projects(&ws, output, None) {
                    Ok(res) => {
                        println!("✅ Project backup completed successfully!");
                        println!("📁 Archive: {}", res.path);
                        println!("📄 Files: {} ({} bytes)", res.total_files, res.total_bytes);
                        return Ok(());
                    }
                    Err(e) => {
                        eprintln!("❌ Backup failed: {}", e);
                        std::process::exit(1);
                    }
                }
            }
            Commands::RestoreProjects { file, target } => {
                let ws = target
                    .map(PathBuf::from)
                    .unwrap_or_else(|| PathBuf::from(&home).join(".gemini/antigravity-cli/scratch"));
                println!("📦 Restoring projects to: {}", ws.display());
                match handlers::archive::run_restore_projects(&file, &ws) {
                    Ok(count) => {
                        println!("✅ Restored {} files successfully!", count);
                        return Ok(());
                    }
                    Err(e) => {
                        eprintln!("❌ Restore failed: {}", e);
                        std::process::exit(1);
                    }
                }
            }
        }
    }

    let subscriber = FmtSubscriber::builder()
        .with_max_level(Level::INFO)
        .finish();
    tracing::subscriber::set_global_default(subscriber).ok();

    let default_workspace = PathBuf::from(&home).join(".gemini/antigravity-cli/scratch");
    let workspace_dir = args.dir.map(PathBuf::from).unwrap_or(default_workspace);

    if !workspace_dir.exists() {
        let _ = std::fs::create_dir_all(&workspace_dir);
    }

    let (tx, _) = broadcast::channel(100);
    let supervisor = Arc::new(supervisor::PtySupervisor::new());
    let watcher = watcher::FsWatcher::new(tx.clone());
    let _ = watcher.watch(&workspace_dir);
    let mcp_supervisor = Arc::new(mcp::McpSupervisor::new());

    let state = Arc::new(AppState {
        port: args.port,
        hub_port: args.hub_port,
        workspace_dir: workspace_dir.clone(),
        security: SecurityManager::new(args.token.clone()),
        start_time: Instant::now(),
        tx,
        supervisor,
        watcher,
        mcp_supervisor,
    });

    let artifacts_dir = PathBuf::from(&home).join(".gemini/antigravity-cli/brain");
    let attachments_dir = PathBuf::from(&home).join(".gemini/antigravity-cli/attachments");

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        // Health & Status
        .route("/api/health", get(handlers::health::health_handler))
        .route("/api/status", get(handlers::health::status_handler))
        .route("/api/server/info", get(handlers::health::server_info_handler))
        .route("/api/shutdown", post(handlers::health::shutdown_handler))
        .route("/api/models", get(handlers::health::models_handler))
        .route("/api/models/refresh", get(handlers::health::models_handler))
        .route("/api/quotas", get(handlers::health::quotas_handler))
        .route("/api/quotas/refresh", get(handlers::health::quotas_handler))
        .route("/api/instances", get(handlers::health::instances_handler))
        .route("/api/instances/:id/terminate", post(handlers::health::terminate_instance_handler))
        .route("/api/abort", post(handlers::health::abort_handler))
        .route("/api/agy/cli", get(handlers::health::agy_cli_handler))
        .route("/api/agy/cli/install", post(handlers::health::agy_cli_install_handler))
        // Filesystem & Explorer
        .route("/api/fs/roots", get(handlers::fs::fs_roots_handler))
        .route("/api/fs/browse", get(handlers::fs::fs_browse_handler))
        .route("/api/tree", get(handlers::fs::file_tree_handler))
        .route("/api/file/read", get(handlers::fs::file_read_handler))
        .route("/api/file/save", post(handlers::fs::file_save_handler))
        .route("/api/file/patch", post(handlers::fs::file_patch_handler))
        .route("/api/file/create", post(handlers::fs::file_create_handler))
        .route("/api/file/copy", post(handlers::fs::file_copy_handler))
        .route("/api/file/delete", post(handlers::fs::file_delete_handler))
        .route("/api/file/rename", post(handlers::fs::file_rename_handler))
        .route("/api/search", get(handlers::fs::file_search_handler))
        .route("/api/fs/search", get(handlers::fs::file_search_handler))
        .route("/api/fs/watches", get(handlers::fs::fs_watches_handler))
        .route("/api/fs/watch", post(handlers::fs::fs_watch_handler))
        .route("/api/fs/unwatch", post(handlers::fs::fs_unwatch_handler))
        .route(
            "/api/mcp/config",
            get(handlers::fs::mcp_config_get_handler).post(handlers::fs::mcp_config_save_handler),
        )
        // MCP Streaming RPC Supervisor & Tool Sandbox
        .route("/api/mcp/servers", get(mcp::handlers::mcp_list_servers_handler))
        .route("/api/mcp/tools", get(mcp::handlers::mcp_list_tools_handler))
        .route("/api/mcp/call", post(mcp::handlers::mcp_call_tool_handler))
        .route("/api/mcp/restart", post(mcp::handlers::mcp_restart_server_handler))
        .route("/api/mcp/toggle", post(mcp::handlers::mcp_toggle_server_handler))
        // Projects
        .route("/api/projects", get(handlers::projects::projects_handler))
        .route("/api/projects/add", post(handlers::projects::projects_add_handler))
        .route("/api/projects/remove", post(handlers::projects::projects_remove_handler))
        .route("/api/projects/create", post(handlers::projects::create_project_handler))
        // Git
        .route("/api/git/status", get(handlers::git::git_status_handler))
        .route("/api/git/branches", get(handlers::git::git_branches_handler))
        .route("/api/git/diff", get(handlers::git::git_diff_handler))
        .route("/api/git/diff/compute", post(handlers::git::compute_diff_handler))
        .route("/api/diff", post(handlers::git::compute_diff_handler))
        .route("/api/diff/apply", post(handlers::git::apply_diff_handler))
        .route("/api/git/diff/apply", post(handlers::git::apply_diff_handler))
        .route("/api/git/merge", post(handlers::git::git_merge_handler))
        .route("/api/git/conflict/resolve", post(handlers::git::git_conflict_resolve_handler))
        .route("/api/git/log", get(handlers::git::git_log_handler))
        .route("/api/git/commit/details", get(handlers::git::git_commit_details_handler))
        .route("/api/git/commit/diff", get(handlers::git::git_commit_diff_handler))
        .route("/api/git/commit/content", get(handlers::git::git_commit_content_handler))
        .route("/api/git/init", post(handlers::git::git_init_handler))
        .route("/api/git/clone", post(handlers::git::git_clone_handler))
        .route("/api/git/stage", post(handlers::git::git_stage_handler))
        .route("/api/git/unstage", post(handlers::git::git_unstage_handler))
        .route("/api/git/discard", post(handlers::git::git_discard_handler))
        .route("/api/git/commit", post(handlers::git::git_commit_handler))
        .route("/api/git/push", post(handlers::git::git_push_handler))
        .route("/api/git/pull", post(handlers::git::git_pull_handler))
        .route("/api/git/checkout", post(handlers::git::git_checkout_handler))
        .route("/api/git/branch/create", post(handlers::git::git_checkout_handler))
        .route(
            "/api/git/config",
            get(handlers::git::git_config_get_handler).post(handlers::git::git_config_set_handler),
        )
        // LSP Diagnostics & Auto-Completion Gateway
        .route("/api/lsp/complete", post(handlers::lsp::lsp_complete_handler))
        .route("/api/lsp/symbols", post(handlers::lsp::lsp_symbols_handler))
        .route("/api/lsp/diagnostics", post(handlers::lsp::lsp_diagnostics_handler))
        // Multi-Agent Consensus, DAG Scheduling & Task Delegation
        .route("/api/agents/dag", post(handlers::agents::dag_schedule_handler))
        .route("/api/agents/consensus", post(handlers::agents::consensus_handler))
        // Fast Semantic Vector Index, RAG Cache & Tokenizer
        .route("/api/ai/similarity", post(handlers::ai::similarity_handler))
        .route("/api/ai/rag/search", post(handlers::ai::rag_search_handler))
        .route("/api/ai/tokens", post(handlers::ai::tokens_handler))
        .route("/api/ai/intent/classify", post(handlers::ai::intent_classify_handler))
        // Real-Time Audio DSP, Ring-Buffer & Spectral Telemetry
        .route("/api/audio/telemetry", post(handlers::audio::audio_telemetry_handler))
        .route("/api/audio/voice/analyze", post(handlers::audio::voice_analyze_handler))
        .route("/ws/audio/telemetry", get(handlers::audio::ws_audio_telemetry_handler))
        // Antigravity Hub Diagnostics & Call Telemetry
        .route("/api/agy/hub/diag", get(handlers::diag::agy_hub_diag_handler))
        // Antigravity Cloud Agents & Reverse Proxy Preview
        .route("/api/cloud/status", get(handlers::cloud::cloud_status_handler))
        .route("/api/cloud/preview/register", post(handlers::cloud::cloud_register_route_handler))
        .route("/api/cloud/preview/routes", get(handlers::cloud::cloud_routes_handler))
        .route("/api/cloud/preview/proxy", post(handlers::cloud::cloud_preview_proxy_handler))
        .route("/api/cloud/ports/scan", post(handlers::cloud::cloud_scan_ports_handler))
        .route("/api/cloud/preview/:port", any(handlers::cloud::cloud_reverse_proxy_gateway))
        .route("/api/cloud/preview/:port/*path", any(handlers::cloud::cloud_reverse_proxy_gateway))
        // OpenAI Codex Protocol & App-Server Daemon
        .route("/api/codex/status", get(handlers::codex::codex_status_handler))
        .route("/api/codex/start", post(handlers::codex::codex_start_handler))
        .route("/api/codex/restart", post(handlers::codex::codex_restart_handler))
        .route("/api/codex/rpc", post(handlers::codex::codex_rpc_handler))
        .route("/api/codex/requests", get(handlers::codex::codex_requests_handler))
        .route("/api/codex/requests/:key/reply", post(handlers::codex::codex_reply_handler))
        .route("/api/codex/ws", get(handlers::codex::codex_ws_handler))
        .route("/api/codex/turns-meta", get(handlers::codex::codex_turns_meta_handler))
        .route("/api/codex/cli/job", get(handlers::codex::codex_cli_job_handler))
        .route("/api/codex/cli/:action", post(handlers::codex::codex_cli_start_handler))
        // OpenCode Multi-Provider & Local Server Gateway
        .route("/api/opencode/status", get(handlers::opencode::opencode_status_handler))
        .route("/api/opencode/start", post(handlers::opencode::opencode_start_handler))
        .route("/api/opencode/restart", post(handlers::opencode::opencode_restart_handler))
        .route(
            "/api/opencode/custom-providers",
            get(handlers::opencode::opencode_get_custom_providers_handler)
                .post(handlers::opencode::opencode_create_custom_provider_handler),
        )
        .route(
            "/api/opencode/custom-providers/:id",
            put(handlers::opencode::opencode_update_custom_provider_handler)
                .delete(handlers::opencode::opencode_delete_custom_provider_handler),
        )
        .route(
            "/api/opencode/custom-providers/fetch-models",
            post(handlers::opencode::opencode_fetch_models_handler),
        )
        .route(
            "/api/opencode/oauth-callback",
            post(handlers::opencode::opencode_oauth_callback_handler),
        )
        .route("/api/opencode/credentials", get(handlers::opencode::opencode_credentials_handler))
        .route("/api/opencode/mcp", get(handlers::opencode::opencode_get_mcp_handler))
        .route(
            "/api/opencode/mcp/:name",
            put(handlers::opencode::opencode_set_mcp_handler)
                .delete(handlers::opencode::opencode_delete_mcp_handler),
        )
        .route("/api/opencode/cli/job", get(handlers::opencode::opencode_cli_job_handler))
        .route("/api/opencode/cli/:action", post(handlers::opencode::opencode_cli_start_handler))
        .route("/api/opencode/x/*path", any(handlers::opencode::opencode_proxy_x_handler))
        // REA Reverse Engineering Supervisor & Tool Bridge
        .route("/api/rea/status", get(handlers::rea::rea_status_handler))
        .route("/api/rea/tools", get(handlers::rea::rea_tools_handler))
        .route("/api/rea/run", post(handlers::rea::rea_run_handler))
        .route("/api/rea/ghidra/decompile", post(handlers::rea::rea_ghidra_decompile_handler))
        // SQLite FTS5 / BM25 & Hybrid Vector Search Database
        .route("/api/db/index", post(handlers::db::db_index_handler))
        .route("/api/db/search", post(handlers::db::db_search_handler))
        .route("/api/db/stats", get(handlers::db::db_stats_handler))
        .route("/api/db/vacuum", post(handlers::db::db_vacuum_handler))
        // Network Traffic Inspector & Bandwidth Monitor
        .route("/api/network/traffic", get(handlers::network::network_traffic_handler))
        .route("/api/network/record", post(handlers::network::network_record_handler))
        .route("/api/network/stats", get(handlers::network::network_stats_handler))
        .route("/api/network/clear", post(handlers::network::network_clear_handler))
        .route("/api/network/proxy/test", post(handlers::network::network_proxy_test_handler))
        // Hardware Telemetry & Thermal Governor
        .route("/api/hw/telemetry", get(handlers::hardware::hw_telemetry_handler))
        .route("/api/hw/thermal/evaluate", post(handlers::hardware::hw_thermal_eval_handler))
        .route("/api/hw/cpu", get(handlers::hardware::hw_cpu_handler))
        // Offline Model Runtime & GGUF Inspector
        .route("/api/models/gguf/inspect", post(handlers::models::models_gguf_inspect_handler))
        .route("/api/models/runtime/validate", post(handlers::models::models_runtime_validate_handler))
        .route("/api/models/route", post(handlers::models::models_route_handler))
        // Conversations & Export / Restore
        .route("/api/conversations", get(handlers::conversations::conversations_list_handler))
        .route("/api/conversations/search", get(handlers::conversations::search_conversations_handler))
        .route("/api/conversations/compact", post(handlers::conversations::conversations_compact_handler))
        .route("/api/conversations/rollover-seed", post(handlers::conversations::conversations_rollover_seed_handler))
        .route("/api/conversations/parse-trajectory", post(handlers::conversations::parse_trajectory_handler))
        .route(
            "/api/conversations/restore",
            post(handlers::archive::restore_conversation_archive_handler),
        )
        .route(
            "/api/conversations/import",
            post(handlers::archive::restore_conversation_archive_handler),
        )
        .route(
            "/api/conversations/:id/export",
            get(handlers::archive::export_conversation_archive_handler),
        )
        .route(
            "/api/conversations/:id/share",
            get(handlers::archive::export_conversation_archive_handler),
        )
        .route(
            "/api/conversations/:id",
            get(handlers::conversations::conversation_messages_handler)
                .delete(handlers::conversations::delete_conversation_handler),
        )
        .route(
            "/api/conversations/:id/title",
            post(handlers::conversations::update_conversation_title_handler)
                .patch(handlers::conversations::update_conversation_title_handler),
        )
        .route(
            "/api/conversations/:id/steps/:step_index",
            get(handlers::conversations::conversation_step_handler),
        )
        // Backup & Restore Engine
        .route("/api/backup/chats", post(handlers::archive::backup_chats_handler))
        .route("/api/restore/chats", post(handlers::archive::restore_chats_handler))
        .route("/api/backup/projects", post(handlers::archive::backup_projects_handler))
        .route("/api/restore/projects", post(handlers::archive::restore_projects_handler))
        // Terminal / PTY Process Supervisor
        .route("/api/terminal/sessions", get(supervisor::handlers::list_sessions_handler))
        .route("/api/terminal/spawn", post(supervisor::handlers::spawn_session_handler))
        .route(
            "/api/terminal/:id",
            get(supervisor::handlers::get_session_handler)
                .delete(supervisor::handlers::close_session_handler),
        )
        .route("/api/terminal/:id/write", post(supervisor::handlers::write_session_handler))
        .route("/api/terminal/:id/resize", post(supervisor::handlers::resize_session_handler))
        .route("/api/terminal/:id/kill", post(supervisor::handlers::kill_session_handler))
        .route("/api/terminal/:id/close", post(supervisor::handlers::close_session_handler))
        .route("/api/terminal/:id/output", get(supervisor::handlers::output_session_handler))
        .route("/ws/terminal/:id", get(supervisor::handlers::ws_terminal_handler))
        .route("/ws/pty/:id", get(supervisor::handlers::ws_terminal_handler))
        // WebSocket
        .route("/ws", get(ws::ws_handler))
        // Static files
        .nest_service("/artifacts", ServeDir::new(artifacts_dir))
        .nest_service("/attachments", ServeDir::new(attachments_dir))
        .layer(cors)
        .with_state(state);

    let addr: SocketAddr = format!("{}:{}", args.bind, args.port).parse()?;
    info!("🚀 AntiBot Rust IDE Server listening on http://{}", addr);

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}
