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
}
