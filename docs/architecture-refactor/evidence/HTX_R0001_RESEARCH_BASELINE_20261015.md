# R0001 研究基线与 4h provisional 参数记录

取证时间：2026-10-15  
来源：服务器 `/opt/coinstrategy/htx-trend-py-state/configs/runs/R0001.yaml`、冻结质量文件；本文件只记录基线，不宣布统计通过，也不授权 LIVE。

## 已确认的 R0001 研究设定

| 项目 | 值 |
|---|---|
| 研究周期 | `4hour` |
| 品种 | `BTC-USDT`、`ETH-USDT` |
| 假设 | 时间序列动量：Donchian ensemble（42/84/126/168）结合 ATR14×2 止损捕捉中期趋势 |
| C01 | Donchian periods = 42、84、126、168；threshold = 0.25 |
| C02 | ATR period = 14；ATR multiplier = 2.0；tick size = 0.1；止损滑点 = 1 tick |
| 风险 | 每笔 0.5%；总风险 1.5%；最大 3 仓 |
| 成本 | `base`；配置注释为 maker 0.02%、taker 0.06%，市价入场按 taker |
| 杠杆统计目标 | 5.0 |
| 验收假设 | 首要验证流程可复现，不以单次盈利作为保留条件 |

## 数据冻结事实

服务器已有两套 R0001 质量证据，均显示 `ok=true`、BTC/ETH 4h 无 gaps、确认 bad ticks 11 条（BTC 6、ETH 5）。旧冻结目录的 dataset 路径是 `4hour_da62bd85ac0728b9`；refreeze 目录登记为 `4hour_8024e8fc92d12d85`，并包含 funding/spec 重新冻结证据。生产配置文件仍把 `dataset_id` 和 `manifest_hash` 写成 `TODO_FREEZE_*` 占位，因此配置文件本身不能作为已锁定回测输入。

## A15 provisional 参数结论

可以把上述设定作为 **4h 研究候选基线**，用于回放和参数敏感性分析；在 C01/C02 的固定 seed、成本口径和分片结果完成前，不得把它写入正式 LIVE 环境。特别是以下内容还不能视为生产参数：

- Donchian 42/84/126/168 的组合权重/投票方式；
- ATR 止损在同一根 K 线同时触发入场、止损和目标时的优先级；
- 真实账户手续费率、滑点和合约规格；
- 4h 与 1d 市场状态的确认关系；
- 多仓并发时的相关性和总风险分配。

## C03 研究复盘状态

当前已完成“研究设定和冻结质量证据收集”，尚未完成验证集权益/PF/分品种成交与跳过统计，也没有把单次 PF 当作统计门禁。下一步必须：

1. 用 refreeze 后唯一确认的 dataset/manifest 生成固定 `run_id`；
2. 在 train/validation/test 分界上做同源回放；
3. 记录无成本、基准成本、保守成本三档净收益；
4. 再执行 C01 permutation 和 C02 bootstrap；样本不足时标为“待定”；
5. 结果只进入研究报告，不自动改变 shadow 或 LIVE gate。

## 当前判定

- **A15：已形成 provisional 参数清单，未放行。**
- **C03：基线证据已收集，研究复盘未完成。**
- **C01/C02：仍待执行。**
- **R0001 统计门禁：未锁定。**
