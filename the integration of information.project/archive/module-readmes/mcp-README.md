# trae-video-mcp

Trae Info 视频分析能力的 MCP（Model Context Protocol）封装：把服务端 REST（`POST /api/video/analyze`）暴露为 stdio MCP 工具，供 Claude Code / Cursor / Codex 等 Agent 宿主直接调用。

依据 [docs/video-mcp-tool.md](../docs/video-mcp-tool.md) v2.0 的诚实边界版实施：协议适配层不复制业务逻辑，全部计算在服务端完成。

## 工具清单

| 工具 | 参数 | 说明 |
|---|---|---|
| `analyze_video` | `url`*, `detail`(brief/standard/detailed，默认 standard), `maxFrames`(1-8，仅 detailed), `includeImages`(默认 false) | 全管线：下载 → 字幕优先转录（无字幕 ASR 兜底）→ 关键帧（按档位）→ AI 总结。brief 档零视觉成本 |
| `get_transcript` | `url`* | 低成本转录：brief 档 0 帧、无视觉/OCR 调用；返回 segments 时间轴 + 转录 + `transcriptSource`（platform_or_embedded_caption / asr） |
| `get_frames` | `url`*, `maxFrames`(1-8，默认 5), `includeImages`(默认 false) | 关键帧提取：时间戳 + 视觉描述 + OCR；includeImages=true 时回传 JPEG base64（体积大） |

档位映射（`detail` → 服务端 `summaryLevel`）：`brief→模糊`（0 帧）、`standard→默认`（3 帧视觉描述、无 OCR、不回图）、`detailed→详细`（尊重 maxFrames + OCR + 回传帧图）。

## 环境要求

- Node.js ≥ 18（构建于 v24，依赖已锁定：`@modelcontextprotocol/sdk` 1.30.0、`zod` 3.25.76、TypeScript 5.9.2）
- 可达的 Trae Info 服务端（默认 `http://127.0.0.1:8765`，经环境变量 `TRAE_VIDEO_SERVER` 配置，如云端部署 `http://<VPS_IP>:8765`）

## 构建与本地运行

```bash
cd mcp
npm install          # 按 package-lock.json 锁定版本安装
npm run build         # tsc 编译到 dist/
node dist/index.js    # 启动（stdio，等待 JSON-RPC）
```

启动时可通过环境变量指定后端：

```bash
TRAE_VIDEO_SERVER=http://<VPS_IP>:8765 node dist/index.js
```

## 宿主接入

### Claude Code（claude_code_desktop / CLI）

在 MCP 服务器配置（`claude mcp add` 或 `~/.claude.json`）：

```json
{
  "mcpServers": {
    "trae-video": {
      "command": "node",
      "args": ["C:\\path\\to\\integration of information project\\mcp\\dist\\index.js"],
      "env": { "TRAE_VIDEO_SERVER": "http://<VPS_IP>:8765" }
    }
  }
}
```

CLI 方式：

```bash
claude mcp add trae-video --env TRAE_VIDEO_SERVER=http://<VPS_IP>:8765 -- node /path/to/mcp/dist/index.js
```

### Cursor

`Settings → MCP → Add MCP Server`：

```json
{
  "mcpServers": {
    "trae-video": {
      "command": "node",
      "args": ["/path/to/mcp/dist/index.js"],
      "env": { "TRAE_VIDEO_SERVER": "http://<VPS_IP>:8765" }
    }
  }
}
```

### 通用 stdio 宿主

任何支持 stdio MCP 的宿主：command = `node`，args = `<项目路径>/mcp/dist/index.js`，可选 env `TRAE_VIDEO_SERVER`。

## 验证安装

```bash
cd mcp && npm run build
# 快速协议验证（不发起真实分析，秒级完成）：
SMOKE_LIST_ONLY=1 TRAE_VIDEO_SERVER=http://<VPS_IP>:8765 node examples/smoke-test.mjs
# 完整端到端（真实调用 get_transcript，10 秒样本约 2.5 分钟）：
TRAE_VIDEO_SERVER=http://<VPS_IP>:8765 node examples/smoke-test.mjs
```

预期输出三步：`[1/3] initialize ok` → `[2/3] tools/list ok: analyze_video, get_transcript, get_frames` → `[3/3] tools/call get_transcript (xx s) ok`（list-only 模式只到第二步）。Windows PowerShell 下环境变量用 `$env:SMOKE_LIST_ONLY="1"; $env:TRAE_VIDEO_SERVER="..."` 形式。

## 边界与已知限制（诚实声明）

- **工具范围**：`get_metadata`（零成本元数据）、`get_task` / `cancel_task`（任务查询/取消）、`analyze_moment`（时间窗局部分析）依赖服务端任务接口（方案 B-1 / M5 阶段），本期不提供，不虚设。
- **来源限制（当前）**：仅支持 **HTTP(S) 媒体直链**——服务端下载器为 `http.get(url)`，尚无平台播放页解析器；YouTube/Bilibili 平台页解析为**待实现能力**（需真实解析实现与样本验证后开放）。登录/DRM/区域限制内容返回明确错误，不支持"任意 URL"。
- **调用成本（全管线约束）**：当前 REST 单端点下，所有工具都会触发服务端完整管线——`get_transcript` 不触发视觉模型，但服务端仍会执行一次中文总结（随后丢弃）；`get_frames` 仍会执行转录与总结。按需阶段提取待服务端任务接口（AnalysisPlanner）上线。
- **图片交付形态**：帧图以 JSON 字符串内的 base64 返回（`includeImages=true`），**尚非 MCP 原生图像内容块**；`standard` 档 + `includeImages=true` 无法取得被服务端省略的帧图（仅 `detailed` 档回传）。
- **无缓存旁路**：当前 REST 单端点对同一 URL 重复调用会重新跑完整管线（约 2.5 分钟/10s 视频）；`forceRefresh` 与阶段缓存待服务端任务接口上线。
- **stdout 纪律**：本进程 stdout 只输出协议消息（宿主依赖）；日志走 stderr，已在代码中强制。
- **安全**：服务器地址经环境变量配置；无凭据内置。MCP 层不做鉴权（沿用服务端 REST 现状，服务端侧配额/鉴权属任务接口范畴）。

## 升级路径

服务端任务接口（`POST /api/video/tasks` 五端点，见 video-mcp-tool.md §四）上线后，本封装将追加 `get_metadata` / `get_task` / `cancel_task` / `analyze_moment` 工具并把现有工具切换到任务式调用（长任务不再阻塞单次请求）。
