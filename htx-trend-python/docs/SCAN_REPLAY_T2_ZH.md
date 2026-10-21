# T2 离线 runtime.scan 重放与 A+B 复核

## 运行与边界

```bash
PYTHONPATH=src python -m htx_trend.scan_replay --inputs replay_inputs/20261021 --out replay_inputs/20261021/output
PYTHONPATH=src /Users/jenniferni/jenifer/venv-research/bin/pytest -q
```

仅使用内存 ledger、fake 市场与时钟，调用原 `PaperTrendWorker.tick → runtime.scan/manage → evaluate_gate/exits.decide`；不初始化生产 worker，不访问网络、数据库或凭据。进程内临时观察补丁不支持并发运行生产 worker。输出过滤随机 ID，保留源码哈希、完整配置和门/退出输入输出。

输入 CSV 同时受源码 `LOCKED_HASHES` 与 MANIFEST 双重锁定；内容或 MANIFEST 哈希不符拒绝运行。此次仅扩充交易参考 CSV，K 线哈希不变。参考集不参与信号、持仓和退出决策，只用于运行完成后的比较。

## A：forming-bar-open 盘口假设修复

原 worker 按 BTC、ETH 顺序遍历，每币先 manage，再由该币 `had_position` 决定是否 scan。不是全市场统一先 scan 或统一先 exit。BTC 空仓、ETH 有仓时，BTC scan 先于 ETH manage，因此 BTC 的 used_margin 包含尚未平掉的 ETH。

重放继续调用原 worker，不改编排。新增 scan 时的持仓快照和保证金一致性断言：逐仓 entry_price × quantity × contract_size / leverage 的合计必须等于 execution_facts.used_margin_usdt。

回归测试构造 ETH 7 张、入场 2400.74、面值 .01、10x，验证 BTC 09:00 的真实 2 张 EMA 候选被 `RISK_LIMIT / risk.total_margin FAIL` 拒绝（ETH 占用 16.80518U；加 BTC 16.34256U 超过30U），随后同 tick ETH 才退出。

根因是旧重放盘口采用上一根已收盘15m close：ETH 08:15仓在08:45评估时用2398.32，触发保护价突破而提前退出，随后BTC 09:00扫描时保证金已释放。现网该次参考价约2396，不能用旧盘口假设复现。

修复仅限 `OfflineMarket.top_of_book`：存在包含 now 的形成中15m bar时取其open作mid，否则沿用上一根已收盘close，spread计算不变。不读取形成中bar的high/low/close，信号窗口仍只含满足10秒宽限的已收盘线；不改worker顺序，不按参考退出时间强制持仓。

修复后ETH 08:15仓在 **09:00:30** 平仓，原因仍为 `armed-protection-breach-atr-impulse`。BTC 09:00先扫描时ETH仍占用16.80819U（新盘口下入场价2401.17；上述独立回归fixture仍用2400.74），候选被 `RISK_LIMIT / risk.total_margin FAIL` 拒绝，随后ETH正常退出；BTC 09:15入场匹配。该结果是明确盘口假设下的重放一致性，不代表恢复了真实盘口。

## B：12个position与逐腿参考

参考集为窗口内开仓、截至2026-10-21 15:01导出已平仓的12个position。新增 ETH `93cef710`：10-20 23:00:32 LONG DONCHIAN，10-21 13:45:33退出，来源 positions/ENTRY_ORDER及归因报告。未知成交明细和费用留空，不反推为权威成交。

每个position只占一个入场匹配名额；使用币种+入场15m桶定位，方向和主模型独立核验。JSON `trades[].exit_legs` 从实际 reduction fills 提取逐腿数量、时间、价格和退出原因。参考 `exit_legs_json` 给出已知腿；按次序比较每腿数量、退出15m桶和原因，缺腿、多腿均不通过。

BTC 02:15参考两腿来自归因报告§3：09:00:21平1张 profit-at-risk，09:15:54平1张 trend-weakened。实际重放是05:00平1张 profit-at-risk、09:00再平1张 profit-at-risk，属于真实差异，不能因第一腿原因相同便标完整对齐。

其余position只有聚合参考，明确标 `EXIT_REFERENCE_INCOMPLETE`；不把聚合行伪装成逐腿订单。因此完整逐腿通过数为0；这不等于12笔入场全部错误。现有本地 BTC bundle 只含开仓订单/成交，并不含两笔退出订单 ID。

## 当前验收结果

入场 **12/12**，多发 **0**，漏发 **0**；ETH 23:00入场已匹配。同一输入两次独立运行的 `report.json`、`report.md` 逐字节一致。完整逐腿对齐仍为0，原因和证据缺口保留在B及报告中。全量 pytest：**458 passed**，含形成中bar边界、无形成中bar回退、spread保持及保证金拒绝回归。

默认重放至10-21 10:30:59。固定15m行情最后一根开盘12:00、60m最后11:00，无法延长到ETH 13:45退出而仍满足worker完整性/新鲜度检查；不外推行情、不强平。完整报告写入 output/report.md、report.json。

## 保持的假设

- 盘口取证不可得 → 假设：scan_delay 时刻用形成中 15m bar 的 open 作 mid（优于上一根 close）；无形成中 bar 时回退上一根 close；spread=0。无真实滑点或tick路径。
- funding：**取证不可得 → 假设** 0。
- contract_spec：**取证不可得 → 假设** BTC .001、ETH .01（state context佐证），tick .01、可交易。
- 初始空仓、300U、10倍、保证金上限10%、最多5仓；动态权益/费用/占用按原worker计算。
- 固定收盘后30秒调用，10秒收盘宽限，15m/60m均160根完整线；CSV signal_bar_cst是成交桶，候选K线开盘为该时间减15m。
- 隔离环境变量覆盖；不读取参考最终状态回填历史。prior_trade=None，原worker中性市场context及默认退出输入保持不变。
- `require_execution_facts=False`，硬保证金及止损风险门仍启用；门短路后的条件不算通过。未证明本地源码与现网构建逐字节等价。

未修改现网判单、stops、参数或 runtime/paper_worker/execution/exits；未装机、热更、SSH、改PAPER/LIVE/collector/nginx/前端。
