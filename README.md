# The Integration of Information（Trae Info）

跨平台（Android / Desktop）**信息聚合平台**：云端服务以「模拟正常用户行为」为主采集多平台信息，经清洗、去重、排序、AI 摘要与翻译后，通过 REST API 下发客户端信息流；用户用自然语言描述偏好，系统解析为关键词与展示模式实现个性化排序。

> 本仓库采用 **SDD（Spec-Driven Development，规范驱动开发）**：长期约束沉淀在项目宪法，逐功能契约沉淀在功能规范，历史快照归档留痕。入口见 [the integration of information.project/](the%20integration%20of%20information.project/README.md)。

## 核心设计原则

1. **采集不依赖 API Key** —— 优先模拟真实用户行为（浏览器 + 登录态），直连可用时才走 HTTP
2. **代理在云端** —— 3xui 订阅由服务端自动解析 / 测速 / 择优，客户端零配置
3. **成本导向** —— 全链路分级降档（摘要三档、视频三档、视觉模型六级降级链），密集计算由显式请求触发
4. **模块化分层** —— `core` / `server` / `client-app` 三模块，DTO 默认值保证前后兼容
5. **数据安全优先** —— 凭据不入库、不进命令行、不进日志

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│  client-app（Compose Multiplatform：Android / Desktop）        │
│  首页 · 订阅定制 · 信息流 · 信息偏好 · 更多                     │
└───────────────────────────┬──────────────────────────────────┘
                            │ REST /api（云端代理零配置）
┌───────────────────────────▼──────────────────────────────────┐
│  server（Ktor）                                                │
│  ┌─ 采集：三通道 ────────────────────────────────────────┐     │
│  │ A 云端浏览器（Playwright + 登录态，主力）              │     │
│  │ B 伴侣脚本（浏览器端上报 /api/companion/ingest）        │     │
│  │ C HTTP 适配器（公开 API 直连）                          │     │
│  └───────────────────────────────────────────────────────┘     │
│  ┌─ 处理：清洗去重 → 偏好排序 → AI 摘要/翻译 → 视频分析 ─┐     │
│  └───────────────────────────────────────────────────────┘     │
│  代理穿透（xray）· 会话与登录态 · 预算与熔断                     │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│  core（KMP 共享）：模型 · DTO · 类目与平台 taxonomy              │
└──────────────────────────────────────────────────────────────┘

外围：mcp/（MCP 工具封装）· agent/（MCP 宿主应用）· extension/（采集伴侣油猴脚本）
```

## 模块结构

| 模块 | 说明 |
|---|---|
| `core/` | Kotlin Multiplatform 共享层：`ContentItem`、`Taxonomy`（平台与 32 类目）、REST DTO |
| `server/` | Ktor 服务端：采集适配器、浏览器自动化、代理池、AI 摘要与翻译、视频分析管线、决策核心 V2、REST 路由 |
| `client-app/` | Compose Multiplatform 客户端（Android + Desktop） |
| `mcp/` | `trae-video-mcp`：把服务端视频分析 REST 封装为 MCP 工具（stdio） |
| `agent/` | `trae-video-agent`：MCP 宿主应用，产出可引用的视频证据报告 |
| `extension/` | 采集伴侣油猴脚本（通道 B 客户端实现） |
| `scripts/` | 一致性检查与服务器部署脚本 |
| `docs/` | 全体系工程文档与使用指南 |
| `the integration of information.project/` | **SDD 归档根**：宪法 + 功能规范 + 历史快照 |
| `backup/` | 工程文档离线备份与还原说明 |

## 关键能力

- **三通道采集**：HTTP 适配器 / 云端浏览器 / 伴侣脚本，含降级链与熔断（合法空结果不计失败）
- **平台与类目**：学术、科技、人文、国际时事等类目体系，20+ 来源
- **代理穿透**：订阅自动解析、节点测速择优，海外流量经云端代理
- **浏览器自动化**：登录态保持、人机行为模拟、反检测
- **AI 能力**：三档摘要、视觉模型六级降级链、外文标题与概要翻译（并发去重、内容键缓存）
- **视频分析**：下载 → 字幕优先转录（ASR 兜底）→ 关键帧 → OCR / 视觉描述 → 三源融合总结；三档成本分级
- **决策核心 V2**：统一准入 → 身份去重 → 排序，含灰度开关与来源收益观测
- **个性化偏好**：自然语言偏好解析为关键词与展示模式

## 构建

### 服务端与客户端（Kotlin / Gradle）

```bash
# JDK 17（必需）
export JAVA_HOME=<jdk-17 路径>          # Windows: $env:JAVA_HOME="<jdk-17 路径>"

# 服务端发行版
gradle :server:distZip --no-daemon -x test

# Android 客户端
gradle :client-app:assembleDebug --no-daemon -x test
# 产物：client-app/build/outputs/apk/debug/client-app-debug.apk
```

Android 构建需在项目根 `local.properties` 指定 SDK 路径：

```properties
sdk.dir=<Android SDK 绝对路径>
```

> 详细工具链（JDK / Gradle / Android SDK / adb / 模拟器）见 [docs/android-build-env.md](docs/android-build-env.md)。

### MCP 与 Agent（Node.js ≥ 18）

```bash
cd mcp   && npm install && npm run build     # trae-video-mcp
cd agent && npm install && npm run build     # trae-video-agent
```

服务端地址经环境变量注入（默认 `http://127.0.0.1:8765`）：

```bash
TRAE_VIDEO_SERVER=http://<服务端地址>:8765 node mcp/dist/index.js
```

> 接入 Claude Code / Cursor 的完整配置见 [docs/mcp-usage-guide.md](docs/mcp-usage-guide.md)；Agent 应用用法见 [docs/video-agent-usage.md](docs/video-agent-usage.md)。

### 服务端部署

部署流程与铁律记录在技能 `.trae/skills/trae-server-deploy/SKILL.md`。**部署目标地址、SSH 用户与端口在本公开仓库中以占位符脱敏**，实际取值请填入本地配置：

| 占位符 | 含义 |
|---|---|
| `<VPS_IP>` | 服务器公网地址 |
| `<SSH_USER>` | SSH 登录用户 |
| `<SSH_PORT>` / `<API_PORT>` | SSH 端口 / 应用 API 端口（应用默认 8765） |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/PROJECT_SYSTEM.md](docs/PROJECT_SYSTEM.md) | **全体系工程文档**（现状权威源）：架构、能力、API、约定、演进路线、已知限制 |
| [the integration of information.project/](the%20integration%20of%20information.project/README.md) | SDD 层级：宪法（目标 / 技术栈 / 路线图）+ F01–F12 功能规范 + 归档 |
| [docs/android-build-env.md](docs/android-build-env.md) | Android 构建工具链与模拟器验证 |
| [docs/mcp-usage-guide.md](docs/mcp-usage-guide.md) | MCP 工具链接与全套使用方法 |
| [docs/video-agent-usage.md](docs/video-agent-usage.md) | Agent 版视频处理应用架构与使用 |
| [docs/video-agent-watch.md](docs/video-agent-watch.md) · [docs/video-mcp-tool.md](docs/video-mcp-tool.md) · [docs/video-translation-editing.md](docs/video-translation-editing.md) | 视频能力三方向设计（证据包 / MCP 工具 / 翻译剪辑） |
| [backup/BACKUP-RESTORE.md](backup/BACKUP-RESTORE.md) | 工程文档备份与还原方法 |
| [docs/vps-connection.md](docs/vps-connection.md) | 服务器连接信息（已脱敏，作为配置模板） |

## 安全约定

- 凭据（API Key、服务器密码、SSH 私钥）**不入库、不进命令行参数、不进日志**
- `local.properties`、`.env`、`server/data/`、构建产物与本地配置均由 `.gitignore` 排除
- 抓取遵循「模拟正常用户行为」，不绕过付费墙与鉴权；登录 / DRM / 区域限制内容明确报错

## 许可

各子项目按自身声明授权（`mcp/`、`agent/` 为 MIT）。整仓授权以项目所有者声明为准。
