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

这些命令只用于回放、纸面与差异验证。`compare` 发现任一字段差异时退出码为 `1`。正式冻结数据回放必须显式传入 `--manifest`；manifest 必须为 `COMPLETE`，请求的 HTX 文件必须唯一且内容 hash、K 线顺序、连续性、OHLC 和成交量审计全部通过。

连续行情采集与运行 worker 解耦。以下命令不读取账户或交易密钥；它会保存 HTX 原始响应、标准化闭合 K 线、质量报告和内容寻址 manifest。生产定时器反复执行 `collect-tick` 即可断点续采，重叠读取最近两根闭合 K 线以保留交易所修订版本。

```bash
htx-trend collect-history --evidence /var/lib/coinstrategy/htx-python-evidence \
  --symbols BTC-USDT ETH-USDT --start 2026-04-01T00:00:00Z --end 2026-10-01T00:00:00Z
htx-trend collect-tick --evidence /var/lib/coinstrategy/htx-python-evidence \
  --symbols BTC-USDT ETH-USDT
htx-trend verify-evidence --evidence /var/lib/coinstrategy/htx-python-evidence --hash <manifest-sha256>
```

服务器连接模板见 [`deployment/htx-python.env.example`](deployment/htx-python.env.example)。模板只列连接字段；账户 API 密钥继续从 MySQL 的加密账户记录读取，真实密码和私钥文件不得进入仓库。

研究、运行证据回放、OBSERVE、PAPER 与 LIVE 现在共用 `HtxStrategyDecisionEngine`，不会再分别调用两套信号 scorer。新策略版本还必须提供密封的 12 阶段准入产物：`htx-trend validate-admission --artifact /path/admission.json`。详细调用边界见 [`../docs/architecture-refactor/HTX_PYTHON_STRATEGY_IRON_LAW_CALL_FLOW_V1.md`](../docs/architecture-refactor/HTX_PYTHON_STRATEGY_IRON_LAW_CALL_FLOW_V1.md)。

## 生产运行平台

Python 的生产事实账本是现有 MySQL，不是本地 SQLite。订单查询按交易所累计成交量事务更新订单、仓位和统一账本；部分成交后撤单保留真实剩余仓位，未知提交结果保留为 `UNKNOWN` 并按客户端订单号恢复。SQLite 仍只用于离线回放和事务行为测试。部署前由运维显式执行 [`sql/2026-10-08__python_strategy_platform.sql`](sql/2026-10-08__python_strategy_platform.sql)、[`sql/2026-10-09__python_exit_state.sql`](sql/2026-10-09__python_exit_state.sql)、仓库根目录的 [`sql/htx/2026-10-15__htx_trend_candidate_timeframe_profile.sql`](../sql/htx/2026-10-15__htx_trend_candidate_timeframe_profile.sql) 和 [`sql/htx/2026-10-15__htx_trend_candidate_gate_result_width.sql`](../sql/htx/2026-10-15__htx_trend_candidate_gate_result_width.sql)，worker 启动时只读验表，绝不执行 DDL。前一份迁移为候选记录补充独立的 15m/4h `timeframe_profile` 追溯字段，后一份迁移放宽诊断 Gate 代码的存储宽度。

每个实例对应一个独立 systemd 单元：[`deployment/systemd/coinstrategy-python-strategy@.service`](deployment/systemd/coinstrategy-python-strategy@.service)。实例、心跳与账户资金预算在 MySQL 的 `strategy_runtime_instance`、`strategy_runtime_heartbeat`、`strategy_capital_allocation` 中保存。趋势、单所 carry、跨所 carry 因此可分别启动、停止、回滚；共享账户只通过预算和最大并发仓位做硬约束。

`OBSERVE` worker 并发读取公开行情、顺序写入同口径证据和心跳，不会创建订单。`LIVE` 代码路径已存在，但启动前必须同时满足：完成退出逐笔验收并显式设置 `HTX_PYTHON_EXIT_PARITY_APPROVED=true`；当前只允许 PRIMARY 且拒绝启用镜像账户；Java 与 Python 使用同一个 `coinstrategy:htx:trend:writer:v1` 生命周期锁；同源趋势和退出开关全部一致。会话已批准的混合市场消融必须显式保留：`HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false`，避免回到默认 `true`。

`HTX_TREND_V1` 保留为存量兼容版本。其他 `strategy_name` 启动前必须设置 `HTX_TREND_STRATEGY_ADMISSION_PATH=/path/admission.json`；缺失、版本不匹配、证据阶段不足或内容 hash 被改写都会失败关闭。通过完整准入的新版本按止损距离和 `risk_per_trade` 先计算风险仓位，再受目标名义额和最大名义额约束。

LIVE worker 支持在同一 writer 内同时调度 `INTRADAY_15M` 和 `SWING_4H`。前者固定使用 `15min/60min`，后者固定使用 `4hour/1day`；周期与 profile 不匹配时启动失败。候选、订单和持仓均携带 `timeframe_profile`，持仓只由所属 profile 的管理器读取和退出。账户采用合约互斥：同一合约已有任一 profile 的非终态仓位时，另一 profile 返回 `PROFILE_CONTRACT_MUTEX`，不会叠加或反向接管交易所聚合仓位。硬止损继续使用可执行盘口即时保护，不等待下一根周期 K 线。

```bash
HTX_TREND_PROFILES=INTRADAY_15M,SWING_4H
HTX_TREND_INTRADAY_15M_LIVE_ENABLED=true
HTX_TREND_SWING_4H_LIVE_ENABLED=false
```

关闭某个 profile 的 LIVE 开关仍会计算并保存其候选，标记 `shadow=true/SHADOW_ONLY`，但不会调用真实订单执行器。重新启用后只处理新扫描产生的候选，不补发开关关闭期间的旧候选。

```bash
python -m htx_trend.worker --strategy-instance <instance-key>
```

本仓库尚未部署 Python、未执行生产 SQL，也未切换真实执行权。首次切换必须先停旧 writer 并确认锁释放，再启动 Python；锁丢失后进程永久禁止新变更，必须重启恢复。上线与回滚流程见 [`../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md`](../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md)。
