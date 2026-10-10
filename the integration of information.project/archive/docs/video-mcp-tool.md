# 视频能力方向 B：MCP 工具工程方案 v2.0

> 版本：2.0｜编制日期：2026-09-18｜状态：设计定稿，待实施（非上线证明）
> 依据：《视频内容提取三方向改进实施方案》1.0（2026-09-18，外部输入）＋ `docs/PROJECT_SYSTEM.md`（决策核心 V2 / P0 修复后现状）
> 取代 v1.0 设计稿。v1 中"只需协议壳即可完整落地""`Semaphore(1)` 并发闸门天然防宿主滥用"等表述按方案 §13 要求删除；本文补齐阶段拆分、长任务、官方 SDK 版本锁定与真实宿主验证。

## 一、目标与非目标

**目标**：把视频信息提取能力封装为 MCP 工具，供 Claude Code / Cursor / Codex 等 Agent 宿主直接调用：
1. REST 先拆能力（任务接口），MCP 只做协议适配——**不复制下载、转录业务逻辑**；
2. 工具按需调用内核阶段，不重复运行全管线（`get_metadata` 不触发 ASR/视觉）；
3. 业务长任务先返回 taskId，由 `get_task` 查询，不依赖宿主支持同一种长任务扩展；
4. 服务端鉴权、授权、URL 防护、配额与取消对 REST/MCP **一致生效**。

**非目标**：不手写不完整的 JSON-RPC 壳；不承诺"支持任意 URL"（登录/DRM/区域限制返回明确错误）；npm 公共发布不是本期验收前提。

## 二、当前实现（PROJECT_SYSTEM.md 现状，均为已验证事实）

| 环节 | 现状（v2.0 基线，含 2026-09-20 MCP v0.1.0 落地） | MCP 化差距 |
|---|---|---|
| 接入形态 | `POST /api/video/analyze` 单一大端点（同步阻塞，旧客户端在用）；**MCP 适配版 v0.1.0 已落地**（`mcp/` 目录，官方 SDK 1.30.0 stdio，端到端实测通过） | 无任务接口（提交/查询/取消）、无产物端点 |
| 工具粒度 | 服务端单端点全量返回；MCP 层拆 `analyze_video/get_transcript/get_frames` 三工具（响应组装层筛选） | `get_metadata`（零成本）/`analyze_moment`/`get_task`/`cancel_task` 未提供（依赖服务端任务接口） |
| 字段筛选 | 无（`summaryLevel` + `describeFrames` 布尔开关）；MCP 层裁剪响应字段但**服务端仍执行全管线**：`get_transcript` 不触发视觉却仍跑中文总结后丢弃（B03 残留）；`get_frames` 仍执行转录+视觉+OCR+总结 | 无 `fields` 依赖计算，按需阶段提取待 AnalysisPlanner |
| 时间窗 | 无 | 无 `startSec/endSec` |
| 缓存 | **视频分析链路无缓存复用**——同 URL 重复调用全量重跑（10s 样本两次实测均 ~145s） | 无显式阶段缓存控制、无 `forceRefresh` 边界定义 |
| 任务期限 | **30 分钟总期限已上线**（2026-09-18 P0：`withTimeout`，超时返回受限原因不再无限占闸门） | 同步接口挂 30 分钟不可接受，需异步任务化 |
| 并发控制 | `Semaphore(1)` 闸门 + **`tryAcquire()` 忙时直接拒绝（无等待队列——拒绝而非排队，无 429 反压）**；whisper/ffmpeg 内存约束 | 防滥用需"信号量 + 有界队列 + 429 反压 + 配额"组合，当前无队列无反压 |
| 可观测 | `/api/debug/metrics`（含 decision 块：V2 开关+来源观测） | 无任务级阶段/费用计量 |
| 图片交付 | 帧图以 JSON 字符串内 base64 返回（`includeImages`），**尚非 MCP 原生图像内容块**；`standard+includeImages` 无法取得被服务端省略的图（仅 `detailed` 回传） | 证据目标：授权产物端点按需取图 + MCP 图像内容块 |
| 来源支持 | 服务端下载器为 `http.get(url)`——**仅 HTTP(S) 媒体直链**；YouTube/Bilibili 平台页解析器未实现 | 平台页支持在真实解析实现及样本验证完成前列为待实现，不宣称 |

**当前三工具适配版 / 目标七工具任务版对照**（能力声明与实际统一）：

| 维度 | 当前 v0.1.0（三工具适配版） | 目标（七工具任务版，M5） |
|---|---|---|
| 工具 | analyze_video / get_transcript / get_frames | + get_metadata / analyze_moment / get_task / cancel_task |
| 调用形态 | 同步转调旧端点，阻塞至完成（10s 样本 ~145s） | 提交任务返回 taskId，异步查询/取消 |
| 阶段执行 | 服务端全管线（多余阶段被 MCP 层丢弃） | AnalysisPlanner 按需阶段（`get_metadata` 零下载） |
| 缓存 | 无（重复调用全量重跑） | 阶段缓存 + `forceRefresh` 旁路 |
| 来源 | HTTP(S) 直链 | + YouTube/Bilibili 平台页（解析器落地并样本验证后） |

## 三、差距对照（方案 §2 阻断项映射）

| 编号 | 问题 | 对本方向的影响 | 本期动作 |
|---|---|---|---|
| B02 | 所有档位先下载完整视频 | `get_metadata` 也会触发全片下载（当前未提供该工具） | SourceResolver 字幕前置 + 音频优先 |
| B03 | 默认抽帧无消费者不跳过 | **MCP v0.1.0 实测残留**：`get_transcript` 零视觉但服务端仍跑中文总结后丢弃；`get_frames` 仍执行转录+视觉+OCR+总结全管线 | AnalysisPlanner 按需阶段；验收按阶段调用次数计量，不以工具名称/"请求成功"证明按需提取 |
| B06 | 等待进程退出后才读输出 | 长任务卡死不可观测 | ProcessRunner 并发消费 + 进程树终止 |
| B09 | 帧数无可靠上限 | `get_frames` 失控 | 参数校验 + 预算 + 全局硬上限 12 帧（MCP 层已 clamp maxFrames ≤ 8） |

## 四、REST 任务接口（先拆能力，再做协议适配）

| 接口 | 用途 |
|---|---|
| `POST /api/video/tasks` | 提交规范化任务，返回 taskId；缓存命中可直接返回完成状态 |
| `GET /api/video/tasks/{id}` | 状态、进度、错误、费用和结果清单 |
| `DELETE /api/video/tasks/{id}` | 幂等取消任务，终止子进程 |
| `GET /api/video/tasks/{id}/evidence` | 分页获取证据，支持字段筛选 |
| `GET /api/video/tasks/{id}/artifacts/{artifactId}` | 授权获取帧和导出物 |

- 旧 `POST /api/video/analyze` 暂保留为适配入口（旧客户端 `summaryLevel` → `detail`、`describeFrames` → `enableVision` 映射），不强制迁移；同步接口保留超时上限，长任务推荐新版接口。
- 规范化请求：`mode: summary|evidence|subtitles|editing`、`detail: brief|standard|detailed`、`fields`、`startSec/endSec`、`maxFrames`、`includeImages`、`enableOcr/enableVision`、`targetLanguage`、`forceRefresh`。预算与服务端硬限制 > 显式参数 > 档位默认；矛盾显式参数返回 400 不静默覆盖。
- 网络防护是 URL 抓取接口的组成部分：仅允许 HTTP(S)，校验 DNS 解析与每次重定向目标，拒绝回环/私网/链路本地/元数据地址；FFmpeg 不直接消费任意远程 URL，优先处理受控本地文件。

**任务接口契约（可直接实现版）**：

`POST /api/video/tasks`：
- 请求体：规范化请求全字段（上列）。`startSec/endSec` 必须有限、非负、前小于后；已知时长时不得超出视频长度（400 `invalid_time_window`）。
- 响应：`201 Created` `{taskId, status: "queued", cacheHit: false}`；请求参数命中**未过期**阶段缓存时直接返回 `200 OK` + 完成态结果（`cacheHit: true`）；参数校验失败 `400`（`error` 码：`invalid_url` / `invalid_time_window` / `param_conflict`，如 brief 档显式要求非零帧）；并发闸门忙且队列满 `429`（`retryAfterSec`）；服务端校验在**任何下载/重计算之前**。
- taskId 服务端生成（ULID），128 字符上限，幂等键 `Idempotency-Key` 头可选：同键重复提交返回既有任务。

`GET /api/video/tasks/{id}`：
- `200`：`{taskId, status, stages: [{stage, state, startedEpochMs, endedEpochMs, costEstimate}], resultRef?, error?}`；未知 id `404`。
- 状态机：`queued → running → succeeded | partial | failed | cancelled`；`partial` 定义 = ≥1 个必需阶段成功但有阶段失败/超时/预算截断，必须携带 `completedStages` + `failedStages` + `warnings`；产物过期后查询仍 `200` 返回元数据但 `resultRef.expired: true`（不返 404，区分"任务不存在"与"产物过期"）。
- 转换规则：`queued/running` 期间 `DELETE` → `cancelled`（进程树终止，临时文件回收登记）；终态（succeeded/partial/failed/cancelled）不可逆；`running` 超过单阶段超时 → 该阶段 `failed`，任务进 `partial` 或 `failed`（按是否有可用产物）。

`GET /api/video/tasks/{id}/evidence?cursor=&fields=&limit=`：
- 分页：cursor 游标（服务端生成 opaq 标记，`limit` 默认 50、上限 200，超出截断并在响应 `nextCursor` 为 null 时终止）；单页响应体上限 **1 MB**（超出降 limit 并提示）。
- `fields` 白名单筛选（segments/frames/ocr/summary…），未知字段 400。

`GET /api/video/tasks/{id}/artifacts/{artifactId}`：
- 授权校验（任务所有者/token）；返回 `Content-Type` + `{artifactId, bytes, expiresEpochMs}` 头，`Expires` 默认 **24 小时**（与共享内核磁盘 TTL 一致，可配置）；过期 `410 Gone`（`artifact_expired`），任务被取消 `404`。

**共享在途任务取消语义**：同阶段键在途任务被多个调用者共享时——**任一调用者 `DELETE` 仅登记其自身请求取消，不终止共享任务执行**；任务只由服务端超时/预算/所有调用者全部取消（引用计数归零）终止。等待同一结果的调用者经 `GET /tasks/{id}` 观察到 `cancelled` 时得到 `error.code=shared_task_cancelled` + 可重试标记。

## 五、工具契约

| 工具 | 主要参数 | 必需阶段 |
|---|---|---|
| `get_metadata` | url | 来源解析、元数据 |
| `get_transcript` | url、startSec、endSec、language | 字幕/音轨/ASR，**无视觉** |
| `get_frames` | url、startSec、endSec、maxFrames | 视频、取帧、去重；**默认无 ASR** |
| `analyze_video` | url、detail、fields | 按执行计划组合 |
| `analyze_moment` | url、startSec、endSec、maxFrames | 局部转录与帧证据 |
| `get_task` | taskId | 查询任务或分批取结果 |
| `cancel_task` | taskId | 取消及资源释放 |

规则：
- 每个工具明确输入 JSON Schema、默认值、边界与输出结构；未知字段、无效时间窗（NaN/Infinity/负数/超时长）和超限帧数在**重计算前**可预测地报错。
- `brief` 默认 0 帧（显式要求非零帧返回参数冲突）；`standard` ≤3；`detailed` ≤8；全局硬上限 12 帧。独立 `get_frames` 工具使用自己的合法帧预算（仍受全局上限约束）。
- 结果使用结构化字段并按需返回文本/图像内容块；限制单次返回大小，长转录分页。
- `forceRefresh` 跳过对应阶段缓存但仍受预算限制；不能映射到与视频证据无关的普通摘要缓存。
- stdio 的 stdout 只输出协议消息，日志走 stderr；凭据经环境配置，不出现在工具结果与日志中。

## 六、协议接入（v1 路径 A/B 表作废，改为 SDK 约束）

- **首版使用 TypeScript 官方 MCP SDK + stdio**，复用服务端 REST；SDK 与协议版本在实施时核验并锁定（已查阅的固定参考：MCP 2025-06-18 传输规范，实施时以锁定版本为准，不依赖可变主分支）。
- 远程 HTTP 接入若需要，单独按选定版本的 Streamable HTTP 规范实现。
- v1 的"Node 单文件 `index.mjs` 手写 JSON-RPC"与"发布 npm 包"从清单移除：官方 SDK 取代手写壳，发布形式（本地启动包优先）由实施时验证决定。

## 七、共享依赖

三方向共用内核（SourceResolver / AnalysisPlanner / ProcessRunner / TranscriptService / EvidenceService / ArtifactCache / BudgetService），详见方向 A 文档 §六。MCP Adapter 职责边界：schema 校验、工具路由、内容块与错误映射——**不复制业务逻辑**。

注释时间线（v1 提出的"何时发生什么"结构化输出）保留为 `analyze_video` 输出的组装项：转录段 + 帧 + OCR 按时间轴合并排序，纯响应组装改动；**不再描述为"零额外成本已齐备"**——依赖 M2 时间轴校验（B07）落地后才可靠。

## 八、缓存和费用

- 阶段缓存键含 `sourceId+sourceVersion+timeRange+stage+language+modelVersion+promptVersion+paramsHash`；临时签名 URL 不作为稳定来源 ID。
- 视频重计算并发初值 1（3.7G VPS 约束）；忙碌请求排入**有界队列**，超容量返回 429 + 重试提示——并发保护从"单信号量"升级为"信号量 + 有界队列 + 429 反压 + 配额"。
- 费用：每次调用记录实际 provider/model/usage（输入输出 token、帧数、像素量、下载字节、CPU 时间、峰值 RSS、缓存命中）；usage 不可得标"估算"。

## 九、任务清单（映射方案 §9：M5，依赖 M2 内核 + M4 证据契约）

| 任务 | 内容 | 完成定义 |
|---|---|---|
| B-1 | REST 任务接口五端点 + 旧端点兼容映射 | 幂等取消杀进程树；旧客户端回归通过 |
| B-2 | 官方 SDK MCP server：七工具 + JSON Schema | Inspector 完成初始化/发现/调用/错误/断连检查 |
| B-3 | 长任务与错误结构 | 401/429/预算不足/部分结果/超时/取消均有稳定错误结构 |
| B-4 | 双宿主端到端验证 | ≥2 个实际宿主（如 Claude Code + Cursor）独立验证 |
| B-5 | 安装与发布物 | 锁定依赖、安装说明、本地启动包 |

## 十、验收数据

- `get_metadata` 的 ASR/视觉调用次数均为 0；`get_frames` 默认 ASR 次数为 0（B03 回归，按阶段调用次数计量，不以工具名称/"请求成功"判定）。
- 相同来源与参数的第二次调用命中阶段缓存，不重新转录（用例：无强制刷新时不重复 ASR/视觉/翻译）。
- 字幕存在只取文本的样本：视频媒体下载字节=0（B02 回归）。
- 大输出子进程：≥2MB JSON 输出正常完成，stderr 不污染结果（B06 回归）。
- 超时/取消：规定超时后进程树消失、任务状态正确、临时文件可回收。

**B 方向评分表**（沿用方案 §11 五维度；共享内核证据可引用同一份报告）：

| 维度 | 满分 | 目标最低分 | B 方向证据位置 |
|---|---:|---:|---|
| 需求覆盖 | 25 | 22 | 七工具逐项可演示记录 + Inspector 录制（B-2/B-4） |
| 技术正确性 | 25 | 22 | 任务状态机用例（含 partial/取消/过期转换）、B02/B03/B06/B09 回归（§十） |
| 低成本设计 | 20 | 17 | 阶段调用次数统计（get_metadata=0）、缓存命中用例、费用计量 |
| 方案可落地性 | 15 | 13 | 本节契约可直接实现（状态码/状态机/分页/取消语义已定义）、安装说明与本地启动包（B-5） |
| 验证与报告可信度 | 15 | 13 | 双宿主端到端记录（B-4）、锁定依赖清单、错误结构测试 |
| **合计** | **100** | **87** | 全部 ≥ 对应目标最低分且 B 方向总分 ≥ 85 才放行 |

## 十一、限制与发布回滚

- 限制：stdio 单机形态；远程接入待单独立项；**当前来源仅 HTTP(S) 媒体直链**（服务端下载器 `http.get(url)`），YouTube/Bilibili 平台页解析器落地并样本验证后才开放。
- 功能开关：`mcpEnabled`（ConfigStore Snapshot + 环境变量 + 默认 false，同 `decisionCoreV2` 灰度先例）；依赖 `videoPlannerV2`。
- 回滚：关闭 `mcpEnabled` 停止 MCP 入口；REST 任务接口独立灰度，已有任务保留状态与产物。

## 附：对标参考

- [mcp-video-analyzer](https://github.com/guimatheus92/mcp-video-analyzer)：按需工具、时间窗、字段选择和阶段复用思想来源（借鉴设计；版本以实施时锁定为准）。
- [MCP 2025-06-18 传输规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)：已查阅的固定版本规范，不代表实施时最新。
