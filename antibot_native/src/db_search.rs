//! Native Full-Text (FTS5/BM25) & Hybrid Vector Search Engine in Rust.
//!
//! Provides:
//! - Inverted index with Okapi BM25 scoring ($k_1=1.2, b=0.75$) matching SQLite FTS5.
//! - Reciprocal & weighted hybrid fusion ($\alpha \cdot \text{BM25} + (1-\alpha) \cdot \text{Vector}$).
//! - Document storage, snippet extraction, disk snapshot persistence, and vacuuming.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
use crate::vector_rag::cosine_similarity;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DocumentRecord {
    pub id: String,
    pub title: String,
    pub body: String,
    #[serde(default)]
    pub embedding: Vec<f32>,
    #[serde(default)]
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SearchResultItem {
    pub id: String,
    pub score: f32,
    pub bm25_score: f32,
    pub vector_score: f32,
    pub title: String,
    pub snippet: String,
    pub metadata: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct IndexStats {
    pub total_documents: usize,
    pub unique_terms: usize,
    pub total_postings: usize,
    pub avg_doc_length: f32,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct InvertedIndex {
    documents: HashMap<String, DocumentRecord>,
    // term -> doc_id -> term_frequency
    postings: HashMap<String, HashMap<String, usize>>,
    // doc_id -> total_word_count
    doc_lengths: HashMap<String, usize>,
    total_words: usize,
}

impl InvertedIndex {
    pub fn new() -> Self {
        Self::default()
    }

    /// Tokenizes input text into normalized lowercase alphanumeric words.
    pub fn tokenize(text: &str) -> Vec<String> {
        let mut tokens = Vec::new();
        let mut current = String::new();

        for ch in text.chars() {
            if ch.is_alphanumeric() || ch == '_' {
                current.extend(ch.to_lowercase());
            } else if !current.is_empty() {
                if current.len() >= 2 {
                    tokens.push(std::mem::take(&mut current));
                } else {
                    current.clear();
                }
            }
        }

        if current.len() >= 2 {
            tokens.push(current);
        }

        tokens
    }

    /// Indexes or updates a document in the search database.
    pub fn insert(&mut self, doc: DocumentRecord) {
        let doc_id = doc.id.clone();

        // If doc already exists, remove previous postings first
        if self.documents.contains_key(&doc_id) {
            self.remove(&doc_id);
        }

        let full_text = format!("{} {}", doc.title, doc.body);
        let tokens = Self::tokenize(&full_text);
        let length = tokens.len();

        let mut term_freqs: HashMap<String, usize> = HashMap::new();
        for t in tokens {
            *term_freqs.entry(t).or_insert(0) += 1;
        }

        for (term, freq) in term_freqs {
            self.postings.entry(term).or_default().insert(doc_id.clone(), freq);
        }

        self.doc_lengths.insert(doc_id.clone(), length);
        self.total_words += length;
        self.documents.insert(doc_id, doc);
    }

    /// Removes a document from the inverted index.
    pub fn remove(&mut self, doc_id: &str) -> bool {
        if let Some(length) = self.doc_lengths.remove(doc_id) {
            self.total_words = self.total_words.saturating_sub(length);
            self.documents.remove(doc_id);

            // Clean up postings for this document
            let terms_to_prune: Vec<String> = self
                .postings
                .iter_mut()
                .filter_map(|(term, doc_map)| {
                    doc_map.remove(doc_id);
                    if doc_map.is_empty() {
                        Some(term.clone())
                    } else {
                        None
                    }
                })
                .collect();

            for term in terms_to_prune {
                self.postings.remove(&term);
            }

            true
        } else {
            false
        }
    }

    /// Computes BM25 score for all indexed documents matching query terms.
    /// $k_1 = 1.2$, $b = 0.75$.
    pub fn search_bm25(&self, query_text: &str, top_k: usize) -> Vec<(String, f32)> {
        let query_tokens = Self::tokenize(query_text);
        if query_tokens.is_empty() || self.documents.is_empty() {
            return Vec::new();
        }

        let n_docs = self.documents.len() as f32;
        let avg_dl = if self.documents.is_empty() {
            1.0
        } else {
            (self.total_words as f32) / n_docs
        };

        let k1 = 1.2f32;
        let b = 0.75f32;

        let mut scores: HashMap<String, f32> = HashMap::new();

        for term in &query_tokens {
            if let Some(postings_map) = self.postings.get(term) {
                let doc_freq = postings_map.len() as f32;
                // Lucene / FTS5 IDF formulation with smoothing
                let idf = ((n_docs - doc_freq + 0.5) / (doc_freq + 0.5) + 1.0).ln().max(0.0);

                for (doc_id, &tf) in postings_map {
                    let dl = *self.doc_lengths.get(doc_id).unwrap_or(&1) as f32;
                    let tf_f = tf as f32;
                    let num = tf_f * (k1 + 1.0);
                    let denom = tf_f + k1 * (1.0 - b + b * (dl / avg_dl));
                    let term_score = idf * (num / denom);

                    *scores.entry(doc_id.clone()).or_insert(0.0) += term_score;
                }
            }
        }

        let mut ranked: Vec<(String, f32)> = scores.into_iter().collect();
        ranked.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        ranked.truncate(top_k);
        ranked
    }

    /// Hybrid search combining BM25 lexical ranking and embedding cosine similarity.
    /// $\alpha \in [0.0, 1.0]$: weight for BM25 (e.g. 0.5 for balanced, 1.0 for lexical only, 0.0 for vector only).
    pub fn search_hybrid(
        &self,
        query_text: &str,
        query_embedding: &[f32],
        alpha: f32,
        top_k: usize,
    ) -> Vec<SearchResultItem> {
        let weight_lexical = alpha.clamp(0.0, 1.0);
        let weight_vector = 1.0 - weight_lexical;

        let bm25_results = self.search_bm25(query_text, self.documents.len());
        let max_bm25 = bm25_results.first().map(|(_, s)| *s).unwrap_or(1.0).max(1e-5);

        let mut bm25_map: HashMap<String, f32> = HashMap::new();
        for (id, raw_score) in bm25_results {
            bm25_map.insert(id, raw_score / max_bm25);
        }

        let mut scored_items: Vec<SearchResultItem> = Vec::new();

        for (id, doc) in &self.documents {
            let norm_bm25 = *bm25_map.get(id).unwrap_or(&0.0);

            let vector_sim = if !query_embedding.is_empty() && !doc.embedding.is_empty() {
                cosine_similarity(query_embedding, &doc.embedding).max(0.0)
            } else {
                0.0
            };

            let hybrid_score = weight_lexical * norm_bm25 + weight_vector * vector_sim;

            if hybrid_score > 0.0 || (norm_bm25 > 0.0 || vector_sim > 0.0) {
                let snippet = Self::extract_snippet(&doc.body, query_text);
                scored_items.push(SearchResultItem {
                    id: id.clone(),
                    score: (hybrid_score * 10000.0).round() / 10000.0,
                    bm25_score: (norm_bm25 * 10000.0).round() / 10000.0,
                    vector_score: (vector_sim * 10000.0).round() / 10000.0,
                    title: doc.title.clone(),
                    snippet,
                    metadata: doc.metadata.clone(),
                });
            }
        }

        scored_items.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        scored_items.truncate(top_k);
        scored_items
    }

    /// Extracts a readable snippet containing query keywords.
    fn extract_snippet(body: &str, query: &str) -> String {
        let q_tokens = Self::tokenize(query);
        let lower = body.to_lowercase();

        let mut first_match = None;
        for token in q_tokens {
            if let Some(pos) = lower.find(&token) {
                first_match = Some(pos);
                break;
            }
        }

        if let Some(pos) = first_match {
            let start = pos.saturating_sub(40);
            let end = (pos + 80).min(body.len());
            // Safe UTF-8 character boundary slicing
            let s_bound = body.floor_char_boundary(start);
            let e_bound = body.ceil_char_boundary(end);
            format!("...{}...", &body[s_bound..e_bound].trim())
        } else {
            let end = 120.min(body.len());
            let e_bound = body.ceil_char_boundary(end);
            format!("{}...", &body[..e_bound].trim())
        }
    }

    /// Returns index health and statistics.
    pub fn stats(&self) -> IndexStats {
        let n_docs = self.documents.len();
        let total_postings: usize = self.postings.values().map(|m| m.len()).sum();
        let avg_dl = if n_docs > 0 {
            (self.total_words as f32) / (n_docs as f32)
        } else {
            0.0
        };

        IndexStats {
            total_documents: n_docs,
            unique_terms: self.postings.len(),
            total_postings,
            avg_doc_length: (avg_dl * 10.0).round() / 10.0,
        }
    }

    /// Persists index snapshot to a file on disk.
    pub fn save_to_file<P: AsRef<Path>>(&self, path: P) -> std::io::Result<()> {
        let serialized = serde_json::to_vec(self)?;
        let mut file = File::create(path)?;
        file.write_all(&serialized)?;
        file.flush()?;
        Ok(())
    }

    /// Loads index snapshot from a file on disk.
    pub fn load_from_file<P: AsRef<Path>>(path: P) -> std::io::Result<Self> {
        let mut file = File::open(path)?;
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes)?;
        let index: Self = serde_json::from_slice(&bytes)?;
        Ok(index)
    }

    /// Compacts the index and prunes orphan postings.
    pub fn vacuum(&mut self) -> usize {
        let before_terms = self.postings.len();
        self.postings.retain(|_, map| !map.is_empty());
        before_terms.saturating_sub(self.postings.len())
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_inverted_index_insert_and_bm25_search() {
        let mut index = InvertedIndex::new();

        index.insert(DocumentRecord {
            id: "doc-rust".to_string(),
            title: "Rust Language Guide".to_string(),
            body: "Rust guarantees memory safety without garbage collection using borrow checker.".to_string(),
            embedding: vec![1.0, 0.0],
            metadata: HashMap::new(),
        });

        index.insert(DocumentRecord {
            id: "doc-python".to_string(),
            title: "Python Data Science".to_string(),
            body: "Python provides clean syntax and dynamic typing with rich data science libraries.".to_string(),
            embedding: vec![0.0, 1.0],
            metadata: HashMap::new(),
        });

        let results = index.search_bm25("memory safety borrow checker", 5);
        assert!(!results.is_empty());
        assert_eq!(results[0].0, "doc-rust");
        assert!(results[0].1 > 0.0);

        let py_results = index.search_bm25("dynamic typing", 5);
        assert!(!py_results.is_empty());
        assert_eq!(py_results[0].0, "doc-python");
    }

    #[test]
    fn test_hybrid_search_fusion() {
        let mut index = InvertedIndex::new();

        index.insert(DocumentRecord {
            id: "doc-1".to_string(),
            title: "Concurrency in Rust".to_string(),
            body: "Thread safety and channels in Rust programming.".to_string(),
            embedding: vec![0.9, 0.1],
            metadata: HashMap::new(),
        });

        index.insert(DocumentRecord {
            id: "doc-2".to_string(),
            title: "Android Jetpack Compose".to_string(),
            body: "Building modern Android UIs with declarative Compose state.".to_string(),
            embedding: vec![0.1, 0.9],
            metadata: HashMap::new(),
        });

        // Search with both lexical query and embedding vector matching doc-1
        let query_emb = vec![1.0, 0.0];
        let results = index.search_hybrid("concurrency channels", &query_emb, 0.5, 2);

        assert_eq!(results.len(), 2);
        assert_eq!(results[0].id, "doc-1");
        assert!(results[0].score > results[1].score);
        assert!(results[0].bm25_score > 0.0);
        assert!(results[0].vector_score > 0.5);
    }

    #[test]
    fn test_index_stats_and_vacuum() {
        let mut index = InvertedIndex::new();
        index.insert(DocumentRecord {
            id: "d1".to_string(),
            title: "Alpha".to_string(),
            body: "First test document".to_string(),
            embedding: vec![],
            metadata: HashMap::new(),
        });

        let stats = index.stats();
        assert_eq!(stats.total_documents, 1);
        assert!(stats.unique_terms > 0);

        index.remove("d1");
        assert_eq!(index.stats().total_documents, 0);
        let _pruned = index.vacuum();
        assert_eq!(index.stats().unique_terms, 0);
    }
}
