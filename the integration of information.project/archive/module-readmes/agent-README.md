# trae-video-agent — agent 版视频处理应用

Trae Info 视频能力的 Agent 版本：**MCP 宿主 + 固定流程智能体**。以 stdio 客户端身份驱动 [trae-video-mcp](../mcp/) 的三个工具，把视频处理编排为四类任务（简报 / 帧证据 / 监控批处理 / 汇总），输出**可引用的 markdown 证据报告**（`seg#`/`frame#` 编号 + 时间戳，对齐方向 A 证据包思想）。

完整使用说明（含架构）：[docs/video-agent-usage.md](../docs/video-agent-usage.md)

## 快速开始

```powershell
cd agent
npm.cmd install
npm.cmd run build
# 链路自检（秒级）：
node dist/index.js check
# 单视频简报（brief 档零视觉成本，约 1-3 分钟）：
$env:TRAE_VIDEO_SERVER = "http://<VPS_IP>:8765"
node dist/index.js brief "https://example.com/sample.mp4"
```

## 命令

| 命令 | 说明 |
|---|---|
| `check` | MCP 链路自检（initialize + tools/list，不发起真实分析） |
| `brief <url> [--detail=brief\|standard\|detailed] [--out=dir]` | 单视频简报：AI 总结 + 转录时间轴 + 帧证据 |
| `frames <url> [--max=1-8] [--images] [--out=dir]` | 帧证据报告：时间戳 + 视觉描述 + OCR |
| `watch [--config=path] [--out=dir]` | 监控清单批处理：未处理 URL 逐个出简报，已处理跳过（state 去重），自动更新汇总 |
| `digest [--out=dir]` | 手动重出已处理视频汇总 |

## 定位与边界（诚实声明）

- 本应用是**固定流程智能体**（无自主 LLM 规划）；需要自主规划请让 Claude Code 等 Agent 宿主直接驱动 trae-video-mcp。
- 仅支持 HTTP(S) 媒体直链；YouTube/Bilibili 平台页解析待实现。
- 服务端 REST 单端点全管线成本：每次工具调用下载完整媒体并转录；watch 的 state 去重是当前**唯一**防重复成本手段（服务端阶段缓存待任务接口上线）。
