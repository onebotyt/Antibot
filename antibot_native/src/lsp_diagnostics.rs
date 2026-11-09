//! Native Language Server Protocol (LSP) Diagnostics & Auto-Completion Gateway.
//!
//! Provides:
//! - `CompletionItem`, `CompletionKind` and `CompletionTrie` — microsecond prefix & fuzzy auto-completion.
//! - `extract_file_symbols` — single-pass scanner for functions, classes, structs, variables across Rust, Kotlin, TS/JS, and Python.
//! - `parse_diagnostics` — universal compiler/linter error & warning parser with UTF-16 Compose span mapping.
//! - `get_default_completions` — offline keyword & standard symbol completion for popular languages.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

// ---------------------------------------------------------------------------
// 1. Completion Types & Trie
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct CompletionItem {
    pub label: String,
    pub kind: String, // "function", "class", "struct", "variable", "keyword", "snippet", etc.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub detail: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub documentation: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub insert_text: Option<String>,
    pub sort_priority: i32,
}

#[derive(Debug, Default)]
struct TrieNode {
    children: BTreeMap<char, TrieNode>,
    items: Vec<CompletionItem>,
}

#[derive(Debug, Default)]
pub struct CompletionTrie {
    root: TrieNode,
}

impl CompletionTrie {
    pub fn new() -> Self {
        Self {
            root: TrieNode::default(),
        }
    }

    /// Inserts a completion item into the prefix Trie.
    pub fn insert(&mut self, item: CompletionItem) {
        let mut node = &mut self.root;
        for ch in item.label.to_lowercase().chars() {
            node = node.children.entry(ch).or_default();
        }
        // Avoid duplicate labels with same kind
        if !node.items.iter().any(|existing| existing.label == item.label && existing.kind == item.kind) {
            node.items.push(item);
        }
    }

    /// Queries completions matching prefix, returning up to `limit` items sorted by priority.
    pub fn complete(&self, prefix: &str, limit: usize) -> Vec<CompletionItem> {
        let prefix_lower = prefix.to_lowercase();
        let mut node = &self.root;

        for ch in prefix_lower.chars() {
            match node.children.get(&ch) {
                Some(next) => node = next,
                None => return Vec::new(),
            }
        }

        let mut results = Vec::new();
        Self::collect_all(node, &mut results);

        results.sort_by(|a, b| {
            b.sort_priority
                .cmp(&a.sort_priority)
                .then_with(|| a.label.len().cmp(&b.label.len()))
                .then_with(|| a.label.cmp(&b.label))
        });

        results.truncate(limit);
        results
    }

    fn collect_all(node: &TrieNode, results: &mut Vec<CompletionItem>) {
        results.extend(node.items.iter().cloned());
        for child in node.children.values() {
            Self::collect_all(child, results);
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Default Language Completions (Offline Engine)
// ---------------------------------------------------------------------------

pub fn get_default_completions(ext: &str) -> Vec<CompletionItem> {
    let clean_ext = ext.trim_start_matches('.').to_lowercase();
    let mut items = Vec::new();

    let (keywords, types, snippets) = match clean_ext.as_str() {
        "rs" => (
            vec![
                "fn", "let", "mut", "struct", "enum", "trait", "impl", "pub", "use",
                "mod", "match", "if", "else", "loop", "while", "for", "in", "return",
                "break", "continue", "as", "async", "await", "where", "self", "Self",
                "const", "static", "unsafe", "ref", "type",
            ],
            vec![
                "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64",
                "u128", "usize", "f32", "f64", "bool", "char", "str", "String",
                "Vec", "Option", "Result", "Box", "Rc", "Arc", "Mutex", "Cell",
            ],
            vec![
                ("fn_main", "fn main() {\n    $0\n}", "main function entrypoint"),
                ("test", "#[test]\nfn test_$1() {\n    $0\n}", "unit test function"),
                ("println", "println!(\"{}\", $0);", "print with newline"),
            ],
        ),
        "kt" | "kts" => (
            vec![
                "fun", "val", "var", "class", "interface", "object", "companion",
                "package", "import", "return", "if", "else", "when", "for", "while",
                "do", "try", "catch", "finally", "throw", "null", "is", "as",
                "in", "out", "suspend", "override", "data", "sealed", "open", "private",
                "protected", "public", "internal",
            ],
            vec![
                "Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char",
                "String", "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet",
                "Any", "Unit", "Nothing", "Composable",
            ],
            vec![
                ("fun_main", "fun main(args: Array<String>) {\n    $0\n}", "main entrypoint"),
                ("composable", "@Composable\nfun $1() {\n    $0\n}", "Compose UI function"),
            ],
        ),
        "ts" | "tsx" | "js" | "jsx" => (
            vec![
                "function", "const", "let", "var", "class", "interface", "type",
                "import", "export", "default", "from", "return", "if", "else",
                "switch", "case", "for", "while", "do", "try", "catch", "finally",
                "throw", "new", "this", "typeof", "instanceof", "async", "await",
                "yield", "extends", "implements", "null", "undefined",
            ],
            vec![
                "string", "number", "boolean", "any", "unknown", "never", "void",
                "object", "symbol", "bigint", "Array", "Record", "Promise", "Map", "Set",
            ],
            vec![
                ("arrow_fn", "($1) => {\n    $0\n}", "arrow function"),
                ("console_log", "console.log($0);", "console log"),
            ],
        ),
        "py" => (
            vec![
                "def", "class", "import", "from", "as", "return", "if", "elif",
                "else", "for", "while", "try", "except", "finally", "raise", "with",
                "yield", "async", "await", "lambda", "pass", "break", "continue",
                "None", "True", "False", "and", "or", "not", "is", "in", "self",
            ],
            vec![
                "int", "float", "str", "bool", "list", "dict", "set", "tuple",
                "bytes", "Optional", "Union", "Any", "List", "Dict",
            ],
            vec![
                ("def_main", "if __name__ == '__main__':\n    main()", "main entrypoint"),
            ],
        ),
        _ => (vec![], vec![], vec![]),
    };

    for kw in keywords {
        items.push(CompletionItem {
            label: kw.to_string(),
            kind: "keyword".to_string(),
            detail: Some(format!("{} keyword", kw)),
            documentation: None,
            insert_text: Some(kw.to_string()),
            sort_priority: 50,
        });
    }

    for ty in types {
        items.push(CompletionItem {
            label: ty.to_string(),
            kind: "type".to_string(),
            detail: Some(format!("{} type", ty)),
            documentation: None,
            insert_text: Some(ty.to_string()),
            sort_priority: 60,
        });
    }

    for (name, snippet, doc) in snippets {
        items.push(CompletionItem {
            label: name.to_string(),
            kind: "snippet".to_string(),
            detail: Some("code snippet".to_string()),
            documentation: Some(doc.to_string()),
            insert_text: Some(snippet.to_string()),
            sort_priority: 40,
        });
    }

    items
}

// ---------------------------------------------------------------------------
// 3. File Symbol Extractor (Single-Pass Scanner)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FileSymbol {
    pub name: String,
    pub kind: String, // "function", "class", "struct", "interface", "variable", "constant"
    pub line: usize,  // 1-indexed
    pub character: usize, // 1-indexed (UTF-16 code units)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub container_name: Option<String>,
}

/// Extracts declared symbols from source code across Rust, Kotlin, TS/JS, and Python.
pub fn extract_file_symbols(content: &str, ext: &str) -> Vec<FileSymbol> {
    let clean_ext = ext.trim_start_matches('.').to_lowercase();
    let mut symbols = Vec::new();
    let mut current_container: Option<String> = None;
    let mut brace_depth: usize = 0;

    for (line_idx, line) in content.lines().enumerate() {
        let line_num = line_idx + 1;
        let trimmed = line.trim();

        if trimmed.is_empty() || trimmed.starts_with("//") || trimmed.starts_with('#') || trimmed.starts_with("/*") {
            continue;
        }

        let words: Vec<&str> = trimmed.split_whitespace().collect();
        if words.is_empty() {
            continue;
        }

        // Rust patterns
        if clean_ext == "rs" {
            if let Some(pos) = words.iter().position(|&w| w == "fn") {
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split('(').next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: if current_container.is_some() { "method".to_string() } else { "function".to_string() },
                            line: line_num,
                            character: col,
                            container_name: current_container.clone(),
                        });
                    }
                }
            } else if let Some(pos) = words.iter().position(|&w| w == "struct" || w == "enum" || w == "trait") {
                let kind = words[pos];
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split(['{', '<', '(']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        current_container = Some(name.to_string());
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: kind.to_string(),
                            line: line_num,
                            character: col,
                            container_name: None,
                        });
                    }
                }
            } else if let Some(pos) = words.iter().position(|&w| w == "impl") {
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split(['{', '<']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        current_container = Some(name.to_string());
                    }
                }
            }
        }

        // Kotlin patterns
        if clean_ext == "kt" || clean_ext == "kts" {
            if let Some(pos) = words.iter().position(|&w| w == "fun") {
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split('(').next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: if current_container.is_some() { "method".to_string() } else { "function".to_string() },
                            line: line_num,
                            character: col,
                            container_name: current_container.clone(),
                        });
                    }
                }
            } else if let Some(pos) = words.iter().position(|&w| w == "class" || w == "interface" || w == "object") {
                let kind = words[pos];
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split(['(', '<', '{', ':']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        current_container = Some(name.to_string());
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: kind.to_string(),
                            line: line_num,
                            character: col,
                            container_name: None,
                        });
                    }
                }
            }
        }

        // TypeScript / JavaScript
        if clean_ext == "ts" || clean_ext == "tsx" || clean_ext == "js" || clean_ext == "jsx" {
            if let Some(pos) = words.iter().position(|&w| w == "function") {
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split(['(', '<']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: "function".to_string(),
                            line: line_num,
                            character: col,
                            container_name: current_container.clone(),
                        });
                    }
                }
            } else if let Some(pos) = words.iter().position(|&w| w == "class" || w == "interface" || w == "type") {
                let kind = words[pos];
                if let Some(name_part) = words.get(pos + 1) {
                    let name = name_part.split(['{', '<', ' ', '=']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        current_container = Some(name.to_string());
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: kind.to_string(),
                            line: line_num,
                            character: col,
                            container_name: None,
                        });
                    }
                }
            }
        }

        // Python patterns
        if clean_ext == "py" {
            if words[0] == "def" {
                if let Some(name_part) = words.get(1) {
                    let name = name_part.split('(').next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: if current_container.is_some() { "method".to_string() } else { "function".to_string() },
                            line: line_num,
                            character: col,
                            container_name: current_container.clone(),
                        });
                    }
                }
            } else if words[0] == "class" {
                if let Some(name_part) = words.get(1) {
                    let name = name_part.split(['(', ':']).next().unwrap_or("").trim();
                    if is_valid_ident(name) {
                        let col = compute_utf16_col(line, name);
                        current_container = Some(name.to_string());
                        symbols.push(FileSymbol {
                            name: name.to_string(),
                            kind: "class".to_string(),
                            line: line_num,
                            character: col,
                            container_name: None,
                        });
                    }
                }
            }
        }

        if clean_ext == "py" {
            if !line.starts_with(' ') && !line.starts_with('\t') {
                current_container = None;
            }
        } else {
            for ch in line.chars() {
                if ch == '{' {
                    brace_depth += 1;
                } else if ch == '}' {
                    brace_depth = brace_depth.saturating_sub(1);
                    if brace_depth == 0 {
                        current_container = None;
                    }
                }
            }
        }
    }

    symbols
}

fn is_valid_ident(s: &str) -> bool {
    if s.is_empty() {
        return false;
    }
    let mut chars = s.chars();
    let first = chars.next().unwrap();
    if !first.is_alphabetic() && first != '_' {
        return false;
    }
    chars.all(|c| c.is_alphanumeric() || c == '_')
}

fn compute_utf16_col(line: &str, target: &str) -> usize {
    if let Some(byte_idx) = line.find(target) {
        let prefix = &line[..byte_idx];
        prefix.encode_utf16().count() + 1
    } else {
        1
    }
}

// ---------------------------------------------------------------------------
// 4. Universal Diagnostic Parser
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Diagnostic {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file: Option<String>,
    pub line: usize, // 1-indexed
    pub col: usize,  // 1-indexed (UTF-16)
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_line: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end_col: Option<usize>,
    pub severity: String, // "error", "warning", "info", "hint"
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// Parses raw compiler/linter error and warning output into normalized LSP diagnostics.
pub fn parse_diagnostics(raw_output: &str) -> Vec<Diagnostic> {
    let mut diagnostics = Vec::new();

    for line in raw_output.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        // 1. Rust / GCC / Clang / TSC pattern:
        // path/to/file.rs:12:5: error[E0308]: mismatched types
        // path/to/file.ts:15:9 - error TS2322: Type 'string' is not assignable to type 'number'.
        if let Some(diag) = parse_colon_separated_diagnostic(trimmed) {
            diagnostics.push(diag);
            continue;
        }

        // 2. Pyright / Flake8 pattern:
        // file.py:10:5: E123 indentation error
        if let Some(diag) = parse_python_diagnostic(trimmed) {
            diagnostics.push(diag);
            continue;
        }

        // 3. Kotlin compiler / Gradle pattern:
        // e: /path/to/File.kt: (15, 23): Unresolved reference: foo
        // w: /path/to/File.kt: (20, 5): Parameter 'x' is never used
        if let Some(diag) = parse_kotlin_diagnostic(trimmed) {
            diagnostics.push(diag);
            continue;
        }
    }

    diagnostics
}

fn parse_colon_separated_diagnostic(line: &str) -> Option<Diagnostic> {
    let parts: Vec<&str> = line.splitn(4, ':').collect();
    if parts.len() < 4 {
        return None;
    }

    let file = parts[0].trim();
    let line_num: usize = parts[1].trim().parse().ok()?;
    let col_str = parts[2].trim();
    let col_num: usize = col_str.split_whitespace().next()?.parse().ok()?;
    let rest = parts[3].trim();

    let (severity, message, code) = parse_severity_and_code(rest);

    Some(Diagnostic {
        file: Some(file.to_string()),
        line: line_num,
        col: col_num,
        end_line: Some(line_num),
        end_col: Some(col_num + 5),
        severity,
        message,
        source: Some("compiler".to_string()),
        code,
    })
}

fn parse_python_diagnostic(line: &str) -> Option<Diagnostic> {
    let parts: Vec<&str> = line.splitn(4, ':').collect();
    if parts.len() != 4 {
        return None;
    }
    let file = parts[0].trim();
    let line_num: usize = parts[1].trim().parse().ok()?;
    let col_num: usize = parts[2].trim().parse().ok()?;
    let rest = parts[3].trim();

    let (code, msg) = if let Some((c, m)) = rest.split_once(' ') {
        (Some(c.to_string()), m.to_string())
    } else {
        (None, rest.to_string())
    };

    let severity = if code.as_deref().unwrap_or("").starts_with('W') {
        "warning".to_string()
    } else {
        "error".to_string()
    };

    Some(Diagnostic {
        file: Some(file.to_string()),
        line: line_num,
        col: col_num,
        end_line: Some(line_num),
        end_col: Some(col_num + 3),
        severity,
        message: msg,
        source: Some("linter".to_string()),
        code,
    })
}

fn parse_kotlin_diagnostic(line: &str) -> Option<Diagnostic> {
    // e: /path/to/File.kt: (15, 23): Unresolved reference: foo
    let (prefix, rest) = line.split_once(':')?;
    let severity = match prefix.trim() {
        "e" => "error",
        "w" => "warning",
        "i" => "info",
        _ => return None,
    };

    let (file_part, coords_and_msg) = rest.split_once(": (")?;
    let file = file_part.trim();

    let (coords, msg_part) = coords_and_msg.split_once("): ")?;
    let (line_s, col_s) = coords.split_once(',')?;
    let line_num: usize = line_s.trim().parse().ok()?;
    let col_num: usize = col_s.trim().parse().ok()?;

    Some(Diagnostic {
        file: Some(file.to_string()),
        line: line_num,
        col: col_num,
        end_line: Some(line_num),
        end_col: Some(col_num + 5),
        severity: severity.to_string(),
        message: msg_part.trim().to_string(),
        source: Some("kotlinc".to_string()),
        code: None,
    })
}

fn parse_severity_and_code(rest: &str) -> (String, String, Option<String>) {
    let lower = rest.to_lowercase();
    let severity = if lower.contains("error") {
        "error".to_string()
    } else if lower.contains("warning") {
        "warning".to_string()
    } else if lower.contains("info") {
        "info".to_string()
    } else {
        "hint".to_string()
    };

    // Extract code like [E0308] or TS2322
    let mut code = None;
    if let Some(start) = rest.find('[') {
        if let Some(end) = rest[start..].find(']') {
            code = Some(rest[start + 1..start + end].to_string());
        }
    } else if let Some(ts_idx) = rest.find("TS") {
        let code_cand: String = rest[ts_idx..].chars().take_while(|c| c.is_alphanumeric()).collect();
        if !code_cand.is_empty() {
            code = Some(code_cand);
        }
    }

    (severity, rest.to_string(), code)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_completion_trie_insert_and_query() {
        let mut trie = CompletionTrie::new();
        trie.insert(CompletionItem {
            label: "println".to_string(),
            kind: "function".to_string(),
            detail: Some("print macro".to_string()),
            documentation: None,
            insert_text: Some("println!($0)".to_string()),
            sort_priority: 100,
        });
        trie.insert(CompletionItem {
            label: "print".to_string(),
            kind: "function".to_string(),
            detail: None,
            documentation: None,
            insert_text: None,
            sort_priority: 90,
        });
        trie.insert(CompletionItem {
            label: "process".to_string(),
            kind: "module".to_string(),
            detail: None,
            documentation: None,
            insert_text: None,
            sort_priority: 50,
        });

        let matches = trie.complete("print", 10);
        assert_eq!(matches.len(), 2);
        assert_eq!(matches[0].label, "println");
        assert_eq!(matches[1].label, "print");

        let matches_p = trie.complete("pr", 10);
        assert_eq!(matches_p.len(), 3);
    }

    #[test]
    fn test_extract_file_symbols_rust() {
        let code = r#"
pub struct UserProfile {
    pub id: u64,
}

impl UserProfile {
    pub fn get_name(&self) -> String {
        "test".to_string()
    }
}

fn calculate_total(a: i32, b: i32) -> i32 {
    a + b
}
"#;
        let symbols = extract_file_symbols(code, "rs");
        assert!(symbols.iter().any(|s| s.name == "UserProfile" && s.kind == "struct"));
        assert!(symbols.iter().any(|s| s.name == "get_name" && s.kind == "method" && s.container_name.as_deref() == Some("UserProfile")));
        assert!(symbols.iter().any(|s| s.name == "calculate_total" && s.kind == "function"));
    }

    #[test]
    fn test_extract_file_symbols_kotlin() {
        let code = r#"
class MainActivity : ComponentActivity() {
    fun setupView() {
    }
}
fun globalHelper() {
}
"#;
        let symbols = extract_file_symbols(code, "kt");
        assert!(symbols.iter().any(|s| s.name == "MainActivity" && s.kind == "class"));
        assert!(symbols.iter().any(|s| s.name == "setupView" && s.kind == "method"));
        assert!(symbols.iter().any(|s| s.name == "globalHelper" && s.kind == "function"));
    }

    #[test]
    fn test_parse_diagnostics_rustc() {
        let output = "src/main.rs:42:15: error[E0308]: mismatched types\n";
        let diags = parse_diagnostics(output);
        assert_eq!(diags.len(), 1);
        assert_eq!(diags[0].file.as_deref(), Some("src/main.rs"));
        assert_eq!(diags[0].line, 42);
        assert_eq!(diags[0].col, 15);
        assert_eq!(diags[0].severity, "error");
        assert_eq!(diags[0].code.as_deref(), Some("E0308"));
    }

    #[test]
    fn test_parse_diagnostics_kotlinc() {
        let output = "e: /app/src/Main.kt: (10, 5): Unresolved reference: test\n";
        let diags = parse_diagnostics(output);
        assert_eq!(diags.len(), 1);
        assert_eq!(diags[0].file.as_deref(), Some("/app/src/Main.kt"));
        assert_eq!(diags[0].line, 10);
        assert_eq!(diags[0].col, 5);
        assert_eq!(diags[0].severity, "error");
        assert_eq!(diags[0].message, "Unresolved reference: test");
    }

    #[test]
    fn test_default_completions() {
        let rust_kw = get_default_completions("rs");
        assert!(rust_kw.iter().any(|c| c.label == "fn"));
        assert!(rust_kw.iter().any(|c| c.label == "Vec"));

        let kt_kw = get_default_completions("kt");
        assert!(kt_kw.iter().any(|c| c.label == "fun"));
        assert!(kt_kw.iter().any(|c| c.label == "Composable"));
    }
}
