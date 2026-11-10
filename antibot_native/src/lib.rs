use jni::objects::{JByteArray, JClass, JFloatArray, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jfloatArray, jint, jintArray, jobjectArray, jshort, jstring};
use jni::JNIEnv;
use std::sync::Mutex;

pub mod agent_dag;
pub mod audio;
pub mod audio_dsp;
pub mod crypto;
pub mod db_search;
pub mod diff;
pub mod fuzzy;
pub mod diff_patch;
pub mod lsp_diagnostics;
pub mod hw_telemetry;
pub mod mcp_rpc;
pub mod markdown;
pub mod mmap_log;
pub mod model_runtime;
pub mod net_traffic;
pub mod syntax;
pub mod vector_rag;

static VAD: Mutex<Option<audio::VadEngine>> = Mutex::new(None);
static RING_BUFFER: Mutex<Option<audio_dsp::AudioRingBuffer>> = Mutex::new(None);
static TELEMETRY_NOISE: Mutex<f32> = Mutex::new(300.0);
static SEARCH_INDEX: Mutex<Option<db_search::InvertedIndex>> = Mutex::new(None);
static TRAFFIC_INSPECTOR: Mutex<Option<net_traffic::TrafficInspector>> = Mutex::new(None);

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_isNativeAvailable<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jboolean {
    1
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getNativeVersion<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let output = env.new_string("2.0.0-rust-native-production").unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_parseMarkdownBlocksNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
) -> jstring {
    let input: String = match env.get_string(&content) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let blocks = markdown::parse_markdown_blocks(&input);
    let json_str = serde_json::to_string(&blocks).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_parseInlineSpansNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jstring {
    let input: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let spans = markdown::parse_inline_spans(&input);
    let json_str = serde_json::to_string(&spans).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_computeDiffNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    old_text: JString<'local>,
    new_text: JString<'local>,
) -> jstring {
    let old_str: String = match env.get_string(&old_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let new_str: String = match env.get_string(&new_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let diff_result = diff::compute_unified_diff(&old_str, &new_str);
    let json_str = serde_json::to_string(&diff_result).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_sha256Native<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    input_str: JString<'local>,
) -> jstring {
    let input: String = match env.get_string(&input_str) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let hash = crypto::compute_sha256_hex(input.as_bytes());
    let output = env.new_string(hash).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_highlightSyntaxNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    code: JString<'local>,
    ext: JString<'local>,
) -> jintArray {
    let code_str: String = match env.get_string(&code) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ext_str: String = match env.get_string(&ext) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let flat = syntax::highlight_code_flat(&code_str, &ext_str);
    let array = match env.new_int_array(flat.len() as i32) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    if env.set_int_array_region(&array, 0, &flat).is_err() {
        return std::ptr::null_mut();
    }

    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_processAudioPcmNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pcm_array: JByteArray<'local>,
) -> jfloatArray {
    let pcm_vec: Vec<u8> = match env.convert_byte_array(&pcm_array) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut lock = VAD.lock().unwrap();
    let engine = lock.get_or_insert_with(audio::VadEngine::new);
    let metrics = engine.process_pcm(&pcm_vec);

    let result = [
        metrics.normalized_amplitude,
        metrics.peak_amplitude,
        if metrics.is_speech { 1.0f32 } else { 0.0f32 },
        metrics.snr_db,
        metrics.rms_raw,
    ];

    let output_array = match env.new_float_array(5) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    if env.set_float_array_region(&output_array, 0, &result).is_err() {
        return std::ptr::null_mut();
    }

    output_array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_resetVadNative<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let mut lock = VAD.lock().unwrap();
    if let Some(engine) = lock.as_mut() {
        engine.reset();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_pcmToWavNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pcm_array: JByteArray<'local>,
    sample_rate: jint,
    channels: jshort,
    bits_per_sample: jshort,
) -> jbyteArray {
    let pcm_vec: Vec<u8> = match env.convert_byte_array(&pcm_array) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    let wav_bytes = audio::pcm_to_wav(&pcm_vec, sample_rate as u32, channels as u16, bits_per_sample as u16);
    let output_array = match env.byte_array_from_slice(&wav_bytes) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    output_array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_fuzzyScoreNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pattern: JString<'local>,
    target: JString<'local>,
) -> jint {
    let p_str: String = match env.get_string(&pattern) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let t_str: String = match env.get_string(&target) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    fuzzy::fuzzy_score(&p_str, &t_str).unwrap_or(-1) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_fuzzyFilterIndicesNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    pattern: JString<'local>,
    targets: jobjectArray,
    max_results: jint,
) -> jintArray {
    let p_str: String = match env.get_string(&pattern) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let obj_array = unsafe { JObjectArray::from_raw(targets) };
    let count = match env.get_array_length(&obj_array) {
        Ok(len) => len as usize,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut target_strings: Vec<String> = Vec::with_capacity(count);

    for i in 0..count {
        if let Ok(elem) = env.get_object_array_element(&obj_array, i as i32) {
            let jstr = JString::from(elem);
            let s: String = env.get_string(&jstr).map(|js| js.into()).unwrap_or_default();
            target_strings.push(s);
        } else {
            target_strings.push(String::new());
        }
    }

    let target_refs: Vec<&str> = target_strings.iter().map(|s| s.as_str()).collect();
    let matches = fuzzy::fuzzy_filter_indices(&p_str, &target_refs, max_results.max(1) as usize);

    // Flat integer array: [index0, score0, index1, score1, ...]
    let mut flat = Vec::with_capacity(matches.len() * 2);
    for (idx, sc) in matches {
        flat.push(idx as i32);
        flat.push(sc);
    }

    let array = match env.new_int_array(flat.len() as i32) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    if env.set_int_array_region(&array, 0, &flat).is_err() {
        return std::ptr::null_mut();
    }

    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_countLinesMmapNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
) -> jint {
    let p_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    mmap_log::count_lines_mmap(&p_str).unwrap_or(0) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getLineMmapNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    path: JString<'local>,
    line_index: jint,
) -> jstring {
    let p_str: String = match env.get_string(&path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let line = mmap_log::get_line_mmap(&p_str, line_index.max(0) as usize)
        .ok()
        .flatten()
        .unwrap_or_default();

    let output = env.new_string(line).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getSplicedTurnMmapNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    compact_path: JString<'local>,
    full_path: JString<'local>,
    line_index: jint,
) -> jstring {
    let cp_str: String = match env.get_string(&compact_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let fp_str: String = match env.get_string(&full_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let turn = mmap_log::get_spliced_turn_mmap(&cp_str, &fp_str, line_index.max(0) as usize)
        .ok()
        .flatten()
        .unwrap_or_default();

    let output = env.new_string(turn).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_extractTranscriptMetaMmapNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    transcript_path: JString<'local>,
) -> jstring {
    let p_str: String = match env.get_string(&transcript_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let meta = mmap_log::extract_transcript_meta_mmap(&p_str)
        .ok()
        .flatten();

    let json_str = serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_applyPatchNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    original: JString<'local>,
    patch_text: JString<'local>,
    reverse: jboolean,
) -> jstring {
    let orig: String = match env.get_string(&original) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let patch_str: String = match env.get_string(&patch_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let hunks = diff_patch::parse_unified_diff(&patch_str);
    let result = diff_patch::apply_patch(&orig, &hunks, reverse != 0);

    let json = match result {
        Ok(patched) => serde_json::json!({ "ok": true, "text": patched }),
        Err(e) => serde_json::json!({ "ok": false, "error": e.message, "hunk": e.hunk_index }),
    };

    let output = env.new_string(json.to_string()).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_threeWayMergeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    ours: JString<'local>,
    theirs: JString<'local>,
) -> jstring {
    let base_str: String = match env.get_string(&base) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ours_str: String = match env.get_string(&ours) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let theirs_str: String = match env.get_string(&theirs) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let result = diff_patch::three_way_merge(&base_str, &ours_str, &theirs_str);
    let json_str = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_resolveConflictsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    merged_text: JString<'local>,
    strategy: JString<'local>,
) -> jstring {
    let text: String = match env.get_string(&merged_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let strat_str: String = match env.get_string(&strategy) {
        Ok(s) => s.into(),
        Err(_) => "ours".to_string(),
    };

    let strat = match strat_str.to_lowercase().as_str() {
        "theirs" => diff_patch::ConflictStrategy::Theirs,
        "both" => diff_patch::ConflictStrategy::Both,
        _ => diff_patch::ConflictStrategy::Ours,
    };

    let resolved = diff_patch::resolve_conflicts(&text, strat);
    let output = env.new_string(resolved).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getCompletionsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    prefix: JString<'local>,
    ext: JString<'local>,
    symbols_json: JString<'local>,
    limit: jint,
) -> jstring {
    let p_str: String = match env.get_string(&prefix) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ext_str: String = match env.get_string(&ext) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let syms_str: String = match env.get_string(&symbols_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let mut trie = lsp_diagnostics::CompletionTrie::new();

    // Default language keywords and types
    for item in lsp_diagnostics::get_default_completions(&ext_str) {
        trie.insert(item);
    }

    // Additional user symbols
    if !syms_str.is_empty() {
        if let Ok(extra_items) = serde_json::from_str::<Vec<lsp_diagnostics::CompletionItem>>(&syms_str) {
            for item in extra_items {
                trie.insert(item);
            }
        }
    }

    let matches = trie.complete(&p_str, limit.max(1) as usize);
    let json_str = serde_json::to_string(&matches).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_extractFileSymbolsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JString<'local>,
    ext: JString<'local>,
) -> jstring {
    let c_str: String = match env.get_string(&content) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let ext_str: String = match env.get_string(&ext) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let symbols = lsp_diagnostics::extract_file_symbols(&c_str, &ext_str);
    let json_str = serde_json::to_string(&symbols).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_parseDiagnosticsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw_output: JString<'local>,
) -> jstring {
    let r_str: String = match env.get_string(&raw_output) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let diags = lsp_diagnostics::parse_diagnostics(&r_str);
    let json_str = serde_json::to_string(&diags).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_frameJsonRpcRequestNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    id: jint,
    method: JString<'local>,
    params_json: JString<'local>,
) -> jstring {
    let m_str: String = match env.get_string(&method) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let p_str: String = match env.get_string(&params_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let params_val: Option<serde_json::Value> = if !p_str.trim().is_empty() {
        serde_json::from_str(&p_str).ok()
    } else {
        None
    };

    let framed = mcp_rpc::frame_jsonrpc_request(id.max(0) as u64, &m_str, params_val);
    let output = env.new_string(framed).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_parseJsonRpcResponseNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    raw: JString<'local>,
) -> jstring {
    let r_str: String = match env.get_string(&raw) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let json_str = match mcp_rpc::parse_jsonrpc_response(&r_str) {
        Ok(res) => serde_json::to_string(&res).unwrap_or_else(|_| "{}".to_string()),
        Err(e) => serde_json::json!({
            "jsonrpc": "2.0",
            "id": 0,
            "error": { "code": -32700, "message": e }
        }).to_string(),
    };

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_validateAgentDagNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    nodes_json: JString<'local>,
) -> jstring {
    let input: String = match env.get_string(&nodes_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let nodes: Vec<agent_dag::AgentTaskNode> = serde_json::from_str(&input).unwrap_or_default();
    let result = agent_dag::validate_and_schedule_dag(&nodes);
    let json_str = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_evaluateConsensusNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    proposals_json: JString<'local>,
    strategy_str: JString<'local>,
) -> jstring {
    let p_input: String = match env.get_string(&proposals_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let s_input: String = match env.get_string(&strategy_str) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let proposals: Vec<agent_dag::AgentProposal> = serde_json::from_str(&p_input).unwrap_or_default();
    let strat = agent_dag::ConsensusStrategy::parse(&s_input);
    let result = agent_dag::evaluate_agent_consensus(&proposals, strat);
    let json_str = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

fn get_float_vec(env: &mut JNIEnv, arr: &JFloatArray) -> Vec<f32> {
    match env.get_array_length(arr) {
        Ok(len) if len > 0 => {
            let mut buf = vec![0.0f32; len as usize];
            if env.get_float_array_region(arr, 0, &mut buf).is_ok() {
                buf
            } else {
                Vec::new()
            }
        }
        _ => Vec::new(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_cosineSimilarityNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    vec_a: JFloatArray<'local>,
    vec_b: JFloatArray<'local>,
) -> jfloat {
    let a = get_float_vec(&mut env, &vec_a);
    let b = get_float_vec(&mut env, &vec_b);
    vector_rag::cosine_similarity(&a, &b)
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_searchRagDocumentsNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query_vec: JFloatArray<'local>,
    documents_json: JString<'local>,
    top_k: jint,
    min_score: jfloat,
) -> jstring {
    let query = get_float_vec(&mut env, &query_vec);
    let docs_input: String = match env.get_string(&documents_json) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let docs: Vec<vector_rag::VectorDocument> = serde_json::from_str(&docs_input).unwrap_or_default();
    let results = vector_rag::search_knn(&query, &docs, top_k.max(0) as usize, min_score);
    let json_str = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());

    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_estimateTokenCountNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jint {
    let input: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    vector_rag::estimate_token_count(&input) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_truncateToTokenBudgetNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
    max_tokens: jint,
) -> jstring {
    let input: String = match env.get_string(&text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let (truncated, tokens) = vector_rag::truncate_to_token_budget(&input, max_tokens.max(0) as usize);
    let json_val = serde_json::json!({
        "text": truncated,
        "token_count": tokens,
    });

    let output = env.new_string(json_val.to_string()).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_initAudioRingBufferNative<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    capacity_samples: jint,
) {
    let mut lock = RING_BUFFER.lock().unwrap();
    *lock = Some(audio_dsp::AudioRingBuffer::new(capacity_samples.max(256) as usize));
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_writeAudioRingBufferNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pcm_bytes: JByteArray<'local>,
) -> jint {
    let pcm_vec: Vec<u8> = match env.convert_byte_array(&pcm_bytes) {
        Ok(v) => v,
        Err(_) => return 0,
    };

    let sample_count = pcm_vec.len() / 2;
    if sample_count == 0 {
        return 0;
    }

    let mut samples = Vec::with_capacity(sample_count);
    for chunk in pcm_vec.chunks_exact(2) {
        samples.push(i16::from_le_bytes([chunk[0], chunk[1]]));
    }

    let mut lock = RING_BUFFER.lock().unwrap();
    let ring = lock.get_or_insert_with(|| audio_dsp::AudioRingBuffer::new(16000));
    ring.write(&samples) as jint
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_computeEqualizerBandsNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pcm_bytes: JByteArray<'local>,
    num_bands: jint,
) -> jfloatArray {
    let pcm_vec: Vec<u8> = match env.convert_byte_array(&pcm_bytes) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    let sample_count = pcm_vec.len() / 2;
    let mut samples = Vec::with_capacity(sample_count);
    for chunk in pcm_vec.chunks_exact(2) {
        samples.push(i16::from_le_bytes([chunk[0], chunk[1]]));
    }

    let bands = audio_dsp::compute_equalizer_bands(&samples, 16000, num_bands.max(4) as usize);

    let output_array = match env.new_float_array(bands.len() as jint) {
        Ok(arr) => arr,
        Err(_) => return std::ptr::null_mut(),
    };

    if env.set_float_array_region(&output_array, 0, &bands).is_err() {
        return std::ptr::null_mut();
    }

    output_array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_computeAudioTelemetryNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    pcm_bytes: JByteArray<'local>,
    sample_rate: jint,
) -> jstring {
    let pcm_vec: Vec<u8> = match env.convert_byte_array(&pcm_bytes) {
        Ok(v) => v,
        Err(_) => return std::ptr::null_mut(),
    };

    let mut noise_lock = TELEMETRY_NOISE.lock().unwrap();
    let telemetry = audio_dsp::analyze_pcm_telemetry(
        &pcm_vec,
        sample_rate.max(8000) as u32,
        8,
        &mut noise_lock,
    );

    let json_str = serde_json::to_string(&telemetry).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_indexDocumentNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    doc_json: JString<'local>,
) -> jboolean {
    let input: String = match env.get_string(&doc_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let doc: db_search::DocumentRecord = match serde_json::from_str(&input) {
        Ok(d) => d,
        Err(_) => return 0,
    };

    let mut lock = SEARCH_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(db_search::InvertedIndex::new);
    index.insert(doc);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_searchHybridNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    query_text: JString<'local>,
    query_vec: JFloatArray<'local>,
    alpha: jfloat,
    top_k: jint,
) -> jstring {
    let q_text: String = match env.get_string(&query_text) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };
    let query_embedding = get_float_vec(&mut env, &query_vec);

    let mut lock = SEARCH_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(db_search::InvertedIndex::new);
    let results = index.search_hybrid(&q_text, &query_embedding, alpha, top_k.max(1) as usize);

    let json_str = serde_json::to_string(&results).unwrap_or_else(|_| "[]".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getIndexStatsNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let mut lock = SEARCH_INDEX.lock().unwrap();
    let index = lock.get_or_insert_with(db_search::InvertedIndex::new);
    let stats = index.stats();

    let json_str = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_recordNetworkTrafficNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    entry_json: JString<'local>,
) -> jboolean {
    let input: String = match env.get_string(&entry_json) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    let entry: net_traffic::HttpTrafficEntry = match serde_json::from_str(&input) {
        Ok(e) => e,
        Err(_) => return 0,
    };

    let mut lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let inspector = lock.get_or_insert_with(net_traffic::TrafficInspector::default);
    inspector.record(entry);
    1
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getRecentNetworkTrafficNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    limit: jint,
) -> jstring {
    let lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let entries = match &*lock {
        Some(inspector) => inspector.get_recent(limit.max(1) as usize),
        None => Vec::new(),
    };

    let json_str = serde_json::to_string(&entries).unwrap_or_else(|_| "[]".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getNetworkMetricsNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    let lock = TRAFFIC_INSPECTOR.lock().unwrap();
    let metrics = match &*lock {
        Some(inspector) => inspector.get_metrics(),
        None => net_traffic::TrafficInspector::default().get_metrics(),
    };

    let json_str = serde_json::to_string(&metrics).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_clearNetworkTrafficNative<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) {
    let mut lock = TRAFFIC_INSPECTOR.lock().unwrap();
    if let Some(inspector) = lock.as_mut() {
        inspector.clear();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_getHardwareTelemetryNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    override_temp: jfloat,
) -> jstring {
    let opt_temp = if override_temp > 0.0 {
        Some(override_temp)
    } else {
        None
    };
    let telem = hw_telemetry::collect_hardware_telemetry(opt_temp);
    let json_str = serde_json::to_string(&telem).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_evaluateThermalThrottleNative<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    temp_celsius: jfloat,
    total_cores: jint,
) -> jstring {
    let advice = hw_telemetry::evaluate_thermal_throttle(temp_celsius, total_cores.max(1) as usize);
    let json_str = serde_json::to_string(&advice).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_inspectGgufHeaderNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    file_path: JString<'local>,
) -> jstring {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let res = model_runtime::inspect_gguf_file(std::path::Path::new(&path_str));
    let json_str = match res {
        Ok(meta) => serde_json::to_string(&meta).unwrap_or_else(|_| "{}".to_string()),
        Err(e) => serde_json::json!({ "error": e }).to_string(),
    };
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_gemini_native_AntiBotNative_validateModelRuntimeNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    model_path: JString<'local>,
    context_length: jint,
) -> jstring {
    let path_str: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let meta = match model_runtime::inspect_gguf_file(std::path::Path::new(&path_str)) {
        Ok(m) => m,
        Err(e) => {
            let err_json = serde_json::json!({ "feasible": false, "warning": e }).to_string();
            let output = env.new_string(err_json).unwrap();
            return output.into_raw();
        }
    };

    let telem = hw_telemetry::collect_hardware_telemetry(None);
    let avail_ram = telem.memory.available_mb;
    let online_cores = telem.online_cores_count;

    let validation = model_runtime::validate_model_runtime(
        &meta,
        context_length.max(0) as usize,
        avail_ram,
        online_cores,
    );

    let json_str = serde_json::to_string(&validation).unwrap_or_else(|_| "{}".to_string());
    let output = env.new_string(json_str).unwrap();
    output.into_raw()
}













