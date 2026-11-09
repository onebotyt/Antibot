use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum MarkdownBlockDto {
    #[serde(rename = "paragraph")]
    Paragraph { text: String },
    #[serde(rename = "header")]
    Header { level: u8, text: String },
    #[serde(rename = "code")]
    Code { language: String, code: String },
    #[serde(rename = "math")]
    Math { latex: String, #[serde(rename = "isDisplay")] is_display: bool },
    #[serde(rename = "mermaid")]
    Mermaid { code: String },
    #[serde(rename = "interactive_ui")]
    InteractiveUi { #[serde(rename = "htmlCode")] html_code: String },
    #[serde(rename = "alert")]
    Alert { #[serde(rename = "alertType")] alert_type: String, title: Option<String>, text: String },
    #[serde(rename = "blockquote")]
    Blockquote { text: String },
    #[serde(rename = "bullet")]
    Bullet { indent: usize, text: String },
    #[serde(rename = "numbered")]
    Numbered { number: String, text: String },
    #[serde(rename = "task")]
    Task { #[serde(rename = "isChecked")] is_checked: bool, text: String },
    #[serde(rename = "table")]
    Table {
        headers: Vec<String>,
        rows: Vec<Vec<String>>,
        alignments: Vec<String>,
    },
    #[serde(rename = "details")]
    Details { summary: String, body: String, #[serde(rename = "defaultOpen")] default_open: bool },
    #[serde(rename = "image")]
    Image { alt: String, url: String },
    #[serde(rename = "video")]
    Video { alt: String, url: String },
    #[serde(rename = "youtube")]
    YouTubeVideo {
        #[serde(rename = "videoId")] video_id: String,
        #[serde(rename = "originalUrl")] original_url: String,
        title: String,
        #[serde(rename = "customThumbnailUrl")] custom_thumbnail_url: Option<String>,
    },
    #[serde(rename = "hr")]
    HorizontalRule,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InlineSpanDto {
    #[serde(rename = "type")]
    pub span_type: String, // "bold", "italic", "code", "strikethrough", "link", "math_inline", "file_link"
    pub start: usize,
    pub end: usize,
    pub text: String,
    pub url: Option<String>,
}

pub fn parse_markdown_blocks(content: &str) -> Vec<MarkdownBlockDto> {
    let mut result = Vec::new();
    let lines: Vec<&str> = content.lines().collect();
    let mut i = 0;

    while i < lines.len() {
        let line = lines[i];
        let trimmed = line.trim();

        // Empty lines
        if trimmed.is_empty() {
            i += 1;
            continue;
        }

        // 1. Math block starting with $$
        if trimmed.starts_with("$$") {
            let first_line = trimmed.strip_prefix("$$").unwrap_or("");
            if first_line.ends_with("$$") && first_line.len() >= 2 {
                let math = first_line.strip_suffix("$$").unwrap_or("").trim();
                result.push(MarkdownBlockDto::Math {
                    latex: math.to_string(),
                    is_display: true,
                });
                i += 1;
                continue;
            } else {
                let mut math_lines = Vec::new();
                if !first_line.trim().is_empty() {
                    math_lines.push(first_line.trim());
                }
                i += 1;
                while i < lines.len() && !lines[i].contains("$$") {
                    math_lines.push(lines[i]);
                    i += 1;
                }
                if i < lines.len() {
                    let last = lines[i].split("$$").next().unwrap_or("").trim();
                    if !last.is_empty() {
                        math_lines.push(last);
                    }
                }
                result.push(MarkdownBlockDto::Math {
                    latex: math_lines.join("\n"),
                    is_display: true,
                });
                i += 1;
                continue;
            }
        }

        // 2. Math block starting with \[
        if trimmed.starts_with("\\[") {
            let first_line = trimmed.strip_prefix("\\[").unwrap_or("");
            if let Some(pos) = first_line.find("\\]") {
                let math = first_line[..pos].trim();
                result.push(MarkdownBlockDto::Math {
                    latex: math.to_string(),
                    is_display: true,
                });
                i += 1;
                continue;
            } else {
                let mut math_lines = Vec::new();
                if !first_line.trim().is_empty() {
                    math_lines.push(first_line.trim());
                }
                i += 1;
                while i < lines.len() && !lines[i].contains("\\]") {
                    math_lines.push(lines[i]);
                    i += 1;
                }
                if i < lines.len() {
                    let last = lines[i].split("\\]").next().unwrap_or("").trim();
                    if !last.is_empty() {
                        math_lines.push(last);
                    }
                }
                result.push(MarkdownBlockDto::Math {
                    latex: math_lines.join("\n"),
                    is_display: true,
                });
                i += 1;
                continue;
            }
        }

        // 3. Fenced Code Block: ``` or ````
        if trimmed.starts_with("```") {
            let fence_len = trimmed.chars().take_while(|&c| c == '`').count();
            let lang = trimmed[fence_len..].trim();
            let mut code_lines = Vec::new();
            i += 1;
            while i < lines.len() {
                let curr_trim = lines[i].trim_start();
                if curr_trim.starts_with("```") && curr_trim.chars().take_while(|&c| c == '`').count() >= fence_len {
                    break;
                }
                code_lines.push(lines[i]);
                i += 1;
            }

            let code_content = code_lines.join("\n");
            let lang_lower = lang.to_ascii_lowercase();

            if lang_lower == "math" || lang_lower == "latex" || lang_lower == "katex" {
                result.push(MarkdownBlockDto::Math {
                    latex: code_content,
                    is_display: true,
                });
            } else if lang_lower == "mermaid" {
                result.push(MarkdownBlockDto::Mermaid {
                    code: code_content.trim().to_string(),
                });
            } else if lang_lower == "interactive_ui"
                || lang_lower == "interactive_app"
                || lang_lower == "html_app"
                || lang_lower == "canvas_app"
                || lang_lower == "widget"
                || lang_lower == "ui"
            {
                result.push(MarkdownBlockDto::InteractiveUi {
                    html_code: code_content.trim().to_string(),
                });
            } else {
                result.push(MarkdownBlockDto::Code {
                    language: lang.to_string(),
                    code: code_content,
                });
            }
            i += 1;
            continue;
        }

        // 4. <details> Collapsible Section
        if trimmed.to_ascii_lowercase().starts_with("<details") {
            let is_open = trimmed.to_ascii_lowercase().contains("open");
            let mut summary = "Details".to_string();
            let mut body_lines = Vec::new();
            i += 1;
            while i < lines.len() && !lines[i].trim().to_ascii_lowercase().starts_with("</details>") {
                let curr = lines[i];
                let curr_trim = curr.trim();
                if curr_trim.to_ascii_lowercase().starts_with("<summary>") {
                    let s = curr_trim
                        .replace("<summary>", "")
                        .replace("</summary>", "")
                        .replace("<SUMMARY>", "")
                        .replace("</SUMMARY>", "");
                    summary = s.trim().to_string();
                } else {
                    body_lines.push(curr);
                }
                i += 1;
            }
            result.push(MarkdownBlockDto::Details {
                summary,
                body: body_lines.join("\n").trim().to_string(),
                default_open: is_open,
            });
            i += 1;
            continue;
        }

        // 5. Video: ![alt](url.mp4) or [alt](url.mp4)
        if (trimmed.starts_with("![") || trimmed.starts_with("[")) && trimmed.ends_with(')') {
            if let Some(open_paren) = trimmed.rfind('(') {
                let url = &trimmed[open_paren + 1..trimmed.len() - 1];
                let is_video = url.ends_with(".mp4")
                    || url.ends_with(".webm")
                    || url.ends_with(".mov")
                    || url.ends_with(".m4v")
                    || url.ends_with(".3gp")
                    || url.contains(".mp4?");
                if is_video {
                    let alt = if trimmed.starts_with("![") {
                        &trimmed[2..open_paren - 1]
                    } else {
                        &trimmed[1..open_paren - 1]
                    };
                    result.push(MarkdownBlockDto::Video {
                        alt: alt.to_string(),
                        url: url.to_string(),
                    });
                    i += 1;
                    continue;
                }
            }
        }

        // 6. Standalone Image: ![alt](url)
        if trimmed.starts_with("![") && trimmed.ends_with(')') {
            if let Some(open_paren) = trimmed.rfind('(') {
                let alt = &trimmed[2..open_paren - 1];
                let url = &trimmed[open_paren + 1..trimmed.len() - 1];
                let yt_id = extract_youtube_video_id(url);
                if let Some(yt) = yt_id {
                    result.push(MarkdownBlockDto::YouTubeVideo {
                        video_id: yt.clone(),
                        original_url: format!("https://www.youtube.com/watch?v={}", yt),
                        title: if alt.is_empty() { "YouTube Video".to_string() } else { alt.to_string() },
                        custom_thumbnail_url: None,
                    });
                } else {
                    result.push(MarkdownBlockDto::Image {
                        alt: alt.to_string(),
                        url: url.to_string(),
                    });
                }
                i += 1;
                continue;
            }
        }

        // 7. Headers: #, ##, ###, etc.
        if trimmed.starts_with('#') {
            let hash_count = trimmed.chars().take_while(|&c| c == '#').count();
            if hash_count <= 6 && trimmed.chars().nth(hash_count) == Some(' ') {
                let text = trimmed[hash_count + 1..].trim();
                result.push(MarkdownBlockDto::Header {
                    level: hash_count as u8,
                    text: text.to_string(),
                });
                i += 1;
                continue;
            }
        }

        // 8. Horizontal Rule
        if trimmed == "---" || trimmed == "***" || trimmed == "___" || trimmed == "- - -" || trimmed == "* * *" {
            result.push(MarkdownBlockDto::HorizontalRule);
            i += 1;
            continue;
        }

        // 9. Alert / Callout Block: > [!NOTE] or > [!TIP]
        if trimmed.starts_with("> [!") && trimmed.contains(']') {
            let bracket_end = trimmed.find(']').unwrap();
            let alert_type = trimmed[4..bracket_end].to_ascii_uppercase();
            let first_line_extra = trimmed[bracket_end + 1..].trim();
            let mut alert_lines = Vec::new();
            if !first_line_extra.is_empty() {
                alert_lines.push(first_line_extra);
            }
            i += 1;
            while i < lines.len() {
                let l = lines[i].trim();
                if l.starts_with('>') {
                    let clean = l.strip_prefix('>').unwrap_or("").trim();
                    alert_lines.push(clean);
                    i += 1;
                } else {
                    break;
                }
            }
            result.push(MarkdownBlockDto::Alert {
                alert_type,
                title: None,
                text: alert_lines.join("\n"),
            });
            continue;
        }

        // 10. Blockquote: > text
        if trimmed.starts_with('>') {
            let mut quote_lines = Vec::new();
            while i < lines.len() {
                let l = lines[i].trim();
                if l.starts_with('>') {
                    quote_lines.push(l.strip_prefix('>').unwrap_or("").trim());
                    i += 1;
                } else {
                    break;
                }
            }
            result.push(MarkdownBlockDto::Blockquote {
                text: quote_lines.join("\n"),
            });
            continue;
        }

        // 11. Task list item: - [ ] or - [x] or * [ ]
        if trimmed.starts_with("- [ ] ") || trimmed.starts_with("- [x] ") || trimmed.starts_with("- [X] ")
            || trimmed.starts_with("* [ ] ") || trimmed.starts_with("* [x] ") || trimmed.starts_with("* [X] ")
        {
            let is_checked = trimmed.chars().nth(3).map(|c| c == 'x' || c == 'X').unwrap_or(false);
            let text = trimmed[6..].trim();
            result.push(MarkdownBlockDto::Task {
                is_checked,
                text: text.to_string(),
            });
            i += 1;
            continue;
        }

        // 12. Bullet list item: - text or * text
        if (trimmed.starts_with("- ") || trimmed.starts_with("* ") || trimmed.starts_with("+ ")) && trimmed.len() > 2 {
            let leading_spaces = line.chars().take_while(|c| c.is_whitespace()).count();
            let indent = leading_spaces / 2;
            let text = trimmed[2..].trim();
            result.push(MarkdownBlockDto::Bullet {
                indent,
                text: text.to_string(),
            });
            i += 1;
            continue;
        }

        // 13. Numbered list item: 1. text or 2) text
        if let Some(dot_pos) = trimmed.find(". ") {
            let prefix = &trimmed[..dot_pos];
            if !prefix.is_empty() && prefix.chars().all(|c| c.is_ascii_digit()) {
                let text = trimmed[dot_pos + 2..].trim();
                result.push(MarkdownBlockDto::Numbered {
                    number: format!("{}.", prefix),
                    text: text.to_string(),
                });
                i += 1;
                continue;
            }
        }

        // 14. Table: lines starting and containing |
        if trimmed.starts_with('|') && trimmed.ends_with('|') && trimmed.matches('|').count() >= 2 {
            if let Some((table, next_idx)) = parse_table(&lines, i) {
                result.push(table);
                i = next_idx;
                continue;
            }
        }

        // 15. Default: Paragraph
        let mut para_lines = Vec::new();
        para_lines.push(trimmed);
        i += 1;
        while i < lines.len() {
            let next_line = lines[i].trim();
            if next_line.is_empty()
                || next_line.starts_with('#')
                || next_line.starts_with("```")
                || next_line.starts_with("$$")
                || next_line.starts_with("\\[")
                || next_line.starts_with('>')
                || next_line.starts_with("- ")
                || next_line.starts_with("* ")
                || next_line.starts_with("|")
            {
                break;
            }
            para_lines.push(next_line);
            i += 1;
        }

        result.push(MarkdownBlockDto::Paragraph {
            text: para_lines.join(" "),
        });
    }

    result
}

fn parse_table(lines: &[&str], start: usize) -> Option<(MarkdownBlockDto, usize)> {
    if start + 1 >= lines.len() {
        return None;
    }

    let header_line = lines[start].trim();
    let delim_line = lines[start + 1].trim();

    if !delim_line.contains("---") && !delim_line.contains("-|-") {
        return None;
    }

    let headers: Vec<String> = header_line
        .trim_matches('|')
        .split('|')
        .map(|s| s.trim().to_string())
        .collect();

    let alignments: Vec<String> = delim_line
        .trim_matches('|')
        .split('|')
        .map(|s| {
            let clean = s.trim();
            if clean.starts_with(':') && clean.ends_with(':') {
                "CENTER".to_string()
            } else if clean.ends_with(':') {
                "RIGHT".to_string()
            } else {
                "LEFT".to_string()
            }
        })
        .collect();

    let mut rows = Vec::new();
    let mut curr = start + 2;

    while curr < lines.len() {
        let l = lines[curr].trim();
        if !l.starts_with('|') || !l.ends_with('|') {
            break;
        }
        let cells: Vec<String> = l
            .trim_matches('|')
            .split('|')
            .map(|s| s.trim().to_string())
            .collect();
        rows.push(cells);
        curr += 1;
    }

    Some((
        MarkdownBlockDto::Table {
            headers,
            rows,
            alignments,
        },
        curr,
    ))
}

pub fn parse_inline_spans(text: &str) -> Vec<InlineSpanDto> {
    let mut spans = Vec::new();
    let chars: Vec<char> = text.chars().collect();
    let n = chars.len();
    let mut i = 0;

    while i < n {
        // 1. Inline code: `code`
        if chars[i] == '`' {
            let start = i;
            i += 1;
            while i < n && chars[i] != '`' {
                i += 1;
            }
            if i < n && chars[i] == '`' {
                let code_text: String = chars[start + 1..i].iter().collect();
                spans.push(InlineSpanDto {
                    span_type: "code".to_string(),
                    start,
                    end: i + 1,
                    text: code_text,
                    url: None,
                });
                i += 1;
                continue;
            }
            i = start + 1;
            continue;
        }

        // 2. Inline math: $math$ (avoid $$)
        if chars[i] == '$' && (i + 1 < n && chars[i + 1] != '$') {
            let start = i;
            i += 1;
            while i < n && chars[i] != '$' && chars[i] != '\n' {
                i += 1;
            }
            if i < n && chars[i] == '$' && i > start + 1 {
                let math_text: String = chars[start + 1..i].iter().collect();
                spans.push(InlineSpanDto {
                    span_type: "math_inline".to_string(),
                    start,
                    end: i + 1,
                    text: math_text,
                    url: None,
                });
                i += 1;
                continue;
            }
            i = start + 1;
            continue;
        }

        // 3. Bold: **text** or __text__
        if i + 1 < n && ((chars[i] == '*' && chars[i + 1] == '*') || (chars[i] == '_' && chars[i + 1] == '_')) {
            let marker = chars[i];
            let start = i;
            i += 2;
            while i + 1 < n && !(chars[i] == marker && chars[i + 1] == marker) {
                i += 1;
            }
            if i + 1 < n && chars[i] == marker && chars[i + 1] == marker {
                let bold_text: String = chars[start + 2..i].iter().collect();
                spans.push(InlineSpanDto {
                    span_type: "bold".to_string(),
                    start,
                    end: i + 2,
                    text: bold_text,
                    url: None,
                });
                i += 2;
                continue;
            }
            i = start + 2;
            continue;
        }

        // 4. Strikethrough: ~~text~~
        if i + 1 < n && chars[i] == '~' && chars[i + 1] == '~' {
            let start = i;
            i += 2;
            while i + 1 < n && !(chars[i] == '~' && chars[i + 1] == '~') {
                i += 1;
            }
            if i + 1 < n && chars[i] == '~' && chars[i + 1] == '~' {
                let strike_text: String = chars[start + 2..i].iter().collect();
                spans.push(InlineSpanDto {
                    span_type: "strikethrough".to_string(),
                    start,
                    end: i + 2,
                    text: strike_text,
                    url: None,
                });
                i += 2;
                continue;
            }
            i = start + 2;
            continue;
        }

        // 5. Links: [text](url)
        if chars[i] == '[' {
            let start = i;
            let mut close_bracket = None;
            let mut j = i + 1;
            while j < n && chars[j] != '\n' {
                if chars[j] == ']' {
                    close_bracket = Some(j);
                    break;
                }
                j += 1;
            }

            if let Some(cb) = close_bracket {
                if cb + 1 < n && chars[cb + 1] == '(' {
                    let mut close_paren = None;
                    let mut k = cb + 2;
                    while k < n && chars[k] != '\n' && chars[k] != ' ' {
                        if chars[k] == ')' {
                            close_paren = Some(k);
                            break;
                        }
                        k += 1;
                    }

                    if let Some(cp) = close_paren {
                        let link_text: String = chars[start + 1..cb].iter().collect();
                        let link_url: String = chars[cb + 2..cp].iter().collect();
                        let is_file = link_url.starts_with("file://");
                        spans.push(InlineSpanDto {
                            span_type: if is_file { "file_link".to_string() } else { "link".to_string() },
                            start,
                            end: cp + 1,
                            text: link_text,
                            url: Some(link_url),
                        });
                        i = cp + 1;
                        continue;
                    }
                }
            }
        }

        i += 1;
    }

    spans
}

fn extract_youtube_video_id(url: &str) -> Option<String> {
    if let Some(idx) = url.find("youtu.be/") {
        let rest = &url[idx + 9..];
        let end = rest.find(['?', '&', '/', '#']).unwrap_or(rest.len());
        if end > 0 {
            return Some(rest[..end].to_string());
        }
    }
    if let Some(idx) = url.find("youtube.com/watch") {
        if let Some(v_idx) = url[idx..].find("v=") {
            let rest = &url[idx + v_idx + 2..];
            let end = rest.find(['&', '#', '/']).unwrap_or(rest.len());
            if end > 0 {
                return Some(rest[..end].to_string());
            }
        }
    }
    None
}
