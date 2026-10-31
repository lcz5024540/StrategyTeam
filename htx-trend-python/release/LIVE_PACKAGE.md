# LIVE encode package (htx-trend-live-primary)

**LIVE tarball ≠ 密封 admission/approval ≠ 启动交易**。本路径仅产出可审计的 LIVE 代码包与绑定字段，不安装、不启动实例。

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

**当前 LIVE 生效范围（本 PR）：**

- **Exit / 持仓管理**：`HTX_LIVE_CONTINUATION_VERSION=confirm-v1`（或 `linked-v1`）在 `LivePositionManager` 对 **15m `HTX_TREND_15M_V2`** 持仓走 `advance_confirmation` / `decide_continuation`。
- **Entry / 入场**：**未**接入 PAPER 的 `reentry_gate` / linked 再入逻辑；`HTX_LIVE_CONTINUATION_VERSION=linked-v1` 在 LIVE **不会**改变入场闸门（与 PAPER 全量不对齐）。
- **Cooldown（B 组）**：仅 PAPER（`HTX_PAPER_COMPLETED_TRADE_COOLDOWN`）；LIVE 无对应 env。

```sh
export HTX_LIVE_CONTINUATION_VERSION=confirm-v1   # baseline-v4 | confirm-v1 | linked-v1
```

PAPER 仍只读 `HTX_PAPER_CONTINUATION_VERSION`；LIVE **不会**单独读取 PAPER 变量。详见 [CONTINUATION.md](CONTINUATION.md) 开关表。

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

- Product stamp: `reports/product-stamp-live15m-path-A-encode-20261031.md` SHA256 `ada77a6d5bb45bd0a53b788867af7c1c0245c8c437ff469dece84beb9dd061af`
- Dev order: `reports/dev-order-live15m-path-A-encode-20261031.md` SHA256 `afd13f7e928b9f278cfec2a1be3c67efb8b3e4343976540be2b27ef3a4792d11`

上述 stamp **仅授权 encode + LIVE 包路径**，不包含 install/start LIVE。
