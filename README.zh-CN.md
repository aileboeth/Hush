# Hush（轻息）

[English](README.md) · **简体中文**

面向 Android 12–16 的轻量、免 Root、单 APK 后台与内存治理工具。

Hush 通过 Android 本机「无线调试」一次性自举启动 `uid=2000`（`shell`）守护进程——无需 Root、无需电脑、不依赖外部 Shizuku、无需无障碍权限，激活后划掉应用界面即可零常驻后台运行。

## 核心特性

- **单 APK 本机自举**：内置 mDNS 服务发现、TLS 1.3 客户端与纯 Kotlin BoringSSL 兼容 SPAKE25519 无线调试配对协议（同时提供电脑 USB 一行兜底命令）。守护进程拉起后即可立即关闭「无线调试」。
- **零常驻前端**：策略引擎独立运行在脱离终端的 `app_process64`（`uid=2000`）进程中，关闭 Hush 页面后前端 0 内存占用，无需常驻通知栏。
- **三档精准治理**：
  - **强杀档（`FORCE_STOP`）**：针对短视频、购物、阅读等无强推送需求的内存大户，在后台超时、息屏或内存高压时彻底停止，连根释放物理内存与 ZRAM/Swap。
  - **保消息瘦身档（`SOFT_TRIM`）**：专为微信、QQ 等通讯软件设计，绝不强杀、绝不锁后台权限，仅温和释放界面/小程序/WebView 缓存并避开 `:push` 推送进程，保障消息畅通。
  - **常驻保护档（`PROTECTED`）**：所有自动策略永久跳过，叠加系统核心进程硬编码保护网。
- **精准音频免杀**：结合音频播放状态与进程非冻结态校验，后台听歌、听书、播客自动跳过不被误杀。
- **完全可逆限制**：所有 `appops`（禁止后台 / 限制唤醒锁）与受限待机桶调整均记入台账，支持随时一键全部还原。
- **极简流畅交互**：浅色/深色 Apple 极简视觉，内置横向磁吸内存趋势曲线与滚动数字计数器、3D 触控倾斜流光主卡、胶囊流体变形激活面板、卡片到详情页共享元素转场与速度吸附橡皮筋记录抽屉。

## 安装

在 [Releases](https://github.com/aileboeth/Hush/releases) 下载 `Hush-<版本>.apk` 安装（要求 Android 12 / API 31 及以上）。

## 快速开始（无线激活）

1. 连接 **Wi-Fi**，进入系统 **设置 → 开发者选项**，确保最顶部的 **「开发者选项」总开关** 与 **「无线调试」** 均已开启。
2. 点击 **「无线调试」左侧文字**（不要只点右侧开关）进入子页面，点 **「使用配对码配对设备」**，并保持配对码弹窗不要关闭。
3. 在 Hush 右上角点状态胶囊打开 **无线激活**：
   - **分屏 / 小窗直接填（推荐）**：直接填入弹窗中的 6 位配对码与 IP 冒号后的 5 位配对端口。
   - **通知栏下拉回复**：发送配对通知后下拉通知中心回复（vivo OriginOS 请从屏幕**左上角**下拉通知中心）。
4. 点 **一键启动守护进程**。就绪后即可安全关闭「无线调试」，只要手机不重启均保持有效。

## 隐私与安全

- **100% 本地运行**：无任何公网请求、无统计埋点、无外部服务器。
- **环回加密鉴权**：`uid=2000` 守护进程仅监听 `127.0.0.1` 随机端口，采用 256-bit 一次性随机会话令牌与常量时间校验；令牌通过 `0600` 临时标准输入传入，绝不暴露在 `/proc/<pid>/cmdline`。
- **硬编码安全护栏**：关键系统进程与输入法/桌面组件受不可绕过保护，防止误操作导致系统异常。

## 模块结构

```text
core-contract/   冻结契约接口、状态模型、零依赖 MiniJson、RPC 协议与 SafetyGuardrails
daemon-shell/    uid=2000 守护进程（DaemonMain、DaemonRpcServer、PolicyEngine、AndroidSystemBridge）
adb-bootstrap/   纯 Kotlin 无线调试自举器（mDNS、TLS 1.3、SPAKE25519 配对、无 PTY 脱离启动）
core-engine/     环回 RPC 客户端、本地配置存储、Real / Fake HushGraph
app/             Jetpack Compose (Material 3) 前端界面
```

## 从源码构建

需要 JDK 17 与 Android SDK 35（无需 NDK）。

```powershell
# 运行全部单元测试（契约、SPAKE25519 加密、守护进程策略与 RPC）
.\gradlew.bat testDebugUnitTest

# 构建 Debug APK
.\gradlew.bat :app:assembleDebug

# 构建内置模拟数据的离线演示 APK
.\gradlew.bat :app:assembleDebug -Phush.fake=true
```

## 开源协议

[MIT](LICENSE)
