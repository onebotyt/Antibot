use std::{collections::HashMap, sync::Arc};
use tokio::sync::RwLock;
use tracing::info;

use super::pty::{PtySession, SpawnOptions};

pub struct PtySupervisor {
    sessions: Arc<RwLock<HashMap<String, Arc<PtySession>>>>,
}

impl PtySupervisor {
    pub fn new() -> Self {
        Self {
            sessions: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub async fn spawn(&self, opts: SpawnOptions) -> Result<Arc<PtySession>, Box<dyn std::error::Error + Send + Sync>> {
        let session = PtySession::spawn(opts)?;
        let mut map = self.sessions.write().await;
        map.insert(session.id.clone(), Arc::clone(&session));
        Ok(session)
    }

    pub async fn get(&self, id: &str) -> Option<Arc<PtySession>> {
        let map = self.sessions.read().await;
        map.get(id).cloned()
    }

    pub async fn list(&self) -> Vec<Arc<PtySession>> {
        let map = self.sessions.read().await;
        map.values().cloned().collect()
    }

    pub async fn kill(&self, id: &str, signal: Option<i32>) -> Result<(), String> {
        let session = self.get(id).await.ok_or_else(|| format!("Session '{}' not found", id))?;
        session.kill_process_group(signal).map_err(|e| e.to_string())
    }

    pub async fn close(&self, id: &str) -> Result<(), String> {
        let session = {
            let mut map = self.sessions.write().await;
            map.remove(id)
        };
        if let Some(s) = session {
            let _ = s.kill_process_group(Some(libc::SIGTERM));
            Ok(())
        } else {
            Err(format!("Session '{}' not found", id))
        }
    }

    pub async fn kill_all(&self) -> usize {
        let map = self.sessions.read().await;
        let mut count = 0;
        for session in map.values() {
            if session.is_alive() {
                if session.kill_process_group(Some(libc::SIGTERM)).is_ok() {
                    count += 1;
                }
            }
        }
        if count > 0 {
            info!("⚡ Killed {} supervised child process groups", count);
        }
        count
    }

    pub async fn prune_exited(&self, keep_last: usize) {
        let mut map = self.sessions.write().await;
        let mut exited: Vec<(String, chrono::DateTime<chrono::Utc>)> = map
            .iter()
            .filter(|(_, s)| !s.is_alive())
            .map(|(k, s)| (k.clone(), s.started_at))
            .collect();

        if exited.len() > keep_last {
            exited.sort_by_key(|(_, t)| *t);
            let to_remove = exited.len() - keep_last;
            for (id, _) in exited.into_iter().take(to_remove) {
                map.remove(&id);
            }
        }
    }
}

impl Default for PtySupervisor {
    fn default() -> Self {
        Self::new()
    }
}
