//! Zero-copy Unified Diff Patcher, 3-Way Merge Engine & Conflict Resolver.
//!
//! Provides:
//! - `parse_unified_diff` — parses standard unified diff format into typed hunks.
//! - `apply_patch` — applies or reverses hunks with ±3 line fuzzy drift tolerance.
//! - `three_way_merge` — merges `ours` and `theirs` against a common `base`.
//! - `resolve_conflicts` — strips conflict markers using a chosen strategy.

use serde::{Deserialize, Serialize};

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/// A single line inside a diff hunk.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum PatchLine {
    Context(String),
    Add(String),
    Remove(String),
}

/// A parsed hunk from a unified diff.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffPatchHunk {
    pub old_start: usize,
    pub old_count: usize,
    pub new_start: usize,
    pub new_count: usize,
    pub lines: Vec<PatchLine>,
}

/// Error returned when a hunk cannot be applied.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PatchError {
    pub hunk_index: usize,
    pub message: String,
}

/// Result of a 3-way merge.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MergeResult {
    pub merged: String,
    pub has_conflicts: bool,
    pub conflict_count: usize,
    pub conflict_regions: Vec<ConflictRegion>,
}

/// A region where ours and theirs conflict.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConflictRegion {
    pub base_start: usize,
    pub base_end: usize,
    pub ours_text: String,
    pub theirs_text: String,
}

/// Strategy for resolving conflict markers.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ConflictStrategy {
    Ours,
    Theirs,
    Both,
}

// ---------------------------------------------------------------------------
// 1. Unified Diff Parser
// ---------------------------------------------------------------------------

/// Parses a unified diff string into a vector of hunks.
pub fn parse_unified_diff(diff_text: &str) -> Vec<DiffPatchHunk> {
    let mut hunks = Vec::new();
    let mut current: Option<DiffPatchHunk> = None;

    for line in diff_text.lines() {
        if line.starts_with("@@ ") {
            if let Some(h) = current.take() {
                hunks.push(h);
            }
            if let Some(h) = parse_hunk_header(line) {
                current = Some(h);
            }
        } else if line.starts_with("--- ") || line.starts_with("+++ ") {
            // file header lines — skip
        } else if line.starts_with("diff ") {
            // diff header — flush any pending hunk
            if let Some(h) = current.take() {
                hunks.push(h);
            }
        } else if let Some(ref mut hunk) = current {
            if let Some(rest) = line.strip_prefix('+') {
                hunk.lines.push(PatchLine::Add(rest.to_string()));
            } else if let Some(rest) = line.strip_prefix('-') {
                hunk.lines.push(PatchLine::Remove(rest.to_string()));
            } else if let Some(rest) = line.strip_prefix(' ') {
                hunk.lines.push(PatchLine::Context(rest.to_string()));
            } else if line == "\\ No newline at end of file" {
                // ignore
            } else {
                // Treat bare lines as context (handles missing leading space)
                hunk.lines.push(PatchLine::Context(line.to_string()));
            }
        }
    }

    if let Some(h) = current {
        hunks.push(h);
    }

    hunks
}

fn parse_hunk_header(line: &str) -> Option<DiffPatchHunk> {
    // @@ -old_start,old_count +new_start,new_count @@
    let after_at = line.strip_prefix("@@ ")?;
    let end_at = after_at.find(" @@")?;
    let range_str = &after_at[..end_at];

    let mut parts = range_str.split_whitespace();
    let old_part = parts.next()?.strip_prefix('-')?;
    let new_part = parts.next()?.strip_prefix('+')?;

    let (old_start, old_count) = parse_range(old_part);
    let (new_start, new_count) = parse_range(new_part);

    Some(DiffPatchHunk {
        old_start,
        old_count,
        new_start,
        new_count,
        lines: Vec::new(),
    })
}

fn parse_range(s: &str) -> (usize, usize) {
    if let Some((start, count)) = s.split_once(',') {
        (
            start.parse().unwrap_or(1),
            count.parse().unwrap_or(0),
        )
    } else {
        (s.parse().unwrap_or(1), 1)
    }
}

// ---------------------------------------------------------------------------
// 2. Patch Apply (with fuzzy drift)
// ---------------------------------------------------------------------------

const DRIFT_TOLERANCE: isize = 3;

/// Applies parsed hunks to the original text.
///
/// When `reverse` is true, additions become removals and vice versa,
/// effectively un-applying the patch.
pub fn apply_patch(original: &str, hunks: &[DiffPatchHunk], reverse: bool) -> Result<String, PatchError> {
    let mut lines: Vec<String> = original.lines().map(|l| l.to_string()).collect();

    // We need to track cumulative offset as we insert/remove lines
    let mut offset: isize = 0;

    for (hunk_idx, hunk) in hunks.iter().enumerate() {
        let target_line = if reverse {
            hunk.new_start as isize - 1 + offset
        } else {
            hunk.old_start as isize - 1 + offset
        };

        // Collect the context/remove lines we expect to find
        let expected: Vec<&str> = hunk.lines.iter().filter_map(|pl| match pl {
            PatchLine::Context(s) => Some(s.as_str()),
            PatchLine::Remove(s) if !reverse => Some(s.as_str()),
            PatchLine::Add(s) if reverse => Some(s.as_str()),
            _ => None,
        }).collect();

        // Find best matching position with fuzzy drift
        let best_pos = find_best_match(&lines, &expected, target_line, DRIFT_TOLERANCE);

        let pos = match best_pos {
            Some(p) => p,
            None => {
                return Err(PatchError {
                    hunk_index: hunk_idx,
                    message: format!(
                        "Hunk {} failed: could not find context at line {} (±{})",
                        hunk_idx + 1,
                        target_line + 1,
                        DRIFT_TOLERANCE
                    ),
                });
            }
        };

        // Apply the hunk at `pos`
        let mut cursor = pos;
        let mut insertions = 0isize;
        let mut deletions = 0isize;

        for pl in &hunk.lines {
            match pl {
                PatchLine::Context(_) => {
                    cursor += 1;
                }
                PatchLine::Remove(s) if !reverse => {
                    if cursor < lines.len() && lines[cursor] == *s {
                        lines.remove(cursor);
                        deletions += 1;
                    } else {
                        // Fuzzy: skip if line doesn't match exactly
                        cursor += 1;
                    }
                }
                PatchLine::Add(s) if !reverse => {
                    lines.insert(cursor, s.clone());
                    cursor += 1;
                    insertions += 1;
                }
                // Reverse mode: Add becomes Remove, Remove becomes Add
                PatchLine::Add(s) if reverse => {
                    if cursor < lines.len() && lines[cursor] == *s {
                        lines.remove(cursor);
                        deletions += 1;
                    } else {
                        cursor += 1;
                    }
                }
                PatchLine::Remove(s) if reverse => {
                    lines.insert(cursor, s.clone());
                    cursor += 1;
                    insertions += 1;
                }
                _ => {}
            }
        }

        offset += insertions - deletions;
    }

    // Rejoin lines with newline. Add trailing newline if original had one.
    let mut result = lines.join("\n");
    if original.ends_with('\n') && !result.ends_with('\n') {
        result.push('\n');
    }

    Ok(result)
}

fn find_best_match(
    lines: &[String],
    expected: &[&str],
    target: isize,
    drift: isize,
) -> Option<usize> {
    if expected.is_empty() {
        // No context to match — trust the target position
        return Some(target.max(0) as usize);
    }

    let len = lines.len() as isize;

    // Try exact position first, then drift outward
    for d in 0..=drift {
        for sign in &[0isize, 1, -1] {
            let pos = target + d * sign;
            if pos < 0 || pos >= len {
                continue;
            }
            let p = pos as usize;
            if matches_context(lines, expected, p) {
                return Some(p);
            }
        }
    }

    None
}

fn matches_context(lines: &[String], expected: &[&str], start: usize) -> bool {
    if start + expected.len() > lines.len() {
        return false;
    }
    let mut li = start;
    for exp in expected {
        if lines[li] != *exp {
            return false;
        }
        li += 1;
    }
    true
}

// ---------------------------------------------------------------------------
// 3. Three-Way Merge
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
struct Edit {
    start: usize, // 0-indexed line in base
    end: usize,   // exclusive
    replacement: Vec<String>,
}

/// Performs a 3-way merge of `ours` and `theirs` relative to `base`.
///
/// Non-conflicting edits (changes to different regions) are merged cleanly.
/// Overlapping edits produce standard conflict markers.
pub fn three_way_merge(base: &str, ours: &str, theirs: &str) -> MergeResult {
    let base_lines: Vec<&str> = base.lines().collect();
    let ours_edits = compute_edits(&base_lines, ours);
    let theirs_edits = compute_edits(&base_lines, theirs);

    // Merge edits
    let mut merged_lines: Vec<String> = Vec::new();
    let mut conflicts = Vec::new();
    let mut base_cursor = 0usize;

    let mut oi = 0usize;
    let mut ti = 0usize;

    while oi < ours_edits.len() || ti < theirs_edits.len() {
        let o_edit = ours_edits.get(oi);
        let t_edit = theirs_edits.get(ti);

        match (o_edit, t_edit) {
            (Some(o), Some(t)) => {
                if o.end <= t.start {
                    // Ours comes first — no overlap
                    // Copy base lines before this edit
                    for i in base_cursor..o.start {
                        merged_lines.push(base_lines[i].to_string());
                    }
                    merged_lines.extend(o.replacement.iter().cloned());
                    base_cursor = o.end;
                    oi += 1;
                } else if t.end <= o.start {
                    // Theirs comes first — no overlap
                    for i in base_cursor..t.start {
                        merged_lines.push(base_lines[i].to_string());
                    }
                    merged_lines.extend(t.replacement.iter().cloned());
                    base_cursor = t.end;
                    ti += 1;
                } else {
                    // Overlap — check if edits are identical
                    if o.start == t.start && o.end == t.end && o.replacement == t.replacement {
                        // Same edit — apply once
                        for i in base_cursor..o.start {
                            merged_lines.push(base_lines[i].to_string());
                        }
                        merged_lines.extend(o.replacement.iter().cloned());
                        base_cursor = o.end;
                        oi += 1;
                        ti += 1;
                    } else {
                        // True conflict
                        let conflict_start = o.start.min(t.start);
                        let conflict_end = o.end.max(t.end);

                        for i in base_cursor..conflict_start {
                            merged_lines.push(base_lines[i].to_string());
                        }

                        let ours_text = o.replacement.join("\n");
                        let theirs_text = t.replacement.join("\n");

                        merged_lines.push("<<<<<<< ours".to_string());
                        merged_lines.extend(o.replacement.iter().cloned());
                        merged_lines.push("=======".to_string());
                        merged_lines.extend(t.replacement.iter().cloned());
                        merged_lines.push(">>>>>>> theirs".to_string());

                        conflicts.push(ConflictRegion {
                            base_start: conflict_start,
                            base_end: conflict_end,
                            ours_text,
                            theirs_text,
                        });

                        base_cursor = conflict_end;
                        oi += 1;
                        ti += 1;
                    }
                }
            }
            (Some(o), None) => {
                for i in base_cursor..o.start {
                    merged_lines.push(base_lines[i].to_string());
                }
                merged_lines.extend(o.replacement.iter().cloned());
                base_cursor = o.end;
                oi += 1;
            }
            (None, Some(t)) => {
                for i in base_cursor..t.start {
                    merged_lines.push(base_lines[i].to_string());
                }
                merged_lines.extend(t.replacement.iter().cloned());
                base_cursor = t.end;
                ti += 1;
            }
            (None, None) => break,
        }
    }

    // Append remaining base lines
    for i in base_cursor..base_lines.len() {
        merged_lines.push(base_lines[i].to_string());
    }

    let conflict_count = conflicts.len();
    MergeResult {
        merged: merged_lines.join("\n"),
        has_conflicts: conflict_count > 0,
        conflict_count,
        conflict_regions: conflicts,
    }
}

/// Computes minimal edits from `base_lines` to `target` text using `similar`.
fn compute_edits(base_lines: &[&str], target: &str) -> Vec<Edit> {
    let target_lines: Vec<&str> = target.lines().collect();
    let base_text: Vec<&str> = base_lines.to_vec();

    let diff = similar::TextDiff::from_slices(&base_text, &target_lines);
    let mut edits = Vec::new();

    let mut base_idx = 0usize;
    let mut pending_start: Option<usize> = None;
    let mut pending_end = 0usize;
    let mut pending_replacement: Vec<String> = Vec::new();

    for change in diff.iter_all_changes() {
        match change.tag() {
            similar::ChangeTag::Equal => {
                // Flush pending edit
                if let Some(start) = pending_start.take() {
                    edits.push(Edit {
                        start,
                        end: pending_end,
                        replacement: std::mem::take(&mut pending_replacement),
                    });
                }
                base_idx = change.old_index().map(|i| i + 1).unwrap_or(base_idx);
            }
            similar::ChangeTag::Delete => {
                let old_idx = change.old_index().unwrap_or(base_idx);
                if pending_start.is_none() {
                    pending_start = Some(old_idx);
                }
                pending_end = old_idx + 1;
                base_idx = old_idx + 1;
            }
            similar::ChangeTag::Insert => {
                if pending_start.is_none() {
                    pending_start = Some(base_idx);
                    pending_end = base_idx;
                }
                let text = change.value().trim_end_matches('\n').to_string();
                pending_replacement.push(text);
            }
        }
    }

    // Flush final
    if let Some(start) = pending_start {
        edits.push(Edit {
            start,
            end: pending_end,
            replacement: pending_replacement,
        });
    }

    edits
}

// ---------------------------------------------------------------------------
// 4. Conflict Resolution
// ---------------------------------------------------------------------------

/// Resolves all conflict markers in `text` using the given strategy.
pub fn resolve_conflicts(text: &str, strategy: ConflictStrategy) -> String {
    let mut result = Vec::new();
    let mut in_ours = false;
    let mut in_theirs = false;
    let mut ours_lines: Vec<&str> = Vec::new();
    let mut theirs_lines: Vec<&str> = Vec::new();

    for line in text.lines() {
        if line.starts_with("<<<<<<< ") {
            in_ours = true;
            ours_lines.clear();
            theirs_lines.clear();
        } else if line == "=======" && in_ours {
            in_ours = false;
            in_theirs = true;
        } else if line.starts_with(">>>>>>> ") && in_theirs {
            // Resolve this conflict block
            match strategy {
                ConflictStrategy::Ours => {
                    result.extend(ours_lines.drain(..));
                }
                ConflictStrategy::Theirs => {
                    result.extend(theirs_lines.drain(..));
                }
                ConflictStrategy::Both => {
                    result.extend(ours_lines.drain(..));
                    result.extend(theirs_lines.drain(..));
                }
            }
            in_theirs = false;
        } else if in_ours {
            ours_lines.push(line);
        } else if in_theirs {
            theirs_lines.push(line);
        } else {
            result.push(line);
        }
    }

    result.join("\n")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_DIFF: &str = r#"--- a/hello.txt
+++ b/hello.txt
@@ -1,4 +1,5 @@
 Hello
-World
+Beautiful
+World!
 Foo
 Bar
"#;

    #[test]
    fn test_parse_unified_diff() {
        let hunks = parse_unified_diff(SAMPLE_DIFF);
        assert_eq!(hunks.len(), 1);
        let h = &hunks[0];
        assert_eq!(h.old_start, 1);
        assert_eq!(h.old_count, 4);
        assert_eq!(h.new_start, 1);
        assert_eq!(h.new_count, 5);
        assert_eq!(h.lines.len(), 6);
        assert_eq!(h.lines[0], PatchLine::Context("Hello".to_string()));
        assert_eq!(h.lines[1], PatchLine::Remove("World".to_string()));
        assert_eq!(h.lines[2], PatchLine::Add("Beautiful".to_string()));
        assert_eq!(h.lines[3], PatchLine::Add("World!".to_string()));
        assert_eq!(h.lines[4], PatchLine::Context("Foo".to_string()));
        assert_eq!(h.lines[5], PatchLine::Context("Bar".to_string()));
    }

    #[test]
    fn test_apply_patch_basic() {
        let original = "Hello\nWorld\nFoo\nBar\n";
        let hunks = parse_unified_diff(SAMPLE_DIFF);
        let result = apply_patch(original, &hunks, false).unwrap();
        assert!(result.contains("Beautiful"));
        assert!(result.contains("World!"));
        assert!(!result.contains("\nWorld\n"));
    }

    #[test]
    fn test_apply_patch_fuzzy_drift() {
        // Original has 2 extra lines at the top, shifting everything down by 2
        let original = "EXTRA1\nEXTRA2\nHello\nWorld\nFoo\nBar\n";
        // Hunk says old_start=1 but the content is at line 3 — within ±3 drift
        let hunks = parse_unified_diff(SAMPLE_DIFF);
        let result = apply_patch(original, &hunks, false).unwrap();
        assert!(result.contains("Beautiful"));
        assert!(result.contains("World!"));
        assert!(result.contains("EXTRA1"));
    }

    #[test]
    fn test_apply_patch_reverse() {
        let original = "Hello\nWorld\nFoo\nBar\n";
        let hunks = parse_unified_diff(SAMPLE_DIFF);
        let patched = apply_patch(original, &hunks, false).unwrap();
        // Now reverse the patch to get back to original
        let reversed = apply_patch(&patched, &hunks, true).unwrap();
        assert_eq!(reversed.trim(), original.trim());
    }

    #[test]
    fn test_three_way_merge_no_conflict() {
        let base = "line1\nline2\nline3\nline4\nline5";
        let ours = "line1\nline2-ours\nline3\nline4\nline5";
        let theirs = "line1\nline2\nline3\nline4-theirs\nline5";

        let result = three_way_merge(base, ours, theirs);
        assert!(!result.has_conflicts);
        assert_eq!(result.conflict_count, 0);
        assert!(result.merged.contains("line2-ours"));
        assert!(result.merged.contains("line4-theirs"));
    }

    #[test]
    fn test_three_way_merge_with_conflict() {
        let base = "line1\nline2\nline3";
        let ours = "line1\nline2-ours\nline3";
        let theirs = "line1\nline2-theirs\nline3";

        let result = three_way_merge(base, ours, theirs);
        assert!(result.has_conflicts);
        assert_eq!(result.conflict_count, 1);
        assert!(result.merged.contains("<<<<<<< ours"));
        assert!(result.merged.contains("line2-ours"));
        assert!(result.merged.contains("======="));
        assert!(result.merged.contains("line2-theirs"));
        assert!(result.merged.contains(">>>>>>> theirs"));
    }

    #[test]
    fn test_resolve_conflict_ours() {
        let text = "line1\n<<<<<<< ours\nours-change\n=======\ntheirs-change\n>>>>>>> theirs\nline3";
        let resolved = resolve_conflicts(text, ConflictStrategy::Ours);
        assert!(resolved.contains("ours-change"));
        assert!(!resolved.contains("theirs-change"));
        assert!(!resolved.contains("<<<<<<<"));
    }

    #[test]
    fn test_resolve_conflict_theirs() {
        let text = "line1\n<<<<<<< ours\nours-change\n=======\ntheirs-change\n>>>>>>> theirs\nline3";
        let resolved = resolve_conflicts(text, ConflictStrategy::Theirs);
        assert!(!resolved.contains("ours-change"));
        assert!(resolved.contains("theirs-change"));
        assert!(!resolved.contains("<<<<<<<"));
    }
}
