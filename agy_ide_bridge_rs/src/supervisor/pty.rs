use std::{
    collections::VecDeque,
    io::{Read, Write},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::Instant,
};
use chrono::{DateTime, Utc};
use portable_pty::{CommandBuilder, MasterPty, NativePtySystem, PtySize, PtySystem};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::broadcast;
use tracing::{info, warn};

const MAX_OUTPUT_BUFFER_BYTES: usize = 256 * 1024; // 256 KB ring buffer

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SpawnOptions {
    pub id: Option<String>,
    pub title: Option<String>,
    pub command: Option<String>,
    pub args: Option<Vec<String>>,
    pub cwd: Option<String>,
    pub cols: Option<u16>,
    pub rows: Option<u16>,
    pub env: Option<std::collections::HashMap<String, String>>,
}

pub struct PtySession {
    pub id: String,
    pub title: String,
    pub command: String,
    pub args: Vec<String>,
    pub cwd: String,
    pub pid: u32,
    pub cols: Arc<Mutex<u16>>,
    pub rows: Arc<Mutex<u16>>,
    pub started_at: DateTime<Utc>,
    pub is_alive: Arc<AtomicBool>,
    pub exit_code: Arc<Mutex<Option<i32>>>,
    pub writer: Arc<tokio::sync::Mutex<Box<dyn Write + Send>>>,
    pub master: Arc<Mutex<Box<dyn MasterPty + Send>>>,
    pub output_buffer: Arc<Mutex<VecDeque<u8>>>,
    pub broadcast_tx: broadcast::Sender<Vec<u8>>,
    pub last_activity: Arc<Mutex<Instant>>,
}

impl PtySession {
    pub fn spawn(opts: SpawnOptions) -> Result<Arc<Self>, Box<dyn std::error::Error + Send + Sync>> {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/data/data/com.termux/files/home".to_string());
        let cwd = opts
            .cwd
            .unwrap_or_else(|| format!("{}/.gemini/antigravity-cli/scratch", home));

        let default_shell = std::env::var("SHELL").unwrap_or_else(|_| {
            let termux_bash = "/data/data/com.termux/files/usr/bin/bash";
            if std::path::Path::new(termux_bash).exists() {
                termux_bash.to_string()
            } else {
                "bash".to_string()
            }
        });

        let command = opts.command.unwrap_or(default_shell);
        let args = opts.args.unwrap_or_default();
        let cols = opts.cols.unwrap_or(80).max(10);
        let rows = opts.rows.unwrap_or(24).max(4);

        let id = opts
            .id
            .unwrap_or_else(|| format!("term_{}", &uuid_timestamp()));
        let title = opts
            .title
            .unwrap_or_else(|| format!("{}: {}", id, command));

        let pty_system = NativePtySystem::default();
        let pair = pty_system.openpty(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;

        let mut cmd = CommandBuilder::new(&command);
        cmd.args(&args);
        cmd.cwd(&cwd);

        // Termux / Android standard environment
        cmd.env("TERM", "xterm-256color");
        cmd.env("COLORTERM", "truecolor");
        cmd.env("LANG", "en_US.UTF-8");
        cmd.env("HOME", &home);

        if let Some(custom_envs) = opts.env {
            for (k, v) in custom_envs {
                cmd.env(k, v);
            }
        }

        let mut child = pair.slave.spawn_command(cmd)?;
        let pid = child.process_id().unwrap_or(0);
        info!("🐚 Spawned PTY session '{}' (PID: {}, command: '{}')", id, pid, command);

        let mut reader = pair.master.try_clone_reader()?;
        let writer = pair.master.take_writer()?;

        let is_alive = Arc::new(AtomicBool::new(true));
        let exit_code = Arc::new(Mutex::new(None));
        let output_buffer = Arc::new(Mutex::new(VecDeque::with_capacity(32 * 1024)));
        let (broadcast_tx, _) = broadcast::channel(500);
        let last_activity = Arc::new(Mutex::new(Instant::now()));

        // Background reader thread: captures stdout/stderr from PTY master
        {
            let is_alive_clone = Arc::clone(&is_alive);
            let output_buffer_clone = Arc::clone(&output_buffer);
            let broadcast_tx_clone = broadcast_tx.clone();
            let last_activity_clone = Arc::clone(&last_activity);
            let session_id_clone = id.clone();

            std::thread::Builder::new()
                .name(format!("pty-read-{}", id))
                .spawn(move || {
                    let mut buf = [0u8; 8192];
                    loop {
                        match reader.read(&mut buf) {
                            Ok(0) => {
                                // PTY closed / child exited
                                break;
                            }
                            Ok(n) => {
                                let chunk = &buf[..n];
                                if let Ok(mut activity) = last_activity_clone.lock() {
                                    *activity = Instant::now();
                                }

                                // Append to output ring buffer
                                if let Ok(mut ring) = output_buffer_clone.lock() {
                                    if ring.len() + n > MAX_OUTPUT_BUFFER_BYTES {
                                        let excess = (ring.len() + n) - MAX_OUTPUT_BUFFER_BYTES;
                                        let drain_count = excess.min(ring.len());
                                        ring.drain(0..drain_count);
                                    }
                                    ring.extend(chunk);
                                }

                                // Broadcast to active WebSocket subscribers
                                let _ = broadcast_tx_clone.send(chunk.to_vec());
                            }
                            Err(e) => {
                                // On Linux/Android, reading an exited PTY often yields EIO
                                if e.raw_os_error() != Some(libc::EIO) {
                                    warn!("PTY read error on session '{}': {}", session_id_clone, e);
                                }
                                break;
                            }
                        }
                    }
                    is_alive_clone.store(false, Ordering::SeqCst);
                })?;
        }

        // Background waiter thread: waits for child exit and records status
        {
            let is_alive_clone = Arc::clone(&is_alive);
            let exit_code_clone = Arc::clone(&exit_code);
            let session_id_clone = id.clone();

            std::thread::Builder::new()
                .name(format!("pty-wait-{}", id))
                .spawn(move || {
                    match child.wait() {
                        Ok(status) => {
                            let code = if status.success() { 0 } else { 1 };
                            if let Ok(mut ec) = exit_code_clone.lock() {
                                *ec = Some(code);
                            }
                            info!("PTY session '{}' exited with status: {}", session_id_clone, code);
                        }
                        Err(e) => {
                            warn!("Error waiting on child in session '{}': {}", session_id_clone, e);
                        }
                    }
                    is_alive_clone.store(false, Ordering::SeqCst);
                })?;
        }

        let session = Arc::new(Self {
            id,
            title,
            command,
            args,
            cwd,
            pid,
            cols: Arc::new(Mutex::new(cols)),
            rows: Arc::new(Mutex::new(rows)),
            started_at: Utc::now(),
            is_alive,
            exit_code,
            writer: Arc::new(tokio::sync::Mutex::new(writer)),
            master: Arc::new(Mutex::new(pair.master)),
            output_buffer,
            broadcast_tx,
            last_activity,
        });

        Ok(session)
    }

    pub async fn write_data(&self, data: &[u8]) -> Result<(), std::io::Error> {
        let mut w = self.writer.lock().await;
        w.write_all(data)?;
        w.flush()?;
        if let Ok(mut act) = self.last_activity.lock() {
            *act = Instant::now();
        }
        Ok(())
    }

    pub fn resize(&self, cols: u16, rows: u16) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let cols = cols.max(10);
        let rows = rows.max(4);
        let master = self.master.lock().unwrap();
        master.resize(PtySize {
            rows,
            cols,
            pixel_width: 0,
            pixel_height: 0,
        })?;
        if let Ok(mut c) = self.cols.lock() {
            *c = cols;
        }
        if let Ok(mut r) = self.rows.lock() {
            *r = rows;
        }
        Ok(())
    }

    pub fn kill_process_group(&self, signal: Option<i32>) -> Result<(), std::io::Error> {
        let sig = signal.unwrap_or(libc::SIGTERM);
        if self.pid == 0 {
            return Ok(());
        }

        unsafe {
            // Negative PID signals the entire process group
            let pg_res = libc::kill(-(self.pid as libc::pid_t), sig);
            if pg_res != 0 {
                // If group signal failed, signal the specific PID
                libc::kill(self.pid as libc::pid_t, sig);
            }
        }

        self.is_alive.store(false, Ordering::SeqCst);
        Ok(())
    }

    pub fn get_output_snapshot(&self, max_bytes: Option<usize>) -> Vec<u8> {
        let ring = self.output_buffer.lock().unwrap();
        let max = max_bytes.unwrap_or(MAX_OUTPUT_BUFFER_BYTES);
        if ring.len() <= max {
            ring.iter().copied().collect()
        } else {
            let skip = ring.len() - max;
            ring.iter().skip(skip).copied().collect()
        }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Vec<u8>> {
        self.broadcast_tx.subscribe()
    }

    pub fn is_alive(&self) -> bool {
        self.is_alive.load(Ordering::SeqCst)
    }

    pub fn to_json(&self) -> Value {
        let cols = *self.cols.lock().unwrap();
        let rows = *self.rows.lock().unwrap();
        let is_running = self.is_alive();
        let exit_code = *self.exit_code.lock().unwrap();

        json!({
            "id": self.id,
            "title": self.title,
            "command": self.command,
            "args": self.args,
            "workingDirectory": self.cwd,
            "cwd": self.cwd,
            "pid": self.pid,
            "cols": cols,
            "rows": rows,
            "isExited": !is_running,
            "isCommandRunning": is_running,
            "lastCommand": self.command,
            "exitCode": exit_code,
            "createdAt": self.started_at.to_rfc3339(),
            "startedAt": self.started_at.to_rfc3339(),
        })
    }
}

fn uuid_timestamp() -> String {
    let now = Utc::now();
    format!("{:x}", now.timestamp_millis())
}
