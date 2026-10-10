# Agent 版视频处理应用（trae-video-agent）架构与完整使用说明

> 版本：1.0｜导出日期：2026-09-23｜对应 trae-video-agent v0.1.0（已构建，MCP 链路自检 + 云端端到端实测通过）
> 定位：方向 A（[video-agent-watch.md](video-agent-watch.md)）Agent 观看能力的**当前可行子集**——固定流程智能体 + 可引用证据报告；方向 A 的证据包任务接口（M4）落地前的过渡形态。

## 一、架构

```
┌─────────────────────────────────────────────────────────────────┐
│  用户 / CLI / 定时调度                                           │
│    check · brief · frames · watch · digest                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │ 命令编排（agent/src/index.ts）
┌──────────────────────────▼──────────────────────────────────────┐
│  trae-video-agent（MCP 宿主 + 固定流程智能体）                    │
│  · VideoMcpClient：stdio 客户端，连接 MCP，超时 16 分钟           │
│  · 任务编排：state 去重（watch）、报告目录管理（tasks.ts）        │
│  · 证据报告：seg#/frame# 可引用编号 + 证据边界声明（report.ts）   │
└──────────────────────────┬──────────────────────────────────────┘
                           │ MCP stdio（JSON-RPC）
┌──────────────────────────▼──────────────────────────────────────┐
│  trae-video-mcp v0.1.0（协议适配层）                              │
│  analyze_video / get_transcript / get_frames（zod schema 校验）   │
└──────────────────────────┬──────────────────────────────────────┘
                           │ POST /api/video/analyze
┌──────────────────────────▼──────────────────────────────────────┐
│  Trae Info 服务端（腾讯云 VPS :8765，30 分钟总期限）               │
│  下载(300MB 上限) → 字幕优先转录(ASR 兜底) → AI 关键帧时间戳      │
│  → FFmpeg 截帧 → OCR(详细档)/视觉描述 → AI 三源融合总结           │
└─────────────────────────────────────────────────────────────────┘
```

| 层 | 职责 | 不做 |
|---|---|---|
| trae-video-agent | 命令编排、监控清单状态去重、证据报告渲染 | 不复制业务逻辑、不做自主 LLM 规划 |
| trae-video-mcp | 协议适配、schema 校验、档位映射、体积控制 | 不复制下载/转录逻辑 |
| 服务端 REST | 全部计算（下载/转录/取帧/总结） | 无阶段缓存/任务化（待 M5） |

**智能体形态说明（诚实边界）**：v0.1.0 是**固定流程智能体**——任务路由是确定性的（命令 → 固定管线 → 报告），智能性来自服务端 AI（转录总结/关键帧推断/视觉描述）。需要**自主规划**（模型自己决定调用哪个工具、何时取帧）时，让 Claude Code / Cursor 等宿主直接驱动 trae-video-mcp（见 [mcp-usage-guide.md](mcp-usage-guide.md)）；本应用定位为**无人值守批处理与报告生成**，二者互补。

## 二、链接与路径

| 项 | 值 |
|---|---|
| 应用目录 | `C:\Users\ASUS\Documents\trae_projects\integration of information project\agent\` |
| 入口 | `agent\dist\index.js`（bin: `trae-video-agent`） |
| 依赖 | 官方 MCP SDK `@modelcontextprotocol/sdk` 1.30.0（客户端模式）；TypeScript 5.9.2 |
| MCP 适配层 | `mcp\dist\index.js`（`TRAE_MCP_ENTRY` 可覆盖；默认按相对路径自动定位） |
| 后端服务端 | `http://<VPS_IP>:8765`（环境变量 `TRAE_VIDEO_SERVER`；默认 `http://127.0.0.1:8765`） |
| 报告输出 | `agent\reports\<日期>\`（`--out` 可覆盖） |
| 监控清单 | `agent\watch.json`（不存在时自动生成模板） |
| 监控状态 | `agent\.watch-state.json`（已处理 URL 去重标记） |

## 三、安装与构建

```powershell
cd agent
npm.cmd install
npm.cmd run build
```

要求：Node.js ≥ 18；`mcp/` 已构建（agent 依赖其 dist/index.js）。

## 四、命令全套

### 1. `check` — 链路自检（秒级）

```powershell
node dist/index.js check
```

执行 MCP initialize + tools/list，不发起真实分析。预期输出三工具名 + `AGENT_CHECK_DONE`。

### 2. `brief <url>` — 单视频简报

```powershell
$env:TRAE_VIDEO_SERVER = "http://<VPS_IP>:8765"
node dist/index.js brief "https://example.com/sample.mp4" --detail=brief
```

| 参数 | 值 | 说明 |
|---|---|---|
| `--detail` | brief（默认）/ standard / detailed | brief 零视觉成本；standard 3 帧；detailed 含 OCR |
| `--out` | 目录 | 覆盖默认报告目录 |

产出 `agent\reports\<日期>\<host>-<hash>.md`：元信息表 → AI 总结 → **转录时间轴**（`seg#1 [mm:ss-mm:ss] 原文`，可引用）→ **关键帧证据**（`frame#1 @mm:ss 视觉描述｜OCR`）→ 证据边界声明。

### 3. `frames <url>` — 帧证据报告

```powershell
node dist/index.js frames "https://example.com/sample.mp4" --max=6 --images
```

`--max` 1-8（默认 5）；`--images` 额外保存原始 JSON（含 JPEG base64，文件较大）。产出 `<host>-<hash>-frames.md`。

### 4. `watch` — 监控清单批处理（核心 agent 能力）

```powershell
node dist/index.js watch            # 清单不存在时自动生成 agent/watch.json 模板，编辑后重跑
node dist/index.js watch --config=D:\my\watch.json
```

清单格式：

```json
{
  "detail": "brief",
  "videos": [
    { "url": "https://example.com/a.mp4", "note": "可选备注" },
    { "url": "https://example.com/b.mp4" }
  ]
}
```

行为：对每个**未处理** URL 生成简报 → 写入 state（`已处理标记`）→ 全部完成后自动生成当日汇总；**已处理 URL 跳过**（state 去重——当前服务端无阶段缓存旁路，这是防重复全管线成本的唯一手段）。

### 5. `digest` — 手动汇总

```powershell
node dist/index.js digest
```

把 state 中全部已处理视频合并为 `digest-<日期>-<id>.md`（标题/URL/档位/简报路径/总结摘录）。

## 五、验证记录（2026-09-23 实测）

| 验证 | 结果 |
|---|---|
| 构建 `tsc` | 通过（strict 模式） |
| `check` MCP 链路 | initialize 0.3s + tools/list 三工具全发现 |
| 云端端到端 `brief --detail=brief`（10s 样本 mov_bbb.mp4） | 报告生成：AI 总结 + seg# 时间轴 + transcriptSource=asr + 证据边界声明 |

## 六、边界与已知限制（诚实声明）

- **来源限制**：仅 HTTP(S) 媒体直链；YouTube/Bilibili 平台页解析待实现；登录/DRM/区域限制返回明确错误。
- **全管线成本**：当前 REST 单端点下每次工具调用都下载完整媒体并转录（brief 档免视觉费用但仍有下载+ASR/转录+总结）；10s 样本约 145s。**不要对同一 URL 反复 brief**——先查 reports/ 与 watch state。
- **无自主规划**：固定流程智能体；自主规划由 MCP 宿主承担。
- **无服务端缓存旁路**：`forceRefresh`/阶段缓存待任务接口（M5）上线；agent 层 state 去重只覆盖本机。
- **并发**：服务端 `Semaphore(1)` 忙时直接拒绝（429/错误返回），watch 串行处理是为避开该约束。
- **图片交付**：JSON 内 base64，非 MCP 原生图像内容块；`--images` 仅在确实要看图时使用。

## 七、故障排查

| 现象 | 处理 |
|---|---|
| `check` 失败/工具列表为空 | `mcp/` 未构建：`cd mcp && npm.cmd run build`；或 `TRAE_MCP_ENTRY` 指错 |
| 工具调用报「服务端 HTTP 连接失败」 | `TRAE_VIDEO_SERVER` 不可达：查安全组 8765、`systemctl is-active trae-info`（SSH 需批准） |
| 超时（>16 分钟） | 服务端闸门被占或视频过长；稍后重试或用 brief 档 |
| 返回 HTML/解析异常 | URL 非媒体直链 |
| watch 全部跳过 | state 已有记录；删除 `agent\.watch-state.json` 对应条目可强制重处理 |

## 八、升级路径

服务端任务接口（`POST /api/video/tasks` 五端点，M5）上线后：agent 切换任务式调用（提交/轮询/取消）；解锁按需阶段提取（get_metadata 零下载）、阶段缓存与 `forceRefresh`；方向 A 证据包契约（segmentId/frameId/artifactId + coverage）替换当前 markdown 证据格式。
