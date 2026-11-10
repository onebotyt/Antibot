//! Native Offline Model Runtime Bridge & GGUF Metadata Inspector in pure Rust.
//!
//! Provides:
//! - Binary GGUF v2/v3 header parsing: architecture, context length, embedding dim, layer count, KV heads.
//! - KV cache memory calculator based on model architecture dimensions and context budget.
//! - Hardware-aware memory feasibility validation ensuring offline LLM weights fit within device RAM.

use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::Path;

pub const GGUF_MAGIC: [u8; 4] = [0x47, 0x47, 0x55, 0x46]; // "GGUF"

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct GgufMetadata {
    pub magic: String,
    pub version: u32,
    pub tensor_count: u64,
    pub kv_count: u64,
    pub architecture: String,
    pub context_length: u32,
    pub embedding_length: u32,
    pub block_count: u32,
    pub head_count: u32,
    pub head_count_kv: u32,
    pub general_name: String,
    pub file_size_bytes: u64,
    pub estimated_model_size_mb: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ModelRuntimeValidation {
    pub feasible: bool,
    pub estimated_ram_mb: u64,
    pub available_ram_mb: u64,
    pub model_weights_mb: u64,
    pub kv_cache_mb: u64,
    pub recommended_context: usize,
    pub recommended_threads: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub warning: Option<String>,
}

#[derive(Debug, PartialEq)]
enum GgufType {
    Uint8 = 0,
    Int8 = 1,
    Uint16 = 2,
    Int16 = 3,
    Uint32 = 4,
    Int32 = 5,
    Float32 = 6,
    Bool = 7,
    String = 8,
    Array = 9,
    Uint64 = 10,
    Int64 = 11,
    Float64 = 12,
    Unknown = 99,
}

impl From<u32> for GgufType {
    fn from(v: u32) -> Self {
        match v {
            0 => GgufType::Uint8,
            1 => GgufType::Int8,
            2 => GgufType::Uint16,
            3 => GgufType::Int16,
            4 => GgufType::Uint32,
            5 => GgufType::Int32,
            6 => GgufType::Float32,
            7 => GgufType::Bool,
            8 => GgufType::String,
            9 => GgufType::Array,
            10 => GgufType::Uint64,
            11 => GgufType::Int64,
            12 => GgufType::Float64,
            _ => GgufType::Unknown,
        }
    }
}

fn read_u32<R: Read>(r: &mut R) -> Result<u32, String> {
    let mut buf = [0u8; 4];
    r.read_exact(&mut buf)
        .map_err(|e| format!("Failed to read u32: {}", e))?;
    Ok(u32::from_le_bytes(buf))
}

fn read_u64<R: Read>(r: &mut R) -> Result<u64, String> {
    let mut buf = [0u8; 8];
    r.read_exact(&mut buf)
        .map_err(|e| format!("Failed to read u64: {}", e))?;
    Ok(u64::from_le_bytes(buf))
}

fn read_string<R: Read>(r: &mut R) -> Result<String, String> {
    let len = read_u64(r)? as usize;
    if len > 65536 {
        return Err(format!("GGUF string length too large: {}", len));
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf)
        .map_err(|e| format!("Failed to read string data: {}", e))?;
    Ok(String::from_utf8_lossy(&buf).to_string())
}

fn skip_gguf_value<R: Read>(r: &mut R, val_type: &GgufType) -> Result<(), String> {
    match val_type {
        GgufType::Uint8 | GgufType::Int8 | GgufType::Bool => {
            let mut buf = [0u8; 1];
            r.read_exact(&mut buf).map_err(|e| e.to_string())?;
        }
        GgufType::Uint16 | GgufType::Int16 => {
            let mut buf = [0u8; 2];
            r.read_exact(&mut buf).map_err(|e| e.to_string())?;
        }
        GgufType::Uint32 | GgufType::Int32 | GgufType::Float32 => {
            let mut buf = [0u8; 4];
            r.read_exact(&mut buf).map_err(|e| e.to_string())?;
        }
        GgufType::Uint64 | GgufType::Int64 | GgufType::Float64 => {
            let mut buf = [0u8; 8];
            r.read_exact(&mut buf).map_err(|e| e.to_string())?;
        }
        GgufType::String => {
            let len = read_u64(r)? as usize;
            let mut buf = vec![0u8; len.min(1024 * 1024)];
            r.read_exact(&mut buf).map_err(|e| e.to_string())?;
        }
        GgufType::Array => {
            let elem_type_raw = read_u32(r)?;
            let elem_type = GgufType::from(elem_type_raw);
            let array_len = read_u64(r)? as usize;
            for _ in 0..array_len {
                skip_gguf_value(r, &elem_type)?;
            }
        }
        GgufType::Unknown => {
            return Err("Encountered unknown GGUF value type".to_string());
        }
    }
    Ok(())
}

/// Parses GGUF metadata from a reader up to tensor info block.
pub fn parse_gguf_header<R: Read>(mut r: R, file_size_bytes: u64) -> Result<GgufMetadata, String> {
    let mut magic_buf = [0u8; 4];
    r.read_exact(&mut magic_buf)
        .map_err(|e| format!("Failed to read magic: {}", e))?;

    if magic_buf != GGUF_MAGIC {
        return Err(format!(
            "Invalid GGUF magic header: expected 'GGUF', got {:?}",
            magic_buf
        ));
    }

    let version = read_u32(&mut r)?;
    if version < 1 || version > 3 {
        return Err(format!("Unsupported GGUF version: {}", version));
    }

    let tensor_count = read_u64(&mut r)?;
    let kv_count = read_u64(&mut r)?;

    let mut architecture = "llama".to_string();
    let mut general_name = "model".to_string();
    let mut context_length = 4096u32;
    let mut embedding_length = 4096u32;
    let mut block_count = 32u32;
    let mut head_count = 32u32;
    let mut head_count_kv = 32u32;

    // Read key-value metadata pairs
    for _ in 0..kv_count.min(1000) {
        let key = match read_string(&mut r) {
            Ok(k) => k,
            Err(_) => break,
        };

        let val_type_raw = match read_u32(&mut r) {
            Ok(t) => t,
            Err(_) => break,
        };
        let val_type = GgufType::from(val_type_raw);

        if key == "general.architecture" {
            if val_type == GgufType::String {
                if let Ok(s) = read_string(&mut r) {
                    architecture = s;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key == "general.name" {
            if val_type == GgufType::String {
                if let Ok(s) = read_string(&mut r) {
                    general_name = s;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key.ends_with(".context_length") {
            if val_type == GgufType::Uint32 {
                if let Ok(v) = read_u32(&mut r) {
                    context_length = v;
                }
            } else if val_type == GgufType::Uint64 {
                if let Ok(v) = read_u64(&mut r) {
                    context_length = v as u32;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key.ends_with(".embedding_length") {
            if val_type == GgufType::Uint32 {
                if let Ok(v) = read_u32(&mut r) {
                    embedding_length = v;
                }
            } else if val_type == GgufType::Uint64 {
                if let Ok(v) = read_u64(&mut r) {
                    embedding_length = v as u32;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key.ends_with(".block_count") {
            if val_type == GgufType::Uint32 {
                if let Ok(v) = read_u32(&mut r) {
                    block_count = v;
                }
            } else if val_type == GgufType::Uint64 {
                if let Ok(v) = read_u64(&mut r) {
                    block_count = v as u32;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key.ends_with(".attention.head_count") {
            if val_type == GgufType::Uint32 {
                if let Ok(v) = read_u32(&mut r) {
                    head_count = v;
                }
            } else if val_type == GgufType::Uint64 {
                if let Ok(v) = read_u64(&mut r) {
                    head_count = v as u32;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else if key.ends_with(".attention.head_count_kv") {
            if val_type == GgufType::Uint32 {
                if let Ok(v) = read_u32(&mut r) {
                    head_count_kv = v;
                }
            } else if val_type == GgufType::Uint64 {
                if let Ok(v) = read_u64(&mut r) {
                    head_count_kv = v as u32;
                }
            } else {
                skip_gguf_value(&mut r, &val_type).ok();
            }
        } else {
            // Skip other metadata values cleanly
            if skip_gguf_value(&mut r, &val_type).is_err() {
                break;
            }
        }
    }

    let estimated_model_size_mb = file_size_bytes / (1024 * 1024);

    Ok(GgufMetadata {
        magic: "GGUF".to_string(),
        version,
        tensor_count,
        kv_count,
        architecture,
        context_length,
        embedding_length,
        block_count,
        head_count,
        head_count_kv,
        general_name,
        file_size_bytes,
        estimated_model_size_mb,
    })
}

/// Inspects a GGUF file from filesystem path.
pub fn inspect_gguf_file(path: &Path) -> Result<GgufMetadata, String> {
    let file = File::open(path).map_err(|e| format!("Failed to open model file: {}", e))?;
    let metadata = file
        .metadata()
        .map_err(|e| format!("Failed to read metadata: {}", e))?;
    let file_size = metadata.len();

    let reader = BufReader::new(file);
    parse_gguf_header(reader, file_size)
}

/// Validates memory feasibility and recommends optimal execution parameters.
pub fn validate_model_runtime(
    meta: &GgufMetadata,
    requested_context: usize,
    available_ram_mb: u64,
    online_cores: usize,
) -> ModelRuntimeValidation {
    let layers = meta.block_count as u64;
    let heads_kv = meta.head_count_kv.max(1) as u64;
    let head_dim = if meta.head_count > 0 {
        (meta.embedding_length / meta.head_count).max(64) as u64
    } else {
        128u64
    };

    let ctx = if requested_context == 0 {
        meta.context_length as u64
    } else {
        requested_context as u64
    };

    // KV Cache calculation: 2 (K & V) * layers * heads_kv * head_dim * 2 bytes (fp16) * context
    let kv_cache_bytes = 2 * layers * heads_kv * head_dim * 2 * ctx;
    let kv_cache_mb = kv_cache_bytes / (1024 * 1024);

    let model_weights_mb = meta.estimated_model_size_mb;
    let working_overhead_mb = 256u64;
    let total_required_mb = model_weights_mb + kv_cache_mb + working_overhead_mb;

    let feasible = total_required_mb <= available_ram_mb;

    // Recommend safe context if requested overflows RAM
    let recommended_context = if feasible {
        ctx as usize
    } else {
        let ram_budget_for_kv = available_ram_mb
            .saturating_sub(model_weights_mb + working_overhead_mb)
            * 1024
            * 1024;
        let denom = (2 * layers * heads_kv * head_dim * 2).max(1);
        let safe_ctx = (ram_budget_for_kv / denom) as usize;
        safe_ctx.clamp(512, meta.context_length as usize)
    };

    let recommended_threads = (online_cores / 2).clamp(1, 6);

    let warning = if !feasible {
        Some(format!(
            "Model requires ~{}MB RAM (weights: {}MB, KV: {}MB), but only {}MB is available. Consider reducing context to {} tokens.",
            total_required_mb, model_weights_mb, kv_cache_mb, available_ram_mb, recommended_context
        ))
    } else if total_required_mb > (available_ram_mb * 85 / 100) {
        Some(format!(
            "High memory pressure warning: Model requires ~{}MB of {}MB available ({}%).",
            total_required_mb, available_ram_mb, (total_required_mb * 100) / available_ram_mb.max(1)
        ))
    } else {
        None
    };

    ModelRuntimeValidation {
        feasible,
        estimated_ram_mb: total_required_mb,
        available_ram_mb,
        model_weights_mb,
        kv_cache_mb,
        recommended_context,
        recommended_threads,
        warning,
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn build_synthetic_gguf_v3() -> Vec<u8> {
        let mut buf = Vec::new();
        // 1. Magic "GGUF"
        buf.extend_from_slice(&GGUF_MAGIC);
        // 2. Version 3
        buf.extend_from_slice(&3u32.to_le_bytes());
        // 3. Tensor count (128)
        buf.extend_from_slice(&128u64.to_le_bytes());
        // 4. KV count (3 pairs)
        buf.extend_from_slice(&3u64.to_le_bytes());

        // Pair 1: general.architecture = "qwen2"
        let key1 = "general.architecture";
        buf.extend_from_slice(&(key1.len() as u64).to_le_bytes());
        buf.extend_from_slice(key1.as_bytes());
        buf.extend_from_slice(&(GgufType::String as u32).to_le_bytes());
        let val1 = "qwen2";
        buf.extend_from_slice(&(val1.len() as u64).to_le_bytes());
        buf.extend_from_slice(val1.as_bytes());

        // Pair 2: qwen2.context_length = 8192
        let key2 = "qwen2.context_length";
        buf.extend_from_slice(&(key2.len() as u64).to_le_bytes());
        buf.extend_from_slice(key2.as_bytes());
        buf.extend_from_slice(&(GgufType::Uint32 as u32).to_le_bytes());
        buf.extend_from_slice(&8192u32.to_le_bytes());

        // Pair 3: qwen2.block_count = 28
        let key3 = "qwen2.block_count";
        buf.extend_from_slice(&(key3.len() as u64).to_le_bytes());
        buf.extend_from_slice(key3.as_bytes());
        buf.extend_from_slice(&(GgufType::Uint32 as u32).to_le_bytes());
        buf.extend_from_slice(&28u32.to_le_bytes());

        buf
    }

    #[test]
    fn test_parse_gguf_header_synthetic() {
        let data = build_synthetic_gguf_v3();
        let file_size = 1024 * 1024 * 1500; // 1500 MB mock file
        let meta = parse_gguf_header(Cursor::new(data), file_size).unwrap();

        assert_eq!(meta.magic, "GGUF");
        assert_eq!(meta.version, 3);
        assert_eq!(meta.tensor_count, 128);
        assert_eq!(meta.kv_count, 3);
        assert_eq!(meta.architecture, "qwen2");
        assert_eq!(meta.context_length, 8192);
        assert_eq!(meta.block_count, 28);
        assert_eq!(meta.estimated_model_size_mb, 1500);
    }

    #[test]
    fn test_validate_model_runtime_feasible() {
        let meta = GgufMetadata {
            magic: "GGUF".to_string(),
            version: 3,
            tensor_count: 128,
            kv_count: 5,
            architecture: "llama".to_string(),
            context_length: 4096,
            embedding_length: 2048,
            block_count: 16,
            head_count: 16,
            head_count_kv: 4,
            general_name: "test-model".to_string(),
            file_size_bytes: 800 * 1024 * 1024,
            estimated_model_size_mb: 800,
        };

        let val = validate_model_runtime(&meta, 2048, 4000, 8);
        assert!(val.feasible);
        assert_eq!(val.model_weights_mb, 800);
        assert!(val.estimated_ram_mb > 800);
        assert!(val.estimated_ram_mb <= 4000);
        assert_eq!(val.recommended_context, 2048);
    }

    #[test]
    fn test_validate_model_runtime_insufficient_ram() {
        let meta = GgufMetadata {
            magic: "GGUF".to_string(),
            version: 3,
            tensor_count: 290,
            kv_count: 10,
            architecture: "llama".to_string(),
            context_length: 8192,
            embedding_length: 4096,
            block_count: 32,
            head_count: 32,
            head_count_kv: 8,
            general_name: "large-model".to_string(),
            file_size_bytes: 4500 * 1024 * 1024,
            estimated_model_size_mb: 4500,
        };

        // Only 3000MB available for 4500MB weights
        let val = validate_model_runtime(&meta, 4096, 3000, 8);
        assert!(!val.feasible);
        assert!(val.warning.is_some());
    }
}
