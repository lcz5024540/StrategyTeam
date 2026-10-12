# HTX Trend Python — 角色待办

> 状态：维护中 | 日期：2026-10-12

| 角色 | 当前焦点 | 文档 |
|------|----------|------|
| 策略产品 | 正式 LIVE 验收口径、R0001 参数签字 | [LIVE-acceptance.md](./LIVE-acceptance.md) |
| 策略开发 | LIVE 线合入、热修、env 与债务 | 本节【策略开发】 |
| 策略测试 | 热修回归、parity 测试、制品校验 | `htx-trend-python/tests/` |
| 策略运维 | tar+SHA 部署、worker 心跳、回滚 | [DEV-runbook.md](./DEV-runbook.md) |
| 行研 | Research MVP、证据链、回测门禁 | PR#11 / `htx-trend-python/RESEARCH_SYSTEM.md` |
| 行情分析 | 因子与行情数据质量 | `htx-trend-python/data/quality/` |

---

## 策略产品

- [ ] 在 [LIVE-acceptance.md](./LIVE-acceptance.md) 中定稿正式 LIVE 验收标准（开仓/平仓、风控、心跳、资金边界）。
- [ ] 确认 R0001 与正式 LIVE 参数是否与运维 env 一致。
- [ ] 签字：热修合入 `support_htx_python` 后的可上线窗口。

## 策略开发

- [x] **PR#23 窄化**：在 `support_htx_python` 上仅 cherry-pick LIVE 生产热修（`from_env` slots、`latest_breadth_stats` PyMySQL `%%`），去掉 cursor/* 大包历史。
- [ ] **LIVE 线合入收尾**：PR#23 合并后，在 `support_htx_python` tip 打标签或记录制品 SHA，与运维制品目录对齐。
- [ ] **`from_env` 热修**：`EntryExecutionConfig` / `ExecutionConfig.from_env` 使用 `defaults=cls()`；回归见 `tests/test_config_from_env.py`；说明见 `htx-trend-python/docs/LIVE_HOTFIX_FROM_ENV.md`。
- [ ] **breadth 热修**：`mysql_store.latest_breadth_stats` 中 `LIKE` 字面量 `%` 写为 `%%`；回归见 `tests/test_live_entry_parity.py`；说明见 `htx-trend-python/docs/LIVE_HOTFIX_BREADTH_PYMYSQL.md`。
- [ ] **`htx-python.env.example` 补全**：模板缺少 **`HTX_TREND_COMBINATION_ENTRY_ENABLED=true`**（及运维常用 `HTX_TREND_LEVERAGE` 等）；`LiveTrendWorker` 启动硬依赖 combination entry，缺项会导致非预期启动失败。
- [ ] **PR / 分支清理**：关闭或废弃基于旧 LIVE lineage 的大 diff 分支；后续热修一律 `support_htx_python` + 窄 PR。
- [ ] **技术债（开发侧）**：
  - `CHANGELOG.md` 在 integration 分支未统一，热修记录分散在 `docs/htx-trend-python/` 与包内 `LIVE_HOTFIX_*.md`。
  - 正式 LIVE 与 research 包版本号（`_version.py`）与运维制品命名需一次对齐。
  - Java/Python 双写互斥与 writer lock 运维检查清单待写入 runbook 自动化项。
  - `deployment/htx-python.env.example` 与 `LiveTrendWorkerConfig.from_env` 全量键位 diff 自动化测试（防再发 slots/from_env 类问题）。

## 策略测试

- [ ] PR#23 合并后：在干净 venv 跑 `pytest tests/test_config_from_env.py tests/test_live_entry_parity.py tests/test_mysql_store_platform.py`。
- [ ] 制品：`sha256sum -c` 与包内 `PACKAGE_MANIFEST` 门禁（若走完整 `package.py` 验证）。
- [ ] （可选）staging PRIMARY 上验证 heartbeat 由 FAILED → OK。

## 策略运维

- [ ] 仅通过 **tar + SHA256** 升级，不在生产机 `git pull`（见 [DEV-runbook.md](./DEV-runbook.md)）。
- [ ] 维护 `htx-trend-worker@htx-trend-live-primary` 与 env 文件权限。
- [ ] 记录回滚基线 commit（热修前 `support_htx_python` tip 或文档指定 SHA）。

## 行研

- [ ] 跟进 PR#11 合入 `support_htx_python` 后的 research 制品与 LIVE worker 部署隔离。
- [ ] 证据目录与 `HTX_PYTHON_EVIDENCE_DIR` 磁盘监控。

## 行情分析

- [ ] 行情/stitch 质量项变更时通知策略开发更新 admission 与因子窗口配置。
