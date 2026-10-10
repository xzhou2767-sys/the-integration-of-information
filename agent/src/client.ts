/**
 * MCP 宿主封装：以 stdio 客户端身份连接 trae-video-mcp（协议适配层），
 * 把三个视频工具暴露为类型化 API。分层：本应用（编排/报告）→ MCP → 服务端 REST。
 */
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import path from "node:path";

/** 服务端响应契约（VideoAnalyzeResponse，经 MCP 层筛选后的形状）。 */
export interface Segment { start: number; end: number; text: string }
export interface KeyFrame {
  timestamp: number;
  description?: string | null;
  ocrText?: string | null;
  imageBase64?: string | null;
}
export interface AnalyzeResult {
  title: string;
  summary: string;
  transcript: string;
  language: string;
  durationSec: number;
  segments: Segment[];
  keyFrames: KeyFrame[];
  usedSubtitles: boolean;
}

/** 定位 trae-video-mcp 入口：TRAE_MCP_ENTRY 可覆盖；默认 <项目根>/mcp/dist/index.js。 */
export function resolveMcpEntry(): string {
  const override = process.env.TRAE_MCP_ENTRY;
  if (override) return path.resolve(override);
  // 本文件位于 <项目根>/agent/dist/client.js → 上两级到项目根，再进 mcp/dist。
  return fileURLToPath(new URL("../../mcp/dist/index.js", import.meta.url));
}

function cleanEnv(): Record<string, string> {
  return Object.fromEntries(
    Object.entries(process.env).filter((e): e is [string, string] => typeof e[1] === "string"),
  );
}

/** 单次工具调用超时：服务端 30 分钟总期限 + MCP 层 fetch 10 分钟，客户端放宽到 16 分钟覆盖排队。 */
const TOOL_TIMEOUT_MS = 16 * 60_000;

export class VideoMcpClient {
  private client!: Client;

  constructor(private serverUrl: string) {}

  /** 连接并完成 MCP 握手，返回可用工具名清单（供 check 命令与断言）。 */
  async connect(): Promise<string[]> {
    const env = cleanEnv();
    env.TRAE_VIDEO_SERVER = this.serverUrl;
    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [resolveMcpEntry()],
      env,
    });
    this.client = new Client({ name: "trae-video-agent", version: "0.1.0" });
    await this.client.connect(transport);
    const tools = await this.client.listTools();
    return tools.tools.map((t) => t.name);
  }

  /** 调用工具并解析首个文本内容块为 JSON；isError 时抛出可读错误。 */
  async callTool<T>(name: string, args: Record<string, unknown>): Promise<T> {
    const res = await this.client.callTool({ name, arguments: args }, undefined, {
      timeout: TOOL_TIMEOUT_MS,
    });
    const content = (res.content ?? []) as Array<{ type: string; text?: string }>;
    const text = content.find((c) => c.type === "text")?.text ?? "";
    if (res.isError) throw new Error(`工具 ${name} 失败：${text.slice(0, 500)}`);
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new Error(`工具 ${name} 返回非 JSON：${text.slice(0, 200)}`);
    }
  }

  async close(): Promise<void> {
    await this.client.close();
  }
}

/** 全管线分析（brief 档零视觉成本；detailed 档含 OCR/帧图）。 */
export function analyze(
  c: VideoMcpClient,
  url: string,
  opts: { detail: "brief" | "standard" | "detailed"; maxFrames?: number; includeImages?: boolean },
): Promise<AnalyzeResult> {
  return c.callTool<AnalyzeResult>("analyze_video", {
    url,
    detail: opts.detail,
    ...(opts.detail === "detailed" ? { maxFrames: opts.maxFrames ?? 5 } : {}),
    includeImages: opts.includeImages ?? false,
  });
}

/** 关键帧提取（detailed 档：视觉描述 + OCR）。 */
export function frames(
  c: VideoMcpClient,
  url: string,
  opts: { maxFrames?: number; includeImages?: boolean },
): Promise<AnalyzeResult> {
  return c.callTool<AnalyzeResult>("get_frames", {
    url,
    maxFrames: opts.maxFrames ?? 5,
    includeImages: opts.includeImages ?? false,
  });
}
