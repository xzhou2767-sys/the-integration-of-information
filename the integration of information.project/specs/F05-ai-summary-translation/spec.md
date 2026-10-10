# F05 AI 摘要与翻译链 — 功能规范

> 状态：**待建立**（已验收功能，规范待反向提取；排期见 `../../constitution/project-roadmap.md` §五）
> 分支：`feature/f05-ai-summary-translation` ｜ 验收证据：`../../../docs/PROJECT_SYSTEM.md` §5.5（路线图 §二 F05 行）
> 模板：`../_TEMPLATE/spec.md`（配套 `plan.md` / `tasks.md` 同目录建立）

## 待建立内容（占位）

按模板 §§一–四 逐项与用户对话确认：摘要三档与视觉模型六级降级链的触发条件、`TranslationService` in-flight 去重契约（键语义/失败释放/锁内复查）、验收标准（五场景单测 + 缓存命中零调用）。

**反向提取线索**：`PROJECT_SYSTEM.md` §5.5（含 2026-09-21 反转缺陷修复记录）、§11.3 两条教训（`newKeySet().add()` 语义反转、尾随 lambda 陷阱）、`server/src/test/.../TranslationServiceTest.kt` 五场景。
