// MCP stdio 烟雾测试：initialize → tools/list → tools/call get_transcript
// 用法：node examples/smoke-test.mjs [视频URL]
// 环境变量：TRAE_VIDEO_SERVER（默认 http://127.0.0.1:8765）
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const SERVER = process.env.TRAE_VIDEO_SERVER ?? "http://127.0.0.1:8765";
const VIDEO_URL = process.argv[2] ?? "https://www.w3schools.com/html/mov_bbb.mp4";
/** SMOKE_LIST_ONLY=1 只验证协议（initialize + tools/list，不发起真实分析） */
const LIST_ONLY = process.env.SMOKE_LIST_ONLY === "1";

const proc = spawn(process.execPath, [fileURLToPath(new URL("../dist/index.js", import.meta.url))], {
  env: { ...process.env, TRAE_VIDEO_SERVER: SERVER },
  stdio: ["pipe", "pipe", "inherit"],
});

proc.on("error", (e) => { console.error("spawn failed:", e); process.exit(1); });

const send = (obj) => proc.stdin.write(JSON.stringify(obj) + "\n");
let buf = "";
const pending = new Map();
proc.stdout.on("data", (chunk) => {
  buf += chunk.toString("utf8");
  let idx;
  while ((idx = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, idx).trim();
    buf = buf.slice(idx + 1);
    if (!line) continue;
    const msg = JSON.parse(line);
    if (msg.id && pending.has(msg.id)) {
      pending.get(msg.id)(msg);
      pending.delete(msg.id);
    }
  }
});
const request = (id, method, params) =>
  new Promise((resolve) => { pending.set(id, resolve); send({ jsonrpc: "2.0", id, method, params }); });

const timeout = setTimeout(() => { console.error("TIMEOUT"); proc.kill(); process.exit(1); }, 600000);

try {
  const init = await request(1, "initialize", {
    protocolVersion: "2025-06-18",
    capabilities: {},
    clientInfo: { name: "smoke-test", version: "0.0.1" },
  });
  console.log(`[1/3] initialize ok: ${init.result.serverInfo.name} v${init.result.serverInfo.version}`);
  send({ jsonrpc: "2.0", method: "notifications/initialized" });

  const tools = await request(2, "tools/list", {});
  console.log(`[2/3] tools/list ok: ${tools.result.tools.map((t) => t.name).join(", ")}`);
  if (LIST_ONLY) { console.log("SMOKE_TEST_DONE (list-only)"); clearTimeout(timeout); proc.kill(); process.exit(0); }

  const t0 = Date.now();
  const res = await request(3, "tools/call", { name: "get_transcript", arguments: { url: VIDEO_URL } });
  const elapsed = ((Date.now() - t0) / 1000).toFixed(1);
  if (res.result?.isError) {
    console.log(`[3/3] tools/call get_transcript (${elapsed}s) ERROR: ${res.result.content?.[0]?.text?.slice(0, 300)}`);
    process.exitCode = 2;
  } else {
    const parsed = JSON.parse(res.result.content[0].text);
    console.log(`[3/3] tools/call get_transcript (${elapsed}s) ok:`);
    console.log(`      title=${parsed.title} lang=${parsed.language} duration=${parsed.durationSec}s source=${parsed.transcriptSource} segments=${parsed.segments.length}`);
  }
  console.log("SMOKE_TEST_DONE");
} finally {
  clearTimeout(timeout);
  proc.kill();
}
