# HTX 趋势策略研究系统 MVP-1

独立的Python回测研究系统，用于趋势策略的可复现实验和严格记录。

## 架构

```
htx_trend/research/
├── adapters/        # HTX + Binance 市场数据客户端
│   ├── htx/        # HTX 公开接口（复用现有 htx_v5.py）
│   └── binance.py  # Binance USDT-M（仅历史回填）
├── data/           # 数据采集、拼接、冻结
├── features/       # 技术指标：ATR, EMA, Donchian, ADX
├── context/        # 市场状态标签（regime）
├── conditions/     # 条件插件：C01 Donchian, C02 ATR止损
├── strategy/       # 状态机：FLAT → ENTRY_PENDING → OPEN → EXIT_PENDING
├── risk/           # 仓位计算、强平检查
├── engine/         # 事件驱动回测引擎
│   └── backtest/   # SimBroker: t收盘信号 → t+1成交
├── ledger/         # SQLite 账本（runs, registrations, trials, test_locks）
├── reports/        # Markdown 报告生成
└── ops/            # 运维监控：结构化日志、心跳、状态接口
```

## 核心特性

### 1. 数据管理
- **HTX 数据采集**：4h/1d K线、资金费历史、合约规格
- **Binance 回填**：用于补充早期历史（2-3年），标记来源
- **拼接与检查**：重叠期close价格偏差 >0.3% 标记为非可比
- **数据集冻结**：manifest + hash，60/20/20 train/val/test split

### 2. 特征与市场状态
- **技术指标**：ATR(14/20), EMA(20/60/120), Donchian(42/84/126/168), ADX(14)
- **Regime标签**：trend_state (TREND_UP/DOWN/RANGE/TRANSITION) × vol_state (HIGH/MID/LOW)
- **公式版本**：每个指标带版本号（v1.0.0）

### 3. 条件系统
- **C01 Donchian Ensemble**：多周期突破信号集成，阈值0.25
- **C02 ATR Stop**：ATR14 × 2.0 初始止损
- **插件接口**：统一的 `evaluate(DecisionContext, params) → ConditionResult`

### 4. 风险管理
- **仓位计算**：单笔风险0.5%，总风险≤1.5%，≤3仓位
- **强平检查**：强平距离 ≥ 1.5×止损距离，否则降杠杆
- **最小张数**：超过风险预算则跳过

### 5. 回测引擎
- **事件驱动**：t 收盘信号，t+1 开盘成交
- **成本场景**：zero / optimistic / base(0.02%/0.06%) / conservative
- **资金费**：逐期按历史费率计入
- **无前视**：DecisionContext 只包含截止 t 收盘的数据

### 6. 账本与记录
- **SQLite表**：runs, registrations, trials, test_set_locks
- **注册门禁**：hypothesis/keep_rule/drop_rule 必填，否则拒绝运行
- **试验计数**：每次运行自动累计
- **测试集锁**：hash锁，读第二次拒绝

### 7. 运维监控（MVP-1轻量级）
- **结构化日志**：JSON格式，包含 run_id/component/event/level
- **心跳系统**：每个组件写心跳+最后处理bar时间
- **健康检查**：`python -m htx_trend.research health` 返回非零表示异常
- **状态接口**：仓位、PnL、强平距离、熔断状态（函数+CLI+可选HTTP）
- **非策略仓位标记**：账户上其他策略/手动开的仓位标记为 non-strategy

### 8. 审批门禁
- **三个门禁**：PRE_PAPER（进入PAPER前）、PRE_LIVE（进入实盘前）、BREAKER_RESTART（熔断后重启）
- **审批记录**：gate、run_id、策略版本、审批人、决策（APPROVED/REJECTED/PENDING）、理由、时间
- **版本校验**：审批通过后如果策略版本变更，需要重新审批
- **强制门禁**：没有 APPROVED 记录禁止晋级和重启

### 9. 费率管理
- **FeeProvider 接口**：可接入 HTX 认证 API 获取账户实际费率
- **默认费率**：回测默认 maker 0.02% / taker 0.06%（base scenario）
- **费率记录**：每次运行记录使用的费率来源（default_config / htx_authenticated_api）
- **清晰标注**：报告中明确标注费率来源，区分"假设值"和"账户实际值"

### 10. 账户级风控
- **账户模式**：趋势策略运行在 HTX MAIN 账户（cross margin）
- **策略隔离**：套利策略使用独立子账户，无保证金共享
- **账户级强平检查**：考虑所有仓位同时止损后，账户保证金必须足够且有 buffer
- **非策略仓位识别**：其他策略或手动开的仓位在状态输出中标记

## 使用流程

### 1. 安装
```bash
cd htx-trend-python
pip install -e '.[dev]'
```

### 2. 采集数据
```bash
python -m htx_trend.research collect \
  --workspace . \
  --contracts BTC-USDT,ETH-USDT \
  --lookback-days 1095 \
  --backfill-binance
```

### 3. 冻结数据集
```bash
python -m htx_trend.research freeze \
  --workspace . \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --backfill-binance
```

输出示例：
```
Dataset frozen: 4hour_a1b2c3d4
Total bars: 6570
Train: 3942
Val: 1314
Test: 1314
```

### 4. 编辑运行配置
更新 `configs/runs/R0001.yaml` 中的 `dataset_id` 为上一步的输出。

### 5. 运行回测
```bash
python -m htx_trend.research run configs/runs/R0001.yaml --workspace .
```

输出：
- `runs/R0001/report.md`：Markdown 报告
- `runs/R0001/decisions.parquet`：逐bar决策记录
- `runs/R0001/fills.parquet`：成交明细
- `runs/R0001/run.log`：结构化日志
- `ledger.db`：账本记录

### 6. 查看报告
```bash
cat runs/R0001/report.md
```

### 7. 健康检查
```bash
python -m htx_trend.research health --workspace .
```

### 8. 查看状态
```bash
python -m htx_trend.research status --workspace . --json
```

### 9. 审批管理

创建审批记录：
```bash
python -m htx_trend.research approve \
  --workspace . \
  --gate PRE_PAPER \
  --run-id R0001 \
  --strategy-version v1.0.0 \
  --approver alice@example.com \
  --decision APPROVED \
  --rationale "All backtest metrics pass threshold, ready for PAPER"
```

检查审批门禁：
```bash
python -m htx_trend.research check-approval \
  --workspace . \
  --gate PRE_PAPER \
  --run-id R0001 \
  --strategy-version v1.0.0
```

退出码：0=通过，1=拒绝

## 配置文件格式

见 `configs/runs/R0001.yaml`：

```yaml
run_id: R0001
parent_run_id: null  # 对照运行

registration:
  change: "变更说明"
  hypothesis: "假设（必填）"
  expected_effect: "预期效果"
  keep_rule: "保留标准（必填）"
  drop_rule: "放弃标准（必填）"
  evidence_ref: "文献引用"

dataset_id: "4hour_xxxx"
symbols: [BTC-USDT, ETH-USDT]
split: train  # train / validation / test
stage_acceptance: false  # 测试集锁

initial_equity: 1000.0
cost_scenario: base

risk_per_trade: 0.005
max_total_risk: 0.015
max_positions: 3

conditions:
  C01:
    enabled: true
    periods: [42, 84, 126, 168]
  C02:
    enabled: true
    atr_period: 14
    atr_multiplier: 2.0

fees:
  use_authenticated: false  # 需要 API key
  # maker_rate: 0.0002  # 可选，覆盖默认值
  # taker_rate: 0.0006
```

## 测试

```bash
pytest tests/research/
```

核心测试：
- `test_features.py`：指标计算、无前视
- `test_determinism.py`：相同配置→相同结果；改数据→hash变化
- `test_ops.py`：心跳、健康检查、状态接口

## 数据要求

- **不提交大数据文件**：`data/raw/*.parquet` 和 `data/datasets/*` 应在 `.gitignore`
- **manifest可提交**：`data/datasets/*/manifest.json` 可提交（小文件）
- **网络隔离fallback**：如果 VM 无法访问交易所，可用 fixture 数据集测试管道

## 限制（MVP-1）

- ✅ 公开市场数据，无 API key
- ✅ 仅回测，无 live 下单
- ✅ BTC/ETH 4h，条件 C01+C02
- ❌ 暂无 walk-forward / DSR / PBO（后续版本）
- ❌ 暂无 HTTP 状态端点（CLI 已足够）
- ❌ 暂无资金费/OI拥挤条件（M2）

## 与现有代码关系

- **复用**：`htx_v5.py`（HTX客户端签名和公开接口）
- **独立**：研究系统在 `htx_trend/research/` 下，与 live trading 代码（`worker.py`, `execution.py` 等）完全分离
- **共存**：两套系统可共存于同一仓库，通过不同入口点调用

## 下一步

1. 验收 MVP-1：确保测试通过，R0001 端到端运行
2. 补充条件：C03-C09（跟踪止损、EMA过滤、波动率定仓、拥挤降仓等）
3. 统计工具：DSR, PBO, walk-forward
4. PAPER 执行：接入实时行情，模拟下单
5. 告警与监控：接入告警渠道，夜间值班

## 参考文档

- `trend-strategy-architecture-v0.1.md`
- `trend-strategy-prd-v0.1.md`
- `first-trend-strategy-parameter-rationale.md`
