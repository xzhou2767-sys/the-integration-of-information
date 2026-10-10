# F<XX> <功能名称> — 任务清单（tasks.md）

> 模板版本：1.0｜前置：同目录 `plan.md` 已定稿。
> 分支：`feature/f<xx>-<slug>` ｜ 完成定义：任务项 `[x]` 且对应验收项（spec.md §三）有证据。

## 任务分解

- [ ] T1 …（完成定义：）
- [ ] T2 …（完成定义：）
- [ ] T3 …

## 验收对照

| 验收项（spec.md §三） | 覆盖任务 | 证据位置 | 状态 |
|---|---|---|---|
| AC-1 | T1 | | 未验证 |

## 收尾检查

- [ ] 全部验收项有可复现证据
- [ ] 已同步 `docs/PROJECT_SYSTEM.md` 对应章节（技能 `project-system-sync`）
- [ ] 分支合并：`feature/f<xx>-<slug>` → `main`，合并信息含验收结论
- [ ] 备份刷新：`backup/` 归档与 `backup/BACKUP-RESTORE.md` 记录更新
