use axum::{
    extract::{Path as AxumPath, Query},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use memchr::memchr_iter;
use memmap2::Mmap;
use regex::Regex;
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    fs::{self, File},
    path::PathBuf,
    time::UNIX_EPOCH,
};

use crate::models::{
    ChatMessage, ConversationListResponse, ConversationSummary, ToolCall,
    TranscriptSearchMatch, TranscriptSearchResponse,
};

fn get_line_offsets(mmap: &[u8]) -> Vec<usize> {
    if mmap.is_empty() {
        return Vec::new();
    }
    let mut offsets = Vec::with_capacity(64);
    offsets.push(0);
    for idx in memchr_iter(b'\n', mmap) {
        if idx + 1 < mmap.len() {
            offsets.push(idx + 1);
        }
    }
    offsets
}

#[derive(Debug, Deserialize)]
pub struct ConvQuery {
    pub page: Option<usize>,
    pub limit: Option<usize>,
    pub q: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ConvSearchQuery {
    pub q: Option<String>,
    pub query: Option<String>,
    pub limit: Option<usize>,
    pub role: Option<String>,
    pub case_sensitive: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct UpdateTitleReq {
    pub title: String,
}

fn brain_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    PathBuf::from(&home).join(".gemini/antigravity-cli/brain")
}

fn extract_prompt_text(content: &str) -> String {
    let user_req_re = Regex::new(r"(?s)<USER_REQUEST>(.*?)</USER_REQUEST>").unwrap();
    if let Some(caps) = user_req_re.captures(content) {
        if let Some(m) = caps.get(1) {
            return m.as_str().trim().to_string();
        }
    }
    let all_tags_re = Regex::new(r"<[^>]+>").unwrap();
    all_tags_re.replace_all(content, "").trim().to_string()
}

pub async fn conversations_list_handler(Query(params): Query<ConvQuery>) -> impl IntoResponse {
    let page = params.page.unwrap_or(1).max(1);
    let limit = params.limit.unwrap_or(30);
    let search = params.q.map(|s| s.to_lowercase());

    let brain = brain_dir();
    let mut convs = Vec::new();

    if let Ok(entries) = fs::read_dir(&brain) {
        for entry in entries.flatten() {
            let p = entry.path();
            if !p.is_dir() {
                continue;
            }
            let conv_id = entry.file_name().to_string_lossy().to_string();
            let transcript_path = p.join(".system_generated/logs/transcript.jsonl");

            let metadata = match fs::metadata(&transcript_path) {
                Ok(m) if m.len() > 0 => m,
                _ => continue,
            };

            let mod_time = metadata
                .modified()
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);

            let created_at = chrono::DateTime::from_timestamp(mod_time as i64, 0)
                .map(|dt| dt.to_rfc3339())
                .unwrap_or_default();

            let title_file = p.join("custom_title.txt");
            let (title, steps_count) = if let Ok(t) = fs::read_to_string(&title_file) {
                let trimmed = t.trim().to_string();
                let steps = if let Ok(f) = File::open(&transcript_path) {
                    if let Ok(m) = unsafe { Mmap::map(&f) } {
                        let mut c = memchr_iter(b'\n', &m).count();
                        if !m.is_empty() && m[m.len() - 1] != b'\n' {
                            c += 1;
                        }
                        c.max(1)
                    } else {
                        1
                    }
                } else {
                    1
                };
                (if trimmed.is_empty() { conv_id.clone() } else { trimmed }, steps)
            } else {
                let mut found_title = String::new();
                let mut steps = 1;
                if let Ok(file) = File::open(&transcript_path) {
                    if let Ok(m) = unsafe { Mmap::map(&file) } {
                        let offsets = get_line_offsets(&m);
                        steps = offsets.len().max(1);
                        for (idx, &off) in offsets.iter().enumerate() {
                            let end = if idx + 1 < offsets.len() {
                                offsets[idx + 1]
                            } else {
                                m.len()
                            };
                            let line = &m[off..end];
                            if let Ok(step) = serde_json::from_slice::<Value>(line) {
                                if step["type"] == "USER_INPUT" {
                                    if let Some(content) = step["content"].as_str() {
                                        let clean = extract_prompt_text(content);
                                        if !clean.is_empty() {
                                            found_title = if clean.len() > 36 {
                                                format!("{}...", &clean[..36])
                                            } else {
                                                clean
                                            };
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                (if found_title.is_empty() { conv_id.clone() } else { found_title }, steps)
            };

            if let Some(q) = &search {
                if !conv_id.to_lowercase().contains(q) && !title.to_lowercase().contains(q) {
                    continue;
                }
            }

            convs.push((
                mod_time,
                ConversationSummary {
                    id: conv_id,
                    title,
                    created_at: created_at.clone(),
                    steps_count,
                    is_running: false,
                    last_activity: Some(created_at),
                },
            ));
        }
    }

    // Sort descending by mod_time
    convs.sort_by(|a, b| b.0.cmp(&a.0));

    let total = convs.len();
    let start = (page - 1) * limit;
    let paged: Vec<ConversationSummary> = if start < total {
        convs.into_iter().skip(start).take(limit).map(|(_, c)| c).collect()
    } else {
        Vec::new()
    };

    let has_more = (page * limit) < total;

    (
        StatusCode::OK,
        Json(ConversationListResponse {
            conversations: paged,
            total,
            page,
            limit,
            has_more,
        }),
    )
}

pub async fn conversation_messages_handler(
    AxumPath(id): AxumPath<String>,
) -> impl IntoResponse {
    let conv_dir = brain_dir().join(&id);
    let transcript_path = conv_dir.join(".system_generated/logs/transcript.jsonl");
    let full_path = conv_dir.join(".system_generated/logs/transcript_full.jsonl");

    if !transcript_path.exists() {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "Conversation not found", "messages": [] })),
        );
    }

    let compact_file = match File::open(&transcript_path) {
        Ok(f) => f,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": e.to_string(), "messages": [] })),
            );
        }
    };

    let compact_mmap = match unsafe { Mmap::map(&compact_file) } {
        Ok(m) => m,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": e.to_string(), "messages": [] })),
            );
        }
    };

    let full_mmap_opt = if full_path.exists() {
        File::open(&full_path).ok().and_then(|f| unsafe { Mmap::map(&f).ok() })
    } else {
        None
    };

    let full_offsets = full_mmap_opt.as_ref().map(|m| get_line_offsets(m));
    let compact_offsets = get_line_offsets(&compact_mmap);
    let mut messages = Vec::with_capacity(compact_offsets.len());

    for (idx, &off) in compact_offsets.iter().enumerate() {
        let end = if idx + 1 < compact_offsets.len() {
            compact_offsets[idx + 1]
        } else {
            compact_mmap.len()
        };
        let line_slice = &compact_mmap[off..end];

        let mut step_val: Option<Value> = serde_json::from_slice(line_slice).ok();

        // If truncated_fields is present, splice in the full fields from transcript_full.jsonl!
        if let Some(ref val) = step_val {
            if val.get("truncated_fields").is_some() {
                if let (Some(ref full_m), Some(ref f_offs)) = (&full_mmap_opt, &full_offsets) {
                    if idx < f_offs.len() {
                        let f_start = f_offs[idx];
                        let f_end = if idx + 1 < f_offs.len() { f_offs[idx + 1] } else { full_m.len() };
                        if let Ok(full_val) = serde_json::from_slice::<Value>(&full_m[f_start..f_end]) {
                            step_val = Some(full_val);
                        }
                    }
                }
            }
        }

        if let Some(step) = step_val {
            let step_type = step["type"].as_str().unwrap_or_default();
            if step_type == "USER_INPUT" {
                let content = step["content"].as_str().unwrap_or_default();
                messages.push(ChatMessage {
                    role: "user".to_string(),
                    content: extract_prompt_text(content),
                    thinking: None,
                    tool_calls: Vec::new(),
                });
            } else if step_type == "PLANNER_RESPONSE" {
                let content = step["content"].as_str().unwrap_or_default().to_string();
                let thinking = step["thinking"].as_str().map(|s| s.to_string());
                let mut tool_calls = Vec::new();

                if let Some(arr) = step["tool_calls"].as_array() {
                    for (idx, tc) in arr.iter().enumerate() {
                        let id = tc["id"].as_str().unwrap_or(&format!("tool_{idx}")).to_string();
                        let name = tc["name"].as_str().unwrap_or("tool").to_string();
                        let command = tc["command"]
                            .as_str()
                            .or_else(|| tc["args"]["CommandLine"].as_str())
                            .or_else(|| tc["args"]["AbsolutePath"].as_str())
                            .unwrap_or("")
                            .to_string();
                        let output = tc["output"].as_str().unwrap_or("").to_string();
                        let status = tc["status"].as_str().unwrap_or("SUCCESS").to_string();

                        tool_calls.push(ToolCall {
                            id,
                            name,
                            command,
                            status,
                            output,
                        });
                    }
                }

                messages.push(ChatMessage {
                    role: "assistant".to_string(),
                    content,
                    thinking,
                    tool_calls,
                });
            }
        }
    }

    (StatusCode::OK, Json(json!({ "id": id, "messages": messages })))
}

pub async fn conversation_step_handler(
    AxumPath((id, step_index)): AxumPath<(String, usize)>,
) -> impl IntoResponse {
    let conv_dir = brain_dir().join(&id);
    let compact_path = conv_dir.join(".system_generated/logs/transcript.jsonl");
    let full_path = conv_dir.join(".system_generated/logs/transcript_full.jsonl");

    if !compact_path.exists() {
        return (StatusCode::NOT_FOUND, Json(json!({ "error": "Not found" })));
    }

    let compact_file = match File::open(&compact_path) {
        Ok(f) => f,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    };

    let compact_mmap = match unsafe { Mmap::map(&compact_file) } {
        Ok(m) => m,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    };

    let offsets = get_line_offsets(&compact_mmap);
    if step_index >= offsets.len() {
        return (StatusCode::NOT_FOUND, Json(json!({ "error": "Step index out of range" })));
    }

    let start = offsets[step_index];
    let end = if step_index + 1 < offsets.len() { offsets[step_index + 1] } else { compact_mmap.len() };
    let line_slice = &compact_mmap[start..end];

    let mut val: Value = match serde_json::from_slice(line_slice) {
        Ok(v) => v,
        Err(e) => return (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    };

    // If truncated, check transcript_full.jsonl
    if val.get("truncated_fields").is_some() && full_path.exists() {
        if let Ok(f) = File::open(&full_path) {
            if let Ok(full_m) = unsafe { Mmap::map(&f) } {
                let f_offs = get_line_offsets(&full_m);
                if step_index < f_offs.len() {
                    let fs = f_offs[step_index];
                    let fe = if step_index + 1 < f_offs.len() { f_offs[step_index + 1] } else { full_m.len() };
                    if let Ok(full_v) = serde_json::from_slice::<Value>(&full_m[fs..fe]) {
                        val = full_v;
                    }
                }
            }
        }
    }

    (StatusCode::OK, Json(val))
}

pub async fn update_conversation_title_handler(
    AxumPath(id): AxumPath<String>,
    Json(req): Json<UpdateTitleReq>,
) -> impl IntoResponse {
    let conv_dir = brain_dir().join(&id);
    let _ = fs::create_dir_all(&conv_dir);
    let title_file = conv_dir.join("custom_title.txt");

    match fs::write(&title_file, req.title.trim()) {
        Ok(_) => (StatusCode::OK, Json(json!({ "status": "ok", "id": id, "title": req.title }))),
        Err(e) => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({ "error": e.to_string() }))),
    }
}

pub async fn delete_conversation_handler(
    AxumPath(id): AxumPath<String>,
) -> impl IntoResponse {
    let conv_dir = brain_dir().join(&id);
    if conv_dir.exists() {
        let _ = fs::remove_dir_all(&conv_dir);
    }
    (StatusCode::OK, Json(json!({ "status": "deleted", "id": id })))
}

pub async fn search_conversations_handler(
    Query(params): Query<ConvSearchQuery>,
) -> impl IntoResponse {
    let query_str = params.query.or(params.q).unwrap_or_default();
    if query_str.trim().is_empty() {
        return (
            StatusCode::OK,
            Json(TranscriptSearchResponse {
                query: query_str,
                total: 0,
                matches: Vec::new(),
            }),
        );
    }

    let limit = params.limit.unwrap_or(50).min(200);
    let role_filter = params.role.map(|r| r.to_lowercase());
    let case_sensitive = params.case_sensitive.unwrap_or(false);
    let needle = if case_sensitive {
        query_str.clone()
    } else {
        query_str.to_lowercase()
    };

    let brain = brain_dir();
    let mut matches = Vec::new();

    if let Ok(entries) = fs::read_dir(&brain) {
        for entry in entries.flatten() {
            if matches.len() >= limit {
                break;
            }
            let p = entry.path();
            if !p.is_dir() {
                continue;
            }
            let conv_id = entry.file_name().to_string_lossy().to_string();
            let transcript_path = p.join(".system_generated/logs/transcript.jsonl");
            if !transcript_path.exists() {
                continue;
            }

            // Get title
            let title_file = p.join("custom_title.txt");
            let mut title = fs::read_to_string(&title_file)
                .ok()
                .map(|t| t.trim().to_string())
                .filter(|t| !t.is_empty())
                .unwrap_or_default();

            let file = match File::open(&transcript_path) {
                Ok(f) => f,
                Err(_) => continue,
            };

            let mmap = match unsafe { Mmap::map(&file) } {
                Ok(m) => m,
                Err(_) => continue,
            };

            let offsets = get_line_offsets(&mmap);
            for (line_idx, &off) in offsets.iter().enumerate() {
                if matches.len() >= limit {
                    break;
                }
                let end = if line_idx + 1 < offsets.len() {
                    offsets[line_idx + 1]
                } else {
                    mmap.len()
                };
                let line_slice = &mmap[off..end];
                let line_str = match std::str::from_utf8(line_slice) {
                    Ok(s) => s,
                    Err(_) => continue,
                };

                // Fast pre-check on line content before expensive parsing
                let line_matches = if case_sensitive {
                    line_str.contains(&needle)
                } else {
                    line_str.to_lowercase().contains(&needle)
                };

                if !line_matches {
                    continue;
                }

                if let Ok(step) = serde_json::from_str::<Value>(line_str) {
                    let step_type = step["type"].as_str().unwrap_or_default();
                    let role = if step_type == "USER_INPUT" {
                        "user"
                    } else if step_type == "PLANNER_RESPONSE" {
                        "assistant"
                    } else {
                        "system"
                    };

                    if let Some(ref rf) = role_filter {
                        if rf != "all" && rf != role {
                            continue;
                        }
                    }

                    let raw_content = step["content"].as_str().unwrap_or_default();
                    let clean_content = if role == "user" {
                        extract_prompt_text(raw_content)
                    } else {
                        raw_content.to_string()
                    };

                    if title.is_empty() && role == "user" && !clean_content.is_empty() {
                        title = if clean_content.len() > 36 {
                            format!("{}...", &clean_content[..36])
                        } else {
                            clean_content.clone()
                        };
                    }

                    let search_target = if case_sensitive {
                        clean_content.clone()
                    } else {
                        clean_content.to_lowercase()
                    };

                    if let Some(byte_idx) = search_target.find(&needle) {
                        let char_indices: Vec<(usize, char)> = clean_content.char_indices().collect();
                        let match_char_idx = clean_content[..byte_idx].chars().count();
                        let start_char = match_char_idx.saturating_sub(40);
                        let end_char = (match_char_idx + query_str.chars().count() + 80).min(char_indices.len());

                        let start_byte = char_indices.get(start_char).map(|&(b, _)| b).unwrap_or(0);
                        let end_byte = char_indices.get(end_char).map(|&(b, _)| b).unwrap_or(clean_content.len());

                        let mut snippet = clean_content[start_byte..end_byte].trim().to_string();
                        if start_char > 0 {
                            snippet = format!("...{snippet}");
                        }
                        if end_char < char_indices.len() {
                            snippet = format!("{snippet}...");
                        }

                        let step_index = step["step_index"].as_u64().unwrap_or(0);
                        let timestamp = step["created_at"].as_str().unwrap_or("").to_string();

                        let display_title = if title.is_empty() {
                            conv_id.clone()
                        } else {
                            title.clone()
                        };

                        matches.push(TranscriptSearchMatch {
                            conversation_id: conv_id.clone(),
                            title: display_title,
                            step_index,
                            role: role.to_string(),
                            snippet,
                            match_offset: match_char_idx,
                            timestamp,
                        });
                    }
                }
            }
        }
    }

    let total = matches.len();
    (
        StatusCode::OK,
        Json(TranscriptSearchResponse {
            query: query_str,
            total,
            matches,
        }),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ContextCompactor  — POST /api/conversations/compact
//
// Ports Kotlin ContextCompactor: splitHistory, buildSummarizationPrompt,
// loadAnchorRules, and buildRolloverSeedPrompt to Rust.
// executeSummarization() (the LLM streaming call) remains in Kotlin.
// ─────────────────────────────────────────────────────────────────────────────

use serde::Serialize;

#[derive(Debug, Deserialize)]
pub struct CompactMessage {
    pub role: String,         // "user" | "assistant"
    pub content: String,
    #[serde(default)]
    pub tool_calls: Vec<CompactToolCall>,
}

#[derive(Debug, Deserialize)]
pub struct CompactToolCall {
    pub name: String,
    #[serde(default)]
    pub command: String,
    #[serde(default)]
    pub status: String,
    pub exit_code: Option<i32>,
    #[serde(default)]
    pub output: String,
}

#[derive(Debug, Deserialize)]
pub struct CompactRequest {
    pub messages: Vec<CompactMessage>,
    #[serde(rename = "windowLimit", default = "default_window_limit")]
    pub window_limit: usize,
    #[serde(rename = "existingSummary")]
    pub existing_summary: Option<String>,
}

fn default_window_limit() -> usize { 20 }

#[derive(Debug, Serialize)]
pub struct CompactResponse {
    /// Messages older than the window (should be summarized)
    pub older: Vec<CompactMessageOut>,
    /// Messages inside the context window (kept as-is)
    pub recent: Vec<CompactMessageOut>,
    /// Ready-to-send summarization prompt for the LLM
    pub summarization_prompt: String,
}

#[derive(Debug, Serialize)]
pub struct CompactMessageOut {
    pub role: String,
    pub content: String,
}

// ── RolloverSeed request ─────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct RolloverSeedRequest {
    pub summary: String,
    #[serde(rename = "workspacePath", default)]
    pub workspace_path: String,
    #[serde(rename = "pendingObjective")]
    pub pending_objective: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct RolloverSeedResponse {
    pub seed_prompt: String,
    pub anchor_rules: Vec<AnchorRule>,
}

#[derive(Debug, Serialize)]
pub struct AnchorRule {
    pub name: String,
    pub content: String,
}

// ── helpers ──────────────────────────────────────────────────────────────────

/// Strips `<!-- tool_call:<id> -->` comment markers from message content.
/// Matches Kotlin: `Regex("<!--\\s*tool_call:[a-zA-Z0-9_-]+\\s*-->")`.
fn strip_tool_call_markers(text: &str) -> String {
    // Manual scanner — avoids a regex dependency here (regex already in scope via imports above)
    let re = Regex::new(r"<!--\s*tool_call:[a-zA-Z0-9_\-]+\s*-->").unwrap();
    re.replace_all(text, "").trim().to_string()
}

fn build_summarization_prompt(messages: &[CompactMessage], existing_summary: Option<&str>) -> String {
    let mut conv = String::new();

    if let Some(prev) = existing_summary {
        if !prev.trim().is_empty() {
            conv.push_str("=== PREVIOUS CONTEXT SUMMARY ===\n");
            conv.push_str(prev.trim());
            conv.push_str("\n================================\n\n");
        }
    }

    conv.push_str("=== CONVERSATION TURNS TO SUMMARIZE ===\n");
    for msg in messages {
        let role_name = if msg.role == "user" { "User" } else { "Assistant" };
        let clean = strip_tool_call_markers(&msg.content);
        conv.push_str(&format!("[{role_name}]: {clean}\n"));

        for tool in &msg.tool_calls {
            let cmd_snippet = if tool.command.len() > 120 { &tool.command[..120] } else { &tool.command };
            let exit = tool.exit_code.unwrap_or(0);
            conv.push_str(&format!(
                "  [Tool Executed: {}] command: {} (status: {}, exit: {})\n",
                tool.name, cmd_snippet, tool.status, exit
            ));
            if !tool.output.trim().is_empty() {
                let out_snippet = if tool.output.len() > 250 {
                    format!("{}...", &tool.output[..250])
                } else {
                    tool.output.clone()
                };
                conv.push_str(&format!("  [Tool Output Snippet]: {out_snippet}\n"));
            }
        }
        conv.push('\n');
    }
    conv.push_str("=======================================\n");

    format!(
        "You are an expert AI Context Summarizer. Condense the following conversation history into a high-density, crystal-clear executive memory block that an AI can use to continue working seamlessly without losing any critical context.\n\n{conv}\nINSTRUCTIONS:\n1. Output a refined, comprehensive 3-6 word title for this overall conversation enclosed in <chat_title>...</chat_title> at the very top (e.g. <chat_title>Spring Boot Auth Migration</chat_title>).\n2. Produce a concise, structured markdown summary (around 150-300 words) with these exact sections:\n• **Core Goal & Requirements**: What the user is building or asking for.\n• **Key Decisions & Architecture**: Important choices made, libraries/frameworks chosen, preferences specified.\n• **Work Accomplished & Tool Results**: Files created/modified, commands run, features implemented, and key outcomes.\n• **Current Status & Pending Tasks**: Where the conversation currently left off and what needs to happen next.\n\nKeep it factual, concise, and focused on code, files, decisions, and outcomes. Do NOT include filler words."
    )
}

fn load_anchor_rules(workspace_path: &str) -> Vec<AnchorRule> {
    let mut rules = Vec::new();
    if workspace_path.trim().is_empty() {
        return rules;
    }
    let root = std::path::Path::new(workspace_path);
    if !root.is_dir() {
        return rules;
    }

    // 1. AGENTS.md
    let agents_md = root.join("AGENTS.md");
    if agents_md.is_file() {
        if let Ok(text) = fs::read_to_string(&agents_md) {
            let content: String = text.chars().take(6000).collect();
            rules.push(AnchorRule { name: "AGENTS.md".into(), content });
        }
    }

    // 2. .gemini/rules/
    let rules_dir = root.join(".gemini/rules");
    if rules_dir.is_dir() {
        if let Ok(entries) = fs::read_dir(&rules_dir) {
            let mut dir_rules: Vec<AnchorRule> = entries.flatten()
                .filter(|e| {
                    let p = e.path();
                    p.is_file() && matches!(p.extension().and_then(|x| x.to_str()), Some("md") | Some("txt"))
                })
                .filter_map(|e| {
                    let name = e.file_name().to_string_lossy().to_string();
                    fs::read_to_string(e.path()).ok().map(|text| AnchorRule {
                        name,
                        content: text.chars().take(4000).collect(),
                    })
                })
                .collect();
            dir_rules.sort_by(|a, b| a.name.cmp(&b.name));
            rules.extend(dir_rules);
        }
    }

    // 3. CLAUDE.md
    let claude_md = root.join("CLAUDE.md");
    if claude_md.is_file() {
        if let Ok(text) = fs::read_to_string(&claude_md) {
            let content: String = text.chars().take(6000).collect();
            rules.push(AnchorRule { name: "CLAUDE.md".into(), content });
        }
    }

    rules
}

fn build_rollover_seed_prompt(summary: &str, anchor_rules: &[AnchorRule], pending_objective: Option<&str>) -> String {
    let mut out = String::new();
    out.push_str("=== [AUTO-ROLLOVER: FRESH CONTINUATION SESSION] ===\n");
    out.push_str("This chat was automatically initialized from a previous session to ensure peak agent reasoning and zero memory bloat.\n\n");

    if !anchor_rules.is_empty() {
        out.push_str("### 📌 INVARIANT PROJECT RULES (ALWAYS CARRIED FULL)\n");
        for rule in anchor_rules {
            out.push_str(&format!("--- [{}] ---\n", rule.name));
            out.push_str(rule.content.trim());
            out.push_str("\n-------------------\n");
        }
        out.push('\n');
    }

    out.push_str("### 📋 EXECUTIVE PROGRESS FROM PREVIOUS CHAT\n");
    out.push_str(summary.trim());
    out.push_str("\n\n");

    if let Some(obj) = pending_objective {
        if !obj.trim().is_empty() {
            out.push_str("### 🎯 IMMEDIATE NEXT OBJECTIVE\n");
            out.push_str(obj.trim());
            out.push_str("\n\n");
        }
    }

    out.push_str("INSTRUCTION: Continue development seamlessly from this point forward. Follow all invariant rules above.\n");
    out.push_str("===================================================");
    out
}

// ── handlers ─────────────────────────────────────────────────────────────────

/// POST /api/conversations/compact
///
/// Given a full message list + window limit, returns:
/// - `older`: messages outside the window (candidates for summarization)
/// - `recent`: messages inside the window (kept verbatim)
/// - `summarization_prompt`: ready-to-send prompt for the Kotlin LLM streaming call
pub async fn conversations_compact_handler(
    Json(req): Json<CompactRequest>,
) -> (StatusCode, Json<CompactResponse>) {
    let n = req.messages.len();
    let (older_msgs, recent_msgs) = if n <= req.window_limit {
        (&req.messages[..0], &req.messages[..])
    } else {
        let split = n - req.window_limit;
        (&req.messages[..split], &req.messages[split..])
    };

    let prompt = build_summarization_prompt(older_msgs, req.existing_summary.as_deref());

    let to_out = |msgs: &[CompactMessage]| -> Vec<CompactMessageOut> {
        msgs.iter().map(|m| CompactMessageOut {
            role: m.role.clone(),
            content: m.content.clone(),
        }).collect()
    };

    (StatusCode::OK, Json(CompactResponse {
        older: to_out(older_msgs),
        recent: to_out(recent_msgs),
        summarization_prompt: prompt,
    }))
}

/// POST /api/conversations/rollover-seed
///
/// Builds the full rollover seed prompt from a summary + workspace anchor rules.
/// Kotlin calls this after summarization completes, then uses the returned
/// `seed_prompt` as the first message of the new chat session.
pub async fn conversations_rollover_seed_handler(
    Json(req): Json<RolloverSeedRequest>,
) -> (StatusCode, Json<RolloverSeedResponse>) {
    let anchor_rules = load_anchor_rules(&req.workspace_path);
    let seed_prompt = build_rollover_seed_prompt(&req.summary, &anchor_rules, req.pending_objective.as_deref());
    (StatusCode::OK, Json(RolloverSeedResponse { seed_prompt, anchor_rules }))
}

#[cfg(test)]

mod tests {
    use super::*;

    #[test]
    fn test_get_line_offsets_empty() {
        assert_eq!(get_line_offsets(b""), Vec::<usize>::new());
    }

    #[test]
    fn test_get_line_offsets_multiple_lines() {
        let data = b"line1\nline2\nline3\n";
        let offsets = get_line_offsets(data);
        assert_eq!(offsets, vec![0, 6, 12]);
    }

    #[test]
    fn test_extract_prompt_text_user_request() {
        let content = "<antigem-expansion command=\"\"></antigem-expansion><USER_REQUEST>Fix the Rust compilation issue</USER_REQUEST>";
        assert_eq!(extract_prompt_text(content), "Fix the Rust compilation issue");
    }

    #[test]
    fn test_extract_prompt_text_fallback_tags() {
        let content = "<system>Hello</system> World!";
        assert_eq!(extract_prompt_text(content), "Hello World!");
    }

    #[test]
    fn test_strip_tool_call_markers() {
        let text = "Result here <!-- tool_call:abc-123 --> and more text";
        assert_eq!(strip_tool_call_markers(text), "Result here  and more text");
    }

    #[tokio::test]
    async fn test_compact_handler_split_window() {
        let msgs = vec![
            CompactMessage { role: "user".into(), content: "Turn 1".into(), tool_calls: vec![] },
            CompactMessage { role: "assistant".into(), content: "Resp 1".into(), tool_calls: vec![] },
            CompactMessage { role: "user".into(), content: "Turn 2".into(), tool_calls: vec![] },
            CompactMessage { role: "assistant".into(), content: "Resp 2".into(), tool_calls: vec![] },
        ];
        let req = CompactRequest {
            messages: msgs,
            window_limit: 2,
            existing_summary: None,
        };
        let (status, Json(res)) = conversations_compact_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.older.len(), 2);
        assert_eq!(res.recent.len(), 2);
        assert!(res.summarization_prompt.contains("Turn 1"));
        assert!(!res.summarization_prompt.contains("Turn 2"));
    }

    #[tokio::test]
    async fn test_rollover_seed_prompt_generation() {
        let req = RolloverSeedRequest {
            summary: "Completed auth migration.".into(),
            workspace_path: "".into(),
            pending_objective: Some("Implement refresh tokens".into()),
        };
        let (status, Json(res)) = conversations_rollover_seed_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(res.seed_prompt.contains("Completed auth migration."));
        assert!(res.seed_prompt.contains("Implement refresh tokens"));
        assert!(res.seed_prompt.contains("AUTO-ROLLOVER"));
    }
}


