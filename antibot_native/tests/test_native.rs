use antibot_native::{crypto, diff, markdown};

#[test]
fn test_parse_markdown_blocks() {
    let md = r#"# Main Header

This is a test paragraph with **bold** text and `inline code`.

$$
E = mc^2
$$

```rust
fn main() {
    println!("Hello, AntiBot Native!");
}
```

> [!NOTE]
> This is a high-speed native alert callout.

- [x] Completed task item
- [ ] Incomplete task item
- Regular bullet item
  - Indented bullet

| Feature | Language | Performance |
| :--- | :---: | ---: |
| UI Parsing | Rust | 120 FPS |
| Diff Engine | Rust | Instant |
"#;

    let blocks = markdown::parse_markdown_blocks(md);
    assert!(!blocks.is_empty(), "Blocks should not be empty");

    let json = serde_json::to_string(&blocks).expect("Should serialize to JSON");
    println!("Parsed {} blocks: {}", blocks.len(), json);

    assert!(json.contains("Main Header"));
    assert!(json.contains("E = mc^2"));
    assert!(json.contains("Hello, AntiBot Native!"));
    assert!(json.contains("NOTE"));
    assert!(json.contains("Completed task item"));
    assert!(json.contains("120 FPS"));
}

#[test]
fn test_parse_inline_spans() {
    let text = "Here is **bold text**, `code span`, $E = mc^2$, and [AntiBot](https://github.com/onebotyt/Antibot).";
    let spans = markdown::parse_inline_spans(text);
    assert!(!spans.is_empty());

    let types: Vec<&str> = spans.iter().map(|s| s.span_type.as_str()).collect();
    assert!(types.contains(&"bold"));
    assert!(types.contains(&"code"));
    assert!(types.contains(&"math_inline"));
    assert!(types.contains(&"link"));
}

#[test]
fn test_compute_diff() {
    let old = "line 1\nline 2\nline 3\n";
    let new = "line 1\nmodified line 2\nline 3\nline 4\n";

    let diff_result = diff::compute_unified_diff(old, new);
    assert_eq!(diff_result.total_deletions, 1);
    assert_eq!(diff_result.total_additions, 2);
    assert!(!diff_result.hunks.is_empty());
}

#[test]
fn test_compute_sha256() {
    let hash = crypto::compute_sha256_hex(b"antibot");
    // echo -n "antibot" | sha256sum -> 4296df4...
    assert_eq!(hash.len(), 64);
}
