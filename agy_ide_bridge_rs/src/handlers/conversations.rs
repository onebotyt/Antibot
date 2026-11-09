use axum::{
    extract::{Path as AxumPath, Query},
    http::StatusCode,
    response::IntoResponse,
    Json,
};
use regex::Regex;
use serde::Deserialize;
use serde_json::{json, Value};
use std::{
    fs::{self, File},
    io::{BufRead, BufReader},
    path::PathBuf,
    time::UNIX_EPOCH,
};

use crate::models::{
    ChatMessage, ConversationListResponse, ConversationSummary, ToolCall,
    TranscriptSearchMatch, TranscriptSearchResponse,
};

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
            let title = if let Ok(t) = fs::read_to_string(&title_file) {
                let trimmed = t.trim().to_string();
                if trimmed.is_empty() {
                    conv_id.clone()
                } else {
                    trimmed
                }
            } else {
                let mut found_title = String::new();
                if let Ok(file) = File::open(&transcript_path) {
                    let reader = BufReader::new(file);
                    for line in reader.lines().flatten() {
                        if let Ok(step) = serde_json::from_str::<Value>(&line) {
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
                if found_title.is_empty() {
                    conv_id.clone()
                } else {
                    found_title
                }
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
                    steps_count: 1,
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
    let transcript_path = brain_dir().join(&id).join(".system_generated/logs/transcript.jsonl");

    if !transcript_path.exists() {
        return (
            StatusCode::NOT_FOUND,
            Json(json!({ "error": "Conversation not found", "messages": [] })),
        );
    }

    let file = match File::open(&transcript_path) {
        Ok(f) => f,
        Err(e) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": e.to_string(), "messages": [] })),
            );
        }
    };

    let reader = BufReader::new(file);
    let mut messages = Vec::new();

    for line in reader.lines().flatten() {
        if let Ok(step) = serde_json::from_str::<Value>(&line) {
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

            let reader = BufReader::new(file);
            for line in reader.lines().flatten() {
                if matches.len() >= limit {
                    break;
                }

                // Fast pre-check on line content before expensive parsing
                let line_matches = if case_sensitive {
                    line.contains(&needle)
                } else {
                    line.to_lowercase().contains(&needle)
                };

                if !line_matches {
                    continue;
                }

                if let Ok(step) = serde_json::from_str::<Value>(&line) {
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

