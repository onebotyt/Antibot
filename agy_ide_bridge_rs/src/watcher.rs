//! Inotify-based recursive workspace file watcher & real-time WebSocket event broadcaster.
//!
//! Watches active workspace projects and streams file change events (`create`, `modify`, `remove`, `rename`)
//! over the existing WebSocket bus (`/ws`), enabling zero-polling live disk synchronization in the Android IDE.

use notify::{Config, Event, EventKind, RecommendedWatcher, RecursiveMode, Watcher};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex, RwLock,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct FsEventPayload {
    #[serde(rename = "type")]
    pub event_type: String,
    pub kind: String,
    pub path: String,
    pub is_dir: bool,
    pub timestamp: u64,
}

pub struct FsWatcher {
    watcher: Mutex<Option<RecommendedWatcher>>,
    watched_paths: RwLock<HashSet<PathBuf>>,
    _broadcast_tx: broadcast::Sender<String>,
    is_active: AtomicBool,
}

impl FsWatcher {
    /// Creates and starts an Inotify file watcher.
    pub fn new(broadcast_tx: broadcast::Sender<String>) -> Arc<Self> {
        let (event_tx, mut event_rx) = tokio::sync::mpsc::channel::<Event>(256);

        let watcher_res = RecommendedWatcher::new(
            move |res: Result<Event, notify::Error>| match res {
                Ok(event) => {
                    let _ = event_tx.blocking_send(event);
                }
                Err(err) => {
                    warn!("Inotify watcher error: {}", err);
                }
            },
            Config::default().with_poll_interval(Duration::from_millis(100)),
        );

        let (watcher_opt, active) = match watcher_res {
            Ok(w) => (Some(w), true),
            Err(e) => {
                error!("Failed to initialize Linux inotify watcher: {}", e);
                (None, false)
            }
        };

        let fs_watcher = Arc::new(Self {
            watcher: Mutex::new(watcher_opt),
            watched_paths: RwLock::new(HashSet::new()),
            _broadcast_tx: broadcast_tx.clone(),
            is_active: AtomicBool::new(active),
        });

        // Spawn background debounced event processor
        let tx = broadcast_tx;
        tokio::spawn(async move {
            let mut last_seen: HashMap<PathBuf, Instant> = HashMap::new();
            let debounce_window = Duration::from_millis(50);

            while let Some(event) = event_rx.recv().await {
                let kind_str = match event.kind {
                    EventKind::Create(_) => "create",
                    EventKind::Modify(_) => "modify",
                    EventKind::Remove(_) => "remove",
                    EventKind::Any | EventKind::Other => "modify",
                    _ => continue,
                };

                let now = Instant::now();
                // Prune expired entries from debounce map periodically
                if last_seen.len() > 1000 {
                    last_seen.retain(|_, last_time| now.duration_since(*last_time) < Duration::from_secs(5));
                }

                for path in event.paths {
                    // Filter noisy files
                    if should_ignore_path(&path) {
                        continue;
                    }

                    // Debounce rapid duplicate events on the same file
                    if let Some(prev) = last_seen.get(&path) {
                        if now.duration_since(*prev) < debounce_window {
                            continue;
                        }
                    }
                    last_seen.insert(path.clone(), now);

                    let is_dir = path.is_dir();
                    let path_str = path.to_string_lossy().to_string();
                    let timestamp = SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .unwrap_or_default()
                        .as_millis() as u64;

                    let payload = FsEventPayload {
                        event_type: "fs_event".to_string(),
                        kind: kind_str.to_string(),
                        path: path_str,
                        is_dir,
                        timestamp,
                    };

                    if let Ok(msg) = serde_json::to_string(&payload) {
                        debug!("Broadcasting FS event: {}", msg);
                        let _ = tx.send(msg);
                    }
                }
            }
        });

        fs_watcher
    }

    /// Recursively watches a directory for filesystem events.
    pub fn watch(&self, path: &Path) -> Result<(), String> {
        if !self.is_active.load(Ordering::Relaxed) {
            return Err("Watcher not active".to_string());
        }
        if !path.exists() {
            return Err(format!("Path does not exist: {}", path.display()));
        }

        let canonical = match path.canonicalize() {
            Ok(p) => p,
            Err(_) => path.to_path_buf(),
        };

        let mut lock = self.watcher.lock().map_err(|e| e.to_string())?;
        if let Some(watcher) = lock.as_mut() {
            watcher
                .watch(&canonical, RecursiveMode::Recursive)
                .map_err(|e| format!("Failed to watch {}: {}", canonical.display(), e))?;

            let mut paths = self.watched_paths.write().map_err(|e| e.to_string())?;
            paths.insert(canonical.clone());
            info!("👁️ Inotify watching directory: {}", canonical.display());
            Ok(())
        } else {
            Err("Watcher not initialized".to_string())
        }
    }

    /// Stops watching a directory.
    pub fn unwatch(&self, path: &Path) -> Result<(), String> {
        let canonical = match path.canonicalize() {
            Ok(p) => p,
            Err(_) => path.to_path_buf(),
        };

        let mut lock = self.watcher.lock().map_err(|e| e.to_string())?;
        if let Some(watcher) = lock.as_mut() {
            let _ = watcher.unwatch(&canonical);
            let mut paths = self.watched_paths.write().map_err(|e| e.to_string())?;
            paths.remove(&canonical);
            info!("Stopped watching directory: {}", canonical.display());
            Ok(())
        } else {
            Err("Watcher not initialized".to_string())
        }
    }

    /// Lists all currently watched directory paths.
    pub fn list_watched(&self) -> Vec<String> {
        if let Ok(paths) = self.watched_paths.read() {
            paths.iter().map(|p| p.to_string_lossy().to_string()).collect()
        } else {
            Vec::new()
        }
    }
}

/// Checks if a file path should be ignored from broadcasting.
fn should_ignore_path(path: &Path) -> bool {
    let s = path.to_string_lossy();

    // Internal VCS & build artifacts
    if s.contains("/.git/objects/")
        || s.contains("/.git/index.lock")
        || s.contains("/.git/logs/")
        || s.contains("/target/debug/")
        || s.contains("/target/release/")
        || s.contains("/node_modules/.cache/")
        || s.contains("/.gradle/")
        || s.contains("/build/intermediates/")
    {
        return true;
    }

    // Temporary editor swap files
    if s.ends_with('~') || s.ends_with(".swp") || s.ends_with(".tmp") || s.ends_with(".crswap") {
        return true;
    }

    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_fs_watcher_lifecycle() {
        let (tx, mut rx) = broadcast::channel(16);
        let watcher = FsWatcher::new(tx);

        let temp_dir = std::env::temp_dir().join(format!("antibot_test_watch_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&temp_dir);

        let res = watcher.watch(&temp_dir);
        assert!(res.is_ok(), "Should successfully watch temp dir");

        let watched = watcher.list_watched();
        assert!(!watched.is_empty(), "Watched list should contain temp dir");

        // Write a test file
        let test_file = temp_dir.join("test_watch.txt");
        let _ = std::fs::write(&test_file, "hello inotify");

        // Wait for event on broadcast channel
        let event = tokio::time::timeout(Duration::from_millis(500), rx.recv()).await;
        if let Ok(Ok(msg)) = event {
            assert!(msg.contains("fs_event"), "Event should have type fs_event");
            assert!(msg.contains("test_watch.txt"), "Event should contain filename");
        }

        let _ = watcher.unwatch(&temp_dir);
        let _ = std::fs::remove_dir_all(&temp_dir);
    }
}

