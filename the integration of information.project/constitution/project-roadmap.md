# 项目宪法 · 路线图（Project Roadmap）

> 版本：1.0｜制定日期：2026-10-10｜地位：SDD 项目宪法之一。
> **颗粒度规则**：以「已验收功能」为最小颗粒；每个功能对应一个 git 分支（分支隔离）与一个规范文件夹（`the integration of information.project/specs/<FXX>/`）。规范内容通过与用户对话逐个建立（SDD：先规范后实现）。
> 状态权威源：`docs/PROJECT_SYSTEM.md`；本表为其宪法化视图，冲突时以 PROJECT_SYSTEM.md 为准。

## 一、SDD 流程（规范驱动开发程序）

```
功能想法 → specs/<FXX>/spec.md（需求与边界/接口契约/验收标准）
        → plan.md（技术方案）→ tasks.md（任务清单）
        → 分支 feature/<FXX>-<slug> 上实现 → 验收证据记录 → 合并
```

- 规范未建立不开始实现；规范变更走宪法修订（用户批准）。
- 已验收功能补建规范时，从 PROJECT_SYSTEM.md 对应章节反向提取验收证据。
- **git 状态（2026-10-10 启用）**：仓库已初始化，主分支 `main`，基线提交 `e2b23db`；§二 F 表所列 12 个 `feature/f<xx>-<slug>` 分支**均已实际建立**（自 `main` 分出）。功能规范的建立与评审在该分支上进行，收尾 `--no-ff` 合并回 `main`。工作流与 git 约定见 `../README.md` §三/§四。

## 二、已验收功能（F01–F12，规范待逐个补建）

| ID | 功能 | 分支 | 现状/验收证据（PROJECT_SYSTEM.md） |
|---|---|---|---|
| F01 | 信息采集三通道（HTTP 适配器/云端浏览器/伴侣脚本） | `feature/f01-tri-channel-crawling` | §5.1：三通道 + 降级链 + 熔断（已验证） |
| F02 | 平台与类目体系（0.5.3 批次，32 类目） | `feature/f02-platform-taxonomy` | §5.2：arXiv/HN/豆瓣等接入并 feed 验证 |
| F03 | 3xui 云端代理穿透 | `feature/f03-cloud-proxy` | §5.3：节点自动择优 READY（API 实测） |
| F04 | 浏览器自动化与登录态 | `feature/f04-browser-automation` | §5.4：反检测 + 登录态（pendingLogins=0 正常） |
| F05 | AI 摘要与翻译链 | `feature/f05-ai-summary-translation` | §5.5：三档摘要 + 翻译 in-flight 修复五场景回归（2026-09-21 部署验证） |
| F06 | 视频分析管线 | `feature/f06-video-pipeline` | §5.6：字幕优先 + 30 分钟总期限（P0 验收） |
| F07 | 个性化偏好引擎 | `feature/f07-preference-engine` | §5.7：自然语言偏好解析（feed personalized=true 实测） |
| F08 | 客户端体验六项 | `feature/f08-client-ux` | §5.8：已读状态机/骨架屏/切换动画/下拉刷新/动态取色/本地缓存（0.5.8/versionCode 15） |
| F09 | 决策核心 V2（P0+P1，灰度） | `feature/f09-decision-core-v2` | §5.9/§10.4：压测 V1/V2 对比零错误；灰度开关默认关 |
| F10 | 视频能力 MCP 封装 v0.1.0 | `feature/f10-video-mcp` | §4.5：三工具端到端实测通过（2026-09-20） |
| F11 | 视频处理 Agent 应用 v0.1.0 | `feature/f11-video-agent` | §4.6：check + 云端端到端 brief 验证（2026-09-23） |
| F12 | 采集伴侣油猴脚本 v0.1.0 | `feature/f12-companion-extension` | 附录：`extension/trae-companion.user.js`（通道 B 实现体） |

## 三、进行中 / 待建（下一批规范）

| ID（预留） | 功能 | 依据 | 状态 |
|---|---|---|---|
| F13 | 视频共享内核（M2：SourceResolver/AnalysisPlanner/ProcessRunner 等） | video-agent-watch.md §六 | 设计定稿，待实施 |
| F14 | 视频证据包契约（M4：segmentId/frameId + coverage） | video-agent-watch.md §四 | 待 M2 |
| F15 | REST 任务接口五端点 + 七工具任务版（M5） | video-mcp-tool.md §四/§五 | 契约可直接实现 |
| F16 | 概要翻译闭环 + 字幕/EDL 导出（M3/M6） | video-translation-editing.md §八 | 待 M2/M3/M4 |
| F17 | 决策核心 V2 P2/P3（DedupService 事件分组、QueryPlanner、全量评估） | §10.4 | 观测数据积累后推进 |
| F18 | 前端阅读体验（字号行距/快捷键/全文阅读视图） | §10.2 待办 | 未开始 |

## 四、里程碑视图

- **已完成**：0.5.x 客户端六批体验（→0.5.8）、三通道采集体系、决策 V2 P0/P1、视频管线 P0 期限、MCP/Agent 首期封装（F01–F12）。
- **当前阶段**：视频三方向 M2 共享内核 → M4 证据包 → M5 任务接口（F13–F15 为主线）。
- **远期**：决策 V2 全量、方向 C 出片链、平台批次 2/3 复核（§10.3 待评估）。

## 五、规范建立对话排期（与用户逐个进行）

按依赖顺序：F13（内核）→ F15（任务接口）→ F14（证据包）→ F16（翻译闭环）→ 回补 F01–F12 规范（反向提取）→ F17/F18。每轮对话聚焦一个功能，产出 `spec.md`（+ plan/tasks）后**留在该功能分支的 `specs/F<xx>-<slug>/` 目录**并合并回 `main`（路径：`the integration of information.project/specs/`）。
