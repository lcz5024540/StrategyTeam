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
htx-trend collect-market-facts --evidence /var/lib/coinstrategy/htx-python-evidence \
  --symbols BTC-USDT ETH-USDT --start 2026-04-11T00:00:00Z --end 2026-10-11T00:00:00Z
htx-trend verify-evidence --evidence /var/lib/coinstrategy/htx-python-evidence --hash <manifest-sha256>
```

服务器连接模板见 [`deployment/htx-python.env.example`](deployment/htx-python.env.example)。模板只列连接字段；账户 API 密钥继续从 MySQL 的加密账户记录读取，真实密码和私钥文件不得进入仓库。

研究、运行证据回放、OBSERVE、PAPER 与 LIVE 现在共用 `HtxStrategyDecisionEngine`，不会再分别调用两套信号 scorer。新策略版本还必须提供密封的 12 阶段准入产物：`htx-trend validate-admission --artifact /path/admission.json`。详细调用边界见 [`../docs/architecture-refactor/HTX_PYTHON_STRATEGY_IRON_LAW_CALL_FLOW_V1.md`](../docs/architecture-refactor/HTX_PYTHON_STRATEGY_IRON_LAW_CALL_FLOW_V1.md)。

## 生产运行平台

Python 的生产事实账本是现有 MySQL，不是本地 SQLite。订单查询按交易所累计成交量事务更新订单、仓位和统一账本；部分成交后撤单保留真实剩余仓位，未知提交结果保留为 `UNKNOWN` 并按客户端订单号恢复。SQLite 仍只用于离线回放和事务行为测试。部署前由运维显式执行 [`sql/2026-10-08__python_strategy_platform.sql`](sql/2026-10-08__python_strategy_platform.sql)、[`sql/2026-10-09__python_exit_state.sql`](sql/2026-10-09__python_exit_state.sql)、仓库根目录的 [`sql/htx/2026-10-15__htx_trend_candidate_timeframe_profile.sql`](../sql/htx/2026-10-15__htx_trend_candidate_timeframe_profile.sql) 和 [`sql/htx/2026-10-15__htx_trend_candidate_gate_result_width.sql`](../sql/htx/2026-10-15__htx_trend_candidate_gate_result_width.sql)，worker 启动时只读验表，绝不执行 DDL。前一份迁移为候选记录补充独立的 15m/4h `timeframe_profile` 追溯字段，后一份迁移放宽诊断 Gate 代码的存储宽度。

每个实例对应一个独立 systemd 单元：[`deployment/systemd/coinstrategy-python-strategy@.service`](deployment/systemd/coinstrategy-python-strategy@.service)。实例、心跳与账户资金预算在 MySQL 的 `strategy_runtime_instance`、`strategy_runtime_heartbeat`、`strategy_capital_allocation` 中保存。趋势、单所 carry、跨所 carry 因此可分别启动、停止、回滚；共享账户只通过预算和最大并发仓位做硬约束。

`OBSERVE` worker 并发读取公开行情、顺序写入同口径证据和心跳，不会创建订单。`LIVE` 代码路径已存在，但启动前必须同时满足：完成退出逐笔验收并显式设置 `HTX_PYTHON_EXIT_PARITY_APPROVED=true`；当前只允许 PRIMARY 且拒绝启用镜像账户；Java 与 Python 使用同一个 `coinstrategy:htx:trend:writer:v1` 生命周期锁；同源趋势和退出开关全部一致。会话已批准的混合市场消融必须显式保留：`HTX_TREND_MIXED_MARKET_REQUIRES_STRONG_CANDIDATE=false`，避免回到默认 `true`。

`HTX_TREND_V1` 保留为存量兼容版本，但 LIVE 必须额外提供最多 45 天的密封 waiver。其他 `strategy_name` 启动前必须设置 `HTX_TREND_STRATEGY_ADMISSION_PATH=/path/admission.json` 和对应的密封 `LiveApproval`；缺失、版本不匹配、证据阶段不足或内容 hash 被改写都会失败关闭。LIVE 仓位按交易所确认权益计算：单笔止损风险不超过 0.5%，总开放止损风险不超过 1.5%，最多三个持仓；权益事实缺失时停止新增风险。

LIVE worker 支持在同一 writer 内同时调度 `INTRADAY_15M` 和 `SWING_4H`。前者固定使用 `15min/60min`，后者固定使用 `4hour/1day`；周期与 profile 不匹配时启动失败。候选、订单和持仓均携带 `timeframe_profile`，持仓只由所属 profile 的管理器读取和退出。账户采用合约互斥：同一合约已有任一 profile 的非终态仓位时，另一 profile 返回 `PROFILE_CONTRACT_MUTEX`，不会叠加或反向接管交易所聚合仓位。硬止损继续使用可执行盘口即时保护，不等待下一根周期 K 线。

```bash
HTX_TREND_PROFILES=INTRADAY_15M,SWING_4H
HTX_TREND_INTRADAY_15M_LIVE_ENABLED=true
HTX_TREND_SWING_4H_LIVE_ENABLED=false
# HTX_TREND_V1 only enables 4h after its dedicated acceptance is complete.
HTX_TREND_ALLOW_LEGACY_4H_LIVE=false
```

关闭某个 profile 的 LIVE 开关仍会计算并保存其候选，标记 `shadow=true/SHADOW_ONLY`，但不会调用真实订单执行器。重新启用后只处理新扫描产生的候选，不补发开关关闭期间的旧候选。

冻结数据的双 profile 同源生命周期研究使用：

```bash
htx-trend profile-research --evidence /path/to/evidence \
  --manifest-hash <complete-dataset-manifest-sha256> \
  --market-facts-manifest-hash <complete-market-facts-manifest-sha256>
```

结果明确标记为 `STRATEGY_SIMULATION`，保存完整交易账本、四种手续费/滑点场景、
60/20/20、walk-forward、置换检验、时间块 bootstrap 与经济性结论；历史
OHLCV 中不存在的盘口和账户事实会作为模拟假设列出。资金费只按冻结的 HTX
逐合约历史结算事件计入，合约面值和价格 tick 来自同一个事实 manifest；缺少
完整事实包时研究入口直接失败，不再按 K 线频率虚构资金费。

R2 的模型独立研究入口为：

```bash
htx-trend intraday-optimize --evidence /path/to/evidence \
  --manifest-hash <six-month-kline-manifest> \
  --market-facts-manifest-hash <six-month-market-facts-manifest>
htx-trend swing-optimize --evidence /path/to/expanded-evidence \
  --manifest-hash <complete-4hour-1day-manifest> \
  --market-facts-manifest-hash <matching-market-facts-manifest>
```

两个入口只使用 TRAIN 选择、VALIDATION 评价，末尾 TEST 保持未读取。4h 研究要求
完整 1d 上下文；缺少已闭合高周期 K 线会记录为 `UNKNOWN` 并拒绝新增风险。当前
P4/P5 均未产生可进入 PAPER/LIVE 的候选。

```bash
python -m htx_trend.worker --strategy-instance <instance-key>
```

双周期 V2 的代码边界、迁移、验证命令和当前未完成的真实时间证据见 [`../docs/architecture-refactor/HTX_PYTHON_DUAL_PROFILE_V2_IMPLEMENTATION_V1.md`](../docs/architecture-refactor/HTX_PYTHON_DUAL_PROFILE_V2_IMPLEMENTATION_V1.md)。新增 worker 启动前还需显式执行 Python 专用迁移 [`sql/2026-10-17__strategy_risk_reservation_stop_risk.sql`](sql/2026-10-17__strategy_risk_reservation_stop_risk.sql) 与 [`sql/2026-10-17__candidate_strategy_identity.sql`](sql/2026-10-17__candidate_strategy_identity.sql)。

本仓库尚未部署 Python、未执行生产 SQL，也未切换真实执行权。首次切换必须先停旧 writer 并确认锁释放，再启动 Python；锁丢失后进程永久禁止新变更，必须重启恢复。上线与回滚流程见 [`../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md`](../docs/architecture-refactor/HTX_TREND_PYTHON_LIVE_SAFETY_V1.md)。

## PAPER 常驻 worker 候选（P1–P5）

`worker --strategy-instance <独立 instance_key>` 读取已有 runtime instance 注册记录。
TREND/PAPER 使用无凭据的公共 HTX 客户端，复用 PaperTrendRuntime、PaperExchange
和 V4 模拟退出；不构造账户客户端、LIVE executor、LIVE writer lock，不签发或要求
LIVE admission/LiveApproval。OBSERVE 和 LIVE 保留原启动路径及授权要求。
受支持的既有策略版本可在 PAPER 面运行，未知版本仍拒绝；此例外不授予 LIVE 权限。

配置示例见 `tests/fixtures/paper_worker.env`：`HTX_PAPER_MAX_ACTIVE_POSITIONS=5`、
`HTX_PAPER_EQUITY_USDT=300`、`HTX_PAPER_MAX_MARGIN_RATE=0.10`、
`HTX_PAPER_CONTRACTS=BTC-USDT,ETH-USDT`，模拟杠杆默认 10。
这些参数通过进程 env 注入，instance_key/strategy_name/execution_mode 由现有 DB 注册记录提供。
容量可下调至 1–5，保证金率必须在 (0,10%]，资金必须为 300U；非法值拒启。
配置仅启动时读取，不提供热更新。无需新增或执行 SQL；本交付不创建注册记录。

`HTX_PAPER_STATE_DIR/<instance_key>.json` 保存独立模拟订单、成交、仓位、决策与已处理 bar。
同一 instance 文件以进程锁保护；完整扫描周期原子落盘，错误周期回滚，重启跳过已完成 bar。
应由运维为每个实例提供持久、私有的状态目录；禁止将不同实例映射成同一 key。
JSON 保存初始化身份/资金配置，变更这些配置须使用新实例。健康状态由现有 supervisor
写回该 instance 的 heartbeat；行情缺失、陈旧或状态落盘失败不能报告 HEALTHY。

每根新收盘 bar 串行管理仓位并检查入场，复用原策略判定和硬风险门。
市价模拟使用当次公共盘口 bid/ask，限价仅在当次盘口范围命中时模拟成交，否则过期；
手续费按合约面值计量。仓位上限、累计保证金和止损风险使用模拟账本事实，
同一合约有仓时不新增，当次平仓不立即重开，已使用结构不可重复开仓。
模拟没有真实撮合排队/部分成交保证；不是 OBSERVE，也不是交易所确认成交。
公共 K 线证据写入现有 evidence store，模拟账本保留独立记录。
这次单测/候选包不能作为 REAL_PAPER≥14d 或 R4/R5 已通过的证明。

离线打包：`python release/package.py build --paper --out-root /tmp/paper-codex/frozen`。
版本为 `0.1.3-paper-candidate-<HEAD前8位>`。`BUILD.json` 给出完整 frozen/归档路径
及 SHA256，`MANIFEST.json` 逐文件绑定实际工作区内容。由于禁止 commit，source_commit
是基线 HEAD，working_tree_dirty 明确为 true，不代表实现已提交。
工作区未 commit，tip8 基于 HEAD；执行器 commit 后可能重跑 package 对齐。
`approval-template` 仅输出未签封的 SYNTHETIC 非真授权模板。
候选包不代表可以装包、热更或启动 PAPER；须另行产品交审及运维开工短令。

## PAPER evidence compact-v2

[运行、盘点、部署与回滚说明](release/EVIDENCE-RETENTION.md)。默认 legacy-v1；新格式显式配置，只影响 PAPER。历史数据无自动删除。

## Offline COS archive

[Verified archive and replay retrieval](COS-ARCHIVE.md) provides an independent Python CLI with approved source snapshots, compressed packs and hash-checked restores. It does not change trading workers or delete local evidence.
