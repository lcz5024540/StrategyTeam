# LIVE encode package (htx-trend-live-primary)

**LIVE tarball ≠ 密封 admission/approval ≠ 启动交易**。本路径仅产出可审计的 LIVE 代码包与绑定字段，不安装、不启动实例。

本文介绍不带`--realtime`的旧趋势LIVE包。新实时结构使用`build-live --realtime`，版本与策略身份独立，详见[实时结构LIVE待启用包](REALTIME-LIVE-PACKAGE.md)；不得混用旧策略许可。

## 版本命名

- 格式：`0.1.7-live-<8位HEAD>`（例如 `0.1.7-live-dbfee173`）
- 刻意避开已废弃的 `0.1.1-live-*` 线，避免与历史包混淆
- PAPER 候选包仍使用 `0.1.6-paper-db-*` / `0.1.7-evidence-v2-*`；**不得**把 PAPER tar 标记为 LIVE

## 绑定

| 字段 | 值 |
|---|---|
| `instance_key` | `htx-trend-live-primary` |
| `strategy_version` | `HTX_TREND_15M_V2` |
| `timeframe_profile` | `INTRADAY_15M` |
| `mode` | `LIVE` |

## CLI（仓库根目录下 `htx-trend-python/`）

```sh
cd htx-trend-python
export PYTHONPATH=src
PY=python3
OUT=/tmp/htx-live/frozen
"$PY" release/package.py build-live --out-root "$OUT"
TAR="$("$PY" release/package.py build-live --out-root /tmp/htx-live/build2)"  # 或读取上一条 stdout
"$PY" release/package.py verify "$TAR"
```

`verify` 对 LIVE 与 PAPER 包自动分流：LIVE manifest 含 `"live": true`。

## 包内容

- `src/htx_trend/**/*.py`、`pyproject.toml`、README
- `release/` 打包入口、`release/artifacts/*.json`
- **`deployment/`**（systemd / env 示例）
- 生成的 `release/config.example.json`（`mode=LIVE`，非运行账户配置）

不含 SQL、测试、密钥、预伪造的 `synthetic:false` 产品授权。

## 15m continuation（LIVE，范围说明）

**路径 B 对齐范围（相对 PAPER `0.1.6-paper-db-dbfee173` + `confirm-v1`）：**

| # | 能力 | 状态 |
|---|---|---|
| 1 | 开单主路径（信号→闸门→下单） | **已对齐逻辑族**；`ProductLiveWaiver` 旁路 G2 PAPER≥14d 后 `LiveTrendWorker`→`HtxTrendEntryExecutor.submit` 可走通 |
| 2 | 出场 / 保护位 confirm-v1 | **保持**（`LivePositionManager` + `HTX_LIVE_CONTINUATION_VERSION=confirm-v1`） |
| 3 | 杠杆 10 | **装机/交审须设** `HTX_TREND_LEVERAGE=10`（代码默认 5 未改） |
| 4 | 最大活跃仓 | **故意不对齐**：LIVE 硬限 **3**（`LiveRiskPolicy`）；PAPER 默认可 5 |
| 5 | linked `reentry_gate` | `linked-v1` 在LIVE与PAPER均启用价格恢复重入门；confirm-v1不含此门 |
| 6 | cooldown B 组 | LIVE baseline/confirm沿用已接入的完整交易冷却；LIVE linked不叠加固定冷却，PAPER B仍是独立对照 |
| 7 | ProductLiveWaiver 启 LIVE | **`HTX_TREND_PRODUCT_LIVE_WAIVER_PATH`**；与 G2 三件套双挂 **fail-closed** |

**当前 LIVE 生效范围（exit 侧）：**

- **Exit / 持仓管理**：`HTX_LIVE_CONTINUATION_VERSION=confirm-v1`（或 `linked-v1`）在新仓提交前冻结到持仓状态，`LivePositionManager` 对带相应版本的 **15m `HTX_TREND_15M_V2`** 持仓走 `advance_confirmation` / `decide_continuation`。
- **Entry / 入场**：LIVE linked调用同一价格恢复门，读取账户/合约/profile内最近DONE交易及匹配的complete_exit事实；缺失则拒绝同向保护重入。
- **Cooldown（B 组）**：PAPER通过独立开关选择；LIVE baseline/confirm保留原冷却接线，linked关闭固定冷却。

```sh
export HTX_LIVE_CONTINUATION_VERSION=confirm-v1   # baseline-v4 | confirm-v1 | linked-v1
export HTX_TREND_LEVERAGE=10                      # 交审/装机对齐 PAPER HTX_PAPER_LEVERAGE=10
```

PAPER 仍只读 `HTX_PAPER_CONTINUATION_VERSION`；LIVE **不会**单独读取 PAPER 变量。详见 [CONTINUATION.md](CONTINUATION.md) 开关表。

## ProductLiveWaiver（路径 B · 旁路 G2 PAPER≥14d）

**≠ 伪造 `synthetic:false` admission**；顶层 `synthetic:false` 仅表示本 waiver 件非 demo fixture。

从 **已 verify 的新 B LIVE 冻包**（交审 Pass 后 `build-live` 产出；**不是**基线 `0.1.7-live-73fe4c1b`）签发：

```sh
"$PY" release/seal_product_live_waiver.py seal "$TAR" --output /tmp/product-live-waiver.json \
  --account-id 7 --approved-by 'product@example' --rollback-point 'RB-path-B' \
  --product-stamp /workspace/reports/product-stamp-live15m-path-B-waiver-encode-20261031.md
"$PY" release/seal_product_live_waiver.py validate --artifact /tmp/product-live-waiver.json
```

运行时（旁路枝，**仍需** strategy admission 供 RANKING/GATE/SIZING；**不要**同时配置 G2 approval+context）：

- `HTX_TREND_PRODUCT_LIVE_WAIVER_PATH`
- `HTX_TREND_STRATEGY_ADMISSION_PATH`（OBSERVE 面校验，非 LIVE 面 PAPER≥14d）
- `HTX_PYTHON_PACKAGE_SHA256` / `HTX_PYTHON_SOURCE_COMMIT`

正式满窗 G2 三件套路径 **并行保留**（`HTX_TREND_LIVE_APPROVAL_PATH` + `HTX_TREND_APPROVAL_CONTEXT_PATH` + admission LIVE 面）。

## 基线冻包不可装机

- `0.1.7-live-73fe4c1b` · SHA256 `ca57bbf7d9c2ac585c5227ee2c3b8ae4d877806150400d0f2a29db90fa9b95be` — **仅编码基线**，不得装机、不得启 LIVE。

## G2 密封（demo / PRODUCT-DRAFT）

从已 verify 的 LIVE 包生成 **合成** admission + approval + context（**始终** `synthetic:true`，formal LIVE 校验会拒绝）：

```sh
"$PY" release/seal_live.py demo "$TAR" --output /tmp/live-seal-demo --account-id 7
```

产品侧 **PRODUCT-DRAFT**（仍 `synthetic:true`；仅预填运维元数据，**不是**产品真签章，填字段也不会过 formal LIVE）：

```sh
"$PY" release/seal_live.py product-template "$TAR" --output /tmp/live-seal-product \
  --account-id 7 --approved-by 'ops@example' --rollback-point 'RB-2026-10-31' \
  --issued-at '2026-10-31T00:00:00Z' --expires-at '2026-12-31T00:00:00Z'
```

真 `synthetic:false` 密封须由产品/运维基于 **真实** admission 与 PAPER 证据链另行签发；本仓库工具不提供 fixture 冒充路径。

校验：

```sh
"$PY" release/seal_live.py validate --admission /tmp/live-seal-demo/admission.json \
  --approval /tmp/live-seal-demo/approval.json --context /tmp/live-seal-demo/context.json --surface PAPER
```

运行时 env（示例，见 `SEAL_MANIFEST.json` 的 `required_env`）：

- `HTX_TREND_STRATEGY_ADMISSION_PATH`
- `HTX_TREND_LIVE_APPROVAL_PATH`
- `HTX_TREND_APPROVAL_CONTEXT_PATH`
- `HTX_PYTHON_PACKAGE_SHA256` / `HTX_PYTHON_SOURCE_COMMIT`

## DB / `strategy_version`（V2 装机建议，非本 PR 执行）

**本产品 encode 戳不授权改库或切机**；下列步骤仅供 merge 后、**产品另发切机戳**时交运维参考。

| 检查项 | 建议 |
|---|---|
| 实例 | `strategy_runtime_instance.instance_key = htx-trend-live-primary` 绑定 **PRIMARY** 账户；`execution_mode=LIVE` |
| 策略版本 | 运行列 / admission 与包内一致：`strategy_name` / `strategy_version = HTX_TREND_15M_V2`，`timeframe_profile = INTRADAY_15M` |
| 从 legacy 迁移 | 若仍为 `HTX_TREND_V1` 或旧 fingerprint：在维护窗更新实例策略名、写入与 LIVE 包 `config_fingerprint` 一致的 `config_fingerprint`，并替换 G2 三件套路径 env |
| SQL 参考 | 仓库内仅有 draft 示例（如 `deployment/paper-4h/insert-strategy-runtime-instance.sql.draft`）；**勿**在本 PR 直接执行；运维按环境写正式变更单 |
| 回滚点 | 保留改前实例行 + 旧包 SHA256 + 旧 admission/approval/context 路径备份；回滚时恢复 env 三路径与 `HTX_PYTHON_*` 绑定，不在此 PR 自动回滚 |

## 授权引用

- Path B product stamp: `reports/product-stamp-live15m-path-B-waiver-encode-20261031.md`
- Path B dev order: `reports/dev-order-live15m-path-B-waiver-encode-20261031.md`
- Path B spec: `reports/spec-product-live-waiver-seal-B-20261031.full.md`
- Path A stamp（历史 encode；**不可切机**）: `reports/product-stamp-live15m-path-A-encode-20261031.md`

**本 PR = 路径 B encode + 交审材料**；≠ merge ≠装机 ≠启 LIVE ≠ build-live（待产品 Pass）。

## 开仓修复候选
限价、净R、重入及回滚条件见 [15m开仓修复说明](../../docs/htx-trend-python/entry-fixes-20261102.md)。订单类型必须按实际env确认；未配置仍默认market。`structure-cost-v1`为显式价格/质量合同，未自动部署或标定净R阈值。新仓保存策略归属；旧无标签仓按baseline管理，关闭新入场不改变已开仓归属。
