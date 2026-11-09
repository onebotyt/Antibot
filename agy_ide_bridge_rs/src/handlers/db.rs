use axum::{http::StatusCode, Json};
use serde::{Deserialize, Serialize};
use std::sync::Mutex;
use antibot_native::db_search::{DocumentRecord, IndexStats, InvertedIndex, SearchResultItem};

static DB_INDEX: Mutex<Option<InvertedIndex>> = Mutex::new(None);

#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub enum DbIndexPayload {
    Single(DocumentRecord),
    Batch(Vec<DocumentRecord>),
}

#[derive(Debug, Serialize)]
pub struct DbIndexResponse {
    pub indexed: usize,
    pub status: String,
}

#[derive(Debug, Deserialize)]
pub struct DbSearchRequest {
    pub query: String,
    #[serde(default)]
    pub query_embedding: Vec<f32>,
    #[serde(default = "default_alpha")]
    pub alpha: f32,
    #[serde(default = "default_top_k")]
    pub top_k: usize,
}

fn default_alpha() -> f32 {
    0.5
}

fn default_top_k() -> usize {
    10
}

#[derive(Debug, Serialize)]
pub struct DbVacuumResponse {
    pub pruned_terms: usize,
    pub status: String,
}

/// POST /api/db/index
///
/// Indexes single or batch documents into the full-text BM25 and vector search database.
pub async fn db_index_handler(
    Json(payload): Json<DbIndexPayload>,
) -> (StatusCode, Json<DbIndexResponse>) {
    let mut lock = DB_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(InvertedIndex::new);

    let count = match payload {
        DbIndexPayload::Single(doc) => {
            index.insert(doc);
            1
        }
        DbIndexPayload::Batch(docs) => {
            let n = docs.len();
            for doc in docs {
                index.insert(doc);
            }
            n
        }
    };

    (
        StatusCode::OK,
        Json(DbIndexResponse {
            indexed: count,
            status: "ok".to_string(),
        }),
    )
}

/// POST /api/db/search
///
/// Performs hybrid full-text (BM25) and vector semantic search with customizable alpha fusion.
pub async fn db_search_handler(
    Json(payload): Json<DbSearchRequest>,
) -> (StatusCode, Json<Vec<SearchResultItem>>) {
    let mut lock = DB_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(InvertedIndex::new);

    let results = index.search_hybrid(
        &payload.query,
        &payload.query_embedding,
        payload.alpha,
        payload.top_k,
    );

    (StatusCode::OK, Json(results))
}

/// GET /api/db/stats
///
/// Returns search index metrics: total documents, unique terms, postings, and average document length.
pub async fn db_stats_handler() -> (StatusCode, Json<IndexStats>) {
    let mut lock = DB_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(InvertedIndex::new);
    (StatusCode::OK, Json(index.stats()))
}

/// POST /api/db/vacuum
///
/// Compacts the index and prunes orphan postings.
pub async fn db_vacuum_handler() -> (StatusCode, Json<DbVacuumResponse>) {
    let mut lock = DB_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(InvertedIndex::new);
    let pruned = index.vacuum();

    (
        StatusCode::OK,
        Json(DbVacuumResponse {
            pruned_terms: pruned,
            status: "ok".to_string(),
        }),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[tokio::test]
    async fn test_db_handlers_lifecycle() {
        let doc = DocumentRecord {
            id: "t1".to_string(),
            title: "Testing DB Search".to_string(),
            body: "Rust high performance indexed search engine.".to_string(),
            embedding: vec![1.0, 0.0],
            metadata: HashMap::new(),
        };

        let (status, Json(idx_res)) = db_index_handler(Json(DbIndexPayload::Single(doc))).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(idx_res.indexed, 1);

        let (status, Json(stats)) = db_stats_handler().await;
        assert_eq!(status, StatusCode::OK);
        assert!(stats.total_documents >= 1);

        let search_req = DbSearchRequest {
            query: "performance indexed".to_string(),
            query_embedding: vec![0.9, 0.1],
            alpha: 0.5,
            top_k: 5,
        };

        let (status, Json(search_res)) = db_search_handler(Json(search_req)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(!search_res.is_empty());
        assert_eq!(search_res[0].id, "t1");

        let (status, Json(vac_res)) = db_vacuum_handler().await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(vac_res.status, "ok");
    }
}
