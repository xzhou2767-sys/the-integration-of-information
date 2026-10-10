# 信息来源平台增删流程（platform-onboarding）

适用场景：向信息聚合平台新增/删除一个信息来源平台（如 0.5.3 批次接入的 arXiv、Hacker News、豆瓣等）。
所有模块必须按本清单逐项对齐颗粒度——任何一个登记点遗漏都会造成成本模型漂移或偏好排序脱节。
部署/出包/验证命令见 `trae-server-deploy` 技能，本技能不重复。

## 一、通道选择决策（按平台排序机制公开程度三梯队）

| 梯队 | 特征 | 接入方式 | 装配形态 |
|---|---|---|---|
| 完全公开 | 排序公式/筛选规则开源可复现（HN Hot、SE Wilson、arXiv、Wikipedia、MediaWiki） | 官方/公开 HTTP API 直连为**主**通道 | `primary=HttpAdapter` + 浏览器兜底 |
| 部分公开 | 披露核心因子、权重保密（豆瓣、NSSD、Substack RSS） | 尽力 HTTP（站点随时可能改版） | `primary=HttpAdapter`（best-effort，空结果降级浏览器） |
| 基本不公开 | 无公开架构/公式（微信搜一搜、CNKI、JSTOR、Google Scholar、财新/澎湃） | 云端浏览器模拟行为为主 | `primary=null` + `BrowserScraper`；有 SerpAPI 检索式可加兜底 |

通用铁律：**测试可行方式，优先直连，直连不可用再模拟行为**；海外平台 `requiresProxy=true` 走云端 3xui 代理，客户端永远直连云 API。

## 二、新增平台 Checklist（按顺序，全部必做）

### 1. core 枚举与类目分配
- `core/src/commonMain/kotlin/com/trae/info/core/model/Taxonomy.kt`：
  - `SourcePlatform` 枚举加值（id 全小写中划线；`requiresProxy` 海外 true / 国内 false；`hasOfficialApi` 按事实填）；
  - 类目 `sources` 分配：按平台内容域归入对应 sources 列表（学术→academicSources、科技→techSources、人文→neHumanitiesSources 等），新列表可新建（参考 neBeautySources）。
- 若增 DTO 字段：`Subscription.kt`/`Dtos.kt` 必须给默认值（旧客户端/旧服务端反序列化兼容），版本号 `client-app/build.gradle.kts` 的 versionCode/versionName 同步 +1。

### 2. 抓取通道实现
- **HTTP 主通道**：`server/.../crawler/Adapters.kt` 写 `HttpAdapter` 子类，实现 `fetch(category, limit)`；
  - 官方公式排序的平台在适配器内**复现公式重排序**（参考 HackerNewsAdapter 的 Hot 公式、StackExchangeAdapter 的 Wilson 区间）；
  - 质量信号写入固定位置供 PreferenceEngine 解析：HN → `tags` 末位 `"hot=x.x"`；SE → `summary` 前缀 `"投票：x↑y↓｜Wilson：0.xx"`；豆瓣 → `summary` 前缀 `"评分：x.x"`；
  - 纯元数据 summary（无正文文本）必须同时登记到第 5 步 isLowValue。
- **浏览器通道**：`server/.../browser/BrowserScrapers.kt` 写 `object XxxScraper : BrowserScraper`，实现 platform/loginRequired/loginUrl/readySelector/searchUrl()/loggedIn()/extract()，并在 `BrowserScrapers.all` 注册。

### 3. 装配
- `PlatformAdapters.build()` 中按梯队形态加 `CompositeAdapter`（primary/browser/scraper/fallback 四位一体；无检索式的平台 fallback=null）。

### 4. BudgetPlanner.BROWSER_PRIMARY（单一事实源，最易漏）
- `server/.../service/BudgetPlanner.kt` 的 `BROWSER_PRIMARY` 集合：**凡装配为 `primary=null`（浏览器主通道）的平台必须登记**。
- 该集合同时决定：单元固定开销 40s 计价 + ScrapingProcessService 的相关度 top-8 类目圈定。漏登 → 成本被按 6s 低估 ~7 倍 → 单元数虚高、时间预算超支、后段单元被跳过。
- HTTP 主通道平台（含浏览器兜底）**不要**登记——兜底是失败路径，期望成本按主通道计。

### 5. isLowValue 元数据规则
- `server/.../service/ScrapingProcessService.kt` 的 `isLowValue`：适配器输出「纯元数据摘要」（投票/评分/UP主播放量等，无正文）的平台登记特判，避免 12s/条 AI 总结预算浪费在无文本条目上。注意 `tooShort`（标题<8 且摘要<20）兜不住 20~30 字符的元数据行。

### 6. 质量分接入（完全公开机制平台）
- 排序公式公开的平台，把归一化质量分接进 `PreferenceEngine.qualityScoreOf`（0.0-1.0；黑箱平台返回 0.5 中性）。
- 作用位置：`rank()` 与摘要候选排序的偏好同分 tie-breaker（relevanceScore → qualityScore → publishedAt），**不改变偏好优先原则**。

### 7. 编译 + 部署 + 验证（命令见 trae-server-deploy）
- 本地：`:server:compileKotlin`（有 core/客户端改动再加 `:client-app:assembleDebug`）；
- 服务器：上传改动 → `:server:distZip` → 解压重启；
- 验证：`/api/categories` 出现新平台 → `POST /api/admin/scraping/tick` 触发一轮 → `/api/feed?category=<含新平台的类目>` 确认条目入库、`personalized=true`、质量分排序单调 → `/api/debug/metrics` 确认熔断器 CLOSED。

## 三、删除平台 Checklist（反向）

1. `Taxonomy.kt`：移除枚举值 + 各类目 sources 引用（编译器会报出所有残留引用）；
2. `Adapters.kt`：删适配器类 + `PlatformAdapters.build()` 装配项；
3. `BrowserScrapers.kt`：删刮板 object + `all` 注册；
4. `BudgetPlanner.BROWSER_PRIMARY` / `isLowValue` / `qualityScoreOf`：删对应登记；
5. feed 缓存与摘要缓存中的旧条目随 TTL 自然过期，无需清理；
6. 编译部署验证同上。

## 四、血泪教训

- 新增平台最容易漏的是第 4/5 步（BROWSER_PRIMARY 与 isLowValue）——0.5.3 批次就漏了 CAIXIN_PENGPAI（浏览器主通道未登记，成本被低估且未进 top-8 圈定），被一致性审查兜住。
- `pkill -f gradle` 在 SSH 单引号命令里会匹配命令行自身导致自杀断连（exit -1）——分开执行或用 `pgrep -fl` 先确认。
- SSH heredoc 内的双引号会被引号层吞掉——含引号的脚本一律本地 Write → scp → 执行 → 删除。
- 直连测试在境内 VPS 必然超时（Google 系全部不可达）——海外 API 验证一律加 `-x socks5h://127.0.0.1:10810` 走 xray。
- 站点反爬随时升级（知乎热榜 101 鉴权、B站 412、MOOC 反爬页）——直连失败属预期，靠熔断器+浏览器兜底消化，合法空结果不计失败。
- 平台内质量信号只在单元内排序是不够的：全局排序若不接 tie-breaker，500 vote 热帖与 5 vote 旧帖在偏好同分时无区别。
