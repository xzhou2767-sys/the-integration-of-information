# 信息整合平台（Trae Info）全体系文档

> **文档定位**：本项目的唯一全体系工程文档，覆盖架构、能力、API、约定、演进路线。
> **维护约定**：任何代码变更、架构调整、功能上线、部署、技术结论产出后，**必须同步更新本文件对应章节**（更新流程见技能 `project-system-sync`）。
> **版本基准**：客户端 0.5.8（versionCode 15）｜服务端已部署于腾讯云 VPS｜服务端版本号规则见 §八
> **最后更新**：2026-10-10（启用 git 与 SDD 规范驱动开发体系；建立公开远程仓库；新增 backup/ 备份单元与 SDD 归档根索引；基础设施信息与凭据全量脱敏）
>
> **文档两分法**（2026-09-25 起生效，用于判定该改哪个数、该走什么流程）：
> 1. **可漂移快照** —— 类目/平台计数、文件清单、版本号、参数取值、持久化文件列表等**随版本自然变化**的事实。
>    允许随版本更新，更新时**直接改写数值**即可，无需设计决策；修改后应能由
>    `scripts/check-consistency.ps1` 或实测命令复核。文中以【可漂移快照】标注。
> 2. **规范性描述** —— 设计目标、能力边界、依赖方向、算法语义、工程铁律、验收判据。
>    变更须走**显式设计决策**（记录在案），不得静默漂移。文中以【规范性描述】标注。
>
> 未标注的章节默认为规范性描述；新增或修订章节时应补充标注。

---

## 一、项目定位

跨平台（Android / Desktop）信息聚合平台。云端服务以**模拟正常用户行为**为主采集多平台信息，
经清洗、去重、排序、AI 摘要后，通过 REST API 下发到客户端信息流；用户以自然语言描述偏好，
系统解析为关键词与展示模式实现个性化排序。

**核心设计原则**
1. 采集不依赖 API Key —— 优先模拟真实用户行为（浏览器 + 登录态），直连可用时才走 HTTP
2. 代理在云端 —— 3xui 订阅由服务端自动解析/测速/择优，客户端零配置
3. 成本导向 —— 全链路分级降档（摘要三档、视频三档、视觉模型六级降级链）

---

## 二、技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Kotlin | 2.1.0 | 全部模块主语言 |
| Compose Multiplatform | 1.7.3 | 客户端 UI（Android + Desktop 共享） |
| Ktor | 3.0.3 | 服务端（Netty）+ 客户端 HTTP |
| kotlinx-serialization-json | 1.7.3 | 全链路 JSON |
| Coroutines | 1.9.0 | 全异步（禁止 callback 风格） |
| Playwright (Java) | 1.49.0 | 云端浏览器自动化 |
| AGP | 8.7.3 | Android 构建 |
| Logback | 1.5.12 | 服务端日志 |
| Python 3.12 | — | 视频分析管线（ffmpeg / faster-whisper / OCR） |

Android：compileSdk/targetSdk 35，minSdk 26。

---

## 三、模块架构

`settings.gradle.kts` → rootProject `trae-info-integration`，3 个模块：

| 模块 | 类型 | 职责 |
|---|---|---|
| `:core` | KMP 库（android + jvm） | 跨端共享领域模型与 API DTO；被 server 与 client-app 共同依赖 |
| `:server` | Kotlin/JVM（application） | Netty 云端服务（端口 8765）：采集调度、代理穿透、浏览器自动化、AI 能力、全部 REST API |
| `:client-app` | KMP + Compose MP | Android（`com.trae.info.client`）+ Desktop（Msi/Dmg/Deb）前端 |

**依赖方向**：`client-app → core ← server`（core 是唯一共享层，客户端只与云端 REST 通信）

---

## 四、目录结构详解

### 4.1 server（`server/src/main/kotlin/com/trae/info/server/`）

| 包 / 文件 | 用途 |
|---|---|
| `Application.kt` | Netty 入口与依赖装配（配置 → 代理池 → AI → 浏览器 → Service → 路由 → 退出钩子） |
| `ai/AiProvider.kt` | AI 供应商统一抽象（豆包/GPT 走 OpenAI 兼容协议，Gemini 走原生协议） |
| `ai/AiProviders.kt` | 多供应商注册表 + 降级链（豆包 → GPT → Gemini）+ 视觉六级链 |
| `ai/PromptStrategies.kt` | 各场景提示词策略 |
| `ai/VisionModelRouter.kt` | 视觉模型路由与健康状态 |
| `browser/BrowserFetchService.kt` | Playwright 抓取/登录会话管理（常驻浏览器池、并发闸门、空闲回收） |
| `browser/AgentLoginEngine.kt` | Agent 智能登录（QR 优先 → 表单填充 → 验证码/登录态判定） |
| `browser/BrowserScrapers.kt` | 各平台浏览器刮板（MOOC/知乎/小红书/抖音/Quora/B站/Reddit/微信/财新/CNKI 等） |
| `browser/HumanBehavior.kt` | 拟人化行为（settle/scroll/输入节奏）反检测 |
| `browser/SessionStore.kt` | 登录态（storageState）持久化 |
| `config/AppConfig.kt` | 配置模型（多源解析：config.json → 系统属性 → 环境变量） |
| `config/ConfigStore.kt` | `data/` 目录配置持久化（原子写） |
| `crawler/PlatformAdapter.kt` | 适配器接口 + `HttpAdapter` 基类 |
| `crawler/Adapters.kt` | 约 20 个平台适配器 + `AdapterBreaker` 熔断器 + `CompositeAdapter` 降级组合 + `rssDateToEpochMs` 多格式日期解析（RFC 1123 → ISO 兜底） |
| `decision/Decision.kt` | 决策核心 V2 数据契约与准入（`Verdict` 四态、`AdmissionPolicy.decide`、`SourceObservation`、`ScoreExplanation`、`QuerySpec`，policyVersion=adm-v2.1） |
| `decision/QueryPlanner.kt` | 检索词规划（用户偏好实体 ∪ 类目词，论文类目 90 天窗 / 其余 45 天窗） |
| `decision/SourceScheduler.kt` | 收益调度（priority = expectedUseful/max(cost,ε)+explorationBonus，观测平滑） |
| `decision/DedupService.kt` | 身份去重（规范化 URL 只删追踪参数 + arXiv 去版本号 + SHA-256 指纹）与事件分组（Jaccard ≥0.6） |
| `decision/ContentRanker.kt` | 可解释偏好排序（relevance×2 + quality×0.5 + freshness×1，负向词硬排除） |
| `proxy/ProxyPool.kt` | 3xui 订阅 → 解析 → xray → 测速 → 选优 → 探活切换 |
| `proxy/XrayManager.kt` | xray 子进程生命周期/日志截断/优雅退出 |
| `proxy/SubscriptionParser.kt` | 订阅解析（VLESS+Reality 等协议参数） |
| `routes/Routes.kt` | 全部 REST 路由（含内部状态转 DTO） |
| `service/ContentService.kt` | 采集编排核心、类目聚合、feed 缓存与 single-flight |
| `service/ScrapingProcessService.kt` | 后台抓取循环进程（start/stop/tick + StateFlow 状态） |
| `service/BudgetPlanner.kt` | 抓取预算与配额规划（`BROWSER_PRIMARY` 单一事实源） |
| `service/ContentPolicy.kt` | 统一准入与去重策略 |
| `service/PreferenceEngine.kt` | 偏好文本 → 关键词/展示模式/加权排序 |
| `service/SummaryStore.kt` | AI 总结持久化（LRU 2000 / 30 天） |
| `service/TranslationService.kt` | 外文标题批量翻译（LRU 2000，异步补翻） |
| `service/VideoAnalysisService.kt` | 视频分析编排（档位分级 + Python 脚本调用） |
| `companion/CompanionDtos.kt` | 通道 B 伴侣脚本上报 DTO |
| `store/DecisionStore.kt` | 来源观测持久化（`decision-store.json` 原子写，抓取单元结束后记录 attempts/successes/uniqueNew/elapsedMs） |
| `util/AtomicFiles.kt` | 原子文件写入（防配置损坏） |

### 4.2 core（`core/src/commonMain/kotlin/com/trae/info/core/`）

| 文件 | 用途 |
|---|---|
| `model/ContentItem.kt` | 统一信息条目（含 `aiSummary`、`translatedTitle`） |
| `model/Taxonomy.kt` | `Domain` / `Region` / `SourcePlatform`（含 requiresProxy）/ `InfoCategory` / 全量分类体系单例 |
| `model/Subscription.kt` | `PlatformGroup` / `SubscriptionPrefs`（类目与平台开关 + 平台组合）+ `nowMs()` |
| `api/Dtos.kt` | 请求响应 DTO（Feed/Categories/Proxy/Analyze/AgentLogin/Session/Remote 键鼠/Admin 配置/Scraping 等） |
| `api/VideoDtos.kt` | 视频分析 DTO（请求含 `summaryLevel` / `KeyFrame` / `VideoAnalyzeResponse`） |
| `androidMain` `jvmMain` | `nowMs()` 两端 actual 实现 |

### 4.3 client-app（`client-app/src/`）

**commonMain**
| 文件 | 用途 |
|---|---|
| `App.kt` | 导航骨架（Android 底部 6 项导航 / Desktop 侧边 NavigationRail）+ 二级页面容器 |
| `api/CloudApi.kt` | 云端 REST 客户端 + `CloudApiException`（非 2xx 抛错，避免错误 JSON 被误当正常 DTO） |
| `data/FavoritesStore.kt` | 收藏夹（本地持久化，星标卡片/详情共用） |
| `data/ReadStore.kt` | 已读状态机（上限 2000，打开详情即标记） |
| `data/FeedCacheStore.kt` | 信息流离线缓存（类目列表 + 类目内容双缓存，TTL 7 天，断网兜底） |
| `theme/Theme.kt` | HMCL 靛蓝主题（`#5C6BC0`）+ 动态取色（Monet）覆盖入口 |
| `platform/Platform.kt` | expect 平台能力（服务器地址/收藏/已读/缓存 JSON/时间格式化/打开链接） |
| `platform/ImageDecoder.kt` | 跨端 PNG 解码（登录截图） |
| `ui/FeedScreen.kt` | 信息流（卡片 + 详情弹层 + 骨架屏 + 下拉刷新 + 类目切换动画） |
| `ui/HomeScreen.kt` | 抓取控制与状态展示（AI 摘要档位、全部总结开关、间隔/时长） |
| `ui/FavoritesScreen.kt` | 收藏夹 |
| `ui/MoreScreen.kt` | 二级入口聚合（云端登录/代理/管理/设置） |
| `ui/PreferenceScreen.kt` | 信息偏好（复制提示词/粘贴总结/派生结果展示） |
| `ui/ProxyScreen.kt` | 云端代理状态 |
| `ui/SessionsScreen.kt` | 云端登录（二维码提示 → Agent 登录 → 手动远程控制保底） |
| `ui/SubscriptionScreen.kt` | 订阅定制（类目/平台开关 + 平台组合） |
| `ui/SettingsScreen.kt` | 设置（服务器地址） |
| `ui/AdminScreen.kt` | 云端管理（多 Tab 图形化配置） |

**androidMain**：`android/MainActivity.kt`（入口 + Monet 动态取色）、`android/AppContext.kt`、`platform/Platform.android.kt`（SharedPreferences）、`platform/ImageDecoder.android.kt`
**desktopMain**：`desktop/Main.kt`、`platform/Platform.desktop.kt`（java.util.prefs）、`platform/ImageDecoder.desktop.kt`

### 4.4 Python 脚本

`server/scripts/video_pipeline.py` — 视频分析管线：提字幕（FFmpeg）/ 提音频 / faster-whisper 转写 / 指定时间点抽帧 / 单帧 OCR；输入输出 JSON，由 `VideoAnalysisService` 以子进程调用（带超时强杀与协程取消联动）。
**注意**：服务进程实际执行 `/opt/trae-info/scripts/video_pipeline.py`（systemd WorkingDirectory=/opt/trae-info 的相对路径），与 `server/scripts/` 是两份拷贝，更新脚本时两处都要同步。

### 4.5 mcp（`mcp/`，TypeScript MCP 封装，2026-09-20 新增）

| 文件 | 用途 |
|---|---|
| `src/index.ts` | trae-video-mcp：官方 SDK（1.30.0）stdio server，把 `POST /api/video/analyze` 封装为三工具（analyze_video / get_transcript / get_frames），detail→summaryLevel 档位映射，后端地址经 `TRAE_VIDEO_SERVER` 环境变量注入 |
| `examples/smoke-test.mjs` | stdio 协议烟雾测试（initialize/tools/list/tools/call；`SMOKE_LIST_ONLY=1` 快速模式） |
| `README.md` | 使用说明（Claude Code / Cursor 接入配置、验证安装、诚实边界声明） |

构建：`cd mcp && npm install && npm run build`（dist/index.js）。端到端已实测（2026-09-20：get_transcript 连 VPS 真实分析 10s 样本 142-146s，`transcriptSource: asr` 标记正确）。

### 4.6 agent（`agent/`，TypeScript MCP 宿主应用，2026-09-23 新增）

agent 版视频处理应用 trae-video-agent v0.1.0：以 MCP stdio **客户端**身份驱动 trae-video-mcp 三工具的固定流程智能体，输出可引用 markdown 证据报告（`seg#`/`frame#` 编号 + 证据边界声明，对齐方向 A 证据包思想的当前可行子集）。

| 文件 | 用途 |
|---|---|
| `src/client.ts` | VideoMcpClient（stdio 客户端，连接 `../mcp/dist/index.js`，工具超时 16 分钟）+ analyze/frames 类型化封装 |
| `src/index.ts` | CLI 五命令：check（链路自检）/ brief（简报）/ frames（帧证据）/ watch（监控批处理）/ digest（汇总） |
| `src/tasks.ts` | watch 清单装载、state 去重（`.watch-state.json`，防服务端无缓存旁路下的重复全管线成本）、digest 渲染 |
| `src/report.ts` | 报告渲染：`agent/reports/<日期>/`，元信息表 + AI 总结 + 转录时间轴 + 帧证据 + 证据边界声明 |
| `watch.json` | 监控清单（不存在时首跑自动生成模板） |

构建：`cd agent && npm install && npm run build`。验证（2026-09-23）：check 链路 0.3s 发现三工具；云端端到端 brief（10s 无语音样本）报告生成正确（segs=0 属实，AI 总结诚实提示素材不足）。架构与完整用法见 `docs/video-agent-usage.md`。诚实边界：固定流程智能体（无自主 LLM 规划，自主规划由 MCP 宿主承担）；仅 HTTP(S) 直链；全管线成本（brief 档零视觉）。

---

## 五、核心能力体系

### 5.1 信息采集（三通道架构）

| 通道 | 形态 | 说明 |
|---|---|---|
| A. 云端浏览器 | Playwright + 登录态 | 模拟真实用户行为，主力通道（微信搜一搜/抖音/小红书/CNKI 等） |
| B. 伴侣脚本 | 客户端上报 | `POST /api/companion/ingest` 接收真实浏览器环境采集结果 |
| C. HTTP 适配器 | 官方/公开 API | 完全公开机制平台直连（arXiv/HN/StackExchange/Wikipedia） |

**降级链**：`CompositeAdapter(primary=HttpAdapter, browser=BrowserFetchService, scraper, fallback)`
—— 直连不可用 → 浏览器模拟；**测试可行方式优先直连**，海外平台 `requiresProxy=true` 走云端代理。

**熔断**：连续 3 次异常失败 → 熔断 5 分钟（浏览器冷启动）+ 半开重试；**合法空结果不计失败**。

### 5.2 平台与类目体系

- **32 个细分类目 × 21 个平台**（`Taxonomy.kt` 单一事实源）。
  > 【可漂移快照】上列计数为 2026-09-25 实测值，随平台/类目增删而变；权威值以代码为准：
  > `Taxonomy.categories.size` 与 `SourcePlatform.entries.size`。另：14 个浏览器刮板（`BrowserScrapers.all`）、
  > 8 个浏览器主通道平台（`BudgetPlanner.BROWSER_PRIMARY`）。计数漂移可由 `scripts/check-consistency.ps1` 发现。
- **类目与平台增删的能力边界**（【规范性描述】，不随版本漂移）：
  - **可运行时增删**（客户端可请求）：细分类目（`POST/DELETE /api/admin/categories`）、
    平台启用范围（订阅开关与平台组合）；
  - **需走接入清单**（编译期固件）：新增平台**本体** —— `SourcePlatform` 为编译期枚举，
    必须按 `platform-onboarding` 技能逐项登记（适配器/刮板/`BROWSER_PRIMARY`/`isLowValue`/质量分）。
    客户端只能启用或停用已登记平台，不能创造一个采集通道不存在的平台。
- 平台按排序机制公开程度分三梯队：完全公开 / 部分公开 / 基本不公开
- 新增平台必须走 `platform-onboarding` 技能清单（含 `BudgetPlanner.BROWSER_PRIMARY` 登记，漏登会导致成本低估 ~7 倍）

### 5.3 代理穿透

3xui 订阅 `https://3x.955273.xyz:2096/sub/...` → 解析 → xray 子进程（SOCKS 10810）→ 延迟测速 → 选最低延迟节点 → 定期探活失败自动切换。仅海外平台流量经代理，国内直连。

### 5.4 浏览器自动化与登录

- 常驻浏览器池（按是否走代理各 1 实例）、并发 2、同平台串行、单元 90s 硬超时
- 浏览器每 ~100 单元 / 30 分钟重启控内存；登录会话 10 分钟空闲回收
- **登录三优先级**（0.5.6 起）：二维码提示 → Agent 账号密码登录 → 手动远程控制（触控板映射 + 唤起键盘）
- 反检测：完整 Chromium + `--headless=new` + stealth initScript（`headless_shell` 会被识别上报）

### 5.5 AI 能力

**语言模型链**：豆包 → GPT(OpenAI) → Gemini（120s 超时，60s 失败冷却，协程取消不降级）
> 现状：豆包=true，Gemini=true（2026-09-17 部署），GPT 未配置

**视觉模型链**（成本优先）：`doubao-seed-2-0-mini → 2-0-lite → 2-1-turbo → 2-1-pro → las-vision → doubao-seed-evolving`
降级触发：配额不足 / 限流 / 超时 >180s / 认证失败

**AI 摘要三档**：模糊（≤25 字 / 15s）· 默认（≤40 字 / 20s）· 详细（80-120 字 / 30s，600 字上下文）
缓存：`summaries.json` 跨会话持久（LRU 2000 / 30 天 / 原子写）

**外文翻译**：`TranslationService` 批量 ≤20 条标题单次调用，`translations.json` 持久缓存
（**落盘数量截断 MAX_ENTRIES=2000；真实 LRU 与内存容量控制未实现——内存 Map 无淘汰无访问顺序，B05 未关闭**）；
`decorate()` 同步填充（零延迟）+ `translateMissingAsync()` 异步补翻（下次生效）；
**in-flight 键去重**（2026-09-21 修复语义反转缺陷：原 `filterNot { inFlight.add(it.id) }` 首次条目被排除/重复条目被保留，
改为 `.filter { add(it.id) }` + 批内锁内缓存复查 + 单一闸门许可内超 20 条自动分批；
`TranslationServiceTest` 五场景回归通过：首次/并发合并/失败重试释放/超批分批/锁内复查）

### 5.6 视频分析

**管线**：下载（300MB 上限，双保险计数）→ 字幕优先（跳过 ASR）→ faster-whisper 兜底 →
AI 推断关键帧时间戳 → FFmpeg 抽帧 → OCR（详细档）+ 视觉描述 → 三源融合总结

**成本档位**（`summaryLevel`）：
| 档位 | 帧数 | OCR | 视觉 | 回传帧图 |
|---|---|---|---|---|
| 模糊 | 0 | ✗ | ✗ | ✗ |
| 默认 | 3 | ✗ | ✓ | ✗ |
| 详细 | keyFrameCount(5) | ✓ | ✓ | ✓ |

并发闸门 `Semaphore(1)`（whisper/ffmpeg 内存开销大，3.7G VPS 限制）
任务总期限 `withTimeout` 30 分钟（超时返回受限原因，不再无限占用闸门）

### 5.7 个性化偏好

`PreferenceEngine`：自然语言偏好总结 → 正面关键词 + **负面关键词**（`减少/不喜欢/少看` 优先）+ 展示模式推导
排序链：偏好相关度 → 质量分（HN hot / SE Wilson / 豆瓣评分）→ 时间
保存后 60s 防抖自动触发抓取

### 5.8 客户端体验（0.5.7 / 0.5.8 累积）

已读状态机 · 骨架屏 shimmer · 类目切换动画 · 下拉刷新 · Monet 动态取色 · 本地离线缓存 ·
外文译题展示 · 收藏夹 · 6 项底部导航

### 5.9 决策核心 V2（灰度中，默认关闭）

依据《重写决策核心与复用采集组件-工程实施方案》落地的可解释决策链，P0 修复 + P1 核心已上线，P2/P3 待推进：

- **新链路**（`decisionCoreV2=true` 时 `ContentService.feed` 生效）：
  `AdmissionPolicy.decide`（时效/类型/证据准入，Verdict=ACCEPT/BACKGROUND_ONLY/NEEDS_EVIDENCE/REJECT，仅 ACCEPT+NEEDS_EVIDENCE 进入）→ `DedupService.dedup`（身份去重）→ `take(50)` → `ContentRanker.rankWithExplanation`（评分解释排序）
- **时间窗规则**（修复 45-90 天 arXiv 论文被误删）：论文类目 90 天窗 / 动态内容 45 天窗按内容类型唯一选择；未知日期 → NEEDS_EVIDENCE，freshness=0.3 不伪装新内容
- **收益调度**（`sourceSchedulerV2=true` 时 `ScrapingProcessService.orderedUnits` 生效）：观测收益优先（expectedUseful/max(cost,ε)+explorationBonus 0.15），观测数据来自抓取单元结束后的 `DecisionStore.recordUnit`（奖励口径 = 新增唯一条目数，对比单元开始前缓存身份集合）
- **灰度开关**：`config.json` 的 `decisionCoreV2` / `sourceSchedulerV2`（默认 false），环境变量 `TRAE_DECISION_CORE_V2` / `TRAE_SCHEDULER_V2` 兜底；修改 config.json 后需重启生效。来源观测与决策无关（开关关闭也照常记录积累数据）
- **可观测**：`GET /api/debug/metrics` 新增 `decision` 块（开关状态 + observedSources + 各源 attempts/successRate/uniqueNew/avgElapsedMs）
- **压测验收**（2026-09-18，VPS 本机，8 类目轮换）：V1/V2 两相 × 并发 10/50/100 各 30s，全部 0 错误；V2 相 p95 更优（c=50 时 20.0ms vs 34.0ms，c=100 时 84.5ms vs 83.7ms 持平）；RSS 峰值 316M（限制 2.2G）；V2 链路抽样 feed 返回真实条目正常

---

## 六、REST API 清单（前缀 `/api`）

| 分组 | 端点 |
|---|---|
| 内容 | `GET /categories`、`GET/POST /subscription`、`GET /feed?category=`、`POST /analyze` |
| 偏好 | `GET/POST /preference` |
| 代理 | `GET /proxy/status`、`POST /proxy/refresh` |
| 登录会话 | `GET /sessions`、`POST /session/{p}/login/start\|complete\|cancel`、`GET .../login/screenshot` |
| Agent 登录 | `POST /session/{p}/agent/login` |
| 远程控制 | `POST /session/{p}/remote/click\|type\|key\|navigate\|scroll`、`GET .../remote/url` |
| 伴侣脚本 | `POST /companion/ingest` |
| 视频 | `POST /video/analyze` |
| 云端管理 | `GET/POST /admin/config/ai\|crawler\|proxy`、`GET/POST/DELETE /admin/categories`、`GET/POST /admin/goals`、`POST /admin/ai-search`、`GET /admin/vision-models` |
| 抓取进程 | `POST /admin/scraping/start\|stop\|tick`、`GET /admin/scraping/status` |
| 运维观测 | `GET /debug/metrics`（堆内存/代理/熔断/缓存/抓取进度/decision 块：V2 开关状态+来源观测统计） |

**惯例**：非 2xx 统一返回 `ErrorResponse(error)`；客户端 `CloudApi` 装 `HttpCallValidator` 转 `CloudApiException`。

---

## 七、数据与持久化清单

**服务端（`/opt/trae-info/data/`）**
| 文件 | 内容 |
|---|---|
| `config.json` | AI Key / 爬虫密钥 / 代理参数 / 偏好总结（原子写） |
| `subscription.json` | 订阅偏好与平台组合 |
| `summaries.json` | AI 总结缓存（LRU 2000 / 30 天） |
| `translations.json` | 外文译题缓存（落盘截断 2000；真实 LRU 未实现，B05 未关闭） |
| `companion-items.json` | 伴侣脚本上报条目（临时） |
| `decision-store.json` | 来源观测计数（抓取单元 attempts/successes/uniqueNew/elapsedMs，原子写） |
| `server.log` | 应用日志 |

**客户端（SharedPreferences / java.util.prefs，key 均为独立命名空间）**
`favorites`（收藏）· `readIds`（已读）· `feedCache`（信息流缓存，含类目列表）· `serverUrl`（服务器地址）

---

## 八、部署与发布

| 产物 | 流程 |
|---|---|
| 服务端 | 本地 `scp` 变更文件 → VPS 构建 `:server:distZip` → 解压 → `systemctl restart trae-info` → curl 验证 |
| 客户端 | `:client-app:assembleDebug` → 复制为 `trae-info-client-<ver>.apk` → `adb install -r` → uiautomator 验证 |

**铁律**：同一时间只允许一个 Gradle 构建（VPS 3.7G 内存，并发构建会假死）；Shell 工具须 `dangerouslyDisableSandbox`。
完整命令见技能 `trae-server-deploy`。

服务器：腾讯云 VPS `<VPS_IP>`，应用目录 `/opt/trae-info/`，systemd 单元 `trae-info`，端口 8765。

---

## 九、工程约定与铁律

1. **模块化**：新中间件放 `src/middleware/`（一文件一职责）；Service 层不直接碰路由
2. **异步**：全链路 async/await，禁止 callback 风格
3. **原子写**：所有配置/状态落盘走 `AtomicFiles`（防损坏）
4. **DTO 兼容**：新增字段必须给默认值（旧客户端/服务端反序列化兼容），客户端版本号同步 +1
5. **`BROWSER_PRIMARY`**：浏览器主通道平台必须在此登记（成本模型 + top-8 类目圈定的单一事实源）
6. **平台增删**：必须走 `platform-onboarding` 技能清单，逐项对齐
7. **凭据安全**：API Key 不进命令行参数/scp 文件/远端临时文件；经 SSH stdin 管道 + 服务器端闭环写配置
8. **反检测**：云端浏览器必须用完整 Chromium + `--headless=new` + stealth
9. **同文件多 Edit 串行验证**：并行 Edit 偶发回退，改完须 grep 确认落盘

---

## 十、演进路线

### 10.1 视频能力三方向（v2.0 设计定稿，2026-09-18 重写，待实施）

依据《视频内容提取三方向改进实施方案》（外部输入）重写三份方向文档：三方向共用一个提取内核（来源解析/字幕前置/音频优先/任务与阶段缓存/预算），不分别建设三套下载转录缓存；默认自动流程只覆盖低成本提取与概要翻译，密集视觉/全字幕翻译/剪辑导出由显式请求触发。每份文档含目标与非目标、当前实现表、差距（B01–B09 阻断项映射）、共享依赖、接口与错误、参数优先级、缓存与费用、任务清单（映射 M0–M7 里程碑）、验收数据、限制、发布与回滚十一节。

| 方向 | 文档 | v2.0 核心 |
|---|---|---|
| Agent 观看 | `video-agent-watch.md` | 证据包契约（segmentId/frameId 引用 + coverage 覆盖标记 + 按需授权取图）、文本优先按需视觉、区分文本覆盖与视觉抽样。**当前可行子集已落地**（2026-09-23）：`agent/` 目录 trae-video-agent v0.1.0，MCP 宿主固定流程智能体（check/brief/frames/watch/digest 五命令 + 可引用证据报告），见 4.6 |
| MCP 工具 | `video-mcp-tool.md` | REST 任务接口先拆能力（tasks/evidence/artifacts 五端点）→ 官方 SDK 协议适配；七工具契约；有界队列 + 429 反压（取代"单信号量防滥用"）。**首期封装已落地**（2026-09-20）：`mcp/` 目录 trae-video-mcp v0.1.0，三工具诚实边界版 + 端到端实测通过，见 4.5 |
| 翻译出片 / 剪辑辅助 | `video-translation-editing.md` | 概要翻译四分支状态机（translated/generated_from_content/existing_chinese/waiting_for_content）、内容键缓存（修复同 ID 旧译文复用）、SRT/ASS 结构校验、CMX 3600 有限子集 EDL |

### 10.2 前端体验（已落地 6 项，待办）

已落地：已读状态机 / 骨架屏 / 切换动画 / 下拉刷新 / 动态取色 / 本地缓存
待办：阅读体验定制（字号行距）、桌面键盘快捷键（j/k/b/m）、全文提取阅读视图

### 10.3 待评估

- YouTube / 豆瓣 / B站 原生内容组织（批次 2/3 曾暂缓，待批次 1 收益复核）
- 视频能力档位落地后的 MCP 壳发布

### 10.4 决策核心 V2 路线（依据外部工程实施方案）

- **P0 已落地**（2026-09-18 部署）：arXiv 时间窗冲突修复 / RSS RFC1123 日期解析 / 翻译 in-flight 去重 / 视频任务 30 分钟总期限
- **P1 已落地**：decision 包五类 + DecisionStore 观测 + 灰度开关 + feed/抓取编排接入（详见 5.9）
- **P2/P3 待推进**：DedupService 事件分组消费、QueryPlanner 与 AI 检索联动、灰度全量开启评估（需观测数据积累后决策）

---

## 十一、已知限制与技术结论

### 11.1 平台侧

| 平台 | 限制 | 应对 |
|---|---|---|
| bilibili | 封数据中心 IP（412） | 走浏览器/RSS 降级 |
| 知乎热榜 | HTTP 接口需登录（101） | 已登录态浏览器 |
| Reddit | API 需 OAuth | 浏览器登录态 |
| X (Twitter) | Free 层读权限不足 | 云端浏览器登录会话 |
| MOOC | 直连返反爬页 | 浏览器通道 + CSRF 缓存 30 分钟 |

> **已移出**：微信视频号（观众侧 Web 无入口 + 创作者后台实测接口）于 2026-09-25 作为
> **独立分支项目**移出本文档，见 `docs/branch-wechat-channels.md`。其技术阻碍（HLS + 防盗链
> 需扩展 `downloadVideo`）与主线无关，不再在本文档维护。

### 11.3 关键教训

- koa-connect wrapper 导致 `ctx.state` 丢失 —— 须原生 Koa 重写
- 空抓取结果不得长期缓存（否则信息流长期空白）；空结果做 5 分钟负缓存
- Android 模拟器无 CPU 虚拟化驱动时无法启动（AEHD）；`airplane-mode` 切换后 NAT 出站 TCP 可能不恢复
- 模拟器验证优先用 uiautomator 取真实 bounds，勿靠截图估算坐标
- 服务器构建报 `Unresolved reference` 而本地通过时，优先怀疑**缺文件**（本地新增文件未上传）或**源码漂移**（服务器文件版本落后本地，如 SummaryStore 缺方法——上传对应本地文件即可）
- RSS `pubDate` 多为 RFC 1123 格式，ISO 解析器会静默返回 0（epoch）导致条目被误判超旧——`rssDateToEpochMs` 按格式逐级兜底解析
- feed 压测参数名是 `category`（非 `categoryId`），传错仅返回 400 无提示——压测/调试脚本先用 `GET /api/categories` 拿真实 id
- PowerShell 会话的 PSSecurityException profile 报错是噪音可忽略；PowerShell 不支持 `&&` 需用 `;`
- 模拟器内 Android 客户端默认 `10.0.2.2:8765`（宿主回环）——审查云端部署需先在「更多→设置」改为 VPS 公网地址并「保存并测试连接」
- 服务端 Python 脚本有**双拷贝路径陷阱**：服务进程执行 `/opt/trae-info/scripts/`（WorkingDirectory 相对路径），而构建源在 `/opt/trae-info/server/scripts/`——更新 `video_pipeline.py` 必须两处同步，否则"unknown action"报错指向旧版
- PowerShell 下 npm 会装到当前目录：构建 Node 子项目必须显式指定 cwd（或在项目目录内执行），否则误装根目录产生 node_modules/package.json 残留
- `ConcurrentHashMap.newKeySet().add()` 首次返回 true / 重复 false——`filterNot { set.add(x) }` 语义恰好反转（保留重复、排除首次），去重标记必须写 `.filter { set.add(x) }` 并配"首次/并发/失败重试"回归测试（2026-09-21 TranslationService 实例）
- Gradle/Kotlin 尾随 lambda 匹配**最后一个参数**：函数若末参非函数类型（如 `fakeChat(onCall: (Int)->Unit = {}, failFirst: Boolean = false)`），尾 lambda 会错配到 Boolean——必须用具名参数 `fakeChat(onCall = {...})`

---

## 附：文档索引

| 文档 | 位置 |
|---|---|
| 全体系文档（本文件） | `docs/PROJECT_SYSTEM.md` |
| 视频 Agent 观看方向 | `docs/video-agent-watch.md` |
| 视频 MCP 工具方向 | `docs/video-mcp-tool.md` |
| 视频翻译出片方向 | `docs/video-translation-editing.md` |
| 抓取效率方案 | `.trae/documents/0.5.2_scraping_efficiency_plan.md` |
| 平台扩展方案 | `.trae/documents/platform_expansion_plan.md` |
| MCP 封装使用说明（trae-video-mcp v0.1.0） | `mcp/README.md` |
| MCP 链接与全套使用方法（导出） | `docs/mcp-usage-guide.md` |
| Agent 版视频处理应用架构与使用说明（trae-video-agent v0.1.0） | `docs/video-agent-usage.md`（速览：`agent/README.md`） |
| 视频能力里程碑定义（M0–M7 唯一事实源，草案待确认） | `docs/video-milestones.md` |
| 分支项目：微信视频号接入（2026-09-25 移出本文件 §11.2） | `docs/branch-wechat-channels.md` |
| **最终更改规划（2026-09-25，服务端发布事故修复 + 全量整改执行版）** | `.trae/documents/final-change-plan-2026-09-25.md` |
| 全量整改方案（2026-09-25 过程记录：审查证据、逐条发现、待答项原文；**已被上行的最终规划取代**） | `.trae/documents/remediation-plan-2026-09-25.md` |
| 审核整改进度（2026-09-20 暂停点；r1–r8 任务来源） | `.trae/documents/audit-remediation-progress-2026-09-20.md` |
| 通道 B 采集伴侣（油猴脚本 v0.1.0，对应 §5.1 通道 B 的实现体） | `extension/trae-companion.user.js` |
| 一致性检查脚本（平台 id 硬编码 / 范围表 / isLowValue / 里程碑引用） | `scripts/check-consistency.ps1` |
| 部署脚本（服务端发布） | `scripts/deploy-server.sh`、`scripts/update-server.sh` |
| 腾讯云 VPS 连接信息工程文件（导出） | `docs/vps-connection.md` |
| 安卓 APK 编译工具链与环境使用说明（导出，2026-10-02） | `docs/android-build-env.md` |
| **SDD 规范驱动开发归档根**（宪法 + 功能规范 + 归档，2026-10-10） | `the integration of information.project/README.md` |
| 　└ 项目宪法（目标/技术栈/路线图） | `the integration of information.project/constitution/` |
| 　└ 功能规范（F01–F12，每功能一目录 + `_TEMPLATE`） | `the integration of information.project/specs/` |
| **工程文档备份单元**（归档 + 还原方法 + SHA256，2026-10-10） | `backup/BACKUP-RESTORE.md` |
| **远程仓库**（公开，2026-10-10 建立；main + 12 功能分支） | `https://github.com/xzhou2767-sys/the-integration-of-information` |
| 决策核心 V2 工程实施方案（外部输入） | `C:\Users\ASUS\Documents\Codex\2026-09-15\zhe\outputs\重写决策核心与复用采集组件-工程实施方案.md` |
| 视频内容提取三方向改进实施方案（外部输入，三份 v2.0 方向文档的上游依据） | `C:\Users\ASUS\Documents\Codex\2026-09-18\md-claude-vedio-agent-mcp-md\outputs\视频内容提取三方向改进实施方案.md` |
| 技能：服务端部署 | `.trae/skills/trae-server-deploy/SKILL.md` |
| 技能：平台接入 | `.trae/skills/platform-onboarding/SKILL.md` |
| 技能：全体系文档同步 | `.trae/skills/project-system-sync/SKILL.md` |
| 技能：视频信息提取（三方向思路提炼，2026-09-24） | `.trae/skills/video-extraction/SKILL.md` |