use axum::extract::Json;
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    path::Path,
    time::Duration,
};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReaStatus {
    pub success: bool,
    pub container_installed: bool,
    pub ready: bool,
    pub components: HashMap<String, bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub rea_version: Option<String>,
    pub launcher_installed: bool,
}

#[derive(Debug, Deserialize)]
pub struct ReaRunRequest {
    pub command: String,
    pub args: Option<Vec<String>>,
    pub container: Option<bool>,
    pub timeout_seconds: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct ReaRunResponse {
    pub success: bool,
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
}

pub async fn rea_status_handler() -> Json<ReaStatus> {
    let mut components = HashMap::new();

    let termux_root = "/data/data/com.termux/files";
    let ubuntu_dir = format!("{}/usr/var/lib/proot-distro/installed-rootfs/ubuntu", termux_root);
    let container_installed = Path::new(&ubuntu_dir).exists();
    components.insert("container".to_string(), container_installed);

    let launcher_path = format!("{}/usr/bin/rea-mcp", termux_root);
    let launcher_installed = Path::new(&launcher_path).exists();
    components.insert("launcher".to_string(), launcher_installed);

    let jadx_exists = Path::new(&format!("{}/root/tools/jadx-headless-mcp-0.7.1-all.jar", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/jadx", termux_root)).exists();
    components.insert("jadx".to_string(), jadx_exists);

    let apktool_exists = Path::new(&format!("{}/root/tools/apktool.jar", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/apktool", termux_root)).exists();
    components.insert("apktool".to_string(), apktool_exists);

    let rizin_exists = Path::new(&format!("{}/usr/bin/rizin", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/rizin", termux_root)).exists();
    components.insert("rizin".to_string(), rizin_exists);

    let gdb_exists = Path::new(&format!("{}/usr/bin/gdb", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/gdb", termux_root)).exists();
    components.insert("gdb".to_string(), gdb_exists);

    let adb_exists = Path::new(&format!("{}/usr/bin/adb", termux_root)).exists();
    components.insert("adb".to_string(), adb_exists);

    let ghidra_exists = Path::new(&format!("{}/root/tools/ghidra/support/analyzeHeadless", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/local/bin/ghidra-decompiler", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/ghidra", termux_root)).exists();
    components.insert("ghidra".to_string(), ghidra_exists);

    let rea_version = if container_installed {
        Some("6.8.0".to_string())
    } else {
        None
    };

    let ready = launcher_installed || container_installed;

    Json(ReaStatus {
        success: true,
        container_installed,
        ready,
        components,
        rea_version,
        launcher_installed,
    })
}

pub async fn rea_tools_handler() -> Json<serde_json::Value> {
    let groups = serde_json::json!([
        {
            "id": "android",
            "name": "Android Application Analysis",
            "description": "JADX decompilation, APKTool resources, manifest decoding and ADB runtime inspection",
            "tools": [
                "inspect_android_package",
                "search_android_classes",
                "inspect_android_class",
                "inspect_android_method",
                "trace_android_references",
                "decode_android_resources",
                "inspect_apk_signature"
            ]
        },
        {
            "id": "native",
            "name": "Native ELF & Architecture Analysis",
            "description": "Rizin disassembler, symbol extraction, decompiler and function call graph",
            "tools": [
                "open_binary",
                "close_binary",
                "list_procedures",
                "procedure_assembly",
                "procedure_pseudo_code",
                "procedure_callees",
                "procedure_callers",
                "search_bytes",
                "search_strings"
            ]
        },
        {
            "id": "react_native",
            "name": "React Native & Hermes",
            "description": "Hermes bytecode decompiler (hermes-dec) and bundle string recovery",
            "tools": [
                "analyze_javascript_application",
                "recover_javascript_sources",
                "trace_web_module_imports"
            ]
        },
        {
            "id": "network",
            "name": "Network & PCAP Packet Inspection",
            "description": "Packet capture decoding, protocol structure and SSL inspection",
            "tools": [
                "inspect_pcap_capture",
                "inspect_packet_fields"
            ]
        },
        {
            "id": "ghidra",
            "name": "NSA Ghidra Decompiler & Headless Analysis",
            "description": "NSA Ghidra headless decompiler, script analyzer, AST recovery and cross-architecture symbol decompiler for .so, ELF, Mach-O and PE",
            "tools": [
                "decompile_with_ghidra",
                "ghidra_headless_analyze",
                "ghidra_export_symbols",
                "inspect_binary_ast"
            ]
        }
    ]);

    Json(serde_json::json!({
        "success": true,
        "mode": "on_demand",
        "groups": groups
    }))
}

#[derive(Debug, Deserialize)]
pub struct GhidraDecompileRequest {
    pub binary_path: String,
    pub function_name: Option<String>,
    pub output_dir: Option<String>,
    pub timeout_seconds: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct GhidraDecompileResponse {
    pub success: bool,
    pub binary_path: String,
    pub decompiler: String,
    pub decompiled_code: Option<String>,
    pub symbols: Vec<String>,
    pub message: String,
}

pub async fn rea_ghidra_decompile_handler(
    Json(payload): Json<GhidraDecompileRequest>,
) -> Json<GhidraDecompileResponse> {
    let termux_root = "/data/data/com.termux/files";
    let ubuntu_dir = format!("{}/usr/var/lib/proot-distro/installed-rootfs/ubuntu", termux_root);
    let headless_path = format!("{}/root/tools/ghidra/support/analyzeHeadless", ubuntu_dir);
    let is_installed = Path::new(&headless_path).exists()
        || Path::new(&format!("{}/usr/local/bin/ghidra-decompiler", ubuntu_dir)).exists()
        || Path::new(&format!("{}/usr/bin/ghidra", termux_root)).exists();

    if !is_installed {
        return Json(GhidraDecompileResponse {
            success: false,
            binary_path: payload.binary_path,
            decompiler: "NSA Ghidra Headless".to_string(),
            decompiled_code: None,
            symbols: vec![],
            message: "NSA Ghidra headless analyzer is not installed. Install it via Settings → REA → NSA Ghidra Decompiler.".to_string(),
        });
    }

    let timeout_secs = payload.timeout_seconds.unwrap_or(60);
    let mut args = vec![
        "login".to_string(),
        "ubuntu".to_string(),
        "--".to_string(),
        "/root/tools/ghidra/support/analyzeHeadless".to_string(),
        "/tmp".to_string(),
        "tmp_ghidra_proj".to_string(),
        "-import".to_string(),
        payload.binary_path.clone(),
    ];

    if let Some(ref func) = payload.function_name {
        args.push("-postScript".to_string());
        args.push(format!("DecompileFunction.java {}", func));
    }

    if let Some(ref dir) = payload.output_dir {
        args.push("-scriptPath".to_string());
        args.push(dir.clone());
    }

    let result = tokio::time::timeout(
        Duration::from_secs(timeout_secs),
        tokio::process::Command::new("proot-distro")
            .args(&args)
            .output(),
    ).await;

    match result {
        Ok(Ok(output)) => {
            let stdout = String::from_utf8_lossy(&output.stdout).to_string();
            let stderr = String::from_utf8_lossy(&output.stderr).to_string();
            let success = output.status.success();
            let message = if success {
                "Decompilation completed successfully".to_string()
            } else {
                format!("Ghidra execution returned code {}: {}", output.status.code().unwrap_or(-1), stderr)
            };
            Json(GhidraDecompileResponse {
                success,
                binary_path: payload.binary_path,
                decompiler: "NSA Ghidra Headless".to_string(),
                decompiled_code: Some(stdout),
                symbols: vec![],
                message,
            })
        }
        Ok(Err(e)) => Json(GhidraDecompileResponse {
            success: false,
            binary_path: payload.binary_path,
            decompiler: "NSA Ghidra Headless".to_string(),
            decompiled_code: None,
            symbols: vec![],
            message: format!("Failed to invoke Ghidra process: {}", e),
        }),
        Err(_) => Json(GhidraDecompileResponse {
            success: false,
            binary_path: payload.binary_path,
            decompiler: "NSA Ghidra Headless".to_string(),
            decompiled_code: None,
            symbols: vec![],
            message: format!("Ghidra decompilation timed out after {} seconds", timeout_secs),
        }),
    }
}

pub async fn rea_run_handler(
    Json(payload): Json<ReaRunRequest>,
) -> Json<ReaRunResponse> {
    let container = payload.container.unwrap_or(false);
    let timeout_secs = payload.timeout_seconds.unwrap_or(30);

    let (program, args) = if container {
        let mut full_args = vec!["login".to_string(), "ubuntu".to_string(), "--".to_string(), payload.command];
        if let Some(user_args) = payload.args {
            full_args.extend(user_args);
        }
        ("proot-distro".to_string(), full_args)
    } else {
        (payload.command, payload.args.unwrap_or_default())
    };

    let result = tokio::time::timeout(
        Duration::from_secs(timeout_secs),
        tokio::process::Command::new(&program)
            .args(&args)
            .output(),
    ).await;

    match result {
        Ok(Ok(output)) => {
            let stdout = String::from_utf8_lossy(&output.stdout).to_string();
            let stderr = String::from_utf8_lossy(&output.stderr).to_string();
            let exit_code = output.status.code().unwrap_or(-1);
            Json(ReaRunResponse {
                success: output.status.success(),
                exit_code,
                stdout,
                stderr,
            })
        }
        Ok(Err(e)) => {
            Json(ReaRunResponse {
                success: false,
                exit_code: -1,
                stdout: String::new(),
                stderr: format!("Failed to execute process: {}", e),
            })
        }
        Err(_) => {
            Json(ReaRunResponse {
                success: false,
                exit_code: -1,
                stdout: String::new(),
                stderr: format!("Command timed out after {} seconds", timeout_secs),
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_rea_status() {
        let res = rea_status_handler().await;
        assert!(res.0.success);
        assert!(res.0.components.contains_key("container"));
        assert!(res.0.components.contains_key("launcher"));
        assert!(res.0.components.contains_key("ghidra"));
    }

    #[tokio::test]
    async fn test_rea_tools() {
        let res = rea_tools_handler().await;
        assert_eq!(res.0.get("success").and_then(|v| v.as_bool()), Some(true));
        assert_eq!(res.0.get("mode").and_then(|v| v.as_str()), Some("on_demand"));
        let groups = res.0.get("groups").and_then(|v| v.as_array()).expect("groups array");
        assert!(groups.iter().any(|g| g.get("id").and_then(|id| id.as_str()) == Some("ghidra")));
    }

    #[tokio::test]
    async fn test_rea_run_local() {
        let req = ReaRunRequest {
            command: "echo".to_string(),
            args: Some(vec!["hello_rea".to_string()]),
            container: Some(false),
            timeout_seconds: Some(5),
        };
        let res = rea_run_handler(Json(req)).await;
        assert!(res.0.success);
        assert_eq!(res.0.exit_code, 0);
        assert!(res.0.stdout.contains("hello_rea"));
    }

    #[tokio::test]
    async fn test_rea_ghidra_decompile_fallback() {
        let req = GhidraDecompileRequest {
            binary_path: "/system/bin/sh".to_string(),
            function_name: Some("main".to_string()),
            output_dir: None,
            timeout_seconds: Some(5),
        };
        let res = rea_ghidra_decompile_handler(Json(req)).await;
        assert_eq!(res.0.binary_path, "/system/bin/sh");
        assert_eq!(res.0.decompiler, "NSA Ghidra Headless");
    }
}
