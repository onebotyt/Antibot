# <div align="center">🤖 AntiBot</div>

<div align="center">
  <strong>The Next-Generation Agentic AI Coding Workspace, Native Linux Subsystem & Local AI Hub for Android</strong>
</div>

<br/>

<div align="center">

[![Download Latest APK](https://img.shields.io/badge/⬇️%20Download%20Latest%20APK-Latest%20Release-success?style=for-the-badge&logo=android&logoColor=white)](https://github.com/onebotyt/Antibot/releases/latest)

<br/>

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-Jetpack%20Compose-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com/jetpack/compose)
[![gRPC](https://img.shields.io/badge/gRPC-Square%20Wire-244F7D?style=flat-square&logo=grpc&logoColor=white)](https://square.github.io/wire/)
[![GitHub Actions](https://img.shields.io/badge/CI%2FCD-Auto%20APK%20Build-2088FF?style=flat-square&logo=githubactions&logoColor=white)](https://github.com/onebotyt/Antibot/actions)

</div>

---

### 📥 1-Click Direct Download
👉 **[Download antibot.apk (Latest Version)](https://github.com/onebotyt/Antibot/releases/latest/download/antibot.apk)**

* **1-Tap Direct Download**: Clicking the link above immediately downloads `antibot.apk` to your device.
* **Standalone App**: Installs as an independent app (`com.antibot`) side by side with AntiGem without replacing or updating it.
* You can also view all builds on the **[Releases Page](https://github.com/onebotyt/Antibot/releases)**.

---

## 🌟 Overview

**AntiBot** turns your Android device into a complete agentic AI development environment. It pairs a high-performance **Jetpack Compose** UI with a native **Termux Linux subsystem**, an **embedded Go IDE bridge**, and multi-engine coding agents:

- **Antigravity (`agy`)** — Direct binary gRPC connection to the `agy` language server daemon.
- **Local Model Only (0 Tokens / Offline)** — Run commands, inspect projects, launch dev servers, and summarize code without consuming cloud tokens or needing an active internet connection.

---

## ✨ Features

- **0-Token Local Offline Model**: Switch seamlessly to the dedicated local model for offline coding, file browsing, shell commands, and turn recaps with zero API token consumption.
- **Self-Learning Dev Server Runner**: Automatically detects project setups (Node.js, Python, SQL, Rust, Go). Runs cached recipes with 0 tokens or asks once, saves to `.ide/launch.json`, and re-runs locally forever.
- **Clipboard Image Support**: Copy images anywhere on Android and paste them directly into the chat input bar for vision tasks.
- **Modern Color Themes**: Choose between *Neon Cyberpunk*, *Midnight Sapphire*, *Matrix Emerald*, *Cosmic Amethyst*, and battery-saving *OLED Pure Black*.
- **Task Flight Recorder**: Automatic session snapshots (<100MB) with 1-tap state recovery banner if a process gets interrupted.
- **Human-Readable Token Metrics**: Instant visibility into exact token usage (`Tokens In`, `Tokens Out`, `Duration`, `Tokens/sec`).
- **Memory Optimized**: Chunked attachment streaming and removed telemetry bloat for low-memory Android devices.

---

## 📦 Automated APK Builds via GitHub Actions

Every commit pushed to the repository automatically triggers the **Build AntiBot APKs** workflow:

1. Go to the **[Actions tab](https://github.com/onebotyt/Antibot/actions)**.
2. Select the latest workflow run.
3. Download the compiled **`AntiBot-APKs`** artifact containing:
   - `antiBot-termux-v*-debug.apk` (Recommended standalone edition)
   - `antiBot-standard-v*-debug.apk`
