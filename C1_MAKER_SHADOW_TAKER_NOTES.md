# C1-MAKER-SHADOW-TAKER 编码交审笔记

> **性质**：仅编码 → 交审；**禁止 merge、出包、装机、热更、LIVE、admission**。  
> **声明**：编码 ≠ 过闸 ≠ 出包；σ_d 仍 ≥ 200 ORDERPX，B 合规配对结论仍欠权威 fills。

## 绑定核验

| 文档 | 路径 | 状态 |
|------|------|------|
| 开发口令 C1 | `/Users/jenniferni/jenifer/build/c1-maker-shadow-20261026-specs/dev-order-c1-paper-maker-shadow-taker-20261026.md` | SHA256 `1ca193cab70dd709f373d30eefc037b992856c599f8f6086ee0bac458e4ac8b6` ✅ |
| 开发口令副本 | `/Users/jenniferni/jenifer/build/c1-maker-shadow-20261026/dev-order-c1-paper-maker-shadow-taker-20261026.md` | SHA256 `1ca193cab70dd709f373d30eefc037b992856c599f8f6086ee0bac458e4ac8b6` ✅ |
| 授权 | BrainBot 用户确认卡 t569s1989 = C1 | ✅ |
| 基线 | `origin/support_htx_python` @ `5c5a11d8173ed9a018357d210bcb37646445e99a`（MAINLINE-AB #51） | ✅ |

- 分支：`feature/c1-maker-shadow-taker-20261026`
- 返工后 tip SHA：`78f958fb542957a2ec529e0bba30b79d9f063e38`
- partial_accept 透传修复后 tip SHA：`e3d34358241027f02caef00143789a5b601f3385`
- PR 号/URL：#52 → `support_htx_python`

## 返工说明

产品审合 Fail 根因：maker / P-SHADOW 只挂在 `execution_service.py`（LIVE EntryExecutor），`paper_worker.py` / `runtime.py` 零引用 `MakerExecutionConfig` / `p_shadow_taker` / `evaluate_maker_lifecycle`。

本次返工已把冻结表 + P-SHADOW-TAKER 接到 **PAPER 主路径**（`PaperTrendWorker` → `PaperTrendRuntime`）：

- `htx-trend-python/src/htx_trend/paper_worker.py`：读取 `MakerExecutionConfig.from_env()` 并注入 `PaperTrendRuntime`；`worker.py` 将 `evidence_store` 传入 PAPER worker。
- `htx-trend-python/src/htx_trend/runtime.py`：`PaperTrendRuntime.scan()` 在 `maker_config.enabled` 时走 maker 路径：
  - 定价锚 = 最优同向价（`maker_reference_price`，已支持 `TopOfBook`）
  - LIMIT 价 = `maker_limit_price(side, reference, offset_bps)`
  - 单 bar 零改价生命周期 = `evaluate_maker_lifecycle(candles=(execution_candle,), max_wait_bars=cfg.max_wait_bars, max_amends=cfg.max_amends, partial_accept=cfg.partial_accept, timeout_action=cfg.timeout_action)`
  - 超时/未成 = `CANCELLED` / `PARTIAL_CANCELLED` / `MAKER_EXPIRED`，**绝不**转 MARKET
  - 无盘口 → `MAKER_MISSING_BOOK`（微结构缺失=缺失）
- P-SHADOW-TAKER：maker on 时写 `p_shadow_taker` evidence（legs `maker_live` / `taker_shadow`）：
  - 有 `evidence_store` 时 `put("p_shadow_taker", ...)`
  - 无 store 时落 `ledger.decision(kind="P_SHADOW_TAKER", ...)`，保证可审计
- maker off（默认）：PAPER 行为与现网一致，仍按 `gate.price_plan.style` 选 MARKET/LIMIT taker。

## 冻结表旋钮

| 配置名 | 代码默认 | C1 可配值 | 说明 |
|--------|----------|-----------|------|
| `HTX_TREND_MAKER_ENABLED` | `false` | `true` | 默认仍为 taker；C1 运维 env 开 |
| `HTX_TREND_MAKER_OFFSET_BPS` | `2` | `0` | 0 时 LIMIT 价 = 最优同向价锚 |
| `HTX_TREND_MAKER_MAX_WAIT_BARS` | `4` | `1` | 单 bar 等待 |
| `HTX_TREND_MAKER_MAX_AMENDS` | `None`（无显式限制，兼容旧行为） | `0` | 0 时禁用追价循环 |
| `HTX_TREND_MAKER_PARTIAL_ACCEPT` | `false` | `false` | 部分成 = 缺失；不补挂 |
| `HTX_TREND_MAKER_TIMEOUT_ACTION` | `TAKER` | `CANCEL` | 超时 ≠ 转吃单；C1 运维钉 CANCEL |

- post-only：PAPER 模拟盘以 LIMIT 价触及为成交条件；未触及则 `CANCELLED` / `EXPIRED` / 缺失。禁止挂失败改吃单。
- 定价锚：最优同向价。LONG→best bid；SHORT→best ask。无盘口→`maker_reference_price` 返回 `None`，微结构不可得=缺失，不静默用 mid。

## 单 bar 零改价最小闭环

落点：`htx-trend-python/src/htx_trend/maker_execution.py` 纯函数 `evaluate_maker_lifecycle`，并在 `PaperTrendRuntime._submit_maker_entry` 中实际调用。

```
place LIMIT @ 最优同向价 (offset=0)
  → wait ≤ max_wait_bars (=1)
  → amend 仅当 max_amends > 0 且阈值突破（C1 钉 0，永不追价）
  → 耗尽后 timeout_action:
       CANCEL → CANCELLED / PARTIAL_CANCELLED（绝不转 taker/market）
       TAKER  → TAKER / PARTIAL_TAKER（仅兼容旧行为，C1 禁用）
```

- `partial_accept=False` 时任何部分成直接取消剩余，不补挂。
- 禁止在启用 maker 后静默走「超时转 taker」。

## partial_accept 透传修复

产品审合 Pass 后发现：`evaluate_maker_lifecycle` 内层调用 `evaluate_maker_fill(..., partial_accept=True)` 写死为 `True`，导致外层 `HTX_TREND_MAKER_PARTIAL_ACCEPT=false` 时，触及限价单仍被模拟为半成，进而进入 `PARTIAL_CANCELLED`，几乎无 FILLED 开仓样本。

修复：将内层 `partial_accept=True` 改为透传外层参数 `partial_accept=partial_accept`。

C1 语义：

| 条件 | 期望 |
|------|------|
| `partial_accept=False` + 触及 limit | **FILLED 全成**（quantity 全填；有仓） |
| `partial_accept=False` + 未触及 | **CANCELLED**（无仓；无 MARKET 回退） |
| `partial_accept=True` + 触及 | 保留半成模拟路径（`evaluate_maker_fill` 半成；lifecycle 可多 bar 填完或 PARTIAL_*） |
| 任何路径 | **禁止**半成后再挂 / 转 MARKET（C1 冻结：amends=0 / timeout=CANCEL） |

对应测试更新：

- `test_evaluate_maker_lifecycle_partial_accept_false_fills_fully_when_touched`：触及 + false → FILLED 全成
- `test_paper_maker_on_limit_touched_partial_accept_false_fills_full_position`：PAPER 触及 + false → LIMIT 单成交、有仓
- `test_evaluate_maker_lifecycle_partial_accept_true_keeps_remaining`：半成路径保留
- `test_paper_maker_on_limit_not_touched_cancels_no_position`：未触及 cancel 路径保留

## P-SHADOW-TAKER

主路径 = 真实 maker LIMIT 发单；同信号另记影子 taker 意图价/假设成本，**只记账、不发单**。

- Evidence key：`p_shadow_taker`
- 记录结构显式 `leg` 字段：
  - `leg=maker_live`：真实 LIMIT 单价格、maker fee、成本 bps
  - `leg=taker_shadow`：假设 taker 价格（LONG 取 best ask，SHORT 取 best bid）、taker fee、成本 bps
- 保留旧 `maker_shadow_pair` evidence（主 taker 时影子 maker），用于向后兼容；C1 主场景使用新 key。

落点：
- `htx-trend-python/src/htx_trend/execution_service.py`（LIVE `submit()`）
- `htx-trend-python/src/htx_trend/runtime.py`（PAPER `_submit_maker_entry()`）

## 钟（代码侧只协助）

代码侧不擅自切钟。运维维度建议通过 `maker_cut_ts`（或等价 `window_id`）标记 maker 路径启用时刻；后续分析必须避免把切前 taker 段与切后 maker 段混进同一主闸。代码中不自动混算。

## 硬禁确认

- [x] 未做 LIVE / admission / LiveApproval / 仓286 / 43.139 N* 变更
- [x] 未触碰 `htx-trend-paper-4h` unit 或其目录
- [x] 未放宽 ATR / 硬止损 / 仓位与保证金上限
- [x] 未改入场信号逻辑；成本假设 12bps + funding 口径不变
- [x] 未改 `research/lob_prob`、collector/nginx/前端部署
- [x] 未执行生产迁移 / 未连现网 DB
- [x] 未自行装机 / SSH 热更
- [x] 未 force push / 未 merge
- [x] 未宣称 σ_d 过闸或 B 合规配对通过

## pytest 汇总

| 套件 | passed | failed | skipped |
|------|--------|--------|--------|
| `htx-trend-python` | 553 | 0 | 0 |

运行命令：

```bash
export PATH="/opt/homebrew/bin:$HOME/.local/bin:$PATH"
export ALL_PROXY=socks5h://127.0.0.1:7897 HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 https_proxy=http://127.0.0.1:7897 http_proxy=http://127.0.0.1:7897
PY=/Users/jenniferni/jenifer/venv-research/bin/python
cd /Users/jenniferni/jenifer/build/c1-maker-shadow-20261026/htx-trend-python
PYTHONPATH=src $PY -m pytest -q
```

测试覆盖：
1. PAPER 默认 maker off → 仍 MARKET/taker（`test_paper_maker_shadow.py`）
2. PAPER maker on + 冻结旋钮（offset=0 / wait=1 / amends=0 / partial=false / timeout=CANCEL）→ LIMIT/锚价/单 bar 取消或缺失；**无 MARKET 回退**
3. PAPER maker on ⇒ `p_shadow_taker` 记录存在（含 leg 区分）
4. `test_paper_worker.py` 新增：PAPER worker 从 env 读取 maker 冻结旋钮并注入 runtime
5. 既有 MAINLINE-B / A2 / maker / execution_service 套件保持通过

## 改动文件列表

```
htx-trend-python/src/htx_trend/maker_execution.py
htx-trend-python/src/htx_trend/runtime.py
htx-trend-python/src/htx_trend/paper_worker.py
htx-trend-python/src/htx_trend/worker.py
htx-trend-python/tests/test_paper_maker_shadow.py（新增）
htx-trend-python/tests/test_paper_worker.py
C1_MAKER_SHADOW_TAKER_NOTES.md（本文件）
```

## 声明

- 本 PR 仅完成编码与测试，**不合入、不出包、不热更**。
- ORDERPX σ_d 仍 ≥ 200；B 合规配对结论仍需权威 fills 后方可主张。
- 装机、切钟、VOID/RESET 等运维操作须另单审批执行。
