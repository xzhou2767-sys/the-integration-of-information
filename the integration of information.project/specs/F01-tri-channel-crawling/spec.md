# F01 信息采集三通道（HTTP 适配器 / 云端浏览器 / 伴侣脚本） — 功能规范

> 状态：**待建立**（已验收功能，规范待反向提取；排期见 `../../constitution/project-roadmap.md` §五）
> 分支：`feature/f01-tri-channel-crawling` ｜ 验收证据：`../../../docs/PROJECT_SYSTEM.md` §5.1（路线图 §二 F01 行）
> 模板：`../_TEMPLATE/spec.md`（配套 `plan.md` / `tasks.md` 同目录建立）

## 待建立内容（占位）

按模板 §§一–四 逐项与用户对话确认：需求与边界（三通道职责划分与降级链）、接口契约（`POST /api/companion/ingest`、适配器 `fetch` 约定）、验收标准（熔断/合法空结果不计失败的可复现判据）、测试与部署口径。

**反向提取线索**：`PROJECT_SYSTEM.md` §5.1（三通道表 + CompositeAdapter 降级链 + 熔断 3 次/5 分钟）；技能 `platform-onboarding`（通道选择三梯队决策）。
