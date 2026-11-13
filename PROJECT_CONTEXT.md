# AntiBot Project Context & Memory

> **IMPORTANT FOR AI AGENTS**: Read this file first when starting a new session or conversation in this repository. It provides complete continuity of architectural decisions, completed migrations, active configuration, and guidelines.

---

## 1. Project Overview & Tech Stack

**AntiBot** is an advanced AI coding assistant and IDE application built for Android and Termux environments.
- **Current Version**: `v2.4.107` (Version Code: `227`)
- **Package ID**: `com.antibot` (Termux flavor)
- **Repository**: `https://github.com/onebotyt/Antibot`
- **Primary Branch**: `main`

### Architecture Layers
1. **Frontend / UI**: Jetpack Compose, Material 3, dynamic high-tech themes (Neon Cyberpunk, Midnight Sapphire, Matrix Emerald, Cosmic Amethyst, OLED Pure Black), multi-CLI top corner dropdown selector, drawer filters, and task flight recorder.
2. **Kotlin Data / Domain Layer**: Coroutine Flows, DataStore / SharedPreferences persistence, OkHttp 4/5 Kotlin networking, SQLite/Room chat databases.
3. **High-Performance Rust Daemon (`agy_ide_bridge_rs/`)**:
   - Packaged as `app/src/main/assets/bin/agy_ide_bridge.xz`.
   - Axum-based HTTP & SSE daemon running locally on `127.0.0.1:45451`.
   - Memory-mapped transcript parsing, intent classification, archive compression, model routing, and traffic inspection.
4. **Native C/Rust JNI Layer (`antibot_native/`)**:
   - `libantibot_native.so`: Hardware-accelerated cryptographic primitives (SHA-256), process pty utilities.
5. **Termux Environment**: Local package management, shell execution, PTY supervisor.

---

## 2. Core Agent Rules & Constraints

Follow these rules strictly across all development tasks:
- **Package Manager**: Use `pnpm` by default for Node.js projects (do not use `npm` unless requested).
- **Communication First**: Always answer user questions, explain designs, and address feedback directly before making edits. Wait for user confirmation when proposing substantial refactors.
- **Termux Environment**: Never install system packages (`pkg`/`apt`) automatically without explicit user permission.
- **Build Rules**:
  - Never attempt to compile or assemble Standard Builds — **always use `termuxBuild`** / `assembleTermuxRelease`.
  - Do not run local release builds or gradle installs unless instructed. Release builds are automated via GitHub Actions CI.
- **Git & Committer Attribution**:
  - Author identity for all commits and CI sync actions must always be:
    `onebotyt <gdhami115@gmail.com>`.
  - **NEVER** attribute commits to `github-actions[bot]`.

---

## 3. Multi-CLI Agent Support & Top Bar Selector

### Features & Implementation
AntiBot supports seamless execution and routing across multiple CLI agents:
1. **Antigravity (AGY)**: Full cloud-native agentic pair programmer.
2. **Local (0 Tokens / Offline)**: Offline heuristics & local model engine for 0-token dev server, shell commands, and file edits.
3. **Claude Code**: Terminal CLI agent integration.
4. **OpenCode**: Open-source coding agent.
5. **Codex**: OpenAI coding engine.

### Key Components
- **Persistence (`AgentPreferences.kt`)**: Selected agents are saved to persistent storage (`enabled_agents` set) and restored cleanly across app restarts.
- **Top Corner Dropdown Selector (`ChatTopBar.kt` & `AgentTopDropdown.kt`)**:
  - Replaced legacy pill switch on home screen with an anchor dropdown in the top-right corner.
  - Allows instant one-tap switching between active CLI engines.
- **Chat Drawer Filters (`ChatHistoryDrawer.kt`)**:
  - Dynamic filter chips in the history drawer (`All`, `Antigravity`, `Local`, `Claude Code`, `OpenCode`, `Codex`).
  - Uses explicit typing: `buildList<Pair<AgentKind?, String>>`.

### DeepSeek Harness App Shell (Default Launch View)
- The app opens into `AppViewMode.HARNESS` (full-screen WebView of the DeepSeek Harness UI) instead of the chat screen.
- `HarnessController` (`data/agent/dsh/HarnessController.kt`) probes `http://127.0.0.1:<harnessPort>/`; it reuses a harness already answering there, otherwise starts the app's own `npx -y @deepseek-ai/dsh@next web --host 127.0.0.1 --port 4011 --no-open` through a persistent `LocalPtySession`, logs to `filesDir/home/.dsh-harness.log`, and parses the one-time `?token=...` URL the harness prints.
- `ui/harness/HarnessScreen.kt` renders the WebView and injects a theme-matched floating settings button (`#antibot-settings-fab`, re-injected by a MutationObserver) backed by the `AntiBotBridge` `@JavascriptInterface`. Tapping it switches to CHAT and opens `SettingsDialog` (`SettingsSection.MAIN`); closing the dialog returns to the harness.
- Harness port/command are configurable in Settings → DeepSeek → "Harness UI Shell" (`DshPreferences.harnessPort` / `harnessCommand`; defaults `4011` / `npx -y @deepseek-ai/dsh@next web`). Termux's own `dsh-web` stays on `4010`, so the app never reuses a harness it cannot authenticate.
- `DshPreferences.harnessUrl` points the shell at a harness that is already running (paste the `dsh web: http://127.0.0.1:4010/?token=...` URL); when set, the app shows that harness instead of starting one.
- The DeepSeek Harness ships native addons that only exist for glibc/Linux (`node-addon-require-builtin` has no android-arm64 build), so it cannot run inside the app's native Android shell; the app's own copy therefore needs a glibc (proot) environment. `HarnessController.diagnose` explains this on the error screen and shows the launcher's log tail.
- The floating switcher has a `harness` item (on by default) to return to the harness from any view. All pre-existing UI elements are unchanged; the harness is additive.

---

## 4. Personal Assistant (Formerly Jarvis) System

### User Configuration & Renaming
- Renamed setting category from "Jarvis" to **"Personal Assistant"**.
- Inside Settings, users can assign a custom name to their personal assistant.
- In-chat voice interaction and call system support live duplex audio communication with local and cloud backends.

---

## 5. Rust Migration Catalog (Ported Endpoints)

The following critical performance bottlenecks have been migrated from Kotlin into the Rust bridge daemon (`agy_ide_bridge_rs/`):

| # | Feature / Service | Rust Bridge Endpoint | Kotlin Caller | Description |
|---|---|---|---|---|
| 1 | **ContextCompactor** | `POST /api/conversations/compact` & `/rollover-seed` | `ContextCompactor.kt` | Zero-allocation history windowing, prompt building, and rollover seed generation. |
| 2 | **SHA-256 Crypto** | `POST /api/file/save` & `POST /api/file/patch` | `IdeApiClient.kt` | Direct SHA-256 verification via Rust crypto engine on file operations. |
| 3 | **TrajectoryParser** | `POST /api/conversations/parse-trajectory` | `TrajectoryParser.kt` | Instant parsing of JSON step trajectories into typed `ChatMessage` / `ToolCall` domain objects. |
| 4 | **NetworkInspectorManager** | `POST /api/network/record` & `GET /api/network/stats` | `NetworkInspectorManager.kt` | Native bandwidth (upload/download bps), connection tracking, and circular log buffer. |
| 5 | **Archive Engine** | `POST /api/backup/*` & `POST /api/restore/*` | `IdeApiClient.kt` | In-daemon tar/gz/zip chat and project workspace backup and restoration. |
| 6 | **Transcript Metadata Engine** | `GET /api/conversations/:id/meta` | `IdeApiClient.kt` | High-speed memory-mapped extraction of conversation title, total steps, running status, first prompt, and last active role. |

*(Note: Legacy Local Model intelligence and Smart Manager auto-routing were completely decommissioned and removed to maintain direct, 100% authentic CLI agent execution: Antigravity, Claude Code, OpenAI Codex, OpenCode).*

---

## 6. GitHub Actions CI/CD & Build System

### Workflows
1. **`.github/workflows/build-apk.yml`**:
   - Triggers on push to `main` / `master` and `workflow_dispatch`.
   - Steps: Setup Java 21, auto-increment version code/name in `version.json`, ensure keystore (`app/release.jks`), unpack `agy_ide_bridge.xz`, execute `./scripts/build_all_apks.sh --release`, upload artifacts, and publish GitHub Release.
   - **Git Committer**: Configured to `onebotyt <gdhami115@gmail.com>` to keep `github-actions[bot]` out of repository contributors.
2. **`.github/workflows/release.yml`**:
   - Builds and publishes tag releases from `version.json`.

### OkHttp Kotlin Idioms (Strict Compilation)
When updating OkHttp network calls, always adhere to OkHttp 4/5 Kotlin syntax:
- `import okhttp3.MediaType.Companion.toMediaType`
- `"application/json".toMediaType()` (DO NOT use `MediaType.parse()`)
- `import okhttp3.RequestBody.Companion.toRequestBody`
- `payload.toRequestBody("application/json".toMediaType())`
- `response.body?.string()` (DO NOT use method call `response.body()`)

---

## 7. Next Steps & Ongoing Backlog

When resuming in a new conversation:
1. **Rust Migration Tier 2 / 3**:
   - Migrate remaining `LocalEnvironmentManager.kt` archive decompression steps (`apache-commons-compress` → Rust `archive.rs`).
   - Port deep heuristic execution handlers from `LocalIntelligenceEngine.kt` into Rust supervisor.
2. **Personal Assistant Voice UX**:
   - Validate live audio streaming buffer latency on low-end Android devices.
   - Expand customizable prompt presets for the renamed Personal Assistant.
3. **Verification**:
   - Ensure `./scripts/build_all_apks.sh --release` continues to build green on GitHub Actions CI.
