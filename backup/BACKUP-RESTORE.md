# 项目工程文档备份与还原说明（BACKUP-RESTORE）

> 版本：1.0｜备份日期：2026-10-10｜本文件与归档文件同目录，构成「可自解释」的备份单元。
> 定位：本项目**工程文档与不可再生配置**的离线冗余。日常回滚优先用 git（见 `../the integration of information.project/constitution/`），本备份作为离线兜底。

## 一、归档清单

| 角色 | 路径 | 大小 | 条目数 | SHA256 |
|---|---|---|---:|---|
| **主备份**（权威） | `backup/project-docs-2026-10-10.tar.gz` | 237,828 B | 109 | `69FB2DDB814DE7B92873FA3757C4023E6C230F36D3EBA4A5DF3580FCE9CEFD50` |
| 冗余副本 | `archive/2026-10-10-project-docs.zip` | 310,015 B | — | `76E57509473ED07276AC3BD5BADEFDDCCC7679D9AC53631026CC6F78A1C15FB1` |

两份内容同源（同日快照），互为冗余：tar 保留空目录结构、便于脚本化还原；zip 便于在无 tar 环境（资源管理器）直接解压。

## 二、覆盖范围

**包含**（工程文档 + 不可再生配置）：
- `docs/` 全部工程文档——现状权威源 `PROJECT_SYSTEM.md`、视频三方向文档（agent-watch / mcp-tool / translation-editing）、导出指南（MCP 用法 / Agent 用法 / 安卓工具链 / VPS 连接）
- `.trae/documents/` 方案与进度文档；`.trae/skills/` 四个技能（platform-onboarding / project-system-sync / trae-server-deploy / video-extraction）
- `the integration of information.project/` 完整 SDD 体系（宪法 3 文档 + F01–F12 功能规范 + `_TEMPLATE` + 内部 archive）
- `extension/trae-companion.user.js`（通道 B 实现体）、`scripts/` 三个运维脚本
- `mcp/README.md`、`agent/README.md`（两个子项目说明）
- 根构建配置：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`.gitignore`

**不包含**（按 `.gitignore` 与体积考量，均为可再生产物）：
- 构建产物：`build/`、`.gradle/`、`.kotlin/`、`dist/`、`node_modules/`
- 二进制发布物：`*.apk`、根目录 `trae-*.zip` 源码包
- 本地环境配置：`local.properties`（含本机 Android SDK 绝对路径）
- 运行期数据：`server/data/`、`*-store.json`、`summaries.json`、`translations.json`、`feed-cache.json`
- 临时与探针文件：`.tmp-*`、`tmp-*`

## 三、还原方法

### 3.1 全量还原

```powershell
$root = "c:\Users\ASUS\Documents\trae_projects\integration of information project"

# 1) 先做现场快照，避免覆盖未提交改动
Copy-Item $root "$root-现场快照-$(Get-Date -f yyyyMMddHHmm)" -Recurse

# 2) 解包（tar 按归档内相对路径覆盖同名文件；-C 指定解包根）
tar.exe -xzf "$root\backup\project-docs-2026-10-10.tar.gz" -C $root
```

zip 副本还原（等价，无 tar 环境时使用）：

```powershell
Expand-Archive -Path "$root\archive\2026-10-10-project-docs.zip" -DestinationPath $root -Force
```

### 3.2 单文件 / 子目录还原（推荐，风险最小）

```powershell
# 只还原某一文件
tar.exe -xzf "$root\backup\project-docs-2026-10-10.tar.gz" -C $root "docs/PROJECT_SYSTEM.md"

# 只还原 SDD 体系
tar.exe -xzf "$root\backup\project-docs-2026-10-10.tar.gz" -C $root "the integration of information.project"

# 只还原技能
tar.exe -xzf "$root\backup\project-docs-2026-10-10.tar.gz" -C $root ".trae/skills"
```

### 3.3 只看不落地（先确认再决定）

```powershell
# 列目录
tar.exe -tzf "$root\backup\project-docs-2026-10-10.tar.gz" | Select-String "docs/"

# 打印单个文件内容到屏幕（-O）
tar.exe -xOzf "$root\backup\project-docs-2026-10-10.tar.gz" "docs/PROJECT_SYSTEM.md" | Select-Object -First 30
```

## 四、完整性校验

```powershell
$root = "c:\Users\ASUS\Documents\trae_projects\integration of information project"

(Get-FileHash "$root\backup\project-docs-2026-10-10.tar.gz" -Algorithm SHA256).Hash
# 期望：69FB2DDB814DE7B92873FA3757C4023E6C230F36D3EBA4A5DF3580FCE9CEFD50

(Get-FileHash "$root\archive\2026-10-10-project-docs.zip" -Algorithm SHA256).Hash
# 期望：76E57509473ED07276AC3BD5BADEFDDCCC7679D9AC53631026CC6F78A1C15FB1

(tar.exe -tzf "$root\backup\project-docs-2026-10-10.tar.gz" | Measure-Object).Count
# 期望：109
```

哈希不一致说明归档被改动或传输损坏——不要直接还原，改用另一份副本或从 git 取。

## 五、备份刷新流程（文档实质变更后执行）

```powershell
$root = "c:\Users\ASUS\Documents\trae_projects\integration of information project"

# 1) 重建主备份（显式列举，避免误纳构建产物）
tar.exe -czf "$root\backup\project-docs-2026-10-10.tar.gz" -C $root docs .trae "the integration of information.project" extension scripts mcp/README.md agent/README.md build.gradle.kts settings.gradle.kts gradle.properties .gitignore

# 2) 同步冗余副本
Remove-Item "$root\archive\2026-10-10-project-docs.zip" -Force -ErrorAction SilentlyContinue
Compress-Archive -Path "$root\docs","$root\.trae","$root\the integration of information.project","$root\extension","$root\scripts" -DestinationPath "$root\archive\2026-10-10-project-docs.zip" -Force

# 3) 重算哈希并回填本文 §一 / §四
(Get-FileHash "$root\backup\project-docs-2026-10-10.tar.gz" -Algorithm SHA256).Hash
(Get-FileHash "$root\archive\2026-10-10-project-docs.zip" -Algorithm SHA256).Hash
```

**命名约定**：`project-docs-<YYYY-MM-DD>.tar.gz`。同日多次刷新覆盖同名文件（哈希随 §一 更新）；跨日产生新文件，旧文件保留形成历史快照。

## 六、注意事项

- **还原是覆盖式的**：`tar -x` 会直接覆盖同名文件。解包前先做现场快照，或先用 §3.3 列目录确认，或改用 §3.2 单文件还原。
- **本备份不含凭据**：`local.properties`、`server/data/config.json`（含 AI Key）、`.tmp-askpass.cmd`（VPS 密码）均不在归档内。还原后需另行配置——参见 `docs/android-build-env.md`（工具链）与 `docs/vps-connection.md`（VPS 连接）。
- 归档内的 `.md.bak*` / `SKILL.md.bak-*` 为历史版本快照，保留以便追溯，不影响使用。
- 备份只覆盖**文档与配置**，不含源码树（`core/`、`server/`、`client-app/`）。源码请用 git 管理；若 git 尚不可用，另行打包源码目录。
- 与 SDD 的关系：SDD 宪法 `constitution/project-roadmap.md` §五 的收尾检查项要求每次功能验收后刷新本备份。
