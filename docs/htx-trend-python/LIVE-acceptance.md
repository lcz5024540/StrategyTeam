# HTX Trend Python — 正式 LIVE 验收（产品主笔）

> 状态：**草稿 / 待产品定稿** | 日期：2026-10-12

本文档由 **策略产品** 维护正式 LIVE（`htx-trend-worker@htx-trend-live-primary` 等）的验收口径。开发与测试在 PR 合并前可引用本页章节标题，**具体通过标准以产品定稿为准**。

## 产品待填写

- [ ] 验收范围：PRIMARY 账户、合约白名单、最大持仓与日内亏损 cap
- [ ] 心跳与可观测性：`strategy_runtime_heartbeat`、日志关键字、告警阈值
- [ ] 开平仓：首笔开仓、平仓、部分平仓、拒单与 UNKNOWN 恢复
- [ ] 风控：与 R0001 / `formal_live` 校验一致性的签字项
- [ ] 回滚：制品 SHA 与回滚 commit 记录

## 参考（实现侧，非验收正文）

- 包内：`htx-trend-python/FORMAL_LIVE_ACCEPTANCE.md`
- 热修后冒烟：heartbeat **OK**、无 `unsupported format character 't'`、无 `from_env` slots `ValueError`
- 部署方式：[DEV-runbook.md](./DEV-runbook.md)（tar+SHA，非 git pull）

---

*产品定稿后请更新文首状态为「已定稿」，并在此列出版本号与生效日期。*
