# FundingPaper 真实深度模拟成交与收益核算技术设计

| 项 | 内容 |
|---|---|
| 文档类型 | 架构设计（Technical Design Document） |
| 文档状态 | Approved v1.0（2026-09-02 用户补充账户、杠杆与日报要求） |
| 作者 | CoinStrategy / Codex |
| 撰写日期 | 2026-09-02 |
| 目标读者 | 策略、后端、前端、测试、运维 |
| 前置文档 | [套利看板设计](ARBITRAGE_DASHBOARD_DESIGN.md)、[Paper 运行手册](CROSS_EXCHANGE_PAPER_ARBITRAGE_RUNBOOK.md) |
| 配套图示 | 3 张 draw.io，见 [§11.1](#111-图示索引) |
| 安全边界 | 仅 Paper；只读公开行情；不接私有下单、不撤单、不平仓真实仓位、不修改生产交易开关 |

---

## 1. 背景

### 1.1 业务背景

资金费套利只有在两条腿按真实可成交价格成交、持仓期间资金费正确入账、平仓再次按真实深度退出后，收益才有决策价值。当前扫描器已经能按 L2 计算候选机会的共同基础币数量和 VWAP，但 Paper 开平仓仍缺少逐档成交事实与完整成本账本。因此，页面展示的收益可能低估大额订单吃档、平仓冲击和补偿腿损耗。

用户给出的验收场景是：订单簿 10 档累计约 20,000U，提交 2,000U 时，系统必须明确吃到哪些档、每档成交多少、最终 VWAP 和滑点多少，并把开仓与平仓的实际结果纳入收益。

### 1.2 现状与差距

| 差距编号 | 描述 |
|---|---|
| G-01 | `CrossExchangeOpportunitySelector` 扫描时计算 VWAP，但开仓请求没有把用户指定金额贯穿到重报价。 |
| G-02 | `PaperAtomicHedgeExecutor` 使用机会记录中的价格，`PaperPerpetualGateway` 只应用模拟 fill ratio，不读取并逐档消耗最新 L2。 |
| G-03 | 第二腿虽跟随第一腿成交数量，但没有基于第二腿当时的新订单簿重新逐档定价。 |
| G-04 | 平仓默认使用 Mark Price，没有按“卖出多腿吃 bids、买回空腿吃 asks”计算退出 VWAP。 |
| G-05 | 现有订单表只有请求量、成交量、均价，缺少盘口时间、最优价、最差成交价、档数、滑点和逐档 fill。 |
| G-06 | 手续费、资金费、基差收益与滑点没有进入同一个不可变账本，容易漏算或重复扣减。 |
| G-07 | 页面缺少从预览报价到实际成交、再到最终净收益的可审计证据。 |

### 1.3 本次交付范围

本设计覆盖 G-01 至 G-07：建立统一逐档扫盘引擎、开仓/平仓/补偿执行状态机、不可变成交和 PnL 账本、精确 API 与页面字段、数据库迁移和分阶段验证方案。实现、部署与 Paper 跑仓必须在本设计审核通过后另行执行。

不在范围：真实交易所私有下单、充值提现、自动开仓调度、生产交易配置变更、跨币种保证金模型、订单排队位置模拟。

### 1.4 商业性思考

| 编号 | 内容 | 说明 |
|---|---|---|
| BIZ-G-1 | 消除虚假盈利 | 大额 Paper 收益必须反映开平仓真实深度和费用。 |
| BIZ-G-2 | 服务策略审核与容量决策 | 让操作者知道 100U、500U、2,000U 是否仍有净收益。 |
| BIZ-G-3 | 可量化 KPI | 100% Paper 仓位可由逐档成交与账本重算；账本误差小于 0.01U。 |
| BIZ-C-1 | 研发成本 | 预计 5 个里程碑、约 7–10 个开发日，复用现有 Java/MySQL/React。 |
| BIZ-C-2 | 收益 | 避免因忽略退出冲击而扩大不可盈利仓位；为自动开仓提供可信基础。 |
| BIZ-C-3 | ROI | 以避免一次错误 2,000U 仓位的成本作为最低收益口径，不承诺投资回报。 |
| BIZ-M-1 | 对比现状 | 优于仅按 Mark/Mid 或固定 bps 扣减的轻量 Paper 模型。 |
| BIZ-M-2 | 差异化 | 两腿共同基础币、逐档证据、资金费与现金流统一对账。 |
| BIZ-M-3 | 时机 | 当前已具备公开 L2 与 Paper 状态机，正适合补齐执行真实性。 |
| BIZ-R-1 | 合规边界 | Paper 数据必须显式标识 `PAPER_PUBLIC_ESTIMATE`，不得冒充真实成交。 |
| BIZ-R-2 | 失败后果 | 错误收益会误导仓位放大；因此默认 fail-closed。 |
| BIZ-R-3 | 退出成本 | 新表可保留、旧路径可配置回退为只读扫描，但不得回退为虚假 Paper 收益。 |

---

## 2. 需求与约束

### 2.1 功能需求

| 编号 | 需求描述 | 验收标准 |
|---|---|---|
| FR-1 | 按指定金额扫真实 L2 | 输入 2,000U，输出每档成交量、共同基础币量、VWAP、最差价、剩余量。 |
| FR-2 | 精确双腿预览 | 同一报价返回长腿 asks 与短腿 bids 的逐档结果，任何一腿不足则拒绝。 |
| FR-3 | 开仓重新取价 | 确认开仓时不复用列表旧快照；检查时效并重新获取第一腿盘口。 |
| FR-4 | 原子对冲语义 | 第一腿成交多少，第二腿按可兼容精度对冲多少；失败进入补偿而非伪造 OPENED。 |
| FR-5 | 平仓真实吃档 | 多腿卖 bids、空腿买 asks，按当前持仓数量逐档计算。 |
| FR-6 | 逐笔费用 | 每个 fill 以成交名义金额乘对应 venue taker fee 计费。 |
| FR-7 | 资金费账本 | 每次结算按实际持仓方向、数量、结算价与实际/公开费率生成幂等条目。 |
| FR-8 | 收益不重复计费 | 净收益使用实际成交现金流；滑点作为归因展示，不再二次扣除。 |
| FR-9 | 可执行未实现收益 | 同时展示 Mark PnL 和按当前 L2 全量退出后的 Liquidation PnL。 |
| FR-10 | 全链路可追溯 | 仓位可追溯到 quote、attempt、book、fill slice、fee、funding 和 close。 |
| FR-11 | 页面审核证据 | 开/平仓各显示最优价、VWAP、最差价、吃档数、impact bps、impact U。 |
| FR-12 | 重放验证 | 能用服务器保存的盘口 CSV 重放并得到相同结果。 |

### 2.2 非功能需求

| 编号 | 类别 | 目标 |
|---|---|---|
| NF-1 | 延迟 | 单腿 100 档扫盘 P95 < 5ms（不含网络）；双腿重报价 P95 < 1s。 |
| NF-2 | 一致性 | execution attempt、order、fills、ledger 在一个本地事务内提交。 |
| NF-3 | 幂等性 | `idempotency_key` 唯一；资金费以 venue+symbol+settlementTime+position 唯一。 |
| NF-4 | 精度 | 金额/数量统一 `BigDecimal`；禁止 double；按 venue tick/step 向不利方向保守取整。 |
| NF-5 | 时效 | 默认盘口年龄 ≤ 3s，报价有效期 ≤ 5s，均配置化。 |
| NF-6 | 可观测性 | 每次拒绝有 reason code；每个 attempt 有 trace id 和账本对账状态。 |
| NF-7 | 容量 | fill slice 在线保留 90 天，之后按月归档；PnL ledger 永久保留。 |
| NF-8 | 安全 | Paper 与 Live 类型隔离；Paper bean 不持有交易私钥。 |

### 2.3 架构约束

| 编号 | 约束 |
|---|---|
| C-1 | 复用现有 `PerpetualMarketDataClient` 公开行情入口。 |
| C-2 | 深度计算必须只有一个 canonical engine；扫描、预览、开仓、平仓共用。 |
| C-3 | `BookLevel` 必须保留 price、base quantity、quote notional，不能仅由 notional 反推数量。 |
| C-4 | 固定滑点参数只能做准入安全缓冲，不能作为实际成交滑点写入 PnL。 |
| C-5 | MySQL 迁移按项目既有 SQL 目录和命名规范提交。 |
| C-6 | 自动开仓不随本期默认启用；先人工 canary。 |

### 2.4 假设与依赖

| 编号 | 假设 / 依赖 | 影响 |
|---|---|---|
| A-1 | 交易所公开 L2 提供价、量和服务端/接收时间 | 缺时间或盘口为空时拒绝报价。 |
| A-2 | 合约乘数、数量步长、价格 tick 可从适配器取得 | 元数据缺失时 fail-closed。 |
| A-3 | Paper 只有单服务实例或数据库锁可用 | 多实例时必须启用 position/attempt 行锁。 |
| A-4 | 公开盘口不能证明真实排队成交 | 页面必须保留“公开盘口估算”标识。 |

---

## 3. 总体架构

### 3.1 架构原则

| 原则 | 说明 |
|---|---|
| P-1 · 成交事实优先 | PnL 只基于已持久化 fill，不基于候选机会价格。 |
| P-2 · 共同基础币 | 两腿以可兼容的 base quantity 对冲，不比较两个 USDT 深度最小值。 |
| P-3 · 保守估值 | 深度不足、盘口过期、精度未知均拒绝；不外推不存在的流动性。 |
| P-4 · 归因不重算 | 滑点是实际现金流与 touch benchmark 的差，不重复从现金流 PnL 扣除。 |
| P-5 · 不可变账本 | fill 和 PnL entry 只追加；修正通过 adjustment 反向冲销。 |

### 3.2 逻辑分层

| 层次 | 职责 | 关键组件 | 依赖下层 |
|---|---|---|---|
| L4 · API/UI | 报价、确认、仓位与收益展示 | Paper Controller、Dashboard Read Model | L3 |
| L3 · 编排 | 开/平/补偿状态机与幂等 | ExecutionOrchestrator、PositionMonitor | L2 |
| L2 · 领域计算 | 逐档扫盘、精度协调、PnL 汇总 | DepthSweepEngine、HedgeQuoteService、PnlCalculator | L1 |
| L1 · 事实层 | 公开盘口、元数据、持久化 | MarketDataClient、InstrumentMeta、Repositories | — |

依赖只允许 L4 → L3 → L2 → L1。扫描器与执行器不得各自复制 VWAP 算法。

### 3.3 系统上下文

输入侧是管理端用户、扫描任务、仓位监控任务；依赖侧是交易所公开行情和 MySQL；输出侧是 Paper 仓位、执行审计、收益看板与告警。详见[系统上下文图](diagrams/funding-paper-depth-context.drawio)。

### 3.4 运行时视图

| 数据流 | 输入触发 | 处理主体 | 输出 |
|---|---|---|---|
| DF-1 · 预览 | 用户输入 opportunityId + 2,000U | QuoteService | 双腿可成交报价或拒绝原因 |
| DF-2 · 开仓 | quoteId + idempotencyKey | ExecutionOrchestrator | OPENED / COMPENSATED / MANUAL_INTERVENTION |
| DF-3 · 持仓估值 | 周期任务 | PositionValuationService | Mark PnL + Liquidation PnL |
| DF-4 · 平仓 | 人工或风控触发 | CloseOrchestrator | 真实退出 fills + realized PnL |
| DF-5 · 结算 | funding 时间到达 | FundingSettlementService | 幂等资金费 ledger entry |

---

## 4. 模块详细设计

### 4.1 统一盘口模型

```java
record NormalizedBookLevel(
    BigDecimal price,
    BigDecimal baseQuantity,
    BigDecimal quoteNotional
) {}

record OrderBookSnapshot(
    Exchange exchange,
    String symbol,
    Instant receivedAt,
    String bookHash,
    List<NormalizedBookLevel> bids,
    List<NormalizedBookLevel> asks,
    InstrumentPrecision precision
) {}
```

适配器负责把 contract quantity × contract multiplier 转成 base quantity；`quoteNotional = price × baseQuantity`。三者同时保存，避免用 USDT 深度反推合约张数造成步长错误。

### 4.2 `DepthSweepEngine`

纯函数接口：

```java
ExecutionQuote sweep(
    OrderBookSnapshot book,
    OrderSide side,
    BigDecimal requestedBaseQuantity,
    ExecutionPhase phase
);
```

算法：

1. BUY 取 asks 升序，SELL 取 bids 降序。
2. `remaining = requestedBaseQuantity`，逐档填充 `min(remaining, level.baseQuantity)`。
3. 每档生成 `FillSlice(price, filledBase, filledQuote)`。
4. `VWAP = Σ(filledBase × price) / Σ(filledBase)`。
5. BUY impact：`(VWAP-bestAsk)/bestAsk×10,000`；SELL impact：`(bestBid-VWAP)/bestBid×10,000`。
6. BUY impact cost U：`(VWAP-bestAsk)×filledBase`；SELL：`(bestBid-VWAP)×filledBase`。
7. 剩余量大于 0 时返回 PARTIAL；执行策略默认拒绝，禁止假定后续档位。

### 4.3 `HedgeQuoteService`

开仓的业务输入是“长腿目标 quote notional”。服务先扫长腿 asks，确定实际 base quantity；再按两所 quantity step 的最小兼容数量向下取整，并以该数量扫短腿 bids。若精度取整后的 delta 超过配置阈值、任一腿深度不足或净收益不达标，则整个 quote 为 REJECTED。

重要语义：`requestedNotional=2000` 是下单规模，不是保证金资本。风险定仓器只负责给出上限：

```text
effectiveRequestedNotional = min(userRequestedNotional, riskAllowedNotional)
```

不得再用风险定仓器的动态值覆盖用户输入。

### 4.4 `ExecutionOrchestrator`

开仓状态机：

```text
CREATED → QUOTING → QUOTED → OPENING_FIRST → OPENING_SECOND → OPENED
                                               └→ COMPENSATING → ROLLED_BACK
                                                                  └→ MANUAL_INTERVENTION
```

- 预览只用于审核，确认时必须检查 quote 未过期并重新拉取第一腿盘口。
- 第一腿按新盘口逐档模拟，第二腿随后再拉一次盘口，并按第一腿实际成交 base quantity 扫盘。
- 第二腿不足时，对已裸露数量按第一腿反向盘口执行补偿；补偿本身也逐档记账。
- 任一状态转移与 attempt/fill/ledger 在数据库事务中保存。
- `(positionId, phase)` 加数据库锁；`idempotencyKey` 唯一，避免双击开两仓。

### 4.5 平仓与可执行估值

- 多腿平仓：SELL，消耗 long venue bids。
- 空腿平仓：BUY，消耗 short venue asks。
- 请求数量等于当前未平 base quantity；两腿仍按兼容精度处理。
- `Liquidation PnL` 每次使用当前 L2 全量扫盘，只作为估值，不生成 fill。
- `Mark PnL` 保留为市场参考；风控退出判断优先看更保守的 Liquidation PnL。

### 4.6 PnL 与滑点公式

设所有 fill 采用“买入现金流为负，卖出现金流为正”的符号：

```text
tradingCashflowPnl = Σ(signed fill quote notional)
fundingNet          = Σ(funding received) - Σ(funding paid)
feeTotal            = Σ(abs(fill quote notional) × feeRate)
netRealizedPnl      = tradingCashflowPnl + fundingNet - feeTotal - transferCost + adjustment
```

滑点归因：

```text
executionImpactU = Σ(actual signed cashflow - touch-price benchmark cashflow)
```

其中对策略收益不利的 impact 以正成本展示。因为 actual VWAP 已进入 `tradingCashflowPnl`，`executionImpactU` **只用于解释，不再从 netRealizedPnl 二次扣除**。页面可以展示：

```text
Touch 基准收益 + 开仓冲击 + 平仓冲击 + 补偿冲击 = 实际交易现金流收益
实际交易现金流收益 + 资金费 - 手续费 - 转账成本 = 净收益
```

### 4.7 示例：10 档累计 20,000U，下单 2,000U

假设长腿 asks 如下（仅为确定性测试 fixture，不代表实时市场）：

| 档 | 价格 | 本档 USDT 容量 | 本档成交 USDT |
|---:|---:|---:|---:|
| 1 | 100.00 | 500 | 500 |
| 2 | 100.10 | 700 | 700 |
| 3 | 100.20 | 800 | 800 |
| 4–10 | 更高 | 18,000 | 0 |

输入 2,000U 会吃 3 档，而不是按“20,000U / 10 档”平均估算。基础币成交量约为 `5 + 6.99300699 + 7.98403194 = 19.97703893`，VWAP 约 `100.11496`，相对 best ask 的 impact 约 `11.50bps`。实际实现以 base quantity 和精度为主计算，表中的 USDT 只是便于说明。

---

## 5. 数据模型

### 5.1 逻辑模型

| 实体 | 语义 | 生命周期 | 幂等键 |
|---|---|---|---|
| execution_quote | 某次盘口上的可执行预览 | 5 秒后过期，永久审计 | quote_id |
| execution_attempt | 一次开/平/补偿编排 | 状态机至终态 | idempotency_key |
| execution_fill | 实际模拟成交逐档事实 | 不可变 | attempt+leg+slice_no |
| pnl_ledger | 现金流、资金费、费用与调整 | 不可变 | source_type+source_id+entry_type |

实体关系见 [ER 图](diagrams/funding-paper-depth-er.drawio)。

### 5.2 物理模型

以下 DDL 为实现基线；字段长度应在迁移前与项目既有主键类型对齐。

```sql
CREATE TABLE cross_exchange_execution_quote (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  quote_id VARCHAR(64) NOT NULL,
  opportunity_id BIGINT NOT NULL,
  phase VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  requested_notional DECIMAL(36,18) NOT NULL,
  executable_base_qty DECIMAL(36,18) NOT NULL,
  expected_net_pnl DECIMAL(36,18) NULL,
  reject_reason VARCHAR(64) NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_execution_quote_id (quote_id),
  KEY idx_execution_quote_opp_time (opportunity_id, created_at)
) COMMENT='Paper 双腿执行报价';

CREATE TABLE cross_exchange_execution_attempt (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  attempt_id VARCHAR(64) NOT NULL,
  quote_id VARCHAR(64) NULL,
  position_id BIGINT NULL,
  phase VARCHAR(16) NOT NULL,
  state VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  requested_base_qty DECIMAL(36,18) NOT NULL,
  hedged_base_qty DECIMAL(36,18) NOT NULL DEFAULT 0,
  exposed_base_qty DECIMAL(36,18) NOT NULL DEFAULT 0,
  failure_code VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_execution_attempt_id (attempt_id),
  UNIQUE KEY uk_execution_attempt_idem (idempotency_key),
  KEY idx_execution_attempt_position (position_id, created_at)
) COMMENT='Paper 开平补偿执行状态机';

CREATE TABLE cross_exchange_execution_fill (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  attempt_id VARCHAR(64) NOT NULL,
  leg_no TINYINT NOT NULL,
  slice_no SMALLINT NOT NULL,
  exchange_code VARCHAR(32) NOT NULL,
  symbol VARCHAR(64) NOT NULL,
  side VARCHAR(8) NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  book_received_at DATETIME(3) NOT NULL,
  book_hash VARCHAR(64) NOT NULL,
  best_price DECIMAL(36,18) NOT NULL,
  price DECIMAL(36,18) NOT NULL,
  base_qty DECIMAL(36,18) NOT NULL,
  quote_notional DECIMAL(36,18) NOT NULL,
  fee_rate DECIMAL(20,12) NOT NULL,
  fee_amount DECIMAL(36,18) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_execution_fill_slice (attempt_id, leg_no, slice_no),
  KEY idx_execution_fill_symbol_time (exchange_code, symbol, created_at)
) COMMENT='Paper 逐档成交事实';

CREATE TABLE cross_exchange_pnl_ledger (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_id BIGINT NOT NULL,
  attempt_id VARCHAR(64) NULL,
  entry_type VARCHAR(32) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(128) NOT NULL,
  amount_usdt DECIMAL(36,18) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  metadata_json JSON NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_pnl_ledger_source (source_type, source_id, entry_type),
  KEY idx_pnl_ledger_position_time (position_id, occurred_at)
) COMMENT='Paper 不可变收益账本';
```

现有 order 表增加 `attempt_id` 外键语义（项目若不使用物理 FK，则保持逻辑关联），聚合字段 `average_price/worst_price/impact_bps/levels_consumed` 从 fill 计算，不能作为独立事实源。

### 5.3 领域字典

| 字典 | 值 |
|---|---|
| phase | OPEN、CLOSE、COMPENSATE、VALUATION |
| quote status | FULL、PARTIAL、REJECTED、EXPIRED |
| ledger entry | TRADE_CASHFLOW、TRADING_FEE、FUNDING_RECEIVED、FUNDING_PAID、TRANSFER_COST、ADJUSTMENT |
| failure code | STALE_BOOK、INSUFFICIENT_DEPTH、PRECISION_MISMATCH、IMPACT_LIMIT、QUOTE_EXPIRED、SECOND_LEG_SHORTFALL、RECONCILIATION_ERROR |

---

## 6. 接口设计

### 6.1 HTTP 契约

| 序号 | Method | Path | 作用 |
|---|---|---|---|
| API-01 | POST | `/api/funding-arbitrage/paper/quotes` | 对指定 opportunity 和 notional 获取新双腿报价 |
| API-02 | POST | `/api/funding-arbitrage/paper/positions/open` | quoteId + idempotencyKey 确认开仓 |
| API-03 | POST | `/api/funding-arbitrage/paper/positions/{id}/close-quotes` | 按当前持仓获取退出报价 |
| API-04 | POST | `/api/funding-arbitrage/paper/positions/{id}/close` | closeQuoteId + idempotencyKey 确认平仓 |
| API-05 | GET | `/api/funding-arbitrage/paper/executions/{attemptId}` | 查询状态、每档成交与失败原因 |
| API-06 | GET | `/api/funding-arbitrage/paper/positions/{id}/pnl` | 查询 Mark/Liquidation/Realized 及归因 |

报价请求：

```json
{"opportunityId": 123, "requestedNotional": "2000", "mode": "PAPER"}
```

报价响应至少包含 `quoteId/expiresAt/executableBaseQty`，以及两腿的 `bookReceivedAt/bestPrice/vwap/worstPrice/levelsConsumed/impactBps/impactUsdt/fillSlices`。所有金额用字符串传输。

错误码统一 `FUND-PAPER-*`，HTTP 409 用于报价过期、幂等冲突和仓位冲突；422 用于深度、精度、冲击或风控不满足；503 用于公开行情不可用。

### 6.2 内部 SPI

```java
interface OrderBookProvider {
  OrderBookSnapshot latest(Exchange exchange, String symbol);
}

interface DepthExecutionEngine {
  ExecutionQuote sweep(OrderBookSnapshot book, OrderSide side,
                       BigDecimal requestedBaseQty, ExecutionPhase phase);
}

interface PnlLedgerService {
  void postTradeFills(long positionId, ExecutionAttempt attempt, List<ExecutionFill> fills);
  void postFunding(long positionId, FundingSettlement settlement);
  PnlBreakdown summarize(long positionId);
}
```

---

## 7. 运行时视图

### 7.1 DF-1 · 预览与开仓

1. 用户输入机会与 2,000U；服务重新读取两腿盘口和合约精度。
2. 长腿按 asks 扫描，得到 base quantity；协调两所 step 后扫短腿 bids。
3. 保存短期 quote，页面展示每档和净收益预估。
4. 用户确认后以幂等键创建 attempt，并重新获取第一腿盘口。
5. 第一腿逐档成交并落 fill；重新获取第二腿盘口，按实际第一腿数量成交。
6. 两腿平衡则 OPENED；不足则补偿并进入 ROLLED_BACK 或 MANUAL_INTERVENTION。
7. 事务提交后 read model 更新页面。

### 7.2 DF-2 · 持仓、资金费与平仓

1. 监控任务读取持仓和新 L2，计算 Mark PnL 与全量退出 Liquidation PnL。
2. 到结算时间，资金费服务按实际方向和持仓量生成幂等 ledger entry。
3. 触发平仓时，分别扫 long bids 和 short asks，并展示 close quote。
4. 确认后重新取盘口、执行两腿与必要补偿。
5. 从不可变 ledger 汇总最终收益，检查交易现金流、费用、资金费与仓位归零。

详细顺序见[执行数据流图](diagrams/funding-paper-depth-flow.drawio)。

### 7.3 异常与容灾

| 场景 | 影响 | 应对 |
|---|---|---|
| 盘口过期/断档 | 报价不可信 | 拒绝，不沿用旧快照。 |
| 第二腿深度骤降 | 单腿暴露 | 逐档补偿第一腿；仍不足则人工介入并告警。 |
| 服务重启 | attempt 中断 | 启动恢复任务扫描非终态 attempt，依据已落 fills 续跑或补偿。 |
| 账本重复结算 | PnL 重复 | 唯一 source key 阻止重复入账。 |
| 数据不平 | 收益失真 | 状态置 `RECONCILIATION_ERROR`，禁止新开仓。 |

---

## 8. 非功能设计

### 8.1 一致性与并发

- 同一 opportunity 的 quote 可并存，但同一 symbol 的 active Paper position 默认唯一。
- 开仓确认锁定 position reservation；跨实例以数据库唯一约束和 `SELECT ... FOR UPDATE` 为准。
- 同一 bookHash 内多笔并发 Paper 订单可启用 3 秒“虚拟流动性预留”，避免重复消费同一快照；首版人工单仓模式可关闭。
- 所有聚合值可由 fills 与 ledger 重建；每日执行自动 reconciliation。

### 8.2 可观测性

| 指标名 | 类型 | 说明 |
|---|---|---|
| `paper_quote_total{status,reason}` | Counter | 报价结果与拒绝原因 |
| `paper_execution_impact_bps{phase,exchange}` | Histogram | 开/平/补偿冲击 |
| `paper_execution_levels{phase,exchange}` | Histogram | 吃档数 |
| `paper_second_leg_shortfall_usdt` | Histogram | 第二腿缺口 |
| `paper_pnl_reconciliation_error_usdt` | Gauge | 账本对账误差 |
| `paper_book_age_ms{exchange}` | Gauge | 行情年龄 |

告警：裸露数量 > 容忍值立即 P1；账本误差 > 0.01U 为 P1；连续 3 次行情过期为 P2；补偿率 > 1% 为 P2。

### 8.3 安全

Paper 执行模块只注入 public market-data adapter 和 Paper repository。Live gateway 不实现该接口或使用独立 Spring profile；API 响应和页面始终显示 `PAPER_PUBLIC_ESTIMATE`。日志不记录密码、API key 或服务器凭据。

---

## 9. 部署架构

### 9.1 部署形态

复用现有 Web/Job 与 MySQL：Web 提供报价和人工确认，Job 负责盘口采集、估值、资金费结算和恢复。首版不新增中间件。

### 9.2 配置矩阵

| Key | 默认值 | 说明 |
|---|---:|---|
| `funding.paper.depthExecution.enabled` | false | 总开关，部署后先 shadow |
| `funding.paper.depthExecution.maxBookAgeMs` | 3000 | 最大盘口年龄 |
| `funding.paper.depthExecution.quoteTtlMs` | 5000 | 预览有效期 |
| `funding.paper.depthExecution.maxImpactBps` | 5 | 单腿准入阈值，需按回放校准 |
| `funding.paper.depthExecution.maxHedgeDelta` | venue min step | 最大未对冲量 |
| `funding.paper.depthExecution.liquidityReservationEnabled` | false | 高频并发时开启 |
| `funding.paper.autoOpen.enabled` | false | 本设计不启用自动开仓 |

账户口径固定为每个交易所 Paper 账户 2,000U，最大杠杆 3x；3x 仅为硬上限，不能覆盖深度、滑点、清算距离和组合风险给出的更低限额。

### 9.3 上线流程

| 阶段 | 内容 | 验收 |
|---|---|---|
| R0 | 数据库 migration + shadow quote | 不开仓；新旧 VWAP 差异可解释。 |
| R1 | 本地/测试环境 100U 人工 canary | 开平仓对账误差 ≤ 0.01U。 |
| R2 | 500U 人工 canary | 吃档、费用、资金费、退出冲击全可见。 |
| R3 | 2,000U 人工 canary | 使用服务器实时盘口；任一限制不满足则拒绝。 |
| R4 | 连续 7 天 Paper | 无裸露未处理、无重复资金费、无账本不平。 |

Web 服务需要部署 API/read model 改动；Job 服务需要部署盘口、估值、结算与恢复改动。两者必须使用同一版本的领域模块和 migration。前端静态资源也需部署以展示新字段。

### 9.4 回滚

关闭 `depthExecution.enabled` 后只保留扫描与历史查询，禁止回退到旧的“Mark Price 假成交”开仓。数据库新增表不删除；未终态 attempt 先由恢复任务收口，无法收口则保持暂停并人工审计。

---

## 10. 演进路线与验证

### 10.1 里程碑

| 里程碑 | 内容 | 交付物 | 预估 |
|---|---|---|---:|
| M1 | 统一盘口与 sweep engine | 领域模型、单元/性质测试 | 1–2 天 |
| M2 | quote API 与精度协调 | 预览、过期、拒绝原因 | 1–2 天 |
| M3 | 开平补偿状态机与数据表 | migration、恢复、幂等 | 2–3 天 |
| M4 | PnL ledger 与 read model | 账本、对账、页面 API | 1–2 天 |
| M5 | 页面、回放和部署 | 逐档抽屉、测试报告、runbook | 1–2 天 |

关键路径为 M1 → M2 → M3 → M4 → M5。自动开仓是后续独立里程碑，必须在 7 天 Paper 验收后再次审核。

### 10.2 测试矩阵

| 层级 | 必测内容 |
|---|---|
| 单元 | 1 档/多档/刚好吃完/不足；BUY/SELL；step/tick；费用与滑点公式。 |
| 性质测试 | 成交守恒；不越档；VWAP 位于 best 与 worst 之间；规模增大时冲击不改善；不超额成交。 |
| 状态机 | 第二腿失败、补偿部分成交、重启恢复、重复请求、并发开仓。 |
| 账本 | cashflow + funding - fee - cost = net；滑点不重复扣除；冲销而非改历史。 |
| 回放 | 读取服务器保存的 v2 CSV，同一快照、输入与精度得到确定性相同结果。 |
| E2E | 100U → 500U → 2,000U，完整开仓、结算、平仓和页面审计。 |

正式“去跑”前的硬门：代码测试全绿；服务器盘口文件格式核验；数据库 migration 成功；服务时间同步；Paper profile 与 Live gateway 隔离；页面显示逐档与对账；100U canary 人工通过。任一门失败均不推进金额。

---

## 11. 附录

### 11.1 图示索引

| 编号 | 图名 | 用途 | 文件 |
|---|---|---|---|
| 图 11-1 | 系统上下文 | 人、服务、公开交易所与数据库边界 | [funding-paper-depth-context.drawio](diagrams/funding-paper-depth-context.drawio) |
| 图 11-2 | 执行数据流 | 预览、重报价、双腿、补偿、账本 | [funding-paper-depth-flow.drawio](diagrams/funding-paper-depth-flow.drawio) |
| 图 11-3 | ER 图 | quote、attempt、fill、ledger 与 position | [funding-paper-depth-er.drawio](diagrams/funding-paper-depth-er.drawio) |

### 11.2 页面审核字段

详情抽屉分为五块：订单规模与共同基础币；开仓两腿逐档表；平仓两腿逐档表；收益瀑布；生命周期。收益必须同时给出：Touch 基准、开仓 impact、平仓 impact、补偿 impact、交易现金流 PnL、资金费、手续费、最终净收益。列表不直接开仓；只能从独立确认弹窗获取新 quote 后确认。

### 11.3 未决问题

| 编号 | 问题 | 建议 |
|---|---|---|
| OQ-1 | 第一腿先执行长腿还是流动性较弱腿 | M3 用服务器回放比较失败/补偿成本后确定。 |
| OQ-2 | 5bps 阈值是否过严 | 用 7 天真实盘口按币种分位数校准，不凭经验放宽。 |
| OQ-3 | 手续费率来源 | 首版配置化并保存到每个 fill；后续接公开/账户费率快照。 |
| OQ-4 | 是否模拟延迟期间盘口变化 | 首版两腿分别实时拉取；后续可用 CSV 时间序列做 latency model。 |

### 11.4 审核结论请求

建议审核并确认以下方向后再实施：

1. 下单金额以用户输入为准，风险定仓器只做上限，不覆盖输入。
2. 实际 PnL 只取逐档 fill 现金流；滑点作为归因，不重复扣减。
3. 平仓必须逐档扫盘，并同时展示 Mark PnL 与 Liquidation PnL。
4. 第一阶段只做手工 Paper canary，不自动开仓，不接真实交易。
