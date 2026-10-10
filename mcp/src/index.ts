#!/usr/bin/env node
/**
 * trae-video-mcp：把 Trae Info 服务端视频分析 REST 封装为 MCP 工具（stdio）。
 * 依据 docs/video-mcp-tool.md v2.0 的诚实边界版：
 * - 复用现有 POST /api/video/analyze（旧端点适配入口），不复制业务逻辑；
 * - 仅提供当前 REST 真实可支撑的工具：analyze_video / get_transcript / get_frames；
 * - get_metadata（零成本元数据）与 get_task / cancel_task / analyze_moment 依赖服务端
 *   任务接口（方案 B-1，M5 阶段），上线后在本文件追加，本期不虚设。
 * - stdout 只输出协议消息；日志与错误诊断走 stderr。
 * 服务器地址经环境变量 TRAE_VIDEO_SERVER 配置（默认 http://127.0.0.1:8765）。
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const SERVER_URL = (process.env.TRAE_VIDEO_SERVER ?? "http://127.0.0.1:8765").replace(/\/+$/, "");
const ANALYZE_ENDPOINT = `${SERVER_URL}/api/video/analyze`;

/** detail → 服务端 summaryLevel（VideoAnalyzeRequest 契约，值为中文字符串）。 */
const DETAIL_MAP: Record<string, string> = {
  brief: "模糊",
  standard: "默认",
  detailed: "详细",
};

/** 服务端响应契约（VideoAnalyzeResponse）。 */
interface TranscriptSegment {
  start: number;
  end: number;
  text: string;
}
interface KeyFrame {
  timestamp: number;
  imageBase64?: string | null;
  description?: string | null;
  ocrText?: string | null;
}
interface VideoAnalyzeResponse {
  title: string;
  summary: string;
  transcript: string;
  language: string;
  durationSec: number;
  segments: TranscriptSegment[];
  keyFrames: KeyFrame[];
  usedSubtitles: boolean;
}

/** REST 调用 + 结构化错误（非 2xx → ErrorResponse(error) 约定）。 */
async function callAnalyze(body: object, timeoutMs = 10 * 60 * 1000): Promise<VideoAnalyzeResponse> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(ANALYZE_ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await res.text();
    if (!res.ok) {
      let msg = text;
      try {
        const parsed = JSON.parse(text) as { error?: string };
        if (parsed.error) msg = parsed.error;
      } catch {
        /* 非 JSON 错误体，保留原文 */
      }
      throw new Error(`服务端 HTTP ${res.status}: ${msg}`);
    }
    return JSON.parse(text) as VideoAnalyzeResponse;
  } catch (e) {
    if (e instanceof Error && e.name === "AbortError") {
      throw new Error(`服务端响应超时（>${Math.round(timeoutMs / 60000)} 分钟）`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

/** 剔除帧图 base64（默认不回传，控制响应体积；includeImages 显式开启）。 */
function stripFrameImages(r: VideoAnalyzeResponse): VideoAnalyzeResponse {
  return {
    ...r,
    keyFrames: r.keyFrames.map(({ imageBase64: _drop, ...rest }) => rest),
  };
}

const server = new McpServer(
  { name: "trae-video-mcp", version: "0.1.0" },
  {
    instructions:
      "Trae Info 视频分析工具。服务器需可达（TRAE_VIDEO_SERVER 配置）。注意：所有工具都会触发服务端下载与转录（字幕可用时跳过 ASR）；brief 档零视觉成本。",
  },
);

/** 工具 1：analyze_video —— 全管线分析（转录 + 帧证据 + AI 总结）。 */
server.registerTool(
  "analyze_video",
  {
    title: "分析视频（全管线）",
    description:
      "对视频做完整分析：下载 → 字幕优先转录（无字幕时 ASR 兜底）→ 关键帧提取（按 detail 档）→ AI 总结。" +
      "detail=brief 零视觉成本（纯转录总结）；standard 3 帧视觉描述；detailed 尊重 maxFrames（≤8）+ OCR。" +
      "当前仅支持 HTTP(S) 媒体直链（服务端下载器为 http.get，尚无平台页解析器）；" +
      "YouTube/Bilibili 平台页解析为待实现能力（需真实解析与样本验证后开放）。",
    inputSchema: {
      url: z.string().url().describe("视频 URL（当前仅支持 http/https 媒体直链）"),
      detail: z.enum(["brief", "standard", "detailed"]).default("standard").describe("成本档位"),
      maxFrames: z.number().int().min(1).max(8).optional().describe("详细档关键帧数（1-8，默认 5；仅 detailed 生效）"),
      includeImages: z.boolean().default(false).describe("是否回传帧图 base64（默认 false 控制体积）"),
    },
  },
  async ({ url, detail = "standard", maxFrames, includeImages = false }) => {
    try {
      const r = await callAnalyze({
        url,
        summaryLevel: DETAIL_MAP[detail],
        describeFrames: detail !== "brief",
        ...(detail === "detailed" ? { keyFrameCount: maxFrames ?? 5 } : {}),
      });
      return {
        content: [{ type: "text", text: JSON.stringify(includeImages ? r : stripFrameImages(r), null, 2) }],
      };
    } catch (e) {
      return { content: [{ type: "text", text: `分析失败：${(e as Error).message}` }], isError: true };
    }
  },
);

/** 工具 2：get_transcript —— 低成本转录（brief 档，0 帧，无视觉调用）。 */
server.registerTool(
  "get_transcript",
  {
    title: "获取视频转录",
    description:
      "只取视频转录文本（brief 档：0 帧、无视觉/OCR 调用；平台字幕可用时跳过 ASR）。返回 segments 时间轴 + 转录全文 + 语言/时长。注意：服务端仍需下载音视频才能转录（并会执行一次中文总结后由本工具丢弃——当前 REST 单端点约束），本工具不触发任何视觉模型调用。",
    inputSchema: {
      url: z.string().url().describe("视频 URL（当前仅支持 http/https 媒体直链）"),
    },
  },
  async ({ url }) => {
    try {
      const r = await callAnalyze({ url, summaryLevel: DETAIL_MAP.brief, describeFrames: false });
      const out = {
        title: r.title,
        language: r.language,
        durationSec: r.durationSec,
        transcriptSource: r.usedSubtitles ? "platform_or_embedded_caption" : "asr",
        segments: r.segments,
        transcript: r.transcript,
      };
      return { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] };
    } catch (e) {
      return { content: [{ type: "text", text: `转录失败：${(e as Error).message}` }], isError: true };
    }
  },
);

/** 工具 3：get_frames —— 关键帧提取（详细档，视觉描述 + OCR）。 */
server.registerTool(
  "get_frames",
  {
    title: "获取视频关键帧",
    description:
      "提取关键帧并返回每帧时间戳 + 视觉描述 + OCR 文本（detailed 档）。maxFrames 1-8（全局硬上限内）。includeImages=true 时回传 JPEG base64（体积大，仅在确实要看图时开启）。注意：当前 REST 单端点约束下服务端会执行完整管线（含转录与总结，随后由本工具丢弃）；任务化拆分后改为按需取帧。",
    inputSchema: {
      url: z.string().url().describe("视频 URL（当前仅支持 http/https 媒体直链）"),
      maxFrames: z.number().int().min(1).max(8).default(5).describe("关键帧数量（1-8）"),
      includeImages: z.boolean().default(false).describe("是否回传帧图 base64（默认 false）"),
    },
  },
  async ({ url, maxFrames = 5, includeImages = false }) => {
    try {
      const r = await callAnalyze({
        url,
        summaryLevel: DETAIL_MAP.detailed,
        describeFrames: true,
        keyFrameCount: maxFrames,
      });
      const out = {
        title: r.title,
        durationSec: r.durationSec,
        keyFrames: r.keyFrames.map((f) => ({
          timestamp: f.timestamp,
          description: f.description ?? null,
          ocrText: f.ocrText ?? null,
          ...(includeImages ? { imageBase64: f.imageBase64 ?? null } : {}),
        })),
      };
      return { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] };
    } catch (e) {
      return { content: [{ type: "text", text: `取帧失败：${(e as Error).message}` }], isError: true };
    }
  },
);

async function main() {
  const transport = new StdioServerTransport();
  await server.connect(transport);
  console.error(`[trae-video-mcp] stdio 已启动，后端：${SERVER_URL}`);
}

main().catch((e) => {
  console.error("[trae-video-mcp] 启动失败：", e);
  process.exit(1);
});
