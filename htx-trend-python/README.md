# HTX 趋势策略 Python 运行时

这是 `coin-strategy-htx` 中**合约趋势策略**的 Python 迁移工程。它不迁移网格、现货做市或通用 Java Web 服务；单所资金费套利与跨所资金费套利会作为独立 Python worker 接入，不能与趋势 worker 共用订单或仓位状态。

当前完成 Python 项目骨架与 HTX V5 typed client。客户端能读取公开行情和私有账户、仓位、订单、杠杆信息；`place_order` 和 `cancel_order` 默认被硬性禁止。只有显式构造 `HtxV5Client(..., live_trading_enabled=True)` 才允许发送交易请求。

官方 [`huobiapi/huobi_Python`](https://github.com/huobiapi/huobi_Python) 仓库在迁移基线 `8a96761845302daf585010784150779da5edc206` 中主要覆盖现货 V3，合约仅保留旧 `swap_cross_order`。当前 Java 趋势服务依赖 HTX V5 合约端点，因此本工程使用同一 HMAC-SHA256 签名协议实现受测试的 V5 适配器，不把真实交易降级到旧合约接口。

```bash
python -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest
```

迁移路线见 [`../docs/architecture-refactor/HTX_TREND_PYTHON_MIGRATION_V1.md`](../docs/architecture-refactor/HTX_TREND_PYTHON_MIGRATION_V1.md)。


## 迁移完成后的验证入口

Python 不会启动即建表：需要在纸面环境中显式执行 `htx-trend init-ledger --database /path/paper.sqlite --schema sql/schema.sql`。运行时 K 线证据可用 `htx-trend replay --evidence /path/evidence --output /path/report.json` 生成完整性报告。Java 与 Python 决策导出为同一 JSON 后，可用 `htx-trend compare --java java.json --python python.json` 输出逐字段差异。

这些命令只用于回放、纸面与差异验证。真实 HTX 执行必须同时在 `HtxV5Client` 和 `LiveAuthority` 显式开启，并且只允许批准的单一账户；当前没有部署或启用该路径。

## 生产运行平台

Python 的生产事实账本是现有 MySQL，不是本地 SQLite。Python 仓储已按 Java 页面使用的趋势信号、候选、因子、仓位、订单、退出决策和统一账本表实现同口径写入；当前可运行的观察 worker 只写信号、候选、因子和心跳，仓位/订单/账本写入留给后续纸面与 live 执行适配器。SQLite 仍只用于离线回放与纸面测试。迁移由运维显式执行 [`sql/2026-10-08__python_strategy_platform.sql`](sql/2026-10-08__python_strategy_platform.sql)，worker 启动时只读校验必需表，绝不执行 DDL。

每个实例对应一个独立 systemd 单元：[`deployment/systemd/coinstrategy-python-strategy@.service`](deployment/systemd/coinstrategy-python-strategy@.service)。实例、心跳与账户资金预算在 MySQL 的 `strategy_runtime_instance`、`strategy_runtime_heartbeat`、`strategy_capital_allocation` 中保存。趋势、单所 carry、跨所 carry 因此可分别启动、停止、回滚；共享账户只通过预算和最大并发仓位做硬约束。

当前可安全运行的是 `OBSERVE` 趋势 worker：它并发读取公开行情、顺序写入同口径证据和心跳，但不会创建订单。启动前由部署流程提供数据库变量与 `COINSTRATEGY_CONTRACTS=BTC-USDT,ETH-USDT,...`，然后执行：

```bash
python -m htx_trend.worker --strategy-instance <instance-key>
```

`PAPER` 与 `LIVE` 模式不会被这个观察 worker 接受。后续接入时仍需账户级、策略实例级、执行器级三层授权，并且同一账户同一策略实例不能让 Java 与 Python 同时成为 live writer。
