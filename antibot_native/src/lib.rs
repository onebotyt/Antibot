use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jfloatArray, jint, jintArray, jshort, jstring};
use jni::JNIEnv;
use std::sync::Mutex;

pub mod audio;
pub mod crypto;
pub mod diff;
pub mod markdown;
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
    let output = env.new_string("1.0.65-rust-audio-dsp-neon").unwrap();
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


