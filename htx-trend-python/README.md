# HTX 趋势策略 Python 运行时

这是 `coin-strategy-htx` 中**合约趋势策略**的 Python 迁移工程。它不迁移网格、现货做市或通用 Java Web 服务；单所资金费套利与跨所资金费套利会作为独立 Python worker 接入，不能与趋势 worker 共用订单或仓位状态。

当前已完成 Python 行情、组合开仓、累计成交投影、持仓退出和 HTX V5 typed client。客户端能读取公开行情和私有账户、仓位、订单、杠杆信息；`place_order` 和 `cancel_order` 默认被硬性禁止。真实变更还必须同时持有与 Java 共用的 MySQL 生命周期锁，并通过执行器逐次验锁。

官方 [`huobiapi/huobi_Python`](https://github.com/huobiapi/huobi_Python) 仓库在迁移基线 `8a96761845302daf585010784150779da5edc206` 中主要覆盖现货 V3，合约仅保留旧 `swap_cross_order`。当前 Java 趋势服务依赖 HTX V5 合约端点，因此本工程使用同一 HMAC-SHA256 签名协议实现受测试的 V5 适配器，不把真实交易降级到旧合约接口。

```bash
python -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest
```

迁移路线见 [`../docs/architecture-refactor/HTX_TREND_PYTHON_MIGRATION_V1.md`](../docs/architecture-refactor/HTX_TREND_PYTHON_MIGRATION_V1.md)。


## 迁移完成后的验证入口

Python 不会启动即建表：需要在纸面环境中显式执行 `htx-trend init-ledger --database /path/paper.sqlite --schema sql/schema.sql`。运行时 K 线证据可用 `htx-trend replay --evidence /path/evidence --output /path/report.json` 生成完整性报告。Java 与 Python 决策导出为同一 JSON 后，可用 `htx-trend compare --java java.json --python python.json` 输出逐字段差异。

这些命令只用于回放、纸面与差异验证。`compare` 发现任一字段差异时退出码为 `1`。回放必须包含同一 `run_id` 的 15m 与 60m 已完成 K 线；缺高周期证据会报告 `MISSING_TREND_WINDOW`，不会再用 15m 数据冒充 60m。

## 生产运行平台

Python 的生产事实账本是现有 MySQL，不是本地 SQLite。订单查询按交易所累计成交量事务更新订单、仓位和统一账本；部分成交后撤单保留真实剩余仓位，未知提交结果保留为 `UNKNOWN` 并按客户端订单号恢复。SQLite 仍只用于离线回放和事务行为测试。部署前由运维显式执行 [`sql/2026-10-08__python_strategy_platform.sql`](sql/2026-10-08__python_strategy_platform.sql) 和 [`sql/2026-10-09__python_exit_state.sql`](sql/2026-10-09__python_exit_state.sql)，worker 启动时只读验表，绝不执行 DDL。

每个实例对应一个独立 systemd 单元：[`deployment/systemd/coinstrategy-python-strategy@.service`](deployment/systemd/coinstrategy-python-strategy@.service)。实例、心跳与账户资金预算在 MySQL 的 `strategy_runtime_instance`、`strategy_runtime_heartbeat`、`strategy_capital_allocation` 中保存。趋势、单所 carry、跨所 carry 因此可分别启动、停止、回滚；共享账户只通过预算和最大并发仓位做硬约束。

`OBSERVE` worker 并发读取公开行情、顺序写入同口径证据和心跳，不会创建订单。`LIVE` 代码路径已存在，但启动前必须同时满足：完成退出逐笔验收并显式设置 `HTX_PYTHON_EXIT_PARITY_APPROVED=true`；当前只允许 PRIMARY 且拒绝启用镜像账户；Java 与 Python 使用同一个 `coinstrategy:htx:trend:writer:v1` 生命周期锁；同源趋势和退出开关全部一致。会话已批准的混合市场消融必须显式保留：`HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false`，避免回到默认 `true`。

```bash
python -m htx_trend.worker --strategy-instance <instance-key>
```

本仓库尚未部署 Python、未执行生产 SQL，也未切换真实执行权。首次切换必须先停旧 writer 并确认锁释放，再启动 Python；锁丢失后进程永久禁止新变更，必须重启恢复。上线与回滚流程见 [`../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md`](../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md)。
