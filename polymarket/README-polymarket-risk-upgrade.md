---
title: "Polymarket 跟单风控升级设计"
type: design
status: active
tags: [polymarket, security]
created: 2026-04-10
updated: 2026-04-10
---

# Polymarket 跟单风控升级设计

## 1. 目标

当前仓库已经具备以下链路：

`leaderboard -> wallet profile -> watchlist -> trade event -> signal risk -> follow task -> execution -> accounting`

现有实现适合做第一代规则风控，但还不能回答下面几个核心问题：

1. 什么样的钱包应该进入监听池，而不是只看历史胜率和 PnL
2. 某个 leader 的行为是否稳定、可复制、可容量化
3. 某笔 leader trade 是否对我们有正期望
4. 某市场短期是否会出现流动性恶化和滑点抬升
5. leader trade 是否真的造成了后续价格移动，而不是只和热度共振

本设计的目标是把 Polymarket 跟单从“规则过滤”升级为“交易级预期收益风控 + 执行风险风控 + 因果验证框架”。

## 2. 当前实现与缺口

### 2.1 当前已有能力

- leader 候选来源于 Polymarket leaderboard，再导入本地画像
- 本地画像字段包括：
  - `win_rate`
  - `realized_pnl_usdc`
  - `total_trades`
  - `average_size_usdc`
  - `average_roi_rate`
  - `max_drawdown_rate`
  - `wallet_score`
- 已有实时 trade event 表、follow task 表、order/fill/position/accounting 表
- 已有规则型 signal risk：
  - 信号新鲜度
  - 触发金额阈值
  - 价格区间
  - 最大持仓数
  - 最大组合成本
  - 市场剩余时间
  - 重复仓位与重复任务拦截
  - 共识钱包数与共识分

### 2.2 当前主要缺口

- 没有盘口快照和微观结构数据，无法做滑点与流动性预测
- 没有 event 后未来价格路径标签，无法做交易级 EV
- 没有“未跟单/对照样本”，无法做可靠的 treatment effect 估计
- wallet score 仍然是手工加权，不是 out-of-sample 的未来有效性预测
- consensus score 只是 watchlist wallet score 的均值，不代表未来收益概率

## 3. 总体分层

建议将风控拆成四层。

### 3.1 Layer A: Leader Admission

决定某个地址是否进入监听池。

输出：

- `watch_enabled`
- `admission_score`
- `admission_probability`
- `leader_style`

### 3.2 Layer B: Trade-Level EV

决定某笔 leader trade 是否值得跟。

输出：

- `follow_probability`
- `expected_alpha_bps`
- `expected_slippage_bps`
- `expected_net_ev_usdc`
- `ev_pass`

### 3.3 Layer C: Execution Risk

决定现在是否应该下单。

输出：

- `liquidity_risk_score`
- `slippage_spike_probability`
- `capacity_ok`
- `execution_pass`

### 3.4 Layer D: Causal Validation

不直接参与线上拦截，但负责回答：

- 哪类 leader 真的能推动价格
- 哪类 market regime 中 leader 有 treatment effect
- 哪些信号只是热门市场噪声

## 4. 模型选择建议

## 4.1 必须先做的基线

- Logistic Regression
- Linear Regression
- Isotonic / Platt calibration

原因：

- 可解释
- 易审计
- 可快速验证标签是否成立
- 可作为 GBDT 的 sanity check

## 4.2 主力模型

- LightGBM
- XGBoost

适用原因：

- 适合当前 tabular + event 数据
- 能处理非线性与特征交互
- 对缺失值和不同尺度特征更稳
- 训练与迭代成本低于序列模型

## 4.3 第二阶段再考虑

- TCN
- Transformer

前提：

- 补齐秒级或更高频的 order book / trade stream
- 有稳定的序列窗口样本
- baseline 和 GBDT 已经遇到明显瓶颈

结论：

第一阶段不建议以 Transformer 为主模型。

## 5. Leader Admission 设计

## 5.1 目标

不是预测“这个地址历史上强不强”，而是预测：

`该地址未来 7/14/30 天是否还能产出值得监听的可复制信号`

## 5.2 标签定义

建议定义三个标签。

### 标签 A: future_followable_rate

未来窗口内，该 wallet 触发的 trade event 中，满足 EV > 0 的比例。

### 标签 B: future_net_alpha

未来窗口内，若按固定规则 paper follow，该 wallet 的净收益。

### 标签 C: future_consistency_flag

未来窗口内，是否持续稳定而不是集中于少数极端收益事件。

## 5.3 特征

### 静态画像特征

- win_rate
- realized_pnl_usdc
- total_trades
- average_size_usdc
- average_roi_rate
- max_drawdown_rate

### 稳定性特征

- 近 7/30/90 天活跃交易数
- 交易频次方差
- 每周有效信号数
- pnl 集中度
- 单市场依赖度
- 单事件依赖度

### 可复制性特征

- 平均触发金额 / 市场深度
- leader trade 后平均滑点
- 进入高热市场占比
- 低流动性市场占比
- 临近结算市场占比

### 行为风格特征

- 平均持有时长
- 是否加仓
- 是否追价
- 是否偏好 BUY/SELL
- 是否偏好 YES/NO
- 是否偏好事件类/体育类/政治类

## 5.4 模型与输出

- 基线：Logistic Regression + calibration
- 主模型：LightGBM
- 输出：
  - `admission_probability`
  - `stability_score`
  - `capacity_score`
  - `leader_style`

## 5.5 监听池准入逻辑

不建议继续使用单一阈值。

建议改为：

- `admission_probability >= threshold`
- `stability_score >= threshold`
- `capacity_score >= threshold`
- `future_followable_rate` 过去滚动回测达标

## 6. Leader 行为分析设计

## 6.1 目标

给每个 leader 打上行为风格标签，避免把本质不同的 leader 混在一起。

## 6.2 推荐风格分类

- `INFORMATION_LEADING`
- `MOMENTUM_CHASING`
- `LIQUIDITY_TAKING`
- `LATE_MARKET_SPECIALIST`
- `CONSENSUS_FOLLOWER`
- `NOISE`

## 6.3 建议指标

- entry timing percentile
- average hold duration
- add-to-winner ratio
- add-to-loser ratio
- trade-after-spread-widen ratio
- trade-before-volume-spike ratio
- market concentration ratio
- event category concentration
- signed price impact after trade

## 6.4 方法

第一阶段：

- 规则统计
- PCA / UMAP 可视化
- KMeans / HDBSCAN 聚类

第二阶段：

- 序列 embedding
- TCN encoder

## 7. 交易级 EV 模型设计

这是最关键的一层。

## 7.1 目标

对每笔 leader trade 估计：

`expected_net_ev = gross_edge - expected_slippage - fees - adverse_move_risk - inventory_cost`

## 7.2 标签定义

建议至少生成三个 horizon。

- `future_mid_move_30s`
- `future_mid_move_5m`
- `future_mid_move_15m`

再定义净收益标签：

- `paper_follow_pnl_30s`
- `paper_follow_pnl_5m`
- `paper_follow_pnl_until_exit_rule`

分类标签：

- `is_positive_ev_30s`
- `is_positive_ev_5m`
- `is_positive_ev_exit_rule`

## 7.3 特征

### leader 特征

- wallet_score
- admission_probability
- leader_style
- recent signal hit rate
- recent alpha decay speed

### 当前 trade 特征

- side
- price
- size_usdc
- size / average_size_usdc
- size / market depth
- time since previous trade by same leader
- same market repeated trade count

### market 状态特征

- current mid
- bid-ask spread
- top-of-book depth
- depth within 1c / 3c / 5c
- order imbalance
- last 30s buy volume
- last 30s sell volume
- realized volatility
- time to market end
- market heat

### 共识特征

- distinct wallets in window
- weighted consensus score
- consensus trigger notional
- same direction event rate

## 7.4 模型

- 基线：
  - Logistic Regression
  - Linear Regression
- 主模型：
  - LightGBM classifier
  - LightGBM regressor
- 概率输出必须做 calibration

## 7.5 线上使用方式

将当前 `SignalRiskDecision` 扩展为：

- `expectedNetEvUsdc`
- `followProbability`
- `expectedAlphaBps`
- `expectedSlippageBps`
- `evDecisionReason`

拦截条件示例：

- `followProbability < min_follow_probability`
- `expectedNetEvUsdc <= 0`
- `expectedSlippageBps > max_expected_slippage_bps`

## 8. 短期流动性 / 滑点模型设计

## 8.1 目标

预测 leader event 后短时间内：

- spread 是否扩大
- depth 是否塌陷
- 我们的真实成交滑点是否上升

## 8.2 标签

- `spread_widen_30s_flag`
- `depth_drop_30s_flag`
- `slippage_spike_flag`
- `realized_slippage_bps`

## 8.3 特征

- 当前 spread
- 各档深度
- 深度斜率
- recent cancel intensity
- recent trade intensity
- signed trade imbalance
- leader size / near-touch depth
- distinct wallet burst count
- market age
- time to market end

## 8.4 模型建议

- 基线：Logistic / Linear
- 主模型：LightGBM
- 输出：校准后的概率

## 8.5 线上用途

作为 execution gate，而不是替代 EV 模型。

示例：

- `slippageSpikeProbability > threshold` 时改为不下单
- 或降低 stake
- 或延迟一定秒数再重新评估

## 9. 因果验证设计

## 9.1 目标

验证 leader trade 是否真的产生可复制的价格推动，而不是与市场热度共同发生。

## 9.2 推荐方法顺序

### 第一层：事件研究

围绕 event time 计算：

- `mid_t+10s - mid_t0`
- `mid_t+30s - mid_t0`
- `mid_t+5m - mid_t0`
- `last_trade_price` 对应变化

按 leader、market category、market regime、size bucket 分层。

### 第二层：匹配 + DiD

为 treatment trade 匹配近似同质对照：

- 同类型市场
- 相近价格区间
- 相近 spread / depth / heat
- 相近 time-to-end
- 相近历史波动率

然后做 DiD，估计 leader trade 对后续价格路径的影响。

### 第三层：DML

在高维协变量下估计 CATE：

- 哪类 leader 有更强 treatment effect
- 哪类 market regime treatment effect 为正

## 9.3 合成控制的定位

适合更粗粒度场景：

- 某 leader 长期活跃在某市场
- 某市场持续受某类 leader 影响

不建议作为逐笔 trade 分析的第一优先。

## 10. 需要补的数据表

建议新增以下表。

## 10.1 `smart_wallet_market_snapshot`

用途：

- 存 event 发生前后 market microstructure

字段建议：

- `id`
- `market_id`
- `outcome`
- `snapshot_at`
- `mid_price`
- `best_bid`
- `best_ask`
- `spread_bps`
- `bid_depth_1c`
- `ask_depth_1c`
- `bid_depth_3c`
- `ask_depth_3c`
- `bid_depth_5c`
- `ask_depth_5c`
- `order_imbalance`
- `last_trade_price`
- `last_trade_size`
- `trade_count_30s`
- `buy_volume_30s`
- `sell_volume_30s`
- `realized_vol_30s`
- `time_to_end_seconds`
- `created_at`

索引建议：

- `(market_id, outcome, snapshot_at)`

## 10.2 `smart_wallet_trade_effect_label`

用途：

- 为每个 leader trade 预计算未来价格路径和纸面跟单收益标签

字段建议：

- `id`
- `source_event_id`
- `market_id`
- `outcome`
- `side`
- `event_at`
- `mid_t0`
- `mid_t_10s`
- `mid_t_30s`
- `mid_t_60s`
- `mid_t_5m`
- `mid_t_15m`
- `last_trade_t_10s`
- `last_trade_t_30s`
- `last_trade_t_5m`
- `paper_entry_price`
- `paper_exit_price_30s`
- `paper_exit_price_5m`
- `paper_exit_price_rule`
- `paper_pnl_30s`
- `paper_pnl_5m`
- `paper_pnl_rule`
- `is_positive_ev_30s`
- `is_positive_ev_5m`
- `is_positive_ev_rule`
- `created_at`

索引建议：

- `(source_event_id)`
- `(market_id, event_at)`

## 10.3 `smart_wallet_wallet_feature_daily`

用途：

- 存 wallet 每日滚动画像，支持 leader admission 训练

字段建议：

- `id`
- `wallet_address`
- `feature_date`
- `trades_7d`
- `trades_30d`
- `markets_30d`
- `pnl_30d`
- `win_rate_30d`
- `avg_size_30d`
- `drawdown_30d`
- `concentration_market_30d`
- `low_liquidity_ratio_30d`
- `late_market_ratio_30d`
- `followable_rate_30d`
- `created_at`

索引建议：

- `(wallet_address, feature_date)`

## 10.4 `smart_wallet_treatment_control_sample`

用途：

- 因果分析用的 treatment / control 配对样本

字段建议：

- `id`
- `source_event_id`
- `control_market_id`
- `control_snapshot_at`
- `match_distance`
- `covariates_json`
- `created_at`

## 11. 训练样本构造原则

## 11.1 时间切分

必须使用时间切分，不允许随机切分。

推荐：

- train: T-180d ~ T-30d
- valid: T-30d ~ T-7d
- test: T-7d ~ T

## 11.2 泄漏控制

- wallet 画像特征只能使用 event 发生前数据
- market microstructure 只能使用 event 当下或更早数据
- 不能把结算结果直接泄漏进短期 EV 模型

## 11.3 样本权重

可对高成交额或高容量 trade 提高权重，但需要防止少数大额样本主导训练。

## 12. 线上决策建议

将当前纯规则 `SignalRiskDecision` 升级为：

- 规则 gate
- EV gate
- execution gate

建议决策顺序：

1. leader admission 先决定监听池
2. 收到 event 后先跑规则 gate
3. 通过后跑 EV 模型
4. EV 通过后跑 slippage / liquidity 模型
5. 最终输出 stake 调整后的 follow task

## 13. 推荐实施顺序

## Phase 1: 数据补齐

- 新增盘口快照表
- 新增 event effect label 表
- 新增 wallet 日画像表
- 为 follow task / fill 生成 paper exit label

这是第一优先级。

## Phase 2: 交易级 EV 模型

- 先做 Logistic / Linear baseline
- 再做 LightGBM
- 上线 calibration
- 输出 `expectedNetEvUsdc`

这是最先产生收益改进的一层。

## Phase 3: 滑点与流动性模型

- 预测短期 spread widening / depth drop / realized slippage
- 接入 execution gate

## Phase 4: leader admission 模型

- 用未来 followable rate 和 future alpha 重建 watchlist 准入逻辑

## Phase 5: 因果验证

- 事件研究
- 匹配 + DiD
- DML

## 14. 与当前代码的直接映射

### 需要优先扩展的现有模块

- `PolymarketWalletDiscoveryService`
  - 从“排行榜导入”升级到“admission scoring + watchlist management”
- `SmartWalletScoringService`
  - 从手工加权分升级到模型分 + 回退规则分
- `PolymarketConsensusSignalService`
  - 保留，但只作为特征来源，不应继续作为主决策依据
- `SmartWalletSignalRiskService`
  - 扩展 EV / slippage / liquidity 预测结果
- `SmartWalletFollowTaskService`
  - 写入模型输出字段和最终 decision trace

### 推荐新增的模块

- `LeaderAdmissionFeatureService`
- `TradeEffectLabelService`
- `MarketSnapshotCollectorService`
- `TradeEvModelService`
- `SlippageRiskModelService`
- `LeaderCausalAnalysisService`

## 15. 第一批开发任务

建议先拆成以下任务：

1. 新增 `smart_wallet_market_snapshot`、`smart_wallet_trade_effect_label`、`smart_wallet_wallet_feature_daily` 表
2. 新增 market snapshot 采集任务
3. 新增 trade effect label 回填任务
4. 新增离线训练脚本目录，先训练 Logistic / LightGBM baseline
5. 扩展 `SignalRiskDecision`，写入 EV 和滑点风险输出
6. 在 follow task 页面展示：
   - `expected_net_ev_usdc`
   - `follow_probability`
   - `slippage_spike_probability`
   - `leader_style`

## 16. 最终判断

对当前仓库来说，最合理的路线不是直接上复杂序列模型，而是：

`补标签 -> 做交易级 EV -> 做滑点风险 -> 重做 leader admission -> 做因果验证`

如果只能先做一件事，优先做：

`trade effect label + 交易级 EV 模型`

因为这一步最直接决定“这笔单到底该不该跟”。

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../docs/MOC-Polymarket.md) ｜ [知识库首页](../docs/知识库首页.md)
