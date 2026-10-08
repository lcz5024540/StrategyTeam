# HTX 趋势策略 Python 运行时

这是 `coin-strategy-htx` 中**合约趋势策略**的 Python 迁移工程。它不迁移网格、现货做市、资金费套利或通用 Java Web 服务。

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
