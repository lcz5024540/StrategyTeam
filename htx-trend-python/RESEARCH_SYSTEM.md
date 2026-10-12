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
- **策略清单**：见下方「策略条件清单 (strategy_manifest.yaml)」

### 4. 风险管理
- **仓位计算**：单笔风险 2%（300U 账户最大亏损 6U），总风险 ≤10%，最多 5 仓（品种仍仅 BTC/ETH 4h）
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
- **健康检查**：`python -m htx_trend health`（或 `python -m htx_trend.research.cli health`）返回非零表示异常
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

freeze 之前必须拉**全历史**（`--lookback-days max`）。不要用 `--lookback-days 1095` 当正式冻结窗口。采集和冻结用**不同 workspace**，避免和运行时目录混在一起。

```bash
python -m htx_trend.research collect \
  --workspace /data/htx-collect \
  --contracts BTC-USDT,ETH-USDT \
  --lookback-days max \
  --backfill-binance
```

### 3. 冻结数据集

在单独的 freeze workspace 里做。`--cutoff` 必填。1d 必须复用 4h 的精确边界（`--split-from-manifest` 或同时传 `--validation-start` 与 `--test-start`）。

```bash
python -m htx_trend.research freeze \
  --workspace /data/htx-freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --backfill-binance \
  --overlap-threshold 0.003 \
  --cutoff 2026-10-12T00:00:00Z

python -m htx_trend.research freeze \
  --workspace /data/htx-freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 1day \
  --daily-cut UTC00 \
  --backfill-binance \
  --overlap-threshold 0.003 \
  --split-from-manifest /data/htx-freeze/data/datasets/<4hour_id>/manifest.json
```

输出按品种打印 train/val/test 根数，不再用单一 idx 混算。

### 4. 编辑运行配置
`configs/runs/R0001.yaml` 的 `dataset_id` / `manifest_hash` 默认是 `TODO_FREEZE_*` 占位，runner 会拒绝。更新为上一步冻结输出的 id 与 `manifest_hash`。

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
python -m htx_trend health --workspace .
# 或 python -m htx_trend.research.cli health --workspace .
```

### 8. 查看状态
```bash
python -m htx_trend status --json
# 无 status 文件时非零退出
# 或 python -m htx_trend.research.cli status --json
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

dataset_id: "TODO_FREEZE_DATASET_ID"  # freeze 后替换
manifest_hash: "TODO_FREEZE_MANIFEST_HASH"
symbols: [BTC-USDT, ETH-USDT]
split: train  # train / validation / test
timeframe: 4hour

initial_equity: 300
cost_scenario: base

risk_per_trade: 0.02
max_total_risk: 0.10
max_positions: 5

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

## 策略条件清单 (strategy_manifest.yaml)

机器可读的 C01–C09 目录，由 **代码 + 运行配置** 生成，提交在包根目录 `strategy_manifest.yaml`。

每条条件包含：`id`、`name`、`implemented`、`enabled`（来自 run config）、`params`（解析后的值，含已声明字段）、`code_path`、`code_sha256`（条件模块文件字节的 SHA256）。清单级字段：`package_version`、`git_commit`（仅记录，**不进入 hash**）、`timeframe`、`symbols`、风险参数（`initial_equity` / `risk_per_trade` / `max_total_risk` / `max_positions` / `leverage_target`）、`cost_scenario`、`dataset_id`、冻结数据集 `manifest_hash`、`split`（及配置里的 split 时间戳）、`manifest_sha256`。Hash 对去掉 `manifest_sha256` / `git_commit` / `package_version` 后的 canonical JSON（`sort_keys=True`）；绑定靠 `code_sha256` 与参数。C02 必须 `enabled: true`。非 SMOKE 的 `run` 要求 `R0001.yaml`（或该次配置）里的 `strategy_manifest_sha256` 与当前清单一致。`scripts/package.py` 打包前跑 `--check`，并写入 `PACKAGE_MANIFEST`（每个打包文件的 sha256 + content hash）。从包运行时，非 SMOKE 在启动前核对磁盘上每个清单文件的 SHA256；从 git checkout 运行时要求工作区干净。Ledger `runs` 记录 `_build_info` 的 commit/version/branch 与 `package_content_hash`。拼接点必须在 `data/quality/stitch_points.yaml`（或 `--stitch-config`）里按品种显式声明为 ISO 时间或 `none`，写入冻结 manifest 的 hash；未声明即拒绝。

```bash
python -m htx_trend.research strategy-manifest \
  --config configs/runs/R0001.yaml \
  --write strategy_manifest.yaml

python -m htx_trend.research strategy-manifest \
  --config configs/runs/R0001.yaml \
  --check
```

`--check` 在 committed 文件相对代码/配置过期时以非零退出。CI / `scripts/package.py` 解包测试会跑 `test_committed_manifest_matches_code_and_r0001`。每次回测把完整 JSON 和 `strategy_manifest_sha256` 写入 ledger；清单构建失败则 run 失败。打 tag 前可打印摘要（**不创建 tag**）：

```bash
python scripts/tag_message.py
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
