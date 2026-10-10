# 视频能力里程碑定义（M0–M7）

> 建立依据：整改方案 `C12`。`PROJECT_SYSTEM.md` §10.1 声称三份方向文档「映射 M0–M7」，
> 但实测三份文档**仅引用 M0/M2/M3/M4/M6**，且 **M1、M7 全文无任何定义处**。
> 本文件是里程碑编号的唯一事实源；三份方向文档应改为引用本文件。
>
> **状态：草案，待确认。** 下表由三份方向文档的「共享依赖」节与任务清单反推得到
> （依据见每行「推导来源」），非原始方案原文。请逐项确认或修正后，删除本提示并
> 将状态改为「已确认」。

## 一、里程碑表

| 编号 | 名称 | 内容 | 产出物 | 推导来源 |
|---|---|---|---|---|
| **M0** | 基线与对标 | 建立改进前的成本/时延基线，作为「≥30% 成本下降」一类结论的比较基准 | 基线测量报告（同集对照） | `video-agent-watch.md` §一「以 M0 基线的同集对比为准」、§七；`video-agent-watch.md` §八「以 M0 基线为准」 |
| **M1** | 来源解析 | 稳定来源 ID、字幕清单（人工字幕优先于自动字幕）、时长探测、URL 防护；**首批仅 HTTP(S) 媒体直链**，平台页解析（YouTube/Bilibili）列为待实现 | `SourceResolver` + `docs` 中的来源支持矩阵 | 三份文档 §六 共享依赖均列 `SourceResolver` 为其首项；`video-mcp-tool.md` §五 `get_metadata` 依赖「来源解析、元数据」 |
| **M2** | 共享内核 | 阶段拆分与调度：`AnalysisPlanner`（按需阶段）、`ProcessRunner`（stdout/stderr 并发消费、进程树终止）、`ArtifactCache`（阶段级缓存与键）、`BudgetService`（调用前预留预算）、`TranscriptService`（`transcriptSource` 归一化、真实时长） | 上述五个内核组件 + 阶段键规范 | 三份文档 §六 共享依赖表；`video-agent-watch.md` §八「依赖 M2（共享内核）完成后推进 M4」；`video-mcp-tool.md` §九「M5，依赖 M2 内核 + M4 证据契约」 |
| **M3** | 概要翻译闭环 | `ContentItem` 概要字段与状态机（translated / generated_from_content / existing_chinese / waiting_for_content）、内容键缓存（修复同 ID 旧译文复用）、四分支处理顺序 | C-1~C-4 任务 | `video-translation-editing.md` §三 B01 修复四分支；§八「M3 概要翻译闭环」 |
| **M4** | 证据契约 | 证据包契约落地：`segmentId`/`frameId`/`artifactId`、`coverage` 覆盖标记、按需授权取图、时间轴校验（B07）；`EvidenceService` | A1~A4 任务 + 契约 DTO | `video-agent-watch.md` §四 契约、§八 A1–A4；`video-mcp-tool.md` §九「依赖 … M4 证据契约」 |
| **M5** | 任务接口与七工具 | REST 任务五端点（`tasks`/`tasks/{id}`/`DELETE`/`evidence`/`artifacts`）+ 官方 SDK MCP server 七工具 + 有界队列与 429 反压 | B-1~B-5 任务 | `video-mcp-tool.md` §四 五端点、§五 七工具、§九「M5」 |
| **M6** | 字幕与剪辑导出 | SRT/ASS 导出与结构校验、章节结构化、CMX 3600 有限子集 EDL、`ExportService` | C-5~C-7 任务 | `video-translation-editing.md` §四、§八「M6 字幕剪辑，M6 依赖 M2/M3/M4 契约」 |
| **M7** | 全量发布与灰度收敛 | `evidenceMode` / `mcpEnabled` / `summaryTranslation` / `editingExports` 四个开关的灰度收敛与全量、双宿主验证归档、安装物发布 | 发布记录 + 灰度报告 | 三份文档 §十一「发布与回滚」均定义各自开关与灰度顺序，但**无编号**；M7 为本文件补全（原方案未定义，待确认） |

## 二、依赖关系

```
M0 基线
 └─► M1 来源解析
      └─► M2 共享内核 ──┬─► M3 概要翻译闭环 ──┐
                        ├─► M4 证据契约 ─────┼─► M6 字幕与剪辑
                        └─► M5 任务接口 ─────┘
                                             └─► M7 全量发布
```

- M3 / M4 / M5 可并行，但**均以 M2 为前提**（阶段拆分不落地时，任务接口只是同步端点套壳，证据包无产物可取）。
- M6 依赖 M2 + M3 + M4（字幕时间轴来自 M2 的真实时长校正，译文关联来自 M3，导出端点挂在 M4 的产物机制上）。
- M7 是发布阶段，不产生新能力，只收敛开关与归档证据。

## 三、与三份方向文档的引用对照（整改后应替换为指向本文件）

| 文档 | 当前引用 | 应改 |
|---|---|---|
| `video-agent-watch.md` | §八「依赖：M2（共享内核）完成后推进 M4」；§七「以 M0 基线的同集对比为准」 | 引用本文件，不重复定义 |
| `video-mcp-tool.md` | §九「映射方案 §9：M5，依赖 M2 内核 + M4 证据契约」 | 同上 |
| `video-translation-editing.md` | §八「映射方案 §9：M3 概要翻译闭环 + M6 字幕剪辑，M6 依赖 M2/M3/M4 契约」 | 同上 |
| `PROJECT_SYSTEM.md` | §10.1「映射 M0–M7 里程碑」 | 改为「里程碑定义见 `docs/video-milestones.md`」 |

## 四、待确认项

1. **上表是否为原始《视频内容提取三方向改进实施方案》的 M0–M7 原义？** 若原方案另有定义（外部输入文件不在本仓库内），请以原义为准覆盖本草案。
2. **M1 是否应包含平台页解析**（YouTube/Bilibili）？三份文档 §十 均声明其为待实现，故本草案把平台页放在 M1 之外。
3. **M7 是否需要一个独立编号**？三份文档的发布节均无编号，本草案为闭环补入。
4. **是否有 M0 之前的前置里程碑**（如「方案定稿」）未纳入编号体系？

---

*本文件由 2026-09-25 整改方案（C12）建立；确认后请同步更新 `PROJECT_SYSTEM.md` §10.1 与三份方向文档的引用，并把本提示替换为状态行。*
