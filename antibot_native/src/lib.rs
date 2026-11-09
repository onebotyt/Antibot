use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jfloatArray, jint, jintArray, jobjectArray, jshort, jstring};
use jni::JNIEnv;
use std::sync::Mutex;

pub mod audio;
pub mod crypto;
pub mod diff;
pub mod fuzzy;
pub mod markdown;
pub mod mmap_log;
pub mod syntax;

static VAD: Mutex<Option<audio::VadEngine>> = Mutex::new(None);

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
    let output = env.new_string("1.0.68-rust-mmap-cache").unwrap();
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



