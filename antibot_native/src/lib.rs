use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;

pub mod crypto;
pub mod diff;
pub mod markdown;

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
    let output = env.new_string("1.0.63-rust-neon").unwrap();
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
