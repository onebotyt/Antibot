use memchr::memchr_iter;
use memmap2::Mmap;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io;
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LineOffsetIndex {
    pub line_count: usize,
    pub offsets: Vec<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TranscriptSummaryMeta {
    pub title: String,
    pub total_steps: usize,
    pub is_running: bool,
    pub first_prompt: String,
    pub last_role: String,
}

/// Counts lines in a file via memory mapping and SIMD memchr
pub fn count_lines_mmap<P: AsRef<Path>>(path: P) -> io::Result<usize> {
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    if metadata.len() == 0 {
        return Ok(0);
    }
    let mmap = unsafe { Mmap::map(&file)? };
    let mut count = memchr_iter(b'\n', &mmap).count();
    if !mmap.is_empty() && mmap[mmap.len() - 1] != b'\n' {
        count += 1;
    }
    Ok(count)
}

/// Builds byte offsets for each line in the file: [0, offset1, offset2, ...]
pub fn build_line_offsets(mmap: &[u8]) -> Vec<u64> {
    if mmap.is_empty() {
        return Vec::new();
    }
    let mut offsets = Vec::with_capacity(64);
    offsets.push(0);

    for idx in memchr_iter(b'\n', mmap) {
        if idx + 1 < mmap.len() {
            offsets.push((idx + 1) as u64);
        }
    }
    offsets
}

/// Reads a specific line by index (0-indexed) using memory mapping
pub fn get_line_mmap<P: AsRef<Path>>(path: P, line_index: usize) -> io::Result<Option<String>> {
    let file = File::open(path)?;
    let metadata = file.metadata()?;
    if metadata.len() == 0 {
        return Ok(None);
    }
    let mmap = unsafe { Mmap::map(&file)? };
    let offsets = build_line_offsets(&mmap);
    if line_index >= offsets.len() {
        return Ok(None);
    }

    let start = offsets[line_index] as usize;
    let end = if line_index + 1 < offsets.len() {
        let next_start = offsets[line_index + 1] as usize;
        if next_start > 0 && mmap[next_start - 1] == b'\n' {
            if next_start > 1 && mmap[next_start - 2] == b'\r' {
                next_start - 2
            } else {
                next_start - 1
            }
        } else {
            next_start
        }
    } else {
        let mut e = mmap.len();
        while e > start && (mmap[e - 1] == b'\n' || mmap[e - 1] == b'\r') {
            e -= 1;
        }
        e
    };

    if start <= end && end <= mmap.len() {
        let slice = &mmap[start..end];
        let text = String::from_utf8_lossy(slice).to_string();
        Ok(Some(text))
    } else {
        Ok(None)
    }
}

/// Slices a turn by index, automatically splicing full fields from `transcript_full.jsonl`
/// when `truncated_fields` is present in `transcript.jsonl`.
pub fn get_spliced_turn_mmap<P: AsRef<Path>>(
    compact_path: P,
    full_path: P,
    line_index: usize,
) -> io::Result<Option<String>> {
    let compact_line = match get_line_mmap(compact_path, line_index)? {
        Some(l) => l,
        None => return Ok(None),
    };

    // Fast check if truncated_fields is present in compact line
    if compact_line.contains("\"truncated_fields\"") {
        if let Ok(full_line) = get_line_mmap(full_path, line_index) {
            if let Some(fl) = full_line {
                if !fl.trim().is_empty() {
                    return Ok(Some(fl));
                }
            }
        }
    }

    Ok(Some(compact_line))
}

/// Quickly extracts summary metadata (title, total steps, first user prompt)
/// without deserializing the entire JSONL file.
pub fn extract_transcript_meta_mmap<P: AsRef<Path>>(
    transcript_path: P,
) -> io::Result<Option<TranscriptSummaryMeta>> {
    let file = File::open(transcript_path)?;
    let metadata = file.metadata()?;
    if metadata.len() == 0 {
        return Ok(None);
    }
    let mmap = unsafe { Mmap::map(&file)? };
    let offsets = build_line_offsets(&mmap);
    let total_steps = offsets.len();

    let mut first_prompt = String::new();
    let mut title = String::new();
    let mut last_role = String::new();
    let mut is_running = false;

    for (idx, &off) in offsets.iter().enumerate() {
        let start = off as usize;
        let end = if idx + 1 < offsets.len() {
            offsets[idx + 1] as usize
        } else {
            mmap.len()
        };
        let line_slice = &mmap[start..end];

        if first_prompt.is_empty() {
            if let Ok(val) = serde_json::from_slice::<serde_json::Value>(line_slice) {
                if val["type"] == "USER_INPUT" {
                    if let Some(c) = val["content"].as_str() {
                        let prompt = clean_user_prompt(c);
                        if !prompt.is_empty() {
                            first_prompt = prompt.clone();
                            title = if prompt.len() > 40 {
                                format!("{}...", &prompt[..40])
                            } else {
                                prompt
                            };
                        }
                    }
                }
            }
        }

        if idx + 1 == total_steps {
            if let Ok(val) = serde_json::from_slice::<serde_json::Value>(line_slice) {
                if let Some(r) = val["source"].as_str() {
                    last_role = r.to_string();
                }
                if let Some(st) = val["status"].as_str() {
                    if st == "RUNNING" || st == "IN_PROGRESS" {
                        is_running = true;
                    }
                }
            }
        }
    }

    Ok(Some(TranscriptSummaryMeta {
        title,
        total_steps,
        is_running,
        first_prompt,
        last_role,
    }))
}

fn clean_user_prompt(content: &str) -> String {
    if let Some(start) = content.find("<USER_REQUEST>") {
        let rest = &content[start + "<USER_REQUEST>".len()..];
        if let Some(end) = rest.find("</USER_REQUEST>") {
            return rest[..end].trim().to_string();
        }
    }
    let mut out = String::with_capacity(content.len());
    let mut in_tag = false;
    for ch in content.chars() {
        if ch == '<' {
            in_tag = true;
        } else if ch == '>' {
            in_tag = false;
        } else if !in_tag {
            out.push(ch);
        }
    }
    out.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_mmap_line_offsets_and_count() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("test.jsonl");
        {
            let mut f = File::create(&path).unwrap();
            writeln!(f, "{{\"step_index\": 0, \"type\": \"USER_INPUT\", \"content\": \"<USER_REQUEST>hello world</USER_REQUEST>\"}}").unwrap();
            writeln!(f, "{{\"step_index\": 1, \"type\": \"PLANNER_RESPONSE\", \"content\": \"hi\"}}").unwrap();
            writeln!(f, "{{\"step_index\": 2, \"type\": \"USER_INPUT\", \"content\": \"second\"}}").unwrap();
        }

        let count = count_lines_mmap(&path).unwrap();
        assert_eq!(count, 3);

        let line0 = get_line_mmap(&path, 0).unwrap().unwrap();
        assert!(line0.contains("hello world"));

        let line2 = get_line_mmap(&path, 2).unwrap().unwrap();
        assert!(line2.contains("second"));

        let meta = extract_transcript_meta_mmap(&path).unwrap().unwrap();
        assert_eq!(meta.total_steps, 3);
        assert_eq!(meta.title, "hello world");
        assert_eq!(meta.first_prompt, "hello world");
    }

    #[test]
    fn test_mmap_splice_truncated() {
        let dir = tempfile::tempdir().unwrap();
        let compact_path = dir.path().join("transcript.jsonl");
        let full_path = dir.path().join("transcript_full.jsonl");

        {
            let mut f = File::create(&compact_path).unwrap();
            writeln!(f, "{{\"step_index\": 0, \"content\": \"short\", \"truncated_fields\": [\"content\"]}}").unwrap();
        }
        {
            let mut f = File::create(&full_path).unwrap();
            writeln!(f, "{{\"step_index\": 0, \"content\": \"long full untruncated content\"}}").unwrap();
        }

        let turn = get_spliced_turn_mmap(&compact_path, &full_path, 0).unwrap().unwrap();
        assert!(turn.contains("long full untruncated content"));
    }
}
