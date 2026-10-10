# the integration of information.project

> **本目录是「信息整合平台（Trae Info）」的 SDD（Spec-Driven Development，规范驱动开发）归档根。**
> 建立日期：2026-10-10｜git 基线 `e2b23db`，体系补齐 `8d7d9fb`（主分支 `main`，12 个功能分支同基点）
> 定位：把项目的**长期约束（宪法）**、**逐功能契约（规范）**与**历史快照（归档）**分开存放，做到「规范未定不实现、实现有据可查、证据可回溯」。

## 一、标准层级序列

```
the integration of information.project/
├── README.md                     # 本文件：层级说明 + 工作流 + 索引
├── constitution/                 # ① 项目宪法（长期稳定，变更需用户批准）
│   ├── project-goals.md          #    目标与核心原则
│   ├── project-tech-stack.md     #    技术栈与工具链
│   └── project-roadmap.md        #    路线图（颗粒度＝已验收功能）
├── specs/                        # ② 功能规范（每个功能一个隔离目录）
│   ├── _TEMPLATE/                #    模板：spec.md / plan.md / tasks.md
│   ├── F01-tri-channel-crawling/ #    …
│   ├── …                         #    F01–F12 已验收功能
│   └── F12-companion-extension/
└── archive/                      # ③ 快照归档（只读参考）
    ├── docs/                     #    工程文档快照
    ├── documents/                #    方案与进度文档快照
    ├── module-readmes/           #    子项目 README 快照
    └── skills/                   #.trae 技能快照
```

**各层职责与变更规则**

| 层 | 内容 | 变更规则 |
|---|---|---|
| `constitution/` | 使命、非目标、核心原则、技术栈、路线图 | 长期稳定；修订需用户批准；与 `docs/PROJECT_SYSTEM.md` 冲突时**现状以后者为准，目标方向以宪法为准** |
| `specs/` | 单功能的需求边界、接口契约、验收标准、技术方案、任务清单 | 规范未评审不进入实现；实现完成后回填验收证据 |
| `archive/` | 文档历史快照 | 只读；新快照追加，不修改既有 |

**单一事实源约定**：项目**现状**的权威源始终是 `../../docs/PROJECT_SYSTEM.md`；本目录只做**宪法化与规范化**视图，不另立现状描述。

## 二、功能颗粒度与分支隔离

- **颗粒度**＝一个**已验收功能**（`constitution/project-roadmap.md` §二 F 表）。
- **隔离方式**＝一个功能一个 git 分支 `feature/f<xx>-<slug>`，与同名 `specs/F<xx>-<slug>/` 目录一一对应。
- **当前状态**：`F01–F12` 分支已建立（均自 `main` 基线分出）；规范为**占位桩**，将通过后续逐功能对话补建。

| 分支 | 规范目录 | 规范状态 |
|---|---|---|
| `feature/f01-tri-channel-crawling` | `specs/F01-tri-channel-crawling/` | 待建立 |
| `feature/f02-platform-taxonomy` | `specs/F02-platform-taxonomy/` | 待建立 |
| `feature/f03-cloud-proxy` | `specs/F03-cloud-proxy/` | 待建立 |
| `feature/f04-browser-automation` | `specs/F04-browser-automation/` | 待建立 |
| `feature/f05-ai-summary-translation` | `specs/F05-ai-summary-translation/` | 待建立 |
| `feature/f06-video-pipeline` | `specs/F06-video-pipeline/` | 待建立 |
| `feature/f07-preference-engine` | `specs/F07-preference-engine/` | 待建立 |
| `feature/f08-client-ux` | `specs/F08-client-ux/` | 待建立 |
| `feature/f09-decision-core-v2` | `specs/F09-decision-core-v2/` | 待建立 |
| `feature/f10-video-mcp` | `specs/F10-video-mcp/` | 待建立 |
| `feature/f11-video-agent` | `specs/F11-video-agent/` | 待建立 |
| `feature/f12-companion-extension` | `specs/F12-companion-extension/` | 待建立 |

预留功能（F13–F18，尚未建分支）见 `constitution/project-roadmap.md` §三。

## 三、工作流（一条功能规范的完整生命周期）

```bash
# 1) 切到该功能分支
git checkout feature/f10-video-mcp

# 2) 建立规范三件套（从模板复制）
cp specs/_TEMPLATE/spec.md  specs/F10-video-mcp/spec.md     # 需求边界/接口契约/验收标准
cp specs/_TEMPLATE/plan.md  specs/F10-video-mcp/plan.md     # 技术方案
cp specs/_TEMPLATE/tasks.md specs/F10-video-mcp/tasks.md    # 任务清单
# （Windows PowerShell 用 Copy-Item）

# 3) 与用户逐项确认 spec.md → 评审通过后写 plan.md → 拆 tasks.md
# 4) 按 tasks.md 实施；每条验收项留可复现证据
# 5) 收尾：回填 spec.md §五 证据、同步 docs/PROJECT_SYSTEM.md、刷新 backup/
git commit -am "feat(F10): …"
git checkout main && git merge --no-ff feature/f10-video-mcp
```

约束：
- **规范未建立不开始实现**；规范变更走宪法修订流程（用户批准）。
- **已验收功能**补建规范时，从 `docs/PROJECT_SYSTEM.md` 对应章节**反向提取**验收证据（各 spec 桩已标注提取线索）。
- 合并信息须含验收结论；`tasks.md` 收尾检查四步（证据/文档同步/合并/备份）逐项勾选。

## 四、git 使用约定

- 主分支：`main`（基线提交 `e2b23db`；SDD 体系补齐提交 `8d7d9fb`，12 个功能分支均已快进至此）。
- 功能分支命名：`feature/f<xx>-<slug>`（与上文表一致，勿用其它命名）。
- 提交身份：仓库未写入全局 git 配置；首次提交使用内联身份 `-c user.name="trae-info" -c user.email="trae-info@localhost"`。如需改为本人身份，请自行执行 `git config --local user.name/user.email`（**不修改全局配置**）。
- 大体积产物已由 `.gitignore` 排除：构建产物、`node_modules`、`*.apk`、`*.zip`/`*.tar.gz`（根目录 `trae-src-v2.zip` 达 206 MB）、`local.properties*`、运行期数据。

## 五、相关位置

| 事项 | 位置 |
|---|---|
| 项目现状权威源（全体系工程文档） | `../../docs/PROJECT_SYSTEM.md` |
| 离线备份单元（归档 + 还原方法） | `../../backup/BACKUP-RESTORE.md` |
| 冗余归档副本 | `../../archive/2026-10-10-project-docs.zip` |
| 服务器部署 / 平台接入 / 文档同步 / 视频提取技能 | `../../.trae/skills/` |
