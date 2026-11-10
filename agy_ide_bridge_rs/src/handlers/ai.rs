use axum::{http::StatusCode, Json};
use serde::{Deserialize, Serialize};
use antibot_native::vector_rag::{
    self, cosine_similarity, dot_product, l2_distance, RagSearchResult, VectorDocument,
};

#[derive(Debug, Deserialize)]
pub struct SimilarityRequest {
    pub a: Vec<f32>,
    pub b: Vec<f32>,
}

#[derive(Debug, Serialize)]
pub struct SimilarityResponse {
    pub cosine_similarity: f32,
    pub dot_product: f32,
    pub l2_distance: f32,
}

#[derive(Debug, Deserialize)]
pub struct RagSearchRequest {
    pub query_embedding: Vec<f32>,
    pub documents: Vec<VectorDocument>,
    #[serde(default = "default_top_k")]
    pub top_k: usize,
    #[serde(default)]
    pub min_score: f32,
}

fn default_top_k() -> usize {
    5
}

#[derive(Debug, Deserialize)]
pub struct TokensRequest {
    pub text: String,
    pub max_tokens: Option<usize>,
}

#[derive(Debug, Serialize)]
pub struct TokensResponse {
    pub token_count: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub truncated_text: Option<String>,
    pub is_truncated: bool,
}

/// POST /api/ai/similarity
///
/// Computes cosine similarity, dot product, and L2 euclidean distance between two vectors.
pub async fn similarity_handler(
    Json(payload): Json<SimilarityRequest>,
) -> (StatusCode, Json<SimilarityResponse>) {
    let cos = cosine_similarity(&payload.a, &payload.b);
    let dot = dot_product(&payload.a, &payload.b);
    let l2 = l2_distance(&payload.a, &payload.b);

    (
        StatusCode::OK,
        Json(SimilarityResponse {
            cosine_similarity: (cos * 10000.0).round() / 10000.0,
            dot_product: (dot * 10000.0).round() / 10000.0,
            l2_distance: (l2 * 10000.0).round() / 10000.0,
        }),
    )
}

/// POST /api/ai/rag/search
///
/// Performs in-memory k-NN semantic search across document snippets using cosine similarity.
pub async fn rag_search_handler(
    Json(payload): Json<RagSearchRequest>,
) -> (StatusCode, Json<Vec<RagSearchResult>>) {
    let results = vector_rag::search_knn(
        &payload.query_embedding,
        &payload.documents,
        payload.top_k,
        payload.min_score,
    );
    (StatusCode::OK, Json(results))
}

/// POST /api/ai/tokens
///
/// Estimates token count and optionally truncates text to fit within a specified budget.
pub async fn tokens_handler(
    Json(payload): Json<TokensRequest>,
) -> (StatusCode, Json<TokensResponse>) {
    let total_tokens = vector_rag::estimate_token_count(&payload.text);

    if let Some(max) = payload.max_tokens {
        if total_tokens > max {
            let (truncated, tokens) = vector_rag::truncate_to_token_budget(&payload.text, max);
            return (
                StatusCode::OK,
                Json(TokensResponse {
                    token_count: tokens,
                    truncated_text: Some(truncated),
                    is_truncated: true,
                }),
            );
        }
    }

    (
        StatusCode::OK,
        Json(TokensResponse {
            token_count: total_tokens,
            truncated_text: None,
            is_truncated: false,
        }),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Local Intelligence Engine Intent Classifier — POST /api/ai/intent/classify
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
pub struct IntentClassifyRequest {
    pub prompt: String,
}

#[derive(Debug, Serialize, PartialEq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum LocalIntentType {
    DirectCommand,
    DevServer,
    InbuiltBrowserSearch,
    DevTeamFullstack,
    WebOrFileGeneration,
    CodeCreation,
    GreetingOrIdentity,
    GeneralConversation,
}

#[derive(Debug, Serialize)]
pub struct IntentClassifyResponse {
    pub intent: LocalIntentType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub extracted_command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub extracted_query: Option<String>,
}

pub fn classify_local_intent(prompt: &str) -> (LocalIntentType, Option<String>, Option<String>) {
    let trimmed = prompt.trim();
    let lower = trimmed.to_lowercase();

    // 1. Direct Command
    if trimmed.starts_with('$') || trimmed.starts_with('!')
        || lower.starts_with("run ")
        || lower.starts_with("exec ")
        || lower.starts_with("execute ")
        || lower.starts_with("bash ")
        || lower.starts_with("sh ")
    {
        let cmd = if trimmed.starts_with('$') || trimmed.starts_with('!') {
            trimmed[1..].trim().to_string()
        } else if lower.starts_with("run ") {
            trimmed[4..].trim().to_string()
        } else if lower.starts_with("exec ") {
            trimmed[5..].trim().to_string()
        } else if lower.starts_with("execute ") {
            trimmed[8..].trim().to_string()
        } else if lower.starts_with("bash ") {
            trimmed[5..].trim().to_string()
        } else {
            trimmed[3..].trim().to_string()
        };
        return (LocalIntentType::DirectCommand, Some(cmd), None);
    }

    // 2. Dev server
    let is_dev_server = lower.contains("start server") || lower.contains("run server")
        || lower.contains("start dev") || lower.contains("run dev")
        || lower.contains("pnpm dev") || lower.contains("npm run dev")
        || lower.contains("launch server") || lower.contains("start the project");
    if is_dev_server {
        return (LocalIntentType::DevServer, None, None);
    }

    // 3. Inbuilt browser search
    let is_search = lower.starts_with("search ") || lower.starts_with("lookup ")
        || lower.starts_with("find info ") || lower.starts_with("browse ")
        || lower.starts_with("google ") || lower.starts_with("web search ")
        || lower.contains("documentation for ") || lower.contains("search web for");
    if is_search {
        let query = trimmed
            .strip_prefix("search web for ")
            .or_else(|| trimmed.strip_prefix("web search "))
            .or_else(|| trimmed.strip_prefix("find info "))
            .or_else(|| trimmed.strip_prefix("lookup "))
            .or_else(|| trimmed.strip_prefix("browse "))
            .or_else(|| trimmed.strip_prefix("google "))
            .or_else(|| trimmed.strip_prefix("search "))
            .unwrap_or(trimmed)
            .trim()
            .to_string();
        return (LocalIntentType::InbuiltBrowserSearch, None, Some(query));
    }

    // 4. Full stack team
    let is_fullstack = lower.contains("full stack") || lower.contains("fullstack")
        || (lower.contains("backend") && lower.contains("frontend"))
        || lower.contains("e-commerce") || lower.contains("ecommerce")
        || (lower.contains("make") && lower.contains("website") && lower.contains("api"))
        || (lower.contains("build") && lower.contains("store") && lower.contains("backend"));
    if is_fullstack {
        return (LocalIntentType::DevTeamFullstack, None, None);
    }

    // 5. Web or file generation
    let triggers = [
        "make html", "create html", "build html", "write html", "html portfolio",
        "portfolio page", "portfolio website", "landing page", "web page",
        "make web", "build web", "create web", "create website", "make a website",
        "index.html", "dashboard page", "login page", "signup page", "make portfolio",
    ];
    if triggers.iter().any(|t| lower.contains(t)) {
        return (LocalIntentType::WebOrFileGeneration, None, None);
    }

    // 6. Code creation
    let code_words = [
        "make", "create", "build", "generate", "write", "code", "portfolio",
        "html", "website", "page", "app", "store", "api", "server",
    ];
    if code_words.iter().any(|w| lower.contains(w)) {
        return (LocalIntentType::CodeCreation, None, None);
    }

    // 7. Greeting & identity
    let greeting_tokens = [
        "hello", "hi", "hey", "who are you", "what are you", "what can you do",
        "help", "how are you", "good morning", "good evening", "good afternoon",
        "thank you", "thanks", "who made you", "antibot local", "what model are you",
    ];
    if greeting_tokens.iter().any(|t| lower == *t || lower.starts_with(&format!("{t} ")) || lower.ends_with(&format!(" {t}"))) {
        return (LocalIntentType::GreetingOrIdentity, None, None);
    }

    (LocalIntentType::GeneralConversation, None, None)
}

/// POST /api/ai/intent/classify
pub async fn intent_classify_handler(
    Json(payload): Json<IntentClassifyRequest>,
) -> (StatusCode, Json<IntentClassifyResponse>) {
    let (intent, extracted_command, extracted_query) = classify_local_intent(&payload.prompt);
    (StatusCode::OK, Json(IntentClassifyResponse {
        intent,
        extracted_command,
        extracted_query,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[tokio::test]
    async fn test_similarity_handler() {
        let req = SimilarityRequest {
            a: vec![1.0, 0.0],
            b: vec![0.0, 1.0],
        };
        let (status, Json(res)) = similarity_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.cosine_similarity, 0.0);
        assert_eq!(res.dot_product, 0.0);
        assert!((res.l2_distance - 1.4142).abs() < 1e-3);
    }

    #[tokio::test]
    async fn test_rag_search_handler() {
        let req = RagSearchRequest {
            query_embedding: vec![1.0, 0.0],
            documents: vec![
                VectorDocument {
                    id: "d1".to_string(),
                    text: "Snippet 1".to_string(),
                    embedding: vec![0.9, 0.1],
                    metadata: HashMap::new(),
                },
                VectorDocument {
                    id: "d2".to_string(),
                    text: "Snippet 2".to_string(),
                    embedding: vec![0.1, 0.9],
                    metadata: HashMap::new(),
                },
            ],
            top_k: 1,
            min_score: 0.0,
        };

        let (status, Json(res)) = rag_search_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(res.len(), 1);
        assert_eq!(res[0].id, "d1");
    }

    #[tokio::test]
    async fn test_tokens_handler() {
        let req = TokensRequest {
            text: "Hello world! This is a test for token counting.".to_string(),
            max_tokens: Some(5),
        };

        let (status, Json(res)) = tokens_handler(Json(req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(res.is_truncated);
        assert!(res.token_count <= 5);
        assert!(res.truncated_text.is_some());
    }

    #[test]
    fn test_classify_local_intent() {
        // Direct command
        let (intent, cmd, _) = classify_local_intent("$ ls -la");
        assert_eq!(intent, LocalIntentType::DirectCommand);
        assert_eq!(cmd.as_deref(), Some("ls -la"));

        let (intent, cmd, _) = classify_local_intent("run cargo build");
        assert_eq!(intent, LocalIntentType::DirectCommand);
        assert_eq!(cmd.as_deref(), Some("cargo build"));

        // Dev server
        let (intent, _, _) = classify_local_intent("pnpm dev");
        assert_eq!(intent, LocalIntentType::DevServer);

        // Inbuilt browser search
        let (intent, _, q) = classify_local_intent("search web for rust axum");
        assert_eq!(intent, LocalIntentType::InbuiltBrowserSearch);
        assert_eq!(q.as_deref(), Some("rust axum"));

        // Fullstack
        let (intent, _, _) = classify_local_intent("build a fullstack e-commerce app");
        assert_eq!(intent, LocalIntentType::DevTeamFullstack);

        // Web generation
        let (intent, _, _) = classify_local_intent("create html portfolio page");
        assert_eq!(intent, LocalIntentType::WebOrFileGeneration);

        // Greeting
        let (intent, _, _) = classify_local_intent("hello there!");
        assert_eq!(intent, LocalIntentType::GreetingOrIdentity);
    }
}
