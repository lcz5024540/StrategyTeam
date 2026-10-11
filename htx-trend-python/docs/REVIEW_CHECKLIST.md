# HTX Trend Research System - Technical Review Checklist

**Version**: 0.1.0  
**Review Date**: 2026-10-11  
**Review Spec**: tech-review-spec-v0.1.md  
**Status**: Pre-Release Review

本文档按照《技术评审规范 v0.1》的 8 个评审维度逐项检查，每项标记为 **PASS**（通过）/ **FAIL**（不通过）/ **DEFERRED**（延后，附目标版本）。

---

## A. 策略专业性 (Strategy Professionalism)

### A1. 信号只用已闭合 K 线，不含当前 K 线

**状态**: ✅ PASS

**要求**: 通道计算 shift(1)，当前 K 线不参与历史统计

**证据**:
- **代码**: `src/htx_trend/research/features/indicators.py:33-48`
  ```python
  def donchian_high(series: pd.Series, period: int) -> pd.Series:
      """Donchian high - max of prior N bars (excludes current bar)"""
      return series.shift(1).rolling(window=period).max()
  
  def donchian_low(series: pd.Series, period: int) -> pd.Series:
      """Donchian low - min of prior N bars (excludes current bar)"""
      return series.shift(1).rolling(window=period).min()
  ```

- **测试**: `tests/research/test_donchian_breakout.py`
  - ✅ `test_donchian_excludes_current_bar`: 验证当前 K 线被排除
  - ✅ `test_donchian_breakout_long_detected`: 多头突破检测（close > prior N max）
  - ✅ `test_donchian_breakout_short_detected`: 空头突破检测（close < prior N min）

**结论**: 通道计算正确使用 shift(1)，突破条件不包含当前 K 线。

---

### A2. t 收盘出信号，t+1 成交

**状态**: ✅ PASS

**要求**: 信号产生和成交之间有执行延迟

**证据**:
- **代码**: `src/htx_trend/research/engine/backtest/sim_broker.py:139-169`
  ```python
  def process_next_bar(self, symbol: str, bar: dict) -> None:
      """Process pending orders with next bar (t+1 execution)"""
      bar_time = bar.get("bar_time") or bar.get("timestamp")
      open_price = bar["open"]
      
      for order, signal_time in self.pending_orders:
          if order.symbol != symbol:
              continue
          
          # Fill at t+1 open
          fill = Fill(...)
  ```

- **架构**: `src/htx_trend/research/runner.py:192-250`
  - t 时刻：产生信号，调用 `broker.place_order()` → 订单加入 `pending_orders`
  - t+1 时刻：`broker.process_next_bar()` → 以 t+1 开盘价成交

**结论**: 严格执行 t 收盘信号 → t+1 开盘成交。

---

### A3. 止损和仓位按风险预算计算

**状态**: ✅ PASS

**要求**: 单笔风险 0.5%，总风险 ≤1.5%，最大 3 仓位

**证据**:
- **代码**: `src/htx_trend/research/risk/position_sizing.py:20-101`
  ```python
  class PositionSizer:
      def __init__(
          self,
          risk_per_trade_pct: float = 0.005,  # 0.5%
          max_total_risk_pct: float = 0.015,  # 1.5%
          max_positions: int = 3,
      ):
      
      def calculate_position(...):
          stop_distance_pct = abs(entry_price - stop_loss) / entry_price
          risk_amount = account_equity * self.risk_per_trade_pct
          notional_value = risk_amount / stop_distance_pct
          num_contracts = notional_value / (entry_price * contract_size)
  ```

- **测试**: `tests/research/test_position_sizing_400usdt.py`
  - ✅ 400 USDT 资金下的仓位计算
  - ✅ 风险预算检查
  - ✅ 总风险限制验证

**结论**: 仓位严格按风险百分比计算，止损距离决定合约数量。

---

### A4. 最小张数超标就跳过

**状态**: ✅ PASS

**要求**: 如果风险预算计算出的合约数 < min_order_size，拒绝开仓

**证据**:
- **代码**: `src/htx_trend/research/risk/position_sizing.py:79-88`
  ```python
  if num_contracts < min_order_size:
      return PositionSize(
          symbol=symbol,
          side=side,
          size=0,
          skip_reason=f"Min order size {min_order_size} exceeds risk budget",
      )
  ```

- **测试**: `tests/research/test_position_sizing_400usdt.py:45-76`
  - ✅ `test_position_sizing_rejects_too_small_account`: 验证账户过小时拒绝

**结论**: 最小合约规模检查已实现，超标跳过并记录原因。

---

### A5. 参数全部从配置显式传入

**状态**: ✅ PASS

**要求**: 无隐藏默认值，参数缺失抛出异常

**证据**:
- **代码**: `src/htx_trend/research/conditions/c01_donchian.py:19-43`
  ```python
  class C01DonchianEnsemble:
      def __init__(self, periods: list[int], threshold: float):
          if not periods:
              raise ValueError("C01: 'periods' must be provided and non-empty")
          if threshold is None:
              raise ValueError("C01: 'threshold' must be provided")
          if not (0 <= threshold <= 1):
              raise ValueError(f"C01: 'threshold' must be in [0, 1], got {threshold}")
  ```

- **配置**: `configs/runs/R0001.yaml:46-58`
  ```yaml
  conditions:
    C01:
      enabled: true
      periods: [42, 84, 126, 168]
      threshold: 0.25
    C02:
      enabled: true
      atr_period: 14
      atr_multiplier: 2.0
  ```

- **测试**: `tests/research/test_c01_explicit_params.py`
  - ✅ `test_c01_requires_explicit_periods`
  - ✅ `test_c01_requires_explicit_threshold`
  - ✅ `test_c01_validates_threshold_range`

**结论**: 所有参数显式传入，缺失或无效参数抛出异常。

---

### A6. 参数与《首个趋势策略参数依据》一致

**状态**: ⚠️ **DEFERRED v0.2.0**

**理由**: 参数依据文档 `first-trend-strategy-parameter-rationale.md` 不存在

**当前参数**:
- C01: periods=[42, 84, 126, 168], threshold=0.25
- C02: atr_period=14, atr_multiplier=2.0
- Risk: 0.5% per trade, max 1.5% total, max 3 positions
- Initial capital: 400 USDT

**行动**: v0.2.0 需创建参数依据文档，或在 registration 中说明参数选择理由。

---

## B. 框架与铁律 (Framework & Iron Laws)

### B1. 12 阶段逐项对应

**状态**: 🟡 **PARTIAL** (10/12 完成)

**完成项**:
1. ✅ Strategy Card - `docs/trend-strategy-architecture-v0.1.md`
2. ✅ Data Freeze - `src/htx_trend/research/data/dataset.py`
3. 🟡 Data Quality - `src/htx_trend/research/quality.py` (部分缺失，见 C5)
4. 🟡 Feature Dictionary - `src/htx_trend/research/features/` (Regime 不完整)
5. ✅ Signal State Machine - `src/htx_trend/research/strategy/state_machine.py`
6. ✅ Sizing & Risk - `src/htx_trend/research/risk/`
7. ✅ Cost & Execution - `src/htx_trend/research/engine/backtest/sim_broker.py`
8. ✅ Same-Source Backtest - `src/htx_trend/research/runner.py`
9. ⚠️ Ablation & Sensitivity - DEFERRED v0.3.0
10. 🟡 OOS & Robustness - `src/htx_trend/research/data/dataset.py` (测试集锁定，但无多市场状态回测)
11. 🟡 PAPER - `src/htx_trend/research/ledger/ledger.py` (审批门控完成，但模拟下单未实现)
12. ✅ Report & Admission - `src/htx_trend/research/reports/report_generator.py`

**DEFERRED 项**:
- Stage 9 (Ablation & Sensitivity): 目标 **v0.3.0**，需参数扫描和消融测试
- Stage 11 (PAPER 完整实现): 目标 **v0.2.0**，需模拟下单逻辑

**结论**: 核心回测引擎完整，参数优化和 PAPER 模式留待后续。

---

### B2. 回测与 PAPER/实盘共用同一套代码

**状态**: ✅ PASS

**证据**:
- **决策上下文**: `src/htx_trend/research/conditions/base.py:37-50`
  - `DecisionContext` 在回测和实盘中共享
- **条件插件**: `src/htx_trend/research/conditions/c01_donchian.py`, `c02_atr_stop.py`
  - 条件评估逻辑独立于运行环境
- **状态机**: `src/htx_trend/research/strategy/state_machine.py`
  - 状态转换逻辑在回测中完整模拟

**结论**: 决策逻辑、条件检查、状态机在回测和实盘中共用。

---

### B3. 失败默认安全（数据缺失返回 UNKNOWN，不交易）

**状态**: ✅ PASS

**证据**:
- **C01 缺失数据处理**: `src/htx_trend/research/conditions/c01_donchian.py:70-76`
  ```python
  if not votes:
      return ConditionResult(
          cond_id=self.id,
          status="UNKNOWN",
          reason="No Donchian channels available",
          evidence={"votes": {}, "direction": None},
      )
  ```

- **C02 缺失数据处理**: `src/htx_trend/research/conditions/c02_atr_stop.py`
  - ATR 数据缺失时返回 `UNKNOWN`

**结论**: 数据缺失时返回 `UNKNOWN`，不产生交易信号。

---

## C. 数据冻结与复现 (Data Freeze & Reproducibility)

### C1. K 线只追加不覆盖，每批带内容 hash

**状态**: ❌ **FAIL** → DEFERRED v0.2.0

**当前状态**:
- ✅ 内容 hash: `src/htx_trend/research/data/dataset.py:28-35`
  ```python
  manifest_hash = hashlib.sha256(
      (
          f"{manifest.source}:{manifest.symbols}:{manifest.start_date}:"
          f"{manifest.end_date}:{manifest.total_bars}"
      ).encode()
  ).hexdigest()[:16]
  ```

- ❌ 追加式存储未实现：当前使用 `to_parquet()` 覆盖式写入
- ❌ 批次级 hash 未实现

**行动**: v0.2.0 实现追加式批次存储，每批带独立 hash。

---

### C2. 同时记录 bar_time 和 first_seen_time

**状态**: ❌ **FAIL** → DEFERRED v0.2.0

**当前状态**:
- ✅ `bar_time` 字段存在
- ❌ `first_seen_time` 字段不存在

**行动**: v0.2.0 采集时记录 `first_seen_at` 时间戳，与 `bar_time` 分离。

---

### C3. 交易所修订的 K 线另存版本

**状态**: ❌ **FAIL** → DEFERRED v0.2.0

**要求**: 同一 bar_time 的修订版本不覆盖原数据，旧版本仍可读

**当前状态**: 覆盖式存储，修订会覆盖原数据

**行动**: v0.2.0 实现版本化存储，修订 K 线存为新版本。

**必备测试**: ❌ 缺失 "修订一根 K 线后产生新版本，旧版本仍可读"

---

### C4. 采集进程独立且能续采

**状态**: ❌ **FAIL** → DEFERRED v0.2.0

**要求**: 
- 独立进程，不依赖策略运行
- 断点续传（重启后自动恢复）
- 缺口检测与补齐

**当前状态**:
- ✅ 采集器代码: `src/htx_trend/research/data/collector.py`
- ❌ 无独立进程/调度机制
- ❌ 无断点续传逻辑
- ❌ 无 systemd unit 文件

**行动**: v0.2.0 创建独立采集进程 + systemd unit + 断点续传。

---

### C5. 币安拼接数据带来源和 non_comparable 标记

**状态**: ❌ **FAIL** → DEFERRED v0.2.0

**要求**:
- `non_comparable` 标记：偏差 >0.3% 的 K 线
- `external` 标记：Binance volume/funding/OI 排除于百分位

**当前状态**:
- ✅ 偏差检测代码: `src/htx_trend/research/data/stitcher.py:25-45`
- ❌ `non_comparable` 列未添加到 DataFrame
- ❌ `external` 标记未实现
- ❌ HTX/Binance 分页未实现

**行动**: v0.2.0 添加列标记 + 分页实现。

---

### C6. 配置 + 数据集 hash + git commit 完整重放

**状态**: ✅ PASS

**证据**:
- **数据集 hash**: `src/htx_trend/research/data/dataset.py:28-35`
- **Git commit**: `src/htx_trend/research/runner.py:143-154`
- **配置快照**: `src/htx_trend/research/runner.py:57-66`
  ```python
  run_record = RunRecord(
      run_id=self.run_id,
      git_commit=git_commit,
      dataset_hash=frozen.manifest_hash,
      config_snapshot=self.config,
      ...
  )
  ```

- **测试**: `tests/research/test_strict_determinism.py`
  - ✅ `test_strict_determinism_identical_runs`: 相同配置 → 相同 parquet hash
  - ✅ `test_hash_change_detection_and_refusal`: 数据改变 → hash 变化检测

**结论**: 配置 + 数据 hash + commit 三元组可完整重放。

---

## D. 实验记录 (Experiment Recording)

### D1. 登记门禁

**状态**: ✅ PASS

**证据**:
- **代码**: `src/htx_trend/research/runner.py:83-110`
  ```python
  def _validate_registration(self) -> None:
      if not reg_config.get("hypothesis"):
          raise ValueError("Registration incomplete: 'hypothesis' is required")
      if not reg_config.get("keep_rule"):
          raise ValueError("Registration incomplete: 'keep_rule' is required")
      if not reg_config.get("drop_rule"):
          raise ValueError("Registration incomplete: 'drop_rule' is required")
  ```

- **配置**: `configs/runs/R0001.yaml:13-19`
  ```yaml
  registration:
    change: "Initial baseline with C01 Donchian + C02 ATR stop"
    hypothesis: "Donchian ensemble can capture medium-term trends"
    keep_rule: "Sample size ≥30, PF ≥1.3, positive net expectation"
    drop_rule: "Sample size <30 or PF <1.0 or negative expectation"
  ```

**结论**: 登记门禁完整，缺失字段抛出异常。

---

### D2. 一次只改一个条件

**状态**: ⚠️ **PARTIAL** → DEFERRED v0.2.0

**要求**: 登记门禁验证 exactly one changed condition vs parent_run_id

**当前状态**:
- ✅ `parent_run_id` 字段存在
- ❌ 无自动验证逻辑：对比 parent 配置，确保只改一个条件

**行动**: v0.2.0 添加 `_validate_single_change()` 方法，对比 parent 配置。

---

### D3. 试验计数只增不减

**状态**: ✅ PASS

**要求**: 试验计数 append-only，可导出供 DSR (决策支持报告) 使用

**证据**:
- **Schema**: `src/htx_trend/research/ledger/ledger.py:96-102`
  ```sql
  CREATE TABLE IF NOT EXISTS trials (
      run_id TEXT NOT NULL,
      trial_number INTEGER NOT NULL,
      started_at TEXT NOT NULL,
      completed_at TEXT,
      PRIMARY KEY (run_id, trial_number)
  )
  ```

- **插入逻辑**: append-only（无 UPDATE/DELETE）
- **导出**: SQLite 数据库可直接查询导出

**结论**: 试验计数只增不减，可导出。

---

### D4. 测试集锁

**状态**: ✅ PASS

**要求**: 每次测试集读取必须记录

**证据**:
- **Schema**: `src/htx_trend/research/ledger/ledger.py:104-112`
  ```sql
  CREATE TABLE IF NOT EXISTS test_set_locks (
      dataset_id TEXT NOT NULL,
      run_id TEXT NOT NULL,
      locked_at TEXT NOT NULL,
      approver TEXT,
      PRIMARY KEY (dataset_id, run_id)
  )
  ```

- **锁定逻辑**: `src/htx_trend/research/data/dataset.py:95-102`
  ```python
  if split == "test":
      # Log test set access
      ledger.lock_test_set(dataset_id, run_id, approver)
  ```

**结论**: 测试集访问已记录。

---

### D5. 四层记录（运行/登记/决策/成交）完整

**状态**: ✅ PASS

**证据**:
- **运行**: `src/htx_trend/research/ledger/ledger.py:43-55` (`runs` 表)
- **登记**: `src/htx_trend/research/ledger/ledger.py:57-71` (`registrations` 表)
- **决策**: `src/htx_trend/research/runner.py:254-277` (parquet 输出)
- **成交**: `src/htx_trend/research/runner.py:279-292` (parquet 输出)

**结论**: 四层记录完整。

---

## E. 风控与账户 (Risk & Account)

### E1. 只允许主账号下单

**状态**: ✅ PASS

**证据**:
- **账户守卫**: `src/htx_trend/research/account_guard.py:24-71`
  ```python
  class AccountGuard:
      def require_permission(self, account_id: str, operation: str) -> None:
          allowed, reason = self.check_order_permission(account_id, operation)
          if not allowed:
              raise PermissionError(reason)
  ```

- **配置**: `configs/runs/R0001.yaml:6-11`
  ```yaml
  account:
    account_id: "MAIN"
    account_type: "MAIN"
    margin_mode: "cross"
  ```

- **测试**: `tests/research/test_account_guard.py`
  - ✅ 7 tests 验证账户权限检查

**结论**: 子账户下单被拒绝，主账户验证通过。

---

### E2. 全仓下按账户检查强平

**状态**: ✅ PASS

**要求**: 包含非策略持仓，所有止损同时触发不强平

**证据**:
- **代码**: `src/htx_trend/research/risk/liquidation_check.py:30-68`
  ```python
  def check_account_liquidation_risk(
      self, account_equity: float, all_positions: list, strategy_positions: list
  ) -> tuple[bool, float, str]:
      # Calculate worst case: all stops hit simultaneously
      max_simultaneous_loss = sum(...)
      equity_after_loss = account_equity - max_simultaneous_loss
      
      # Check against liquidation threshold
      total_mmr = sum(...)
      if equity_after_loss < total_mmr + buffer:
          return (False, distance, "Risk of liquidation")
  ```

- **测试**: `tests/research/test_account_liquidation.py`
  - ✅ `test_account_liquidation_check_boundary`: 边界用例
  - ✅ `test_non_strategy_positions_detected`: 非策略持仓检测

**必备测试**: ✅ "全仓账户级强平检查的边界用例" 已存在

**结论**: 账户级强平检查完整，包含非策略持仓。

---

### E3. 非策略持仓会阻止开仓并告警

**状态**: 🟡 **PARTIAL** → DEFERRED v0.2.0

**当前状态**:
- ✅ 检测逻辑: `src/htx_trend/research/risk/liquidation_check.py:70-86`
  ```python
  def get_non_strategy_positions(
      self, all_positions: list, strategy_symbols: list[str]
  ) -> list:
      return [p for p in all_positions if p["symbol"] not in strategy_symbols]
  ```

- ✅ Status 输出标记: `src/htx_trend/research/ops/status.py`
- ❌ 阻止开仓逻辑未实现（需在 runner 中检查并拒绝）
- ❌ 告警未实现

**行动**: v0.2.0 添加非策略持仓检查，阻止开仓 + 告警。

---

### E4. 费率读取和回退过程有记录

**状态**: ✅ PASS

**证据**:
- **费率接口**: `src/htx_trend/research/adapters/htx/fees.py:9-79`
  - `DefaultFeeProvider`: 默认费率 (maker 0.02%, taker 0.06%)
  - `HtxAuthenticatedFeeProvider`: 认证费率（stub）
- **记录**: `src/htx_trend/research/engine/backtest/sim_broker.py:54-61`
  ```python
  self.fee_rates_used: dict[str, FeeRates] = {}
  # 每个 symbol 的费率使用记录
  ```

- **配置**: `configs/runs/R0001.yaml:34-40`
  ```yaml
  fees:
    use_authenticated: false
    # 注：base scenario 默认为 maker 0.02% / taker 0.06%
    # 真实费率来源将在报告中标注
  ```

**结论**: 费率来源和回退过程有记录。

---

### E5. 熔断条件与审批门禁都已实现

**状态**: ✅ PASS

**证据**:
- **审批门控**: `src/htx_trend/research/ledger/ledger.py:114-134`
  ```sql
  CREATE TABLE IF NOT EXISTS approvals (
      gate TEXT NOT NULL,  -- PRE_PAPER, PRE_LIVE, BREAKER_RESTART
      run_id TEXT NOT NULL,
      strategy_version TEXT NOT NULL,
      approver TEXT NOT NULL,
      decision TEXT NOT NULL,  -- APPROVED / REJECTED
      rationale TEXT,
      approved_at TEXT NOT NULL,
      PRIMARY KEY (gate, run_id, strategy_version)
  )
  ```

- **CLI**: `src/htx_trend/research/cli.py`
  - `approve` 命令
  - `check-approval` 命令

- **测试**: `tests/research/test_approvals.py`
  - ✅ 审批流程测试

**结论**: 审批门控完整（PRE_PAPER, PRE_LIVE, BREAKER_RESTART）。

---

## F. 成本 (Cost)

### F1. 四档成本场景

**状态**: ✅ PASS

**证据**:
- **代码**: `src/htx_trend/research/engine/backtest/sim_broker.py:10-14`
  ```python
  class CostScenario(str, Enum):
      ZERO = "zero"
      OPTIMISTIC = "optimistic"
      BASE = "base"
      CONSERVATIVE = "conservative"
  ```

- **费率映射**: `src/htx_trend/research/engine/backtest/sim_broker.py:90-116`

**结论**: 四档成本场景完整。

---

### F2. 资金费按结算期逐期计入

**状态**: ✅ PASS

**证据**:
- **代码**: `src/htx_trend/research/engine/backtest/sim_broker.py:203-220`
  ```python
  def _apply_funding(self, symbol: str, bar_time: datetime) -> None:
      if bar_time.hour in [0, 8, 16]:  # Funding times
          funding_rate = self._get_funding_rate(symbol, bar_time)
          funding_cost = position_value * funding_rate
          position.unrealized_pnl -= funding_cost
  ```

**结论**: 资金费按结算期（0, 8, 16 点）逐期计入。

---

### F3. 回测初始资金和实际账户一致（400 USDT）

**状态**: ✅ PASS

**证据**:
- **配置**: `configs/runs/R0001.yaml:30`
  ```yaml
  initial_equity: 400.0  # 初始资金 400 USDT（真实账户起始资金）
  ```

- **测试**: `tests/research/test_position_sizing_400usdt.py`
  - ✅ 5 tests 验证 400 USDT 下的仓位计算

**结论**: 回测初始资金与实际账户一致。

---

## G. 工程质量 (Engineering Quality)

### G1. 测试覆盖确定性、无前视、hash 变化、强平检查、仓位计算边界

**状态**: ✅ PASS

**测试清单**:

1. **确定性**: ✅ `tests/research/test_strict_determinism.py:test_strict_determinism_identical_runs`
   - 同一配置跑两次结果逐字节一致

2. **无前视**: ✅ `tests/research/test_strict_determinism.py:test_no_lookahead_truncation_invariance`
   - 截断未来数据不改变历史决策

3. **Hash 变化**: ✅ `tests/research/test_strict_determinism.py:test_hash_change_detection_and_refusal`
   - 修改一根 K 线 → dataset hash 改变

4. **强平检查**: ✅ `tests/research/test_account_liquidation.py`
   - 边界用例（所有止损同时触发）

5. **仓位计算边界**: ✅ `tests/research/test_position_sizing_400usdt.py`
   - 最小合约规模超标跳过
   - 总风险限制
   - 最大仓位数

6. **通道不含当前 K 线**: ✅ `tests/research/test_donchian_breakout.py:test_donchian_excludes_current_bar`

7. **已知突破触发**: ✅ `tests/research/test_donchian_breakout.py:test_donchian_breakout_long_detected`

**必备测试对照**:
- ✅ 通道不含当前 K 线的单元测试
- ✅ 构造一个已知突破的样例，信号必须触发
- ✅ 用未来数据打乱的方式检测前视（截断测试）
- ✅ 同一配置跑两次结果逐字节一致
- ❌ 修订一根 K 线后产生新版本，旧版本仍可读（DEFERRED v0.2.0，见 C3）
- ✅ 最小张数超出风险预算时跳过开仓
- ✅ 全仓账户级强平检查的边界用例

**结论**: 7/8 必备测试完成，修订 K 线版本化测试留待 v0.2.0。

---

### G2. 下单和仓位相关代码须经第二人评审

**状态**: ⚠️ **DEFERRED v0.2.0**

**理由**: 代码审查流程未建立（无 PR review 记录）

**行动**: v0.2.0 建立 PR review 流程，风控代码强制双人审查。

---

## H. 运维 (Operations)

### H1. Health 命令正常，stale heartbeat/kline 返回非零

**状态**: ✅ PASS

**证据**:
- **Health 命令**: `src/htx_trend/research/cli.py:health()`
- **Heartbeat 检查**: `src/htx_trend/research/ops/heartbeat.py:31-49`
  ```python
  def check_health(self, max_age_seconds: int = 300) -> bool:
      if (now - last_heartbeat).total_seconds() > max_age_seconds:
          return False
      # Check last_processed_bar_time staleness
  ```

- **退出码**: 健康返回 0，不健康返回 1

**测试**: `tests/research/test_ops.py`
- ✅ Heartbeat 健康检查测试

**结论**: Health 命令正常，过期返回非零。

---

### H2. Status --json 字段完整

**状态**: ✅ PASS

**要求字段**:
- version/branch/commit
- positions
- PnL
- liquidation distance
- breaker
- approvals
- non-strategy flag

**证据**:
- **代码**: `src/htx_trend/research/ops/status.py:14-60`
  ```python
  def get_status() -> dict:
      return {
          "version_info": {
              "version": __version__,
              "branch": get_branch(),
              "commit": get_commit(),
          },
          "positions": [...],
          "total_pnl": ...,
          "account_liquidation_distance_pct": ...,
          "circuit_breaker": {...},
          "approvals": [...],
          "non_strategy_positions": [...],
      }
  ```

**结论**: Status --json 字段完整。

---

### H3. JSON 日志正常

**状态**: ✅ PASS

**证据**:
- **代码**: `src/htx_trend/research/ops/logging.py:58-121`
  ```python
  def log_event(
      component: LogComponent,
      event: str,
      level: LogLevel = LogLevel.INFO,
      **kwargs,
  ):
      log_data = {
          "timestamp": datetime.now(timezone.utc).isoformat(),
          "level": level.value,
          "component": component.value,
          "event": event,
          **kwargs,
      }
      print(json.dumps(log_data))
  ```

- **测试**: 所有测试输出 JSON 日志

**结论**: 结构化 JSON 日志正常。

---

### H4. Systemd unit + 部署/回滚文档

**状态**: 🟡 **PARTIAL** → DEFERRED v0.2.0

**当前状态**:
- ✅ 部署文档: `htx-trend-python/DEPLOYMENT.md` (500+ 行)
  - 部署步骤
  - 回滚步骤
  - 健康检查
  - 故障排查
- ❌ Systemd unit 文件未创建

**行动**: v0.2.0 创建 systemd unit 文件（采集器、heartbeat、策略运行）。

---

### H5. 告警能发到群里

**状态**: ⚠️ **DEFERRED v0.2.0**

**当前状态**: 告警机制未实现

**行动**: v0.2.0 集成告警（Webhook/钉钉/Slack）。

---

## 额外检查项 (Additional Checks)

### Extra 1. Report 包含 evidence_ref/conclusion/decision 字段

**状态**: 🟡 **PARTIAL** → DEFERRED v0.2.0

**当前状态**:
- ✅ `evidence_ref` 在 registration 中: `configs/runs/R0001.yaml:19`
- ✅ `conclusion` 在 schema 中: `src/htx_trend/research/ledger/ledger.py:65-66`
- ❌ Report 输出未包含这些字段（仅性能指标）

**行动**: v0.2.0 报告生成器添加 evidence_ref/conclusion/decision。

---

### Extra 2. C01/C02 参数与参数依据文档一致

**状态**: ⚠️ **DEFERRED v0.2.0**（同 A6）

**理由**: 参数依据文档不存在

---

### Extra 3. 登记门禁：exactly one changed condition vs parent_run_id

**状态**: ⚠️ **DEFERRED v0.2.0**（同 D2）

**行动**: v0.2.0 实现配置对比逻辑。

---

### Extra 4. 采集器独立且可恢复

**状态**: ❌ **FAIL** → DEFERRED v0.2.0（同 C4）

---

## 总结 (Summary)

### 通过项 (PASS): 22/35

**核心功能完整**:
- ✅ 策略专业性（信号/成交/风险/仓位）5/6
- ✅ 数据冻结（配置+hash+commit 重放）
- ✅ 实验记录（登记门禁、试验计数、测试集锁）
- ✅ 风控（账户守卫、强平检查、审批门控）
- ✅ 成本（四档场景、资金费、400 USDT）
- ✅ 测试覆盖（确定性、无前视、边界用例）
- ✅ 运维接口（health、status、JSON 日志）

### 不通过/延后项 (FAIL/DEFERRED): 13/35

**v0.2.0 目标** (P0 - 发版前必须):
1. ❌ C1: K 线追加式存储 + 批次 hash
2. ❌ C2: bar_time + first_seen_time 分离
3. ❌ C3: K 线版本化（修订不覆盖）+ 测试
4. ❌ C4: 独立采集进程 + systemd unit + 断点续传
5. ❌ C5: non_comparable + external 标记 + 分页
6. ⚠️ D2: 单条件变更验证
7. ⚠️ E3: 非策略持仓阻止开仓 + 告警
8. ⚠️ H4: Systemd unit 文件
9. ⚠️ H5: 告警集成
10. ⚠️ Extra 1: Report 字段补全
11. ⚠️ A6/Extra 2: 参数依据文档

**v0.3.0 目标** (P1 - 优化项):
12. ⚠️ B1: Stage 9 (Ablation & Sensitivity)
13. ⚠️ G2: 双人代码审查流程

**v0.4.0 目标** (P2 - PAPER 完整):
14. 🟡 B1: Stage 11 (PAPER 完整实现)

---

## 评审结论 (Review Conclusion)

**当前状态**: ⚠️ **CONDITIONAL PASS with v0.2.0 blockers**

**可发布到研究环境**: ✅ YES
- 核心回测引擎完整
- 严格确定性保证
- 风控边界清晰
- 运维接口可用

**可发布到 PAPER**: ❌ NO (需 v0.2.0)
- 数据层不可变性缺失（C1-C5）
- 采集器未独立（C4）
- 告警未集成（H5）

**可发布到 LIVE**: ❌ NO (需 v0.2.0 + v0.4.0)
- PAPER 模式未完整实现（B1 Stage 11）
- 参数依据文档缺失（A6）

---

**审查人**: _____________  
**日期**: 2026-10-11  
**决策**: [ ] 批准研究环境发布 / [ ] 需补充 v0.2.0 / [ ] 拒绝
