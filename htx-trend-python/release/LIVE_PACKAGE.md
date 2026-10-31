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

## 15m continuation（LIVE）

```sh
export HTX_LIVE_CONTINUATION_VERSION=confirm-v1   # baseline-v4 | confirm-v1 | linked-v1
```

PAPER 仍只读 `HTX_PAPER_CONTINUATION_VERSION`；LIVE **不会**单独读取 PAPER 变量。详见 [CONTINUATION.md](CONTINUATION.md)。

## G2 密封（demo / 运维）

从已 verify 的 LIVE 包生成 **合成** admission + approval + context（默认 `synthetic:true`，formal LIVE 校验会拒绝）：

```sh
"$PY" release/seal_live.py demo "$TAR" --output /tmp/live-seal-demo --account-id 7
```

产品侧模板（approval 层 `synthetic:false`，仍需真实 admission/PAPER 证据才能过 formal LIVE）：

```sh
"$PY" release/seal_live.py product-template "$TAR" --output /tmp/live-seal-product \
  --account-id 7 --approved-by 'ops@example' --rollback-point 'RB-2026-10-31' \
  --issued-at '2026-10-31T00:00:00Z' --expires-at '2026-12-31T00:00:00Z'
```

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

## 授权引用

- Product stamp: `reports/product-stamp-live15m-path-A-encode-20261031.md` SHA256 `ada77a6d5bb45bd0a53b788867af7c1c0245c8c437ff469dece84beb9dd061af`
- Dev order: `reports/dev-order-live15m-path-A-encode-20261031.md` SHA256 `afd13f7e928b9f278cfec2a1be3c67efb8b3e4343976540be2b27ef3a4792d11`

上述 stamp **仅授权 encode + LIVE 包路径**，不包含 install/start LIVE。
