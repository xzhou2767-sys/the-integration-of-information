# 项目宪法 · 技术栈（Project Tech Stack）

> 版本：1.0｜制定日期：2026-10-10｜地位：SDD 项目宪法之一。现状权威源为 `docs/PROJECT_SYSTEM.md` §二/§三，本文为宪法化固化版本。

## 一、主技术栈（Kotlin 多平台单体仓库）

| 组件 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.1.0 | 全部模块主语言 |
| Compose Multiplatform | 1.7.3 | 客户端 UI（Android + Desktop 共享） |
| Ktor | 3.0.3 | 服务端（Netty）+ 客户端 HTTP |
| kotlinx-serialization-json | 1.7.3 | 全链路 JSON |
| Coroutines | 1.9.0 | 全异步（禁止 callback 风格） |
| Playwright (Java) | 1.49.0 | 云端浏览器自动化 |
| AGP | 8.7.3 | Android 构建 |
| Logback | 1.5.12 | 服务端日志 |
| Python | 3.12 | 视频分析管线（ffmpeg / faster-whisper / OCR） |

Android：compileSdk/targetSdk 35，minSdk 26。

## 二、模块架构（三模块）

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | KMP 库（android + jvm） | 跨端共享领域模型与 API DTO |
| `:server` | Kotlin/JVM（application） | Netty 云端服务（:8765）：采集调度、代理穿透、浏览器自动化、AI 能力、全部 REST API |
| `:client-app` | KMP + Compose MP | Android（`com.trae.info.client`）+ Desktop 前端 |

辅助目录：`mcp/`（TypeScript MCP 封装）、`agent/`（TypeScript MCP 宿主应用）、`extension/`（油猴脚本）、`scripts/`（部署/一致性检查）、`server/scripts/` + `scripts/`（Python 视频管线，VPS 双拷贝注意）。

## 三、视频与 Agent 层技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| @modelcontextprotocol/sdk | 1.30.0 | MCP 协议（mcp/ server 与 agent/ client 双向使用） |
| zod | 3.25.76 | 工具输入 schema 校验 |
| TypeScript | 5.9.2 | mcp/ 与 agent/ 编译 |
| Node.js | ≥ 18 | 运行时 |
| faster-whisper / ffmpeg / OCR | — | 服务端视频管线（Python 3.12） |

## 四、基础设施与工具链

| 组件 | 位置/版本 | 说明 |
|---|---|---|
| 腾讯云 VPS | `<VPS_IP>`（ubuntu，sudo 免密） | 生产服务器，3.7G 内存；systemd 单元 `trae-info`；安全组 22/80/8765 |
| 服务器 JDK17 / Gradle | `/usr/lib/jvm/java-17-openjdk-amd64`；`/opt/trae-info/gradle-8.10.2` | distZip 构建链 |
| 本地 JDK17 / Gradle 8.10.2 / Android SDK / adb | `C:\Users\ASUS\AppData\Local\Temp\trae-tc\` | 客户端出包与模拟器（⚠️ Temp 目录，详见 `docs/android-build-env.md`） |
| xray（3xui） | VPS :10810（socks） | 海外流量云端穿透，自动择优 |
| 模拟器 | AVD `emulator-5554`（1080x2340） | uiautomator 坐标法 UI 测试 |

## 五、技术选型铁律（变更需宪法流程）

1. **单一 Gradle 构建**：VPS 3.7G 内存同一时间只允许一个构建（否则整机假死）。
2. **DTO 兼容**：新增字段必须给默认值；客户端版本号同步 +1。
3. **stdout 纪律**：MCP 进程 stdout 只输出协议消息，日志走 stderr。
4. **依赖锁定**：TS 项目按 package-lock.json 锁定版本；对标库版本以实施时锁定为准。
5. **凭据安全**：不进命令行参数/scp 文件/文档/git；读取需用户显式批准。
