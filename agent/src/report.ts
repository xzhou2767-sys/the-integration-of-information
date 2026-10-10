/** 报告渲染：markdown 证据报告（seg#/frame# 可引用编号，对齐方向 A 的证据包思想）。 */
import type { AnalyzeResult } from "./client.js";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

/** 秒 → mm:ss（超过 1 小时用 h:mm:ss）。 */
export function fmtTime(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(r)}` : `${pad(m)}:${pad(r)}`;
}

/** djb2 哈希前 8 位十六进制，用于文件名防碰撞。 */
function hash8(s: string): string {
  let h = 5381;
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
  return (h >>> 0).toString(16).padStart(8, "0");
}

/** URL → 文件名安全 slug（主机名 + 哈希），不用标题（含非法字符风险）。 */
export function slugOf(url: string): string {
  let host = "link";
  try {
    host = new URL(url).hostname.replace(/\./g, "-");
  } catch {
    /* 非 URL 字符串兜底 */
  }
  return `${host}-${hash8(url)}`;
}

export function localDate(d = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function localStamp(d = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${localDate(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** 默认报告目录：<项目根>/agent/reports/<日期>/（基于 import.meta.url，与运行目录无关）。 */
export function defaultReportDir(date = localDate()): string {
  return fileURLToPath(new URL(`../reports/${date}/`, import.meta.url));
}

/** 默认 watch 状态文件：agent/.watch-state.json。 */
export function defaultStatePath(): string {
  return fileURLToPath(new URL("../.watch-state.json", import.meta.url));
}

/** 默认 watch 清单：agent/watch.json。 */
export function defaultWatchConfigPath(): string {
  return fileURLToPath(new URL("../watch.json", import.meta.url));
}

export function writeReport(dir: string, slug: string, markdown: string): string {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${slug}.md`);
  fs.writeFileSync(file, markdown, "utf8");
  return file;
}

function metaTable(r: AnalyzeResult, url: string, detail: string, generatedAt: string): string {
  const source = r.usedSubtitles ? "platform_or_embedded_caption（平台/内嵌字幕，跳过 ASR）" : "asr（语音识别）";
  const visual = detail === "brief" ? "brief（0 帧，零视觉成本）" : detail === "standard" ? "standard（3 帧视觉描述）" : `detailed（${r.keyFrames.length} 帧 + OCR）`;
  return [
    "| 项 | 值 |",
    "|---|---|",
    `| 来源 URL | ${url} |`,
    `| 标题 | ${r.title} |`,
    `| 语言 / 时长 | ${r.language} / ${r.durationSec}s（${fmtTime(r.durationSec)}） |`,
    `| 转录来源 | ${source} |`,
    `| 成本档位 | ${visual} |`,
    `| 生成时间 | ${generatedAt} |`,
  ].join("\n");
}

/** 证据边界声明（诚实标注：稀疏抽样、固定流程智能体、服务端全管线成本）。 */
const EVIDENCE_BOUNDARY = [
  "## 证据边界",
  "",
  "- 视觉证据为**稀疏抽样**（sparse），不构成全片覆盖证明；转录字幕最后结束时间不等于视频时长。",
  "- AI 总结/视觉描述为模型生成内容，与转录原文分开呈现，不冒充原始事实。",
  "- 当前服务端为 REST 单端点全管线：本次调用会下载完整媒体并执行转录（字幕优先，ASR 兜底）；成本档位只决定视觉阶段取舍。",
  "- 报告由固定流程智能体生成（无自主 LLM 规划）；需要自主规划请经 MCP 宿主（Claude Code 等）直接驱动 trae-video-mcp 工具。",
].join("\n");

/** 单视频简报：AI 总结 + 可引用转录时间轴（seg#）+ 帧证据（frame#）。 */
export function renderBrief(r: AnalyzeResult, url: string, detail: string): string {
  const lines: string[] = [`# 视频简报：${r.title}`, "", metaTable(r, url, detail, localStamp()), "", "## 一、AI 总结", "", r.summary || "（服务端未返回总结）", "", "## 二、转录时间轴（seg 引用）", ""];
  if (r.segments.length === 0) {
    lines.push("> 无可用转录片段（可能无语音/无字幕且 ASR 失败）。");
  } else {
    r.segments.forEach((seg, i) => {
      lines.push(`- \`seg#${i + 1}\` [${fmtTime(seg.start)}-${fmtTime(seg.end)}] ${seg.text}`);
    });
  }
  lines.push("", "## 三、关键帧证据（frame 引用）", "");
  if (r.keyFrames.length === 0) {
    lines.push("> 本档位无帧证据（brief 档零视觉成本）。");
  } else {
    r.keyFrames.forEach((f, i) => {
      const parts = [`\`frame#${i + 1}\` @${fmtTime(f.timestamp)}`];
      if (f.description) parts.push(`视觉描述：${f.description}`);
      if (f.ocrText) parts.push(`OCR：${f.ocrText}`);
      if (f.imageBase64) parts.push("（含 JPEG base64，见原始 JSON）");
      lines.push(`- ${parts.join("｜")}`);
    });
  }
  lines.push("", EVIDENCE_BOUNDARY, "");
  return lines.join("\n");
}

/** 帧证据报告（get_frames 结果）。 */
export function renderFrames(r: AnalyzeResult, url: string, maxFrames: number, includeImages: boolean): string {
  const lines: string[] = [
    `# 帧证据报告：${r.title}`,
    "",
    "| 项 | 值 |",
    "|---|---|",
    `| 来源 URL | ${url} |`,
    `| 时长 | ${r.durationSec}s（${fmtTime(r.durationSec)}） |`,
    `| 帧数 | ${r.keyFrames.length}（请求上限 ${maxFrames}） |`,
    `| 帧图 | ${includeImages ? "含 JPEG base64（存伴生 .json）" : "不含（仅文本证据）"} |`,
    `| 生成时间 | ${localStamp()} |`,
    "",
    "## 帧清单（frame 引用）",
    "",
  ];
  r.keyFrames.forEach((f, i) => {
    const parts = [`\`frame#${i + 1}\` @${fmtTime(f.timestamp)}`];
    if (f.description) parts.push(`视觉描述：${f.description}`);
    if (f.ocrText) parts.push(`OCR：${f.ocrText}`);
    lines.push(`- ${parts.join("｜")}`);
  });
  lines.push("", EVIDENCE_BOUNDARY, "");
  return lines.join("\n");
}
