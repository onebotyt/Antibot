//! Fast Semantic Vector Index, RAG Cache & Tokenizer in Rust.
//!
//! Provides:
//! - `cosine_similarity`, `dot_product`, and `l2_distance` for embeddings.
//! - In-memory top-k $k$-NN semantic search over code/transcript chunks.
//! - Fast token count estimator and budget truncator (matching BPE cl100k / Gemini heuristics).

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ---------------------------------------------------------------------------
// 1. Vector Similarity Math
// ---------------------------------------------------------------------------

#[inline]
pub fn dot_product(a: &[f32], b: &[f32]) -> f32 {
    let len = a.len().min(b.len());
    let mut sum = 0.0f32;
    for i in 0..len {
        sum += a[i] * b[i];
    }
    sum
}

#[inline]
pub fn l2_distance(a: &[f32], b: &[f32]) -> f32 {
    let len = a.len().min(b.len());
    let mut sum = 0.0f32;
    for i in 0..len {
        let diff = a[i] - b[i];
        sum += diff * diff;
    }
    sum.sqrt()
}

#[inline]
pub fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    let len = a.len().min(b.len());
    if len == 0 {
        return 0.0;
    }

    let mut dot = 0.0f32;
    let mut norm_a = 0.0f32;
    let mut norm_b = 0.0f32;

    for i in 0..len {
        let val_a = a[i];
        let val_b = b[i];
        dot += val_a * val_b;
        norm_a += val_a * val_a;
        norm_b += val_b * val_b;
    }

    if norm_a == 0.0 || norm_b == 0.0 {
        return 0.0;
    }

    let sim = dot / (norm_a.sqrt() * norm_b.sqrt());
    // Clamp to valid range [-1.0, 1.0] to guard against floating-point inaccuracies
    sim.max(-1.0).min(1.0)
}

// ---------------------------------------------------------------------------
// 2. RAG Document Store & k-NN Nearest Neighbor Retrieval
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct VectorDocument {
    pub id: String,
    pub text: String,
    pub embedding: Vec<f32>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct RagSearchResult {
    pub id: String,
    pub score: f32,
    pub text: String,
    pub metadata: HashMap<String, String>,
}

pub fn search_knn(
    query_embedding: &[f32],
    documents: &[VectorDocument],
    top_k: usize,
    min_score: f32,
) -> Vec<RagSearchResult> {
    if query_embedding.is_empty() || documents.is_empty() || top_k == 0 {
        return Vec::new();
    }

    let mut scored: Vec<(f32, &VectorDocument)> = documents
        .iter()
        .filter_map(|doc| {
            if doc.embedding.is_empty() {
                None
            } else {
                let score = cosine_similarity(query_embedding, &doc.embedding);
                if score >= min_score {
                    Some((score, doc))
                } else {
                    None
                }
            }
        })
        .collect();

    // Sort descending by score
    scored.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap_or(std::cmp::Ordering::Equal));

    scored
        .into_iter()
        .take(top_k)
        .map(|(score, doc)| RagSearchResult {
            id: doc.id.clone(),
            score: (score * 10000.0).round() / 10000.0,
            text: doc.text.clone(),
            metadata: doc.metadata.clone(),
        })
        .collect()
}

// ---------------------------------------------------------------------------
// 3. Fast BPE Token Estimator & Context Truncator
// ---------------------------------------------------------------------------

/// Estimates token count using fast heuristics approximating BPE tokenizers
/// (like OpenAI cl100k_base and Gemini tokenizer) without large 30MB vocabulary tables:
/// - Words (alphanumeric sequences): ~1 token per 3.8-4 characters
/// - Punctuation / delimiters: ~1 token per character or symbol pair
/// - Whitespace / indentation: ~1 token per 4 spaces or newline
pub fn estimate_token_count(text: &str) -> usize {
    if text.is_empty() {
        return 0;
    }

    let bytes = text.as_bytes();
    let mut tokens = 0usize;
    let mut i = 0;
    let len = bytes.len();

    while i < len {
        let b = bytes[i];

        // 1. Whitespace handling
        if b == b' ' || b == b'\t' {
            let start = i;
            while i < len && (bytes[i] == b' ' || bytes[i] == b'\t') {
                i += 1;
            }
            let count = i - start;
            // If it's a single space immediately followed by an alphanumeric character,
            // in BPE (e.g. cl100k / Gemini) the space is merged into the word token (Ġword)
            if count == 1 && i < len && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') {
                // Absorbed into following word
            } else {
                tokens += ((count + 3) / 4).max(1);
            }
            continue;
        }

        // 2. Newline handling
        if b == b'\n' || b == b'\r' {
            tokens += 1;
            i += 1;
            if i < len && bytes[i] == b'\n' {
                i += 1;
            }
            continue;
        }

        // 3. Alphanumeric / Identifier run
        if b.is_ascii_alphanumeric() || b == b'_' {
            let start = i;
            while i < len && (bytes[i].is_ascii_alphanumeric() || bytes[i] == b'_') {
                i += 1;
            }
            let word_len = i - start;
            // BPE word subwords: 1-4 chars = 1 token, 5-8 chars = 2 tokens, etc.
            tokens += ((word_len + 3) / 4).max(1);
            continue;
        }

        // 4. Multi-character punctuation / operator sequences (e.g. "->", "==", "!=", "::", "/*", "()", etc.)
        if b < 128 {
            let start = i;
            while i < len
                && bytes[i] < 128
                && !bytes[i].is_ascii_alphanumeric()
                && !bytes[i].is_ascii_whitespace()
                && bytes[i] != b'_'
            {
                i += 1;
                // Group pairs of punctuation into 1 token
                if i - start >= 2 {
                    break;
                }
            }
            tokens += 1;
            continue;
        }

        // 5. UTF-8 multi-byte characters
        let mut char_len = 1;
        if (b & 0xE0) == 0xC0 {
            char_len = 2;
        } else if (b & 0xF0) == 0xE0 {
            char_len = 3;
        } else if (b & 0xF8) == 0xF0 {
            char_len = 4;
        }
        tokens += 1;
        i += char_len.min(len - i);
    }

    tokens.max(1)
}

/// Truncates a string to fit within a given token budget while preserving UTF-8 boundaries.
/// Returns the truncated text and its token count.
pub fn truncate_to_token_budget(text: &str, max_tokens: usize) -> (String, usize) {
    if max_tokens == 0 || text.is_empty() {
        return (String::new(), 0);
    }

    let current_tokens = estimate_token_count(text);
    if current_tokens <= max_tokens {
        return (text.to_string(), current_tokens);
    }

    // Binary search to find valid character slice boundary
    let char_indices: Vec<usize> = text.char_indices().map(|(idx, _)| idx).collect();
    let mut low = 0;
    let mut high = char_indices.len();
    let mut best_boundary = 0;
    let mut best_tokens = 0;

    while low <= high {
        let mid = (low + high) / 2;
        let byte_pos = if mid < char_indices.len() {
            char_indices[mid]
        } else {
            text.len()
        };

        let slice = &text[..byte_pos];
        let count = estimate_token_count(slice);

        if count <= max_tokens {
            best_boundary = byte_pos;
            best_tokens = count;
            low = mid + 1;
        } else {
            if mid == 0 {
                break;
            }
            high = mid - 1;
        }
    }

    (text[..best_boundary].to_string(), best_tokens)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_similarity_identical_vectors() {
        let a = vec![1.0, 2.0, 3.0];
        let b = vec![1.0, 2.0, 3.0];
        let sim = cosine_similarity(&a, &b);
        assert!((sim - 1.0).abs() < 1e-5);

        let dist = l2_distance(&a, &b);
        assert!(dist < 1e-5);

        let dot = dot_product(&a, &b);
        assert!((dot - 14.0).abs() < 1e-5);
    }

    #[test]
    fn test_similarity_orthogonal_and_opposite() {
        let a = vec![1.0, 0.0];
        let b = vec![0.0, 1.0];
        assert!(cosine_similarity(&a, &b).abs() < 1e-5);

        let c = vec![-1.0, 0.0];
        assert!((cosine_similarity(&a, &c) - (-1.0)).abs() < 1e-5);
    }

    #[test]
    fn test_knn_search_ranking() {
        let docs = vec![
            VectorDocument {
                id: "doc-1".to_string(),
                text: "Rust memory safety".to_string(),
                embedding: vec![0.9, 0.1, 0.0],
                metadata: HashMap::new(),
            },
            VectorDocument {
                id: "doc-2".to_string(),
                text: "Python machine learning".to_string(),
                embedding: vec![0.0, 0.9, 0.1],
                metadata: HashMap::new(),
            },
            VectorDocument {
                id: "doc-3".to_string(),
                text: "Rust borrow checker".to_string(),
                embedding: vec![0.85, 0.15, 0.0],
                metadata: HashMap::new(),
            },
        ];

        let query = vec![1.0, 0.0, 0.0];
        let results = search_knn(&query, &docs, 2, 0.5);

        assert_eq!(results.len(), 2);
        assert_eq!(results[0].id, "doc-1");
        assert_eq!(results[1].id, "doc-3");
        assert!(results[0].score >= results[1].score);
    }

    #[test]
    fn test_estimate_token_count() {
        let empty = estimate_token_count("");
        assert_eq!(empty, 0);

        let short_code = "fn main() {\n    println!(\"Hello, world!\");\n}";
        let count = estimate_token_count(short_code);
        assert!(count >= 8 && count <= 20, "Got token count: {}", count);

        let text = "The quick brown fox jumps over the lazy dog.";
        let text_tokens = estimate_token_count(text);
        assert!(text_tokens >= 8 && text_tokens <= 14);
    }

    #[test]
    fn test_truncate_to_token_budget() {
        let long_code = "fn calculate_total(a: i32, b: i32) -> i32 {\n    let result = a + b;\n    println!(\"Result is: {}\", result);\n    result\n}";
        let total_tokens = estimate_token_count(long_code);

        let (truncated, tokens) = truncate_to_token_budget(long_code, 10);
        assert!(tokens <= 10);
        assert!(truncated.len() < long_code.len());
        assert!(long_code.starts_with(&truncated));

        // When budget is larger than current tokens
        let (full, full_tokens) = truncate_to_token_budget(long_code, total_tokens + 50);
        assert_eq!(full, long_code);
        assert_eq!(full_tokens, total_tokens);
    }
}
