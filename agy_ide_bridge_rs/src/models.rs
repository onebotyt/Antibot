use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileNode {
    pub name: String,
    pub path: String,
    #[serde(rename = "isDir")]
    pub is_dir: bool,
    pub size: u64,
    #[serde(skip_serializing_if = "Vec::is_empty", default)]
    pub children: Vec<FileNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FsItemNode {
    pub name: String,
    pub path: String,
    #[serde(rename = "isDir")]
    pub is_dir: bool,
    pub size: u64,
    #[serde(rename = "modTime")]
    pub mod_time: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ext: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub hidden: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub symlink: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
    #[serde(rename = "childCount", skip_serializing_if = "Option::is_none")]
    pub child_count: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectSummary {
    pub name: String,
    pub path: String,
    #[serde(rename = "isCustom", skip_serializing_if = "Option::is_none")]
    pub is_custom: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FsBrowseResult {
    #[serde(rename = "currentPath")]
    pub current_path: String,
    #[serde(rename = "parentPath")]
    pub parent_path: String,
    #[serde(rename = "homePath")]
    pub home_path: String,
    pub directories: Vec<ProjectSummary>,
    pub items: Vec<FsItemNode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileSaveReq {
    pub path: String,
    pub content: String,
    #[serde(rename = "expectedHash")]
    pub expected_hash: Option<String>,
    pub force: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FilePatchReq {
    pub path: String,
    #[serde(rename = "startLine")]
    pub start_line: usize,
    #[serde(rename = "endLine")]
    pub end_line: usize,
    pub replacement: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileOpReq {
    pub path: String,
    #[serde(rename = "newPath")]
    pub new_path: Option<String>,
    #[serde(rename = "isDir")]
    pub is_dir: Option<bool>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileCopyReq {
    #[serde(rename = "sourcePath")]
    pub source_path: String,
    #[serde(rename = "targetPath")]
    pub target_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CreateProjectReq {
    pub name: String,
    pub template: Option<String>,
    pub path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AddProjectReq {
    pub path: String,
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchMatch {
    pub path: String,
    #[serde(rename = "lineNumber")]
    pub line_number: usize,
    #[serde(rename = "lineText")]
    pub line_text: String,
    #[serde(rename = "matchStart", skip_serializing_if = "Option::is_none")]
    pub match_start: Option<usize>,
    #[serde(rename = "matchEnd", skip_serializing_if = "Option::is_none")]
    pub match_end: Option<usize>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffLine {
    #[serde(rename = "type")]
    pub line_type: String, // "equal", "delete", "insert"
    #[serde(rename = "oldLine", skip_serializing_if = "Option::is_none")]
    pub old_line: Option<usize>,
    #[serde(rename = "newLine", skip_serializing_if = "Option::is_none")]
    pub new_line: Option<usize>,
    pub text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffHunk {
    pub header: String,
    pub lines: Vec<DiffLine>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffFile {
    pub path: String,
    #[serde(rename = "oldPath", skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
    pub status: String, // "modified", "added", "deleted"
    pub hunks: Vec<DiffHunk>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiffResponse {
    pub diff: String,
    pub files: Vec<DiffFile>,
}

#[derive(Debug, Deserialize)]
pub struct ComputeDiffReq {
    #[serde(rename = "oldText")]
    pub old_text: String,
    #[serde(rename = "newText")]
    pub new_text: String,
    #[serde(default)]
    pub filename: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HealthResponse {
    pub status: String,
    pub timestamp: String,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatusHub {
    pub active: bool,
    pub status: String,
    pub port: String,
    pub csrf_token: String,
    pub address: String,
    pub error: String,
    pub logs: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StatusResponse {
    pub status: String,
    pub uptime: f64,
    pub timestamp: String,
    pub version: String,
    pub hub: StatusHub,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitFileStatus {
    pub path: String,
    #[serde(rename = "oldPath", skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
    pub status: String,
    pub staged: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitBranchInfo {
    pub name: String,
    #[serde(rename = "isCurrent")]
    pub is_current: bool,
    #[serde(rename = "isRemote", default)]
    pub is_remote: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitConfig {
    #[serde(rename = "userName")]
    pub user_name: String,
    #[serde(rename = "userEmail")]
    pub user_email: String,
    #[serde(rename = "remoteOrigin", skip_serializing_if = "Option::is_none")]
    pub remote_origin: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitActionResult {
    pub success: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitActionReq {
    #[serde(default)]
    pub project: Option<String>,
    pub branch: Option<String>,
    #[serde(default)]
    pub create: Option<bool>,
    #[serde(default)]
    pub paths: Option<Vec<String>>,
    pub message: Option<String>,
    pub remote: Option<String>,
    pub url: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitSetConfigReq {
    pub project: Option<String>,
    #[serde(rename = "userName")]
    pub user_name: Option<String>,
    #[serde(rename = "userEmail")]
    pub user_email: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitCommitRef {
    pub name: String,
    #[serde(rename = "isHead", default)]
    pub is_head: bool,
    #[serde(rename = "isRemote", default)]
    pub is_remote: bool,
    #[serde(rename = "isTag", default)]
    pub is_tag: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitGraphSegment {
    #[serde(rename = "fromCol")]
    pub from_col: usize,
    #[serde(rename = "toCol")]
    pub to_col: usize,
    #[serde(rename = "type")]
    pub segment_type: String, // "direct", "branch", "merge", "pass_through"
    #[serde(rename = "colorIndex")]
    pub color_index: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitGraphCommit {
    pub hash: String,
    #[serde(rename = "shortHash")]
    pub short_hash: String,
    pub author: String,
    pub date: String,
    pub message: String,
    pub subject: String,
    pub parents: Vec<String>,
    #[serde(rename = "isUnpushed", default)]
    pub is_unpushed: bool,
    #[serde(rename = "isMerge", default)]
    pub is_merge: bool,
    #[serde(rename = "graphColumn", default)]
    pub graph_column: usize,
    #[serde(rename = "colorIndex", default)]
    pub color_index: usize,
    #[serde(default)]
    pub refs: Vec<GitCommitRef>,
    #[serde(default)]
    pub segments: Vec<GitGraphSegment>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitCommitFileChange {
    pub path: String,
    pub status: String, // "M", "A", "D", "R"
    #[serde(rename = "oldPath", skip_serializing_if = "Option::is_none")]
    pub old_path: Option<String>,
    #[serde(default)]
    pub additions: usize,
    #[serde(default)]
    pub deletions: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GitCommitDetailsResponse {
    pub hash: String,
    #[serde(rename = "shortHash")]
    pub short_hash: String,
    pub author: String,
    pub date: String,
    pub subject: String,
    pub body: String,
    #[serde(default)]
    pub message: String,
    #[serde(rename = "changedFiles")]
    pub changed_files: Vec<GitCommitFileChange>,
    pub stats: String,
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationSummary {
    pub id: String,
    pub title: String,
    #[serde(rename = "created_at")]
    pub created_at: String,
    #[serde(rename = "steps_count", default)]
    pub steps_count: usize,
    #[serde(rename = "isRunning", default)]
    pub is_running: bool,
    #[serde(rename = "lastActivity", skip_serializing_if = "Option::is_none")]
    pub last_activity: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationListResponse {
    pub conversations: Vec<ConversationSummary>,
    pub total: usize,
    pub page: usize,
    pub limit: usize,
    #[serde(rename = "hasMore")]
    pub has_more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    pub name: String,
    pub command: String,
    pub status: String,
    pub output: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub thinking: Option<String>,
    #[serde(rename = "tool_calls", default, skip_serializing_if = "Vec::is_empty")]
    pub tool_calls: Vec<ToolCall>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TranscriptSearchMatch {
    #[serde(rename = "conversationId")]
    pub conversation_id: String,
    pub title: String,
    #[serde(rename = "stepIndex")]
    pub step_index: u64,
    pub role: String,
    pub snippet: String,
    #[serde(rename = "matchOffset")]
    pub match_offset: usize,
    pub timestamp: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TranscriptSearchResponse {
    pub query: String,
    pub total: usize,
    pub matches: Vec<TranscriptSearchMatch>,
}

