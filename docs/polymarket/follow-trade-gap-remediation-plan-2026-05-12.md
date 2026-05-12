# Polymarket 跟单链路漏洞整改方案

> 日期：2026-05-12
> 范围：`POLYMARKET_COPY`
> 目标：修复当前跟单策略对分批建仓、减仓/反手、同市场对冲、画像失真、市场暴露失真的处理缺口

---

## 一、结论摘要

当前实现的核心假设仍然偏向：

1. leader 是一次性单腿入场；
2. follower 只负责入场，退出主要靠自身止盈止损；
3. 同一市场只能持有单方向风险；
4. `marketId + outcome` 可以近似代表一笔完整订单闭环。

这套假设会直接导致以下偏差：

1. leader 的 `SELL`、部分减仓、反手、merge 式退出、反向对冲不会被真实跟随；
2. 同地址同市场的多笔小单/分批补仓会被拆散或被重复任务门控拦掉；
3. 行为画像和 truth 统计会把多轮 round-trip、partial close、双腿对冲压扁成单笔闭环；
4. 市场风险按毛成本计，不按净敞口计；
5. 一些依赖“仓位变化节奏”的优质地址会被误判为不可复制，或被错误高估。

---

## 二、整改目标

### P0 止血目标

1. 不再把 leader 的退出/减仓/反手信号直接丢弃。
2. 不再把 leader 的分批建仓误判成“重复任务”或“低质量碎片信号”。
3. 让 follower 的市场级风险口径至少区分“加方向风险”和“降方向风险”。

### P1 建模补齐目标

1. 跟单链路从“事件触发”升级为“仓位变化量触发”。
2. 行为画像和 truth 统计从“单 market/outcome 闭环”升级为“多腿、多轮次、可重开”的交易会话建模。

### P2 稳定化目标

1. 引入 hedge-aware 的行为画像、真值评估、退出评分。
2. 完成灰度与回溯验证，逐步替换现有单腿简化逻辑。

---

## 三、问题拆解与修复方案

## P0-1：补上 leader exit / reduce / reverse 的真实执行链路

### 问题

当前 `SELL` 和 `OPPOSITE_BUY` 会被分类成 `DYNAMIC_FOLLOW_EXIT`，但执行层直接 `SKIPPED`，不会真正对 follower 仓位做减仓、清仓、反手或 rebalance。

### 目标

把 `DYNAMIC_FOLLOW_EXIT` 从“只做标记”改为“生成可执行的仓位调整请求”。

### 方案

1. 新增 `FollowPositionAdjustmentService`。
2. 输入不再是“新开仓信号”，而是：
   - `SOURCE_SELL`
   - `OPPOSITE_BUY`
   - 后续可扩展 `SOURCE_MERGE_EXIT`
3. 服务根据我方现有仓位生成调整动作：
   - `FULL_EXIT`
   - `PARTIAL_EXIT`
   - `REDUCE_THEN_REVERSE`
   - `NO_POSITION_SKIP`
4. `SmartWalletFollowTaskService.executeTask()` 遇到 `DYNAMIC_FOLLOW_EXIT` 时不再 `SKIPPED`，而是转调用该服务。
5. 先只支持 PAPER 和 LIVE 的减仓/平仓，不在 P0 引入双腿对冲自动建仓。

### 涉及文件

1. `coin-strategy-job/.../SmartWalletFollowTaskService.java`
2. 新增 `coin-strategy-job/.../FollowPositionAdjustmentService.java`
3. `coin-strategy-job/.../SmartWalletAccountingService.java`
4. `coin-strategy-job/.../PolymarketClobExecutionService.java`

### 验收用例

1. leader `SELL 30%`，follower 存量仓位同步减仓 30%。
2. leader `SELL 100%`，follower 全部平仓。
3. leader 买入反向 outcome，而 follower 持有原 outcome，先平原仓，不再直接跳过。
4. follower 无持仓时收到退出型信号，不报错，不新开仓。

### 风险

1. leader 的 `SELL` 事件不一定等于减仓比例，需要结合 leader 持仓快照估算 delta。
2. 如果不做 leader 实时持仓对账，部分减仓只能先做近似比例同步。

---

## P0-2：把分批建仓从“重复事件”改成“意图聚合”

### 问题

当前链路默认一笔事件就是一笔完整信号。leader 的试单、冰山单、补仓单会被：

1. `minTriggerSizeUsdc` 过滤；
2. `duplicate pending task` 拦截；
3. `maxOrdersPerMarketOutcome` 限制；
4. 在我方尚未形成持仓前无法进入 scale-up 路径。

### 目标

对同 wallet + market + outcome + side 的短窗口事件做聚合，优先识别“真实建仓意图”。

### 方案

1. 新增 `FollowIntentAggregationService`。
2. 聚合维度：
   - `walletAddress`
   - `marketId`
   - `outcome`
   - `side`
   - 时间窗口默认 5-10 分钟
3. 聚合结果生成 `intentKey`，跟单任务不再只绑定单个 `sourceEventId`，而是绑定：
   - `primarySourceEventId`
   - `intentKey`
   - `aggregatedTriggerSizeUsdc`
   - `fragmentCount`
4. `duplicate pending task` 校验改为：
   - 同 `intentKey` 内增量更新
   - 非同 `intentKey` 才视作重复冲突
5. 白名单聚合和普通地址聚合统一建模，不再只对白名单做“小额聚合”。

### 涉及文件

1. 新增 `coin-strategy-job/.../FollowIntentAggregationService.java`
2. `coin-strategy-job/.../SmartWalletFollowTaskService.java`
3. `coin-strategy-job/.../SmartWalletSignalRiskService.java`
4. 需要新增字段的实体与 migration：
   - `smart_wallet_follow_task`
   - 可选新增 `smart_wallet_follow_intent`

### 验收用例

1. 5 分钟内 4 笔同方向小单被聚合成 1 个跟单意图。
2. 首笔 2 USDC、后续累计到 18 USDC 后通过准入。
3. intent 已存在时，后续补仓更新 stake 或追加计划，不再新增冲突任务。
4. 窗口外的新一轮建仓可以重新生成新 intent。

---

## P0-3：市场风险先从“毛成本”升级到“方向性净暴露”

### 问题

当前市场暴露只按 `marketId` 汇总 open cost，YES/NO 双腿完全不 netting。若预期是保守口径，这没问题；若预期是“对冲降低风险”，则不符合预期。

### 目标

至少让风控能区分：

1. 同方向继续加风险；
2. 反方向降低净风险；
3. 双腿都开后处于低净敞口状态。

### 方案

1. `FollowExposureLimitService` 增加两套口径：
   - `grossMarketExposure`
   - `netDirectionalExposure`
2. P0 阶段不直接把双腿视作零风险，而是：
   - 继续保留 gross cap
   - 追加 net cap
   - 反向腿如果明显降低净暴露，则允许更宽松的 clip
3. `SignalRiskDecision` 和 blocker reason 输出 gross/net 两个值。

### 涉及文件

1. `coin-strategy-job/.../FollowExposureLimitService.java`
2. `coin-strategy-job/.../SmartWalletSignalRiskService.java`
3. `coin-strategy-job/.../SmartWalletPositionSyncService.java`

### 验收用例

1. 已有 YES 仓位时再买 YES，按加风险处理。
2. 已有 YES 仓位时买 NO，按降净风险处理。
3. gross 爆表但 net 很低时，策略日志能明确展示是哪条规则拦截。

---

## P1-1：从 event 跟单改成 position delta 跟单

### 问题

只看 trade event 很难区分：

1. 新开仓；
2. 补仓；
3. 部分止盈；
4. 全平；
5. 反手；
6. 同市场多腿对冲。

### 目标

跟单信号从“看到一笔 trade”升级为“识别 leader 仓位净变化”。

### 方案

1. 新增 leader position snapshot / delta 计算层。
2. 每次同步时按 `wallet + market + outcome` 生成：
   - `prevQty`
   - `currQty`
   - `deltaQty`
   - `deltaNotional`
   - `deltaIntentType`
3. `deltaIntentType` 标准化为：
   - `OPEN`
   - `ADD`
   - `REDUCE`
   - `CLOSE`
   - `REVERSE`
   - `HEDGE_ADD`
4. Follow task 只消费 delta，不再直接消费原始 trade event。

### 依赖

1. 需要 leader active position 同步足够稳定。
2. 需要明确 Polymarket API 对 position 快照的一致性边界。

### 验收用例

1. 连续三笔 BUY 被识别为 `OPEN + ADD + ADD`。
2. 先买 YES 再买 NO，被识别为 `HEDGE_ADD` 而不是重复入场。
3. 先买后卖部分，被识别为 `REDUCE`。
4. 同 market 原方向清空后买 opposite，被识别为 `REVERSE`。

---

## P1-2：重做订单聚合和 closed-order 会话模型

### 问题

现在 `marketId + outcome` 被当成一笔闭环订单的 key，会丢掉：

1. 同市场同 outcome 多轮 round-trip；
2. partial close 后 reopen；
3. 多腿对冲的配对关系；
4. hold time / exit style / win/loss 的真实粒度。

### 目标

把一条“交易会话”从静态 key 升级成可重入、可部分关闭的 session。

### 方案

1. 新增 `wallet_trade_session` 概念。
2. session key 不再只靠 `marketId + outcome`，而是基于时间序列状态机：
   - 0 -> 正净仓：session open
   - 正净仓 -> 缩小：partial close
   - 正净仓 -> 0：close
   - 0 -> 再次正净仓：new session
3. YES/NO 双腿拆成两个 leg，同时挂到同一个 market session 下。
4. `WalletOrderAggregationService` 改成生成：
   - `TradeSession`
   - `TradeLeg`
   - `ClosedSessionSnapshot`

### 涉及文件

1. `coin-strategy-job/.../WalletOrderAggregationService.java`
2. `coin-strategy-job/.../SmartWalletOrderAnalysisService.java`
3. `coin-strategy-job/.../LeaderTradeOutcomeRebuildService.java`
4. 需要新增或重构相关表结构

### 验收用例

1. 同 market/outcome 两次独立 round-trip 形成两条 closed session。
2. partial close 不会把整笔会话提前判定为完结。
3. YES/NO 双腿可以落在同一个 market session 下分析。

---

## P1-3：画像与 truth 统一切到 session 口径

### 问题

现有画像统计的 `dedupOrderCount`、`earlyExitRate`、`settlementCompletionRate`、`settlementWinRate` 以及 truth 的 `trustedClosedOutcomes` 都仍然基于旧闭环口径，容易失真。

### 目标

行为画像、truth、wallet score 使用同一套 session 结果。

### 方案

1. `WalletBehaviorProfileService` 的样本来源改成 `ClosedSessionSnapshot`。
2. `SmartWalletProfileTruthRefreshService` 的可信样本改成 session truth，不再用旧 `marketId+outcome` 聚合结果做兜底。
3. 增加 hedge-aware 特征：
   - `hedgeUsageRate`
   - `reduceDisciplineScore`
   - `reverseAccuracy`
   - `multiLegCompletionRate`

### 验收用例

1. 高频对冲地址不再因为 `marketId+outcome` 去重被错误压缩样本数。
2. 部分止盈型地址不会被误判成“极端早退、不可复制”。
3. truth 的亏损回补统计不再只检查旧 key 缺失。

---

## P2-1：退出评分引入反向腿、对冲腿与 merge 语义

### 问题

当前 exit scoring 仍主要看“同 outcome 的后续 BUY/SELL 和该 outcome 的 consensus decay”，没有把：

1. leader 增加 opposite leg；
2. leader merge 退出；
3. 同市场净敞口下降；
4. 双腿平衡后的风险变化

纳入退出逻辑。

### 方案

1. 扩展 `ExitScoringContext`。
2. 新增：
   - `oppositeLegAdded`
   - `netExposureReduced`
   - `mergeExitDetected`
   - `sameMarketHedgePressure`
3. 止盈止损之外，支持 “leader risk-off exit” 路径。

---

## 四、推荐实施顺序

### 第一阶段：先做 P0

执行顺序：

1. `P0-1` 动态退出执行链路
2. `P0-2` 分批建仓意图聚合
3. `P0-3` gross/net exposure 双口径

原因：

1. 这是当前真实资金偏差最大的三块；
2. 不解决 P0，后面的画像和 truth 会继续吃脏数据；
3. P0 完成后，系统才开始“像一个 position follower”，而不是“只会开首单的 event follower”。

### 第二阶段：再做 P1

1. 先把信号源改成 position delta；
2. 再重做 order/session 聚合；
3. 最后把画像和 truth 切到新口径。

### 第三阶段：最后做 P2

把 hedge-aware 退出评分、画像、灰度验证做完，逐步下掉旧逻辑。

---

## 五、建议本周直接开工的任务单

### Task-1：动态退出执行链路

1. 补 `DYNAMIC_FOLLOW_EXIT` 的 PAPER/LIVE 执行逻辑。
2. 增加对应单测：
   - source sell partial
   - source sell full
   - opposite buy with existing open position

### Task-2：分批建仓聚合

1. 增加 `intentKey` 和聚合窗口。
2. 修改重复任务判定和 follow task 生成逻辑。
3. 增加单测：
   - multiple small buys within window
   - pending task updated instead of duplicated

### Task-3：风险口径改成 gross + net

1. 暴露服务增加净敞口计算。
2. 跟单风控和日志输出同步调整。
3. 增加单测：
   - same-side add
   - opposite-side hedge

---

## 六、验收口径

完成 P0 后，至少满足以下条件：

1. leader 的 `SELL` 不再被直接丢弃。
2. 同地址同市场的分批建仓不会被简单视作重复任务。
3. YES/NO 反向腿能被识别为“降净风险”或“退出意图”，而不是一律阻断。
4. PAPER 回放里，leader 的仓位变化轨迹与 follower 的仓位变化方向基本一致。
5. 新增单测覆盖：
   - 分批建仓
   - partial reduce
   - reverse
   - same-market hedge

---

## 七、当前不建议立即做的事

1. 现在不要直接放开“双腿自动对冲建仓”到 LIVE。
2. 现在不要在旧画像口径上继续调阈值。
3. 现在不要只靠 runtime config 调参解决问题，因为问题核心不是阈值，而是交易语义建模缺失。

---

## 八、落地建议

建议按两个 PR 推进：

1. PR-1：`dynamic-follow-exit + intent-aggregation + exposure-netting`
2. PR-2：`position-delta-model + session-based-profile-truth`

这样拆分的好处是：

1. P0 可以先上线止血；
2. P1 是模型层重构，不和执行层高风险变更混在一起；
3. 回归测试和灰度范围更可控。
