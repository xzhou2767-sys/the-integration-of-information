#!/usr/bin/env node
/**
 * trae-video-agent：agent 版视频处理应用（MCP 宿主 + 固定流程智能体）。
 * 分层：本 CLI（编排/监控/报告）→ trae-video-mcp（协议适配，stdio）→ 服务端 REST。
 * 诚实边界：无自主 LLM 规划；仅 HTTP(S) 媒体直链；服务端全管线成本（brief 档零视觉）。
 *
 * 命令：
 *   check                              MCP 链路自检（initialize + tools/list，秒级）
 *   brief <url> [--detail=...] [--out=dir]   单视频简报（默认 brief 档，零视觉成本）
 *   frames <url> [--max=5] [--images] [--out=dir]  帧证据报告
 *   watch [--config=agent/watch.json] [--out=dir]  监控清单批处理（state 去重 + 自动汇总）
 *   digest [--out=dir]                 手动重出汇总报告
 *   help
 *
 * 环境变量：TRAE_VIDEO_SERVER（默认 http://127.0.0.1:8765）；TRAE_MCP_ENTRY（默认 ../mcp/dist/index.js）。
 */
import { VideoMcpClient, analyze, frames } from "./client.js";
import { defaultReportDir, defaultWatchConfigPath, renderBrief, renderFrames, slugOf, writeReport } from "./report.js";
import { loadState, runWatch, writeDigest } from "./tasks.js";
import path from "node:path";

const SERVER = process.env.TRAE_VIDEO_SERVER ?? "http://127.0.0.1:8765";

function help(): string {
  return [
    "trae-video-agent v0.1.0 —— agent 版视频处理应用（MCP 宿主）",
    "",
    "用法：node dist/index.js <命令> [参数]",
    "",
    "  check                              MCP 链路自检（秒级，不发起真实分析）",
    "  brief <url> [--detail=brief|standard|detailed] [--out=dir]",
    "                                     单视频简报：AI 总结 + seg# 转录时间轴 + frame# 帧证据",
    "  frames <url> [--max=1-8] [--images] [--out=dir]",
    "                                     帧证据报告：时间戳 + 视觉描述 + OCR",
    "  watch [--config=path] [--out=dir]  监控清单批处理（已处理 URL 经 state 去重跳过，自动更新汇总）",
    "  digest [--out=dir]                 手动重出已处理视频的汇总报告",
    "  help                               本帮助",
    "",
    `后端：${SERVER}（环境变量 TRAE_VIDEO_SERVER 可覆盖）`,
    "默认报告目录：agent/reports/<日期>/；默认清单：agent/watch.json；状态：agent/.watch-state.json",
  ].join("\n");
}

/** 解析 --key=value / --flag 形式参数。 */
function parseArgs(argv: string[]): { positional: string[]; flags: Record<string, string | boolean> } {
  const positional: string[] = [];
  const flags: Record<string, string | boolean> = {};
  for (const a of argv) {
    if (a.startsWith("--")) {
      const eq = a.indexOf("=");
      if (eq > 0) flags[a.slice(2, eq)] = a.slice(eq + 1);
      else flags[a.slice(2)] = true;
    } else positional.push(a);
  }
  return { positional, flags };
}

const DETAIL_LEVELS = ["brief", "standard", "detailed"] as const;
type Detail = (typeof DETAIL_LEVELS)[number];

function asDetail(v: unknown): Detail {
  const s = String(v ?? "brief");
  if (!(DETAIL_LEVELS as readonly string[]).includes(s)) {
    throw new Error(`--detail 只接受 ${DETAIL_LEVELS.join("/")}，收到：${s}`);
  }
  return s as Detail;
}

async function main(): Promise<number> {
  const [cmd, ...rest] = process.argv.slice(2);
  const { positional, flags } = parseArgs(rest);
  const outDir = typeof flags.out === "string" ? path.resolve(flags.out) : undefined;

  if (!cmd || cmd === "help" || flags.help === true) {
    console.log(help());
    return 0;
  }

  if (cmd === "check") {
    const client = new VideoMcpClient(SERVER);
    try {
      const t0 = Date.now();
      const tools = await client.connect();
      console.log(`[1/2] MCP initialize ok（${((Date.now() - t0) / 1000).toFixed(1)}s）`);
      console.log(`[2/2] tools/list ok: ${tools.join(", ")}`);
      console.log(`AGENT_CHECK_DONE（后端：${SERVER}；本次未发起真实分析）`);
      return 0;
    } finally {
      await client.close().catch(() => {});
    }
  }

  const needUrl = (): string => {
    const url = positional[0];
    if (!url) throw new Error(`缺少 <url> 参数。用法：node dist/index.js ${cmd} <url> [flags]`);
    return url;
  };

  if (cmd === "brief") {
    const url = needUrl();
    const detail = asDetail(flags.detail);
    const client = new VideoMcpClient(SERVER);
    try {
      await client.connect();
      console.log(`分析中（detail=${detail}）——服务端全管线，brief 档约 1-3 分钟，请稍候…`);
      const result = await analyze(client, url, { detail });
      const file = writeReport(outDir ?? defaultReportDir(), slugOf(url), renderBrief(result, url, detail));
      console.log(`完成：${file}`);
      console.log(`title=${result.title} lang=${result.language} dur=${result.durationSec}s segs=${result.segments.length} frames=${result.keyFrames.length}`);
      return 0;
    } finally {
      await client.close().catch(() => {});
    }
  }

  if (cmd === "frames") {
    const url = needUrl();
    const maxFrames = Math.min(8, Math.max(1, Number(flags.max ?? 5) || 5));
    const includeImages = flags.images === true;
    const client = new VideoMcpClient(SERVER);
    try {
      await client.connect();
      console.log(`取帧中（max=${maxFrames} images=${includeImages}）——服务端全管线，请稍候…`);
      const result = await frames(client, url, { maxFrames, includeImages });
      const file = writeReport(outDir ?? defaultReportDir(), `${slugOf(url)}-frames`, renderFrames(result, url, maxFrames, includeImages));
      console.log(`完成：${file}`);
      console.log(`frames=${result.keyFrames.length}`);
      return 0;
    } finally {
      await client.close().catch(() => {});
    }
  }

  if (cmd === "watch") {
    const cfgPath = typeof flags.config === "string" ? path.resolve(flags.config) : defaultWatchConfigPath();
    const client = new VideoMcpClient(SERVER);
    try {
      await client.connect();
      const r = await runWatch(client, cfgPath, outDir);
      console.log(`WATCH_DONE fresh=${r.fresh} skipped=${r.skipped}${r.digestFile ? ` digest=${r.digestFile}` : "（无新增，未更新汇总）"}`);
      return 0;
    } finally {
      await client.close().catch(() => {});
    }
  }

  if (cmd === "digest") {
    const file = writeDigest(loadState(), outDir);
    console.log(`完成：${file}`);
    return 0;
  }

  console.error(`未知命令：${cmd}\n\n${help()}`);
  return 2;
}

main()
  .then((code) => process.exit(code))
  .catch((e: Error) => {
    console.error(`[trae-video-agent] 失败：${e.message}`);
    process.exit(1);
  });
