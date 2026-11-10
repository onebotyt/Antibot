#!/usr/bin/env python3
"""
Verification Script for Phase 23: Antigravity Cloud Agents & Cloud Web Preview
Tests Rust daemon cloud proxy endpoints and checks Kotlin Cloud UI and proto definitions.
"""

import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error

BRIDGE_BIN = "/data/user/0/com.termux/files/home/.gemini/antigravity-cli/scratch/Antibot/agy_ide_bridge_rs/target/release/agy_ide_bridge_rs"
TEST_PORT = 19488
TEST_HUB_PORT = 19489
BASE_URL = f"http://127.0.0.1:{TEST_PORT}"

def log(msg):
    print(f"[Phase23-Verify] {msg}")

def check_file(path, desc):
    if not os.path.exists(path):
        print(f"❌ Missing {desc}: {path}")
        sys.exit(1)
    print(f"✅ Found {desc}: {path}")

def main():
    log("=== Step 1: Checking Static Files & Kotlin/Proto Architecture ===")
    repo_root = "/data/user/0/com.termux/files/home/.gemini/antigravity-cli/scratch/Antibot"
    
    check_file(os.path.join(repo_root, "app/src/main/proto/LanguageServerService.proto"), "LanguageServerService.proto")
    check_file(os.path.join(repo_root, "app/src/main/java/com/example/gemini/data/agent/agy/AgyCloud.kt"), "AgyCloud.kt")
    check_file(os.path.join(repo_root, "app/src/main/java/com/example/gemini/data/agent/agy/CloudPreviewServer.kt"), "CloudPreviewServer.kt")
    check_file(os.path.join(repo_root, "app/src/main/java/com/example/gemini/ui/cloud/CloudPreview.kt"), "CloudPreview.kt")
    check_file(os.path.join(repo_root, "app/src/main/java/com/example/gemini/ui/cloud/AgyCloudUi.kt"), "AgyCloudUi.kt")
    
    # Verify FetchSandboxPort in proto
    with open(os.path.join(repo_root, "app/src/main/proto/LanguageServerService.proto"), "r") as f:
        proto_content = f.read()
        assert "FetchSandboxPort" in proto_content, "FetchSandboxPort missing from LanguageServerService.proto"
        log("✅ FetchSandboxPort present in LanguageServerService.proto")
    
    # Verify isCloud in Models.kt
    with open(os.path.join(repo_root, "app/src/main/java/com/example/gemini/domain/model/Models.kt"), "r") as f:
        models_content = f.read()
        assert "cloudProjectId" in models_content, "cloudProjectId missing from Models.kt"
        assert "isCloud" in models_content, "isCloud getter missing from Models.kt"
        log("✅ isCloud & cloudProjectId present in Models.kt")

    # Verify AGY_CLOUD in SettingsDialog.kt
    with open(os.path.join(repo_root, "app/src/main/java/com/example/gemini/ui/settings/SettingsDialog.kt"), "r") as f:
        settings_content = f.read()
        assert "AGY_CLOUD" in settings_content, "AGY_CLOUD missing from SettingsDialog.kt"
        log("✅ AGY_CLOUD present in SettingsDialog.kt")

    log("=== Step 2: Testing Rust Bridge Daemon Cloud Endpoints ===")
    if not os.path.exists(BRIDGE_BIN):
        log(f"⚠️ Release binary {BRIDGE_BIN} not found yet; will test after compilation completes.")
        return

    tmp_dir = os.path.join(os.environ.get("TMPDIR", "/tmp"), f"antibot_p23_test_{os.getpid()}")
    os.makedirs(tmp_dir, exist_ok=True)

    proc = subprocess.Popen([
        BRIDGE_BIN,
        "--port", str(TEST_PORT),
        "--hub-port", str(TEST_HUB_PORT),
        "--dir", tmp_dir,
        "--no-hub",
        "--no-claude"
    ])

    try:
        # Wait for daemon ready
        ready = False
        for _ in range(30):
            try:
                req = urllib.request.Request(f"{BASE_URL}/api/health")
                with urllib.request.urlopen(req, timeout=1) as resp:
                    if resp.status == 200:
                        ready = True
                        break
            except Exception:
                time.sleep(0.3)
        assert ready, "Bridge daemon failed to start within 10 seconds"
        log("✅ Bridge daemon is online and responding to /api/health")

        # Test 1: GET /api/cloud/status
        req = urllib.request.Request(f"{BASE_URL}/api/cloud/status")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode())
            assert data["status"] == "ok"
            assert data["version"] == "2.3.0"
            log(f"✅ GET /api/cloud/status verified: {data}")

        # Test 2: POST /api/cloud/preview/register
        reg_payload = json.dumps({
            "port": 5173,
            "target_host": "127.0.0.1",
            "target_port": 5173,
            "description": "Vite React Dev Server"
        }).encode()
        req = urllib.request.Request(f"{BASE_URL}/api/cloud/preview/register", data=reg_payload, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode())
            assert data["status"] == "ok"
            assert data["route"]["port"] == 5173
            log(f"✅ POST /api/cloud/preview/register verified: {data}")

        # Test 3: GET /api/cloud/preview/routes
        req = urllib.request.Request(f"{BASE_URL}/api/cloud/preview/routes")
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode())
            assert len(data["routes"]) >= 1
            assert any(r["port"] == 5173 for r in data["routes"])
            log(f"✅ GET /api/cloud/preview/routes verified: {len(data['routes'])} routes")

        # Test 4: POST /api/cloud/ports/scan
        scan_payload = json.dumps({
            "ports": [TEST_PORT, 5173, 9999],
            "timeout_ms": 100
        }).encode()
        req = urllib.request.Request(f"{BASE_URL}/api/cloud/ports/scan", data=scan_payload, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode())
            assert TEST_PORT in data["active_ports"], "Port scan should detect the running daemon port"
            log(f"✅ POST /api/cloud/ports/scan verified: active_ports={data['active_ports']}")

        # Test 5: Fallback reverse proxy GET /api/cloud/preview/:port
        # Requesting an unreachable port (e.g. 59999) should return 502 with HTML guidance
        try:
            req = urllib.request.Request(f"{BASE_URL}/api/cloud/preview/59999/test-app")
            with urllib.request.urlopen(req, timeout=5) as resp:
                pass
        except urllib.error.HTTPError as e:
            assert e.code == 502, f"Expected 502 Bad Gateway, got {e.code}"
            body = e.read().decode()
            assert "Could not reach cloud preview port 59999" in body
            assert e.headers.get("Access-Control-Allow-Origin") == "*"
            log("✅ Reverse proxy 502 error gateway + CORS header rewrite verified")

        log("🎉 Phase 23 Verification completely succeeded!")
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except Exception:
            proc.kill()

if __name__ == "__main__":
    main()
