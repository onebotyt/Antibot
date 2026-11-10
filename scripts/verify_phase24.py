#!/usr/bin/env python3
import json
import os
import subprocess
import time
import urllib.request
import urllib.error

def test_file_presence():
    print("🔍 Checking Phase 24 Kotlin & Rust files...")
    files = [
        "app/src/main/java/com/example/gemini/data/rea/ReaManager.kt",
        "app/src/main/java/com/example/gemini/data/androiddev/AndroidDevManager.kt",
        "app/src/main/java/com/example/gemini/ui/settings/ReaScreen.kt",
        "app/src/main/java/com/example/gemini/ui/settings/ReaGroupsScreen.kt",
        "app/src/main/java/com/example/gemini/ui/settings/AndroidDevScreen.kt",
        "app/src/main/java/com/example/gemini/data/agent/codex/CodexBridgeClient.kt",
        "app/src/main/java/com/example/gemini/data/agent/codex/CodexChatBackend.kt",
        "app/src/main/java/com/example/gemini/ui/codex/CodexSettingsScreen.kt",
        "app/src/main/java/com/example/gemini/data/agent/opencode/OpenCodeBridgeClient.kt",
        "app/src/main/java/com/example/gemini/data/agent/opencode/OpenCodeChatBackend.kt",
        "app/src/main/java/com/example/gemini/ui/opencode/OpenCodeSettingsScreen.kt",
        "agy_ide_bridge_rs/src/handlers/codex.rs",
        "agy_ide_bridge_rs/src/handlers/opencode.rs",
        "agy_ide_bridge_rs/src/handlers/rea.rs",
        "app/src/main/assets/bin/agy_ide_bridge.xz"
    ]
    for f in files:
        assert os.path.exists(f), f"Missing required file: {f}"
        print(f"  ✓ {f}")
    print("✅ All required Phase 24 files are present!")

def test_live_bridge_daemon():
    print("\n🚀 Testing live pure Rust Bridge Daemon for Phase 24...")
    port = 19876
    binary = "agy_ide_bridge_rs/target/release/agy_ide_bridge_rs"
    assert os.path.exists(binary), f"Missing release binary: {binary}"

    proc = subprocess.Popen(
        [binary, "--port", str(port), "--bind", "127.0.0.1", "--no-hub", "--no-claude"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    try:
        base = f"http://127.0.0.1:{port}"
        # Wait for daemon to listen
        ready = False
        for _ in range(30):
            try:
                with urllib.request.urlopen(f"{base}/api/health", timeout=1) as resp:
                    if resp.status == 200:
                        ready = True
                        break
            except Exception:
                time.sleep(0.2)

        assert ready, "Daemon failed to start on port 19876"
        print("  ✓ Daemon online on port 19876")

        # 1. Test Codex endpoints
        print("  [Codex] Testing /api/codex/status...")
        with urllib.request.urlopen(f"{base}/api/codex/status", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            print("    ✓ /api/codex/status returned success: true")

        print("  [Codex] Testing /api/codex/rpc (ping)...")
        req = urllib.request.Request(
            f"{base}/api/codex/rpc",
            data=json.dumps({"method": "ping"}).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert data["result"] == "pong"
            print("    ✓ /api/codex/rpc responded with pong")

        # 2. Test OpenCode endpoints
        print("  [OpenCode] Testing /api/opencode/status...")
        with urllib.request.urlopen(f"{base}/api/opencode/status", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            print("    ✓ /api/opencode/status returned success: true")

        print("  [OpenCode] Testing /api/opencode/custom-providers CRUD...")
        new_provider = {
            "id": "test-provider",
            "name": "Test Ollama",
            "kind": "ollama",
            "base_url": "http://127.0.0.1:11434",
            "models": [{"id": "llama3.2", "name": "Llama 3.2"}]
        }
        req = urllib.request.Request(
            f"{base}/api/opencode/custom-providers",
            data=json.dumps(new_provider).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert data["provider"]["id"] == "test-provider"
            print("    ✓ /api/opencode/custom-providers created test-provider")

        with urllib.request.urlopen(f"{base}/api/opencode/custom-providers", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert any(p["id"] == "test-provider" for p in data["providers"])
            print("    ✓ /api/opencode/custom-providers listed test-provider")

        print("  [OpenCode] Testing /api/opencode/custom-providers/fetch-models...")
        req = urllib.request.Request(
            f"{base}/api/opencode/custom-providers/fetch-models",
            data=json.dumps({"kind": "openai", "base_url": "https://api.openai.com/v1"}).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert len(data["models"]) > 0
            print(f"    ✓ /api/opencode/custom-providers/fetch-models returned {len(data['models'])} models")

        # 3. Test REA endpoints
        print("  [REA] Testing /api/rea/status...")
        with urllib.request.urlopen(f"{base}/api/rea/status", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert "container" in data["components"]
            assert "launcher" in data["components"]
            print("    ✓ /api/rea/status returned component breakdown")

        print("  [REA] Testing /api/rea/tools...")
        with urllib.request.urlopen(f"{base}/api/rea/tools", timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert data["mode"] == "on_demand"
            assert len(data["groups"]) >= 4
            print(f"    ✓ /api/rea/tools returned {len(data['groups'])} tool groups")

        print("  [REA] Testing /api/rea/run...")
        req = urllib.request.Request(
            f"{base}/api/rea/run",
            data=json.dumps({
                "command": "echo",
                "args": ["antibot_phase24_verified"],
                "container": False
            }).encode(),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode())
            assert data["success"] is True
            assert data["exit_code"] == 0
            assert "antibot_phase24_verified" in data["stdout"]
            print("    ✓ /api/rea/run successfully executed native command")

        print("\n🎉 ALL PHASE 24 VERIFICATION TESTS PASSED!")

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()

if __name__ == "__main__":
    test_file_presence()
    test_live_bridge_daemon()
