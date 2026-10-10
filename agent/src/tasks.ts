/** 任务编排：watch（监控清单无人值守批处理）与 digest（已处理视频汇总）。 */
import fs from "node:fs";
import path from "node:path";
import { analyze, VideoMcpClient, type AnalyzeResult } from "./client.js";
import { defaultReportDir, defaultStatePath, localDate, renderBrief, slugOf, writeReport } from "./report.js";

export interface WatchEntry { url: string; note?: string }
export interface WatchConfig { detail?: "brief" | "standard" | "detailed"; videos: WatchEntry[] }
export interface StateRecord { title: string; report: string; at: string; detail: string; summaryExcerpt: string }
export interface WatchState { processed: Record<string, StateRecord> }

export function loadState(statePath = defaultStatePath()): WatchState {
  if (!fs.existsSync(statePath)) return { processed: {} };
  return JSON.parse(fs.readFileSync(statePath, "utf8")) as WatchState;
}

export function saveState(state: WatchState, statePath = defaultStatePath()): void {
  fs.writeFileSync(statePath, JSON.stringify(state, null, 2), "utf8");
}

export function loadWatchConfig(cfgPath: string): WatchConfig {
  if (!fs.existsSync(cfgPath)) {
    fs.writeFileSync(cfgPath, JSON.stringify({
      detail: "brief",
      videos: [{ url: "https://example.com/sample.mp4", note: "示例条目——替换为真实 HTTP(S) 媒体直链后重新运行 watch" }],
    } satisfies WatchConfig, null, 2), "utf8");
    throw new Error(`watch 清单不存在，已生成模板 ${cfgPath}——编辑后重新运行 watch`);
  }
  return JSON.parse(fs.readFileSync(cfgPath, "utf8")) as WatchConfig;
}

/** 生成单个简报报告并返回 { 文件路径, 分析结果 }。 */
export async function briefOne(
  client: VideoMcpClient,
  url: string,
  detail: "brief" | "standard" | "detailed",
  outDir?: string,
): Promise<{ file: string; result: AnalyzeResult }> {
  const result = await analyze(client, url, { detail });
  const dir = outDir ?? defaultReportDir();
  const file = writeReport(dir, slugOf(url), renderBrief(result, url, detail));
  return { file, result };
}

/** watch：对清单中未处理 URL 逐个生成简报；已处理跳过（state 去重，避免无缓存旁路下的重复全管线成本）。 */
export async function runWatch(
  client: VideoMcpClient,
  cfgPath: string,
  outDir?: string,
): Promise<{ fresh: number; skipped: number; digestFile?: string }> {
  const cfg = loadWatchConfig(cfgPath);
  const detail = cfg.detail ?? "brief";
  const statePath = defaultStatePath();
  const state = loadState(statePath);
  let fresh = 0;
  let skipped = 0;
  for (const entry of cfg.videos) {
    if (!entry?.url) continue;
    if (state.processed[entry.url]) {
      console.log(`跳过（已处理 ${state.processed[entry.url].at}）：${entry.url}`);
      skipped++;
      continue;
    }
    console.log(`处理中（${detail}）：${entry.url}${entry.note ? ` —— ${entry.note}` : ""}`);
    const { file, result } = await briefOne(client, entry.url, detail, outDir);
    state.processed[entry.url] = {
      title: result.title,
      report: path.relative(path.dirname(statePath), file),
      at: new Date().toISOString(),
      detail,
      summaryExcerpt: (result.summary || "").slice(0, 300),
    };
    saveState(state, statePath);
    console.log(`完成：${file}`);
    fresh++;
  }
  let digestFile: string | undefined;
  if (fresh > 0) {
    digestFile = writeDigest(state, outDir);
    console.log(`汇总已更新：${digestFile}`);
  }
  return { fresh, skipped, digestFile };
}

/** digest：把 state 中全部已处理视频合并为一份汇总报告。 */
export function writeDigest(state: WatchState, outDir?: string): string {
  const urls = Object.keys(state.processed);
  const lines: string[] = [
    "# 视频监控汇总（digest）",
    "",
    `生成时间：${new Date().toISOString()}｜已处理视频：${urls.length} 个`,
    "",
  ];
  if (urls.length === 0) lines.push("> 暂无已处理视频。");
  urls.forEach((url, i) => {
    const rec = state.processed[url];
    lines.push(
      `## ${i + 1}. ${rec.title || url}`,
      "",
      `- URL：${url}`,
      `- 处理档位：${rec.detail}｜时间：${rec.at}`,
      `- 简报：${rec.report}`,
      ...(rec.summaryExcerpt ? [`- 总结摘录：${rec.summaryExcerpt}${rec.summaryExcerpt.length >= 300 ? "…" : ""}`] : []),
      "",
    );
  });
  const dir = outDir ?? defaultReportDir();
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `digest-${localDate()}-${slugOf(`digest:${urls.length}:${Date.now()}`).split("-").pop()}.md`);
  fs.writeFileSync(file, lines.join("\n"), "utf8");
  return file;
}
