use antibot_native::mcp_rpc;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    io::{BufRead, BufReader, Write},
    path::PathBuf,
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc, Mutex,
    },
    time::Instant,
};

fn home_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
    PathBuf::from(home)
}

fn mcp_config_path() -> PathBuf {
    let candidates = [
        home_dir().join(".gemini/config/mcp_config.json"),
        home_dir().join(".gemini/antigravity-cli/mcp_config.json"),
        home_dir().join(".gemini/antigravity/mcp_config.json"),
    ];
    for c in &candidates {
        if c.exists() {
            return c.clone();
        }
    }
    candidates[0].clone()
}

fn mcp_schemas_dir() -> PathBuf {
    let candidates = [
        home_dir().join(".gemini/antigravity-cli/mcp"),
        home_dir().join(".gemini/config/mcp"),
        home_dir().join(".gemini/antigravity/mcp"),
    ];
    for c in &candidates {
        if c.exists() {
            return c.clone();
        }
    }
    candidates[0].clone()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpServerSpec {
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: HashMap<String, String>,
    #[serde(default, rename = "serverUrl")]
    pub server_url: Option<String>,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default)]
    pub disabled: bool,
    #[serde(default)]
    pub cwd: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpToolDetail {
    pub server: String,
    pub name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_schema: Option<Value>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpServerInfo {
    pub name: String,
    pub status: String, // "running", "stopped", "disabled", "error"
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server_url: Option<String>,
    pub args: Vec<String>,
    pub disabled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pid: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uptime_secs: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_mb: Option<f64>,
    pub tools_count: usize,
    pub tools: Vec<McpToolDetail>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

pub struct ActiveMcpProcess {
    pub child: Child,
    pub stdin: ChildStdin,
    pub stdout_reader: BufReader<ChildStdout>,
    pub start_time: Instant,
    pub pid: u32,
    pub req_id: AtomicU64,
}

pub struct McpSupervisor {
    processes: Arc<Mutex<HashMap<String, ActiveMcpProcess>>>,
}

impl Default for McpSupervisor {
    fn default() -> Self {
        Self::new()
    }
}

impl McpSupervisor {
    pub fn new() -> Self {
        Self {
            processes: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Reads configured servers from `mcp_config.json` and schema directories.
    pub fn read_config(&self) -> HashMap<String, McpServerSpec> {
        let p = mcp_config_path();
        let mut map = HashMap::new();
        if let Ok(content) = fs::read_to_string(&p) {
            if let Ok(val) = serde_json::from_str::<Value>(&content) {
                if let Some(servers) = val.get("mcpServers").and_then(|s| s.as_object()) {
                    for (k, v) in servers {
                        if let Ok(spec) = serde_json::from_value::<McpServerSpec>(v.clone()) {
                            map.insert(k.clone(), spec);
                        }
                    }
                }
            }
        }

        // Also discover any server directories in ~/.gemini/antigravity-cli/mcp/
        if let Ok(entries) = fs::read_dir(mcp_schemas_dir()) {
            for entry in entries.flatten() {
                if entry.file_type().map(|t| t.is_dir()).unwrap_or(false) {
                    if let Some(name) = entry.file_name().to_str() {
                        if !map.contains_key(name) {
                            map.insert(name.to_string(), McpServerSpec {
                                command: None,
                                args: vec![],
                                env: HashMap::new(),
                                server_url: None,
                                headers: HashMap::new(),
                                disabled: false,
                                cwd: None,
                            });
                        }
                    }
                }
            }
        }

        map
    }

    /// Reads discovered tool schemas from directory ~/.gemini/antigravity-cli/mcp/<server_name>/*.json
    pub fn read_server_tools(&self, server_name: &str) -> Vec<McpToolDetail> {
        let dir = mcp_schemas_dir().join(server_name);
        let mut tools = Vec::new();
        if let Ok(entries) = fs::read_dir(dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().and_then(|e| e.to_str()) == Some("json") {
                    let file_stem = path.file_stem().and_then(|s| s.to_str()).unwrap_or_default();
                    if file_stem.is_empty() || file_stem == "instructions" {
                        continue;
                    }
                    if let Ok(raw) = fs::read_to_string(&path) {
                        let schema_val: Option<Value> = serde_json::from_str(&raw).ok();
                        let desc = schema_val
                            .as_ref()
                            .and_then(|s| s.get("description"))
                            .and_then(|d| d.as_str())
                            .map(|d| d.to_string());

                        tools.push(McpToolDetail {
                            server: server_name.to_string(),
                            name: file_stem.to_string(),
                            description: desc,
                            input_schema: schema_val,
                        });
                    }
                }
            }
        }
        tools.sort_by(|a, b| a.name.cmp(&b.name));
        tools
    }

    /// Lists all servers combining configuration, running process state, and schema tools.
    pub fn list_servers(&self) -> Vec<McpServerInfo> {
        let config = self.read_config();
        let mut lock = self.processes.lock().unwrap();
        let mut results = Vec::new();

        for (name, spec) in config {
            let tools = self.read_server_tools(&name);
            let tools_count = tools.len();

            if spec.disabled {
                results.push(McpServerInfo {
                    name,
                    status: "disabled".to_string(),
                    command: spec.command,
                    server_url: spec.server_url,
                    args: spec.args,
                    disabled: true,
                    pid: None,
                    uptime_secs: None,
                    memory_mb: None,
                    tools_count,
                    tools,
                    error: None,
                });
                continue;
            }

            // Check if process is running
            let (status, pid, uptime_secs, memory_mb) = if let Some(proc) = lock.get_mut(&name) {
                // Check if still alive
                match proc.child.try_wait() {
                    Ok(Some(_status)) => {
                        ("stopped".to_string(), None, None, None)
                    }
                    Ok(None) => {
                        let pid = proc.pid;
                        let uptime = proc.start_time.elapsed().as_secs();
                        let mem = get_proc_memory_mb(pid);
                        ("running".to_string(), Some(pid), Some(uptime), mem)
                    }
                    Err(_) => ("error".to_string(), None, None, None),
                }
            } else if spec.server_url.is_some() {
                ("remote".to_string(), None, None, None)
            } else {
                ("stopped".to_string(), None, None, None)
            };

            results.push(McpServerInfo {
                name,
                status,
                command: spec.command,
                server_url: spec.server_url,
                args: spec.args,
                disabled: false,
                pid,
                uptime_secs,
                memory_mb,
                tools_count,
                tools,
                error: None,
            });
        }

        results.sort_by(|a, b| a.name.cmp(&b.name));
        results
    }

    /// Lists all available tools across all servers.
    pub fn list_all_tools(&self) -> Vec<McpToolDetail> {
        let servers = self.list_servers();
        let mut all_tools = Vec::new();
        for s in servers {
            if !s.disabled {
                all_tools.extend(s.tools);
            }
        }
        all_tools
    }

    /// Spawns an MCP child process if not already running.
    pub fn ensure_server_running(&self, server_name: &str) -> Result<u32, String> {
        let mut lock = self.processes.lock().unwrap();

        if let Some(proc) = lock.get_mut(server_name) {
            if let Ok(None) = proc.child.try_wait() {
                return Ok(proc.pid);
            }
        }

        let config = self.read_config();
        let spec = config
            .get(server_name)
            .ok_or_else(|| format!("MCP server '{}' not found in mcp_config.json", server_name))?;

        if spec.disabled {
            return Err(format!("MCP server '{}' is currently disabled", server_name));
        }

        let cmd_str = match &spec.command {
            Some(c) => c,
            None => {
                if spec.server_url.is_some() {
                    return Ok(0);
                } else {
                    return Err(format!("MCP server '{}' has neither command nor serverUrl configured", server_name));
                }
            }
        };

        let mut cmd = Command::new(cmd_str);
        cmd.args(&spec.args)
            .envs(&spec.env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());

        if let Some(ref cwd) = spec.cwd {
            cmd.current_dir(cwd);
        }

        let mut child = cmd
            .spawn()
            .map_err(|e| format!("Failed to spawn MCP server '{}' ({}): {}", server_name, cmd_str, e))?;

        let pid = child.id();
        let stdin = child.stdin.take().ok_or("Failed to open child stdin")?;
        let stdout = child.stdout.take().ok_or("Failed to open child stdout")?;
        let stdout_reader = BufReader::new(stdout);

        lock.insert(
            server_name.to_string(),
            ActiveMcpProcess {
                child,
                stdin,
                stdout_reader,
                start_time: Instant::now(),
                pid,
                req_id: AtomicU64::new(1),
            },
        );

        Ok(pid)
    }

    /// Executes a tool call over JSON-RPC 2.0 streaming stdio.
    pub fn call_tool(
        &self,
        server_name: &str,
        tool_name: &str,
        arguments: Value,
    ) -> Result<Value, String> {
        mcp_rpc::validate_tool_call(tool_name, &arguments)?;

        // Ensure process is running
        self.ensure_server_running(server_name)?;

        let mut lock = self.processes.lock().unwrap();
        let proc = lock
            .get_mut(server_name)
            .ok_or_else(|| format!("Active process for '{}' missing", server_name))?;

        let id = proc.req_id.fetch_add(1, Ordering::SeqCst);
        let params = json!({
            "name": tool_name,
            "arguments": arguments,
        });

        let frame = mcp_rpc::frame_jsonrpc_request(id, "tools/call", Some(params));

        // Write to stdin
        proc.stdin
            .write_all(frame.as_bytes())
            .map_err(|e| format!("Failed to write to MCP stdin: {}", e))?;
        proc.stdin
            .flush()
            .map_err(|e| format!("Failed to flush MCP stdin: {}", e))?;

        // Read response line from stdout with a timeout
        let mut line = String::new();
        // Since BufReader doesn't have timeout natively without tokio async,
        // we read line directly:
        proc.stdout_reader
            .read_line(&mut line)
            .map_err(|e| format!("Failed to read MCP response: {}", e))?;

        if line.trim().is_empty() {
            return Err("Received empty response from MCP server".to_string());
        }

        let rpc_res = mcp_rpc::parse_jsonrpc_response(&line)?;
        if let Some(err) = rpc_res.error {
            return Err(format!("MCP Error [{}]: {}", err.code, err.message));
        }

        Ok(rpc_res.result.unwrap_or(Value::Null))
    }

    /// Restarts an MCP server, terminating child and cleaning up.
    pub fn restart_server(&self, server_name: &str) -> Result<u32, String> {
        {
            let mut lock = self.processes.lock().unwrap();
            if let Some(mut proc) = lock.remove(server_name) {
                let _ = proc.child.kill();
                let _ = proc.child.wait();
            }
        }
        self.ensure_server_running(server_name)
    }

    /// Toggles enabled/disabled state in mcp_config.json.
    pub fn toggle_server(&self, server_name: &str, enabled: bool) -> Result<(), String> {
        let p = mcp_config_path();
        let content = fs::read_to_string(&p).unwrap_or_else(|_| "{}".to_string());
        let mut val: Value = serde_json::from_str(&content).unwrap_or_else(|_| json!({}));

        if let Some(servers) = val.get_mut("mcpServers").and_then(|s| s.as_object_mut()) {
            if let Some(server) = servers.get_mut(server_name).and_then(|s| s.as_object_mut()) {
                server.insert("disabled".to_string(), json!(!enabled));
            } else {
                return Err(format!("Server '{}' not found in mcp_config.json", server_name));
            }
        } else {
            return Err("Invalid mcp_config.json structure".to_string());
        }

        // If disabling, terminate process if running
        if !enabled {
            let mut lock = self.processes.lock().unwrap();
            if let Some(mut proc) = lock.remove(server_name) {
                let _ = proc.child.kill();
                let _ = proc.child.wait();
            }
        }

        let formatted = serde_json::to_string_pretty(&val).map_err(|e| e.to_string())?;
        fs::write(&p, formatted).map_err(|e| format!("Failed to write mcp_config.json: {}", e))?;

        Ok(())
    }
}

fn get_proc_memory_mb(pid: u32) -> Option<f64> {
    // Read resident pages from /proc/<pid>/statm on Linux/Android
    let statm_path = format!("/proc/{}/statm", pid);
    if let Ok(content) = fs::read_to_string(statm_path) {
        let parts: Vec<&str> = content.split_whitespace().collect();
        if parts.len() >= 2 {
            if let Ok(resident_pages) = parts[1].parse::<f64>() {
                // 4KB per page => resident_pages * 4 / 1024 = MB
                let mb = (resident_pages * 4.0) / 1024.0;
                return Some((mb * 10.0).round() / 10.0);
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_mcp_supervisor_creation() {
        let supervisor = McpSupervisor::new();
        let servers = supervisor.list_servers();
        // Check that servers can be listed without panicking
        assert!(servers.is_empty() || !servers.is_empty());
    }

    #[test]
    fn test_get_proc_memory_mb_current_process() {
        let pid = std::process::id();
        let mem = get_proc_memory_mb(pid);
        assert!(mem.is_some());
        assert!(mem.unwrap() > 0.0);
    }
}
