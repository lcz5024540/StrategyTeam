# R0001 正式操作手册（v0.1.0 = f0e7b49c，包不变）

> 状态：已定稿（操作侧）  |  日期：2026-10-12  |  包不改

产品冻结：

- 提交：`0e54ec240b8f34a541948cbfbf7a3d898913d1ef`
- Tree：`3b13d3d1544324a8d50c4d0c2def9dee9c511da5`
- 包：`htx-trend-python-0.1.0-rc.13-0e54ec24.tar.gz`
- SHA256：`f0e7b49c646e03d3d5af4a61fbb566e3cb7c143dc9ae8a45282ef3c92d267f30`（**不重打**）
- `PACKAGE_CONTENT_HASH`：`42fcfaa58461ad23e7beadb2aff16f2f9088e1ab0d5d3a059f30e017203c75c8`
- 包内 `R0001.yaml` 不改；外置配置
- 不做 rc.14；行研 bot 审完之前不 merge、不打 tag

本手册与脚本、证据同目录：`reports/v0.1.0-ops/`（相对仓库根）。干净环境实跑输出在 `runbook_cli_evidence/`；门禁反例在 `gate_test/`。复现：

```bash
# 在仓库根，cwd 不得是包树
python3 reports/v0.1.0-ops/generate_quality_flag_gate.py \
  --tarball /opt/cursor/artifacts/htx-trend-python-0.1.0-rc.13-0e54ec24.tar.gz \
  --work-dir /tmp/htx-rc13-clean \
  --out-dir reports/v0.1.0-ops
```

```text
OPS=reports/v0.1.0-ops
PKG=/opt/coinstrategy/htx-trend-py/current
VENV=/opt/coinstrategy/htx-trend-py-venv
STATE=/opt/coinstrategy/htx-trend-py-state
PY="$VENV/bin/python"
export PYTHONPATH="$PKG/src"
EXT_CFG=$STATE/configs/R0001.yaml
EVID=$STATE/evidence/R0001
TAR=htx-trend-python-0.1.0-rc.13-0e54ec24.tar.gz
```

不要把新 YAML / 脚本写进 `$PKG`。kline-gap 清单是数据层草稿，**不在包内**，正式路径 `$STATE/ops/kline_gaps.yaml`（模板：`$OPS/kline_gaps.yaml`，`gaps: []`，行情分析bot 维护、产品批准）。门禁 `--kline-gaps` **必填**。

## 硬编码官方网格（BTC-USDT 与 ETH-USDT 相同）

| 项 | 值 |
| --- | --- |
| 品种（硬编码，不从 manifest 读回来比） | `BTC-USDT,ETH-USDT` |
| grid start | `2020-01-01T00:00:00Z`（Binance 从 2020-01-01，stitch `2020-11-01T00:00:00Z`，之后 HTX） |
| cutoff（不含；只收已收盘 bar） | `2026-10-12T00:00:00Z` |
| last open | `2026-10-11T20:00:00Z` |
| expected rows | `14856` = `(cutoff-start)/4h` |
| Binance 段 | `2020-01-01T00:00:00Z` .. `2020-10-31T20:00:00Z`，**1830** 行 |
| HTX 段 | `2020-11-01T00:00:00Z` .. `2026-10-11T20:00:00Z`，**13026** 行 |
| external True | **1830** |
| non_comparable True | **61** |
| bad_tick True | BTC **6** / ETH **5** |
| kline-gaps | `gaps: []`（模板 sha256 `d4fd7c6e49c9de733a361cac044110aa59909d3c6afafe4718649ab4b8f00161`） |

`--train-ratio 0.6 --val-ratio 0.2`：**要写**。两个 start 同时给出时 `compute_split_boundaries` **忽略** ratio（`dataset.py:114-124`）。

`--workspace` 是根 parser 全局参数（`cli.py:590-595`），必须写在子命令 **前面**。写成 `freeze --workspace` 会被拒绝（`runbook_cli_evidence/workspace_after_subcommand.txt`）。

---

## 0. 核包 + 干净环境

```bash
sha256sum -c "$TAR.sha256"
# 必须等于 f0e7b49c646e03d3d5af4a61fbb566e3cb7c143dc9ae8a45282ef3c92d267f30
# 解压到 $PKG。cwd 必须在包树外（不要 cd $PKG）。STATE、venv 与 PKG 分离。
mkdir -p "$STATE" "$VENV" "$EVID" "$STATE/configs" "$STATE/ops"
cp -a "$OPS/kline_gaps.yaml" "$STATE/ops/kline_gaps.yaml"
```

## 0.1 断言 STATE 存在且不是当前目录

```bash
test -d "$STATE" || { echo "STATE missing: $STATE" >&2; exit 1; }
if [ "$(realpath "$STATE")" = "$(realpath "$PWD")" ]; then
  echo "STATE must not be the current directory: $STATE" >&2
  exit 1
fi
case "$(realpath "$PWD")" in
  "$(realpath "$PKG")"|"$(realpath "$PKG")"/*)
    echo "cwd must not be inside the package tree" >&2
    exit 1
    ;;
esac
```

## 1. 冻结前：质量文件 sha + 硬编码品种 + raw 四文件对照期望

品种必须对照**硬编码**列表 `BTC-USDT,ETH-USDT`（不要把变量和自己比）。缺一即停。

```bash
echo "08b52442ca4a60cf2d95a9e047b8f602a3d753a73a2832c86b1f81276fdbd221  $PKG/data/quality/funding_gaps.yaml" \
  | sha256sum -c -
echo "09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b  $PKG/data/quality/bad_ticks.yaml" \
  | sha256sum -c -

EXPECTED_SYMBOLS="BTC-USDT ETH-USDT"
for symbol in $EXPECTED_SYMBOLS; do
  test -f "$STATE/data/raw/${symbol}_4hour_htx.parquet" \
    || { echo "missing HTX raw for $symbol" >&2; exit 1; }
  bn="$(printf '%s' "$symbol" | tr -d '-')"
  test -f "$STATE/data/raw/${bn}_4h_binance.parquet" \
    || { echo "missing Binance raw for $symbol" >&2; exit 1; }
done

"$PY" - <<'PY' | tee "$EVID/raw_inventory.txt"
from pathlib import Path
import hashlib, sys
import pandas as pd

root = Path("/opt/coinstrategy/htx-trend-py-state/data/raw")
expected = {
    "BTC-USDT_4hour_htx.parquet": ("2020-11-01T00:00:00Z", "2026-10-11T20:00:00Z", 13026),
    "ETH-USDT_4hour_htx.parquet": ("2020-11-01T00:00:00Z", "2026-10-11T20:00:00Z", 13026),
    "BTCUSDT_4h_binance.parquet": ("2020-01-01T00:00:00Z", "2020-10-31T20:00:00Z", 1830),
    "ETHUSDT_4h_binance.parquet": ("2020-01-01T00:00:00Z", "2020-10-31T20:00:00Z", 1830),
}
errors = []
for name, (exp_min, exp_max, exp_rows) in expected.items():
    path = root / name
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    times = pd.to_datetime(pd.read_parquet(path)["bar_time"], utc=True)
    got_min = times.min().strftime("%Y-%m-%dT%H:%M:%SZ")
    got_max = times.max().strftime("%Y-%m-%dT%H:%M:%SZ")
    print(f"{name} sha256={digest} rows={len(times)} min={got_min} max={got_max}")
    if times.duplicated().any():
        errors.append(f"{name}: duplicate bar_time")
    if len(times) != exp_rows or got_min != exp_min or got_max != exp_max:
        errors.append(
            f"{name}: got rows={len(times)} {got_min}..{got_max} "
            f"expected rows={exp_rows} {exp_min}..{exp_max}"
        )
if errors:
    print("RAW MISMATCH", *errors, sep="\n", file=sys.stderr)
    raise SystemExit(1)
print("raw segments match official expectations")
PY
```

消费的 raw 是 collect 形态（含 `source`），**无原生旗标列，这是预期**（选项 A）。缺 `source` 列门禁失败，不得填 htx/binance。

## 1.1 正式开跑前：从真实 raw 重算 kline-gaps 仍为 `[]`

任何与模板/`14856` 不一致 ⇒ **停**，上报产品。通过后把 sha256 写入证据。

```bash
"$PY" - <<'PY' | tee "$EVID/kline_gaps_recompute.txt"
from pathlib import Path
import hashlib, sys
import pandas as pd
import yaml

state = Path("/opt/coinstrategy/htx-trend-py-state")
gaps_path = state / "ops" / "kline_gaps.yaml"
payload = yaml.safe_load(gaps_path.read_text())
if payload.get("gaps") != []:
    raise SystemExit(f"kline_gaps.yaml gaps is not []: {payload.get('gaps')!r}")

start = pd.Timestamp("2020-01-01T00:00:00Z")
cutoff = pd.Timestamp("2026-10-12T00:00:00Z")
stitch = pd.Timestamp("2020-11-01T00:00:00Z")
grid = pd.date_range(start, cutoff - pd.Timedelta(hours=4), freq="4h", tz="UTC")
if len(grid) != 14856:
    raise SystemExit(f"grid size {len(grid)} != 14856")

holes = {}
for symbol in ("BTC-USDT", "ETH-USDT"):
    htx = pd.read_parquet(state / "data" / "raw" / f"{symbol}_4hour_htx.parquet")
    bn = pd.read_parquet(state / "data" / "raw" / f"{symbol.replace('-', '')}_4h_binance.parquet")
    htx["bar_time"] = pd.to_datetime(htx["bar_time"], utc=True)
    bn["bar_time"] = pd.to_datetime(bn["bar_time"], utc=True)
    times = pd.concat([
        bn.loc[bn["bar_time"] < stitch, "bar_time"],
        htx.loc[htx["bar_time"] >= stitch, "bar_time"],
    ])
    have = set(times.dt.strftime("%Y-%m-%dT%H:%M:%SZ"))
    missing = [ts.strftime("%Y-%m-%dT%H:%M:%SZ") for ts in grid if ts.strftime("%Y-%m-%dT%H:%M:%SZ") not in have]
    if missing:
        holes[symbol] = missing[:8]
if holes:
    print("UNDECLARED KLINE GAPS", holes, file=sys.stderr)
    raise SystemExit("report to product: raw holes vs official empty kline_gaps.yaml")
digest = hashlib.sha256(gaps_path.read_bytes()).hexdigest()
print(f"kline_gaps.yaml sha256={digest} gaps=[] expected_rows=14856 unchanged")
PY
sha256sum "$STATE/ops/kline_gaps.yaml" | tee "$EVID/kline_gaps.yaml.sha256"
```

## 2. Freeze（全局 --workspace 在前；cwd 在包树外）

```bash
sudo -u ubuntu env PYTHONPATH="$PKG/src" \
  "$PY" -m htx_trend.research --workspace "$STATE" freeze \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --train-ratio 0.6 \
  --val-ratio 0.2 \
  --backfill-binance \
  --overlap-threshold 0.003 \
  --cutoff 2026-10-12T00:00:00Z \
  --validation-start 2024-01-25T00:00:00Z \
  --test-start 2025-06-03T00:00:00Z \
  --non-comparable-window 30 \
  --stitch-config "$PKG/data/quality/stitch_points.yaml" \
  --stitch-point BTC-USDT=2020-11-01T00:00:00Z \
  --stitch-point ETH-USDT=2020-11-01T00:00:00Z \
  --bad-ticks "$PKG/data/quality/bad_ticks.yaml" \
  --funding-gaps "$PKG/data/quality/funding_gaps.yaml"
```

记下 `Dataset frozen: <dataset_id>` 和 `manifest_hash`。

```bash
cat > "$EVID/freeze_args.txt" <<EOF
workspace_position=BEFORE_subcommand
cwd_must_not_be_package=1
cutoff=2026-10-12T00:00:00Z
validation_start=2024-01-25T00:00:00Z
test_start=2025-06-03T00:00:00Z
train_ratio=0.6
val_ratio=0.2
train_val_ratio_ignored_when_starts_present=1
non_comparable_window=30
contracts=BTC-USDT,ETH-USDT
hard_coded_symbols=BTC-USDT,ETH-USDT
stitch_BTC-USDT=2020-11-01T00:00:00Z
stitch_ETH-USDT=2020-11-01T00:00:00Z
bad_ticks=$PKG/data/quality/bad_ticks.yaml
bad_ticks_sha256=09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b
funding_gaps=$PKG/data/quality/funding_gaps.yaml
funding_gaps_sha256=08b52442ca4a60cf2d95a9e047b8f602a3d753a73a2832c86b1f81276fdbd221
kline_gaps=$STATE/ops/kline_gaps.yaml
EOF
```

夹具 freeze（cutoff `2022-05-01T00:00:00Z`）日志：`runbook_cli_evidence/freeze.txt`（cwd = work-dir，**不是**包目录）。

## 3. 冻结后、开跑前：manifest + 质量旗标门禁

manifest 品种对照**硬编码** `{BTC-USDT, ETH-USDT}`，不要用 manifest 和自己比。

```bash
DATASET_DIR="$STATE/data/datasets/<dataset_id>"
"$PY" - <<PY
import json, sys
from pathlib import Path
HARD = ["BTC-USDT", "ETH-USDT"]
man = json.loads(Path("$DATASET_DIR/manifest.json").read_text())
syms = list(man.get("symbols") or [])
if sorted(syms) != sorted(HARD):
    sys.exit(f"manifest symbols {syms} != hard-coded {HARD}")
if man.get("funding_gaps_hash") != "sha256:08b52442ca4a60cf2d95a9e047b8f602a3d753a73a2832c86b1f81276fdbd221":
    sys.exit(f"funding_gaps_hash {man.get('funding_gaps_hash')}")
if man.get("bad_ticks_hash") != "sha256:09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b":
    sys.exit(f"bad_ticks_hash {man.get('bad_ticks_hash')}")
print("manifest symbols + quality hashes ok")
PY

cp -a "$OPS/check_frozen_quality_flags.py" "$STATE/ops/check_frozen_quality_flags.py"

"$PY" -I -B "$STATE/ops/check_frozen_quality_flags.py" \
  --dataset-dir "$DATASET_DIR" \
  --raw-dir "$STATE/data/raw" \
  --contracts BTC-USDT,ETH-USDT \
  --timeframe 4hour \
  --cutoff 2026-10-12T00:00:00Z \
  --window-bars 30 \
  --stitch-config "$PKG/data/quality/stitch_points.yaml" \
  --stitch-point BTC-USDT=2020-11-01T00:00:00Z \
  --stitch-point ETH-USDT=2020-11-01T00:00:00Z \
  --backfill-binance \
  --bad-ticks "$PKG/data/quality/bad_ticks.yaml" \
  --expected-bad-ticks-sha256 09e1ed52105909f8c9810a94ecc0cfdfb667bc2f5072ca0419c108965a197a4b \
  --expected-bad-ticks BTC-USDT=6 \
  --expected-bad-ticks ETH-USDT=5 \
  --grid-start BTC-USDT=2020-01-01T00:00:00Z \
  --grid-start ETH-USDT=2020-01-01T00:00:00Z \
  --expected-rows BTC-USDT=14856 \
  --expected-rows ETH-USDT=14856 \
  --expected-external BTC-USDT=1830 \
  --expected-external ETH-USDT=1830 \
  --expected-non-comparable BTC-USDT=61 \
  --expected-non-comparable ETH-USDT=61 \
  --expected-last-open BTC-USDT=2026-10-11T20:00:00Z \
  --expected-last-open ETH-USDT=2026-10-11T20:00:00Z \
  --expected-htx-rows BTC-USDT=13026 \
  --expected-htx-rows ETH-USDT=13026 \
  --expected-binance-rows BTC-USDT=1830 \
  --expected-binance-rows ETH-USDT=1830 \
  --expected-htx-start BTC-USDT=2020-11-01T00:00:00Z \
  --expected-htx-start ETH-USDT=2020-11-01T00:00:00Z \
  --expected-htx-end BTC-USDT=2026-10-11T20:00:00Z \
  --expected-htx-end ETH-USDT=2026-10-11T20:00:00Z \
  --expected-binance-start BTC-USDT=2020-01-01T00:00:00Z \
  --expected-binance-start ETH-USDT=2020-01-01T00:00:00Z \
  --expected-binance-end BTC-USDT=2020-10-31T20:00:00Z \
  --expected-binance-end ETH-USDT=2020-10-31T20:00:00Z \
  --kline-gaps "$STATE/ops/kline_gaps.yaml" \
  --package-tar "$TAR" \
  --package-sha256 f0e7b49c646e03d3d5af4a61fbb566e3cb7c143dc9ae8a45282ef3c92d267f30 \
  --evidence "$EVID/quality_flags.json"
```

`--package-sha256` 由脚本**实际哈希 tar**核对，不是只记下来。`--kline-gaps` 必填。先断言 raw / frozen 两侧 `(symbol, bar_time)` 唯一，再做集合比较。

夹具用自己的声明值（start `2020-09-01T00:00:00Z`，rows `3642`）。复现全部反例（含 duplicated bar）：

```bash
python3 reports/v0.1.0-ops/generate_quality_flag_gate.py \
  --tarball /opt/cursor/artifacts/htx-trend-python-0.1.0-rc.13-0e54ec24.tar.gz \
  --work-dir /tmp/htx-rc13-clean \
  --out-dir reports/v0.1.0-ops
```

object-dtype 反例已放弃：parquet 往返会收成 bool；dtype 用例以字符串 `'True'`/`'False'` 为准。

## 4. 外置 R0001（只改 4 个产品字段 + runner 必需的 sha）

```bash
cp -a "$PKG/configs/runs/R0001.yaml" "$EXT_CFG"
# 只改 dataset_id / manifest_hash（freeze stdout）。
# 限域 diff 只允许：test_start / test_start_utc / cutoff / data_cutoff_close_utc / dataset_id / manifest_hash
```

包内 `--check` 必须仍是 `306a17eb…`（对着**包内** R0001）：

```bash
sudo -u ubuntu env PYTHONPATH="$PKG/src" \
  "$PY" -m htx_trend.research --workspace "$STATE" strategy-manifest \
  --config "$PKG/configs/runs/R0001.yaml" \
  --check
grep -F "306a17eb4e2b4e86d9be2a2380f3f11a7840c9fd4ad681c0e68384496815c45c" \
  "$PKG/configs/runs/R0001.yaml"
```

外置填完 id 后重建 sha，写回外置文件，不要 `--write` 回包。

```bash
"$PY" - <<'PY' | tee "$EVID/r0001_field_diff.txt"
from pathlib import Path
import yaml
pkg = Path("/opt/coinstrategy/htx-trend-py/current/configs/runs/R0001.yaml")
ext = Path("/opt/coinstrategy/htx-trend-py-state/configs/R0001.yaml")
keys = (
    "test_start", "test_start_utc",
    "cutoff", "data_cutoff_close_utc",
    "dataset_id", "manifest_hash",
)
ignore = {"strategy_manifest_sha256"}
a, b = yaml.safe_load(pkg.read_text()), yaml.safe_load(ext.read_text())
extra = (set(a) ^ set(b)) - set(keys) - ignore
if extra:
    raise SystemExit(f"unexpected key diff vs packaged R0001: {sorted(extra)}")
changed = [k for k in keys if a.get(k) != b.get(k)]
print("changed_allowed_fields:", changed)
if str(b.get("dataset_id", "")).startswith("TODO") or str(b.get("manifest_hash", "")).startswith("TODO"):
    raise SystemExit("external config still has TODO_FREEZE placeholders")
if a.get("strategy_manifest_sha256") == b.get("strategy_manifest_sha256"):
    raise SystemExit("external strategy_manifest_sha256 still packaged 306a17eb; rebuild after fill")
if a.get("strategy_manifest_sha256") != "306a17eb4e2b4e86d9be2a2380f3f11a7840c9fd4ad681c0e68384496815c45c":
    raise SystemExit("packaged R0001 strategy_manifest_sha256 drifted")
print("ok field-limited diff")
PY
cp -a "$EXT_CFG" "$EVID/R0001.yaml"
```

## 5. chmod 444 + sha 记录

```bash
chmod 444 "$EXT_CFG"
sha256sum "$EXT_CFG" | tee "$EVID/R0001.yaml.sha256"
```

## 6. Run（--workspace 在 `run` 前面）

```bash
sudo -u ubuntu env PYTHONPATH="$PKG/src" \
  "$PY" -m htx_trend.research --workspace "$STATE" run \
  "$EXT_CFG" \
  --funding-gaps "$PKG/data/quality/funding_gaps.yaml"
```

`run` 的 `--funding-gaps` 在 rc.13 可省略（P1-1）。正式跑必须显式传入。

## 7. 跑后 sha 复核 + ledger

```bash
sha256sum -c "$EVID/R0001.yaml.sha256"
sqlite3 "$STATE/ledger.db" \
  "SELECT run_id, package_content_hash, integrity_verified FROM runs WHERE run_id='R0001';"
```

必须是：

```text
R0001|42fcfaa58461ad23e7beadb2aff16f2f9088e1ab0d5d3a059f30e017203c75c8|1
```

---

## 附录：本目录产物（均在包外）

| 文件 | 作用 |
| --- | --- |
| `check_frozen_quality_flags.py` | 冻结后门禁 |
| `generate_quality_flag_gate.py` | 路径参数；一条命令复现全部用例 |
| `kline_gaps.yaml` | 数据层草稿，`gaps: []` |
| `gate_test/` | 好样本 + 反例证据 |
| `runbook_cli_evidence/` | 干净环境实跑输出 |
| `v0.1.1-defaults-fallbacks.md` | 默认/回落清单（**不在** `PACKAGE_GLOBS`） |
