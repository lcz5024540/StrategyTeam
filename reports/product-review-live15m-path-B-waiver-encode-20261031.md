# 交审回执 · LIVE-15m 路径 B ProductLiveWaiver encode

- **分支**: `cursor/live15m-path-b-waiver-049a` → `support_htx_python`
- **基线 tip**: `73fe4c1b9b5c4a42401d062e9d2b2835c093c95a`
- **基线冻包**: `0.1.7-live-73fe4c1b` · SHA256 `ca57bbf7d9c2ac585c5227ee2c3b8ae4d877806150400d0f2a29db90fa9b95be` · **不可装机**

## 授权文书 SHA256（全文）

| 文件 | SHA256 |
|---|---|
| `reports/spec-product-live-waiver-seal-B-20261031.full.md` | `7397915041587798e94df8f2c4dee200811d9fab21e1da4d7de6906be7554570` |
| `reports/product-stamp-live15m-path-B-waiver-encode-20261031.md` | `3a1adf9725e451c72aec8cb73934698c6c9cc76854feeb1538c0080bf4df4303` |
| `reports/dev-order-live15m-path-B-waiver-encode-20261031.md` | `b8567b23111ce8703cdee03fdd4dd235003a5a81afccb2265db400e499312cb8` |

## 对齐清单（7 行）

| # | 项 | 结论 |
|---|---|---|
| 1 | 开单主路径 | **已对齐/已补齐**：waiver 旁路下 entry 仍走 `LiveTrendWorker` scan + `HtxTrendEntryExecutor.submit`；每次 entry 重载 `runtime_guard` |
| 2 | 出场 confirm-v1 | **保持** |
| 3 | 杠杆 10 | **交审/装机须设** `HTX_TREND_LEVERAGE=10`（代码默认 5 保留） |
| 4 | 活跃仓上限 | **故意不对齐**：LIVE 硬限 3 |
| 5 | reentry_gate | **confirm-v1 下非 PAPER 生效条件**；LIVE 未接 linked 再入 |
| 6 | cooldown | **故意不对齐**（confirm-v1 默认 false 行为等同） |
| 7 | ProductLiveWaiver 枝 | **已实现**；与 G2 三件套双挂 fail-closed |

## 是否仍含旧 G2 正式路径

**是** — `runtime_guard` 无 waiver 时仍走 `validate_approval` 正式满窗路径。

## waiver 测试命令

```sh
cd htx-trend-python
export PYTHONPATH=src
python3 -m pytest tests/test_product_live_waiver.py -q
python3 -m pytest tests/test_g2_artifacts.py tests/test_live_worker.py tests/test_live_entry_parity.py -q
```

## 新冻包

**本 PR 未执行 `build-live`**（等产品 Pass 交审后再出包）。

## 硬声明

≠ merge 自决 · ≠装机 · ≠启 LIVE · ≠停 PAPER-15m · ≠动 PAPER-4H · ≠把 admission `synthetic:true` 改字为 false
