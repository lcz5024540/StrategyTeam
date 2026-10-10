# HTX 趋势模型政策路由冻结回放

> 回放日期：2026-10-10  
> 状态：OBSERVE_ONLY。未访问交易账户，未写生产数据库，未修改任何政策或订单。

## 目的

验证模型路由记录改动能否回答真实问题：每个 Donchian、Fibonacci、EMA/VWAP 候选究竟是通过政策、没有对应政策，还是已有政策但原始分数不足。

本回放读取服务器当前启用政策的只读导出，在 52 个币种、2026-07-03 至 2026-10-03 的完整 15 分钟冻结 K 线上，按 Java 同源候选合同重建：

1. 组合候选；
2. primary_model + confirmation_models 精确匹配；
3. 原始分数相对 score_threshold 的判断。

它不重放资金费、盘口、保证金、仓位容量、实际下单和退出 v4。因此结果是**政策路由漏斗**，不是收益回测或实盘授权。

## 当前启用政策

| 政策 | 主模型 | 确认结构 | 分数门槛 |
|---|---|---|---:|
| DONCHIAN_BOX | DONCHIAN_BREAKOUT | BOX_BREAKOUT | 85 |
| FIB_BOX | FIB_PULLBACK_CONTINUATION | BOX_BREAKOUT | 85 |
| FIB_BOX_DONCHIAN | FIB_PULLBACK_CONTINUATION | BOX_BREAKOUT,DONCHIAN_BREAKOUT | 90 |
| FIB_DONCHIAN | FIB_PULLBACK_CONTINUATION | DONCHIAN_BREAKOUT | 85 |

没有 EMA/VWAP 的启用政策，也没有 Donchian 的 TREND_CONTEXT 政策。

## 结果

| 漏斗阶段 | 数量 |
|---|---:|
| 有方向的 completed-bar 信号 | 201,958 |
| 生成模型候选 | 218,527 |
| POLICY_PASSED | 42,581 |
| POLICY_SCORE_BELOW_THRESHOLD | 27,869 |
| POLICY_NOT_MATCHED | 148,077 |

### 按模型和确认结构

| 模型与确认结构 | 通过政策 | 分数不足 | 无政策 |
|---|---:|---:|---:|
| DONCHIAN_BREAKOUT + BOX_BREAKOUT | 17,428 | 0 | 0 |
| DONCHIAN_BREAKOUT + TREND_CONTEXT | 0 | 0 | 3,123 |
| EMA_VWAP_MOMENTUM_CONTINUATION + EMA_STACK,MOMENTUM | 0 | 0 | 144,954 |
| FIB_PULLBACK_CONTINUATION + BOX_BREAKOUT | 4,190 | 13,484 | 0 |
| FIB_PULLBACK_CONTINUATION + BOX_BREAKOUT,DONCHIAN_BREAKOUT | 16,773 | 901 | 0 |
| FIB_PULLBACK_CONTINUATION + DONCHIAN_BREAKOUT | 4,190 | 13,484 | 0 |

## 结论

“实盘看起来只走 Fibonacci”不能简单归因于候选未生成。

- EMA/VWAP 的确会大量生成候选，但当前没有任何授权政策，因此全部停在 POLICY_NOT_MATCHED。
- Donchian 并非完全关闭：BOX_BREAKOUT 已有政策，冻结回放中有 17,428 次可通过；TREND_CONTEXT 没有政策。
- Fibonacci 有完整政策覆盖，但多数 BOX 与 DONCHIAN 单确认候选分数不足；双确认结构更容易达到 90 分。

本次 Java 改动会把这些过去被压缩为单条 NONE / POLICY_NOT_MATCHED 的情况，逐个写入 htx_trend_candidate_signal：

- 无结构政策：POLICY_NOT_MATCHED；
- 结构已授权但分数不足：POLICY_SCORE_BELOW_THRESHOLD；
- 通过政策：保留原有 PASSED 和后续真实 Gate 记录。

它不改变真实候选是否下单。部署后，数据库可以直接统计“模型机会不足”与“政策覆盖不足”，而不再依赖服务日志猜测。

## 后续

1. 先部署该观察改动并积累新格式的真实候选账本。
2. Donchian BOX 不能因候选多而直接扩大实盘；需要结合后续 Gate 与扣费后表现验证。
3. EMA/VWAP 需先补同源完整回放，达到模型级样本外准入后才考虑新增政策；不能直接解除 POLICY_NOT_MATCHED。

## 可复现

- 工具：htx-trend/tools/htx_trend_policy_route_replay.py
- 输入：htx-trend/research-data/20260703-20261003-entry-regime-ablation-v1/（Git 忽略）
- 只读政策导出：/tmp/htx-current-enabled-policies-20261010.json
- 回放输出：/tmp/htx-policy-route-replay-20261010.json
