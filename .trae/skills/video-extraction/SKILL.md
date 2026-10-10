---
name: video-extraction
description: 视频信息提取选型与执行规范——提炼三方向方案（Agent 观看/MCP 工具/翻译剪辑）共通思路。Use when 用户要求提取视频转录、取帧、生成视频简报、视频监控批处理、基于视频问答，或封装/扩展视频工具时。Do not use for 非视频类信息采集或普通编码任务。
---

# 视频信息提取思路（三方向方案提炼）

上游：`docs/video-agent-watch.md`（A 证据包）、`docs/video-mcp-tool.md`（B 工具）、`docs/video-translation-editing.md`（C 翻译剪辑），现状权威源 `docs/PROJECT_SYSTEM.md` §4.5/4.6。

## 一、先选形态，再动手

| 任务 | 用什么 | 关键点 |
|---|---|---|
| 单视频一次性简报/取帧 | `agent/dist/index.js brief|frames <url>` | `cd agent`；产出 `agent/reports/<日期>/*.md`，含 seg#/frame# 引用 |
| Agent 宿主内自主规划 | 直接调 MCP 工具 `analyze_video/get_transcript/get_frames` | 接入配置见 `docs/mcp-usage-guide.md` |
| 无人值守批处理/监控 | `agent/dist/index.js watch`（清单 `agent/watch.json`）→ `digest` | state 去重已处理 URL；串行执行 |
| 程序直连/调试 | `POST /api/video/analyze` | `summaryLevel` 传中文档位（模糊/默认/详细） |
| 字幕翻译/优化/EDL 导出 | 方向 C 仅设计稿，**未实施** | 勿宣称自动执行；信息流唯一自动步骤=标题+概要翻译 |

环境变量：`TRAE_VIDEO_SERVER`（默认 `http://127.0.0.1:8765`，云端 `http://<VPS_IP>:8765`）；agent 另有 `TRAE_MCP_ENTRY` 覆盖 MCP 入口。

## 二、成本思路（全链路最优先）

1. **档位**：brief=0 帧零视觉（默认选它）→ standard=3 帧描述 → detailed=≤8 帧+OCR；全局硬上限 12 帧。get_frames 固定 detailed 档。
2. **全管线成本**：当前 REST 单端点下任何工具都会下载完整媒体并转录（10s 样本 ≈145s）；brief 只省视觉阶段，不省下载/转录。
3. **禁止重复调用同一 URL**——服务端无缓存旁路；先查 `agent/reports/` 与 watch state，需要重跑删对应 state 条目。
4. **串行**：服务端 `Semaphore(1)` 忙时直接拒绝（无排队）。不要并发压服务端；watch 天然串行。
5. 期限：服务端 30 分钟总期限；agent 客户端单次工具超时 16 分钟。

## 三、证据思路（可引用、不臆测）

1. 先看 `transcriptSource`：`platform_or_embedded_caption`（字幕优先，跳过 ASR）或 `asr`——决定对文本可信度的判断。
2. 文本问题零视觉调用；画面问题先定位时间窗再取帧。当前无 startSec/endSec 参数=只能全片，如实告知成本，勿假装支持时间窗。
3. 引用格式：`seg#N [mm:ss-mm:ss] 原文`、`frame#N @mm:ss 描述｜OCR`；时间戳为原视频绝对秒。
4. AI 总结/视觉描述与转录原文**分开呈现**，模型生成内容不冒充原始事实。
5. 视觉证据是稀疏抽样（sparse），不构成全片覆盖；无语音样本 `segs=0` 属实，如实报告不编造。

## 四、诚实边界（禁止宣称）

- 仅 HTTP(S) 媒体直链（服务端 `http.get`）；YouTube/Bilibili 平台页解析**待实现**；登录/DRM/区域限制明确报错，不支持"任意 URL"。
- 任务接口未上线：**不虚设** get_metadata / get_task / cancel_task / analyze_moment，不承诺转录时间窗与按需阶段提取。
- 帧图=JSON 内 base64，非 MCP 原生图像内容块；`standard`+includeImages 拿不到被省略的图（仅 detailed 回传）。
- `forceRefresh`/阶段缓存不存在；超 300MB 下载上限会失败。

## 五、验证口径

1. 先秒级自检再真实调用：agent `node dist/index.js check`；MCP 层 `SMOKE_LIST_ONLY=1 node mcp/examples/smoke-test.mjs`。
2. 成本回归按**阶段调用次数**计量（如 get_transcript 视觉调用=0），不以"请求成功"判定按需提取达成。
3. 实测基线：check 0.3s；10s 样本 brief 端到端 ≈145s；无语音样本报告正确降级（见 `agent/reports/2026-09-23/`）。

## 六、故障速查

| 现象 | 处理 |
|---|---|
| check 工具列表空 | `cd mcp && npm run build`；`TRAE_MCP_ENTRY` 指错则修正 |
| HTTP 连接失败 | 服务端不可达：安全组 8765 / `systemctl is-active trae-info`（SSH 需用户批准） |
| 超 16 分钟超时 | 闸门被占或视频过长；改 brief 档或稍后重试 |
| 返回 HTML/解析异常 | URL 非媒体直链 |
| watch 全跳过 | state 已有记录（预期行为） |

## 七、何时更新本技能

服务端任务接口五端点（`POST /api/video/tasks`，方案 M5）上线后必须同步：追加四工具、任务式调用、阶段缓存/`forceRefresh`、方向 A 证据包契约（segmentId/frameId/artifactId + coverage）替换 markdown 证据格式；同步更新 `docs/video-*.md` 与 PROJECT_SYSTEM.md。
