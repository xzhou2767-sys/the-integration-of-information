# trae-video-mcp 链接与全套使用方法

> 版本：1.0｜导出日期：2026-09-23｜对应 trae-video-mcp v0.1.0（已构建，端到端实测通过）
> 上游设计：[docs/video-mcp-tool.md](video-mcp-tool.md) v2.0｜实现目录：`mcp/`

## 一、链接与路径汇总

| 项 | 值 |
|---|---|
| MCP 名称 | `trae-video-mcp`（版本 0.1.0，stdio 传输） |
| 入口文件（本机绝对路径） | `C:\Users\ASUS\Documents\trae_projects\integration of information project\mcp\dist\index.js` |
| 实现目录 | `C:\Users\ASUS\Documents\trae_projects\integration of information project\mcp\`（src/index.ts + package.json + examples/smoke-test.mjs） |
| 后端服务端（云端） | `http://<VPS_IP>:8765`（环境变量 `TRAE_VIDEO_SERVER` 配置；默认 `http://127.0.0.1:8765`） |
| 实际调用的 REST 端点 | `POST {TRAE_VIDEO_SERVER}/api/video/analyze` |
| 依赖版本 | 官方 SDK `@modelcontextprotocol/sdk` 1.30.0、`zod` 3.25.76、TypeScript 5.9.2（package-lock.json 锁定） |
| 协议 | MCP stdio（JSON-RPC；stdout 只输出协议消息，日志走 stderr） |

## 二、环境要求与安装

- Node.js ≥ 18（构建环境 v24）。
- 首次使用前构建一次（dist/index.js 已随项目构建好，改动 src 后需重新构建）：

```bash
cd mcp
npm install          # 按锁定版本安装
npm run build        # tsc 编译到 dist/
```

## 三、宿主接入配置（全套）

JSON 配置中 Windows 路径反斜杠需转义为 `\\`。

### 1. Claude Code（CLI 方式，推荐）

```bash
claude mcp add trae-video --env TRAE_VIDEO_SERVER=http://<VPS_IP>:8765 -- node "C:\Users\ASUS\Documents\trae_projects\integration of information project\mcp\dist\index.js"
```

或直接写 `~/.claude.json` 的 `mcpServers`：

```json
{
  "mcpServers": {
    "trae-video": {
      "command": "node",
      "args": ["C:\\Users\\ASUS\\Documents\\trae_projects\\integration of information project\\mcp\\dist\\index.js"],
      "env": { "TRAE_VIDEO_SERVER": "http://<VPS_IP>:8765" }
    }
  }
}
```

### 2. Cursor（Settings → MCP → Add MCP Server）

```json
{
  "mcpServers": {
    "trae-video": {
      "command": "node",
      "args": ["C:\\Users\\ASUS\\Documents\\trae_projects\\integration of information project\\mcp\\dist\\index.js"],
      "env": { "TRAE_VIDEO_SERVER": "http://<VPS_IP>:8765" }
    }
  }
}
```

### 3. 通用 stdio 宿主（含 Trae 等）

任何支持 stdio MCP 的宿主统一填法：

- command：`node`
- args：`<项目路径>\mcp\dist\index.js`
- env：`TRAE_VIDEO_SERVER = http://<VPS_IP>:8765`（可选；不填走本机 8765）

### 4. 手动直启（调试用）

```powershell
$env:TRAE_VIDEO_SERVER = "http://<VPS_IP>:8765"
node "C:\Users\ASUS\Documents\trae_projects\integration of information project\mcp\dist\index.js"
# 启动后等待 JSON-RPC（stdio），stderr 输出：[trae-video-mcp] stdio 已启动，后端：...
```

## 四、工具清单（三工具）

### 1. `analyze_video` — 分析视频（全管线）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `url` | string (URL) | 必填 | 视频 URL（当前仅支持 http/https 媒体直链） |
| `detail` | brief / standard / detailed | standard | 成本档位 |
| `maxFrames` | int 1-8 | 5 | 仅 detailed 生效 |
| `includeImages` | bool | false | 是否回传帧图 base64（体积大） |

档位映射（`detail` → 服务端 `summaryLevel`）：`brief→模糊`（0 帧，零视觉成本）、`standard→默认`（3 帧视觉描述、无 OCR、不回图）、`detailed→详细`（尊重 maxFrames + OCR + 可回传帧图）。

返回：`{title, summary, transcript, language, durationSec, segments[{start,end,text}], keyFrames[{timestamp,description,ocrText,imageBase64?}], usedSubtitles}`。

### 2. `get_transcript` — 获取转录（低成本）

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `url` | string (URL) | 必填 | 视频 URL（媒体直链） |

返回：`{title, language, durationSec, transcriptSource, segments[], transcript}`；`transcriptSource` 取值 `platform_or_embedded_caption`（平台/内嵌字幕，跳过 ASR）或 `asr`（语音识别）。

### 3. `get_frames` — 关键帧提取

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `url` | string (URL) | 必填 | 视频 URL（媒体直链） |
| `maxFrames` | int 1-8 | 5 | 关键帧数量 |
| `includeImages` | bool | false | 回传 JPEG base64（仅确实要看图时开启） |

返回：`{title, durationSec, keyFrames[{timestamp, description, ocrText, imageBase64?}]}`。

### 调用示例（对 Agent 宿主自然语言）

- 「用 trae-video 的 get_transcript 转录 https://example.com/sample.mp4」
- 「用 analyze_video 详细档分析 https://example.com/sample.mp4，取 6 帧，不要图」
- 「用 get_frames 取 https://example.com/sample.mp4 的 5 个关键帧含图片」

等价 REST（调试直连）：

```bash
curl -X POST http://<VPS_IP>:8765/api/video/analyze \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/sample.mp4","summaryLevel":"模糊","describeFrames":false}'
```

## 五、验证安装

```powershell
cd mcp
# 快速协议验证（不发起真实分析，秒级）：
$env:SMOKE_LIST_ONLY="1"; $env:TRAE_VIDEO_SERVER="http://<VPS_IP>:8765"
node examples/smoke-test.mjs
# 完整端到端（真实调用 get_transcript，10s 样本约 2.5 分钟）：
node examples/smoke-test.mjs
```

预期三步输出：`[1/3] initialize ok` → `[2/3] tools/list ok: analyze_video, get_transcript, get_frames` → `[3/3] tools/call get_transcript (xx s) ok`（list-only 模式只到第二步）。

## 六、边界与已知限制（诚实声明）

- **工具范围**：`get_metadata`（零成本元数据）、`get_task`/`cancel_task`、`analyze_moment` 依赖服务端任务接口（方案 B-1/M5），本期不提供，不虚设。
- **来源限制**：仅 HTTP(S) 媒体直链（服务端下载器 `http.get(url)`）；YouTube/Bilibili 平台页解析为待实现能力；登录/DRM/区域限制返回明确错误。
- **全管线成本**：当前 REST 单端点下所有工具触发服务端完整管线——`get_transcript` 无视觉调用但服务端仍跑一次中文总结后丢弃；`get_frames` 仍执行转录+总结。10s 样本全管线约 145s。
- **无缓存旁路**：同 URL 重复调用全量重跑；`forceRefresh`/阶段缓存待任务接口上线。
- **图片交付**：帧图为 JSON 内 base64 字符串，非 MCP 原生图像内容块；`standard` 档 + `includeImages=true` 拿不到被服务端省略的图（仅 detailed 回传）。
- **无内置鉴权**：MCP 层不做鉴权（沿用服务端 REST 现状）；无凭据内置，服务器地址经环境变量配置。

## 七、故障排查

| 现象 | 原因与处理 |
|---|---|
| 宿主里工具列表为空 | dist 未构建（`npm run build`）或 command/args 路径错误；先跑 smoke-test list-only 验证 |
| 调用报「服务端 HTTP 000/连接失败」 | `TRAE_VIDEO_SERVER` 不可达：云端检查安全组 8765、服务 `systemctl is-active trae-info` |
| 报「服务端响应超时（>10 分钟）」 | 视频过长或服务端闸门被占（`Semaphore(1)`，忙时直接拒绝）；稍后重试或换 brief 档 |
| 报错含 HTML/下载异常 | URL 非媒体直链（如 YouTube/B 站播放页）——当前不支持，改用直链 |
| 返回 `imageBase64: null` | standard 档不回图；改 `detail=detailed`（analyze_video）或保持 get_frames（detailed 档） |

## 八、升级路径

服务端任务接口（`POST /api/video/tasks` 五端点，见 video-mcp-tool.md §四）上线后：追加 `get_metadata`/`get_task`/`cancel_task`/`analyze_moment` 四工具，现有三工具切换为任务式调用（长任务不再阻塞单请求），并解锁阶段缓存/`forceRefresh`/原生图像内容块。
