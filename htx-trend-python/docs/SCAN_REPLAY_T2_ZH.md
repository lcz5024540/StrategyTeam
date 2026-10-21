# T2：完整 PAPER runtime.scan 离线重放

工具直接运行现有 `PaperTrendWorker.tick()`，由它调用 `PaperTrendRuntime.scan()` 和 `manage()`，不复制策略、路由、风险判单或退出规则。没有修改生产模块，也没有增加生产注入点。

## 运行

在 `htx-trend-python/` 下，使用已经安装项目依赖的 Python 3.11 或更新版本：

```sh
PYTHONPATH=src python3.11 -m htx_trend.scan_replay \
  --inputs replay_inputs/20261021 \
  --out replay_inputs/20261021/output
python3.11 -m pytest -q
```

本次验证使用本机已有 `/Users/jenniferni/jenifer/venv-research/bin/python`，全套 **433 passed**。系统 `python3` 为 3.9，不能运行该项目；Homebrew Python 3.11 缺少部分测试依赖。没有联网安装依赖。

`klines.csv` 和 `trades_closed_window.csv` 按原始字节固定。`MANIFEST.json` 列出 SHA256，模块内另外锁定两个哈希，改 CSV 后连同修改 MANIFEST 也会拒绝运行。`--inputs` 可指向固定输入的另一份副本；新数据集需要单独审核并更新锁定值。输入路径只读，只有 `--out` 下的 `report.json` 和 `report.md` 会写入/覆盖。

## 复用边界

- 用内存 ledger 继承 `PaperFileLedger` 的持仓、订单、成交和上下文状态转换；跳过文件构造函数，checkpoint 不执行文件/数据库写入。
- 用原 `WorkerPaperExchange` 执行盘口成交、合约面值手续费和部分平仓。
- 用无客户端的 `OfflineMarket` 提供 K 线、合约、盘口和 funding；没有 HTTP、数据库、私钥或生产连接配置访问。
- 用固定时钟驱动原 worker，每个收盘时点按 BTC、ETH 顺序执行。假对象替换与环境隔离使用作用域内的 patch，退出时还原。不能在同一进程并发运行此工具或生产 worker。
- 只观察原 `evaluate_gate(condition_trace=...)` 和 `exits.decide()` 的输入输出；前者仍只执行一次，结果原样返回。额外的纯策略诊断不参与下单。
- 包版本取自 `pyproject.toml`，输出所有包源码 SHA256、完整 entry/execution 参数和输入哈希。当前包 `0.1.0` 与输入描述的现网构建 `0.1.6-paper-db-c79afbaa` 分别记录，未证明二者代码/环境完全一致。

## 时间、路由和持仓语义

K 线 `bar_time` 是 Unix 秒的开盘时间。15m/60m 窗口均为最近 160 根完整已收盘线，采用现有 10 秒收盘宽限，并检查连续性；不会使用正在形成的小时线。先前数据只用于指标预热，默认从 2026-10-19 21:35:46 CST 之后开始驱动交易，至 2026-10-21 10:30:59 CST。缺少窗口数据时原 worker 拒绝执行，不跳过缺口。

默认在 15m 收盘后 30 秒调用。参考 CSV 的 `signal_bar_cst` 与成交桶一致，例如 03:15:40 成交对应 CSV 03:15；本工具同时输出成交桶 03:15 与实际候选 K 线开盘 03:00。该时间解释是明确假设，不能把参考文件中的 `offline_match_side` 当作本工具判定。

三个独立模型候选及确认模型由原策略生成，按原 `route_candidates` 排名，再选排名中第一个通过门的候选。`trend_window` 虽通过完整性检查，当前 scan 并不使用它判趋势。`market_context` 保持原 worker 的中性默认。

原 worker 在 tick 开始已有同币持仓时只执行持仓管理，哪怕当次完全平仓也不开新仓；历史所有 position context 的 `structure_key` 持续去重。原 worker 没有给 scan 传 `prior_trade`，因此 cooldown 函数接收 None，不能凭空添加“一小时禁止开仓”。同币间隔是上述实际门和持仓路径的结果。

账户权益和占用保证金、手续费、总止损风险均按原 worker 动态计算。`hard_risk_limits_enabled=True`，包括总保证金不超过配置本金 10% 等门。另一个 `require_execution_facts` 开关保持默认 False：其控制的独立可用保证金、深度、逆市、FIB 质量等检查不会启用。报告保留原始 trace（有些未启用检查原函数也会 emit PASS），因此务必结合完整配置和 `not_evaluated_or_disabled` 阅读，不能把原始 PASS 理解为缺失真实数据已验证。

管理和部分/全量退出直接复用 `manage → exits.decide`，跟踪最高/最低、保护价、盈利保护和剩余仓数；原 worker 的 opposition=0、flat_streak=0、protection_breach_bars=0 保持不变。最后未平仓不强行平仓。

## 缺失输入和可配置假设

通过 `--assumptions path/to/assumptions.json` 传入字段覆盖；不能用这个文件改判单阈值。示例：

```json
{
  "scan_delay_seconds": 46,
  "spread_rate": "0.0002",
  "funding_rate": "0.0001",
  "btc_contract_size": "0.001",
  "eth_contract_size": "0.01",
  "price_tick": "0.01",
  "equity": "300",
  "leverage": "10",
  "margin_rate": "0.10",
  "max_active_positions": 5
}
```

默认假设如下，均写入输出：

- 固定收盘后 30 秒，实际现网观察为 12–46 秒；不会用参考成交时刻作为预测输入。
- bid=ask=最后已收盘 15m close；spread 默认 0，覆盖时以 close 为中价。没有深度、真实盘中成交路径和滑点。
- funding=0；BTC 面值 .001、ETH .01，tick=.01，合约状态正常。
- 初始空仓、无历史订单、历史结构键或历史损益；本金 300U、10倍、总保证金上限10%、最多5仓。后续按原 worker 更新，不强制保证金永远充足。
- turnover 与盘口深度缺失；平均成交额门默认关闭，费率与其他阈值采用当前源码默认值；原 PAPER 成交费率 .0004。
- 环境变量隔离，不继承主机的 HTX 参数覆盖，不读取参考 `state.json` 的最终状态来回填过去。初始状态未知及现网环境差异可能导致路径分歧。
- 参考集只有 11 笔窗口内已平仓交易，并非完整现网开仓/订单集合；“多发”只能表示参考集中未观察到，不能证明现网未开仓。缺失真实盘口时，归因仅说明重放实际阻断门/退出分支，不虚构现网原因。

## 结果和阅读方式

默认结果：完整对齐 **9/11**，方向/模型入场对齐 **10/11**；相对参考集多发 **3**、漏发 **1**。完整对齐要求同币同成交桶、方向、模型、最终退出原因一致；不要求成交价格、数量和退出时刻一致。部分退出路径仍完整保存在 JSON。

两笔不符：

1. 10-20 09:15 BTC SHORT/DONCHIAN：重放 09:00 已开 EMA SHORT，09:15 tick 开始有仓，`HAD_POSITION_AT_TICK_START` 阻止 scan。报告给出阻挡持仓的开仓时刻、方向和模型。
2. 10-21 02:15 BTC LONG/EMA：入场对齐，重放在 09:00 以 `profit-at-risk-with-deteriorating-continuation` 完全退出；现网在 09:15 以 `trend-weakened-protection-breach` 退出。重放末次退出 reference=82983.6，高点回撤185.4、ATR约125.957、状态WEAKENING、剩余1张；新保护价约82964.706尚未被跌破，进入盈利回撤且延续恶化分支。真实盘口缺失，不能将它断言为现网缺陷。

多发为 10-20 BTC 09:00、BTC 10:00、ETH 23:00。它们的完整门结果、实际值、阈值、账户事实和结构键见 `extras[].bar_evidence`。

JSON 的 `bars` 覆盖逐根两个币种，`alignment` 给出每笔现网对应证据，`missing`/`extras` 提供双向检验，`trades[].exit_decisions` 包含退出函数完整输入输出。没有候选或原 worker 阻止扫描时，候选门未执行；诊断候选若存在，由 `candidate_diagnostics_only` 标明。门的短路后续项不记成通过。

测试涵盖双重哈希锁、两次完整运行确定性、断网/禁数据库/禁生产ledger初始化、真实交易对齐、反向增删及模型不符、15m/60m无未来数据、601秒真实stale门拦截。没有修改现有测试或削弱风险断言。
