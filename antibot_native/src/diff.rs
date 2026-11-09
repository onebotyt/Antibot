use serde::{Deserialize, Serialize};
use similar::{ChangeTag, TextDiff};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffResultDto {
    #[serde(rename = "totalAdditions")]
    pub total_additions: usize,
    #[serde(rename = "totalDeletions")]
    pub total_deletions: usize,
    pub hunks: Vec<DiffHunkDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffHunkDto {
    pub header: String,
    pub lines: Vec<DiffLineDto>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffLineDto {
    #[serde(rename = "type")]
    pub line_type: String, // "addition", "deletion", "context"
    pub text: String,
    #[serde(rename = "oldLineNo")]
    pub old_line_no: Option<usize>,
    #[serde(rename = "newLineNo")]
    pub new_line_no: Option<usize>,
}

pub fn compute_unified_diff(old_text: &str, new_text: &str) -> DiffResultDto {
    let diff = TextDiff::from_lines(old_text, new_text);
    let mut total_additions = 0;
    let mut total_deletions = 0;
    let mut hunks_dto = Vec::new();

    for hunk in diff.unified_diff().context_radius(3).iter_hunks() {
        let header = format!("{}", hunk.header());
        let mut lines_dto = Vec::new();

        for change in hunk.iter_changes() {
            let tag = change.tag();
            let text = change.value().trim_end_matches(['\r', '\n']).to_string();

            let (line_type, old_line_no, new_line_no) = match tag {
                ChangeTag::Equal => (
                    "context",
                    change.old_index().map(|i| i + 1),
                    change.new_index().map(|i| i + 1),
                ),
                ChangeTag::Delete => {
                    total_deletions += 1;
                    ("deletion", change.old_index().map(|i| i + 1), None)
                }
                ChangeTag::Insert => {
                    total_additions += 1;
                    ("addition", None, change.new_index().map(|i| i + 1))
                }
            };

            lines_dto.push(DiffLineDto {
                line_type: line_type.to_string(),
                text,
                old_line_no,
                new_line_no,
            });
        }

        hunks_dto.push(DiffHunkDto { header, lines: lines_dto });
    }

    DiffResultDto {
        total_additions,
        total_deletions,
        hunks: hunks_dto,
    }
}
