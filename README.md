# Hush

**English** · [简体中文](README.zh-CN.md)

A lightweight, non-root, single-APK background and memory governor for Android 12–16.

Hush bootstraps its own local `uid=2000` (`shell`) daemon over Android Wireless Debugging — no root, no PC required, no external Shizuku dependency, no accessibility service, and zero resident foreground app after activation.

## Highlights

- **Single-APK self-bootstrap**: Built-in mDNS discovery, TLS 1.3 client, and pure-Kotlin BoringSSL-compatible SPAKE25519 wireless ADB pairing (plus a copy-paste USB `adb shell` fallback). Once the daemon starts, you can turn Wireless Debugging off immediately.
- **Zero-overhead detached daemon**: Policy scheduling runs directly inside a detached `app_process64` (`uid=2000`) process. Swiping the Hush UI away leaves the daemon running with zero foreground notification and zero UI memory footprint.
- **Three-tier app governance**:
  - **Force Stop (`FORCE_STOP`)**: Fully stops heavy apps (short video, shopping, readers, etc.) on background timeout, screen-off, or memory pressure to reclaim both RAM and Swap/ZRAM.
  - **Push-Safe Soft Trim (`SOFT_TRIM`)**: Built for messaging apps (WeChat, QQ, etc.). Never force-stopped and never `appops`-restricted — only trims cached UI/WebView/mini-program sub-processes while keeping `:push` channels alive.
  - **Protected (`PROTECTED`)**: Permanently skipped by all automated policies, reinforced by hard-coded system-process guardrails.
- **Accurate audio exemption**: Skips active music, audiobook, and podcast playback by cross-checking active audio playback state and non-frozen process status.
- **Fully reversible constraints**: Every `appops` (`RUN_IN_BACKGROUND`, `WAKE_LOCK`) and standby-bucket change is tracked in a ledger and can be reverted in one tap.
- **Minimalist fluid interface**: Clean light/dark UI featuring an interactive scrubbing memory trend chart with odometer readout, tactile card interactions, shared-element app detail transitions, fluid capsule-to-panel activation flow, and a velocity-snapping activity sheet.

## Install

Download `Hush-<version>.apk` from [Releases](https://github.com/aileboeth/Hush/releases) and install on Android 12+ (API 31+).

## Quick Start (Wireless Activation)

1. Connect to Wi-Fi and open **Settings → Developer options**. Make sure both the top **Developer options** switch and **Wireless debugging** are enabled.
2. Tap the **Wireless debugging** text (not just the toggle) to enter its sub-page, then tap **Pair device with pairing code** and keep the popup open.
3. In Hush, tap the top-right status capsule to open **Wireless Activation**:
   - **Split-screen / Floating window (recommended)**: Enter the 6-digit code and 5-digit pairing port directly in Hush.
   - **Notification shade reply**: Send the pairing notification and reply from the notification shade.
4. Tap **Launch Daemon**. Once ready, you can safely turn **Wireless debugging** back off — Hush stays active until the phone reboots.

## Privacy & Security

- **100% local**: Hush makes zero outbound internet requests and contains no analytics, telemetry, or remote endpoints.
- **Authenticated loopback IPC**: The `uid=2000` daemon binds strictly to `127.0.0.1` on an ephemeral port and enforces constant-time verification of a 256-bit per-session token passed via mode-`0600` stdin (never exposed in `/proc/<pid>/cmdline`).
- **Hard-coded safety net**: Critical Android and OEM system packages cannot be force-stopped or `appops`-restricted.

## Project Modules

```text
core-contract/   Frozen interfaces, state models, zero-dep MiniJson, RPC protocol, SafetyGuardrails
daemon-shell/    uid=2000 app_process64 daemon (DaemonMain, DaemonRpcServer, PolicyEngine, AndroidSystemBridge)
adb-bootstrap/   Pure-Kotlin mDNS + TLS 1.3 + SPAKE25519 wireless ADB pairing & detached launcher
core-engine/     Loopback RPC client, persisted config store, Real/Fake HushGraph implementations
app/             Jetpack Compose (Material 3) frontend UI
```

## Build from Source

Requires JDK 17 and Android SDK 35 (no NDK required).

```bash
# Run all unit tests (contract, crypto SPAKE25519, daemon policy & RPC)
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew :app:assembleDebug

# Assemble offline preview APK backed by FakeHushGraph
./gradlew :app:assembleDebug -Phush.fake=true
```

## License

[MIT](LICENSE)
