use axum::{http::StatusCode, Json};
use serde::Deserialize;
use std::path::Path;
use antibot_native::hw_telemetry::collect_hardware_telemetry;
use antibot_native::model_runtime::{inspect_gguf_file, validate_model_runtime};

#[derive(Debug, Deserialize)]
pub struct ModelInspectRequest {
    pub file_path: String,
}

#[derive(Debug, Deserialize)]
pub struct ModelValidateRequest {
    pub file_path: String,
    #[serde(default = "default_context_length")]
    pub context_length: usize,
}

fn default_context_length() -> usize {
    2048
}

/// POST /api/models/gguf/inspect
///
/// Inspects a GGUF model binary and extracts architecture, layer count, context window, and quantization metadata.
pub async fn models_gguf_inspect_handler(
    Json(payload): Json<ModelInspectRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    let path = Path::new(&payload.file_path);
    match inspect_gguf_file(path) {
        Ok(meta) => (StatusCode::OK, Json(serde_json::to_value(meta).unwrap())),
        Err(e) => (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": e })),
        ),
    }
}

/// POST /api/models/runtime/validate
///
/// Evaluates memory feasibility for offline LLM execution against device available RAM and core topology.
pub async fn models_runtime_validate_handler(
    Json(payload): Json<ModelValidateRequest>,
) -> (StatusCode, Json<serde_json::Value>) {
    let path = Path::new(&payload.file_path);
    let meta = match inspect_gguf_file(path) {
        Ok(m) => m,
        Err(e) => {
            return (
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({
                    "feasible": false,
                    "warning": e
                })),
            );
        }
    };

    let telem = collect_hardware_telemetry(None);
    let validation = validate_model_runtime(
        &meta,
        payload.context_length,
        telem.memory.available_mb,
        telem.online_cores_count,
    );

    (
        StatusCode::OK,
        Json(serde_json::to_value(validation).unwrap()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::File;
    use std::io::Write;
    use std::path::PathBuf;

    struct CleanupGuard(PathBuf);
    impl Drop for CleanupGuard {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn write_test_gguf_file() -> (PathBuf, CleanupGuard) {
        let path = std::env::temp_dir().join(format!("test_model_{}.gguf", std::process::id()));
        let mut file = File::create(&path).unwrap();

        // Magic "GGUF"
        file.write_all(&antibot_native::model_runtime::GGUF_MAGIC)
            .unwrap();
        // Version 3
        file.write_all(&3u32.to_le_bytes()).unwrap();
        // Tensor count
        file.write_all(&64u64.to_le_bytes()).unwrap();
        // KV count
        file.write_all(&2u64.to_le_bytes()).unwrap();

        // KV 1: general.architecture = "llama"
        let key1 = "general.architecture";
        file.write_all(&(key1.len() as u64).to_le_bytes()).unwrap();
        file.write_all(key1.as_bytes()).unwrap();
        file.write_all(&8u32.to_le_bytes()).unwrap(); // String type
        let val1 = "llama";
        file.write_all(&(val1.len() as u64).to_le_bytes()).unwrap();
        file.write_all(val1.as_bytes()).unwrap();

        // KV 2: llama.context_length = 4096
        let key2 = "llama.context_length";
        file.write_all(&(key2.len() as u64).to_le_bytes()).unwrap();
        file.write_all(key2.as_bytes()).unwrap();
        file.write_all(&4u32.to_le_bytes()).unwrap(); // Uint32 type
        file.write_all(&4096u32.to_le_bytes()).unwrap();

        file.flush().unwrap();
        let guard = CleanupGuard(path.clone());
        (path, guard)
    }

    #[tokio::test]
    async fn test_models_handlers_synthetic_gguf() {
        let (path, _guard) = write_test_gguf_file();
        let path_str = path.to_str().unwrap().to_string();

        // 1. Test inspect handler
        let (status, Json(val)) =
            models_gguf_inspect_handler(Json(ModelInspectRequest { file_path: path_str.clone() })).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(val["magic"], "GGUF");
        assert_eq!(val["architecture"], "llama");
        assert_eq!(val["context_length"], 4096);

        // 2. Test validate handler
        let (status, Json(val_res)) = models_runtime_validate_handler(Json(ModelValidateRequest {
            file_path: path_str,
            context_length: 2048,
        }))
        .await;
        assert_eq!(status, StatusCode::OK);
        assert!(val_res["recommended_threads"].as_u64().unwrap() >= 1);
    }
}
