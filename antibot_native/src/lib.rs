use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jintArray, jstring};
use jni::JNIEnv;

pub mod crypto;
pub mod diff;
pub mod markdown;
pub mod syntax;

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
    let output = env.new_string("1.0.64-rust-syntax-neon").unwrap();
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

