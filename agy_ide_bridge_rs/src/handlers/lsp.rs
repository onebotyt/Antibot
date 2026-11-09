use axum::{extract::State, http::StatusCode, response::IntoResponse, Json};
use std::{path::Path, process::Command, sync::Arc};

use crate::{
    models::{
        LspCompleteReq, LspCompleteResponse, LspDiagnosticsReq, LspDiagnosticsResponse,
        LspSymbolsReq, LspSymbolsResponse,
    },
    AppState,
};
use antibot_native::lsp_diagnostics::{self, CompletionTrie};

/// Computes auto-completions for a prefix given language and optional document symbols.
pub async fn lsp_complete_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<LspCompleteReq>,
) -> impl IntoResponse {
    let ext = req
        .ext
        .or_else(|| {
            req.path
                .as_ref()
                .and_then(|p| Path::new(p).extension())
                .map(|e| e.to_string_lossy().to_string())
        })
        .unwrap_or_else(|| "rs".to_string());

    let mut trie = CompletionTrie::new();

    // 1. Insert default keywords and standard library types for this language
    for item in lsp_diagnostics::get_default_completions(&ext) {
        trie.insert(item);
    }

    // 2. Extract and insert file symbols if content or file path provided
    let content = if let Some(ref c) = req.content {
        Some(c.clone())
    } else if let Some(ref rel_path) = req.path {
        let full = state.workspace_dir.join(rel_path);
        std::fs::read_to_string(full).ok()
    } else {
        None
    };

    if let Some(ref code) = content {
        let symbols = lsp_diagnostics::extract_file_symbols(code, &ext);
        for sym in symbols {
            trie.insert(lsp_diagnostics::CompletionItem {
                label: sym.name.clone(),
                kind: sym.kind.clone(),
                detail: sym.container_name.map(|c| format!("{} in {}", sym.kind, c)).or(Some(sym.kind)),
                documentation: None,
                insert_text: Some(sym.name),
                sort_priority: 80,
            });
        }
    }

    let limit = req.limit.unwrap_or(30).min(100);
    let completions = trie.complete(&req.prefix, limit);
    let total = completions.len();

    (
        StatusCode::OK,
        Json(LspCompleteResponse { completions, total }),
    )
}

/// Extracts declared symbols (functions, classes, structs, variables) from a file.
pub async fn lsp_symbols_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<LspSymbolsReq>,
) -> impl IntoResponse {
    let ext = req
        .ext
        .or_else(|| {
            req.path
                .as_ref()
                .and_then(|p| Path::new(p).extension())
                .map(|e| e.to_string_lossy().to_string())
        })
        .unwrap_or_else(|| "rs".to_string());

    let content = if let Some(ref c) = req.content {
        c.clone()
    } else if let Some(ref rel_path) = req.path {
        let full = state.workspace_dir.join(rel_path);
        std::fs::read_to_string(full).unwrap_or_default()
    } else {
        String::new()
    };

    let symbols = lsp_diagnostics::extract_file_symbols(&content, &ext);
    let total = symbols.len();

    (StatusCode::OK, Json(LspSymbolsResponse { symbols, total }))
}

/// Parses or executes compiler diagnostics.
pub async fn lsp_diagnostics_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<LspDiagnosticsReq>,
) -> impl IntoResponse {
    let raw = if let Some(ref raw_out) = req.raw_output {
        raw_out.clone()
    } else if let Some(ref cmd_str) = req.command {
        // Run compiler/linter command in workspace/project directory
        let working_dir = if let Some(ref proj) = req.project {
            state.workspace_dir.join(proj)
        } else {
            state.workspace_dir.clone()
        };

        let output = Command::new("sh")
            .arg("-c")
            .arg(cmd_str)
            .current_dir(&working_dir)
            .output();

        match output {
            Ok(out) => {
                let stdout = String::from_utf8_lossy(&out.stdout).to_string();
                let stderr = String::from_utf8_lossy(&out.stderr).to_string();
                format!("{}\n{}", stdout, stderr)
            }
            Err(e) => format!("execution error: {}", e),
        }
    } else {
        String::new()
    };

    let diagnostics = lsp_diagnostics::parse_diagnostics(&raw);
    let total = diagnostics.len();

    (
        StatusCode::OK,
        Json(LspDiagnosticsResponse { diagnostics, total }),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lsp_complete_flow() {
        let mut trie = CompletionTrie::new();
        trie.insert(lsp_diagnostics::CompletionItem {
            label: "execute_action".to_string(),
            kind: "function".to_string(),
            detail: None,
            documentation: None,
            insert_text: None,
            sort_priority: 90,
        });

        let matches = trie.complete("exec", 10);
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].label, "execute_action");
    }

    #[test]
    fn test_lsp_symbols_flow() {
        let code = "fn test_function() {}\nstruct TestData {}\n";
        let syms = lsp_diagnostics::extract_file_symbols(code, "rs");
        assert_eq!(syms.len(), 2);
    }
}
