use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use std::process::Stdio;
use tokio::io::{AsyncBufReadExt, BufReader};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CliJobState {
    pub kind: String,
    pub running: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i32>,
    pub log: String,
}

/// Locates any CLI binary across system paths, Termux/AntiBot prefixes, pnpm/npm globals, and PATH.
pub fn resolve_cli_binary(name: &str) -> Option<String> {
    // 1. Direct environment PATH
    if let Ok(path_var) = std::env::var("PATH") {
        for dir in path_var.split(':') {
            let p = PathBuf::from(dir).join(name);
            if p.is_file() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }

    // 2. Discover from PREFIX and HOME
    if let Ok(prefix) = std::env::var("PREFIX") {
        let p = PathBuf::from(&prefix).join("bin").join(name);
        if p.is_file() {
            return Some(p.to_string_lossy().to_string());
        }
    }

    if let Ok(home) = std::env::var("HOME") {
        let candidates = [
            PathBuf::from(&home).join(".local/share/pnpm/bin").join(name),
            PathBuf::from(&home).join(".local/share/pnpm").join(name),
            PathBuf::from(&home).join(".local/bin").join(name),
            PathBuf::from(&home).join(".npm-global/bin").join(name),
            PathBuf::from(&home).join(format!(".{}/bin/{}", name, name)),
        ];
        for p in &candidates {
            if p.is_file() {
                return Some(p.to_string_lossy().to_string());
            }
        }
    }

    // 3. Known Android app prefixes (both com.antibot and com.termux)
    let packages = ["com.antibot", "com.termux"];
    for pkg in &packages {
        let candidates = [
            format!("/data/data/{}/files/usr/bin/{}", pkg, name),
            format!("/data/user/0/{}/files/usr/bin/{}", pkg, name),
            format!("/data/data/{}/files/home/.local/share/pnpm/bin/{}", pkg, name),
            format!("/data/user/0/{}/files/home/.local/share/pnpm/bin/{}", pkg, name),
            format!("/data/data/{}/files/home/.local/bin/{}", pkg, name),
            format!("/data/user/0/{}/files/home/.local/bin/{}", pkg, name),
            format!("/data/data/{}/files/home/.npm-global/bin/{}", pkg, name),
            format!("/data/user/0/{}/files/home/.npm-global/bin/{}", pkg, name),
            format!("/data/data/{}/files/home/.{}/bin/{}", pkg, name, name),
            format!("/data/user/0/{}/files/home/.{}/bin/{}", pkg, name, name),
        ];
        for path in &candidates {
            if PathBuf::from(path).is_file() {
                return Some(path.clone());
            }
        }
    }

    // 4. Standard Linux system paths
    let sys_candidates = [
        format!("/usr/local/bin/{}", name),
        format!("/usr/bin/{}", name),
        format!("/bin/{}", name),
    ];
    for path in &sys_candidates {
        if PathBuf::from(path).is_file() {
            return Some(path.clone());
        }
    }

    None
}

/// Locates the appropriate shell binary to run scripts in this environment.
pub fn resolve_shell() -> String {
    let candidates = [
        resolve_cli_binary("bash"),
        resolve_cli_binary("sh"),
    ];
    for c in candidates.into_iter().flatten() {
        if PathBuf::from(&c).is_file() {
            return c;
        }
    }
    "sh".to_string()
}

/// Builds the comprehensive environment map with Termux/AntiBot paths, pnpm paths, and LD_PRELOAD.
pub fn build_cli_env() -> HashMap<String, String> {
    let mut env = HashMap::new();

    let prefix = std::env::var("PREFIX").unwrap_or_else(|_| {
        if PathBuf::from("/data/data/com.antibot/files/usr").exists() {
            "/data/data/com.antibot/files/usr".to_string()
        } else if PathBuf::from("/data/user/0/com.antibot/files/usr").exists() {
            "/data/user/0/com.antibot/files/usr".to_string()
        } else {
            "/data/data/com.termux/files/usr".to_string()
        }
    });

    let home = std::env::var("HOME").unwrap_or_else(|_| {
        if PathBuf::from("/data/data/com.antibot/files/home").exists() {
            "/data/data/com.antibot/files/home".to_string()
        } else if PathBuf::from("/data/user/0/com.antibot/files/home").exists() {
            "/data/user/0/com.antibot/files/home".to_string()
        } else {
            "/data/data/com.termux/files/home".to_string()
        }
    });

    let current_path = std::env::var("PATH").unwrap_or_default();
    let pnpm_home = format!("{}/.local/share/pnpm", home);
    let pnpm_bin = format!("{}/bin", pnpm_home);
    let local_bin = format!("{}/.local/bin", home);
    let npm_global_bin = format!("{}/.npm-global/bin", home);
    let prefix_bin = format!("{}/bin", prefix);

    let extended_path = format!(
        "{}:{}:{}:{}:{}:/system/bin:/system/xbin:{}",
        pnpm_bin, pnpm_home, local_bin, npm_global_bin, prefix_bin, current_path
    );

    env.insert("PREFIX".to_string(), prefix.clone());
    env.insert("HOME".to_string(), home);
    env.insert("PNPM_HOME".to_string(), pnpm_home);
    env.insert("PATH".to_string(), extended_path);
    env.insert("ANDROID_DATA".to_string(), "/data".to_string());
    env.insert("ANDROID_ROOT".to_string(), "/system".to_string());
    env.insert("LANG".to_string(), "en_US.UTF-8".to_string());
    env.insert("LC_ALL".to_string(), "en_US.UTF-8".to_string());

    // LD_PRELOAD libtermux-exec.so if present
    let lib_exec_paths = [
        format!("{}/lib/libtermux-exec.so", prefix),
        "/data/data/com.antibot/files/usr/lib/libtermux-exec.so".to_string(),
        "/data/user/0/com.antibot/files/usr/lib/libtermux-exec.so".to_string(),
        "/data/data/com.termux/files/usr/lib/libtermux-exec.so".to_string(),
    ];
    for lib in &lib_exec_paths {
        if PathBuf::from(lib).exists() {
            env.insert("LD_PRELOAD".to_string(), lib.clone());
            break;
        }
    }

    env
}

/// Spawns a real background CLI installation/update task.
pub fn spawn_cli_job(
    cli_name: String,
    action: String,
    command_script: String,
    active_job: Arc<RwLock<Option<CliJobState>>>,
) {
    let initial_job = CliJobState {
        kind: action.clone(),
        running: true,
        exit_code: None,
        log: format!("=== Starting {} {} ===\nExecuting: {}\n\n", cli_name, action, command_script),
    };

    *active_job.write().unwrap() = Some(initial_job);

    tokio::spawn(async move {
        let shell = resolve_shell();
        let env_vars = build_cli_env();

        let mut cmd = tokio::process::Command::new(&shell);
        cmd.arg("-c").arg(&command_script);

        for (k, v) in env_vars {
            cmd.env(k, v);
        }

        cmd.stdout(Stdio::piped());
        cmd.stderr(Stdio::piped());

        match cmd.spawn() {
            Ok(mut child) => {
                let stdout = child.stdout.take();
                let stderr = child.stderr.take();

                let active_job_out = active_job.clone();
                let out_task = tokio::spawn(async move {
                    if let Some(out) = stdout {
                        let mut reader = BufReader::new(out).lines();
                        while let Ok(Some(line)) = reader.next_line().await {
                            let mut j = active_job_out.write().unwrap();
                            if let Some(ref mut job) = *j {
                                job.log.push_str(&line);
                                job.log.push('\n');
                            }
                        }
                    }
                });

                let active_job_err = active_job.clone();
                let err_task = tokio::spawn(async move {
                    if let Some(err) = stderr {
                        let mut reader = BufReader::new(err).lines();
                        while let Ok(Some(line)) = reader.next_line().await {
                            let mut j = active_job_err.write().unwrap();
                            if let Some(ref mut job) = *j {
                                job.log.push_str(&line);
                                job.log.push('\n');
                            }
                        }
                    }
                });

                let (status, _, _) = tokio::join!(child.wait(), out_task, err_task);
                let exit_code = status.ok().and_then(|s| s.code()).unwrap_or(-1);

                let mut j = active_job.write().unwrap();
                if let Some(ref mut job) = *j {
                    job.running = false;
                    job.exit_code = Some(exit_code);
                    if exit_code == 0 {
                        job.log.push_str(&format!("\n=== {} {} completed successfully. ===\n", cli_name, action));
                    } else {
                        job.log.push_str(&format!("\n=== {} {} failed with exit code {}. ===\n", cli_name, action, exit_code));
                    }
                }
            }
            Err(e) => {
                let mut j = active_job.write().unwrap();
                if let Some(ref mut job) = *j {
                    job.running = false;
                    job.exit_code = Some(-1);
                    job.log.push_str(&format!("\nFailed to spawn shell '{}': {}\n", shell, e));
                }
            }
        }
    });
}
