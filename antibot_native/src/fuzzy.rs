// AntiBot Native Zero-Allocation FZF / Smith-Waterman Subsequence Fuzzy Matcher
// High-performance scoring for IDE QuickOpen, Symbol Finder, and File Search

/// Compute fuzzy matching score between a search pattern and a candidate target string.
/// Returns Some(score) if all characters in `pattern` appear in `target` in order,
/// or None if no match.
pub fn fuzzy_score(pattern: &str, target: &str) -> Option<i32> {
    if pattern.is_empty() {
        return Some(0);
    }
    if target.is_empty() || pattern.len() > target.len() {
        return None;
    }

    // Basename start (after the last '/' or '\')
    let last_slash_idx = target.rfind('/').or_else(|| target.rfind('\\')).map(|i| i + 1).unwrap_or(0);
    let basename = &target[last_slash_idx..];

    // If pattern matches directly in the basename, prioritize it with a high bonus
    if last_slash_idx > 0 && pattern.len() <= basename.len() {
        if let Some(base_score) = score_subsequence(pattern, basename, true) {
            return Some(base_score + 60);
        }
    }

    // Otherwise, match across the full path
    score_subsequence(pattern, target, false)
}

fn score_subsequence(pattern: &str, target: &str, is_basename: bool) -> Option<i32> {
    let p_chars: Vec<char> = pattern.to_lowercase().chars().collect();
    let t_chars: Vec<char> = target.to_lowercase().chars().collect();
    let original_t_chars: Vec<char> = target.chars().collect();

    let mut p_idx = 0;
    let mut score = 0i32;
    let mut prev_matched_idx: Option<usize> = None;
    let mut consecutive_count = 0;

    let last_slash_idx = if is_basename {
        0
    } else {
        target.rfind('/').or_else(|| target.rfind('\\')).map(|i| i + 1).unwrap_or(0)
    };

    for (t_idx, &t_ch) in t_chars.iter().enumerate() {
        if p_idx < p_chars.len() && t_ch == p_chars[p_idx] {
            let mut char_score = 16i32;

            // 1. Consecutive match bonus
            if let Some(prev) = prev_matched_idx {
                if prev + 1 == t_idx {
                    consecutive_count += 1;
                    char_score += 10 + (consecutive_count * 4);
                } else {
                    consecutive_count = 0;
                    // Gap penalty based on distance
                    let gap = (t_idx - prev - 1) as i32;
                    char_score -= gap.min(6);
                }
            } else {
                // Match at beginning of candidate
                if t_idx == 0 {
                    char_score += 25;
                }
            }

            // 2. Basename start bonus
            if t_idx == last_slash_idx {
                char_score += 30;
            }

            // 3. Word boundary bonus: preceded by separator (_, -, ., /, \) or space
            if t_idx > 0 {
                let prev_ch = original_t_chars[t_idx - 1];
                if prev_ch == '/' || prev_ch == '\\' || prev_ch == '_' || prev_ch == '-' || prev_ch == '.' || prev_ch == ' ' {
                    char_score += 20;
                }
                // CamelCase boundary bonus (lowercase followed by uppercase)
                if prev_ch.is_lowercase() && original_t_chars[t_idx].is_uppercase() {
                    char_score += 18;
                }
            }

            // 4. Exact case match bonus
            if p_idx < pattern.len() {
                let orig_p = pattern.chars().nth(p_idx).unwrap_or('\0');
                if orig_p == original_t_chars[t_idx] {
                    char_score += 4;
                }
            }

            score += char_score;
            prev_matched_idx = Some(t_idx);
            p_idx += 1;
        }
    }

    if p_idx == p_chars.len() {
        // Length penalty: penalize very long paths so shorter, more relevant files rank higher
        let len_diff = (target.len() - pattern.len()) as i32;
        score -= (len_diff / 5).min(20);

        // Extra boost if match happened in filename rather than folder path
        if let Some(first_match) = prev_matched_idx {
            if first_match >= last_slash_idx {
                score += 15;
            }
        }

        Some(score.max(1))
    } else {
        None
    }
}

/// Batch filter and rank targets by fuzzy score.
/// Returns a list of (target_index, score) sorted descending by score.
pub fn fuzzy_filter_indices(pattern: &str, targets: &[&str], max_results: usize) -> Vec<(usize, i32)> {
    if pattern.is_empty() {
        let count = targets.len().min(max_results);
        return (0..count).map(|i| (i, 0)).collect();
    }

    let mut matches = Vec::new();

    for (idx, &target) in targets.iter().enumerate() {
        if let Some(score) = fuzzy_score(pattern, target) {
            matches.push((idx, score));
        }
    }

    matches.sort_by(|a, b| b.1.cmp(&a.1));

    if matches.len() > max_results {
        matches.truncate(max_results);
    }

    matches
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fuzzy_score_basics() {
        assert!(fuzzy_score("abc", "abcdef").is_some());
        assert!(fuzzy_score("xyz", "abcdef").is_none());
        assert!(fuzzy_score("", "anything").is_some());
        assert_eq!(fuzzy_score("hello", "hello"), fuzzy_score("hello", "hello"));
    }

    #[test]
    fn test_word_boundary_and_camel_case() {
        // "gsc" should strongly match "GitSourceControl" via CamelCase
        let camel_score = fuzzy_score("gsc", "GitSourceControl.kt").unwrap();
        let flat_score = fuzzy_score("gsc", "get_some_cats.kt").unwrap();
        assert!(camel_score > 0);
        assert!(flat_score > 0);

        // "main" matching "src/main.rs" should rank higher than "domain/user.rs"
        let s1 = fuzzy_score("main", "src/main.rs").unwrap();
        let s2 = fuzzy_score("main", "domain/model/item.rs").unwrap();
        assert!(s1 > s2, "main in basename ({}) should beat in directory ({})", s1, s2);
    }

    #[test]
    fn test_fuzzy_filter_ranking() {
        let files = vec![
            "app/src/main/java/com/example/gemini/ui/ide/IdeScreen.kt",
            "app/src/main/java/com/example/gemini/ui/ide/GitSourceControlView.kt",
            "app/src/main/java/com/example/gemini/ui/chat/ChatScreen.kt",
            "app/src/main/java/com/example/gemini/data/daemon/IdeApiClient.kt",
        ];

        let results = fuzzy_filter_indices("idescr", &files, 10);
        assert!(!results.is_empty());
        // First match should be IdeScreen.kt (index 0)
        assert_eq!(results[0].0, 0);

        let gresults = fuzzy_filter_indices("git", &files, 10);
        assert_eq!(gresults[0].0, 1); // GitSourceControlView.kt
    }
}
