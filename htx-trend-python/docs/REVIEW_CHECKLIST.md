# HTX Trend Research System - Review Checklist

**Version**: 0.1.0  
**Review Date**: 2026-10-11  
**Status**: MVP-1 Delivery Review

本文档将 PRD 和 12 阶段 Iron-Rule 框架的每个要求映射到实现状态。

---

## 12-Stage Iron-Rule Framework

### 1. Strategy Card

**要求**: 明确策略假设、适用条件、风险边界

**状态**: ✅ DONE

**文件**:
- `docs/trend-strategy-architecture-v0.1.md` - 完整策略卡
- `docs/trend-strategy-prd-v0.1.md` - 产品需求文档

**覆盖内容**:
- ✅ 策略假设：趋势突破 + 动量确认
- ✅ 适用市场：HTX USDT 永续合约
- ✅ 风险边界：单笔 0.5% 风险，总风险 ≤1.5%，最多 3 仓位
- ✅ 预期夏普比率、最大回撤、胜率目标

**诚实空缺**:
- ⚠️ 未量化策略在不同市场状态（震荡/趋势/崩盘）的预期表现差异
- ⚠️ 未定义策略失效的明确信号（何时停用）

---

### 2. Data Freeze

**要求**: 数据集冻结、版本化、内容寻址、可重现

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/data/dataset.py` - `DatasetFreezer`
- `src/htx_trend/research/ledger/ledger.py` - `LedgerManager` (registrations 表)
- `tests/research/test_strict_determinism.py` - 哈希验证测试

**覆盖内容**:
- ✅ 内容哈希（SHA256）版本化
- ✅ Manifest 记录数据来源、符号、时间范围
- ✅ 训练/验证/测试集分割 (60/20/20)
- ✅ 测试集锁定（`test_set_locks` 表）防止泄露
- ✅ 严格确定性测试（相同配置 → 相同 parquet 哈希）

**诚实空缺**:
- ❌ **不可变存储未实现**：当前覆盖式写入，未保留历史版本（Issue 4）
- ❌ **bar_time + first_seen_at 时间戳未分离**（Issue 4）
- ❌ **交易所修订版本未版本化**（同一 bar_time 的修订覆盖原数据）（Issue 4）
- ⚠️ 数据集冻结后无自动化校验流程（应在回测前自动校验哈希）

---

### 3. Data Quality

**要求**: 数据完整性、异常检测、交叉验证、标注问题数据

**状态**: 🟡 PARTIAL

**文件**:
- `src/htx_trend/research/quality.py` - `QualityReport`
- `src/htx_trend/research/data/stitcher.py` - `DataStitcher`

**覆盖内容**:
- ✅ 缺口检测（missing bars）
- ✅ 零值/负值检测
- ✅ 重复 bar_time 检测
- ✅ 极端价格跳动检测（>5%）
- ✅ Binance 回填偏差检测（>0.3% 标记为 non_comparable）

**诚实空缺**:
- ❌ **non_comparable 标记未实现**：偏差 >0.3% 的 K 线未在 DataFrame 中标记（Issue 6）
- ❌ **Binance 数据未标记为 external**：volume/funding/OI 应排除于百分位计算（Issue 6）
- ❌ **HTX 分页未实现**：当前单次请求，未获取最大可用历史深度（Issue 5）
- ❌ **Binance 分页未实现**：未实现完整历史回填（Issue 5）
- ⚠️ 资金费率数据质量检查缺失（未验证 funding rate 异常值）
- ⚠️ 持仓量（OI）数据质量检查缺失
- ⚠️ 未实现数据质量分数（可量化数据可信度）

---

### 4. Feature Dictionary

**要求**: 所有特征的公式、版本、依赖关系、无前视偏差证明

**状态**: 🟡 PARTIAL

**文件**:
- `src/htx_trend/research/features/indicators.py` - `FeatureCalculator` (VERSION = "1.1.0")
- `src/htx_trend/research/context/regime.py` - `RegimeLabeler`
- `tests/research/test_strict_determinism.py` - 无前视测试

**覆盖内容**:
- ✅ ATR14/ATR20 (Wilder smoothing)
- ✅ EMA20/60/120
- ✅ Donchian 42/84/126/168 (shift(1) 排除当前 K 线)
- ✅ ADX14 (Wilder smoothing)
- ✅ 特征版本号（`FeatureCalculator.VERSION`）
- ✅ 无前视测试（截断测试证明决策不依赖未来数据）

**诚实空缺**:
- ❌ **Regime 特征不完整**（PRD 要求但缺失）：
  - ❌ `btc_above_ema120`：BTC 是否在 EMA120 上方（牛市过滤器）
  - ❌ `funding_rate_percentile`：资金费率百分位（拥挤度）
  - ❌ `oi_change_7d`：7 日持仓量变化
  - ❌ `event_flag`：重大事件标记（FOMC、CPI、减半等）
  - ❌ `crowding_score`：市场拥挤度（多空持仓不平衡）
- ⚠️ 当前 regime 仅实现 `trend_state` × `vol_state` 的 6 种组合
- ⚠️ 特征公式未导出为独立文档（应有 `FEATURE_DICTIONARY.md`）
- ⚠️ ATR/ADX 方法参数未记录在特征元数据中（method="wilder"）

---

### 5. Signal State Machine

**要求**: 清晰的状态转换、条件检查、无隐藏逻辑

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/strategy/state_machine.py` - `StrategyStateMachine`
- `src/htx_trend/research/conditions/c01_donchian.py` - C01 (v1.1.0: 显式参数 + 投票记录)
- `src/htx_trend/research/conditions/c02_atr_stop.py` - C02

**覆盖内容**:
- ✅ 状态定义：NO_POSITION, PENDING_ENTRY, IN_POSITION, PENDING_EXIT
- ✅ 条件插件接口（`Condition`, `ConditionResult`）
- ✅ C01: Donchian 集成（4 周期投票，threshold 显式传递，记录方向和投票）
- ✅ C02: ATR14x2 止损（2 倍 ATR 初始止损）
- ✅ 状态转换逻辑清晰（entry → holding → exit）

**诚实空缺**:
- ⚠️ C01/C02 之外的条件插件未实现（PRD 提到未来可能的 C03 等）
- ⚠️ 止损移动逻辑未实现（当前仅固定止损）
- ⚠️ 部分平仓逻辑未实现（当前只支持全仓位平仓）

---

### 6. Sizing & Risk

**要求**: 仓位计算、风险限制、账户级清算检查

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/risk/position_sizing.py` - `PositionSizer`
- `src/htx_trend/research/risk/liquidation_check.py` - `AccountLiquidationChecker`
- `src/htx_trend/research/account_guard.py` - `AccountGuard` (账户级权限检查)
- `tests/research/test_account_liquidation.py`

**覆盖内容**:
- ✅ 固定风险百分比：单笔 0.5% 账户权益风险
- ✅ 总风险限制：≤1.5% 账户权益（所有策略仓位）
- ✅ 最大仓位数：≤3
- ✅ 账户级清算检查（Cross Margin, MMR=0.004）
- ✅ 非策略仓位识别（`get_non_strategy_positions`）
- ✅ 账户守卫：只允许 HTX MAIN 账户（拒绝子账户）

**诚实空缺**:
- ❌ **最小合约规模检查未使用实际初始资金**：应使用 R0001 initial_capital=400 USDT（Issue 7）
- ⚠️ 动态风险调整未实现（根据胜率/回撤调整风险百分比）
- ⚠️ 最大单品种敞口未限制（理论上可以 3 个 BTC 仓位）
- ⚠️ 跨品种相关性未考虑（BTC + ETH 仓位风险可能叠加）

---

### 7. Cost & Execution

**要求**: 真实手续费、滑点、资金费率、执行延迟

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/adapters/htx/fees.py` - `FeeProvider`
- `src/htx_trend/research/engine/backtest/sim_broker.py` - `SimBroker`

**覆盖内容**:
- ✅ 手续费接口（`FeeProvider`, `DefaultFeeProvider`, `HtxAuthenticatedFeeProvider`）
- ✅ 默认费率：Maker 0.02%, Taker 0.06%
- ✅ 四种成本场景：BASE (默认费率), ZERO, BEST (0%), AGGRESSIVE (2x 滑点)
- ✅ 资金费率计算（每个周期）
- ✅ t 收盘信号 → t+1 开盘成交（执行延迟）
- ✅ 手续费来源记录（配置中显式声明）

**诚实空缺**:
- ⚠️ HTX 认证费率 API 未实现（仅 stub）
- ⚠️ 动态滑点模型未实现（当前固定 0.1% 或配置值）
- ⚠️ 市场深度/流动性未考虑（大单可能滑点更高）
- ⚠️ 拒单风险未模拟（极端行情可能无法成交）

---

### 8. Same-Source Backtest

**要求**: 回测引擎与实盘使用相同决策逻辑

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/runner.py` - `BacktestRunner`
- `src/htx_trend/research/engine/backtest/sim_broker.py` - `SimBroker`
- `src/htx_trend/research/strategy/state_machine.py` - `StrategyStateMachine`
- `src/htx_trend/research/conditions/base.py` - `DecisionContext`

**覆盖内容**:
- ✅ 决策上下文（`DecisionContext`）在回测和实盘中共享
- ✅ 条件插件（C01/C02）在回测中直接使用
- ✅ 状态机逻辑（entry/exit）在回测中完整模拟
- ✅ 事件驱动架构（Clock, Feed, Broker）
- ✅ 回测结果输出：parquet 文件（decisions, fills, equity）
- ✅ 回测报告生成（Markdown + 图表）

**诚实空缺**:
- ⚠️ PAPER 模式代码路径与回测未完全统一（live worker 代码独立）
- ⚠️ 回测未模拟网络故障、API 限流等实盘异常

---

### 9. Ablation & Sensitivity

**要求**: 参数扫描、消融测试、敏感性分析

**状态**: ⚠️ DEFERRED (MVP-1 范围外)

**文件**:
- `src/htx_trend/research/experiments.py` - 实验框架（部分实现）

**覆盖内容**:
- ✅ 实验框架接口（`ExperimentPlan`, `ExperimentRunner`）
- ✅ 参数网格生成（理论上支持）

**诚实空缺**:
- ❌ **无自动化参数扫描脚本**（需手动编写 YAML 配置）
- ❌ **无消融测试套件**（移除某个条件/特征，观察性能变化）
- ❌ **无敏感性分析报告**（参数 ±10% 对性能的影响）
- ❌ **无过拟合检测**（训练集 vs 验证集性能差异警报）

**理由**: MVP-1 专注于核心回测引擎，参数优化留待后续迭代。

---

### 10. OOS & Robustness

**要求**: 样本外测试、多市场状态、鲁棒性验证

**状态**: 🟡 PARTIAL

**文件**:
- `src/htx_trend/research/data/dataset.py` - 测试集分割
- `src/htx_trend/research/ledger/ledger.py` - `test_set_locks` 表

**覆盖内容**:
- ✅ 测试集锁定（60/20/20 分割，测试集只能使用一次）
- ✅ 测试集解锁需显式审批
- ✅ Ledger 记录测试集使用历史

**诚实空缺**:
- ❌ **多市场状态回测未实现**：
  - ❌ 牛市（BTC >EMA120）
  - ❌ 熊市（BTC <EMA120）
  - ❌ 震荡市（低 ADX）
  - ❌ 高波动市（高 ATR）
- ❌ **Walk-forward 测试未实现**（滚动窗口验证）
- ❌ **蒙特卡洛模拟未实现**（交易序列随机重排，评估运气成分）
- ⚠️ 单一品种测试（BTC），未验证跨品种泛化能力

---

### 11. PAPER

**要求**: 模拟交易 ≥14 天，与回测性能对比，通过审批

**状态**: 🟡 PARTIAL

**文件**:
- `src/htx_trend/research/ledger/ledger.py` - `approvals` 表
- `src/htx_trend/research/ops/status.py` - `StatusInterface`
- `src/htx_trend/research/ops/heartbeat.py` - `HeartbeatManager`

**覆盖内容**:
- ✅ Approval gates: PRE_PAPER, PRE_LIVE, BREAKER_RESTART
- ✅ 审批记录：gate, run_id, strategy_version, approver, decision, rationale
- ✅ Ops 监控：heartbeat, health check, status --json
- ✅ 账户守卫：防止错误账户下单

**诚实空缺**:
- ❌ **PAPER 模式未完全实现**：
  - ❌ 模拟下单逻辑（发送模拟订单到 HTX 或本地记录）
  - ❌ PAPER 与回测性能自动对比报告
  - ❌ PAPER 运行 ≥14 天的自动化验证流程
- ⚠️ Approval CLI 功能完整，但无自动化提醒（人工驱动）

---

### 12. Report & Admission

**要求**: 完整性能报告、风险评估、审批决策依据

**状态**: ✅ DONE

**文件**:
- `src/htx_trend/research/reports/report_generator.py` - `ReportGenerator`
- `docs/MVP1_DELIVERY_REPORT.md` - MVP-1 交付报告
- `DEPLOYMENT.md` - 部署指南

**覆盖内容**:
- ✅ Markdown 回测报告生成
- ✅ 性能指标：总收益、夏普比率、最大回撤、胜率、盈亏比
- ✅ 交易日志：每笔交易记录（entry/exit/PnL/duration）
- ✅ 权益曲线图表（PNG，未在 MVP-1 实现）
- ✅ 完整交付报告（技术实现细节、测试结果、部署说明）

**诚实空缺**:
- ⚠️ 报告未自动化附带图表（需手动生成或后续实现）
- ⚠️ 风险分解报告缺失（按品种、方向、市场状态分解 PnL）
- ⚠️ 滚动窗口性能指标缺失（30 天、90 天滚动夏普比率）

---

## PRD 模块映射

### 数据采集 (Data Collection)

**状态**: 🟡 PARTIAL

**实现**:
- ✅ HTX 公开 API 客户端 (`adapters/htx/market_data.py`)
- ✅ Binance 回填接口 (`adapters/binance.py`)
- ✅ 数据质量检查 (`quality.py`)

**空缺**:
- ❌ HTX 分页未实现（Issue 5）
- ❌ Binance 分页未实现（Issue 5）
- ❌ **独立持续采集进程未实现**（Issue 4）：
  - ❌ 定时调度（cron/systemd timer）
  - ❌ 断点续传（重启后自动恢复）
  - ❌ 缺口检测与补齐（无信号时也持续采集）

---

### 数据集冻结 (Dataset Freeze)

**状态**: ✅ DONE (见 Stage 2)

---

### 特征工程 (Feature Engineering)

**状态**: 🟡 PARTIAL (见 Stage 4)

---

### Regime 标注 (Regime Labeling)

**状态**: 🟡 PARTIAL

**实现**:
- ✅ `trend_state`: UP_TRENDING, DOWN_TRENDING, RANGING
- ✅ `vol_state`: HIGH_VOL, NORMAL_VOL

**空缺**:
- ❌ `btc_above_ema120` (牛市过滤器)
- ❌ `funding_rate_percentile` (拥挤度)
- ❌ `oi_change_7d` (持仓量趋势)
- ❌ `event_flag` (重大事件)
- ❌ `crowding_score` (多空不平衡)

---

### 条件插件 (Condition Plugins)

**状态**: ✅ DONE (见 Stage 5)

---

### 策略状态机 (Strategy State Machine)

**状态**: ✅ DONE (见 Stage 5)

---

### 风险管理 (Risk Management)

**状态**: ✅ DONE (见 Stage 6)

**空缺**:
- ❌ 最小合约规模检查未使用 400 USDT 初始资金（Issue 7）

---

### 回测引擎 (Backtest Engine)

**状态**: ✅ DONE (见 Stage 8)

---

### Ops 监控 (Ops Monitoring)

**状态**: ✅ DONE (见 Stage 11)

---

### 审批门控 (Approval Gates)

**状态**: ✅ DONE (见 Stage 11)

---

### 报告生成 (Report Generation)

**状态**: ✅ DONE (见 Stage 12)

---

## 诚实空缺总结 (Honest Gaps Summary)

### 高优先级（影响策略有效性）

1. ❌ **不可变 K 线存储**（Issue 4）：历史数据可能被覆盖，损害可重现性
2. ❌ **HTX + Binance 分页**（Issue 5）：无法获取完整历史数据
3. ❌ **non_comparable + external 标记**（Issue 6）：拼接偏差数据可能误导信号
4. ❌ **独立持续采集进程**（Issue 4）：数据可能有缺口，影响回测质量
5. ❌ **最小合约规模 vs 400 USDT 初始资金**（Issue 7）：资金太小可能无法开仓

### 中优先级（策略完整性）

6. ⚠️ **Regime 特征缺失**（btc_above_ema120, funding_rate, OI, event）
7. ⚠️ **多市场状态回测缺失**（牛/熊/震荡/高波动）
8. ⚠️ **PAPER 模式未完全实现**（模拟下单、14 天验证流程）
9. ⚠️ **参数扫描/消融测试未实现**（Stage 9）

### 低优先级（优化项）

10. ⚠️ 动态风险调整
11. ⚠️ 跨品种相关性控制
12. ⚠️ 动态滑点模型
13. ⚠️ 蒙特卡洛模拟
14. ⚠️ 自动化图表生成
15. ⚠️ 滚动窗口性能指标

---

## 文件引用索引 (File Reference Index)

### 核心逻辑

- **State Machine**: `src/htx_trend/research/strategy/state_machine.py`
- **Conditions**: `src/htx_trend/research/conditions/c01_donchian.py`, `c02_atr_stop.py`
- **Features**: `src/htx_trend/research/features/indicators.py`
- **Regime**: `src/htx_trend/research/context/regime.py`
- **Risk**: `src/htx_trend/research/risk/position_sizing.py`, `liquidation_check.py`
- **Backtest**: `src/htx_trend/research/engine/backtest/sim_broker.py`
- **Runner**: `src/htx_trend/research/runner.py`

### 数据层

- **Collector**: `src/htx_trend/research/data/collector.py`
- **Stitcher**: `src/htx_trend/research/data/stitcher.py`
- **Dataset**: `src/htx_trend/research/data/dataset.py`
- **Quality**: `src/htx_trend/research/quality.py`
- **HTX API**: `src/htx_trend/research/adapters/htx/market_data.py`
- **Binance API**: `src/htx_trend/research/adapters/binance.py`

### Ops & 监控

- **Ledger**: `src/htx_trend/research/ledger/ledger.py`
- **Logging**: `src/htx_trend/research/ops/logging.py`
- **Heartbeat**: `src/htx_trend/research/ops/heartbeat.py`
- **Status**: `src/htx_trend/research/ops/status.py`
- **Account Guard**: `src/htx_trend/research/account_guard.py`

### 测试

- **Determinism**: `tests/research/test_strict_determinism.py`
- **No Lookahead**: `tests/research/test_strict_determinism.py` (truncation tests)
- **Account Liquidation**: `tests/research/test_account_liquidation.py`
- **Donchian**: `tests/research/test_donchian_breakout.py`
- **C01**: `tests/research/test_c01_explicit_params.py`
- **ATR/ADX**: `tests/research/test_atr_adx_index.py`

### 文档

- **Strategy Card**: `docs/trend-strategy-architecture-v0.1.md`
- **PRD**: `docs/trend-strategy-prd-v0.1.md`
- **Usage Guide**: `RESEARCH_SYSTEM.md`
- **Deployment**: `DEPLOYMENT.md`
- **Delivery Report**: `docs/MVP1_DELIVERY_REPORT.md`

---

## 结论

**MVP-1 交付状态**: 核心回测引擎完整，数据层和 Regime 特征有待完善。

**可发布到 PAPER 的前置条件**:
1. ✅ Issue 4-7 修复（不可变存储、分页、标记、初始资金）
2. ✅ Regime 特征补全（至少 btc_above_ema120）
3. ✅ PAPER 模式实现（模拟下单逻辑）
4. ✅ 14 天 PAPER 运行无重大故障
5. ✅ PRE_PAPER 审批通过

**当前可进行的工作**:
- ✅ 回测研究（使用现有数据集，接受已知空缺）
- ✅ 参数实验（手动编写配置，观察性能）
- ✅ 代码审查和单元测试补充

**不可进行的工作**:
- ❌ 生产 PAPER/LIVE 交易（数据层和特征未完善）
- ❌ 正式策略审批（需补全 Regime 和 OOS 测试）

---

**审查人签名**: _____________  
**日期**: _____________  
**决策**: [ ] 批准 PAPER / [ ] 需补充 / [ ] 拒绝
